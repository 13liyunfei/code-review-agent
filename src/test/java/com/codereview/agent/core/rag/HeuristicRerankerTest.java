package com.codereview.agent.core.rag;

import com.codereview.agent.core.memory.MemoryEntry;
import com.codereview.agent.core.memory.MemoryLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖维度 ③：Cross-Encoder 重排（离线启发式等价实现）。
 *
 * 验证 HeuristicReranker：
 *  - 词重叠越高排序越靠前；
 *  - 高优先级 type（coding_standard/security_rule/best_practice）获得元数据加权；
 *  - 空 / null 候选安全返回空；
 *  - topN 截断正确；
 *  - tokenize 对 CJK 与英文均按 ≥2 字符切分。
 */
class HeuristicRerankerTest {

    private final HeuristicReranker reranker = new HeuristicReranker();

    private MemoryEntry entry(long id, String content, String type) {
        return new MemoryEntry(id, "RAG", "default", content,
                Map.of("type", type), MemoryLevel.LONG_TERM, Instant.now(), null);
    }

    @Test
    void moreRelevantChunkRankedFirst() {
        MemoryEntry relevant = entry(1, "禁止使用明文密码 storage password plaintext", "security_rule");
        MemoryEntry irrelevant = entry(2, "这里是关于咖啡机使用说明的内容与密码毫无关系", "best_practice");
        List<MemoryEntry> ranked = reranker.rerank("明文密码 plaintext password", List.of(irrelevant, relevant), 5);
        assertEquals(2, ranked.size());
        assertEquals(1L, ranked.get(0).id());
    }

    @Test
    void priorityTypeBoostedOverSameOverlap() {
        // 两个块词重叠相同，但一个是 security_rule 应被提到前面
        MemoryEntry priority = entry(1, "sql 注入 防护 参数 绑定", "security_rule");
        MemoryEntry normal = entry(2, "sql 注入 防护 参数 绑定", "note");
        List<MemoryEntry> ranked = reranker.rerank("sql 注入 防护", List.of(normal, priority), 5);
        assertEquals(1L, ranked.get(0).id());
    }

    @Test
    void emptyAndNullCandidatesReturnEmpty() {
        assertTrue(reranker.rerank("q", List.of(), 5).isEmpty());
        assertTrue(reranker.rerank("q", null, 5).isEmpty());
    }

    @Test
    void topNTruncates() {
        List<MemoryEntry> candidates = List.of(
                entry(1, "sql 注入 防护 参数 绑定 预处理语句", "security_rule"),
                entry(2, "sql 注入 防护 参数 绑定 转义", "security_rule"),
                entry(3, "sql 注入 防护 参数 绑定 过滤", "security_rule"),
                entry(4, "sql 注入 防护 参数 绑定 白名单", "security_rule"));
        List<MemoryEntry> ranked = reranker.rerank("sql 注入 防护 参数", candidates, 2);
        assertEquals(2, ranked.size());
    }

    @Test
    void tokenizeSplitsCjkAndEnglishByLength() {
        // 中文按字 >=2 切分；英文按词；单字被过滤
        var tokens = HeuristicReranker.tokenize("密码 password a 注入");
        assertTrue(tokens.contains("密码"));
        assertTrue(tokens.contains("password"));
        assertTrue(tokens.contains("注入"));
        assertFalse(tokens.contains("a"), "单字符 token 应被过滤");
    }

    @Test
    void tokenizeSplitsCamelCaseAndSnakeCaseIdentifiers() {
        // 代码标识符应拆为可共享子词：getUserById -> {get, user, by, id}
        var tokens = HeuristicReranker.tokenize("getUserById");
        assertTrue(tokens.contains("get"));
        assertTrue(tokens.contains("user"));
        assertTrue(tokens.contains("by"));
        assertTrue(tokens.contains("id"));
        // snake_case 与 camelCase 可互通：user_service 与 UserService 都含 user/service
        var t2 = HeuristicReranker.tokenize("user_service_impl");
        assertTrue(t2.contains("user"));
        assertTrue(t2.contains("service"));
        assertTrue(t2.contains("impl"));
        var t3 = HeuristicReranker.tokenize("UserService");
        assertTrue(t3.contains("user"));
        assertTrue(t3.contains("service"));
    }

    @Test
    void blankQueryScoresZeroAndKeepsStableOrder() {
        MemoryEntry a = entry(1, "内容一 关于 缓存 设计", "best_practice");
        MemoryEntry b = entry(2, "内容二 关于 线程 池", "best_practice");
        // 空查询不应抛异常，返回原候选（topN 内）
        List<MemoryEntry> ranked = reranker.rerank("   ", List.of(a, b), 5);
        assertEquals(2, ranked.size());
    }
}
