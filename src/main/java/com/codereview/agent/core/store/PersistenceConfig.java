package com.codereview.agent.core.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 统一持久化底座装配：仅在 {@code pgvector.enabled=true}（集群前提）时提供 {@link PgDb}。
 *
 * <p>未启用 PG 时各状态存储回退到内存实现（单机/测试），启动即告警——与
 * {@code InfrastructureConfig} 对 MemoryStore / KnowledgeStore 的可插拔口径一致。
 */
@Configuration
public class PersistenceConfig {

    private static final Logger log = LoggerFactory.getLogger(PersistenceConfig.class);

    /**
     * 统一 PG 连接池 + 表结构初始化。
     */
    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
    public PgDb pgDb(@Value("${pgvector.host:localhost}") String host,
                     @Value("${pgvector.port:5432}") int port,
                     @Value("${pgvector.database:codereview}") String database,
                     @Value("${pgvector.username:}") String username,
                     @Value("${pgvector.password:}") String password) {
        PgDb db = new PgDb(host, port, database, username, password);
        try {
            db.initSchema();
        } catch (Exception e) {
            log.error("[PgDb] 状态表初始化失败，集群部署将不可用：{}", e.getMessage());
            throw new IllegalStateException("[PgDb] 初始化失败: " + e.getMessage(), e);
        }
        return db;
    }

    /** 池随应用关闭释放。 */
    @Bean
    public DisposableBean pgDbCloser(PgDb pgDb) {
        return pgDb::close;
    }
}
