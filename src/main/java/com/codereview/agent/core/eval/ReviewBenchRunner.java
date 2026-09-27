package com.codereview.agent.core.eval;

import com.codereview.agent.core.model.Finding;

import java.util.ArrayList;
import java.util.List;

/**
 * 基准运行器：跑完整语料，产出 {@link ReviewBenchReport}。
 *
 * <p>它不做 I/O（语料由 {@link BenchCorpus} 加载、回放由 {@link ReviewReplay} 执行），
 * 也不写基线（那是 {@link ReviewBenchGate#writeBaseline} 的事）。**纯函数式的编排**——
 * 这样才能在 CI 里既当"跑分"又当"门禁"，且两次跑必然同结果。
 */
public final class ReviewBenchRunner {

    private final BenchCorpus corpus;
    private final ReviewReplay replay;

    public ReviewBenchRunner(BenchCorpus corpus, ReviewReplay replay) {
        this.corpus = corpus;
        this.replay = replay;
    }

    public static ReviewBenchRunner defaults() {
        return new ReviewBenchRunner(BenchCorpus.load(), new ReviewReplay());
    }

    /** 跑全部 case 并产出报告。 */
    public ReviewBenchReport run() {
        List<ReviewBenchMetrics.Metrics> allParts = new ArrayList<>();
        List<ReviewBenchMetrics.Metrics> defectParts = new ArrayList<>();
        List<ReviewBenchReport.CaseLine> lines = new ArrayList<>();
        List<String> detIssues = new ArrayList<>();
        String origin = "none";

        for (BenchCase c : corpus.cases()) {
            if (!c.replayInput().scorable()) {
                // 负向样本：无回放输入。**仍然计入语料条数**（验收判据①看的是 20 条 PR），
                // 但不参与算分——否则会把"没构造输入"算成"一条都没报出"，recall 被虚低。
                continue;
            }
            if (!"none".equals(c.replayInput().origin())) {
                origin = c.replayInput().origin();
            }
            ReviewReplay.Outcome oc = replay.replay(c);
            List<Finding> got = oc.findings();
            ReviewBenchMetrics.Metrics mAll =
                    ReviewBenchMetrics.evaluate(got, c.groundTruth(), false);
            ReviewBenchMetrics.Metrics mDef =
                    ReviewBenchMetrics.evaluate(got, c.groundTruth(), true);
            allParts.add(mAll);
            defectParts.add(mDef);
            lines.add(new ReviewBenchReport.CaseLine(
                    c.id(), c.provenance(), mAll.groundTruth(), mAll.reported(),
                    mAll.truePositive(), mAll.falsePositive(), mAll.falseNegative(),
                    mAll.precision(), mAll.recall()));

            List<String> d = replay.verifyDeterminism(c);
            for (String s : d) {
                detIssues.add(c.id() + "：" + s);
            }
        }

        ReviewBenchMetrics.Metrics all = ReviewBenchMetrics.aggregate(allParts);
        ReviewBenchMetrics.Metrics defect = ReviewBenchMetrics.aggregate(defectParts);
        return new ReviewBenchReport(
                corpus.codeReviewAgentHead(),
                origin,
                corpus.cases().size(),
                corpus.scorableCases().size(),
                corpus.groundTruthCount(),
                all,
                defect,
                List.copyOf(lines),
                detIssues.isEmpty(),
                List.copyOf(detIssues),
                ReviewBenchReport.POLICY_PRECISION_FIRST,
                "误报是本系统最贵的失败方向：审查结论直接以 PR 评论形式打扰开发者，"
                        + "每一条不该报的发现都在训练开发者忽略后续告警；相比之下漏报的代价"
                        + "在当前的开发者工作流里可以由人工评审兜底。",
                ReviewBenchReport.policyEvidenceFor(all));
    }
}
