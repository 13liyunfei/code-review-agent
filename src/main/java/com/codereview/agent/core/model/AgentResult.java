package com.codereview.agent.core.model;

import java.util.List;

/**
 * 单个 Agent 完成审查后回传的结果。
 *
 * @param prId       PR 标识
 * @param agentType  产出 Agent 类型
 * @param findings   该 Agent 发现的问题列表
 * @param timestamp  完成时间戳（毫秒）
 */
public record AgentResult(
        long prId,
        AgentType agentType,
        List<Finding> findings,
        long timestamp) {

    /**
     * 便捷构造：以当前时间作为完成时间。
     *
     * @param prId      PR 标识
     * @param agentType Agent 类型
     * @param findings  发现列表
     */
    public AgentResult(long prId, AgentType agentType, List<Finding> findings) {
        this(prId, agentType, findings, System.currentTimeMillis());
    }
}
