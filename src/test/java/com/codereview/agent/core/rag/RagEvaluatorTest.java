package com.codereview.agent.core.rag;

import com.codereview.agent.core.memory.MemoryEntry;
import com.codereview.agent.core.memory.MemoryLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖维度 ⑥（相似度阈值 + 选择性回答 abstain）与 ⑤（RAG 评估与可观测）。
 *
 * 验证 RagEvaluator：
 *  - filterByThreshold 剔除低于阈值的块，全部低于阈值时返回空（abstain）；
 *  - parseSimilarity 容错（缺失 / 非法 / null 返回 0）；
 *  - evaluate 在无 ground-truth 时统计 hit/max/avg，启用后计算 precision/recall；
 *  - RagMetrics / RagTrace 记录结构正确，便于可观测与审计。
 */
class RagEvaluatorTest {

    private MemoryEntry withSim(long id, double sim) {
        return new MemoryEntry(id, "RAG", "default", "content-" + id,
                Map.of("similarity", String.format("%.4f", sim)), MemoryLevel.LONG_TERM, Instant.now(), null);
    }

    @Test
    void filterKeepsAboveThreshold() {
        RagEvaluator eval = new RagEvaluator(0.3, false);
        List<MemoryEntry> candidates = List.of(withSim(1, 0.9), withSim(2, 0.1), withSim(3, 0.5));
        List<MemoryEntry> passed = eval.filterByThreshold(candidates);
        assertEquals(2, passed.size());
        assertTrue(passed.stream().anyMatch(e -> e.id() == 1L));
        assertTrue(passed.stream().anyMatch(e -> e.id() == 3L));
        assertTrue(passed.stream().noneMatch(e -> e.id() == 2L));
    }

    @Test
    void allBelowThresholdAbstains() {
        RagEvaluator eval = new RagEvaluator(0.8, false);
        List<MemoryEntry> candidates = List.of(withSim(1, 0.2), withSim(2, 0.3));
        List<MemoryEntry> passed = eval.filterByThreshold(candidates);
        assertTrue(passed.isEmpty(), "全部低于阈值应 abstain（返回空）");
    }

    @Test
    void nullOrEmptyCandidatesAbstain() {
        RagEvaluator eval = new RagEvaluator(0.0, false);
        assertTrue(eval.filterByThreshold(null).isEmpty());
        assertTrue(eval.filterByThreshold(List.of()).isEmpty());
    }

    @Test
    void parseSimilarityFaultTolerant() {
        RagEvaluator eval = new RagEvaluator(0.0, false);
        assertEquals(0.0, eval.parseSimilarity(null));
        assertEquals(0.0, eval.parseSimilarity(withSim(1, 0.5).metadata() == null ? null : withSimMissingMeta()));
        assertEquals(0.0, eval.parseSimilarity(withSimBadMeta()));
    }

    private MemoryEntry withSimMissingMeta() {
        return new MemoryEntry(1L, "RAG", "default", "c", Map.of(), MemoryLevel.LONG_TERM, Instant.now(), null);
    }

    private MemoryEntry withSimBadMeta() {
        return new MemoryEntry(1L, "RAG", "default", "c", Map.of("similarity", "not-a-number"),
                MemoryLevel.LONG_TERM, Instant.now(), null);
    }

    @Test
    void evaluateComputesHitMaxAvgWithoutGroundTruth() {
        RagEvaluator eval = new RagEvaluator(0.0, false);
        List<MemoryEntry> passed = List.of(withSim(1, 0.9), withSim(2, 0.4));
        RagEvaluator.RagMetrics m = eval.evaluate(passed, null);
        assertEquals(2, m.hitCount());
        assertEquals(0.9, m.maxSimilarity(), 1e-6);
        assertEquals(0.65, m.avgSimilarity(), 1e-6);
        assertTrue(Double.isNaN(m.precision()));
        assertTrue(Double.isNaN(m.recall()));
    }

    @Test
    void evaluateComputesPrecisionRecallWithGroundTruth() {
        RagEvaluator eval = new RagEvaluator(0.0, true);
        // 候选命中 id=1,2；ground-truth={1,3} → tp=1, precision=1/2, recall=1/2
        List<MemoryEntry> passed = List.of(withSim(1, 0.9), withSim(2, 0.4));
        Set<String> gt = Set.of("1", "3");
        RagEvaluator.RagMetrics m = eval.evaluate(passed, gt);
        assertEquals(0.5, m.precision(), 1e-6);
        assertEquals(0.5, m.recall(), 1e-6);
    }

    @Test
    void ragTraceSummaryFormats() {
        RagEvaluator.RagTrace trace = new RagEvaluator.RagTrace("query", 10, 5, 0.92, "handbook");
        String s = trace.summary();
        assertTrue(s.contains("candidates=10"));
        assertTrue(s.contains("passed=5"));
        assertTrue(s.contains("maxSim=0.9200"));
        assertTrue(s.contains("sources=handbook"));
    }
}
