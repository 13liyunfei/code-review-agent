package com.codereview.agent.core.model;

/**
 * 审查 Agent 的角色类型（星型拓扑中的专业审查节点）。
 *
 * <p>依据文档设计，代码审查按专业维度拆分为若干独立 Agent，
 * 各自负责一个审查维度，结果汇聚到 {@link #COORDINATOR} 进行最终一致性聚合。
 */
public enum AgentType {

    /** 逻辑审查 Agent：业务正确性（算法、边界、异常、并发）。 */
    LOGIC("逻辑审查"),
    /** 安全审查 Agent：安全合规（注入、XSS、密钥泄露、越权）。 */
    SECURITY("安全审查"),
    /** 性能审查 Agent：性能优化（复杂度、N+1、内存泄漏）。 */
    PERFORMANCE("性能审查"),
    /** 规范审查 Agent：编码规范（风格、命名、注释）。 */
    STYLE("规范审查"),
    /** 架构审查 Agent：架构设计（分层、耦合、依赖）。 */
    ARCHITECTURE("架构审查"),
    /** 协调者 Agent：不负责具体审查，仅做调度与聚合。 */
    COORDINATOR("协调者");

    /** 角色中文名，用于报告展示。 */
    private final String displayName;

    AgentType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 获取角色的中文展示名。
     *
     * @return 中文名
     */
    public String getDisplayName() {
        return displayName;
    }
}
