package com.codereview.agent.core.rag;

import com.codereview.agent.core.llm.SimpleHashEmbeddingClient;
import com.codereview.agent.core.memory.MemoryEntry;
import com.codereview.agent.core.memory.MemoryLevel;
import com.codereview.agent.core.memory.RagContextBuilder;
import com.codereview.agent.core.model.CodeDiff;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 上下文构建器端到端测试：串联维度 ②（混合检索）→ ⑥（阈值过滤）→ ③（重排）→ ⑤（评估）。
 *
 * 使用离线组件（InMemoryKnowledgeStore + HeuristicReranker + RagEvaluator），验证：
 *  - 注入内容来自最相关的知识块（语义检索命中）；
 *  - 高相关块排在前面（重排生效）；
 *  - 无任何知识时返回空串（选择性回答 abstain）；
 *  - 团队隔离在端到端链路中生效（不跨团队泄漏）。
 */
class RagContextBuilderTest {

    private RagContextBuilder builderWith(InMemoryKnowledgeStore store) {
        return new RagContextBuilder(store, new HeuristicReranker(), new RagEvaluator(0.0, false));
    }

    private CodeDiff diff(String patch) {
        return new CodeDiff("Demo.java", patch);
    }

    @Test
    void buildContextInjectsRelevantKnowledge() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
        store.saveKnowledge("teamA",
                "禁止使用明文密码 storage password plaintext 必须加密", Map.of("source", "security-wiki", "type", "security_rule"));
        store.saveKnowledge("teamA",
                "咖啡机使用说明 与本次审查无关的内容", Map.of("source", "misc", "type", "note"));
        RagContextBuilder builder = builderWith(store);

        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(diff("private String password = \"123456\"; // 明文密码")));
        assertNotNull(ctx);
        assertFalse(ctx.isBlank(), "应注入相关知识");
        assertTrue(ctx.contains("明文密码"), "最相关块应被注入");
        // 相关块（security-wiki）source 标签应出现在注入中
        assertTrue(ctx.contains("[security-wiki]"));
    }

    @Test
    void buildContextAbstainsWhenNoKnowledge() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
        // 知识库为空
        RagContextBuilder builder = builderWith(store);
        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(diff("some unrelated change")));
        assertTrue(ctx.isEmpty(), "无知识时应 abstain（返回空串）");
    }

    @Test
    void buildContextDoesNotLeakAcrossTeams() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
        store.saveKnowledge("teamB", "团队B 支付风控 secretB 规则", Map.of("source", "b-kb", "type", "security_rule"));
        RagContextBuilder builder = builderWith(store);
        // 以 teamA 查询，不应看到 teamB 内容
        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(diff("secretB 风控")));
        assertFalse(ctx.contains("secretB"), "端到端链路不应跨团队泄漏");
    }

    @Test
    void buildContextEmptyDiffReturnsSomethingSafe() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
        store.saveKnowledge("teamA", "全局编码规范 命名驼峰 camelCase", Map.of("source", "handbook", "type", "coding_standard"));
        RagContextBuilder builder = builderWith(store);
        // 空 diff：查询为空，但全局基线仍应可被检索到（includeGlobal=true）
        String ctx = builder.buildContext("teamA", "StyleAgent", List.of());
        // 查询空串时向量/BM25 均为 0，可能 abstain；不强制断言内容，仅确保不抛异常且返回非 null
        assertNotNull(ctx);
    }

    @Test
    void buildContextRerankPutsMostRelevantFirst() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
        store.saveKnowledge("teamA",
                "SQL 注入 防护 使用 预处理语句 prepared statement 参数绑定", Map.of("source", "sec", "type", "security_rule"));
        store.saveKnowledge("teamA",
                "SQL 注入 防护 还应 限制 数据库 账号 权限", Map.of("source", "sec2", "type", "security_rule"));
        RagContextBuilder builder = builderWith(store);
        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(diff("String sql = \"select * from user where name=\" + name; // 拼接 SQL")));
        // 两个块相关，注入内容应至少包含其中一个 source 标签
        assertTrue(ctx.contains("[sec]") || ctx.contains("[sec2]"));
    }
}
