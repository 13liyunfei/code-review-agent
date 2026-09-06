package com.codereview.agent.core.memory;

/**
 * 经验条目生命周期状态（记忆升级与遗忘的显式化）。
 *
 * <p>设计对齐「五态生命周期」的最小子集，证据驱动而非时间空转：
 * <ul>
 *   <li>{@link #CANDIDATE} —— 反思/审查沉淀的候选经验（未经验证）；</li>
 *   <li>{@link #ACTIVE}    —— 证据驱动升级：跨 PR 复现或人工正报确认后转正，
 *        检索注入时权重最高；</li>
 *   <li>{@link #ARCHIVED}  —— 遗忘（软删）：人工误报累计达到阈值，或超过 TTL
 *        无命中/无更新（记忆衰退），检索不再注入，等待物理清理或人工恢复审视；</li>
 *   <li>{@link #PURGED}    —— 物理删除（硬删）：ARCHIVED 超保留期由维护任务清理，不可恢复。</li>
 * </ul>
 *
 * <p>升级不靠时间，靠证据：{@code evidence_pos}（复现 + 人工正报）≥ 阈值转 ACTIVE；
 * {@code evidence_neg}（人工误报）≥ 阈值降 ARCHIVED。遗忘（TTL 衰减）只作用于
 * 「长期无命中」的条目——被反复用到的经验每次检索都会刷新 {@code lastHitAt}，
 * 形成 spaced-repetition 反遗忘：常用经验不衰退，冷门经验先软删后硬删。
 */
public enum ExperienceStage {
    CANDIDATE,
    ACTIVE,
    ARCHIVED,
    PURGED;

    /** 数据库列值（小写）。 */
    public String db() {
        return name().toLowerCase();
    }

    /** 由数据库列值解析（未知回落 CANDIDATE）。 */
    public static ExperienceStage from(String s) {
        if (s == null) {
            return CANDIDATE;
        }
        try {
            return valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return CANDIDATE;
        }
    }
}
