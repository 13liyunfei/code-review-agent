package com.codereview.agent.core.admin.dto;

/**
 * 技能信息（前端 Skills 市场展示用）。
 *
 * @param id          内置技能为元数据名；自定义技能为规则 ID
 * @param name        技能名
 * @param title       展示标题
 * @param description 描述
 * @param category    归属维度
 * @param enabled     是否启用
 * @param custom      是否为团队自定义技能
 * @param ruleId      规则 ID（自定义技能为 CUSTOM-xxx，内置为 null）
 */
public record SkillInfo(
        String id,
        String name,
        String title,
        String description,
        String category,
        boolean enabled,
        boolean custom,
        String ruleId) {
}
