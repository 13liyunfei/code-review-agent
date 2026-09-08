package com.codereview.agent.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 核心依赖健康检查：聚合 PostgreSQL(pgvector) 的连通性，
 * 供 {@code /health}（兼 {@code /actuator/health}）暴露给容器编排做存活/就绪探针。
 *
 * <p>仅当对应组件 {@code enabled=true} 时才检查；未启用则跳过该组件。
 * 使用与业务一致的轻量连接方式（DriverManager），不引入额外连接池或第三方依赖。
 *
 * <p><b>历史说明</b>：曾同时检查 Redis（消息队列时代），2026-09-08 随
 * {@code core/mq} 死代码清理移除——Redis 已无业务消费者，不再是系统组件。
 */
@Component
public class CoreHealthIndicator {

    private final boolean pgEnabled;
    private final String pgHost;
    private final int pgPort;
    private final String pgDatabase;
    private final String pgUsername;
    private final String pgPassword;

    public CoreHealthIndicator(
            @Value("${pgvector.enabled:false}") boolean pgEnabled,
            @Value("${pgvector.host:localhost}") String pgHost,
            @Value("${pgvector.port:5432}") int pgPort,
            @Value("${pgvector.database:codereview}") String pgDatabase,
            @Value("${pgvector.username:}") String pgUsername,
            @Value("${pgvector.password:}") String pgPassword) {
        this.pgEnabled = pgEnabled;
        this.pgHost = pgHost;
        this.pgPort = pgPort;
        this.pgDatabase = pgDatabase;
        this.pgUsername = pgUsername;
        this.pgPassword = pgPassword;
    }

    public HealthResult check() {
        Map<String, String> details = new LinkedHashMap<>();
        boolean healthy = true;
        if (pgEnabled) {
            try (Connection conn = DriverManager.getConnection(
                    "jdbc:postgresql://" + pgHost + ":" + pgPort + "/" + pgDatabase, pgUsername, pgPassword);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT 1");
                details.put("pgvector", "up");
            } catch (Exception e) {
                healthy = false;
                details.put("pgvector", "down: " + e.getMessage());
            }
        }
        return new HealthResult(healthy ? "UP" : "DOWN", details);
    }

    /** 健康检查结果。 */
    public static class HealthResult {
        public final String status;
        public final Map<String, String> details;

        public HealthResult(String status, Map<String, String> details) {
            this.status = status;
            this.details = details;
        }
    }
}
