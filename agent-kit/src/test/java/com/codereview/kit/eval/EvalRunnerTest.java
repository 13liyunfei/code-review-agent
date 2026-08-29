package com.codereview.kit.eval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvalRunnerTest {

    record F(String file, String ruleId, String title, int lineStart, String description) implements FindingLike {
    }

    private List<F> perfectFindings(EvalCase c) {
        // 对每个 GT 命中一个发现
        return c.groundTruth().stream()
                .map(g -> new F(g.file(), g.ruleKeyword(), g.ruleKeyword(), 1, "d"))
                .collect(java.util.stream.Collectors.toList());
    }

    @Test
    void 数据集聚合平均precision_recall_f1() {
        EvalDataset ds = new EvalDataset("demo");
        ds.add(new EvalCase("c1", "目标1", List.of(new LlmJudge.GroundTruth("A.java", "硬编码"))));
        ds.add(new EvalCase("c2", "目标2", List.of(new LlmJudge.GroundTruth("B.java", "注入"))));

        EvalRunner runner = new EvalRunner();
        EvalRunner.EvalReport report = runner.run(ds, this::perfectFindings);
        assertEquals("demo", report.dataset());
        assertEquals(2, report.cases());
        assertEquals(1.0, report.avgPrecision(), 1e-6);
        assertEquals(1.0, report.avgRecall(), 1e-6);
        assertEquals(1.0, report.avgF1(), 1e-6);
        assertEquals(2, report.perCase().size());
    }

    @Test
    void 有漏报时recall下降() {
        EvalDataset ds = new EvalDataset("t");
        ds.add(new EvalCase("c1", "目标1", List.of(
                new LlmJudge.GroundTruth("A.java", "硬编码"),
                new LlmJudge.GroundTruth("A.java", "注入"))));
        // 只命中 1 个 GT → recall = 0.5
        EvalRunner runner = new EvalRunner();
        EvalRunner.EvalReport report = runner.run(ds,
                c -> List.of(new F("A.java", "硬编码", "硬编码", 1, "d")));
        assertEquals(0.5, report.avgRecall(), 1e-6);
    }
}
