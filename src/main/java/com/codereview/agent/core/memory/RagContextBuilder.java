package com.codereview.agent.core.memory;

import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.rag.HeuristicReranker;
import com.codereview.agent.core.rag.KnowledgeStore;
import com.codereview.agent.core.rag.RagEvaluator;
import com.codereview.agent.core.rag.Reranker;
import com.codereview.agent.tenant.Teams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 上下文构建器（见文档“RAG 的使用位置”）。
 *
 * <p>审查前检索规范文档 / 历史 PR / 安全 Wiki，将相关内容作为【相关历史知识】注入提示词。
 * 内部链路对标业界最佳实践：
 * <pre>
 *   提取查询 → 混合检索(向量+BM25,RRF融合) → 阈值过滤(abstain) → Cross-Encoder重排 → Top-5格式化
 * </pre>
 * 各阶段能力由协作组件提供，均可离线运行（无 API 时自动降级到启发式）：
 * <ul>
 *   <li>混合检索：{@link KnowledgeStore#searchKnowledge}（PG 实现含 tsvector BM25 + 向量 RRF）；</li>
 *   <li>阈值过滤 / 选择性回答：{@link RagEvaluator#filterByThreshold}；</li>
 *   <li>重排：{@link Reranker}（{@link HeuristicReranker} 默认，可配 {@code ApiReranker}）；</li>
 *   <li>评估 / 可观测：{@link RagEvaluator}（记录命中、相似度、trace）。</li>
 * </ul>
 * 检索时始终纳入团队自身内容 + 全局基线（编码规范手册），实现“全局基线 + 团队叠加”。
 */
@Component
public class RagContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(RagContextBuilder.class);

    private final KnowledgeStore knowledgeStore;
    private final Reranker reranker;
    private final RagEvaluator evaluator;

    /** 初检召回数（重排前）。 */
    private static final int CANDIDATE_K = 10;
    /** 最终注入 Top-N。 */
    private static final int INJECT_TOP_N = 5;

    @org.springframework.beans.factory.annotation.Autowired
    public RagContextBuilder(KnowledgeStore knowledgeStore,
                             Reranker reranker,
                             RagEvaluator evaluator) {
        this.knowledgeStore = knowledgeStore;
        this.reranker = reranker;
        this.evaluator = evaluator;
    }

    /**
     * 为指定团队 / Agent 构建 RAG 增强上下文。
     *
     * @param teamId    团队标识（含全局基线叠加）
     * @param agentType 审查 Agent 类型（仅用于日志）
     * @param diffs     代码变更
     * @return 检索到的相关知识文本（无则返回空串，即选择性回答 abstain）
     */
    public String buildContext(String teamId, String agentType, List<CodeDiff> diffs) {
        long t0 = System.currentTimeMillis();
        // 1. 从代码提取查询意图
        String query = extractQueryFromDiffs(diffs);
        // 2. 混合检索（向量 + BM25 + RRF），含全局基线
        List<MemoryEntry> candidates = knowledgeStore.searchKnowledge(query, CANDIDATE_K,
                Teams.sanitize(teamId), true);
        // 3. 阈值过滤（低于 minSimilarity 的块剔除；全低于则 abstain）
        List<MemoryEntry> passed = evaluator.filterByThreshold(candidates);
        if (passed.isEmpty()) {
            log.info("[RAG] 无相关知识（候选 {} 条均低于阈值或为空），选择性跳过注入, 耗时 {}ms",
                    candidates.size(), System.currentTimeMillis() - t0);
            return "";
        }
        // 4. Cross-Encoder 重排 → Top-5
        List<MemoryEntry> reranked = reranker.rerank(query, passed, INJECT_TOP_N);
        // 5. 注入前去重：按 content hash 去除 handbook 重叠切块产生的重复块（避免同一段注入多次）
        List<MemoryEntry> deduped = dedupeByContent(reranked);
        if (deduped.size() < reranked.size()) {
            log.info("[RAG] 去重：注入前剔除 {} 个重复块（重叠切块导致），{} → {}",
                    reranked.size() - deduped.size(), reranked.size(), deduped.size());
        }
        // 6. 评估指标 + 格式化
        RagEvaluator.RagMetrics metrics = evaluator.evaluate(deduped, null);
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry e : deduped) {
            sb.append("- [").append(e.metadata().getOrDefault("source", "knowledge"))
                    .append("] ").append(e.content()).append('\n');
        }
        log.info("[RAG] 上下文构建：team={}, agent={}, 查询={}字符, 候选 {} → 放行 {} → 注入 Top-{} (maxSim={}), 耗时 {}ms",
                Teams.sanitize(teamId), agentType, query.length(), candidates.size(),
                passed.size(), deduped.size(),
                String.format("%.4f", metrics.maxSimilarity()),
                System.currentTimeMillis() - t0);
        return sb.toString().trim();
    }

    /**
     * 按内容去重：handbook 等被重叠切块后，同一语义段会产出多个近似块，
     * 重排后 Top-N 可能被同源重复块占满，导致注入内容高度雷同。此处按 content 归一化后去重。
     *
     * @param entries 重排后的候选块
     * @return 去重后的块（保持原顺序）
     */
    private List<MemoryEntry> dedupeByContent(List<MemoryEntry> entries) {
        List<MemoryEntry> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MemoryEntry e : entries) {
            if (e == null || e.content() == null) {
                continue;
            }
            String key = e.content().trim().replaceAll("\\s+", "");
            if (seen.add(key)) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * 从代码变更中提取检索查询（取补丁前若干字符）。
     */
    private String extractQueryFromDiffs(List<CodeDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return "";
        }
        String joined = diffs.stream()
                .map(CodeDiff::patch)
                .reduce("", String::concat);
        return joined.length() > 500 ? joined.substring(0, 500) : joined;
    }
}
