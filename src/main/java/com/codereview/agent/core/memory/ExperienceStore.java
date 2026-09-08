package com.codereview.agent.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 经验库（长期记忆的检索门面）。
 *
 * <p><b>生产唯一通道为经验条目</b>（{@link ExperienceLibrary}，生产为 PostgreSQL）：
 * 沉淀「问题模式 → 有效建议」条目，带生命周期（{@link ExperienceStage}：复现证据升级 ACTIVE、
 * 人工误报降级 ARCHIVED、TTL 遗忘），由 {@code ReflectionService} 反思写入、
 * {@code MemoryMaintenanceScheduler} 定时维护；检索侧由 {@code RagContextBuilder} 在生产审查链路
 * 调用（{@link #getRelevantExperiences} 命中即 {@code recordHit}，spaced repetition 反遗忘）。
 *
 * <p><b>历史说明</b>：早期版本还保留一条「向量通道」（{@code MemoryStore} 中
 * {@code metadata.type=experience} 的向量），但唯一写入方（反思 Agent）从未接生产
 * 主链路，向量经验无生产数据源，故该通道已废弃（2026-09-07 清理），
 * 检索一律走条目通道。
 */
public class ExperienceStore {

    private static final Logger log = LoggerFactory.getLogger(ExperienceStore.class);

    /** 检索注入的条目上限。 */
    private static final int TOP_N = 3;

    private final ExperienceLibrary library;

    public ExperienceStore(ExperienceLibrary library) {
        this.library = library == null ? new InMemoryExperienceLibrary() : library;
    }

    /**
     * 获取与当前审查相关的历史经验文本（团队自有经验，不含全局基线）。
     * 条目关键词命中输出；被选中的条目刷新命中（反遗忘）。
     *
     * @param teamId 团队标识（应已净化，与写入侧同口径）
     * @param text   查询文本（通常为 diff 摘要，供关键词重合匹配）
     */
    public String getRelevantExperiences(String teamId, String text) {
        StringBuilder sb = new StringBuilder();
        for (ExperienceEntry e : top(teamId, text, TOP_N)) {
            sb.append("- ").append(e.pattern()).append(" → ").append(e.advice()).append('\n');
            try {
                library.recordHit(teamId, e.pattern());
            } catch (Exception ignored) {
                // 命中统计失败不影响检索
            }
        }
        return sb.toString().trim();
    }

    /** 写入/复现一条经验（反思沉淀；同 pattern 复现证据 +1，达阈值升级 ACTIVE）。 */
    public void add(String teamId, String pattern, String advice) {
        if (pattern == null || pattern.isBlank() || advice == null || advice.isBlank()) {
            return;
        }
        try {
            library.upsertReflection(teamId, pattern.trim(), advice.trim());
        } catch (Exception e) {
            log.warn("[Experience] 经验沉淀失败（不阻断主链路）：team={}, 原因={}", teamId, e.getMessage());
        }
    }

    /** 人工反馈驱动经验证据（误报降级 / 正报升级）。 */
    public void recordFeedback(String teamId, String ruleId, boolean falsePositive) {
        try {
            library.recordFeedback(teamId, ruleId, falsePositive);
        } catch (Exception e) {
            log.warn("[Experience] 经验反馈证据登记失败：team={}, ruleId={}, 原因={}",
                    teamId, ruleId, e.getMessage());
        }
    }

    /** 按查询关键词重合度检索 Top-N（ACTIVE 优先，未遗忘条目）。 */
    public List<ExperienceEntry> top(String teamId, String query, int limit) {
        try {
            Set<String> q = tokenize(query);
            return library.list(teamId).stream()
                    .filter(e -> overlap(q, tokenize(e.pattern())) > 0)
                    .sorted(Comparator
                            .comparing((ExperienceEntry e) -> e.stage() == ExperienceStage.ACTIVE ? 0 : 1)
                            .thenComparingInt(e -> -overlap(q, tokenize(e.pattern())))
                            .thenComparingLong(e -> -e.updatedAt()))
                    .limit(Math.max(1, limit))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 当前未遗忘条目数。 */
    public int size(String teamId) {
        try {
            return library.size(teamId);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 管理视图：全部条目（含归档）。 */
    public List<ExperienceEntry> listAll(String teamId) {
        return library.listAll(teamId);
    }

    public Optional<ExperienceEntry> get(String teamId, long id) {
        return library.get(teamId, id);
    }

    public boolean archive(String teamId, long id) {
        return library.archive(teamId, id);
    }

    public boolean purge(String teamId, long id) {
        return library.purge(teamId, id);
    }

    private Set<String> tokenize(String s) {
        if (s == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase().split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (w.length() >= 2) {
                out.add(w);
            }
        }
        return out;
    }

    private int overlap(Set<String> a, Set<String> b) {
        int n = 0;
        for (String w : b) {
            if (a.contains(w)) {
                n++;
            }
        }
        return n;
    }
}
