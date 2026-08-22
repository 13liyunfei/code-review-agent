package com.codereview.agent.core.agent;

import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewContext;

import java.util.List;

/**
 * 审查 Agent 统一接口。
 *
 * <p>每个专业审查维度（逻辑 / 安全 / 性能 / 规范 / 架构）实现本接口，
 * 由 Coordinator 以星型拓扑统一调度。
 */
public interface ReviewAgent {

    /**
     * 获取 Agent 类型（角色）。
     *
     * @return 角色枚举
     */
    AgentType getType();

    /**
     * 对一组代码变更执行审查。
     *
     * @param diffs 代码变更列表
     * @param ctx   审查上下文（单 PR 内共享）
     * @return 该 Agent 发现的问题列表
     */
    List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx);
}
