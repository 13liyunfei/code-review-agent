package com.codereview.agent.core.rag;

import com.codereview.agent.core.llm.SimpleHashEmbeddingClient;
import com.codereview.agent.core.memory.ExperienceStore;
import com.codereview.agent.core.memory.InMemoryExperienceLibrary;
import com.codereview.agent.core.memory.MemoryEntry;
import com.codereview.agent.core.memory.MemoryLevel;
import com.codereview.agent.core.memory.RagContextBuilder;
import com.codereview.agent.core.model.CodeDiff;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 加固链路测试（P0 五项中的候选扩窗 / freshness / 查询改写接线）。
 *
 * 覆盖：
 *  - retrieve-k 配置化：候选窗从默认 50 生效，且可调小（配置化而不是写死 10）；
 *  - freshness：超过 max-age-days 的陈旧知识不注入，配置关闭（0）时不过滤；
 *  - 查询改写接线：注入改写器后，检索确实使用改写后的查询（且默认恒等时链路不变）；
 *  - 全链路仍保持既有语义（阈值 / 去重 / 注入 Top-N 不变）。
 */
class RagContextBuilderHardeningTest {

    private final ExperienceStore emptyExp = new ExperienceStore(new InMemoryExperienceLibrary());

    private CodeDiff diff(String patch) {
        return new CodeDiff("Demo.java", patch);
    }

    /** 记录查询与 topK 的桩 store（可编程返回预置结果）。 */
    static class RecordingStore implements KnowledgeStore {
        final List<MemoryEntry> preset;
        String lastQuery;
        int lastTopK;
        Duration lastMaxAge;
        boolean lastIncludeGlobal;
        String lastTeam;

        RecordingStore(List<MemoryEntry> preset) {
            this.preset = preset;
        }

        @Override public int saveKnowledge(String teamId, String doc, Map<String, String> meta) { return 0; }
        @Override public void deleteByMetadata(String teamId, String key, String value) {}

        @Override
        public List<MemoryEntry> searchKnowledge(String query, int topK, String teamId, boolean includeGlobal) {
            return searchKnowledge(query, topK, teamId, includeGlobal, null);
        }

        @Override
        public List<MemoryEntry> searchKnowledge(String query, int topK, String teamId,
                                                 boolean includeGlobal, Duration maxAge) {
            this.lastQuery = query;
            this.lastTopK = topK;
            this.lastTeam = teamId;
            this.lastIncludeGlobal = includeGlobal;
            this.lastMaxAge = maxAge;
            return preset;
        }
    }

    private MemoryEntry entry(long id, String content, String source, double sim, Instant createdAt) {
        Map<String, String> m = new java.util.HashMap<>(Map.of(
                "source", source, "type", "security_rule",
                "similarity", String.format("%.4f", sim)));
        return new MemoryEntry(id, "RAG", "teamA", content, Map.copyOf(m),
                MemoryLevel.LONG_TERM, createdAt, null);
    }

    // ---------- 候选扩窗（retrieve-k 配置化）----------

    @Test
    void retrieveKDefaultsTo50AndIsConfigurable() {
        RecordingStore store = new RecordingStore(List.of());
        RagContextBuilder builder = new RagContextBuilder(store, new HeuristicReranker(),
                new RagEvaluator(0.0, false), emptyExp);
        // 默认窗 = 50（对标业界 Top-50~200 召回）
        builder.buildContext("teamA", "SecurityAgent", List.of(diff("x")));
        assertEquals(50, store.lastTopK, "候选扩窗默认应到 50");

        // 配置化：显式设定更小的窗（模拟 yml review.rag.retrieve-k 被调低）
        builder.withRetrievalWindow(10, 5);
        builder.buildContext("teamA", "SecurityAgent", List.of(diff("x")));
        assertEquals(10, store.lastTopK, "retrieve-k 应可配置（此处调回 10）");
    }

    // ---------- freshness（max-age-days）----------

    @Test
    void freshnessPassesMaxAgeToStore() {
        RecordingStore store = new RecordingStore(List.of());
        RagContextBuilder builder = new RagContextBuilder(store, new HeuristicReranker(),
                new RagEvaluator(0.0, false), emptyExp);
        // 默认 0 天 = 不过滤（null）
        builder.buildContext("teamA", "SecurityAgent", List.of(diff("x")));
        assertEquals(null, store.lastMaxAge, "默认 max-age-days=0 应不过滤（传 null）");

        // 配置 30 天后应透传 Duration
        builder.withMaxAgeDays(30);
        builder.buildContext("teamA", "SecurityAgent", List.of(diff("x")));
        assertEquals(Duration.ofDays(30), store.lastMaxAge, "freshness 应透传 maxAge 给检索层");
    }

    @Test
    void freshnessFiltersStaleEntriesEndToEnd() {
        // 端到端：注入一条「1 天前」与一条「100 天前」的知识，maxAge=30 天时应只注入新鲜条目
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
        RagContextBuilder builder = new RagContextBuilder(store, new HeuristicReranker(),
                new RagEvaluator(0.0, false), emptyExp).withMaxAgeDays(30);

        String freshContent = "SQL 注入 防护 预处理语句 参数绑定 fresh";
        String staleContent = "旧版规范 明文存储 密码 stale";
        store.saveKnowledge("teamA", freshContent, Map.of("source", "handbook-new", "type", "security_rule"));
        // 用同包 putDirect 注入一条 createdAt=100 天前的旧知识（走真实混合检索池）
        store.putDirect(entry(999, staleContent, "handbook-old", 0.99, Instant.now().minus(Duration.ofDays(100))));

        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(diff("String sql = \"select\" + id; SQL 注入")));
        assertTrue(ctx.contains("预处理语句"), "新鲜知识应被注入");
        assertFalse(ctx.contains("handbook-old"), "超过 max-age 的陈旧知识不应注入");
    }

    @Test
    void freshnessZeroDisablesFiltering() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
        // maxAgeDays=0 → 不过滤，旧知识仍可召回
        RagContextBuilder builder = new RagContextBuilder(store, new HeuristicReranker(),
                new RagEvaluator(0.0, false), emptyExp);
        store.putDirect(entry(999, "旧版规范 明文存储 密码 stale-only", "handbook-old",
                0.99, Instant.now().minus(Duration.ofDays(365))));
        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(diff("password 明文 存储")));
        assertTrue(ctx.contains("明文存储"), "max-age-days=0（默认）不应过滤旧知识");
    }

    // ---------- 查询改写接线 ----------

    @Test
    void queryRewriterIsAppliedToRetrievalQuery() {
        RecordingStore store = new RecordingStore(List.of());
        RagContextBuilder builder = new RagContextBuilder(store, new HeuristicReranker(),
                new RagEvaluator(0.0, false), emptyExp);
        AtomicInteger calls = new AtomicInteger();
        // 改写器：把任何 diff 查询统一改写为「规范术语查询」，并记录调用
        ReviewQueryRewriter spy = raw -> {
            calls.incrementAndGet();
            return "SQL 注入 参数绑定 prepared statement";
        };
        builder.withQueryRewriter(spy);
        builder.buildContext("teamA", "SecurityAgent",
                List.of(diff("String sql = \"select * from user where id=\" + id;")));
        assertEquals(1, calls.get(), "查询改写器应被调用一次");
        assertEquals("SQL 注入 参数绑定 prepared statement", store.lastQuery,
                "检索应使用改写后的查询（代码形态 → 规范术语形态）");
    }

    @Test
    void defaultIdentityRewriterKeepsRawQuery() {
        RecordingStore store = new RecordingStore(List.of());
        RagContextBuilder builder = new RagContextBuilder(store, new HeuristicReranker(),
                new RagEvaluator(0.0, false), emptyExp);
        String raw = "String sql = \"select * from user where id=\" + id;";
        builder.buildContext("teamA", "SecurityAgent", List.of(diff(raw)));
        // 恒等改写：原样保留「提炼后的结构化查询」（查询已由 DiffQueryExtractor
        // 从裸 patch 提炼为 文件/类/符号，不再是 diff 原文）
        assertEquals(DiffQueryExtractor.extract(List.of(diff(raw))), store.lastQuery,
                "默认恒等改写应原样保留提炼后的查询，不做任何改写");
        assertTrue(store.lastQuery.contains("sql"), "结构化查询应保留关键符号");
    }

    @Test
    void queryRewriteRescuesAbstainedQuery() {
        // 语义鸿沟场景：两条候选——「代码符号块」与「规范术语块」。
        // 原始 diff 查询（代码形态）会让代码符号块排第一（词重叠高），
        // 而改写为规范术语后，规范库块应排到第一（改写把查询拉回知识库语言）。
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
        // 用同包 putDirect 注入两条预置相似度（避免哈希嵌入对中文区分度低导致 sim 失真）
        store.putDirect(entry(1,
                "SQL 注入 必须 使用 预处理语句 prepared statement 参数绑定 禁止拼接",
                "sec-kb", 0.6, Instant.now()));
        store.putDirect(entry(2,
                "String sql select from user where id name 拼接 demo 变量",
                "code-block", 0.95, Instant.now()));
        // 注意：InMemory store 会按真实哈希余弦覆写 similarity 元数据，故此处阈值 0.0
        // （不做相似度拦截）——本测试聚焦「改写改变重排顺序」，由词重叠排序决定高下。
        RagEvaluator evaluator = new RagEvaluator(0.0, true);

        String rawDiff = "String sql = \"select * from user where name=\" + name;";
        // 恒等改写：代码形态查询 → 代码符号块（code-block）应排最前
        RagContextBuilder identityBuilder = new RagContextBuilder(store, new HeuristicReranker(),
                evaluator, emptyExp);
        String withoutRewrite = identityBuilder.buildContext("teamA", "SecurityAgent",
                List.of(diff(rawDiff)));

        // LLM 改写（模拟模型把 diff 归纳为规范术语）→ 规范库块（sec-kb）应排最前
        ReviewQueryRewriter llmLike = raw -> "SQL 注入 预处理语句 参数绑定";
        RagContextBuilder rewrittenBuilder = new RagContextBuilder(store, new HeuristicReranker(),
                evaluator, emptyExp).withQueryRewriter(llmLike);
        String withRewrite = rewrittenBuilder.buildContext("teamA", "SecurityAgent",
                List.of(diff(rawDiff)));

        assertTrue(withoutRewrite.indexOf("[code-block]") < withoutRewrite.indexOf("[sec-kb]"),
                "恒等改写时代码符号块应排第一（当前注入:\n" + withoutRewrite + ")");
        assertTrue(withRewrite.indexOf("[sec-kb]") < withRewrite.indexOf("[code-block]"),
                "规范术语改写后规范库块应排第一（当前注入:\n" + withRewrite + ")");
        assertTrue(withRewrite.contains("预处理语句"), "改写后应命中规范库语义");
    }

    /** 空 diff / 空知识库不因改写层而回归（abstain 语义保持）。 */
    @Test
    void rewriteLayerDoesNotBreakAbstainOnEmptyKnowledge() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
        ReviewQueryRewriter llmLike = raw -> "SQL 注入 预处理语句";
        RagContextBuilder builder = new RagContextBuilder(store, new HeuristicReranker(),
                new RagEvaluator(0.0, false), emptyExp).withQueryRewriter(llmLike);
        String ctx = builder.buildContext("teamA", "SecurityAgent", List.of(diff("some diff")));
        assertEquals("", ctx, "知识库为空时仍应 abstain（改写层不应凭空造知识）");
    }

    /** 回归：既有端到端断言（命中 / 跨团队隔离 / 空 diff 安全）不受新配置影响。 */
    @Test
    void regressionEndToEndSemanticsPreserved() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
        store.saveKnowledge("teamB", "团队B 支付风控 secretB 规则", Map.of("source", "b-kb", "type", "security_rule"));
        RagContextBuilder builder = new RagContextBuilder(store, new HeuristicReranker(),
                new RagEvaluator(0.0, false), emptyExp);
        String ctx = builder.buildContext("teamA", "SecurityAgent", List.of(diff("secretB 风控")));
        assertFalse(ctx.contains("secretB"), "跨团队隔离不因加固而回退");
        assertEquals("", builder.buildContext("teamA", "SecurityAgent", List.of()),
                "空 diff 仍安全返回");
    }
}
