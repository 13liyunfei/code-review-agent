package com.codereview.agent.core.memory;

/**
 * 一条可复用审查经验（含生命周期字段，替代改造前 {@code experience.json} 的二元条目）。
 *
 * @param id          数据库自增 ID（管理操作定位用）
 * @param teamId      团队隔离键
 * @param pattern     问题模式（同 pattern 去重 = 同一条经验的「复现」）
 * @param advice      有效建议
 * @param stage       生命周期状态
 * @param evidencePos 正证据：跨 PR 复现次数 + 人工正报次数（驱动升级）
 * @param evidenceNeg 负证据：人工误报次数（驱动降级/遗忘）
 * @param hitCount    检索命中次数（spaced-repetition 反遗忘）
 * @param lastHitAt   最近一次命中时间（毫秒；无命中则 -1）
 * @param source      来源（reflection / feedback / seed）
 * @param createdAt   创建时间（毫秒）
 * @param updatedAt   最近更新时间（毫秒）
 */
public record ExperienceEntry(
        long id,
        String teamId,
        String pattern,
        String advice,
        ExperienceStage stage,
        int evidencePos,
        int evidenceNeg,
        int hitCount,
        long lastHitAt,
        String source,
        long createdAt,
        long updatedAt) {

    /** 是否仍参与检索注入（未遗忘）。 */
    public boolean retrievable() {
        return stage == ExperienceStage.CANDIDATE || stage == ExperienceStage.ACTIVE;
    }
}
