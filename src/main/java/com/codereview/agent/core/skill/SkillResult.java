package com.codereview.agent.core.skill;

import com.codereview.agent.core.model.Severity;

/**
 * 技能执行结果（确定性静态扫描输出）。
 *
 * <p>由 Agent 统一转换为 {@code Finding} 并补全所属 Agent 类型与分类。
 *
 * @param file        命中文件
 * @param lineStart   命中起始行
 * @param severity    严重级别
 * @param ruleId      规则 ID
 * @param title       标题
 * @param description 描述
 * @param suggestion  修复建议
 * @param confidence  置信度
 */
public record SkillResult(
        String file,
        int lineStart,
        Severity severity,
        String ruleId,
        String title,
        String description,
        String suggestion,
        double confidence) {

    /**
     * 便捷构造：默认高置信（0.95）的 Major 级发现。
     *
     * @param file        文件
     * @param lineStart   行号
     * @param ruleId      规则 ID
     * @param title       标题
     * @param description 描述
     * @param suggestion  建议
     */
    public SkillResult(String file, int lineStart, String ruleId, String title,
                       String description, String suggestion) {
        this(file, lineStart, Severity.MAJOR, ruleId, title, description, suggestion, 0.95);
    }
}
