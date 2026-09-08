package com.codereview.agent.e2e;

import com.codereview.agent.core.llm.EmbeddingClient;
import com.codereview.agent.core.memory.MemoryEntry;
import com.codereview.agent.core.memory.PgVectorMemoryStore;
import com.codereview.agent.core.rag.PgKnowledgeStore;
import com.codereview.agent.core.rag.RagEvaluator;
import com.codereview.agent.core.rag.TextTokenizer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【RAG 语义修复真库 E2E】三个业界对比暴露出的硬伤，均需真实 PostgreSQL 才能证伪：
 *
 * <ol>
 *   <li><b>中文/标识符分词</b>：PG {@code simple} 词典不切中文——分词行应命中、未分词行不应命中
 *       （对照组证明修复前是坏的）；并验证存量迁移能把未分词行重建为可命中。</li>
 *   <li><b>similarity 语义</b>：PG 路径必须写<b>真实余弦</b>而非 RRF 归一化分，
 *       否则 {@code min-similarity} 闸门在生产恒不触发（abstain 空转）。</li>
 *   <li><b>golden 排序指标</b>：真库上跑 hit@k / MRR，把排序质量纳入回归。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgRagSemanticsE2eTest {

    private static final String HOST = "localhost";
    private static final int PORT = 5432;
    private static final String DB = "codereview";
    private static final String USER = "yunfei";
    private static final int DIM = 1024;

    private static boolean pgUp;

    /** 受控向量桩：按文本命中的关键词映射到不同正交方向，使稠密路排序可预测。 */
    private static final EmbeddingClient CONTROLLED = text -> {
        float[] v = new float[DIM];
        String t = text == null ? "" : text;
        if (t.contains("参数绑定")) {
            v[0] = 1f;          // 正解方向
        } else if (t.contains("明文密码")) {
            v[1] = 1f;          // 干扰方向 1
        } else {
            v[2] = 1f;          // 干扰方向 2
        }
        return v;
    };

    @BeforeAll
    static void requirePg() {
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB, USER, "")) {
            pgUp = true;
        } catch (Throwable t) {
            pgUp = false;
        }
        Assumptions.assumeTrue(pgUp, "无本地 PostgreSQL，跳过 RAG 语义 E2E");
    }

    private static String vec(float[] v) {
        StringBuilder sb = new StringBuilder("'[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append("]'::vector").toString();
    }

    private static float[] unitVector(int index) {
        float[] v = new float[DIM];
        v[index] = 1f;
        return v;
    }

    /** 插入一行：{@code tokenized=false} 模拟修复前的「未分词」存量数据。 */
    private void insert(String team, String content, float[] embedding, boolean tokenized) throws Exception {
        String tsv = tokenized
                ? TextTokenizer.toTokenString(content)
                : content;
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB, USER, "");
             Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO memory_store (agent_type, team_id, content, metadata, level, created_at, embedding, search_vector) "
                    + "VALUES ('RAG', '" + team + "', '" + content + "', '{\"source\":\"e2e-sem\"}', 'LONG_TERM', now(), "
                    + vec(embedding) + ", to_tsvector('simple', '" + tsv + "'))");
        }
    }

    private void cleanup(String... teams) throws Exception {
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB, USER, "");
             Statement s = c.createStatement()) {
            for (String team : teams) {
                s.executeUpdate("DELETE FROM memory_store WHERE team_id = '" + team + "'");
            }
        }
    }

    private boolean matches(String team, String query) throws Exception {
        String tsq = TextTokenizer.toTsQueryOr(query, 20);
        try (Connection c = DriverManager.getConnection(
                "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB, USER, "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT 1 FROM memory_store WHERE team_id = '" + team
                             + "' AND search_vector @@ to_tsquery('simple', '" + tsq + "')")) {
            return rs.next();
        }
    }

    @Test
    void chineseAndIdentifierTokensMatchOnlyWhenTokenized() throws Exception {
        String tokenTeam = "e2e-tok-" + UUID.randomUUID().toString().substring(0, 8);
        String rawTeam = "e2e-raw-" + UUID.randomUUID().toString().substring(0, 8);
        String content = "禁止使用字符串拼接的SQL，必须使用参数绑定或PreparedStatement";
        try {
            insert(tokenTeam, content, unitVector(0), true);
            insert(rawTeam, content, unitVector(0), false);

            // 修复后：中文词与英文标识符都能命中
            assertTrue(matches(tokenTeam, "参数绑定"), "分词行应被中文词『参数绑定』命中");
            assertTrue(matches(tokenTeam, "PreparedStatement"), "分词行应被英文标识符命中");

            // 对照组：未分词（修复前的行为）两者都不命中——这正是 BM25 路空转的根因
            assertFalse(matches(rawTeam, "参数绑定"), "未分词行不应命中中文词（对照：修复前行为）");
            assertFalse(matches(rawTeam, "PreparedStatement"), "未分词行不应命中英文词（对照：修复前行为）");

            // 存量迁移：清掉迁移标记后 init() 应把未分词行重建为可命中
            try (Connection c = DriverManager.getConnection(
                    "jdbc:postgresql://" + HOST + ":" + PORT + "/" + DB, USER, "");
                 Statement s = c.createStatement()) {
                // 纯单测 JVM 里没有 Spring 上下文，迁移表可能尚未创建
                s.executeUpdate("CREATE TABLE IF NOT EXISTS rag_schema_migration ("
                        + "name varchar(128) PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
                s.executeUpdate("DELETE FROM rag_schema_migration WHERE name = 'tsvector-tokenize-v1'");
            }
            PgVectorMemoryStore store = new PgVectorMemoryStore(CONTROLLED, HOST, PORT, DB, USER, "", DIM);
            store.init();
            store.destroy();
            assertTrue(matches(rawTeam, "参数绑定"), "迁移重建后，存量未分词行也应能被中文词命中");
        } finally {
            cleanup(tokenTeam, rawTeam);
        }
    }

    @Test
    void similarityIsTrueCosineSoAbstainGateWorks() throws Exception {
        String team = "e2e-cos-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            // 文档向量取反方向：与查询向量余弦 = -1
            float[] neg = unitVector(0);
            neg[0] = -1f;
            insert(team, "完全不相关的历史规范条目", neg, true);

            // 查询向量恒为 +e0 → 与上面条目余弦 -1
            EmbeddingClient positive = text -> unitVector(0);
            PgKnowledgeStore store = new PgKnowledgeStore(positive, null, HOST, PORT, DB, USER, "", DIM);
            List<MemoryEntry> hits = store.searchKnowledge("任何查询", 10, team, false);
            assertFalse(hits.isEmpty(), "应召回到该条目（用于验证 similarity 语义）");

            double sim = Double.parseDouble(hits.get(0).metadata().get("similarity"));
            assertTrue(sim < 0,
                    "similarity 必须是真实余弦（此处为 -1）；若是 RRF 归一化分则恒 >0.5，实际 " + sim);
            assertTrue(hits.get(0).metadata().containsKey("rrfScore"),
                    "RRF 排名分应另存 rrfScore，不得占用 similarity");

            // 关键回归：真实余弦下闸门才可能关闭（修复前 0.87 的 RRF 分永远过不了 0.3 阈值）
            RagEvaluator evaluator = new RagEvaluator(0.3, true);
            assertTrue(evaluator.filterByThreshold(hits).isEmpty(),
                    "低于 min-similarity 的候选应被拦截（abstain 生效），实际放行 " + sim);
            store.close();
        } finally {
            cleanup(team);
        }
    }

    @Test
    void goldenHitAtKAndMrrOnRealPg() throws Exception {
        String team = "e2e-gold-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            String gold = "SQL 注入规范：必须使用参数绑定，禁止字符串拼接";
            String noise1 = "明文密码规范：禁止硬编码密钥";
            String noise2 = "日志规范：禁止打印敏感信息";
            insert(team, gold, CONTROLLED.embed(gold), true);
            insert(team, noise1, CONTROLLED.embed(noise1), true);
            insert(team, noise2, CONTROLLED.embed(noise2), true);

            PgKnowledgeStore store = new PgKnowledgeStore(CONTROLLED, null, HOST, PORT, DB, USER, "", DIM);
            List<MemoryEntry> hits = store.searchKnowledge("SQL 注入 参数绑定", 10, team, false);
            assertFalse(hits.isEmpty(), "golden 查询应召回候选");

            // 正解 = 含『参数绑定』且属于 SQL 注入规范的条目
            MemoryEntry goldHit = hits.stream()
                    .filter(e -> e.content().contains("SQL 注入规范"))
                    .findFirst().orElseThrow();
            Set<String> groundTruth = Set.of(String.valueOf(goldHit.id()));

            RagEvaluator evaluator = new RagEvaluator(0.0, true);
            RagEvaluator.RagMetrics m = evaluator.evaluate(hits, groundTruth);
            assertEquals(1, m.firstHitRank(), "正解应排在第 1 位，实际 " + m.firstHitRank());
            assertEquals(1.0, m.mrr(), 0.0001, "首位命中时 MRR 应为 1.0");
            assertTrue(m.hitAtK(1) && m.hitAtK(3), "应满足 hit@1 / hit@3");

            // 负向对照：ground-truth 不含任何命中条目时 MRR = 0
            RagEvaluator.RagMetrics none = evaluator.evaluate(hits, Set.of("does-not-exist"));
            assertEquals(0.0, none.mrr(), 0.0001, "无命中时 MRR 应为 0");
            assertEquals(0, none.firstHitRank(), "无命中时 firstHitRank 应为 0");
            store.close();
        } finally {
            cleanup(team);
        }
    }
}
