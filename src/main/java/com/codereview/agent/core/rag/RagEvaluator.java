package com.codereview.agent.core.rag;

import com.codereview.agent.core.memory.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * RAG 评估与可观测组件（业界最佳实践：评估闭环 + OpenTelemetry 式追踪）。
 *
 * <p>职责：
 * <ol>
 *   <li><b>阈值过滤 / 选择性回答（abstain）</b>：候选块相似度低于 {@code minSimilarity}
 *       时剔除，避免噪声块污染 LLM 上下文；若全部低于阈值则判定「无足够相关知识」，返回空；</li>
 *   <li><b>评估指标</b>：跟踪每次检索的命中数、最高/平均相似度、阈值拦截数，并支持可选的
 *       ground-truth 召回率（precision/recall）度量（注入已知相关 chunk 时）；</li>
 *   <li><b>可观测</b>：结构化日志记录每条审查可追溯到「召回了哪些知识块 / 哪一检索阶段 / 相似度多少」，
 *       并暴露 {@link RagTrace} 供调用方关联 traceId。</li>
 * </ol>
 *
 * <p>设计为纯逻辑、无 Spring 依赖、可离线测试。
 */
public class RagEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RagEvaluator.class);

    /** 相似度阈值：低于此值的知识块不注入 prompt（默认 0.0 表示不拦截，向后兼容）。 */
    private final double minSimilarity;
    /** 是否启用 ground-truth 召回评估（需候选携带 expectedId 元数据）。 */
    private final boolean evalEnabled;

    public RagEvaluator(double minSimilarity, boolean evalEnabled) {
        this.minSimilarity = minSimilarity;
        this.evalEnabled = evalEnabled;
    }

    public RagEvaluator() {
        this(0.0, false);
    }

    /**
     * 对初检候选做阈值过滤（选择性回答的核心）。
     *
     * @param candidates 初检候选（应已携带 similarity 元数据）
     * @return 通过阈值的有效块；为空表示应 abstain（不注入）
     */
    public List<MemoryEntry> filterByThreshold(List<MemoryEntry> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<MemoryEntry> passed = new java.util.ArrayList<>();
        int rejected = 0;
        for (MemoryEntry e : candidates) {
            double sim = parseSimilarity(e);
            if (sim >= minSimilarity) {
                passed.add(e);
            } else {
                rejected++;
                log.debug("[RAG-Eval] 阈值拦截：id={}, similarity={} < {}", e.id(),
                        String.format("%.4f", sim), minSimilarity);
            }
        }
        log.info("[RAG-Eval] 候选 {} 条，阈值 {} 放行 {} 条，拦截 {} 条",
                candidates.size(), minSimilarity, passed.size(), rejected);
        return passed;
    }

    /**
     * 计算检索质量指标（无 ground-truth 时仅返回命中/相似度统计）。
     *
     * @param passed      通过阈值的块
     * @param groundTruth 已知相关 chunk 的 id 集合（可空）
     * @return 指标快照
     */
    public RagMetrics evaluate(List<MemoryEntry> passed, java.util.Set<String> groundTruth) {
        double maxSim = passed.stream().mapToDouble(this::parseSimilarity).max().orElse(0.0);
        double avgSim = passed.stream().mapToDouble(this::parseSimilarity).average().orElse(0.0);
        double precision = Double.NaN, recall = Double.NaN;
        if (evalEnabled && groundTruth != null && !groundTruth.isEmpty()) {
            long tp = passed.stream()
                    .map(e -> String.valueOf(e.id()))
                    .filter(groundTruth::contains)
                    .count();
            precision = passed.isEmpty() ? 0.0 : (double) tp / passed.size();
            recall = (double) tp / groundTruth.size();
        }
        return new RagMetrics(passed.size(), maxSim, avgSim, precision, recall);
    }

    /** 从 MemoryEntry 元数据解析相似度（由检索实现写入）。 */
    public double parseSimilarity(MemoryEntry e) {
        if (e == null || e.metadata() == null) return 0.0;
        String s = e.metadata().get("similarity");
        if (s == null) return 0.0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    /** 检索质量指标快照。 */
    public record RagMetrics(int hitCount, double maxSimilarity, double avgSimilarity,
                             double precision, double recall) {
    }

    /**
     * 单次 RAG 检索的可追溯轨迹（供调用方关联 traceId / 审计）。
     */
    public record RagTrace(String query, int candidateCount, int passedCount,
                           double maxSimilarity, String topSources) {
        public String summary() {
            return String.format(
                    "RAG[trace candidates=%d passed=%d maxSim=%.4f sources=%s]",
                    candidateCount, passedCount, maxSimilarity, topSources);
        }
    }
}
