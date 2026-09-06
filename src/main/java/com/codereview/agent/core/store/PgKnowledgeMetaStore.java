package com.codereview.agent.core.store;

import com.codereview.agent.core.admin.KnowledgeMeta;
import com.codereview.agent.core.admin.KnowledgeMetaStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL 团队知识元数据存储（{@code knowledge_meta} 表）。
 */
public class PgKnowledgeMetaStore implements KnowledgeMetaStore {

    private static final Logger log = LoggerFactory.getLogger(PgKnowledgeMetaStore.class);

    private final PgDb db;

    public PgKnowledgeMetaStore(PgDb db) {
        this.db = db;
    }

    @Override
    public void save(String teamId, KnowledgeMeta meta) {
        try {
            db.update("""
                            INSERT INTO knowledge_meta
                                (id, team_id, filename, source, category, type, indexed,
                                 chunk_count, size_bytes, content, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)
                            ON CONFLICT (id) DO UPDATE SET
                                team_id = EXCLUDED.team_id,
                                filename = EXCLUDED.filename,
                                source = EXCLUDED.source,
                                category = EXCLUDED.category,
                                type = EXCLUDED.type,
                                indexed = EXCLUDED.indexed,
                                chunk_count = EXCLUDED.chunk_count,
                                size_bytes = EXCLUDED.size_bytes,
                                content = EXCLUDED.content
                            """,
                    meta.id(), teamId, meta.filename(), meta.source(), meta.category(), meta.type(),
                    meta.indexed(), meta.chunkCount(), meta.sizeBytes(), meta.content(), meta.createdAt());
        } catch (Exception e) {
            log.warn("[KB] 元数据入库失败：team={}, id={}, 原因={}", teamId, meta.id(), e.getMessage());
        }
    }

    @Override
    public List<KnowledgeMeta> list(String teamId) {
        try {
            return db.query("""
                            SELECT * FROM knowledge_meta WHERE team_id = ? ORDER BY created_at DESC
                            """,
                    this::row, teamId);
        } catch (Exception e) {
            log.warn("[KB] 元数据列表读取失败（返回空）：team={}, 原因={}", teamId, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public Optional<KnowledgeMeta> get(String teamId, String id) {
        try {
            return db.queryOne("SELECT * FROM knowledge_meta WHERE team_id = ? AND id = ?",
                    this::row, teamId, id);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String teamId, String id) {
        try {
            db.update("DELETE FROM knowledge_meta WHERE team_id = ? AND id = ?", teamId, id);
        } catch (Exception e) {
            log.warn("[KB] 元数据删除失败：team={}, id={}, 原因={}", teamId, id, e.getMessage());
        }
    }

    private KnowledgeMeta row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new KnowledgeMeta(
                rs.getString("id"),
                rs.getString("team_id"),
                rs.getString("filename"),
                rs.getString("source"),
                rs.getString("category"),
                rs.getString("type"),
                rs.getBoolean("indexed"),
                rs.getInt("chunk_count"),
                rs.getLong("size_bytes"),
                rs.getString("content"),
                rs.getTimestamp("created_at").toInstant().toString());
    }
}
