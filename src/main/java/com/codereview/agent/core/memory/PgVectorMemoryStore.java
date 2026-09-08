package com.codereview.agent.core.memory;

import com.codereview.agent.core.llm.EmbeddingClient;
import com.codereview.agent.core.rag.TextTokenizer;
import com.codereview.agent.tenant.Teams;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 基于 PostgreSQL + pgvector 的向量记忆存储（生产实现）。
 *
 * <p>实现 {@link MemoryStore}：将记忆条目持久化到 PostgreSQL 的 vector 列中，
 * 检索时利用 pgvector 的余弦距离算子 {@code <=>} 进行 ANN 近似最近邻搜索，
 * 性能与可扩展性远超内存实现。
 *
 * <p>建表 DDL 在 {@link #init()} 中自动执行：
 * <pre>
 * CREATE TABLE memory_store (
 *   id BIGSERIAL PRIMARY KEY,
 *   agent_type VARCHAR(100),
 *   team_id VARCHAR(100) NOT NULL DEFAULT '__global__',
 *   content TEXT NOT NULL,
 *   metadata JSONB DEFAULT '{}',
 *   level VARCHAR(20) NOT NULL,
 *   created_at TIMESTAMPTZ DEFAULT now(),
 *   embedding vector(256)
 * );
 * CREATE INDEX … USING ivfflat (embedding vector_cosine_ops);
 * </pre>
 *
 * <p>向量序列化为 pgvector 文本格式 {@code [0.1,0.2,…]}，通过 {@code ::vector}
 * 强制类型转换写入。检索使用 {@code embedding <=> ?::vector} 计算余弦距离，
 * 距离越小越相似（余弦距离 = 1 - 余弦相似度）。
 */
public class PgVectorMemoryStore implements MemoryStore, DisposableBean {

    protected static final Logger log = LoggerFactory.getLogger(PgVectorMemoryStore.class);

    protected final EmbeddingClient embeddingClient;
    private final HikariDataSource hikari;
    private final DataSource dataSource;
    private final int vectorDim;
    /** ANN 索引类型：hnsw（默认，pgvector≥0.5）或 ivfflat（老版本兼容回退）。 */
    private final String indexType;

    /** 存量 search_vector 单次迁移的行上限（超量直接跳过，避免大表把启动拖死）。 */
    private static final int MAX_MIGRATE_ROWS = 100_000;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 构造 PgVector 记忆存储。
     *
     * <p>内部自建并持有 {@link HikariDataSource} 连接池，避免每次操作 {@code DriverManager}
     * 新建连接的开销与连接泄漏风险；池在 {@link #destroy()} 中随存储关闭而释放。
     *
     * @param embeddingClient 向量化客户端（用于文本→向量）
     * @param host            PostgreSQL 主机
     * @param port            端口
     * @param database        数据库名
     * @param username       用户名（trust 认证可空）
     * @param password        密码（trust 认证可空）
     * @param vectorDim       向量维度（需与 EmbeddingClient 输出维度一致）
     */
    public PgVectorMemoryStore(EmbeddingClient embeddingClient,
                               String host, int port, String database,
                               String username, String password,
                               int vectorDim) {
        this(embeddingClient, host, port, database, username, password, vectorDim, "hnsw");
    }

    /**
     * 构造 PgVector 记忆存储（可指定 ANN 索引类型）。
     *
     * @param indexType 索引类型：hnsw（默认，pgvector≥0.5 推荐，召回精度更高）或 ivfflat
     */
    public PgVectorMemoryStore(EmbeddingClient embeddingClient,
                               String host, int port, String database,
                               String username, String password,
                               int vectorDim, String indexType) {
        this.embeddingClient = embeddingClient;
        this.vectorDim = vectorDim;
        this.indexType = (indexType == null || indexType.isBlank()) ? "hnsw" : indexType.toLowerCase();
        if (!"hnsw".equals(this.indexType) && !"ivfflat".equals(this.indexType)) {
            throw new IllegalArgumentException("不支持的向量索引类型: " + indexType + "（仅支持 hnsw / ivfflat）");
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        if (username != null && !username.isBlank()) {
            cfg.setUsername(username);
            cfg.setPassword(password);
        }
        cfg.setPoolName("pgvector-pool");
        cfg.setMaximumPoolSize(10);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(30_000);
        cfg.setIdleTimeout(600_000);
        cfg.setMaxLifetime(1_800_000);
        cfg.setConnectionTestQuery("SELECT 1");
        this.hikari = new HikariDataSource(cfg);
        this.dataSource = this.hikari;
        log.info("[PgVector] 已创建 HikariCP 连接池（{}:{}/{}, 最大连接={}，ANN 索引={}）",
                host, port, database, 10, this.indexType);
    }

    /**
     * 启动时建表、迁移、建索引（幂等）。
     *
     * <p>兼容两类存量库：
     * <ol>
     *   <li>多租户改造前创建的表缺 {@code team_id} 列 → 自动 ALTER 补列；</li>
     *   <li>嵌入模型切换后向量维度变化（如 256 哈希 → 2560 真实向量）→ 备份旧表后重建向量列
     *       （旧向量与新模型不兼容，作废；文本数据保留在备份表）。</li>
     * </ol>
     */
    @PostConstruct
    public void init() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            // 启用 pgvector 扩展
            stmt.execute("CREATE EXTENSION IF NOT EXISTS vector");
            log.info("[PgVector] pgvector 扩展已启用");

            if (!tableExists(conn, "memory_store")) {
                createTable(stmt);
            } else {
                migrate(conn, stmt);
            }

            // 建向量 ANN 索引：HNSW 优先（pgvector≥0.5，召回精度与速度均优），
            // 存量 ivfflat 库自动迁移；HNSW 不可用（老 pgvector）时回退 ivfflat。
            ensureVectorIndex(stmt);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_agent ON memory_store (agent_type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_team ON memory_store (team_id)");

            // 全文检索向量列（BM25 / 混合检索用）。
            // 注意：PG 的 simple 词典**不做中文分词**——无空格的中文整段会被当成一个词位
            // （实测「禁止使用字符串拼接的sql」是一个词位，查「参数绑定」、甚至查「SQL」都不命中），
            // 因此写入侧与检索侧都先经 TextTokenizer（中文 bigram + 标识符子词）预处理。
            if (!columnExists(conn, "search_vector")) {
                stmt.execute("ALTER TABLE memory_store ADD COLUMN search_vector tsvector");
                log.info("[PgVector] 迁移：已补充 search_vector 列（混合检索 BM25 用）");
            }
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_memory_tsv
                    ON memory_store USING gin (search_vector)
                    """);
            // 存量行一次性重建：历史数据是按「未分词原文」建的 tsvector，中文查询命中不了
            migrateTsvectorTokens(conn);

            log.info("[PgVector] 表与索引就绪（vector({}), {} 索引, gin(tsvector)）",
                    vectorDim, indexType);
        } catch (SQLException e) {
            throw new IllegalStateException("[PgVector] 初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 确保向量 ANN 索引存在且为期望类型（hnsw / ivfflat），幂等。
     *
     * <p>策略：
     * <ul>
     *   <li>索引不存在 → 直接按期望类型创建（hnsw 失败则 WARN 回退 ivfflat，老 pgvector 无 hnsw）；</li>
     *   <li>存量库为 ivfflat 而期望 hnsw → DROP 后重建迁移（启动时一次性，数据量小时可接受；
     *       数据量大时仍会执行——hnsw 在 pgvector 0.5+ 均可用，迁移收益明确）；</li>
     *   <li>已为期望类型 → 跳过（幂等，不重复重建大索引）。</li>
     * </ul>
     */
    private void ensureVectorIndex(Statement stmt) throws SQLException {
        String existing = currentEmbeddingIndexType();
        if (indexType.equals(existing)) {
            log.info("[PgVector] 向量索引已就绪（{}），跳过创建", existing);
            return;
        }
        if (existing != null) {
            log.warn("[PgVector] 存量向量索引为 {}，按配置迁移为 {}（DROP 后重建）", existing, indexType);
            stmt.execute("DROP INDEX IF EXISTS idx_memory_embedding");
        }
        try {
            createVectorIndex(stmt, indexType);
        } catch (SQLException e) {
            if ("hnsw".equals(indexType)) {
                // 老 pgvector（<0.5）无 hnsw access method → 回退 ivfflat，保证服务可启动
                log.warn("[PgVector] HNSW 索引创建失败（pgvector < 0.5?），回退 IVFFlat：{}", e.getMessage());
                stmt.execute("DROP INDEX IF EXISTS idx_memory_embedding");
                createVectorIndex(stmt, "ivfflat");
                return;
            }
            throw e;
        }
        log.info("[PgVector] 向量索引已创建：{}", indexType);
    }

    /** 当前 idx_memory_embedding 的索引类型（hnsw / ivfflat），不存在返回 null。 */
    private String currentEmbeddingIndexType() {
        String sql = """
                SELECT indexdef FROM pg_indexes
                WHERE tablename = 'memory_store' AND indexname = 'idx_memory_embedding'
                """;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String def = rs.getString(1);
                if (def != null && def.contains("USING hnsw")) {
                    return "hnsw";
                }
                if (def != null && def.contains("USING ivfflat")) {
                    return "ivfflat";
                }
                return "unknown";
            }
            return null;
        } catch (SQLException e) {
            log.warn("[PgVector] 查询现有向量索引类型失败（按不存在处理）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 存量 {@code search_vector} 一次性重建：历史行是按「未分词原文」建的 tsvector，
     * 中文查询命中不了（simple 词典不做中文分词）。幂等——由 {@code rag_schema_migration}
     * 表记录已执行的一次性迁移，重复启动不会重跑。
     *
     * <p>失败只 WARN 不阻断启动：迁移失败仅影响中文 BM25 召回质量，不应让引擎起不来。
     */
    private void migrateTsvectorTokens(Connection conn) {
        final String name = "tsvector-tokenize-v1";
        try (Statement s = conn.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS rag_schema_migration (
                        name varchar(128) PRIMARY KEY,
                        applied_at timestamptz NOT NULL DEFAULT now()
                    )
                    """);
            try (PreparedStatement chk = conn.prepareStatement(
                    "SELECT 1 FROM rag_schema_migration WHERE name = ?")) {
                chk.setString(1, name);
                try (ResultSet rs = chk.executeQuery()) {
                    if (rs.next()) {
                        return;
                    }
                }
            }

            List<Long> ids = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, content FROM memory_store")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                        contents.add(rs.getString(2));
                    }
                }
            }
            if (ids.size() > MAX_MIGRATE_ROWS) {
                log.warn("[PgVector] 存量 {} 行超过单次迁移上限 {}，跳过（请手工分批重建 search_vector）",
                        ids.size(), MAX_MIGRATE_ROWS);
                return;
            }
            try (PreparedStatement up = conn.prepareStatement(
                    "UPDATE memory_store SET search_vector = to_tsvector('simple', ?) WHERE id = ?")) {
                for (int i = 0; i < ids.size(); i++) {
                    up.setString(1, TextTokenizer.toTokenString(contents.get(i)));
                    up.setLong(2, ids.get(i));
                    up.addBatch();
                }
                up.executeBatch();
            }
            try (PreparedStatement mk = conn.prepareStatement(
                    "INSERT INTO rag_schema_migration (name) VALUES (?) ON CONFLICT DO NOTHING")) {
                mk.setString(1, name);
                mk.executeUpdate();
            }
            log.info("[PgVector] 迁移：已按分词器重建 {} 行的 search_vector（中文/标识符 BM25 生效）", ids.size());
        } catch (SQLException e) {
            log.warn("[PgVector] search_vector 分词迁移失败（不影响启动，中文 BM25 可能仍不命中）：{}",
                    e.getMessage());
        }
    }

    private void createVectorIndex(Statement stmt, String type) throws SQLException {
        if ("hnsw".equals(type)) {
            // HNSW：无需 lists；m=16/ef_construction=64 为 pgvector 默认，显式声明便于调优可观测
            stmt.execute("""
                    CREATE INDEX idx_memory_embedding
                    ON memory_store USING hnsw (embedding vector_cosine_ops)
                    WITH (m = 16, ef_construction = 64)
                    """);
        } else {
            stmt.execute("""
                    CREATE INDEX idx_memory_embedding
                    ON memory_store USING ivfflat (embedding vector_cosine_ops)
                    WITH (lists = 100)
                    """);
        }
    }

    private boolean tableExists(Connection conn, String table) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean columnExists(Connection conn, String column) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM information_schema.columns WHERE table_name = 'memory_store' AND column_name = ?")) {
            ps.setString(1, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 读取 memory_store.embedding 列维度（pgvector 的 atttypmod 即维度；无列返回 -1）。 */
    private int embeddingDim(Connection conn) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT a.atttypmod
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relname = 'memory_store' AND n.nspname = 'public' AND a.attname = 'embedding'
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        }
    }

    private void createTable(Statement stmt) throws SQLException {
        stmt.execute("""
                CREATE TABLE IF NOT EXISTS memory_store (
                    id        BIGSERIAL PRIMARY KEY,
                    agent_type VARCHAR(100),
                    team_id    VARCHAR(100) NOT NULL DEFAULT '__global__',
                    content   TEXT NOT NULL,
                    metadata  JSONB DEFAULT '{}',
                    level     VARCHAR(20) NOT NULL,
                    created_at TIMESTAMPTZ DEFAULT now(),
                    embedding vector(%d),
                    search_vector tsvector
                )
                """.formatted(vectorDim));
        log.info("[PgVector] 已创建 memory_store 表（vector({}) + tsvector）", vectorDim);
    }

    /** 存量表迁移：补 team_id 列 + embedding 维度对齐（幂等）。 */
    private void migrate(Connection conn, Statement stmt) throws SQLException {
        if (!columnExists(conn, "team_id")) {
            stmt.execute("ALTER TABLE memory_store ADD COLUMN team_id VARCHAR(100) NOT NULL DEFAULT '__global__'");
            log.info("[PgVector] 迁移：已为 memory_store 补充 team_id 列（多租户兼容）");
        }
        int curDim = embeddingDim(conn);
        if (curDim > 0 && curDim != vectorDim) {
            String bak = "memory_store_bak_" + Instant.now().getEpochSecond();
            stmt.execute("CREATE TABLE " + bak + " AS SELECT * FROM memory_store");
            log.warn("[PgVector] 迁移：embedding 维度 {} != 期望 {}，已备份旧数据至 {}，" +
                    "重建向量列（旧向量与新嵌入模型不兼容，作废）", curDim, vectorDim, bak);
            stmt.execute("DROP INDEX IF EXISTS idx_memory_embedding");
            stmt.execute("ALTER TABLE memory_store DROP COLUMN IF EXISTS embedding");
            stmt.execute("ALTER TABLE memory_store ADD COLUMN embedding vector(" + vectorDim + ")");
        } else if (curDim < 0) {
            stmt.execute("ALTER TABLE memory_store ADD COLUMN embedding vector(" + vectorDim + ")");
            log.info("[PgVector] 迁移：已为 memory_store 补充 embedding 列（vector({})）", vectorDim);
        }
    }

    /** 构造与当前维度一致的零向量（避免空向量写入/检索报错）。 */
    private float[] zeros() {
        return new float[vectorDim];
    }

    @Override
    public MemoryEntry save(MemoryEntry entry) {
        // 若未携带向量，自动计算（仍为空则用零向量兜底，避免 '[]'::vector 报错）
        float[] embedding = entry.embedding();
        if (embedding == null || embedding.length == 0) {
            embedding = embeddingClient.embed(entry.content());
        }
        if (embedding == null || embedding.length == 0) {
            embedding = zeros();
        }

        long t0 = System.currentTimeMillis();
        String teamId = entry.teamId() == null || entry.teamId().isBlank()
                ? Teams.GLOBAL : entry.teamId();
        String metadataJson = toJson(entry.metadata());
        String vectorStr = toVectorString(embedding);
        Instant createdAt = entry.createdAt() != null ? entry.createdAt() : Instant.now();

        String sql = """
                INSERT INTO memory_store (agent_type, team_id, content, metadata, level, created_at, embedding, search_vector)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?::vector, to_tsvector('simple', ?))
                RETURNING id
                """;

        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, entry.agentType());
            ps.setString(2, teamId);
            ps.setString(3, entry.content());
            ps.setString(4, metadataJson);
            ps.setString(5, entry.level().name());
            ps.setObject(6, java.sql.Timestamp.from(createdAt));
            ps.setString(7, vectorStr);
            ps.setString(8, TextTokenizer.toTokenString(entry.content()));

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long id = rs.getLong(1);
                    log.info("[PgVector] 写入成功：id={}, team={}, agent={}, level={}, 内容={}字符, 向量维度={}, 耗时 {}ms",
                            id, teamId, entry.agentType(), entry.level(), entry.content().length(),
                            embedding.length, System.currentTimeMillis() - t0);
                    return new MemoryEntry(id, entry.agentType(), teamId, entry.content(),
                            entry.metadata(), entry.level(), createdAt, embedding);
                }
            }
            throw new SQLException("INSERT 未返回 id");
        } catch (SQLException e) {
            log.error("[PgVector] 保存失败: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<MemoryEntry> search(String query, String agentType, int topK, String teamId, boolean includeGlobal) {
        long t0 = System.currentTimeMillis();
        float[] q = embeddingClient.embed(query == null ? "" : query);
        if (q == null || q.length == 0) {
            q = zeros();
        }
        String vectorStr = toVectorString(q);
        teamId = teamId == null || teamId.isBlank() ? Teams.DEFAULT : teamId;

        // 团队过滤：始终限定本团队；RAG 检索（includeGlobal）额外纳入全局基线。
        // 使用参数化占位符（不拼常量进 SQL）。
        String teamFilter = includeGlobal
                ? "(team_id = ? OR team_id = ?)"
                : "team_id = ?";

        String sql;
        if (agentType == null) {
            sql = """
                    SELECT id, agent_type, team_id, content, metadata, level, created_at,
                           1 - (embedding <=> ?::vector) AS similarity
                    FROM memory_store
                    WHERE %s
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """.formatted(teamFilter);
        } else {
            sql = """
                    SELECT id, agent_type, team_id, content, metadata, level, created_at,
                           1 - (embedding <=> ?::vector) AS similarity
                    FROM memory_store
                    WHERE agent_type = ? AND %s
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                    """.formatted(teamFilter);
        }

        List<MemoryEntry> results = new ArrayList<>();
        double topSim = -1.0;
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (agentType == null) {
                ps.setString(idx++, vectorStr);
                ps.setString(idx++, teamId);
                if (includeGlobal) {
                    ps.setString(idx++, Teams.GLOBAL);
                }
                ps.setString(idx++, vectorStr);
                ps.setInt(idx++, topK);
            } else {
                ps.setString(idx++, vectorStr);
                ps.setString(idx++, agentType);
                ps.setString(idx++, teamId);
                if (includeGlobal) {
                    ps.setString(idx++, Teams.GLOBAL);
                }
                ps.setString(idx++, vectorStr);
                ps.setInt(idx++, topK);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String agt = rs.getString("agent_type");
                    String t = rs.getString("team_id");
                    String content = rs.getString("content");
                    Map<String, String> meta = fromJson(rs.getString("metadata"));
                    MemoryLevel level = MemoryLevel.valueOf(rs.getString("level"));
                    Instant createdAt = rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toInstant() : Instant.now();
                    double similarity = rs.getDouble("similarity");

                    if (similarity > topSim) {
                        topSim = similarity;
                    }
                    log.debug("[PgVector] 检索命中: id={}, similarity={}", id, similarity);
                    results.add(new MemoryEntry(id, agt, t, content, meta, level, createdAt, null));
                }
            }
        } catch (SQLException e) {
            log.error("[PgVector] 检索失败: {}", e.getMessage());
        }
        log.info("[PgVector] 向量检索完成：agent={}, team={}, includeGlobal={}, topK={}, 命中 {} 条, 最高相似度={}, 耗时 {}ms",
                agentType, teamId, includeGlobal, topK, results.size(),
                results.isEmpty() ? "N/A" : String.format("%.4f", topSim),
                System.currentTimeMillis() - t0);
        return results;
    }

    @Override
    public void deleteByMetadata(String teamId, String key, String value) {
        String t = teamId == null || teamId.isBlank() ? Teams.GLOBAL : teamId;
        String sql = "DELETE FROM memory_store WHERE team_id = ? AND metadata->>? = ?";
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t);
            ps.setString(2, key);
            ps.setString(3, value);
            int n = ps.executeUpdate();
            log.info("[PgVector] 已按团队 {} 删除元数据 {}={} 的向量 {} 条", t, key, value, n);
        } catch (SQLException e) {
            log.error("[PgVector] 删除失败: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        if (hikari != null && !hikari.isClosed()) {
            hikari.close();
            log.info("[PgVector] HikariCP 连接池已关闭（{} 连接释放）", hikari.getHikariPoolMXBean() != null
                    ? hikari.getHikariPoolMXBean().getTotalConnections() : "?");
        }
    }

    // ===================== 内部工具方法 =====================

    protected Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 将 float[] 序列化为 pgvector 文本格式 {@code [v1,v2,…]}。
     */
    protected String toVectorString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }

    /**
     * 将 Map 序列化为 JSON 字符串。
     */
    protected String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 将 JSON 字符串反序列化为 Map。
     */
    protected Map<String, String> fromJson(String json) {
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
