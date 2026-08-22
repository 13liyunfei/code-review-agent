package com.codereview.agent.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一模型网关（多厂商 + Quota 配额 + Failover 失败转移）。
 *
 * <p>实现 {@link LlmClient}，对上层（各审查 Agent）屏蔽底层厂商差异：
 * <ul>
 *   <li><b>多厂商路由</b>：按配置顺序选择可用供应商（混元 / OpenAI 兼容 / Azure / Mock）；</li>
 *   <li><b>配额</b>：每个供应商设定时间窗内最大调用次数，超限自动降级到下一供应商；</li>
 *   <li><b>失败转移</b>：某供应商抛异常时，立即切换下一供应商，保证审查链路不中断；</li>
 *   <li><b>兜底</b>：Mock 供应商永远可用，作为链路终点。</li>
 * </ul>
 */
public class ModelGateway implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);

    /**
     * 配额窗口（秒）。
     */
    private static final long WINDOW_SECONDS = 60;

    private final List<ModelProvider> providers;
    private final int maxPerWindow;
    private final java.util.Map<String, QuotaState> quotas = new java.util.concurrent.ConcurrentHashMap<>();

    public ModelGateway(List<ModelProvider> providers, int maxPerWindow) {
        this.providers = providers;
        this.maxPerWindow = maxPerWindow;
    }

    @Override
    public String chat(String prompt) {
        long t0 = System.currentTimeMillis();
        int attempt = 0;
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
                log.warn("[模型网关] 供应商[{}] 调用失败，失败转移至下一供应商：{}（已耗时 {}ms）",
                        p.name(), e.getMessage(), System.currentTimeMillis() - t0);
            }
        }
        long cost = System.currentTimeMillis() - t0;
        log.error("[模型网关] 所有可用供应商均失败，最终回退 Mock（prompt 长度={}, 耗时 {}ms）",
                prompt.length(), cost);
        // 兜底：直接走 Mock（理论上 providers 末尾已包含 mock）
        ModelProvider mock = providers.stream()
                .filter(p -> "mock".equals(p.name()))
                .findFirst()
                .orElse(null);
        if (mock != null) {
            try {
                return mock.chat(prompt);
            } catch (Exception e) {
                log.error("[模型网关] Mock 兜底也失败：{}", e.getMessage());
            }
        }
        return "";
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
