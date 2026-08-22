package com.codereview.agent.core.mq;

/**
 * 队列命名约定（与文档中的 Redis 队列命名保持一致）。
 */
public final class QueueNames {

    /** 聚合队列：各 Agent 审查完成后将结果推送至此，由 Coordinator 消费。 */
    public static final String AGGREGATOR = "queue:aggregator";

    private QueueNames() {
    }

    /**
     * 获取某 Agent 的专属消费队列名。
     *
     * @param agentType Agent 角色名（如 LOGIC / SECURITY）
     * @return 队列名，如 "queue:agent:LOGIC"
     */
    public static String agentQueue(String agentType) {
        return "queue:agent:" + agentType.toUpperCase();
    }
}
