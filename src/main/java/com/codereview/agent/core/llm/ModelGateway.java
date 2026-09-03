package com.codereview.agent.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 统一模型网关（多厂商 + Quota 配额 + Failover 失败转移 + CircuitBreaker 熔断 +
 * Retry-With-Backoff 退避重试 + 可插拔路由策略）。
 *
 * <p><b>核心职责</b>：对上层（各审查 Agent）屏蔽底层厂商差异：
 * <ul>
 *   <li><b>多厂商路由</b>：由 {@link RouteStrategy} 选择下一个目标（默认按列表顺序，
 *       自动跳过熔断器 OPEN 中的供应商）；</li>
 *   <li><b>配额</b>：每个供应商设定时间窗内最大调用次数，超限自动跳过；</li>
 *   <li><b>熔断</b>：每个供应商包 {@link CircuitBreakerProvider}（可选），
 *       连续失败达阈值后 OPEN，期间直接抛 {@link CircuitOpenException} 快速失败；</li>
 *   <li><b>失败转移</b>：永久错误立即切下一供应商；</li>
 *   <li><b>退避重试</b>：临时错误（429/503/timeout 等）在同一供应商上指数退避重试；
 *       超过 {@code maxAttempts} 才切换；</li>
 *   <li><b>Token 用量</b>：每次成功调用通过 {@link TokenUsageRecorder}（可选）累计。 </li>
 * </ul>
 *
 * <p><b>失败必须显式，且无任何 Mock（P0-2 修复 + 2026-09-03 全量去 Mock + 2026-09-03 JD 增强）</b>：
 * 所有供应商（含熔断跳过、配额耗尽、真实调用失败）都试过后，
 * 抛 {@link ModelUnavailableException}，由协调器把该 Agent 标记为降级并在报告中标注。
 *
 * <p><b>降级观测</b>：每次彻底失败计数 + 每个供应商的熔断状态 + Token 用量，
 * 通过 {@link #snapshot()} 读取，供 {@code LlmHealthController} 暴露成 SLA 端点。
 */
public class ModelGateway implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);

    private static final long WINDOW_SECONDS = 60;

    private final List<ModelProvider> providers;
    private final int maxPerWindow;
    private final RouteStrategy routeStrategy;
    private final RetryClassifier retryClassifier;
    private final BackoffPolicy backoffPolicy;
    private final int retryMaxAttempts;
    private final boolean retryEnabled;
    private final TokenUsageRecorder usageRecorder;

    private final Map<String, QuotaState> quotas = new ConcurrentHashMap<>();

    private final AtomicLong totalFailures = new AtomicLong();

    /**
     * @param providers        供应商列表（顺序即默认优先级；建议每个 spec 包一层熔断器）
     * @param maxPerWindow     每窗口最大调用次数
     * @param routeStrategy    路由策略（默认 {@link PriorityRouteStrategy}）
     * @param retryClassifier  失败分类器（默认 {@link DefaultRetryClassifier}）
     * @param backoffPolicy    退避策略（默认 200ms / ×2 / 2s 上限）
     * @param retryMaxAttempts 同供应商最大尝试次数（含首次；1=不退避）
     * @param retryEnabled     是否启用退避重试
     * @param usageRecorder    Token 用量记录器（可为 null 表示不记录）
     */
    public ModelGateway(List<ModelProvider> providers,
                        int maxPerWindow,
                        RouteStrategy routeStrategy,
                        RetryClassifier retryClassifier,
                        BackoffPolicy backoffPolicy,
                        int retryMaxAttempts,
                        boolean retryEnabled,
                        TokenUsageRecorder usageRecorder) {
        this.providers = providers;
        this.maxPerWindow = maxPerWindow;
        this.routeStrategy = routeStrategy == null ? new PriorityRouteStrategy() : routeStrategy;
        this.retryClassifier = retryClassifier == null ? new DefaultRetryClassifier() : retryClassifier;
        this.backoffPolicy = backoffPolicy == null ? new BackoffPolicy(200, 2000, 2.0) : backoffPolicy;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryEnabled = retryEnabled;
        this.usageRecorder = usageRecorder;
    }

    /**
     * 兼容旧构造（无熔断/重试/路由策略/用量记录）。
     */
    public ModelGateway(List<ModelProvider> providers, int maxPerWindow) {
        this(providers, maxPerWindow,
                new PriorityRouteStrategy(), new DefaultRetryClassifier(),
                new BackoffPolicy(200, 2000, 2.0), 1, false, null);
    }

    @Override
    public String chat(String prompt) {
        long t0 = System.currentTimeMillis();
        // 收集「本轮可下发」的供应商（排除不 available / 配额耗尽）
        List<ModelProvider> candidates = collectCandidates();
        if (candidates.isEmpty()) {
            totalFailures.incrementAndGet();
            String reason = String.format(
                    "无可用供应商（已配置 %d 个，全部不可用或配额耗尽，累计失败 %d 次）",
                    providers.size(), totalFailures.get());
            throw new ModelUnavailableException(reason, providers.size(), 0);
        }

        Throwable lastFailure = null;
        int totalAttempts = 0;
        // 策略循环：每轮选下一个 candidate，尝试 +1；本轮失败若是永久错误立刻换下一个；
        // 若是临时错误且 retry 启用，按 backoff 在本 candidate 上重试
        int strategyCursor = 0;
        while (strategyCursor < candidates.size()) {
            ModelProvider p = routeStrategy.next(rotate(candidates, strategyCursor));
            strategyCursor++;
            if (p == null) {
                continue;
            }
            // 配额二次校验（collectCandidates 已过滤；这里防御 available() 状态变化）
            if (quotaExceeded(p.name())) {
                log.debug("[模型网关] 供应商 {} 配额耗尽，跳过", p.name());
                continue;
            }
            int attemptsOnThisProvider = 0;
            int maxForThis = retryEnabled ? retryMaxAttempts : 1;
            while (attemptsOnThisProvider < maxForThis) {
                attemptsOnThisProvider++;
                totalAttempts++;
                try {
                    log.debug("[模型网关] 尝试供应商[{}]（候选位 {}/{}，本供应商第 {} 次），prompt={}字符",
                            p.name(), strategyCursor, candidates.size(), attemptsOnThisProvider, prompt.length());
                    long pStart = System.currentTimeMillis();
                    String result = p.chat(prompt);
                    long pCost = System.currentTimeMillis() - pStart;
                    incQuota(p.name());
                    long cost = System.currentTimeMillis() - t0;
                    log.info("[模型网关] LLM 调用成功：供应商={}, 输出={}字符, 总尝试={}, 耗时 {}ms",
                            p.name(), result == null ? 0 : result.length(), totalAttempts, cost);
                    if (usageRecorder != null) {
                        // Listener 已记录 token 用量；这里仅在缺省监听器场景兜底（不重复 record by caller）
                        // 保留 hook 留作后续按 supplier 维度补 metrics。
                    }
                    return result;
                } catch (CircuitOpenException coe) {
                    // 熔断器 OPEN：跳过本候选，移到下一家（不计入 retry）
                    log.warn("[模型网关] 供应商[{}] 触发熔断（OPEN），跳过：{}", p.name(), coe.getMessage());
                    lastFailure = coe;
                    break;
                } catch (Exception e) {
                    lastFailure = e;
                    boolean retryable = retryClassifier.isRetryable(e);
                    log.warn("[模型网关] 供应商[{}] 调用失败（{}/{}）：{}（retryable={}）",
                            p.name(), attemptsOnThisProvider, maxForThis, e.getMessage(), retryable);
                    if (!retryable || attemptsOnThisProvider >= maxForThis) {
                        // 永久错误 或 已达上限：换下一供应商
                        break;
                    }
                    // 临时错误：本供应商上退避重试
                    long sleepMs = backoffPolicy.backoffMs(attemptsOnThisProvider);
                    log.info("[模型网关] 临时错误退避 {}ms 后重试（供应商 {}）", sleepMs, p.name());
                    backoffPolicy.sleep(sleepMs);
                }
            }
        }

        long totalCost = System.currentTimeMillis() - t0;
        log.error("[模型网关] 所有可用供应商均失败（prompt 长度={}, 已尝试 {} 次，耗时 {}ms）",
                prompt.length(), totalAttempts, totalCost);

        totalFailures.incrementAndGet();
        String reason = String.format(
                "所有供应商均不可用（已尝试 %d 次 / 共 %d 个供应商，累计失败 %d 次，无 Mock 兜底）",
                totalAttempts, providers.size(), totalFailures.get());
        if (lastFailure == null) {
            throw new ModelUnavailableException(reason, providers.size(), totalAttempts);
        }
        throw new ModelUnavailableException(reason, providers.size(), totalAttempts, lastFailure);
    }

    /** 收集「available 且未超配额」的供应商作为本轮候选。 */
    private List<ModelProvider> collectCandidates() {
        List<ModelProvider> ok = new ArrayList<>(providers.size());
        for (ModelProvider p : providers) {
            if (p == null || !p.available()) {
                continue;
            }
            if (quotaExceeded(p.name())) {
                continue;
            }
            ok.add(p);
        }
        return ok;
    }

    /** 从 cursor 起旋转移位（让路由策略从 cursor 开始选择）。 */
    private static List<ModelProvider> rotate(List<ModelProvider> in, int cursor) {
        if (cursor <= 0 || cursor >= in.size()) {
            return in;
        }
        List<ModelProvider> out = new ArrayList<>(in.size());
        out.addAll(in.subList(cursor, in.size()));
        out.addAll(in.subList(0, cursor));
        return out;
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
     */
    public record DegradationStats(long totalFailures) {
        public boolean degraded() {
            return totalFailures > 0;
        }
    }

    public DegradationStats degradationStats() {
        return new DegradationStats(totalFailures.get());
    }

    /**
     * 全链路快照：SLA 端点暴露。
     */
    public LlmGatewaySnapshot snapshot() {
        List<CircuitBreakerProvider.CircuitSnapshot> providerSnaps = new ArrayList<>();
        for (ModelProvider p : providers) {
            if (p instanceof CircuitBreakerProvider cb) {
                providerSnaps.add(cb.snapshot());
            } else {
                // 未包熔断器：构造一个始终 CLOSED 的快照（便于上层无需特判）
                providerSnaps.add(new CircuitBreakerProvider.CircuitSnapshot(
                        p.name(), com.codereview.agent.core.llm.CircuitBreakerState.CLOSED,
                        0, 0, null, 0, 0, 0));
            }
        }
        List<TokenUsageRecorder.ProviderAggregate> agg = usageRecorder == null
                ? List.of()
                : usageRecorder.aggregatesSnapshot();
        return new LlmGatewaySnapshot(totalFailures.get(), providerSnaps, agg);
    }

    public List<ModelProvider> providers() {
        return providers;
    }

    public TokenUsageRecorder usageRecorder() {
        return usageRecorder;
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