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
public class PgKnowledgeStore implements KnowledgeStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgKnowledgeStore.class);

    /** RRF 常数。 */
    private static final double RRF_K = 60.0;
    /** 稀疏路查询参与检索的最大词数（长 diff 查询截断，防 to_tsquery 过长拖慢检索）。 */
    private static final int SPARSE_QUERY_MAX_TERMS = 40;
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
        return hybridSearch(query, topK, teamId, includeGlobal, null);
    }

    @Override
    public List<MemoryEntry> searchKnowledge(String query, int topK, String teamId,
                                             boolean includeGlobal, java.time.Duration maxAge) {
        return hybridSearch(query, topK, teamId, includeGlobal, maxAge);
    }

    @Override
    public void deleteByMetadata(String teamId, String key, String value) {
        writer.deleteByMetadata(teamId, key, value);
    }

    /**
     * 关闭只读检索连接池（E2E / 容器销毁时调用；写入器连接池由 MemoryStore 负责）。
     */
    @Override
    public void close() {
        if (readPool != null && !readPool.isClosed()) {
            readPool.close();
            log.info("[PgKnowledge] 只读检索连接池已关闭");
        }
    }

    /**
     * 混合检索：稠密向量 + BM25(ts_rank)，RRF 融合。
     *
     * @param maxAge freshness 过滤：仅保留 {@code created_at} 距今不超过 {@code maxAge}
     *               的知识块；null / <=0 表示不过滤（向后兼容默认）。
     */
    private List<MemoryEntry> hybridSearch(String query, int topK, String teamId,
                                           boolean includeGlobal, java.time.Duration maxAge) {
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

        // freshness：超过 maxAge 的陈旧知识不参与召回（null / <=0 = 不过滤）
        String freshSql = "";
        if (maxAge != null && !maxAge.isNegative() && !maxAge.isZero()) {
            freshSql = " AND created_at >= now() - (? || ' seconds')::interval";
        }

        // 稀疏路查询串：中文 bigram + 标识符子词，用 OR 连接。
        // 用 OR 而非 plainto_tsquery 的 AND：RAG 查询是整段 diff/长句，AND 要求全部词命中，
        // 长查询几乎必然零命中（另一种形式的稀疏路失效）；OR 保证「任一关键词命中」即可召回，
        // 由 ts_rank 负责把多词命中的文档排到前面。
        String sparseTsQuery = TextTokenizer.toTsQueryOr(query, SPARSE_QUERY_MAX_TERMS);

        Map<Long, Double> denseRank = new HashMap<>();
        Map<Long, Double> denseSim = new HashMap<>();
        Map<Long, Double> sparseRank = new HashMap<>();
        Map<Long, MemoryEntry> byId = new HashMap<>();

        String denseSql = """
                SELECT id, agent_type, team_id, content, metadata, level, created_at,
                       1 - (embedding <=> ?::vector) AS sim
                FROM memory_store
                WHERE agent_type = 'RAG' AND %s %s
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """.formatted(teamFilter, freshSql);
        // freshSql 为空时是空串，不会产生多余 AND；非空自带前导空格，与 %s 拼接为
        // "AND (team...) AND created_at ..." —— freshSql 前不含模板空格（%s%s 紧贴）。

        String sparseSql = """
                SELECT id, agent_type, team_id, content, metadata, level, created_at,
                       ts_rank(search_vector, to_tsquery('simple', ?)) AS bm25
                FROM memory_store
                WHERE agent_type = 'RAG' AND %s %s
                  AND search_vector @@ to_tsquery('simple', ?)
                ORDER BY ts_rank(search_vector, to_tsquery('simple', ?)) DESC
                LIMIT ?
                """.formatted(teamFilter, freshSql);

        try (Connection conn = getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(denseSql)) {
                int idx = 1;
                ps.setString(idx++, vectorStr);
                ps.setString(idx++, t);
                if (includeGlobal) {
                    ps.setString(idx++, Teams.GLOBAL);
                }
                if (!freshSql.isEmpty()) {
                    ps.setLong(idx++, maxAge.toSeconds());
                }
                ps.setString(idx++, vectorStr);
                ps.setInt(idx++, widen);
                try (ResultSet rs = ps.executeQuery()) {
                    int rank = 0;
                    while (rs.next()) {
                        MemoryEntry e = mapRow(rs, rs.getDouble("sim"));
                        byId.put(e.id(), e);
                        denseSim.put(e.id(), rs.getDouble("sim"));
                        denseRank.put(e.id(), (double) rank++);
                    }
                }
            }
            // 稀疏路：查询为空（或分词后无词）时整段跳过——to_tsquery('simple','') 会抛语法错误
            if (!sparseTsQuery.isBlank()) {
                try (PreparedStatement ps = conn.prepareStatement(sparseSql)) {
                    int idx = 1;
                    ps.setString(idx++, sparseTsQuery);
                    ps.setString(idx++, t);
                    if (includeGlobal) {
                        ps.setString(idx++, Teams.GLOBAL);
                    }
                    if (!freshSql.isEmpty()) {
                        ps.setLong(idx++, maxAge.toSeconds());
                    }
                    ps.setString(idx++, sparseTsQuery);
                    ps.setString(idx++, sparseTsQuery);
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

        // 仅被稀疏路命中的条目没进稠密路 widen 窗口，补算一次真实余弦：
        // 保证 similarity 语义在 Pg / InMemory 两个后端完全一致（阈值闸门才可能生效）。
        fillMissingDenseSim(byId.keySet(), vectorStr, denseSim);

        List<MemoryEntry> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, fused.size()); i++) {
            MemoryEntry e = byId.get(fused.get(i).getKey());
            double norm = maxRrf > 0 ? fused.get(i).getValue() / maxRrf : 0.0;
            Map<String, String> m = new HashMap<>(e.metadata() == null ? Map.of() : e.metadata());
            // similarity = 真实余弦相似度（供 RagEvaluator 的 min-similarity 闸门使用，跨后端可比）；
            // rrfScore = RRF 融合归一化排名分（仅供排序与观测，绝不参与阈值判断）。
            // 二者必须分开：RRF 排名分第一名恒为 1.0、第 50 名仍有 ~0.55，
            // 拿它去比 0.3 的余弦阈值等于闸门永远不关（历史上正是这个 bug 让 abstain 空转）。
            m.put("similarity", String.format("%.4f", denseSim.getOrDefault(e.id(), 0.0)));
            m.put("rrfScore", String.format("%.4f", norm));
            result.add(new MemoryEntry(e.id(), e.agentType(), e.teamId(), e.content(),
                    Map.copyOf(m), e.level(), e.createdAt(), e.embedding()));
        }
        log.info("[PgKnowledge] 混合检索：team={}, topK={}, 融合命中 {} 条, 耗时 {}ms",
                t, topK, result.size(), System.currentTimeMillis() - t0);
        return result;
    }

    /**
     * 为「仅被稀疏路命中」的条目补算真实余弦相似度。
     *
     * <p>稠密路只取 widen 条，稀疏路命中的长尾条目可能不在其中；若不补算，这些条目会沿用
     * {@code mapRow} 写入的 bm25 分，导致 {@code similarity} 语义在同一结果集内都不统一。
     * 补算成本：一条 {@code id IN (...)} 的小查询，仅在确有缺失时执行。
     */
    private void fillMissingDenseSim(java.util.Set<Long> ids, String vectorStr,
                                     Map<Long, Double> denseSim) {
        List<Long> missing = new ArrayList<>();
        for (Long id : ids) {
            if (!denseSim.containsKey(id)) {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        String ph = String.join(",", java.util.Collections.nCopies(missing.size(), "?"));
        String sql = "SELECT id, 1 - (embedding <=> ?::vector) AS sim "
                + "FROM memory_store WHERE id IN (" + ph + ")";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, vectorStr);
            for (int i = 0; i < missing.size(); i++) {
                ps.setLong(i + 2, missing.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    denseSim.put(rs.getLong("id"), rs.getDouble("sim"));
                }
            }
        } catch (SQLException e) {
            log.warn("[PgKnowledge] 补算稠密相似度失败（缺失条目 similarity 记为 0）: {}", e.getMessage());
        }
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
