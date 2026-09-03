package com.codereview.agent.core.llm;

import java.util.List;

/**
 * LLM 网关全链路快照（供 SLA 端点暴露）。
 *
 * <p>对应 JD「高可用」「全链路监测」「SLA」诉求：
 * 把 ModelGateway 的「降级计数 + 供应商熔断状态 + token 用量」合并到一个 DTO，
 * 便于容器编排（k8s readinessProbe / Prometheus exporter）侧消费。
 */
public record LlmGatewaySnapshot(
        /** 累计彻底失败次数（所有供应商都不可用）。 */
        long totalFailures,
        /** 各供应商熔断状态快照（按声明顺序）。 */
        List<CircuitBreakerProvider.CircuitSnapshot> providers,
        /** token 用量累计（最近 N 次明细在外层快照）。 */
        List<TokenUsageRecorder.ProviderAggregate> usage
) {
}