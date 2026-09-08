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

    /**
     * 本 Agent 是否应当参与本次审查（按 diff 内容做细粒度准入）。
     *
     * <p>默认恒 true（行为与历史一致：全集并行）。语义型 Agent 可覆写此方法，
     * 例如当 PR 全部为文档/配置文件（无任何代码文件）时跳过 LLM 语义审查，
     * 只保留对任意内容都有价值的规则型/安全型 Agent——从而把 token 花在真正有对象的地方。
     *
     * <p>注意：本方法只做「内容相关性」准入，不做优先级排序；返回 false 的 Agent
     * 由 {@code Coordinator} 在调度前剔除，不会产生「降级」语义，报告维度相应减少。
     *
     * @param diffs 代码变更列表（与 {@link #review} 同源）
     * @param ctx   审查上下文（单 PR 内共享）
     * @return true=参与审查（默认），false=本次跳过
     */
    default boolean supports(List<CodeDiff> diffs, ReviewContext ctx) {
        return true;
    }
}
