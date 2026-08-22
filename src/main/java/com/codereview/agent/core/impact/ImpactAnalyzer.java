package com.codereview.agent.core.impact;

import com.codereview.agent.core.analysis.AstAnalyzer;
import com.codereview.agent.core.analysis.CallGraphAnalyzer;
import com.codereview.agent.core.model.CodeDiff;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文影响面分析器（对齐 codex 的 {@code context-fragments}）。
 *
 * <p>大 PR 若把全部 diff 一次性塞给模型，既浪费 token 又稀释审查焦点。本分析器复用既有
 * {@link AstAnalyzer} 与 {@link CallGraphAnalyzer}，计算「本次变更的方法 → 其上游调用方」
 * 的传播链，从而回答：<b>哪些改动方法存在显著的上游影响面，需要被优先重点回归</b>。
 *
 * <p>设计要点：
 * <ul>
 *   <li>纯静态、零外部依赖，可离线运行；</li>
 *   <li>只处理 Java 文件（其它语言暂无结构解析能力，直接跳过）；</li>
 *   <li>产出结构化 {@link ImpactReport}（供程序消费）与简洁 markdown（供注入 Agent prompt）；</li>
 *   <li>无影响面时为 {@code ""}，调用方据此决定是否注入，避免噪声。</li>
 * </ul>
 */
@Component
public class ImpactAnalyzer {

    /** 单条影响面条目。 */
    public record ImpactEntry(String file, String method, int callerCount, List<String> callers) {}

    /** 一次 PR 的影响面分析结果。 */
    public record ImpactReport(List<ImpactEntry> entries, int changedJavaFiles) {}

    /**
     * 分析 PR 变更的影响面。
     *
     * @param diffs 代码变更
     * @return 影响面报告（已按调用方数量降序，仅含存在上游调用方的条目）
     */
    public ImpactReport analyze(List<CodeDiff> diffs) {
        List<ImpactEntry> entries = new ArrayList<>();
        int javaFiles = 0;

        for (CodeDiff d : diffs) {
            if (!"java".equals(d.language())) {
                continue;
            }
            javaFiles++;
            String source = reconstruct(d.patch());
            AstAnalyzer.AstReport report = AstAnalyzer.analyze(source, d.fileName());
            CallGraphAnalyzer.CallGraphReport graph = CallGraphAnalyzer.analyze(source, report);

            for (AstAnalyzer.ClassInfo ci : report.classes()) {
                for (AstAnalyzer.MethodInfo m : ci.methods()) {
                    Set<String> up = graph.impact(m.name());
                    if (up.isEmpty()) {
                        continue;
                    }
                    // 去重调用方，保持顺序稳定
                    List<String> callers = new ArrayList<>(new LinkedHashSet<>(up));
                    entries.add(new ImpactEntry(d.fileName(), m.name(), callers.size(), callers));
                }
            }
        }

        entries.sort(Comparator.comparingInt(ImpactEntry::callerCount).reversed());
        return new ImpactReport(entries, javaFiles);
    }

    /**
     * 生成注入 Agent prompt 的简洁影响面摘要。
     *
     * @param diffs 代码变更
     * @return markdown 摘要；无影响面时返回空串（调用方据此决定是否注入）
     */
    public String summarize(List<CodeDiff> diffs) {
        ImpactReport report = analyze(diffs);
        if (report.entries().isEmpty()) {
            return "";
        }
        // 仅展示影响面最广的若干方法，避免提示词过长
        int topN = Math.min(15, report.entries().size());
        StringBuilder sb = new StringBuilder();
        sb.append("本次变更涉及 ").append(report.changedJavaFiles()).append(" 个 Java 文件，")
                .append(report.entries().size()).append(" 个方法存在上游调用方，影响面最广的如下：\n");
        for (int i = 0; i < topN; i++) {
            ImpactEntry e = report.entries().get(i);
            List<String> callers = e.callers();
            String top = callers.size() <= 6 ? String.join(", ", callers)
                    : String.join(", ", callers.subList(0, 6)) + " 等 " + callers.size() + " 个";
            sb.append("- `").append(e.file()).append("` 的 `").append(e.method()).append("()` 被 ")
                    .append(e.callerCount()).append(" 个上游方法调用（").append(top).append("）。\n");
        }
        sb.append("请对上述存在广泛调用关系的方法改动给予更高关注，重点评估回归风险。");
        return sb.toString();
    }

    /**
     * 从 unified diff 重建新文件文本（去掉 - 行），供结构分析使用。
     * 与 {@code AdvancedAnalyzer#reconstruct} 同义，独立保留以避免跨包耦合。
     */
    private static String reconstruct(String patch) {
        if (patch == null || patch.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean inHeader = true;
        for (String raw : patch.split("\n", -1)) {
            if (raw.startsWith("diff --git") || raw.startsWith("index ")
                    || raw.startsWith("--- ") || raw.startsWith("+++ ")) {
                continue;
            }
            if (raw.startsWith("@@")) {
                inHeader = false;
                continue;
            }
            if (inHeader) {
                continue;
            }
            if (raw.startsWith("-") && !raw.startsWith("---")) {
                continue;
            }
            String content = raw.length() > 0 && (raw.startsWith("+") || raw.startsWith(" "))
                    ? raw.substring(1) : raw;
            sb.append(content).append('\n');
        }
        return sb.toString();
    }
}
