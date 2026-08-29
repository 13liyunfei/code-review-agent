package com.codereview.kit.eval;

import java.util.List;
import java.util.function.Function;

/**
 * 评估运行器：对数据集逐用例产出发现并聚合 precision/recall/F1（平均），
 * 形成可回归的基准报告——"新版本跑同一数据集，质量不能变差"。
 */
public class EvalRunner {

    /** 聚合报告。 */
    public record EvalReport(String dataset, int cases, double avgPrecision,
                             double avgRecall, double avgF1, List<PerCase> perCase) {
        public record PerCase(String id, double precision, double recall, double f1) {
        }
    }

    private final LlmJudge<?> judge; // 仅复用精确匹配逻辑（llm-as-judge 由调用方决定是否启用）

    public EvalRunner() {
        this(null);
    }

    public EvalRunner(LlmJudge<?> judge) {
        this.judge = judge;
    }

    /**
     * @param dataset  数据集
     * @param produce  给定用例，产出领域发现列表（被测系统）
     * @return 聚合报告
     */
    public <F extends FindingLike> EvalReport run(EvalDataset dataset,
                                                  Function<EvalCase, List<F>> produce) {
        LlmJudge<F> j = judge == null ? new LlmJudge<>(null) : (LlmJudge<F>) judge;
        List<EvalReport.PerCase> per = new java.util.ArrayList<>();
        double sp = 0, sr = 0, sf = 0;
        for (EvalCase c : dataset.cases()) {
            List<F> findings = produce.apply(c);
            LlmJudge.EvalResult r = j.evaluate(findings, c.groundTruth());
            per.add(new EvalReport.PerCase(c.id(), r.precision(), r.recall(), r.f1()));
            sp += r.precision();
            sr += r.recall();
            sf += r.f1();
        }
        int n = Math.max(1, dataset.cases().size());
        return new EvalReport(dataset.name(), dataset.cases().size(),
                sp / n, sr / n, sf / n, List.copyOf(per));
    }
}
