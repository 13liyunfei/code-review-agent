package com.codereview.agent.core.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语料完整性 + 验收判据 + 门禁规则测试。**这是 CI 门禁的执行体**。
 *
 * <p>验收判据（方案 §P1-6）：
 * <ol>
 *   <li>基准集 ≥ 20 PR 且 ground-truth issue ≥ 60 条；</li>
 *   <li>同一次改动前后两次跑分可复现（同输入同输出）；</li>
 *   <li>报告里显式写出当前是「精度优先」还是「召回优先」及理由。</li>
 * </ol>
 */
class ReviewBenchGateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void corpusMeetsAcceptanceCriteria() {
        BenchCorpus corpus = BenchCorpus.load();
        assertTrue(corpus.cases().size() >= 20,
                "验收判据①：语料需 ≥ 20 个 PR，实际 " + corpus.cases().size());
        assertTrue(corpus.groundTruthCount() >= 60,
                "验收判据①：ground-truth issue 需 ≥ 60 条，实际 " + corpus.groundTruthCount());
        assertTrue(corpus.scorableCases().size() >= 15, "可算分 case 太少，指标会不稳");
    }

    @Test
    void everyCaseCarriesProvenanceAndEveryLabelCarriesNoteAndSource() {
        for (BenchCase c : BenchCorpus.load().cases()) {
            assertTrue(c.repoKey() != null && !c.repoKey().isBlank(), c.id() + " 缺 repoKey");
            assertTrue(c.provenance() != null && !c.provenance().isBlank(), c.id() + " 缺 provenance");
            assertTrue(c.shaShort() != null && !c.shaShort().isBlank(), c.id() + " 缺 commit/PR 标识");
            assertTrue(c.patchSha256() != null && c.patchSha256().length() == 64,
                    c.id() + " 缺 patch 哈希（语料漂移检测失效）");
            for (BenchGroundTruth g : c.groundTruth()) {
                assertTrue(g.note() != null && !g.note().isBlank(),
                        c.id() + " " + g.file() + ":" + g.lineStart() + " 的标注没写「为什么是真问题」");
                assertTrue("manual".equals(g.labelSource())
                                || "human-confirmed-machine-candidate".equals(g.labelSource()),
                        c.id() + " 的标注来源不合法：" + g.labelSource());
            }
        }
    }

    @Test
    void benchRunsAndIsDeterministicAndReportStatesTradeoff() {
        ReviewBenchReport report = ReviewBenchRunner.defaults().run();
        assertTrue(report.deterministic(),
                "验收判据②：同输入同输出被破坏 " + report.determinismIssues());
        assertTrue(report.scorableCases() > 0, "没有任何可算分 case");
        assertTrue(report.all().reported() > 0, "一条都没报出，指标无意义");
        // 验收判据③：取舍立场必须写在报告里，且带数据支撑
        assertNotNull(report.policy());
        assertTrue(report.policy().contains("精度优先") || report.policy().contains("召回优先"),
                "报告必须显式写出取舍方向");
        assertTrue(report.policyEvidence().size() >= 3, "取舍要有数据/语境支撑，不能只写一句立场");
        assertTrue(report.policyEvidence().stream().anyMatch(e -> e.contains("FP 占报出量")),
                "取舍证据里必须包含实测数字");
        // 打印一次，便于人工核对与 CI 留痕
        System.out.println(report.toText());
    }

    @Test
    void gateFailsWhenBaselineMissing() {
        ReviewBenchReport report = ReviewBenchRunner.defaults().run();
        ReviewBenchGate.Verdict v = ReviewBenchGate.check(report, null);
        assertTrue(!v.pass(), "基线缺失时必须失败，而不是静默通过");
        assertTrue(v.failures().get(0).contains("基线"));
    }

    @Test
    void gateFailsOnPrecisionRegression() throws Exception {
        ReviewBenchReport report = ReviewBenchRunner.defaults().run();
        // ★ 方向必须对：模拟"回归"是让**基线比现在更好**（precision 更高），
        //   而不是更低——反了的话这条"必报样本"永远不会报，等于没测。
        JsonNode baseline = baselineNodeWith(report, report.all().precision() + 0.5,
                report.all().recall(), report.all().noisePerCase(), report.groundTruthTotal());
        ReviewBenchGate.Verdict v = ReviewBenchGate.check(report, baseline);
        assertTrue(!v.pass());
        assertTrue(v.failures().stream().anyMatch(s -> s.contains("precision 回归")), v.failures().toString());
    }

    @Test
    void gateFailsOnRecallRegression() throws Exception {
        ReviewBenchReport report = ReviewBenchRunner.defaults().run();
        JsonNode baseline = baselineNodeWith(report, report.all().precision(),
                report.all().recall() + 0.5, report.all().noisePerCase(), report.groundTruthTotal());
        ReviewBenchGate.Verdict v = ReviewBenchGate.check(report, baseline);
        assertTrue(!v.pass());
        assertTrue(v.failures().stream().anyMatch(s -> s.contains("recall 回归")), v.failures().toString());
    }

    @Test
    void gateFailsWhenNoiseRises() throws Exception {
        ReviewBenchReport report = ReviewBenchRunner.defaults().run();
        // 噪音是"越低越好"：模拟回归要让**基线噪音更低**，现在才显得超标
        JsonNode baseline = baselineNodeWith(report, report.all().precision(), report.all().recall(),
                report.all().noisePerCase() / 2.0, report.groundTruthTotal());
        ReviewBenchGate.Verdict v = ReviewBenchGate.check(report, baseline);
        assertTrue(!v.pass());
        assertTrue(v.failures().stream().anyMatch(s -> s.contains("噪音比上涨")), v.failures().toString());
    }

    @Test
    void gateFailsWhenGroundTruthCountChanged() throws Exception {
        ReviewBenchReport report = ReviewBenchRunner.defaults().run();
        JsonNode baseline = baselineNodeWith(report, report.all().precision(), report.all().recall(),
                report.all().noisePerCase(), report.groundTruthTotal() - 1);
        ReviewBenchGate.Verdict v = ReviewBenchGate.check(report, baseline);
        assertTrue(!v.pass(), "语料/标注被改动却不更新基线，等于偷换考卷");
        assertTrue(v.failures().stream().anyMatch(s -> s.contains("ground-truth 条数变了")),
                v.failures().toString());
    }

    @Test
    void gatePassesAgainstItsOwnBaseline() throws Exception {
        ReviewBenchReport report = ReviewBenchRunner.defaults().run();
        JsonNode baseline = baselineNodeWith(report, report.all().precision(), report.all().recall(),
                report.all().noisePerCase(), report.groundTruthTotal());
        ReviewBenchGate.Verdict v = ReviewBenchGate.check(report, baseline);
        assertTrue(v.pass(), "与自身指标一致的基线必须通过：" + v.failures());
    }

    @Test
    void committedBaselineExistsAndMatchesCurrentMetrics() {
        JsonNode b = ReviewBenchGate.loadBaseline();
        assertNotNull(b, "committed 的 bench/BASELINE.json 缺失——门禁在 CI 里会永远红灯，"
                + "而红灯的代价是没人再信它");
        ReviewBenchReport report = ReviewBenchRunner.defaults().run();
        ReviewBenchGate.Verdict v = ReviewBenchGate.check(report, b);
        assertTrue(v.pass(), "committed 基线已与当前指标不符（要么是真回归，要么该显式更新基线）：\n"
                + v.toText());
    }

    /** 造一个只改动指定字段的基线节点（用于验证门禁规则的必报样本）。 */
    private static JsonNode baselineNodeWith(ReviewBenchReport r, double p, double rec,
                                             double noise, int gt) {
        var root = MAPPER.createObjectNode();
        root.put("groundTruth", gt);
        var all = root.putObject("all");
        all.put("precision", p);
        all.put("recall", rec);
        all.put("noisePerCase", noise);
        return root;
    }

    /** 基线刷新入口（**显式调用**，不参与常规测试）：{@code mvn -Dbench.updateBaseline=true test}。 */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
            named = "bench.updateBaseline", matches = "true")
    void updateBaselineExplicitly() {
        ReviewBenchReport report = ReviewBenchRunner.defaults().run();
        Path out = ReviewBenchGate.writeBaseline(report,
                Path.of("src/main/resources/bench/BASELINE.json"));
        System.out.println("[Bench] 基线已写入：" + out.toAbsolutePath());
    }
}
