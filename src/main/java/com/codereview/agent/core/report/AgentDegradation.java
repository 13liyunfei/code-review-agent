package com.codereview.agent.core.report;

/**
 * 一次审查中的降级记录：某个环节（Agent 或基础设施）本次未产出可信结论。
 *
 * <p>为什么需要它：Agent 超时、抛异常、模型不可用时，其 findings 为空。若不与
 * 「确实没有问题」区分开，聚合后的报告会显示 0 条发现——读者（和 CI 门禁）会误判为
 * 代码质量良好，而真相是这次根本没看成。降级记录让报告能如实标注。
 *
 * @param stage  降级环节名（Agent 类型名，或 {@code advanced-analysis} 等基础设施阶段名）
 * @param reason 降级原因（人类可读，如「执行超时（300000ms）」）
 */
public record AgentDegradation(String stage, String reason) {

    /** 高级静态分析（AST / 调用图 / SCA）阶段的固定阶段名。 */
    public static final String STAGE_ADVANCED_ANALYSIS = "advanced-analysis";

    /**
     * @param stage  环节名
     * @param reason 原因
     */
    public AgentDegradation {
        if (stage == null || stage.isBlank()) {
            throw new IllegalArgumentException("stage 不能为空");
        }
        if (reason == null || reason.isBlank()) {
            reason = "未知原因";
        }
    }
}
