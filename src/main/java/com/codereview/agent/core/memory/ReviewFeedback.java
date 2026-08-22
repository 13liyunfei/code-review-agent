package com.codereview.agent.core.memory;

/**
 * 开发者反馈（用于反思沉淀与置信度校准，亦是“误报反馈闭环”的核心载体）。
 *
 * <p>当开发者在 PR 上标记某条审查发现为误报 / 有效时，系统据此沉淀经验，
 * 并在后续聚合阶段抑制重复误报（见 {@code FeedbackStore} 与 {@link ReportGenerator}）。
 *
 * @param ruleId          命中的规则 ID（如 SEC-001）
 * @param agentType       来源 Agent 类型（如 SECURITY），可为空表示适用于所有 Agent
 * @param isFalsePositive 是否误报（true=误报，false=正报）
 * @param note            备注（人工填写的原因）
 * @param file            关联文件（可选，用于文件级精准抑制；为空表示规则级）
 */
public record ReviewFeedback(String ruleId, String agentType, boolean isFalsePositive, String note, String file) {

    /**
     * 向后兼容构造器（不指定文件，按规则级处理）。
     *
     * @param ruleId          规则 ID
     * @param agentType       来源 Agent 类型
     * @param isFalsePositive 是否误报
     * @param note            备注
     */
    public ReviewFeedback(String ruleId, String agentType, boolean isFalsePositive, String note) {
        this(ruleId, agentType, isFalsePositive, note, null);
    }
}
