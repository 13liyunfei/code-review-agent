package com.codereview.agent.core.rag;

import com.codereview.agent.core.llm.SimpleHashEmbeddingClient;
import com.codereview.agent.core.memory.MemoryEntry;
import com.codereview.agent.core.memory.MemoryLevel;
import com.codereview.agent.tenant.Teams;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖维度 ①（真实语义 Embedding 接入）、②（混合检索 BM25+向量+RRF）、⑦（RAG 与记忆分层 / 团队隔离）。
 *
 * 使用离线 SimpleHashEmbeddingClient（生产可替换为真实语义向量），验证 InMemoryKnowledgeStore：
 *  - saveKnowledge 按结构感知切分并入库，返回 chunk 数；
 *  - searchKnowledge 走混合检索，返回结果携带 similarity 元数据且归一化在 [0,1]；
 *  - 团队隔离：团队 A 的查询不返回团队 B 的内容；
 *  - 全局基线叠加：includeGlobal=true 时可检索到 __global__ 内容；
 *  - agent_type=RAG 与经验类（EXPERIENCE）分层互不干扰；
 *  - deleteByMetadata 能清理指定知识。
 */
class InMemoryKnowledgeStoreTest {

    private InMemoryKnowledgeStore newStore() {
        return new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
    }

    @Test
    void saveKnowledgeChunksAndReturnsCount() {
        InMemoryKnowledgeStore store = newStore();
        String doc = "# 安全规范\n禁止明文密码。\n# 性能规范\n避免 N+1 查询。";
        int n = store.saveKnowledge("teamA", doc, Map.of("source", "handbook"));
        assertTrue(n >= 2, "结构感知切分应产出多块");
    }

    @Test
    void searchReturnsSimilarityMetadataInUnitInterval() {
        InMemoryKnowledgeStore store = newStore();
        store.saveKnowledge("teamA",
                "禁止使用明文密码 storage password plaintext 加密", Map.of("type", "security_rule"));
        store.saveKnowledge("teamA",
                "咖啡机使用说明 与代码审查无关的内容", Map.of("type", "note"));
        List<MemoryEntry> hits = store.searchKnowledge("明文密码 plaintext password 加密", 5, "teamA", true);
        assertFalse(hits.isEmpty());
        // similarity 为真实余弦相似度，落在 [0,1]，且与 PgKnowledgeStore 口径一致
        for (MemoryEntry e : hits) {
            double s = Double.parseDouble(e.metadata().get("similarity"));
            assertTrue(s >= 0.0 && s <= 1.0001, "similarity 应在 [0,1]：" + s);
        }
        // 最相关块（语义最接近）应排第一，且相似度最高
        double top = Double.parseDouble(hits.get(0).metadata().get("similarity"));
        for (int i = 1; i < hits.size(); i++) {
            double s = Double.parseDouble(hits.get(i).metadata().get("similarity"));
            assertTrue(s <= top + 1e-9, "结果应按相似度降序");
        }
    }

    @Test
    void teamIsolationPreventsCrossTeamLeak() {
        InMemoryKnowledgeStore store = newStore();
        store.saveKnowledge("teamA", "团队A专属 支付风控规则 secretA", Map.of("type", "security_rule"));
        store.saveKnowledge("teamB", "团队B专属 推荐算法逻辑 secretB", Map.of("type", "security_rule"));
        List<MemoryEntry> hitsA = store.searchKnowledge("支付风控 secretA", 5, "teamA", false);
        assertTrue(hitsA.stream().allMatch(e -> "teamA".equals(e.teamId())));
        assertTrue(hitsA.stream().anyMatch(e -> e.content().contains("secretA")));
        assertTrue(hitsA.stream().noneMatch(e -> e.content().contains("secretB")));
    }

    @Test
    void globalBaselineIncludedWhenRequested() {
        InMemoryKnowledgeStore store = newStore();
        store.saveKnowledge(Teams.GLOBAL, "全局编码规范 命名使用驼峰 camelCase", Map.of("type", "coding_standard"));
        store.saveKnowledge("teamA", "团队A 业务规则 订单超时关闭", Map.of("type", "best_practice"));
        // includeGlobal=true：应能检索到 __global__ 的规范
        List<MemoryEntry> hits = store.searchKnowledge("驼峰命名 camelCase", 5, "teamA", true);
        assertTrue(hits.stream().anyMatch(e -> Teams.GLOBAL.equals(e.teamId())));
        // includeGlobal=false：不应出现全局基线
        List<MemoryEntry> teamOnly = store.searchKnowledge("驼峰命名 camelCase", 5, "teamA", false);
        assertTrue(teamOnly.stream().noneMatch(e -> Teams.GLOBAL.equals(e.teamId())));
    }

    @Test
    void ragKnowledgeSeparatedFromExperienceLayer() {
        // 重构后：KnowledgeStore 与 MemoryStore 是两个独立接口、独立实例，
        // 物理上各自持有自己的存储，RAG 检索只会命中知识库、经验检索只会命中记忆库，
        // 二者互不串扰（原「同一 store 内混入 EXPERIENCE」的能力已被架构移除）。
        InMemoryKnowledgeStore ragStore = newStore();
        com.codereview.agent.core.memory.InMemoryVectorStore memStore =
                new com.codereview.agent.core.memory.InMemoryVectorStore(new SimpleHashEmbeddingClient());

        // 同一团队、相似内容的两条数据分别写入两层
        ragStore.saveKnowledge("teamA", "知识库条目 关于异常处理规范", Map.of("type", "best_practice"));
        memStore.save(new MemoryEntry(null, "EXPERIENCE", "teamA",
                "经验条目 关于异常处理规范", Map.of(), MemoryLevel.LONG_TERM, Instant.now(), null));

        // RAG 检索只命中知识库
        List<MemoryEntry> ragHits = ragStore.searchKnowledge("异常处理规范", 5, "teamA", true);
        assertTrue(ragHits.stream().allMatch(e -> "RAG".equals(e.agentType())));
        assertTrue(ragHits.stream().noneMatch(e -> e.content().contains("经验条目")));

        // 记忆检索只命中记忆库（且不会返回知识库写入的内容）
        List<MemoryEntry> memHits = memStore.search("异常处理规范", "EXPERIENCE", 5, "teamA", true);
        assertTrue(memHits.stream().allMatch(e -> "EXPERIENCE".equals(e.agentType())));
        assertTrue(memHits.stream().noneMatch(e -> e.content().contains("知识库条目")));
    }

    @Test
    void deleteByMetadataClearsKnowledge() {
        InMemoryKnowledgeStore store = newStore();
        store.saveKnowledge("teamA", "待删除知识 临时规则", Map.of("kbId", "kb-temp", "type", "note"));
        store.saveKnowledge("teamA", "保留知识 长期规则", Map.of("kbId", "kb-keep", "type", "note"));
        store.deleteByMetadata("teamA", "kbId", "kb-temp");
        List<MemoryEntry> hits = store.searchKnowledge("临时规则", 5, "teamA", false);
        assertTrue(hits.stream().noneMatch(e -> e.content().contains("待删除知识")));
        assertTrue(hits.stream().anyMatch(e -> e.content().contains("保留知识")));
    }

    @Test
    void emptyPoolReturnsEmpty() {
        InMemoryKnowledgeStore store = newStore();
        assertTrue(store.searchKnowledge("anything", 5, "teamA", true).isEmpty());
    }
}
