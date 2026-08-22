package com.codereview.agent.core.skill;

/**
 * 技能元信息，用于注入到提示词中告知 LLM 当前 Agent 已激活的能力。
 *
 * @param name        技能名
 * @param description 技能描述
 * @param category    技能分类（如 security / performance）
 */
public record SkillMetadata(String name, String description, String category) {

    /**
     * 便捷构造。
     *
     * @param name        技能名
     * @param description 描述
     */
    public SkillMetadata(String name, String description) {
        this(name, description, "general");
    }
}
