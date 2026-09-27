package com.codereview.agent.core.eval;

import com.codereview.agent.core.model.Finding;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 基准报告：把「改完到底变好没有」变成一份可读、可存档、可进 CI 的结论。
 *
 * <p><b>★ 本报告必须显式写出当前是"精度优先"还是"召回优先"以及理由</b>——
 * 这是从 alibaba/open-code-review 的 bench 学到的最重要一条纪律：它的 README 敢写
 * "Recall 低于通用 Agent，这是**刻意的 precision-over-noise 取舍**"。**敢于把取舍写在明面上，
 * 读者才知道该怎么读那些数字**；不写，读者会拿它当"排行榜上的分数"，然后按错误的方向优化。
 *
 * <p>本报告给出支撑该声明的**具体数字**（FP 占报出量的比例、噪音比），而不是只给一句立场。
 */
public record ReviewBenchReport(
        String head,
        String replayOrigin,
        int corpusCases,
        int scorableCases,
        int groundTruthTotal,
        ReviewBenchMetrics.Metrics all,
        ReviewBenchMetrics.Metrics defectOnly,
        List<CaseLine> perCase,
        boolean deterministic,
        List<String> determinismIssues,
        String policy,
        String policyReason,
        List<String> policyEvidence) {

    /** 单条 case 的指标行。 */
    public record CaseLine(String id, String provenance, int groundTruth, int reported,
                           int tp, int fp, int fn, double precision, double recall) {
    }

    /** 当前取舍立场。改这个常量就等于改基准的默认优化方向，必须同时改理由。 */
    public static final String POLICY_PRECISION_FIRST = "精度优先（precision-first）";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 渲染为人类可读文本（CI 日志与 PR 评论都直接用这个）。 */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        sb.append("════════════════════════════════════════════════════════════════\n");
        sb.append("审查级评测基准报告（P1-6）\n");
        sb.append("════════════════════════════════════════════════════════════════\n");
        sb.append(String.format(Locale.ROOT, "语料            : %d 条（可算分 %d 条）%n",
                corpusCases, scorableCases));
        sb.append(String.format(Locale.ROOT, "ground-truth    : %d 条%n", groundTruthTotal));
        sb.append(String.format(Locale.ROOT, "回放输入来源    : %s%n", replayOrigin));
        sb.append(String.format(Locale.ROOT, "code-review-agent HEAD（采集时）: %s%n", head));
        sb.append(String.format(Locale.ROOT, "同输入同输出    : %s%n",
                deterministic ? "✅ 全部 case 两次回放逐条一致" : "❌ 存在不一致"));
        for (String s : determinismIssues) {
            sb.append("    · ").append(s).append('\n');
        }
        sb.append('\n');
        sb.append("── 指标（全部 ground-truth）────────────────────────────────────\n");
        sb.append(metricsLine(all));
        sb.append("\n── 指标（仅缺陷类：security/logic/concurrency/resource）────────\n");
        sb.append(metricsLine(defectOnly));
        sb.append('\n');
        sb.append("── 逐条 case ───────────────────────────────────────────────────\n");
        sb.append(String.format(Locale.ROOT, "  %-24s %-12s %4s %4s %4s %4s %4s %7s %7s%n",
                "caseId", "provenance", "GT", "报出", "TP", "FP", "FN", "P", "R"));
        for (CaseLine c : perCase) {
            sb.append(String.format(Locale.ROOT, "  %-24s %-12s %4d %4d %4d %4d %4d %7.3f %7.3f%n",
                    c.id(), c.provenance(), c.groundTruth(), c.reported(),
                    c.tp(), c.fp(), c.fn(), c.precision(), c.recall()));
        }
        sb.append('\n');
        sb.append("── ★ 取舍立场 ──────────────────────────────────────────────────\n");
        sb.append("  当前：").append(policy).append('\n');
        sb.append("  理由：").append(policyReason).append('\n');
        for (String e : policyEvidence) {
            sb.append("    · ").append(e).append('\n');
        }
        sb.append('\n');
        sb.append("── 怎么读这些数字（否则会读错）────────────────────────────────\n");
        sb.append("  · **绝对分不可跨语料比较**：FP 由语料里构造的受控噪音（每 case 2 条）决定，\n");
        sb.append("    所以 precision 的绝对值反映的是「这份考卷出了多少道干扰题」，不是系统水平。\n");
        sb.append("    **有意义的量是同语料下的前后差**（这正是 CI 门禁比较的对象）。\n");
        sb.append("  · recall=1.000 的含义是「确定性链当前一条真问题都没丢」——\n");
        sb.append("    去重/仲裁/抑制/否决这一段是保守的。任何让 recall 掉下 1.000 的改动\n");
        sb.append("    都是**真回归**（链开始吞掉本该报出的结论）。\n");
        sb.append("  · 噪音比的口径 = 每 case 平均**报出条数**（含 TP，因为对开发者而言每条都是打扰），\n");
        sb.append("    不是「误报平均条数」。\n");
        sb.append('\n');
        sb.append("── 本基准不测什么（避免误读）──────────────────────────────────\n");
        sb.append("  · 不测模型质量、prompt 效果、RAG 召回——回放输入是冻结的候选发现\n");
        sb.append("    （origin=").append(replayOrigin).append("），基准回答的是\n");
        sb.append("    「同一批候选，经过去重/仲裁/抑制/否决/聚合之后，剩下的集合对不对」。\n");
        sb.append("  · 逐条人工复核仍是待办：当前标注 = 规则裁决 + 每类抽样读 hunk（见 labels.tsv）。\n");
        return sb.toString();
    }

    private static String metricsLine(ReviewBenchMetrics.Metrics m) {
        return String.format(Locale.ROOT,
                "  precision=%.4f  recall=%.4f  F1=%.4f%n"
                        + "  TP=%d  FP=%d  FN=%d%n"
                        + "  噪音比（每 case 平均报出条数）=%.3f%n",
                m.precision(), m.recall(), m.f1(), m.truePositive(), m.falsePositive(),
                m.falseNegative(), m.noisePerCase());
    }

    /** 渲染为 JSON（给门禁与存档用）。 */
    public String toJson() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("head", head);
        root.put("replayOrigin", replayOrigin);
        root.put("corpusCases", corpusCases);
        root.put("scorableCases", scorableCases);
        root.put("groundTruth", groundTruthTotal);
        root.put("deterministic", deterministic);
        root.set("all", metricsNode(all));
        root.set("defectOnly", metricsNode(defectOnly));
        ArrayNode arr = root.putArray("cases");
        for (CaseLine c : perCase) {
            ObjectNode n = arr.addObject();
            n.put("id", c.id());
            n.put("groundTruth", c.groundTruth());
            n.put("reported", c.reported());
            n.put("tp", c.tp());
            n.put("fp", c.fp());
            n.put("fn", c.fn());
        }
        root.put("policy", policy);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("报告序列化失败", e);
        }
    }

    private static ObjectNode metricsNode(ReviewBenchMetrics.Metrics m) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("precision", m.precision());
        n.put("recall", m.recall());
        n.put("f1", m.f1());
        n.put("tp", m.truePositive());
        n.put("fp", m.falsePositive());
        n.put("fn", m.falseNegative());
        n.put("noisePerCase", m.noisePerCase());
        return n;
    }

    /**
     * 生成取舍证据（**用数字说话**，不是只写立场）。
     *
     * @param m 全部 ground-truth 口径的指标
     */
    public static List<String> policyEvidenceFor(ReviewBenchMetrics.Metrics m) {
        List<String> ev = new ArrayList<>();
        double fpShare = m.reported() == 0 ? 0 : (double) m.falsePositive() / m.reported();
        ev.add(String.format(Locale.ROOT,
                "基线实测：FP 占报出量的 %.1f%%（%d/%d），噪音比 %.2f 条/case",
                fpShare * 100, m.falsePositive(), m.reported(), m.noisePerCase()));
        ev.add(String.format(Locale.ROOT,
                "precision=%.3f 而 recall=%.3f：当前系统在「少说」这一侧强于「说全」这一侧",
                m.precision(), m.recall()));
        ev.add("产品语境：审查结论以 PR 评论形式直接触达开发者，误报会训练开发者忽略告警"
                + "（本项目在告警质量上已有的结论：正确响应是「看一眼然后什么都不做」的告警应被删掉，而不是修好）");
        ev.add("外部先例：alibaba/open-code-review 的 bench 公开写明「Recall 低于通用 Agent，"
                + "是刻意的 precision-over-noise 取舍」——高召回在同类系统里通常是用噪音换的");
        ev.add("若要切到召回优先：可放宽抑制/降低阈值，但报出量会上升，"
                + "**噪音比是这次切换的直接代价**，必须在切换前先定下可接受的噪音上限");
        return ev;
    }
}
