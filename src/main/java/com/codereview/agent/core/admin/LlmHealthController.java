package com.codereview.agent.core.admin;

import com.codereview.agent.core.llm.LlmGatewaySnapshot;
import com.codereview.agent.core.llm.ModelGateway;
import com.codereview.agent.core.llm.TokenUsageRecord;
import com.codereview.agent.core.llm.TokenUsageRecorder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * LLM 网关 SLA 端点：把 {@link ModelGateway} 的运行态暴露给容器编排 / 监控。
 *
 * <p>对应 JD「高可用」「全链路监测」「SLA」诉求：
 * <ul>
 *   <li>{@code GET /api/admin/llm/health}：每个供应商的熔断状态 + 累计失败数 +
 *       距允许试探剩余毫秒；适合 k8s readinessProbe（所有供应商 OPEN 即 DOWN）；</li>
 *   <li>{@code GET /api/admin/llm/stats}：累计彻底失败次数 + 供应商列表；</li>
 *   <li>{@code GET /api/admin/llm/usage}：最近 N 次 token 用量明细 + 按供应商聚合。</li>
 * </ul>
 *
 * <p><b>鉴权</b>：与现有 {@code /api/admin/**} 一致——由 {@code review.api.auth-token}
 * 过滤；本地 dev 留空则零鉴权放行。
 */
@RestController
@RequestMapping("/api/admin/llm")
public class LlmHealthController {

    private final ModelGateway gateway;
    private final TokenUsageRecorder usageRecorder;

    public LlmHealthController(ModelGateway gateway, TokenUsageRecorder usageRecorder) {
        this.gateway = gateway;
        this.usageRecorder = usageRecorder;
    }

    /** 全链路快照（供 k8s readinessProbe / Prometheus exporter 消费）。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        LlmGatewaySnapshot snap = gateway.snapshot();
        long totalProviders = snap.providers().size();
        long healthy = snap.providers().stream()
                .filter(p -> p.state() == com.codereview.agent.core.llm.CircuitBreakerState.CLOSED)
                .count();
        long open = snap.providers().stream()
                .filter(p -> p.state() == com.codereview.agent.core.llm.CircuitBreakerState.OPEN)
                .count();
        boolean degraded = totalProviders == 0 || healthy == 0;
        return Map.of(
                "status", degraded ? "DEGRADED" : "UP",
                "totalProviders", totalProviders,
                "healthy", healthy,
                "open", open,
                "totalFailures", snap.totalFailures(),
                "providers", snap.providers());
    }

    /** 累计统计快照。 */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        LlmGatewaySnapshot snap = gateway.snapshot();
        return Map.of(
                "totalFailures", snap.totalFailures(),
                "providers", snap.providers(),
                "usageByProvider", snap.usage());
    }

    /**
     * token 用量明细：默认最近 100 条；{@code limit} 参数可调（最大 1024）。
     */
    @GetMapping("/usage")
    public Map<String, Object> usage(@RequestParam(value = "limit", required = false, defaultValue = "100") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 1024));
        List<TokenUsageRecord> all = usageRecorder == null ? List.of() : usageRecorder.snapshot();
        int from = Math.max(0, all.size() - safeLimit);
        List<TokenUsageRecord> recent = all.subList(from, all.size());
        return Map.of(
                "bufferSize", all.size(),
                "limit", safeLimit,
                "records", recent,
                "byProvider", usageRecorder == null ? List.of() : usageRecorder.aggregatesSnapshot());
    }
}