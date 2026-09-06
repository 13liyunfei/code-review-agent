package com.codereview.agent.core.store;

import com.codereview.agent.core.resume.ResumeState;
import com.codereview.agent.core.resume.ResumeStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL 断点续跑存储（多机集群共享实现）。
 *
 * <p>断点全量 JSON 存 {@code resume_state} 单行（run_id 主键），行级原子 UPSERT：
 * <ul>
 *   <li>多实例共享同一 PG：A 机崩溃后的断点，B 机收到同 runId 请求照样能续跑；</li>
 *   <li>同一 runId 并发 save 不会互相破坏（文件存储下两进程同时全量读改写会互踩）；</li>
 *   <li>残留清理判据用 DB 的 {@code updated_at}（每行原子更新），无需 mtime。</li>
 * </ul>
 */
public class PgResumeStore implements ResumeStore {

    private static final Logger log = LoggerFactory.getLogger(PgResumeStore.class);

    private final PgDb db;
    private final ObjectMapper mapper = new ObjectMapper();

    public PgResumeStore(PgDb db) {
        this.db = db;
    }

    @Override
    public void save(ResumeState state) {
        if (state == null) {
            return;
        }
        try {
            String payload = mapper.writeValueAsString(state);
            db.update("""
                            INSERT INTO resume_state (run_id, team_id, payload, updated_at)
                            VALUES (?, ?, ?::jsonb, now())
                            ON CONFLICT (run_id) DO UPDATE SET
                                team_id = EXCLUDED.team_id,
                                payload = EXCLUDED.payload,
                                updated_at = now()
                            """,
                    state.runId(), state.teamId(), payload);
        } catch (JsonProcessingException e) {
            log.warn("[Resume] 断点序列化失败（不阻断审查）：runId={}, 原因={}", state.runId(), e.getMessage());
        } catch (Exception e) {
            log.warn("[Resume] 断点保存失败（不阻断审查）：runId={}, 原因={}", state.runId(), e.getMessage());
        }
    }

    @Override
    public Optional<ResumeState> load(String runId, String teamId) {
        try {
            return db.queryOne(
                            "SELECT payload FROM resume_state WHERE run_id = ? AND team_id = ?",
                            rs -> mapper.readValue(rs.getString(1), ResumeState.class),
                            runId, teamId)
                    .map(s -> {
                        log.info("[Resume] 命中断点（跨实例共享）：runId={}, teamId={}", runId, teamId);
                        return s;
                    });
        } catch (Exception e) {
            log.warn("[Resume] 断点读取失败（按无断点处理）：runId={}, 原因={}", runId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void complete(String runId, String teamId) {
        try {
            db.update("DELETE FROM resume_state WHERE run_id = ? AND team_id = ?", runId, teamId);
        } catch (Exception e) {
            log.warn("[Resume] 断点清理失败（无碍）：runId={}, 原因={}", runId, e.getMessage());
        }
    }

    @Override
    public int purgeExpired(Duration maxAge) {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            return 0;
        }
        Instant cutoff = Instant.now().minus(maxAge.toMillis(), ChronoUnit.MILLIS);
        try {
            List<Long> deleted = db.query(
                    "DELETE FROM resume_state WHERE updated_at < ? RETURNING id",
                    rs -> rs.getLong(1), cutoff);
            return deleted.size();
        } catch (Exception e) {
            log.warn("[Resume] 残留断点清理异常（本轮跳过）：{}", e.getMessage());
            return 0;
        }
    }
}
