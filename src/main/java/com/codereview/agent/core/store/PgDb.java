package com.codereview.agent.core.store;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 统一 PostgreSQL 持久化底座（多机集群部署下替代本地 JSON 文件存储）。
 *
 * <p>背景：改造前审查状态（断点/经验/校准/反馈/历史/自定义配置/轨迹/知识元数据）落在
 * {@code data-dir} 本地文件，多实例部署时 A 机沉淀、B 机不可见，必然分叉。
 * 本类持有单一 HikariCP 连接池，向所有 Pg* 存储实现提供连接与表结构初始化，
 * 使每个实例读写同一份 PG 状态——审查进程无本地状态，A/B 双机共享同一视图。
 *
 * <p>设计取舍：
 * <ul>
 *   <li>沿用仓库既有风格（{@code PgVectorMemoryStore} 原生 JDBC + Hikari，零 starter-jdbc），
 *       不引入 Spring JDBC 自动配置，避免改变运行时行为；</li>
 *   <li>仅当 {@code pgvector.enabled=true}（集群前提：PG + pgvector）时由
 *       {@link PersistenceConfig} 装配；未启用时各存储回退内存实现并告警（单机/测试）；</li>
 *   <li>表结构 {@code CREATE TABLE IF NOT EXISTS} 幂等初始化，与现有 {@code memory_store} 的
 *       启动建表方式一致（无 Flyway）。</li>
 * </ul>
 */
public class PgDb implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PgDb.class);

    /** ResultSet → T 行映射器（允许受检异常，由调用层统一捕获）。 */
    public interface RowMapper<T> {
        T map(ResultSet rs) throws Exception;
    }

    private final HikariDataSource hikari;

    public PgDb(String host, int port, String database, String username, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + database);
        if (username != null && !username.isBlank()) {
            cfg.setUsername(username);
            cfg.setPassword(password == null ? "" : password);
        }
        cfg.setPoolName("pg-persistence-pool");
        cfg.setMaximumPoolSize(12);
        cfg.setMinimumIdle(2);
        cfg.setConnectionTimeout(30_000);
        cfg.setIdleTimeout(600_000);
        cfg.setMaxLifetime(1_800_000);
        cfg.setConnectionTestQuery("SELECT 1");
        this.hikari = new HikariDataSource(cfg);
        log.info("[PgDb] 已创建统一持久化连接池（{}:{}/{}, 最大连接={}）", host, port, database, 12);
    }

    /** 取一个连接（调用方负责关闭）。 */
    public Connection conn() throws SQLException {
        return hikari.getConnection();
    }

    /** 执行一次更新（INSERT/UPDATE/DELETE/DDL），参数按位置绑定。 */
    public int update(String sql, Object... args) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("[PgDb] 更新失败: " + e.getMessage() + " | sql=" + sql, e);
        }
    }

    /** 查询多行。 */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        try (Connection c = conn(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            List<T> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("[PgDb] 查询失败: " + e.getMessage() + " | sql=" + sql, e);
        }
    }

    /** 查询单行（无结果返回空）。 */
    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
        return query(sql, mapper, args).stream().findFirst();
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    /**
     * 幂等初始化全部状态表（启动时调用一次）。
     */
    public void initSchema() throws SQLException {
        try (Connection c = conn(); Statement stmt = c.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS resume_state (
                        run_id     TEXT PRIMARY KEY,
                        team_id    TEXT NOT NULL,
                        payload    JSONB NOT NULL,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_resume_team ON resume_state (team_id)");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS team_kv (
                        team_id    TEXT NOT NULL,
                        scope      TEXT NOT NULL,
                        payload    JSONB NOT NULL DEFAULT '{}'::jsonb,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        PRIMARY KEY (team_id, scope)
                    )""");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS experience_entry (
                        id            BIGSERIAL PRIMARY KEY,
                        team_id       TEXT NOT NULL,
                        pattern       TEXT NOT NULL,
                        advice        TEXT NOT NULL,
                        stage         VARCHAR(16) NOT NULL DEFAULT 'candidate',
                        evidence_pos  INT NOT NULL DEFAULT 0,
                        evidence_neg  INT NOT NULL DEFAULT 0,
                        hit_count     INT NOT NULL DEFAULT 0,
                        last_hit_at   TIMESTAMPTZ,
                        source        VARCHAR(32),
                        created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                        UNIQUE (team_id, pattern)
                    )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_exp_team_stage ON experience_entry (team_id, stage)");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS review_feedback (
                        id         BIGSERIAL PRIMARY KEY,
                        team_id    TEXT NOT NULL,
                        payload    JSONB NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_feedback_team ON review_feedback (team_id)");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS review_history (
                        id         BIGSERIAL PRIMARY KEY,
                        team_id    TEXT NOT NULL,
                        hkey       TEXT NOT NULL,
                        payload    JSONB NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_history_team_key ON review_history (team_id, hkey, id)");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS trajectory_store (
                        run_id     TEXT PRIMARY KEY,
                        team_id    TEXT NOT NULL,
                        events     JSONB NOT NULL DEFAULT '[]'::jsonb,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS knowledge_meta (
                        id          TEXT PRIMARY KEY,
                        team_id     TEXT NOT NULL,
                        filename    TEXT,
                        source      TEXT,
                        category    TEXT,
                        type        TEXT,
                        indexed     BOOLEAN NOT NULL DEFAULT FALSE,
                        chunk_count INT NOT NULL DEFAULT 0,
                        size_bytes  BIGINT NOT NULL DEFAULT 0,
                        content     TEXT,
                        created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_kmeta_team ON knowledge_meta (team_id)");
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS calibration_accuracy (
                        rule_id    TEXT PRIMARY KEY,
                        accuracy   DOUBLE PRECISION NOT NULL DEFAULT 1.0,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    )""");
            log.info("[PgDb] 状态表已就绪（resume_state / team_kv / experience_entry / review_feedback / review_history / trajectory_store / knowledge_meta / calibration_accuracy）");
        }
    }

    @Override
    public void close() {
        hikari.close();
    }
}
