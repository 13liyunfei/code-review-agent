package com.codereview.agent.core.rag;

import com.codereview.agent.core.memory.MemoryEntry;

import java.util.List;

/**
 * 重排器（Reranker）抽象——业界 RAG 标准「质量闸门」。
 *
 * <p>初检（向量 / 混合）通常召回 10~50 条候选，但 LLM 上下文窗口有限且对噪声敏感，
 * cross-encoder 重排能比简单点积排序把精度再提升 20~35%（代价约 200~500ms）。
 * 本接口统一两种实现：
 * <ul>
 *   <li>{@link HeuristicReranker}：离线可用，基于查询词-块词重叠 + 元数据加权的轻量打分；</li>
 *   <li>{@link ApiReranker}：对接 Cohere / Jina 等托管 cross-encoder，精度最佳，失败自动降级 heuristic。</li>
 * </ul>
 */
public interface Reranker {

    /**
     * 对候选条目按与查询的相关性重新排序。
     *
     * @param query    查询文本
     * @param candidates 初检候选（按原相似度降序）
     * @param topN     返回条数（≤ candidates.size()）
     * @return 重排后的条目（相关性降序）
     */
    List<MemoryEntry> rerank(String query, List<MemoryEntry> candidates, int topN);
}
