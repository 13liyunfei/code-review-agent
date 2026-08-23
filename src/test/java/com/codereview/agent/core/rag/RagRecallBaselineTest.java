package com.codereview.agent.core.rag;

import com.codereview.agent.core.memory.MemoryEntry;
import com.codereview.agent.core.memory.MemoryLevel;
import com.codereview.agent.core.memory.RagContextBuilder;
import com.codereview.agent.core.model.CodeDiff;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 召回率回归基线（待办建议⑤）。
 *
 * <p>目标：确保「阈值过滤(min-similarity=0.3) + 注入前去重」两项改动不降低召回质量。
 * 中文语义相似度依赖真实子词向量嵌入（生产用 TokenHub），离线哈希嵌入对中文区分度过低，
 * 无法稳定复现。因此本测试用 {@link StubKnowledgeStore} 预设 similarity（模拟真实向量检索结果），
 * 与嵌入实现解耦，确定性验证三层逻辑：
 *   - 阈值过滤：sim >= 0.3 放行，sim < 0.3 拦截（abstain 抑制噪声）；
 *   - 去重：重叠切块（content 归一化相同）只注入 1 次，不丢语义；
 *   - 召回：5 个典型查询的 ground-truth 相关块（sim=0.85）全部被放行并注入（recall=1.0）。
 *
 * <p>本测试即「召回率回归基线」：后续若调整 min-similarity / 切块策略 / 重排，跑此测试即可发现召回回退。
 */
class RagRecallBaselineTest {

    /** 真实链路配置：min-similarity=0.3，evalEnabled=true（application.yml 已落地）。 */
    private static final double MIN_SIM = 0.3;

    /** 预设相似度的 stub 知识库：返回预置块（模拟真实向量检索结果）。 */
    static class StubKnowledgeStore implements KnowledgeStore {
        private final List<MemoryEntry> entries;
        StubKnowledgeStore(List<MemoryEntry> entries) { this.entries = entries; }
        @Override public int saveKnowledge(String teamId, String doc, Map<String, String> meta) { return 0; }
        @Override public void deleteByMetadata(String teamId, String key, String value) {}
        @Override
        public List<MemoryEntry> searchKnowledge(String query, int topK, String teamId, boolean includeGlobal) {
            return entries; // 直接返回预置块（已带 similarity 元数据）
        }
    }

    private MemoryEntry entry(long id, String content, String source, double sim) {
        Map<String, String> m = Map.of("source", source, "type", "security_rule", "similarity", String.format("%.4f", sim));
        return new MemoryEntry(id, "RAG", "teamA", content, m, MemoryLevel.LONG_TERM, Instant.now(), null);
    }

    private RagContextBuilder builderWith(StubKnowledgeStore store) {
        return new RagContextBuilder(store, new HeuristicReranker(), new RagEvaluator(MIN_SIM, true));
    }

    @Test
    void recallBaselineAllFiveQueriesHitGroundTruth() {
        // 5 个查询各自的相关块（sim=0.85，高于阈值）+ 一个噪声块（sim=0.15，应被拦截）
        List<MemoryEntry> entries = new ArrayList<>();
        entries.add(entry(1, "SQL 注入 防护 禁止 字符串 拼接 使用 预处理 语句 参数绑定", "sec-sql", 0.85));
        entries.add(entry(2, "硬编码 密码 密钥 secret 明文 凭证 必须 加密 存储", "sec-secret", 0.85));
        entries.add(entry(3, "禁止 使用 System.out.println 打印 日志 应 使用 日志 框架", "style-log", 0.85));
        entries.add(entry(4, "异常 处理 禁止 空 catch 块 e.printStackTrace 应 记录 日志", "style-exc", 0.85));
        entries.add(entry(5, "TODO FIXME 标记 应 关联 任务 跟踪 系统 禁止 遗留", "proc-todo", 0.85));
        entries.add(entry(6, "咖啡机 茶水间 无关 内容 说明 安排 休息", "misc", 0.15));

        RagContextBuilder builder = builderWith(new StubKnowledgeStore(entries));

        String[] queries = {
                "String sql = \"select * from user where id=\" + id; SQL 拼接",
                "private String password = \"123456\"; 明文密码",
                "System.out.println(\"debug\"); 打印日志",
                "catch (Exception e) { e.printStackTrace(); } 空 catch",
                "// TODO 临时实现 待优化"
        };
        String[] gtSources = {"sec-sql", "sec-secret", "style-log", "style-exc", "proc-todo"};

        for (int i = 0; i < queries.length; i++) {
            String ctx = builder.buildContext("teamA", "MULTI-AGENT",
                    List.of(new CodeDiff("Demo.java", queries[i])));
            assertFalse(ctx.isBlank(), "查询[" + i + "] 应召回相关知识，不应 abstain");
            assertTrue(ctx.contains("[" + gtSources[i] + "]"),
                    "查询[" + i + "] 应召回 ground-truth 源 " + gtSources[i] + "，实际注入:\n" + ctx);
            // 噪声块不应出现在任何注入中
            assertFalse(ctx.contains("[misc]"), "查询[" + i + "] 噪声块（sim=0.15）应被阈值拦截");
        }
    }

    @Test
    void dedupRemovesIdenticalOverlapChunks() {
        // 真实场景：handbook 重叠切块产出 content 完全相同的重复块（PR#26 实测 5→2）。
        // 三个块 content 完全一致，去重后应只注入 1 次，且不丢语义。
        List<MemoryEntry> entries = new ArrayList<>();
        String dup = "SQL 注入 防护 禁止 字符串 拼接 使用 预处理 语句 参数绑定";
        entries.add(entry(1, dup, "sec-sql", 0.85));
        entries.add(entry(2, dup, "sec-sql", 0.85));
        entries.add(entry(3, dup, "sec-sql", 0.85));

        RagContextBuilder builder = builderWith(new StubKnowledgeStore(entries));
        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(new CodeDiff("Demo.java", "String sql = \"select * from user where name=\" + name;")));
        assertTrue(ctx.contains("SQL 注入 防护"), "去重后 ground-truth 语义不应丢失");
        int occurrences = ctx.split("\\[sec-sql\\]").length - 1;
        assertEquals(1, occurrences, "content 完全相同的重叠块去重后应只注入 1 次，实际 " + occurrences + " 次");
    }

    @Test
    void dedupKeepsDistinctChunks() {
        // 反向边界：content 不同的块（部分重叠但非完全相同）应分别保留，不去重。
        List<MemoryEntry> entries = new ArrayList<>();
        entries.add(entry(1, "SQL 注入 防护 禁止 字符串 拼接 使用 预处理 语句", "sec-sql", 0.85));
        entries.add(entry(2, "SQL 注入 防护 还应 限制 数据库 账号 权限 最小 原则", "sec-sql", 0.80));

        RagContextBuilder builder = builderWith(new StubKnowledgeStore(entries));
        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(new CodeDiff("Demo.java", "String sql = \"select\";")));
        // 两个不同块都应注入（不互相去重）
        assertEquals(2, ctx.split("\\[sec-sql\\]").length - 1, "不同 content 的块应各自保留，不去重");
    }

    @Test
    void noiseBelowThresholdIsRejected() {
        List<MemoryEntry> entries = new ArrayList<>();
        entries.add(entry(1, "SQL 注入 防护 禁止 拼接 预处理 语句", "sec-sql", 0.85));
        entries.add(entry(2, "咖啡机 茶水间 无关 内容 说明 安排 休息", "misc", 0.15));

        RagContextBuilder builder = builderWith(new StubKnowledgeStore(entries));
        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(new CodeDiff("Demo.java", "String sql = \"select * from user where id=\" + id;")));
        assertTrue(ctx.contains("[sec-sql]"), "相关块（sim=0.85）应被注入");
        assertFalse(ctx.contains("[misc]"), "低于阈值的噪声块（sim=0.15）不应被注入（abstain 抑制）");
    }

    @Test
    void allCandidatesBelowThresholdAbstains() {
        // 全部低于阈值 → 应返回空串（选择性回答）
        List<MemoryEntry> entries = new ArrayList<>();
        entries.add(entry(1, "咖啡机 茶水间 无关", "misc", 0.15));
        entries.add(entry(2, "天气 预报 无关", "misc2", 0.20));
        RagContextBuilder builder = builderWith(new StubKnowledgeStore(entries));
        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(new CodeDiff("Demo.java", "String sql = \"select\";")));
        assertTrue(ctx.isEmpty(), "全部候选低于阈值时应 abstain（返回空串）");
    }
}
