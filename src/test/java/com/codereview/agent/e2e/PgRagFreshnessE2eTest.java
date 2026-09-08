package com.codereview.agent.e2e;

import com.codereview.agent.core.llm.EmbeddingClient;
import com.codereview.agent.core.memory.MemoryEntry;
import com.codereview.agent.core.rag.PgKnowledgeStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【RAG freshness 真库 E2E】验证 PgKnowledgeStore 的 maxAge 过滤在真实 PostgreSQL 上生效
 * （SQL 绑定顺序 / created_at 条件正确性——纯 InMemory 测试覆盖不到 PG 特有拼接）。
 *
 * <p>策略：向 memory_store 插入两条同 team RAG 数据（一条 now、一条 100 天前），
 * 用零向量（1024 维）做稠密查询（语义上两者等价，唯一变量是 freshness），
 * 断言 maxAge=30 天时只召回新鲜条目。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgRagFreshnessE2eTest {

    private static final String HOST = "localhost";
    private static final int PORT = 5432;
    private static final String DB = "codereview";
    private static final String USER = "yunfei";
    private static final int DIM = 1024;

    private static boolean pgUp;

    /** 1024 维零向量嵌入桩：检索时避免维度 mismatch（dense 路不区分内容，让 freshness 成为唯一变量）。 */
    private static final EmbeddingClient ZERO_EMBED = new EmbeddingClient() {
        @Override
        public float[] embed(String text) {
            return new float[DIM];
        }
    };

    @BeforeAll
    static void requirePg() {
        try (Connection c = DriverManager.getConnection("jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB, USER, "")) {
            pgUp = true;
        } catch (Throwable t) {
            pgUp = false;
        }
        Assumptions.assumeTrue(pgUp, "无本地 PostgreSQL，跳过 RAG freshness E2E");
    }

    private String zeroVectorSql() {
        return "array_fill(0::float8, ARRAY[" + DIM + "])::vector";
    }

    private void insert(String team, String content, String ageSql) throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB, USER, "");
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO memory_store (agent_type, team_id, content, metadata, level, created_at, embedding, search_vector) "
                    + "VALUES ('RAG', '" + team + "', '" + content + "', '{\"source\":\"e2e-fresh\"}', 'LONG_TERM', "
                    + ageSql + ", " + zeroVectorSql() + ", to_tsvector('simple', '" + content + "'))");
        }
    }

    private void cleanup(String team) throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB, USER, "");
             Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM memory_store WHERE team_id = '" + team + "'");
        }
    }

    @Test
    void freshnessFiltersStaleRowsOnRealPg() throws Exception {
        String team = "e2e-fresh-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            insert(team, "freshrule 最新规范 参数绑定 prepared statement", "now()");
            insert(team, "stalerecord 陈旧记录 已废弃的明文密码规则", "now() - interval '100 days'");

            PgKnowledgeStore store = new PgKnowledgeStore(ZERO_EMBED, null,
                    HOST, PORT, DB, USER, "", DIM);

            // 无 maxAge → 两条都召回（freshness 默认关闭）
            List<MemoryEntry> all = store.searchKnowledge("规范 规则", 10, team, false);
            assertEquals(2, all.size(), "无 maxAge 时应召回新旧两条");

            // maxAge=30 天 → 只召回 100 天内入库的新条目
            List<MemoryEntry> fresh = store.searchKnowledge("规范 规则", 10, team, false,
                    Duration.ofDays(30));
            assertEquals(1, fresh.size(), "maxAge=30 天时应过滤掉 100 天前的陈旧条目");
            assertTrue(fresh.get(0).content().contains("freshrule"),
                    "剩余条目应为新鲜条目，实际: " + fresh.get(0).content());
            store.close();
        } finally {
            cleanup(team);
        }
    }

    @Test
    void nonRagRowsUntouchedByFreshnessSearch() throws Exception {
        // 检索限定 agent_type='RAG'：即使库里有经验类（EXPERIENCE）旧行也不应被召回
        String team = "e2e-nonrag-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            try (Connection c = DriverManager.getConnection(
                    "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB, USER, "");
                 Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO memory_store (agent_type, team_id, content, metadata, level, created_at, embedding, search_vector) "
                        + "VALUES ('EXPERIENCE', '" + team + "', '经验 不在 RAG 视角', '{}', 'LONG_TERM', now(), "
                        + zeroVectorSql() + ", to_tsvector('simple', '经验 不在 RAG 视角'))");
            }
            PgKnowledgeStore store = new PgKnowledgeStore(ZERO_EMBED, null,
                    HOST, PORT, DB, USER, "", DIM);
            List<MemoryEntry> hits = store.searchKnowledge("经验", 10, team, false);
            assertTrue(hits.isEmpty(), "EXPERIENCE 行不应出现在 RAG 检索结果中");
            store.close();
        } finally {
            cleanup(team);
        }
    }
}
