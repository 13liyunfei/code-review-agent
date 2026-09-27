package com.codereview.agent.core.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基准门禁：与基线比较，**回归即红**。
 *
 * <h2>门禁规则（每条都有"为什么是它"）</h2>
 * <ol>
 *   <li><b>precision 不得低于基线 − {@value #P_TOL}</b>：允许抖动，不允许掉台阶。</li>
 *   <li><b>recall 不得低于基线 − {@value #R_TOL}</b>：同上。</li>
 *   <li><b>噪音比不得高于基线 × {@value #NOISE_FACTOR}</b>：噪音会随改动悄悄上涨
 *       （多报几条没人会觉得是 bug），必须单独设闸。</li>
 *   <li><b>ground-truth 条数必须与基线一致</b>：语料被改了却不更新基线，等于偷偷换了考卷。
 *       这条拦住的是"为了让门禁变绿而删标注"。</li>
 *   <li><b>同输入同输出必须成立</b>：可复现性是这张基准的立身之本（验收判据②）。</li>
 * </ol>
 *
 * <p>为什么用"绝对下浮"而不是"百分比"：precision/recall 是 0–1 的量，百分比在小基数下会
 * 失真（0.50 → 0.49 是 2%，但绝对值只差 0.01，噪声而已）。用绝对量更直观，也更容易解释。
 */
public final class ReviewBenchGate {

    public static final double P_TOL = 0.02;
    public static final double R_TOL = 0.02;
    public static final double NOISE_FACTOR = 1.10;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASELINE_RES = "bench/BASELINE.json";

    /** 门禁结论。 */
    public record Verdict(boolean pass, List<String> failures, List<String> notes) {
        public String toText() {
            StringBuilder sb = new StringBuilder();
            for (String n : notes) {
                sb.append("  · ").append(n).append('\n');
            }
            if (pass) {
                sb.append("  结论：✅ 未发现回归\n");
            } else {
                sb.append("  结论：❌ 基准回归——\n");
                for (String f : failures) {
                    sb.append("    ! ").append(f).append('\n');
                }
            }
            return sb.toString();
        }
    }

    private ReviewBenchGate() {
    }

    /** 从 classpath 读基线。 */
    public static JsonNode loadBaseline() {
        try (InputStream in = ReviewBenchGate.class.getClassLoader().getResourceAsStream(BASELINE_RES)) {
            if (in == null) {
                return null;
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("基线读取失败：" + BASELINE_RES, e);
        }
    }

    /** 与基线比较。基线缺失时给出明确失败（而不是静默通过）。 */
    public static Verdict check(ReviewBenchReport report, JsonNode baseline) {
        List<String> fail = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (baseline == null || baseline.isMissingNode() || baseline.isEmpty()) {
            fail.add("基线文件 " + BASELINE_RES + " 不存在或为空 —— 门禁无法判定"
                    + "（请先用 writeBaseline 生成并提交）");
            return new Verdict(false, List.copyOf(fail), List.copyOf(notes));
        }
        double bp = baseline.path("all").path("precision").asDouble(-1);
        double br = baseline.path("all").path("recall").asDouble(-1);
        double bn = baseline.path("all").path("noisePerCase").asDouble(-1);
        int bgt = baseline.path("groundTruth").asInt(-1);
        double p = report.all().precision();
        double r = report.all().recall();
        double n = report.all().noisePerCase();

        if (report.groundTruthTotal() != bgt) {
            fail.add(String.format(Locale.ROOT,
                    "ground-truth 条数变了（基线 %d → 现在 %d）：语料/标注被改动时必须显式更新基线，"
                            + "否则门禁在拿不同的考卷比较", bgt, report.groundTruthTotal()));
        }
        if (p < bp - P_TOL) {
            fail.add(String.format(Locale.ROOT, "precision 回归：%.4f → %.4f（低于基线 %.4f - %.2f）",
                    bp, p, bp, P_TOL));
        }
        if (r < br - R_TOL) {
            fail.add(String.format(Locale.ROOT, "recall 回归：%.4f → %.4f（低于基线 %.4f - %.2f）",
                    br, r, br, R_TOL));
        }
        if (n > bn * NOISE_FACTOR) {
            fail.add(String.format(Locale.ROOT,
                    "噪音比上涨：%.3f → %.3f（高于基线 %.3f × %.2f）——多报几条不会被当 bug，"
                            + "所以要单独设闸", bn, n, bn, NOISE_FACTOR));
        }
        if (!report.deterministic()) {
            fail.add("同输入同输出被破坏：" + report.determinismIssues()
                    + "（可复现性失效 ⇒ 指标不可比）");
        }
        notes.add(String.format(Locale.ROOT,
                "基线 recall=%.4f / precision=%.4f / 噪音比 %.3f；" +
                        "本次 recall=%.4f / precision=%.4f / 噪音比 %.3f",
                br, bp, bn, r, p, n));
        notes.add("取舍立场：" + report.policy());
        return new Verdict(fail.isEmpty(), List.copyOf(fail), List.copyOf(notes));
    }

    /**
     * 写基线文件（**只在显式调用时执行**，且要求当前指标本身可复现）。
     *
     * <p>刻意不做成"自动生成再提交"：基线更新是一次**需要人负责的决定**——
     * 它意味着"承认这次指标变化是预期的"。自动更新会让门禁永远为绿。
     *
     * @return 写出的文件路径
     */
    public static Path writeBaseline(ReviewBenchReport report, Path target) {
        if (!report.deterministic()) {
            throw new IllegalStateException("拒绝写基线：同输入同输出未通过（" + report.determinismIssues() + "）");
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("head", report.head());
        root.put("replayOrigin", report.replayOrigin());
        root.put("corpusCases", report.corpusCases());
        root.put("scorableCases", report.scorableCases());
        root.put("groundTruth", report.groundTruthTotal());
        ObjectNode all = root.putObject("all");
        all.put("precision", report.all().precision());
        all.put("recall", report.all().recall());
        all.put("f1", report.all().f1());
        all.put("tp", report.all().truePositive());
        all.put("fp", report.all().falsePositive());
        all.put("fn", report.all().falseNegative());
        all.put("noisePerCase", report.all().noisePerCase());
        ObjectNode def = root.putObject("defectOnly");
        def.put("precision", report.defectOnly().precision());
        def.put("recall", report.defectOnly().recall());
        def.put("f1", report.defectOnly().f1());
        def.put("noisePerCase", report.defectOnly().noisePerCase());
        root.put("policy", report.policy());
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root) + "\n");
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("基线写入失败：" + target, e);
        }
    }
}
