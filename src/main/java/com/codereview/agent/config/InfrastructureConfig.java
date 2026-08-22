package com.codereview.agent.config;

import com.codereview.agent.core.llm.EmbeddingClient;
import com.codereview.agent.core.memory.InMemoryVectorStore;
import com.codereview.agent.core.memory.MemoryStore;
import com.codereview.agent.core.memory.PgVectorMemoryStore;
import com.codereview.agent.core.mq.InMemoryMessageQueue;
import com.codereview.agent.core.mq.MessageQueue;
import com.codereview.agent.core.mq.RedisMessageQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基础设施配置：按配置自动选择向量存储与消息队列的实现。
 *
 * <p>设计遵循「可插拔」原则：
 * <ul>
 *   <li>向量存储：配置 {@code pgvector.enabled=true} 时启用 {@link PgVectorMemoryStore}，
 *       否则回退到 {@link InMemoryVectorStore}（离线可用）。</li>
 *   <li>消息队列：配置 {@code redis.enabled=true} 时启用 {@link RedisMessageQueue}，
 *       否则回退到 {@link InMemoryMessageQueue}（离线可用）。</li>
 * </ul>
 * 切换只需改配置，业务代码零改动。
 */
@Configuration
public class InfrastructureConfig {

    private static final Logger log = LoggerFactory.getLogger(InfrastructureConfig.class);

    // ===================== 向量存储（MemoryStore） =====================

    /**
     * 生产实现：PostgreSQL + pgvector 向量存储。
     *
     * <p>仅当 {@code pgvector.enabled=true} 时激活。
     */
    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
    public MemoryStore pgVectorMemoryStore(EmbeddingClient embeddingClient,
                                           @Value("${pgvector.host:localhost}") String host,
                                           @Value("${pgvector.port:5432}") int port,
                                           @Value("${pgvector.database:codereview}") String database,
                                           @Value("${pgvector.username:}") String username,
                                           @Value("${pgvector.password:}") String password,
                                           @Value("${pgvector.vector-dim:256}") int vectorDim) {
        log.info("已启用 PgVector 记忆存储（{}:{}/{}, dim={}）", host, port, database, vectorDim);
        return new PgVectorMemoryStore(embeddingClient, host, port, database, username, password, vectorDim);
    }

    /**
     * 离线实现：内存向量存储。
     *
     * <p>当 {@code pgvector.enabled} 缺失或为 false 时激活（matchIfMissing=true）。
     */
    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "false", matchIfMissing = true)
    public MemoryStore inMemoryVectorStore(EmbeddingClient embeddingClient) {
        log.warn("未启用 pgvector，回退到 InMemoryVectorStore（数据不持久化，重启丢失）");
        return new InMemoryVectorStore(embeddingClient);
    }

    // ===================== 消息队列（MessageQueue） =====================

    /**
     * 生产实现：Redis 消息队列。
     *
     * <p>仅当 {@code redis.enabled=true} 时激活。
     */
    @Bean
    @ConditionalOnProperty(name = "redis.enabled", havingValue = "true")
    public MessageQueue redisMessageQueue(@Value("${redis.host:localhost}") String host,
                                          @Value("${redis.port:6379}") int port,
                                          @Value("${redis.password:}") String password,
                                          @Value("${redis.connect-timeout-ms:2000}") int connectTimeoutMs,
                                          @Value("${redis.so-timeout-ms:30000}") int soTimeoutMs) {
        log.info("已启用 Redis 消息队列（{}:{}）", host, port);
        return new RedisMessageQueue(host, port, password, connectTimeoutMs, soTimeoutMs);
    }

    /**
     * 离线实现：内存消息队列。
     *
     * <p>当 {@code redis.enabled} 缺失或为 false 时激活（matchIfMissing=true）。
     */
    @Bean
    @ConditionalOnProperty(name = "redis.enabled", havingValue = "false", matchIfMissing = true)
    public MessageQueue inMemoryMessageQueue() {
        log.warn("未启用 Redis，回退到 InMemoryMessageQueue（单机内存，不跨进程）");
        return new InMemoryMessageQueue();
    }
}
