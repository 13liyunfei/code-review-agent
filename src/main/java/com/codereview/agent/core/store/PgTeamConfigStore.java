package com.codereview.agent.core.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * PostgreSQL 团队配置存储（{@code team_kv} 表，JSONB 行）。
 */
public class PgTeamConfigStore implements TeamConfigStore {

    private static final Logger log = LoggerFactory.getLogger(PgTeamConfigStore.class);

    private final PgDb db;

    public PgTeamConfigStore(PgDb db) {
        this.db = db;
    }

    @Override
    public Optional<String> loadJson(String teamId, String scope) {
        try {
            return db.queryOne("SELECT payload::text FROM team_kv WHERE team_id = ? AND scope = ?",
                    rs -> rs.getString(1), teamId, scope);
        } catch (Exception e) {
            log.warn("[TeamKV] 读取失败（按空处理）：team={}, scope={}, 原因={}", teamId, scope, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void saveJson(String teamId, String scope, String json) {
        try {
            db.update("""
                            INSERT INTO team_kv (team_id, scope, payload, updated_at)
                            VALUES (?, ?, ?::jsonb, now())
                            ON CONFLICT (team_id, scope) DO UPDATE SET
                                payload = EXCLUDED.payload,
                                updated_at = now()
                            """,
                    teamId, scope, json == null ? "{}" : json);
        } catch (Exception e) {
            log.warn("[TeamKV] 写入失败：team={}, scope={}, 原因={}", teamId, scope, e.getMessage());
        }
    }
}
