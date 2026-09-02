package com.codereview.agent.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一模型网关（多厂商 + Quota 配额 + Failover 失败转移）。
 *
 * <p>实现 {@link LlmClient}，对上层（各审查 Agent）屏蔽底层厂商差异：
 * <ul>
 *   <li><b>多厂商路由</b>：按配置顺序选择可用供应商（混元 / OpenAI 兼容 / Azure / Mock）；</li>
 *   <li><b>配额</b>：每个供应商设定时间窗内最大调用次数，超限自动降级到下一供应商；</li>
 *   <li><b>失败转移</b>：某供应商抛异常时，立即切换下一供应商，保证审查链路不中断；</li>
 *   <li><b>兜底</b>：Mock 供应商永远可用，作为链路终点（可开关，见 {@code allowMockFallback}）。</li>
 * </ul>
 *
 * <p><b>失败必须显式（0.1.1 修复）</b>：此前所有供应商都失败时会 {@code return ""}，
 * 上层无法区分「模型返回空」与「模型不可用」，于是解析出 0 条发现并产出「看起来通过」的报告。
 * 现在改为抛出 {@link ModelUnavailableException}，由协调器把该 Agent 标记为降级并在报告中标注。
 * 静默失败比显式报错危险得多。
 *
 * <p><b>降级观测</b>：每次走 Mock 兜底或最终失败都会累加计数，可通过 {@link #degradationStats()}
 * 读取，用于健康检查与告警。
 */
public class ModelGateway implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);

    /**
     * 配额窗口（秒）。
     */
    private static final long WINDOW_SECONDS = 60;

    /** 兜底供应商的固定名称（约定俗成，用于识别 Mock）。 */
    private static final String MOCK_PROVIDER = "mock";

    private final List<ModelProvider> providers;
    private final int maxPerWindow;
    /** 是否允许在所有供应商失败后降级到 Mock（关闭后失败即抛异常，更适合生产环境）。 */
    private final boolean allowMockFallback;
    private final java.util.Map<String, QuotaState> quotas = new java.util.concurrent.ConcurrentHashMap<>();

    /** 走 Mock 兜底的次数（显式降级，结果不可信）。 */
    private final AtomicLong mockFallbacks = new AtomicLong();
    /** 彻底失败的次数（连兜底都没有 / 兜底也失败）。 */
    private final AtomicLong totalFailures = new AtomicLong();

    /**
     * 向后兼容构造：允许 Mock 兜底（与修复前行为一致，仅把「返回空串」改为「抛异常」）。
     *
     * @param providers    供应商列表（按顺序优先）
     * @param maxPerWindow 每窗口最大调用次数
     */
    public ModelGateway(List<ModelProvider> providers, int maxPerWindow) {
        this(providers, maxPerWindow, true);
    }

    /**
     * @param providers        供应商列表（按顺序优先）
     * @param maxPerWindow     每窗口最大调用次数
     * @param allowMockFallback 所有供应商失败后是否允许降级到名为 {@code mock} 的供应商。
     *                          生产建议 {@code false}——宁可让该 Agent 降级，也不要拿假结果充数
     */
    public ModelGateway(List<ModelProvider> providers, int maxPerWindow, boolean allowMockFallback) {
        this.providers = providers;
        this.maxPerWindow = maxPerWindow;
        this.allowMockFallback = allowMockFallback;
    }

    /**
     * 发送提示词并获取模型输出。
     *
     * @param prompt 提示词
     * @return 模型输出
     * @throws ModelUnavailableException 所有供应商（含兜底）均失败时抛出，绝不静默返回空串
     */
    @Override
    public String chat(String prompt) {
        long t0 = System.currentTimeMillis();
        int attempt = 0;
        Throwable lastFailure = null;
        for (ModelProvider p : providers) {
            if (!p.available()) {
                continue;
            }
            if (quotaExceeded(p.name())) {
                log.debug("[模型网关] 供应商 {} 配额耗尽，跳过", p.name());
                continue;
            }
            attempt++;
            try {
                log.debug("[模型网关] 尝试供应商[{}]（第 {} 个可用），prompt={}字符",
                        p.name(), attempt, prompt.length());
                String result = p.chat(prompt);
                incQuota(p.name());
                long cost = System.currentTimeMillis() - t0;
                log.info("[模型网关] LLM 调用成功：供应商={}, 输出={}字符, 尝试次数={}, 耗时 {}ms",
                        p.name(), result == null ? 0 : result.length(), attempt, cost);
                return result;
            } catch (Exception e) {
                lastFailure = e;
                log.warn("[模型网关] 供应商[{}] 调用失败，失败转移至下一供应商：{}（已耗时 {}ms）",
                        p.name(), e.getMessage(), System.currentTimeMillis() - t0);
            }
        }
        long cost = System.currentTimeMillis() - t0;
        log.error("[模型网关] 所有可用供应商均失败（prompt 长度={}, 已尝试 {} 个/共 {} 个，耗时 {}ms）",
                prompt.length(), attempt, providers.size(), cost);

        // 兜底：Mock 仅在显式允许时使用（理论上 providers 末尾已包含 mock）
        if (allowMockFallback) {
            ModelProvider mock = providers.stream()
                    .filter(p -> MOCK_PROVIDER.equals(p.name()))
                    .findFirst()
                    .orElse(null);
            if (mock != null) {
                try {
                    String fallback = mock.chat(prompt);
                    mockFallbacks.incrementAndGet();
                    // WARN 而非 DEBUG：走兜底意味着本次结论不可信，必须留下醒目痕迹
                    log.warn("[模型网关] 已降级到 Mock 兜底（累计 {} 次），本次结论不可信，prompt 长度={}",
                            mockFallbacks.get(), prompt.length());
                    return fallback;
                } catch (Exception e) {
                    lastFailure = e;
                    log.error("[模型网关] Mock 兜底也失败：{}", e.getMessage());
                }
            }
        }

        totalFailures.incrementAndGet();
        String reason = String.format(
                "所有供应商均不可用（已尝试 %d 个 / 共 %d 个，Mock 兜底%s，累计失败 %d 次）",
                attempt, providers.size(), allowMockFallback ? "亦失败或未配置" : "已关闭",
                totalFailures.get());
        // 不再 return ""：静默空串会让上层误判为「无发现」，进而产出通过态的假报告
        if (lastFailure == null) {
            throw new ModelUnavailableException(reason, providers.size(), attempt);
        }
        throw new ModelUnavailableException(reason, providers.size(), attempt, lastFailure);
    }

    /**
     * 当前实际使用的供应商（供监控/日志）。
     */
    public String describe() {
        StringBuilder sb = new StringBuilder("ModelGateway[");
        for (ModelProvider p : providers) {
            sb.append(p.name()).append(p.available() ? "(on)" : "(off)").append(",");
        }
        return sb.append("]").toString();
    }

    /**
     * 降级统计快照（供健康检查 / 告警）。
     *
     * @param mockFallbacks 走 Mock 兜底的次数（结论不可信）
     * @param totalFailures 彻底失败的次数
     */
    public record DegradationStats(long mockFallbacks, long totalFailures) {
        /** 是否发生过任何形式的降级。 */
        public boolean degraded() {
            return mockFallbacks > 0 || totalFailures > 0;
        }
    }

    /** 读取降级统计（累积值，进程内）。 */
    public DegradationStats degradationStats() {
        return new DegradationStats(mockFallbacks.get(), totalFailures.get());
    }

    private boolean quotaExceeded(String name) {
        QuotaState qs = quotas.computeIfAbsent(name, k -> new QuotaState());
        long now = Instant.now().getEpochSecond();
        synchronized (qs) {
            if (now - qs.windowStart > WINDOW_SECONDS) {
                qs.count.set(0);
                qs.windowStart = now;
            }
            return qs.count.get() >= maxPerWindow;
        }
    }

    private void incQuota(String name) {
        QuotaState qs = quotas.computeIfAbsent(name, k -> new QuotaState());
        qs.count.incrementAndGet();
    }

    private static final class QuotaState {
        final AtomicInteger count = new AtomicInteger(0);
        long windowStart = Instant.now().getEpochSecond();
    }
}
