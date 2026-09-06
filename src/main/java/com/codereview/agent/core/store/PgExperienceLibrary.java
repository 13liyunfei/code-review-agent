package com.codereview.agent.core.store;

import com.codereview.agent.core.memory.ExperienceEntry;
import com.codereview.agent.core.memory.ExperienceLibrary;
import com.codereview.agent.core.memory.ExperienceStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL 经验库（多机集群共享实现，{@code experience_entry} 表）。
 *
 * <p>升级与遗忘的判定在 SQL 内原子完成（单行 UPDATE 无并发互踩）；
 * 跨实例读到的永远是同一份状态（单一数据源）。
 */
public class PgExperienceLibrary implements ExperienceLibrary {

    private static final Logger log = LoggerFactory.getLogger(PgExperienceLibrary.class);

    /** 复现/正报次数达到该值自动升级 ACTIVE（防单次审查内同 pattern 多次命中造成的虚高）。 */
    static final int ACTIVE_EVIDENCE = 3;
    /** 人工误报达到该值自动降级 ARCHIVED。 */
    static final int ARCHIVE_NEGATIVE = 2;

    private final PgDb db;

    public PgExperienceLibrary(PgDb db) {
        this.db = db;
    }

    @Override
    public void upsertReflection(String teamId, String pattern, String advice) {
        try {
            db.update("""
                            INSERT INTO experience_entry
                                (team_id, pattern, advice, stage, evidence_pos, source, updated_at)
                            VALUES (?, ?, ?, 'candidate', 1, 'reflection', now())
                            ON CONFLICT (team_id, pattern) DO UPDATE SET
                                advice = EXCLUDED.advice,
                                evidence_pos = experience_entry.evidence_pos + 1,
                                stage = CASE WHEN experience_entry.evidence_pos + 1 >= ?
                                             THEN 'active' ELSE experience_entry.stage END,
                                source = 'reflection',
                                updated_at = now()
                            """,
                    teamId, pattern, advice, ACTIVE_EVIDENCE);
        } catch (Exception e) {
            log.warn("[Experience] 经验复现/沉淀失败（不阻断审查）：team={}, 原因={}", teamId, e.getMessage());
        }
    }

    @Override
    public void recordFeedback(String teamId, String ruleId, boolean falsePositive) {
        List<ExperienceEntry> candidates = new ArrayList<>();
        try {
            for (ExperienceEntry e : db.query(
                    "SELECT * FROM experience_entry WHERE team_id = ? AND stage <> 'purged'",
                    this::rowMapper, teamId)) {
                if (matchesRule(e.pattern(), ruleId)) {
                    candidates.add(e);
                }
            }
        } catch (Exception e) {
            log.warn("[Experience] 反馈关联经验查询失败：team={}, ruleId={}, 原因={}", teamId, ruleId, e.getMessage());
            return;
        }
        for (ExperienceEntry e : candidates) {
            try {
                if (falsePositive) {
                    db.update("""
                                    UPDATE experience_entry
                                    SET evidence_neg = evidence_neg + 1,
                                        stage = CASE WHEN evidence_neg + 1 >= ?
                                                     THEN 'archived' ELSE stage END,
                                        updated_at = now()
                                    WHERE team_id = ? AND id = ?
                                    """,
                            ARCHIVE_NEGATIVE, teamId, e.id());
                } else {
                    db.update("""
                                    UPDATE experience_entry
                                    SET evidence_pos = evidence_pos + 1,
                                        stage = 'active',
                                        updated_at = now()
                                    WHERE team_id = ? AND id = ?
                                    """,
                            teamId, e.id());
                }
            } catch (Exception ex) {
                log.warn("[Experience] 经验证据更新失败：team={}, id={}, 原因={}", teamId, e.id(), ex.getMessage());
            }
        }
    }

    /** pattern 形如 "ruleId title file"：以 ruleId 为前缀即视为同规则经验。 */
    private static boolean matchesRule(String pattern, String ruleId) {
        if (pattern == null || ruleId == null || ruleId.isBlank()) {
            return false;
        }
        return pattern.equals(ruleId) || pattern.startsWith(ruleId + " ");
    }

    @Override
    public void recordHit(String teamId, String pattern) {
        try {
            db.update("""
                            UPDATE experience_entry
                            SET hit_count = hit_count + 1, last_hit_at = now(), updated_at = now()
                            WHERE team_id = ? AND pattern = ? AND stage <> 'archived' AND stage <> 'purged'
                            """,
                    teamId, pattern);
        } catch (Exception e) {
            log.warn("[Experience] 命中刷新失败（忽略）：team={}, 原因={}", teamId, e.getMessage());
        }
    }

    @Override
    public List<ExperienceEntry> list(String teamId) {
        try {
            return db.query("""
                            SELECT * FROM experience_entry
                            WHERE team_id = ? AND stage <> 'archived' AND stage <> 'purged'
                            ORDER BY updated_at DESC
                            """,
                    this::rowMapper, teamId);
        } catch (Exception e) {
            log.warn("[Experience] 经验读取失败（返回空）：team={}, 原因={}", teamId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<ExperienceEntry> listAll(String teamId) {
        try {
            return db.query("SELECT * FROM experience_entry WHERE team_id = ? ORDER BY updated_at DESC",
                    this::rowMapper, teamId);
        } catch (Exception e) {
            log.warn("[Experience] 经验管理视图读取失败（返回空）：team={}, 原因={}", teamId, e.getMessage());
            return List.of();
        }
    }

    @Override
    public Optional<ExperienceEntry> get(String teamId, long id) {
        try {
            return db.queryOne("SELECT * FROM experience_entry WHERE team_id = ? AND id = ?",
                    this::rowMapper, teamId, id);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public int size(String teamId) {
        try {
            List<Integer> rows = db.query(
                    "SELECT COUNT(*) FROM experience_entry WHERE team_id = ? AND stage <> 'archived' AND stage <> 'purged'",
                    rs -> rs.getInt(1), teamId);
            return rows.isEmpty() ? 0 : rows.get(0);
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public int archiveIdle(String teamId, Duration idle) {
        if (idle == null || idle.isNegative() || idle.isZero()) {
            return 0;
        }
        try {
            Timestamp cutoff = Timestamp.from(Instant.now().minus(idle.toMillis(), java.time.temporal.ChronoUnit.MILLIS));
            List<Integer> rows = db.query("""
                            UPDATE experience_entry
                            SET stage = 'archived', updated_at = now()
                            WHERE team_id = ? AND stage IN ('candidate', 'active') AND updated_at < ?
                            RETURNING id
                            """,
                    rs -> rs.getInt(1), teamId, cutoff);
            return rows.size();
        } catch (Exception e) {
            log.warn("[Experience] TTL 归档执行失败：team={}, 原因={}", teamId, e.getMessage());
            return 0;
        }
    }

    @Override
    public int archiveIdleAll(Duration idle) {
        if (idle == null || idle.isNegative() || idle.isZero()) {
            return 0;
        }
        try {
            Timestamp cutoff = Timestamp.from(Instant.now().minus(idle.toMillis(), java.time.temporal.ChronoUnit.MILLIS));
            List<Integer> rows = db.query("""
                            UPDATE experience_entry
                            SET stage = 'archived', updated_at = now()
                            WHERE stage IN ('candidate', 'active') AND updated_at < ?
                            RETURNING id
                            """,
                    rs -> rs.getInt(1), cutoff);
            return rows.size();
        } catch (Exception e) {
            log.warn("[Experience] TTL 归档（全团队）执行失败：{}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int purgeArchived(String teamId, Duration purgeAfter) {
        if (purgeAfter == null || purgeAfter.isNegative() || purgeAfter.isZero()) {
            return 0;
        }
        try {
            Timestamp cutoff = Timestamp.from(Instant.now().minus(purgeAfter.toMillis(), java.time.temporal.ChronoUnit.MILLIS));
            List<Integer> rows = db.query(
                    "DELETE FROM experience_entry WHERE team_id = ? AND stage = 'archived' AND updated_at < ? RETURNING id",
                    rs -> rs.getInt(1), teamId, cutoff);
            return rows.size();
        } catch (Exception e) {
            log.warn("[Experience] 经验物理清理执行失败：team={}, 原因={}", teamId, e.getMessage());
            return 0;
        }
    }

    @Override
    public int purgeArchivedAll(Duration purgeAfter) {
        if (purgeAfter == null || purgeAfter.isNegative() || purgeAfter.isZero()) {
            return 0;
        }
        try {
            Timestamp cutoff = Timestamp.from(Instant.now().minus(purgeAfter.toMillis(), java.time.temporal.ChronoUnit.MILLIS));
            List<Integer> rows = db.query(
                    "DELETE FROM experience_entry WHERE stage = 'archived' AND updated_at < ? RETURNING id",
                    rs -> rs.getInt(1), cutoff);
            return rows.size();
        } catch (Exception e) {
            log.warn("[Experience] 经验物理清理（全团队）执行失败：{}", e.getMessage());
            return 0;
        }
    }

    @Override
    public boolean archive(String teamId, long id) {
        try {
            return db.update("""
                            UPDATE experience_entry SET stage = 'archived', updated_at = now()
                            WHERE team_id = ? AND id = ? AND stage <> 'purged'
                            """,
                    teamId, id) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean purge(String teamId, long id) {
        try {
            return db.update("DELETE FROM experience_entry WHERE team_id = ? AND id = ?", teamId, id) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private ExperienceEntry rowMapper(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp lastHit = rs.getTimestamp("last_hit_at");
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new ExperienceEntry(
                rs.getLong("id"),
                rs.getString("team_id"),
                rs.getString("pattern"),
                rs.getString("advice"),
                ExperienceStage.from(rs.getString("stage")),
                rs.getInt("evidence_pos"),
                rs.getInt("evidence_neg"),
                rs.getInt("hit_count"),
                lastHit == null ? -1 : lastHit.getTime(),
                rs.getString("source"),
                created.getTime(),
                updated.getTime());
    }
}
