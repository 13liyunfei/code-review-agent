package com.codereview.agent.core.eval;

import com.codereview.agent.core.model.Finding;

import java.util.ArrayList;
import java.util.List;

/**
 * 审查级评测指标：precision / recall / 噪音比。
 *
 * <h2>★ 匹配口径（先写成一句话，再落码——两侧口径不一致必假报）</h2>
 *
 * <blockquote>
 * 一条**报出的发现** f 命中一条 **ground-truth** g，当且仅当三者同时成立：
 * <b>①</b> 文件相同（按路径后缀比较，容忍 {@code src/...} 与 {@code a/b/src/...} 的目录前缀差异）；
 * <b>②</b> 行号邻近或区间相交 —— {@code f.lineStart} 落在 {@code [g.lineStart − TOL, g.lineEnd + TOL]}
 * 之内，或 {@code f} 的行区间与 {@code g} 的行区间有重叠；
 * <b>③</b> 一对一 —— 同一 g 最多被命中一次（多条 f 命中同一 g 时取**行号最接近**的那条），
 * 且同一 f 最多命中一条 g。
 * </blockquote>
 *
 * <p>为什么需要 {@code ③} 一对一：如果允许多对一，一个 Agent 把同一个问题报三遍就能把
 * recall 刷到 100%，而 precision 只受分母影响——那指标会奖励"重复啰嗦"。本项目的去重键
 * （{@code 文件@行区间#规则}）本来就该把这种重复消掉，所以基准里也必须按一对一算，
 * 否则**去重失效反而是"高分"**，方向就反了。
 *
 * <p>为什么是"后缀比较"而不是"完全相等"：语料里的引用写法不统一（有的写仓库相对全路径、
 * 有的省略中间目录），用完全相等会把**同一处**判成不命中——这是"假 BAD"，比漏检更坏
 * （它会让接受判据失去意义）。
 */
public final class ReviewBenchMetrics {

    /** 行号邻近容差。取 3 的依据：本项目的审查发现普遍以「起始行」定位，而人类标注
     *  会往上下多写一两行（如把 `try` 与 `catch` 当成一处标注）。容差过大会把相邻的
     *  不同问题判成命中，过小则把同一处判成不命中。 */
    public static final int LINE_TOLERANCE = 3;

    /** 整体指标。 */
    public record Metrics(int cases, int groundTruth, int reported,
                          int truePositive, int falsePositive, int falseNegative,
                          double precision, double recall, double f1,
                          double noisePerCase, int degradedCases) {

        /** 空集合并集（无任何 case 可算分时用）。 */
        public static Metrics empty() {
            return new Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    private ReviewBenchMetrics() {
    }

    /**
     * 对一条 case 算指标。
     *
     * @param reported      回放产出的最终发现
     * @param groundTruth   人工标注
     * @param onlyDefectClass 是否只算"缺陷类"（security / logic / concurrency / resource）
     */
    public static Metrics evaluate(List<Finding> reported, List<BenchGroundTruth> groundTruth,
                                   boolean onlyDefectClass) {
        List<Finding> fs = reported == null ? List.of() : reported;
        List<BenchGroundTruth> gs = new ArrayList<>();
        for (BenchGroundTruth g : groundTruth == null ? List.<BenchGroundTruth>of() : groundTruth) {
            if (!onlyDefectClass || g.defectClass()) {
                gs.add(g);
            }
        }
        boolean[] fUsed = new boolean[fs.size()];
        boolean[] gUsed = new boolean[gs.size()];
        int tp = 0;
        // 一对一匹配：按「行号最接近」优先，避免"随便配一条就算中"
        for (int gi = 0; gi < gs.size(); gi++) {
            int best = -1;
            int bestDist = Integer.MAX_VALUE;
            for (int fi = 0; fi < fs.size(); fi++) {
                if (fUsed[fi] || !hit(fs.get(fi), gs.get(gi))) {
                    continue;
                }
                int d = Math.abs(fs.get(fi).lineStart() - gs.get(gi).lineStart());
                if (d < bestDist) {
                    bestDist = d;
                    best = fi;
                }
            }
            if (best >= 0) {
                fUsed[best] = true;
                gUsed[gi] = true;
                tp++;
            }
        }
        int fp = 0;
        for (boolean u : fUsed) {
            if (!u) {
                fp++;
            }
        }
        int fn = gs.size() - tp;
        double p = (tp + fp) == 0 ? 1.0 : (double) tp / (tp + fp);
        double r = gs.isEmpty() ? 1.0 : (double) tp / gs.size();
        double f1 = (p + r) == 0 ? 0 : 2 * p * r / (p + r);
        double noise = fs.size();
        return new Metrics(1, gs.size(), fs.size(), tp, fp, fn, p, r, f1, noise, 0);
    }

    /** 匹配口径的 ①②（文件 + 行邻近），一对一由调用方保证。 */
    public static boolean hit(Finding f, BenchGroundTruth g) {
        return fileMatches(f.file(), g.file()) && lineMatches(f, g);
    }

    /** 文件比较：完全相等，或一方是另一方的路径后缀（容忍目录前缀写法差异）。 */
    static boolean fileMatches(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.replace('\\', '/');
        String y = b.replace('\\', '/');
        return x.equals(y)
                || x.endsWith("/" + y)
                || y.endsWith("/" + x);
    }

    /** 行邻近或区间相交。 */
    static boolean lineMatches(Finding f, BenchGroundTruth g) {
        int fs = f.lineStart();
        int fe = Math.max(f.lineEnd(), fs);
        int gs = g.lineStart();
        int ge = Math.max(g.lineEnd(), gs);
        // 区间相交
        if (fs <= ge && gs <= fe) {
            return true;
        }
        // 邻近：f 的起点落在 g 的 [gs-TOL, ge+TOL] 之内
        return fs >= gs - LINE_TOLERANCE && fs <= ge + LINE_TOLERANCE;
    }

    /** 把多条 case 的指标聚合（加权：按 groundTruth 与 reported 的总量合并后重算）。 */
    public static Metrics aggregate(List<Metrics> parts) {
        if (parts == null || parts.isEmpty()) {
            return Metrics.empty();
        }
        int cases = 0, gt = 0, rep = 0, tp = 0, fp = 0, fn = 0, deg = 0;
        double noise = 0;
        for (Metrics m : parts) {
            cases += m.cases();
            gt += m.groundTruth();
            rep += m.reported();
            tp += m.truePositive();
            fp += m.falsePositive();
            fn += m.falseNegative();
            noise += m.noisePerCase();
            deg += m.degradedCases();
        }
        double p = (tp + fp) == 0 ? 1.0 : (double) tp / (tp + fp);
        double r = gt == 0 ? 1.0 : (double) tp / gt;
        double f1 = (p + r) == 0 ? 0 : 2 * p * r / (p + r);
        // 噪音比：每 case 平均报出条数（含 FP）——这是"打扰开发者的次数"
        double perCase = cases == 0 ? 0 : noise / cases;
        return new Metrics(cases, gt, rep, tp, fp, fn, p, r, f1, perCase, deg);
    }
}
