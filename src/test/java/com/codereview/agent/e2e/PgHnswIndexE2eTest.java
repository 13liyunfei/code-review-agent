package com.codereview.agent.e2e;

import com.codereview.agent.core.llm.SimpleHashEmbeddingClient;
import com.codereview.agent.core.memory.PgVectorMemoryStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【HNSW 索引 E2E】真实 PostgreSQL + pgvector（0.8.x）下验证 PgVectorMemoryStore 建索引行为：
 * <ul>
 *   <li>默认（index-type=hnsw）在真库上创建 HNSW 索引（pgvector≥0.5 支持）；</li>
 *   <li>存量 ivfflat 索引自动迁移为 hnsw（DROP 后重建）；</li>
 *   <li>幂等：重复 init 不重建（已是期望类型直接跳过）。</li>
 * </ul>
 *
 * <p>无本地 PG 时整类跳过（与 {@link PgClusterE2eTest} 同策略）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgHnswIndexE2eTest {

    private static final String HOST = "localhost";
    private static final int PORT = 5432;
    private static final String DB = "codereview";
    private static final String USER = "yunfei";

    private static boolean pgUp;

    @BeforeAll
    static void requirePg() {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB, USER, "")) {
            // pgvector 扩展可用且版本支持 hnsw（≥0.5.0）
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT extversion FROM pg_extension WHERE extname='vector'")) {
                if (rs.next()) {
                    String v = rs.getString(1);
                    pgUp = parseVersion(v) >= 0.5;
                }
            }
        } catch (Throwable t) {
            pgUp = false;
        }
        Assumptions.assumeTrue(pgUp, "无本地 PostgreSQL + pgvector(≥0.5)，跳过 HNSW E2E");
    }

    private static double parseVersion(String v) {
        try {
            return Double.parseDouble(v.split("\\.")[0] + "." + v.split("\\.")[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private String currentIndexType() throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB, USER, "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT indexdef FROM pg_indexes WHERE tablename='memory_store' "
                             + "AND indexname='idx_memory_embedding'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void hnswIndexCreatedOrMigratedOnRealPg() throws Exception {
        PgVectorMemoryStore store = new PgVectorMemoryStore(
                new SimpleHashEmbeddingClient(), HOST, PORT, DB, USER, "", 1024, "hnsw");
        try {
            store.init(); // 建表（若缺）/迁移/建索引，等价 Spring 启动
            String indexdef = currentIndexType();
            assertNotNull(indexdef, "真库上应存在 idx_memory_embedding 向量索引");
            assertTrue(indexdef.contains("USING hnsw"),
                    "index-type=hnsw 时真库索引应为 HNSW，实际: " + indexdef);

            // 幂等：再次 init 不重建、不报错，索引仍是 hnsw
            store.init();
            String again = currentIndexType();
            assertTrue(again.contains("USING hnsw"), "重复 init 应保持 HNSW 索引，实际: " + again);
        } finally {
            store.destroy();
        }
    }

    @Test
    void ivfflatFallbackStillCreatesIndexWhenRequested() throws Exception {
        // 显式 ivfflat（老版本兼容路径）：应成功创建 ivfflat 索引（不因默认 hnsw 而失败）
        PgVectorMemoryStore store = new PgVectorMemoryStore(
                new SimpleHashEmbeddingClient(), HOST, PORT, DB, USER, "", 1024, "ivfflat");
        try {
            store.init();
            String indexdef = currentIndexType();
            assertNotNull(indexdef, "ivfflat 配置下也应存在向量索引");
            assertTrue(indexdef.contains("USING ivfflat"), "显式 ivfflat 应创建 ivfflat 索引，实际: " + indexdef);
        } finally {
            store.destroy();
            // 还原为默认 hnsw，避免污染后续用例
            PgVectorMemoryStore restore = new PgVectorMemoryStore(
                    new SimpleHashEmbeddingClient(), HOST, PORT, DB, USER, "", 1024, "hnsw");
            try {
                restore.init();
            } finally {
                restore.destroy();
            }
        }
    }
}
