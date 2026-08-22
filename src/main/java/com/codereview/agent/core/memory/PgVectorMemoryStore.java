package com.codereview.agent.core.memory;

import com.codereview.agent.core.llm.EmbeddingClient;
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

    private static final Logger log = LoggerFactory.getLogger(PgVectorMemoryStore.class);

    private final EmbeddingClient embeddingClient;
    private final HikariDataSource hikari;
    private final DataSource dataSource;
    private final int vectorDim;

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
        this.embeddingClient = embeddingClient;
        this.vectorDim = vectorDim;

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
        log.info("[PgVector] 已创建 HikariCP 连接池（{}:{}/{}, 最大连接={}）", host, port, database, 10);
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

            // 建索引：IVFFlat 近似最近邻索引（余弦距离）
            stmt.execute("""
                    CREATE INDEX IF NOT EXISTS idx_memory_embedding
                    ON memory_store USING ivfflat (embedding vector_cosine_ops)
                    WITH (lists = 100)
                    """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_agent ON memory_store (agent_type)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_memory_team ON memory_store (team_id)");

            log.info("[PgVector] 表与索引就绪（vector({}), ivfflat 索引）", vectorDim);
        } catch (SQLException e) {
            throw new IllegalStateException("[PgVector] 初始化失败: " + e.getMessage(), e);
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
                    embedding vector(%d)
                )
                """.formatted(vectorDim));
        log.info("[PgVector] 已创建 memory_store 表（vector({})）", vectorDim);
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
                INSERT INTO memory_store (agent_type, team_id, content, metadata, level, created_at, embedding)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?::vector)
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

        // 团队过滤：始终限定本团队；RAG 检索（includeGlobal）额外纳入全局基线
        String teamFilter = includeGlobal
                ? "(team_id = ? OR team_id = '" + Teams.GLOBAL + "')"
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
                ps.setString(idx++, vectorStr);
                ps.setInt(idx++, topK);
            } else {
                ps.setString(idx++, vectorStr);
                ps.setString(idx++, agentType);
                ps.setString(idx++, teamId);
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

    private Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * 将 float[] 序列化为 pgvector 文本格式 {@code [v1,v2,…]}。
     */
    private String toVectorString(float[] vec) {
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
    private String toJson(Map<String, String> map) {
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
