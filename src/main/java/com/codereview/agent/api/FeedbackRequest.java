package com.codereview.agent.api;

import com.codereview.agent.tenant.Teams;

/**
 * 开发者反馈请求体（人工介入 / 误报标记）。
 *
 * @param ruleId          规则 ID（必填，如 SEC-001）
 * @param agentType       来源 Agent 类型（可选，如 SECURITY；为空表示适用全部）
 * @param isFalsePositive 是否误报（true=误报，false=确认有效）
 * @param note            备注（可选）
 * @param file            关联文件（可选，用于文件级精准抑制）
 * @param teamId          团队标识（可选，缺省回退默认团队，用于按团队隔离反馈）
 */
public record FeedbackRequest(String ruleId, String agentType, boolean isFalsePositive,
                             String note, String file, String teamId) {

    /** 便捷构造：不含团队（回退默认团队）。 */
    public FeedbackRequest(String ruleId, String agentType, boolean isFalsePositive, String note, String file) {
        this(ruleId, agentType, isFalsePositive, note, file, Teams.DEFAULT);
    }
}
