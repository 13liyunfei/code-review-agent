package com.codereview.agent.core.rag;

import com.codereview.agent.core.memory.MemoryEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 离线启发式重排器（无外部依赖，作为默认 / API 失败时的兜底）。
 *
 * <p>打分模型（透明、可解释，便于测试与审计），各项均为 [0,1] 区间后加权，
 * 最终 score ∈ [0,1]：
 * <pre>
 *   score = 0.7 * tokenOverlapRatio        // 查询词与块文本的词元重叠比例（Jaccard 风格）
 *         + 0.2 * metadataBoost             // 元数据加权（命中高优先级 type 取 1，否则 0）
 *         + 0.1 * lengthPenalty             // 长度适配因子（过短/过长轻微降权，∈ (0,1]）
 * </pre>
 * 虽不及真实 cross-encoder，但在向量初检的召回结果上做二次精排，
 * 已能稳定把更相关的块提到前面。生产环境应配置 {@link ApiReranker} 获得最佳精度。
 */
public class HeuristicReranker implements Reranker {

    /** 元数据加权：命中这些 type 的块额外加分。 */
    private static final Set<String> PRIORITY_TYPES =
            Set.of("coding_standard", "security_rule", "best_practice");

    @Override
    public List<MemoryEntry> rerank(String query, List<MemoryEntry> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Set<String> qTokens = tokenize(query);
        List<Scored> scored = new ArrayList<>();
        for (MemoryEntry e : candidates) {
            scored.add(new Scored(e, score(e, qTokens)));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        int n = Math.min(topN, scored.size());
        return scored.subList(0, n).stream().map(s -> s.entry).collect(Collectors.toList());
    }

    private double score(MemoryEntry e, Set<String> qTokens) {
        if (qTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> cTokens = tokenize(e.content());
        if (cTokens.isEmpty()) {
            return 0.0;
        }
        // 词重叠比例（Jaccard 风格，∈ [0,1]）
        Set<String> inter = new HashSet<>(qTokens);
        inter.retainAll(cTokens);
        double overlap = (double) inter.size() / Math.max(qTokens.size(), cTokens.size());

        // 元数据加权（∈ {0,1}）
        Map<String, String> meta = e.metadata() == null ? Map.of() : e.metadata();
        double boost = PRIORITY_TYPES.contains(meta.getOrDefault("type", "")) ? 1.0 : 0.0;

        // 长度适配因子：过短（<50）或过长（>2000）轻微降权，∈ (0,1]
        int len = e.content().length();
        double lengthPenalty = (len < 50 || len > 2000) ? 0.5 : 1.0;

        return 0.7 * overlap + 0.2 * boost + 0.1 * lengthPenalty;
    }

    /**
     * 简易分词：非字母数字（含中文）粗切 → <b>中文 bigram</b> + camelCase / snake_case
     * 子词拆分（再统一小写），使代码审查场景下的 {@code getUserById} 与 {@code user_service} /
     * {@code getuserbyid} 能共享子词（get/user/by/id），提升标识符召回；
     * 中文则不再整段成词（否则 Jaccard 重叠对中文恒为 0，启发式重排全程空转）。
     */
    public static Set<String> tokenize(String text) {
        // 统一委托 TextTokenizer（中文 bigram + 标识符子词），与 PG 全文检索 / 内存 BM25 同口径。
        return TextTokenizer.tokenize(text);
    }

    private record Scored(MemoryEntry entry, double score) {
    }
}
