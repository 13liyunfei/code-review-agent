package com.codereview.kit.eval;

import com.codereview.kit.ChatModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LLM 应用评估（llm-as-judge + precision/recall），kit 通用版。
 *
 * <p>两种互补信号：
 * <ul>
 *   <li>**精确匹配信号**：与 ground-truth（预埋问题清单，file+ruleId）比对，
 *       计算 precision / recall / F1——确定性、可回归；</li>
 *   <li>**LLM-as-Judge 信号**：对未匹配发现逐条请 LLM 判定真假阳性（JSON verdict），
 *       覆盖无 ground-truth 的场景；LLM 失败自动跳过（评估绝不阻断业务）。</li>
 * </ul>
 *
 * <p>使用方把领域发现对象实现 {@link FindingLike} 即可接入，
 * 不依赖任何审查域模型（如 code-review-agent 的 Finding / ReviewReport）。
 *
 * @param <F> 领域发现类型
 */
public class LlmJudge<F extends FindingLike> {

    /** ground-truth 条目（预埋问题：文件 + 规则维度即视为命中）。 */
    public record GroundTruth(String file, String ruleKeyword) {}

    /** 评估结论。 */
    public record EvalResult(
            double precision, double recall, double f1,
            int tp, int fp, int fn,
            List<String> judgeFalsePositives,
            List<String> judgeMissed,
            String judgeSummary) {}

    private final ChatModel chatModel; // 可空：为 null 时仅精确匹配信号
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmJudge(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public EvalResult evaluate(List<F> findings, List<GroundTruth> groundTruth) {
        List<F> list = findings == null ? List.of() : findings;

        // 1) 精确匹配：GT 命中 = 存在同 file 且 ruleId/title 含 ruleKeyword 的发现
        Set<String> matchedGt = new HashSet<>();
        int tp = 0;
        List<F> unmatched = new ArrayList<>();
        for (F f : list) {
            boolean hit = groundTruth != null && groundTruth.stream().anyMatch(g ->
                    f.file().contains(g.file())
                            && (f.ruleId().toLowerCase().contains(g.ruleKeyword().toLowerCase())
                            || f.title().toLowerCase().contains(g.ruleKeyword().toLowerCase())));
            if (hit) {
                tp++;
                groundTruth.forEach(g -> {
                    if (f.file().contains(g.file())
                            && (f.ruleId().toLowerCase().contains(g.ruleKeyword().toLowerCase())
                            || f.title().toLowerCase().contains(g.ruleKeyword().toLowerCase()))) {
                        matchedGt.add(g.file() + ":" + g.ruleKeyword());
                    }
                });
            } else {
                unmatched.add(f);
            }
        }
        int gtSize = groundTruth == null ? 0 : groundTruth.size();
        int fn = Math.max(0, gtSize - matchedGt.size());
        int fp = unmatched.size();
        double precision = tp + fp == 0 ? 1.0 : (double) tp / (tp + fp);
        double recall = gtSize == 0 ? 1.0 : (double) tp / gtSize;
        double f1 = precision + recall == 0 ? 0 : 2 * precision * recall / (precision + recall);

        // 2) LLM-as-Judge：对未匹配发现判定真假阳性（可空 / 失败跳过）
        List<String> judgeFp = new ArrayList<>();
        List<String> judgeMissed = new ArrayList<>();
        String summary = "llm-as-judge 未启用";
        if (chatModel != null && !unmatched.isEmpty()) {
            int fpCount = 0;
            for (F f : unmatched) {
                try {
                    String resp = chatModel.chat("判定这条代码审查发现是否为真实问题（是输出 {\"verdict\":\"TP\"}，"
                            + "误报输出 {\"verdict\":\"FP\"}，仅输出 JSON）：\n"
                            + "[" + f.ruleId() + "] " + f.title() + " @ " + f.file() + ":L" + f.lineStart()
                            + "\n" + f.description());
                    JsonNode n = parse(resp);
                    if (n != null && "FP".equalsIgnoreCase(n.path("verdict").asText())) {
                        judgeFp.add(f.ruleId() + " " + f.title());
                        fpCount++;
                    }
                } catch (Exception e) {
                    // 单条判定失败忽略
                }
            }
            summary = "llm-as-judge 复核 " + unmatched.size() + " 条未匹配发现，判误报 " + fpCount + " 条";
        }
        return new EvalResult(precision, recall, f1, tp, fp, fn, judgeFp, judgeMissed, summary);
    }

    private JsonNode parse(String text) {
        try {
            String t = text.trim();
            int s = t.indexOf('{');
            int e = t.lastIndexOf('}');
            return (s < 0 || e <= s) ? null : mapper.readTree(t.substring(s, e + 1));
        } catch (Exception ex) {
            return null;
        }
    }
}
