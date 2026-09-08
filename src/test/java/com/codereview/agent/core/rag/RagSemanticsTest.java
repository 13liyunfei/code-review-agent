package com.codereview.agent.core.rag;

import com.codereview.agent.core.llm.SimpleHashEmbeddingClient;
import com.codereview.agent.core.memory.ExperienceStore;
import com.codereview.agent.core.memory.InMemoryExperienceLibrary;
import com.codereview.agent.core.memory.MemoryEntry;
import com.codereview.agent.core.memory.MemoryLevel;
import com.codereview.agent.core.memory.RagContextBuilder;
import com.codereview.agent.core.model.CodeDiff;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 语义层测试：MMR 多样性、small-to-big 父子上下文、阈值 abstain、结构化查询接线。
 *
 * <p>与 {@link RagContextBuilderTest}（基础链路）/ {@link RagContextBuilderHardeningTest}
 * （加固参数）互补：本类聚焦「注入内容的质量与结构」。
 */
class RagSemanticsTest {

    private static final String NEAR_DUP_1 = "SQL 注入 必须 使用 预处理语句 参数绑定 禁止 字符串 拼接";
    private static final String NEAR_DUP_2 = "SQL 注入 必须 使用 预处理语句 参数绑定 禁止 字符串 拼接 补充";
    private static final String NEAR_DUP_3 = "SQL 注入 必须 使用 预处理语句 参数绑定 禁止 拼接 说明";
    private static final String DIFFERENT = "密码 禁止 明文 存储 密钥 统一 放 配置中心 加密";

    private InMemoryKnowledgeStore storeWith(String... contents) {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
        long id = 1;
        for (String c : contents) {
            store.putDirect(new MemoryEntry(id++, "RAG", "teamA", c,
                    Map.of("source", "kb"), MemoryLevel.LONG_TERM, Instant.now(), null));
        }
        return store;
    }

    private RagContextBuilder builder(InMemoryKnowledgeStore store, double minSimilarity) {
        return new RagContextBuilder(store, new HeuristicReranker(),
                new RagEvaluator(minSimilarity, true),
                new ExperienceStore(new InMemoryExperienceLibrary()));
    }

    private CodeDiff sqlDiff() {
        return new CodeDiff("src/main/java/com/demo/UserDao.java",
                "@@ -1,3 +1,3 @@ public User find(String id) {\n"
                        + "-        String sql = \"select * from user where id = \" + id;\n"
                        + "+        PreparedStatement ps = conn.prepareStatement(sql);\n");
    }

    @Test
    void mmrAvoidsFillingTopNWithNearDuplicates() {
        InMemoryKnowledgeStore store = storeWith(NEAR_DUP_1, NEAR_DUP_2, NEAR_DUP_3, DIFFERENT);

        // 关闭 MMR：Top-2 会被高度相似的重复块占满，覆盖不到「密码」这个知识点
        String withoutMmr = builder(store, 0.0).withMmr(false)
                .withRetrievalWindow(20, 2).withParentContext(false)
                .buildContext("teamA", "SecurityAgent", List.of(sqlDiff()));

        // 开启 MMR：第二条会优先选「信息量新增」的块
        String withMmr = builder(store, 0.0).withMmr(true)
                .withRetrievalWindow(20, 2).withParentContext(false)
                .buildContext("teamA", "SecurityAgent", List.of(sqlDiff()));

        assertTrue(withMmr.contains("配置中心"),
                "MMR 应把不同知识点（密码/密钥）挑进 Top-2，实际：\n" + withMmr);
        // 对照组：不开启时确实拿不到（证明 MMR 是有效增益而非巧合）
        assertFalse(withoutMmr.contains("配置中心"),
                "关闭 MMR 时 Top-2 应被相似碎片占满，实际：\n" + withoutMmr);
    }

    @Test
    void parentExcerptIsInjectedAsSectionContext() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
        store.putDirect(new MemoryEntry(1L, "RAG", "teamA",
                "第二条：禁止字符串拼接 SQL。",
                Map.of("source", "kb", "parentExcerpt", "第一章 安全规范 本章适用于所有后端服务"),
                MemoryLevel.LONG_TERM, Instant.now(), null));

        String with = builder(store, 0.0).withRetrievalWindow(20, 3)
                .buildContext("teamA", "SecurityAgent", List.of(sqlDiff()));
        assertTrue(with.contains("所属章节上下文"), "应回填父章节上下文（small-to-big），实际：\n" + with);
        assertTrue(with.contains("本章适用于所有后端服务"), "父章节摘要内容应可见，实际：\n" + with);

        String without = builder(store, 0.0).withRetrievalWindow(20, 3).withParentContext(false)
                .buildContext("teamA", "SecurityAgent", List.of(sqlDiff()));
        assertFalse(without.contains("所属章节上下文"), "关闭开关后不应注入父章节上下文");
    }

    @Test
    void highThresholdAbstainsLowRelevanceKnowledge() {
        InMemoryKnowledgeStore store = storeWith(DIFFERENT);
        // 阈值 0.99：哈希嵌入下几乎不可能达到 → 知识分区应 abstain（不注入）
        String ctx = builder(store, 0.99).withRetrievalWindow(20, 3)
                .buildContext("teamA", "SecurityAgent", List.of(sqlDiff()));
        assertFalse(ctx.contains("配置中心"),
                "低于 min-similarity 的候选应被闸门拦截（abstain），实际：\n" + ctx);
    }

    @Test
    void structuredQueryReachesKnowledgeStore() {
        InMemoryKnowledgeStore store = storeWith(NEAR_DUP_1);
        RagContextBuilder b = builder(store, 0.0);
        // 用记录型 store 观察最终查询：这里退而验证「结构化查询确实来自 DiffQueryExtractor」
        String expected = DiffQueryExtractor.extract(List.of(sqlDiff()));
        assertTrue(expected.contains("UserDao"), "结构化查询应含类名，实际 " + expected);
        assertTrue(expected.contains("符号"), "结构化查询应含符号分区，实际 " + expected);
        assertFalse(expected.contains("@@"), "结构化查询不应含 hunk 头噪音，实际 " + expected);
        // 结构化查询能真正召回相关内容
        String ctx = b.withRetrievalWindow(20, 3).buildContext("teamA", "SecurityAgent", List.of(sqlDiff()));
        assertTrue(ctx.contains("参数绑定"), "结构化查询应能召回 SQL 注入规范，实际：\n" + ctx);
    }
}
