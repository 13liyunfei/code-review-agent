package com.codereview.agent.core.store;

import com.codereview.agent.core.history.ReviewHistoryEntry;
import com.codereview.agent.core.history.ReviewHistoryStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL 审查历史存储（多机集群共享实现）。
 *
 * <p>每条历史一行（{@code review_history}），同一 key 仅保留最近 {@value #MAX_PER_KEY} 条
 * （写入后清理更早记录，与改造前文件版行为一致）。质量趋势报表从共享 PG 聚合，
 * 任一实例的数据都可被报表读到。
 */
public class PgReviewHistoryStore implements ReviewHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(PgReviewHistoryStore.class);

    /** 同一存储键（repo#pr）最多保留的历史条数。 */
    static final int MAX_PER_KEY = 30;

    private final PgDb db;
    private final ObjectMapper mapper = new ObjectMapper();

    public PgReviewHistoryStore(PgDb db) {
        this.db = db;
    }

    @Override
    public Optional<ReviewHistoryEntry> getLatest(String teamId, String key) {
        try {
            return db.queryOne("""
                            SELECT payload FROM review_history
                            WHERE team_id = ? AND hkey = ?
                            ORDER BY id DESC LIMIT 1
                            """,
                    rs -> mapper.readValue(rs.getString(1), ReviewHistoryEntry.class),
                    teamId, key);
        } catch (Exception e) {
            log.warn("[History] 最近历史读取失败（按无返回）：team={}, key={}, 原因={}", teamId, key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(String teamId, ReviewHistoryEntry entry) {
        try {
            String payload = mapper.writeValueAsString(entry);
            db.update("INSERT INTO review_history (team_id, hkey, payload) VALUES (?, ?, ?::jsonb)",
                    teamId, entry.key(), payload);
            // 同一 key 只保留最近 MAX_PER_KEY 条（与文件版一致）
            db.update("""
                    DELETE FROM review_history
                    WHERE team_id = ? AND hkey = ?
                      AND id NOT IN (
                          SELECT id FROM review_history
                          WHERE team_id = ? AND hkey = ?
                          ORDER BY id DESC LIMIT ?
                      )
                    """, teamId, entry.key(), teamId, entry.key(), MAX_PER_KEY);
        } catch (Exception e) {
            log.warn("[History] 历史保存失败：team={}, key={}, 原因={}", teamId, entry.key(), e.getMessage());
        }
    }

    @Override
    public List<ReviewHistoryEntry> list(String teamId) {
        try {
            return db.query("SELECT payload FROM review_history WHERE team_id = ? ORDER BY id",
                    rs -> mapper.readValue(rs.getString(1), ReviewHistoryEntry.class), teamId);
        } catch (Exception e) {
            log.warn("[History] 历史列表读取失败（返回空）：team={}, 原因={}", teamId, e.getMessage());
            return new ArrayList<>();
        }
    }
}
