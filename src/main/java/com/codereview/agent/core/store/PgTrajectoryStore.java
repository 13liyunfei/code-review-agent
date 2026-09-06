package com.codereview.agent.core.store;

import com.codereview.agent.core.trajectory.ReviewEvent;
import com.codereview.agent.core.trajectory.TrajectoryStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL 轨迹存储：审查结束时把事件序列整体 upsert 到 {@code trajectory_store} 单行。
 */
public class PgTrajectoryStore implements TrajectoryStore {

    private static final Logger log = LoggerFactory.getLogger(PgTrajectoryStore.class);

    private final PgDb db;
    private final ObjectMapper mapper = new ObjectMapper();

    public PgTrajectoryStore(PgDb db) {
        this.db = db;
    }

    @Override
    public void save(String runId, String teamId, List<ReviewEvent> events) {
        try {
            String json = mapper.writeValueAsString(events == null ? List.of() : events);
            db.update("""
                            INSERT INTO trajectory_store (run_id, team_id, events, updated_at)
                            VALUES (?, ?, ?::jsonb, now())
                            ON CONFLICT (run_id) DO UPDATE SET
                                team_id = EXCLUDED.team_id,
                                events = EXCLUDED.events,
                                updated_at = now()
                            """,
                    runId, teamId, json);
            log.info("[Trajectory] 轨迹已入库：runId={}, teamId={}, 事件数={}", runId, teamId,
                    events == null ? 0 : events.size());
        } catch (Exception e) {
            log.warn("[Trajectory] 轨迹入库失败（不阻断审查）：runId={}, 原因={}", runId, e.getMessage());
        }
    }

    @Override
    public Optional<List<ReviewEvent>> load(String runId, String teamId) {
        try {
            return db.queryOne(
                    "SELECT events FROM trajectory_store WHERE run_id = ? AND team_id = ?",
                    rs -> mapper.readValue(rs.getString(1), new TypeReference<List<ReviewEvent>>() {
                    }),
                    runId, teamId);
        } catch (Exception e) {
            log.warn("[Trajectory] 轨迹读取失败：runId={}, 原因={}", runId, e.getMessage());
            return Optional.empty();
        }
    }
}
