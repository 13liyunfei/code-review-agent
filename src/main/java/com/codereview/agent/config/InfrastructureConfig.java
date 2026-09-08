package com.codereview.agent.config;

import com.codereview.agent.core.llm.EmbeddingClient;
import com.codereview.agent.core.memory.InMemoryVectorStore;
import com.codereview.agent.core.memory.MemoryStore;
import com.codereview.agent.core.memory.PgVectorMemoryStore;
import com.codereview.agent.core.rag.InMemoryKnowledgeStore;
import com.codereview.agent.core.rag.KnowledgeStore;
import com.codereview.agent.core.rag.PgKnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 基础设施配置：按配置自动选择向量存储与 RAG 知识库的实现。
 *
 * <p>设计遵循「可插拔」原则：
 * <ul>
 *   <li>向量存储：配置 {@code pgvector.enabled=true} 时启用 {@link PgVectorMemoryStore}，
 *       否则回退到 {@link InMemoryVectorStore}（离线可用）。</li>
 *   <li>RAG 知识库：配置 {@code pgvector.enabled=true} 时启用 {@link PgKnowledgeStore}，
 *       否则回退到 {@link InMemoryKnowledgeStore}（离线可用）。</li>
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
                                           @Value("${pgvector.vector-dim:256}") int vectorDim,
                                           @Value("${pgvector.index-type:hnsw}") String indexType) {
        log.info("已启用 PgVector 记忆存储（{}:{}/{}, dim={}, ANN 索引={}）", host, port, database, vectorDim, indexType);
        return new PgVectorMemoryStore(embeddingClient, host, port, database, username, password, vectorDim, indexType);
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

    // ===================== RAG 知识库（KnowledgeStore，与 MemoryStore 分层） =====================

    /**
     * 生产实现：PostgreSQL + pgvector + tsvector 混合检索知识库。
     *
     * <p>仅当 {@code pgvector.enabled=true} 时激活；复用同一 PG 连接与 {@code memory_store} 表，
     * 但检索限定 {@code agent_type=RAG} 并叠加 BM25（tsvector）+ 向量 RRF 融合，
     * 与经验/记忆（{@code MemoryStore}）在调用方分层，互不串扰。
     */
    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
    public KnowledgeStore pgKnowledgeStore(EmbeddingClient embeddingClient,
                                           MemoryStore memoryStore,
                                           @Value("${pgvector.host:localhost}") String host,
                                           @Value("${pgvector.port:5432}") int port,
                                           @Value("${pgvector.database:codereview}") String database,
                                           @Value("${pgvector.username:}") String username,
                                           @Value("${pgvector.password:}") String password,
                                           @Value("${pgvector.vector-dim:256}") int vectorDim) {
        log.info("已启用 PgKnowledgeStore（混合检索：向量+BM25+RRF，{}:{}/{}, dim={}）",
                host, port, database, vectorDim);
        return new PgKnowledgeStore(embeddingClient, memoryStore, host, port, database, username, password, vectorDim);
    }

    /**
     * 离线实现：内存混合检索知识库（BM25 + 向量 + RRF，完整能力，可离线运行与测试）。
     *
     * <p>当 {@code pgvector.enabled} 缺失或为 false 时激活（matchIfMissing=true）。
     */
    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "false", matchIfMissing = true)
    public KnowledgeStore inMemoryKnowledgeStore(EmbeddingClient embeddingClient) {
        log.warn("未启用 pgvector，RAG 知识库回退到 InMemoryKnowledgeStore（混合检索能力齐全，数据不持久化）");
        return new InMemoryKnowledgeStore(embeddingClient);
    }
}
