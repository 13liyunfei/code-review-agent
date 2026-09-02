package com.codereview.agent.core.model;

import java.util.List;

/**
 * 单个 Agent 完成审查后回传的结果。
 *
 * <p><b>降级可见性</b>：Agent 可能因超时、异常或模型不可用而未产出有效结论。
 * 这类情况必须显式标记为 {@code degraded} 并携带 {@code error} 原因，由聚合阶段
 * 汇总进报告——否则「0 条发现」会被误读为「代码没有问题」，而真相是「这次没看成」。
 *
 * @param prId       PR 标识
 * @param agentType  产出 Agent 类型
 * @param findings   该 Agent 发现的问题列表（降级时为空列表，不为 null）
 * @param timestamp  完成时间戳（毫秒）
 * @param degraded   本次执行是否降级（超时 / 异常 / 模型不可用）
 * @param error      降级原因（未降级时为 null）
 */
public record AgentResult(
        long prId,
        AgentType agentType,
        List<Finding> findings,
        long timestamp,
        boolean degraded,
        String error) {

    /**
     * 便捷构造：以当前时间作为完成时间，正常（未降级）结果。
     *
     * @param prId      PR 标识
     * @param agentType Agent 类型
     * @param findings  发现列表
     */
    public AgentResult(long prId, AgentType agentType, List<Finding> findings) {
        this(prId, agentType, findings, System.currentTimeMillis(), false, null);
    }

    /**
     * 便捷构造：指定完成时间，正常（未降级）结果。
     *
     * @param prId      PR 标识
     * @param agentType Agent 类型
     * @param findings  发现列表
     * @param timestamp 完成时间戳
     */
    public AgentResult(long prId, AgentType agentType, List<Finding> findings, long timestamp) {
        this(prId, agentType, findings, timestamp, false, null);
    }

    /**
     * 构造一条降级结果：该 Agent 本次未产出可信结论。
     *
     * <p>findings 统一为空列表（而非 null），避免下游到处判空。
     *
     * @param prId      PR 标识
     * @param agentType Agent 类型
     * @param error     降级原因（如「执行超时（300000ms）」）
     * @return 带降级标记的结果
     */
    public static AgentResult degraded(long prId, AgentType agentType, String error) {
        return new AgentResult(prId, agentType, List.of(), System.currentTimeMillis(), true, error);
    }

    /** 是否产出了有效结论（未降级）。 */
    public boolean healthy() {
        return !degraded;
    }
}
