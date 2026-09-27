package com.codereview.agent.core.eval;

import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基准指标与匹配口径的单元测试。
 *
 * <p>铁律：每条判据都要有「必须命中」与「必须不命中」两个样本——否则这个检查项
 * 在真实语料上跑出全绿也不能说明任何事（本项目在文档审计上反复踩过这个坑）。
 */
class ReviewBenchMetricsTest {

    private static Finding f(String file, int line, String ruleId) {
        return new Finding(AgentType.LOGIC, file, line, line, Severity.MAJOR, "logic",
                ruleId, "t", "d", "s", 0.8, "LLM");
    }

    private static BenchGroundTruth g(String file, int line, String category) {
        return new BenchGroundTruth("R-1", category, Severity.MAJOR, file, line, line, "n", "manual");
    }

    @Test
    void exactMatchIsHit() {
        var m = ReviewBenchMetrics.evaluate(List.of(f("a/B.java", 10, "R-1")),
                List.of(g("a/B.java", 10, "logic")), false);
        assertEquals(1, m.truePositive());
        assertEquals(0, m.falsePositive());
        assertEquals(0, m.falseNegative());
        assertEquals(1.0, m.precision(), 1e-9);
        assertEquals(1.0, m.recall(), 1e-9);
    }

    @Test
    void directoryPrefixDifferenceStillHits() {
        // 语料里的引用写法不统一（有的写全路径、有的省略中间目录）——
        // 用完全相等会把同一处判成不命中，那是"假 BAD"，比漏检更坏。
        var m = ReviewBenchMetrics.evaluate(List.of(f("src/main/java/A.java", 10, "R-1")),
                List.of(g("main/java/A.java", 10, "logic")), false);
        assertEquals(1, m.truePositive(), "路径后缀相同应命中");
    }

    @Test
    void withinToleranceHitsAndBeyondDoesNot() {
        int tol = ReviewBenchMetrics.LINE_TOLERANCE;
        var in = ReviewBenchMetrics.evaluate(List.of(f("A.java", 10 + tol, "R-1")),
                List.of(g("A.java", 10, "logic")), false);
        assertEquals(1, in.truePositive(), "容差内应命中");
        var out = ReviewBenchMetrics.evaluate(List.of(f("A.java", 10 + tol + 1, "R-1")),
                List.of(g("A.java", 10, "logic")), false);
        assertEquals(0, out.truePositive(), "容差外不应命中");
        assertEquals(1, out.falsePositive());
        assertEquals(1, out.falseNegative());
    }

    @Test
    void differentFileNeverHits() {
        var m = ReviewBenchMetrics.evaluate(List.of(f("A.java", 10, "R-1")),
                List.of(g("B.java", 10, "logic")), false);
        assertEquals(0, m.truePositive());
    }

    @Test
    void manyToOneIsCountedOncePerGroundTruth() {
        // 一个 Agent 把同一处报三遍，不能把 recall 刷到 3 倍；
        // 也不能让"去重失效"看起来像高分。
        var m = ReviewBenchMetrics.evaluate(
                List.of(f("A.java", 10, "R-1"), f("A.java", 11, "R-1"), f("A.java", 12, "R-1")),
                List.of(g("A.java", 10, "logic")), false);
        assertEquals(1, m.truePositive(), "同一 ground-truth 只算一次命中");
        assertEquals(2, m.falsePositive(), "多出来的两条算误报");
        assertEquals(1.0, m.recall(), 1e-9);
        assertEquals(1.0 / 3, m.precision(), 1e-9);
    }

    @Test
    void oneFindingCannotServeTwoGroundTruths() {
        var m = ReviewBenchMetrics.evaluate(List.of(f("A.java", 10, "R-1")),
                List.of(g("A.java", 10, "logic"), g("A.java", 11, "logic")), false);
        assertEquals(1, m.truePositive(), "一对一：一条发现只能兑现一条 ground-truth");
        assertEquals(1, m.falseNegative());
    }

    @Test
    void defectClassFilterSplitsMetrics() {
        List<BenchGroundTruth> gt = List.of(g("A.java", 10, "logic"), g("A.java", 20, "style"));
        var all = ReviewBenchMetrics.evaluate(List.of(f("A.java", 10, "R-1")), gt, false);
        assertEquals(2, all.groundTruth());
        assertEquals(0.5, all.recall(), 1e-9);
        var def = ReviewBenchMetrics.evaluate(List.of(f("A.java", 10, "R-1")), gt, true);
        assertEquals(1, def.groundTruth(), "只算缺陷类时 style 那条不进分母");
        assertEquals(1.0, def.recall(), 1e-9);
    }

    @Test
    void noiseRatioCountsReportedPerCase() {
        var m = ReviewBenchMetrics.evaluate(
                List.of(f("A.java", 10, "R-1"), f("A.java", 900, "X"), f("A.java", 950, "Y")),
                List.of(g("A.java", 10, "logic")), false);
        assertEquals(3.0, m.noisePerCase(), 1e-9);
    }

    @Test
    void aggregateRecomputesFromTotalsNotAverageOfRatios() {
        // 两条 case：容量差很大时，"比率的平均"会给出错误结论。
        var big = ReviewBenchMetrics.evaluate(
                List.of(f("A.java", 10, "R-1"), f("A.java", 11, "R-1"), f("A.java", 12, "R-1")),
                List.of(g("A.java", 10, "logic"), g("A.java", 11, "logic"), g("A.java", 12, "logic")),
                false);
        var small = ReviewBenchMetrics.evaluate(List.of(f("B.java", 10, "R-1")),
                List.of(g("B.java", 10, "logic"), g("B.java", 11, "logic")), false);
        var agg = ReviewBenchMetrics.aggregate(List.of(big, small));
        assertEquals(5, agg.groundTruth());
        assertEquals(4, agg.truePositive());
        assertEquals(0.8, agg.recall(), 1e-9);
        assertEquals(2, agg.cases());
    }

    @Test
    void emptyReportedMeansZeroRecallNotOne() {
        var m = ReviewBenchMetrics.evaluate(List.of(), List.of(g("A.java", 10, "logic")), false);
        assertEquals(0.0, m.recall(), 1e-9);
        assertFalse(Double.isNaN(m.precision()), "没有报出任何东西时 precision 不应是 NaN");
    }

    @Test
    void noGroundTruthMeansRecallOneButPrecisionPunishesNoise() {
        var m = ReviewBenchMetrics.evaluate(List.of(f("A.java", 10, "R-1")), List.of(), false);
        assertEquals(1.0, m.recall(), 1e-9, "没有 ground-truth 时 recall 约定为 1（无召回缺口）");
        assertEquals(0.0, m.precision(), 1e-9, "但报出来的每一条都是误报");
    }

    @Test
    void matcherIsSymmetricForFileSuffixButNotForOrderOfSeverity() {
        assertTrue(ReviewBenchMetrics.fileMatches("x/y/A.java", "y/A.java"));
        assertTrue(ReviewBenchMetrics.fileMatches("y/A.java", "x/y/A.java"));
        assertTrue(ReviewBenchMetrics.fileMatches("A.java", "A.java"));
        assertFalse(ReviewBenchMetrics.fileMatches("A.java", "AB.java"));
    }
}
