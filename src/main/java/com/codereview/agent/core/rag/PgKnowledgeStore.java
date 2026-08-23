package com.codereview.agent.core.rag;

import com.codereview.agent.core.llm.EmbeddingClient;
import com.codereview.agent.core.memory.MemoryEntry;
import com.codereview.agent.core.memory.MemoryLevel;
import com.codereview.agent.core.memory.MemoryStore;
import com.codereview.agent.tenant.Teams;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产级 RAG 知识库（PostgreSQL + pgvector + tsvector 混合检索）。
 *
 * <p><b>职责边界（架构 clean）</b>：本类<b>仅实现 {@link KnowledgeStore}</b>（RAG 专属语义），
 * 不再继承 {@link com.codereview.agent.core.memory.PgVectorMemoryStore}、也不实现
 * {@link MemoryStore}——知识库与记忆库在类型层面严格隔离，避免把「知识库」误暴露为「记忆库」
 * 导致的 Spring 按类型注入歧义（曾引发 {@code ExperienceStore} 注入 {@code MemoryStore} 时
 * 在 {@code pgVectorMemoryStore} 与 {@code PgKnowledgeStore} 间二选一的冲突）。
 *
 * <p><b>写入复用</b>：通过构造注入的 {@link MemoryStore}（即 {@code PgVectorMemoryStore} 实例）
 * 完成落库——共享其连接池、建表迁移与 {@code search_vector} 维护，<b>不另起写入连接</b>。
 *
 * <p><b>检索独立</b>：混合检索（稠密向量 + BM25 + RRF）使用本类自建的<b>独立只读</b> Hikari 连接池，
 * 与写入器物理共享同一张 {@code memory_store} 表（按 {@code agent_type='RAG'} 限定读写视角），
 * 互不阻塞。检索连接仅在 {@link #init()} 中确保 {@code search_vector} 的 GIN 索引存在
 * （表与向量列由写入器负责创建/迁移）。
 *
 * <p>混合检索策略（业界最佳实践）：
 * <ul>
 *   <li><b>稠密向量</b>：{@code embedding <=>} 余弦距离（沿用同一向量索引）；</li>
 *   <li><b>稀疏 BM25</b>：{@code search_vector}（tsvector）+ {@code ts_rank}，
 *       对代码标识符 / 专有名词 / 精确术语友好；</li>
 *   <li><b>RRF 融合</b>：两路按 {@code 1/(k+rank)} 融合（默认向量 0.7 + BM25 0.3），k=60；</li>
 *   <li>结果归一化为 {@code similarity} 元数据，供 {@link RagEvaluator} 阈值过滤。</li>
 * </ul>
 */
public class PgKnowledgeStore implements KnowledgeStore {

    private static final Logger log = LoggerFactory.getLogger(PgKnowledgeStore.class);

    /** RRF 常数。 */
    private static final double RRF_K = 60.0;
    /** 向量路权重（与 BM25 之和为 1）。 */
    private final double denseWeight;

    private final EmbeddingClient embeddingClient;
    /** 写入器：复用其连接池与建表迁移（记忆库同一 PG 实例）。 */
    private final MemoryStore writer;
    /** 检索专用只读连接池（同库同表，独立连接）。 */
    private final HikariDataSource readPool;

    private final ObjectMapper mapper = new ObjectMapper();

    public PgKnowledgeStore(EmbeddingClient embeddingClient, MemoryStore writer,
                            String host, int port, String database,
                            String username, String password, int vectorDim) {
        this(embeddingClient, writer, host, port, database, username, password, vectorDim, 0.7);
    }

    public PgKnowledgeStore(EmbeddingClient embeddingClient, MemoryStore writer,
                            String host, int port, String database,
                            String username, String password, int vectorDim,
                            double denseWeight) {
        this.embeddingClient = embeddingClient;
        this.writer = writer;
        this.denseWeight = denseWeight;

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        if (username != null && !username.isBlank()) {
            cfg.setUsername(username);
            cfg.setPassword(password);
        }
        cfg.setPoolName("pg-knowledge-read-pool");
        cfg.setMaximumPoolSize(8);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(30_000);
        cfg.setIdleTimeout(600_000);
        cfg.setMaxLifetime(1_800_000);
        cfg.setConnectionTestQuery("SELECT 1");
        this.readPool = new HikariDataSource(cfg);
        log.info("[PgKnowledge] 已创建只读检索连接池（{}:{}/{}, 最大连接={}）", host, port, database, 8);
    }

    /**
     * 启动确保检索所需的 GIN 索引存在（表与向量列由写入器创建/迁移，此处仅补 tsvector 索引）。
     */
    @PostConstruct
    public void init() {
        try (Connection conn = getConnection();
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_memory_tsv
                    ON memory_store USING gin (search_vector)
                    """);
            log.info("[PgKnowledge] 检索索引就绪（gin(search_vector)）");
        } catch (SQLException e) {
            throw new IllegalStateException("[PgKnowledge] 初始化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int saveKnowledge(String teamId, String doc, Map<String, String> meta) {
        String t = Teams.sanitize(teamId);
        StructuredChunker chunker = new StructuredChunker();
        List<StructuredChunker.Chunk> chunks = chunker.chunk(doc, meta);
        for (StructuredChunker.Chunk c : chunks) {
            writer.save(new MemoryEntry(null, "RAG", t, c.text(), c.metadata(),
                    MemoryLevel.LONG_TERM, Instant.now(), null));
        }
        return chunks.size();
    }

    @Override
    public List<MemoryEntry> searchKnowledge(String query, int topK, String teamId, boolean includeGlobal) {
        return hybridSearch(query, topK, teamId, includeGlobal);
    }

    @Override
    public void deleteByMetadata(String teamId, String key, String value) {
        writer.deleteByMetadata(teamId, key, value);
    }

    /**
     * 混合检索：稠密向量 + BM25(ts_rank)，RRF 融合。
     */
    private List<MemoryEntry> hybridSearch(String query, int topK, String teamId, boolean includeGlobal) {
        long t0 = System.currentTimeMillis();
        float[] q = embeddingClient.embed(query == null ? "" : query);
        if (q == null || q.length == 0) {
            q = new float[0];
        }
        String vectorStr = toVectorString(q);
        String t = teamId == null || teamId.isBlank() ? Teams.DEFAULT : Teams.sanitize(teamId);
        String teamFilter = includeGlobal
                ? "(team_id = ? OR team_id = ?)"
                : "team_id = ?";

        // 稠密路：取 topK*2 扩大召回；BM25 路：同样 topK*2
        int widen = Math.max(topK * 2, 20);

        Map<Long, Double> denseRank = new HashMap<>();
        Map<Long, Double> sparseRank = new HashMap<>();
        Map<Long, MemoryEntry> byId = new HashMap<>();

        String denseSql = """
                SELECT id, agent_type, team_id, content, metadata, level, created_at,
                       1 - (embedding <=> ?::vector) AS sim
                FROM memory_store
                WHERE agent_type = 'RAG' AND %s
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(teamFilter);

        String sparseSql = """
                SELECT id, agent_type, team_id, content, metadata, level, created_at,
                       ts_rank(search_vector, plainto_tsquery('simple', ?)) AS bm25
                FROM memory_store
                WHERE agent_type = 'RAG' AND %s
                  AND search_vector @@ plainto_tsquery('simple', ?)
                ORDER BY ts_rank(search_vector, plainto_tsquery('simple', ?)) DESC
                LIMIT ?
                """.formatted(teamFilter);

        try (Connection conn = getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(denseSql)) {
                int idx = 1;
                ps.setString(idx++, vectorStr);
                ps.setString(idx++, t);
                if (includeGlobal) {
                    ps.setString(idx++, Teams.GLOBAL);
                }
                ps.setString(idx++, vectorStr);
                ps.setInt(idx++, widen);
                try (ResultSet rs = ps.executeQuery()) {
                    int rank = 0;
                    while (rs.next()) {
                        MemoryEntry e = mapRow(rs, rs.getDouble("sim"));
                        byId.put(e.id(), e);
                        denseRank.put(e.id(), (double) rank++);
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(sparseSql)) {
                int idx = 1;
                ps.setString(idx++, query == null ? "" : query);
                ps.setString(idx++, t);
                if (includeGlobal) {
                    ps.setString(idx++, Teams.GLOBAL);
                }
                ps.setString(idx++, query == null ? "" : query);
                ps.setString(idx++, query == null ? "" : query);
                ps.setInt(idx++, widen);
                try (ResultSet rs = ps.executeQuery()) {
                    int rank = 0;
                    while (rs.next()) {
                        MemoryEntry e = mapRow(rs, rs.getDouble("bm25"));
                        byId.putIfAbsent(e.id(), e);
                        sparseRank.put(e.id(), (double) rank++);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("[PgKnowledge] 混合检索失败: {}", e.getMessage());
        }

        // RRF 融合
        Map<Long, Double> rrf = new HashMap<>();
        for (var en : denseRank.entrySet()) {
            rrf.merge(en.getKey(), denseWeight / (RRF_K + en.getValue()), Double::sum);
        }
        for (var en : sparseRank.entrySet()) {
            rrf.merge(en.getKey(), (1 - denseWeight) / (RRF_K + en.getValue()), Double::sum);
        }
        List<Map.Entry<Long, Double>> fused = new ArrayList<>(rrf.entrySet());
        fused.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        double maxRrf = fused.isEmpty() ? 1.0 : fused.get(0).getValue();

        List<MemoryEntry> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, fused.size()); i++) {
            MemoryEntry e = byId.get(fused.get(i).getKey());
            double norm = maxRrf > 0 ? fused.get(i).getValue() / maxRrf : 0.0;
            Map<String, String> m = new HashMap<>(e.metadata() == null ? Map.of() : e.metadata());
            m.put("similarity", String.format("%.4f", norm));
            result.add(new MemoryEntry(e.id(), e.agentType(), e.teamId(), e.content(),
                    Map.copyOf(m), e.level(), e.createdAt(), e.embedding()));
        }
        log.info("[PgKnowledge] 混合检索：team={}, topK={}, 融合命中 {} 条, 耗时 {}ms",
                t, topK, result.size(), System.currentTimeMillis() - t0);
        return result;
    }

    private MemoryEntry mapRow(ResultSet rs, double score) throws SQLException {
        long id = rs.getLong("id");
        String agt = rs.getString("agent_type");
        String team = rs.getString("team_id");
        String content = rs.getString("content");
        Map<String, String> meta = fromJson(rs.getString("metadata"));
        MemoryLevel level = MemoryLevel.valueOf(rs.getString("level"));
        Instant createdAt = rs.getTimestamp("created_at") != null
                ? rs.getTimestamp("created_at").toInstant() : Instant.now();
        Map<String, String> m = new HashMap<>(meta);
        m.put("similarity", String.format("%.4f", score));
        return new MemoryEntry(id, agt, team, content, Map.copyOf(m), level, createdAt, null);
    }

    // ===================== 内部工具 =====================

    private Connection getConnection() throws SQLException {
        return readPool.getConnection();
    }

    /** 将 float[] 序列化为 pgvector 文本格式 {@code [v1,v2,…]}。 */
    private String toVectorString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }

    private Map<String, String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
