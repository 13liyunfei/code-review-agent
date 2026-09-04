package com.codereview.agent.core.impact;

import com.codereview.agent.core.analysis.index.RepoIndex;
import com.codereview.agent.core.analysis.spi.AnalysisUnit;
import com.codereview.agent.core.analysis.spi.CodeAnalyzer;
import com.codereview.agent.core.model.CodeDiff;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 变更影响面分析器：回答「本次改动的方法，上游有哪些调用方需要重点回归」。
 *
 * <h2>为什么重写</h2>
 * 旧实现把 diff 的 hunk 片段喂给手写正则的 AST 扫描器，存在三层漏斗塌缩：
 * <ol>
 *   <li>diff 只含改动处 ±3 行上下文，<b>不是完整文件</b>；</li>
 *   <li>AST 层只在「类块闭合时」才把方法挂到 {@code ClassInfo}，而 hunk 几乎不可能
 *       包含文件顶部的 {@code class X {} —— 方法解析出来却无处挂载，被丢弃；</li>
 *   <li>调用图只看单文件，跨文件调用方从来就看不见。</li>
 * </ol>
 * 结果：真实 PR 上恒产出 0 条结论，而测试因为用「新增文件全量 patch」
 * （{@code @@ -0,0 +1,N @@}）侥幸通过——测试 helper 悄悄替生产代码满足了前提。
 *
 * <h2>新实现</h2>
 * <ul>
 *   <li><b>完整文件</b>：由 {@link RepoIndex} 拉取全量内容，喂给真正的解析器
 *       （Java 走 JavaParser，其余走 tree-sitter）；</li>
 *   <li><b>精确定位被改方法</b>：解析 hunk 头拿到新文件行号范围，与方法行范围求交，
 *       只分析真正被改动的方法，而不是文件内所有方法；</li>
 *   <li><b>跨文件调用方</b>：Java 引擎解析出全限定签名，据此查索引倒排表。</li>
 * </ul>
 *
 * <h2>能力分级</h2>
 * 非 Java 语言走 tree-sitter，只能给出<b>文件内</b>调用方（无类型信息，无法解析跨文件引用）。
 * 这不是缺陷而是原理性限制，故每条结论都带 {@link ImpactEntry#crossFile()} 标记，
 * 摘要文案据此区分表述，避免把「查不了」说成「没有」。
 */
@Component
public class ImpactAnalyzer {

    /** hunk 头：{@code @@ -a,b +c,d @@}，取新文件侧起始行与行数。 */
    private static final Pattern HUNK =
            Pattern.compile("^@@+ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@", Pattern.MULTILINE);

    /**
     * 单条影响面结论。
     *
     * @param file       所属文件
     * @param method     被改动的方法名
     * @param callerCount 上游调用方数量
     * @param callers    调用方列表（已去重、顺序稳定）
     * @param crossFile  true=调用方来自跨文件索引（精确）；false=仅文件内（语言引擎无符号解析能力）
     */
    public record ImpactEntry(String file, String method, int callerCount,
                              List<String> callers, boolean crossFile) {
    }

    /**
     * 影响面报告。
     *
     * @param entries         按调用方数量降序的影响面条目
     * @param changedFiles    本次变更中参与分析的文件数
     * @param unsupportedLangs 无引擎支持、被跳过的语言（<b>可观测</b>：静默跳过正是旧实现的病灶）
     * @param mode            本次分析采用的模式，用于诊断「为什么没结论」
     */
    public record ImpactReport(List<ImpactEntry> entries, int changedFiles,
                               List<String> unsupportedLangs, Mode mode) {

        public boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    /**
     * 分析模式——用于**区分「没有调用方」和「没能力查」**。
     *
     * <p>旧实现最大的问题不是算不准，而是失败时与「确实没有影响面」无法区分：
     * 两者都表现为 0 条结论，日志一片安静，故障因此长期不被发现。
     */
    public enum Mode {
        /** 基于仓库索引 + 跨文件符号解析（Java）。能力最完整。 */
        CROSS_FILE,
        /** 基于完整文件但只能看文件内调用（非 Java 语言）。 */
        FILE_LOCAL,
        /** 无索引，且该语言引擎无法容错解析片段——拿不到任何结论。 */
        NO_SOURCE,
        /** 无变更或未启用。 */
        NONE
    }

    /**
     * 基于仓库索引分析影响面（主路径）。
     *
     * @param diffs 代码变更
     * @param index 仓库索引（含全量文件内容与跨文件倒排表）
     * @return 影响面报告
     */
    public ImpactReport analyze(List<CodeDiff> diffs, RepoIndex index) {
        if (diffs == null || diffs.isEmpty()) {
            return new ImpactReport(List.of(), 0, List.of(), Mode.NONE);
        }
        if (index == null || index.units().isEmpty()) {
            return analyzeWithoutIndex(diffs);
        }

        List<ImpactEntry> entries = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        int changedFiles = 0;
        boolean anyCrossFile = false;

        for (CodeDiff d : diffs) {
            if (d == null || d.fileName() == null) continue;
            AnalysisUnit unit = index.unit(d.fileName()).orElse(null);
            if (unit == null || !unit.ok()) {
                unsupported.add(langOf(d));
                continue;
            }
            changedFiles++;

            // 只分析真正落在改动行范围内的方法，而不是文件内所有方法
            List<int[]> ranges = changedLineRanges(d.patch());
            List<AnalysisUnit.MethodDecl> touched = ranges.isEmpty()
                    ? unit.methods()
                    : unit.methods().stream()
                          .filter(m -> ranges.stream().anyMatch(r -> overlaps(r, m)))
                          .toList();

            boolean crossFile = unit.capability() == CodeAnalyzer.Capability.CROSS_FILE;
            anyCrossFile |= crossFile;

            for (AnalysisUnit.MethodDecl m : touched) {
                List<String> callers = new ArrayList<>();
                if (crossFile) {
                    // 跨文件：按全限定签名精确查倒排表
                    for (AnalysisUnit.CallSite cs : index.callersOf(m.signature())) {
                        callers.add(callerLabel(cs));
                    }
                } else {
                    // 文件内：只能统计本文件中指向该方法名的调用点
                    for (AnalysisUnit.CallSite cs : unit.callSites()) {
                        if (cs.calleeName().equals(m.name())) {
                            callers.add(unit.path() + ":" + cs.line());
                        }
                    }
                }
                if (callers.isEmpty()) continue;
                List<String> deduped = new ArrayList<>(new LinkedHashSet<>(callers));
                entries.add(new ImpactEntry(d.fileName(), m.name(), deduped.size(), deduped, crossFile));
            }
        }

        entries.sort(Comparator.comparingInt(ImpactEntry::callerCount).reversed());
        Mode mode = anyCrossFile ? Mode.CROSS_FILE : Mode.FILE_LOCAL;
        return new ImpactReport(entries, changedFiles, unsupported, mode);
    }

    /**
     * 无索引时的降级分析。
     *
     * <p>拿不到完整文件内容，Java 引擎无法工作（JavaParser 要求语法完整的编译单元）。
     * 此时不假装能分析，而是明确返回 {@link Mode#NO_SOURCE}，让调用方知道
     * 「这次没结论是因为缺输入」，而不是「这个改动没有影响面」。
     *
     * @param diffs 代码变更
     * @return 报告（通常无条目，但 mode 说明原因）
     */
    public ImpactReport analyzeWithoutIndex(List<CodeDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return new ImpactReport(List.of(), 0, List.of(), Mode.NONE);
        }
        List<String> langs = new ArrayList<>();
        for (CodeDiff d : diffs) {
            if (d != null) langs.add(langOf(d));
        }
        return new ImpactReport(List.of(), 0, langs, Mode.NO_SOURCE);
    }

    /** 向后兼容入口：无索引，等价于 {@link #analyzeWithoutIndex}。 */
    public ImpactReport analyze(List<CodeDiff> diffs) {
        return analyzeWithoutIndex(diffs);
    }

    /**
     * 生成注入 Agent prompt 的简洁影响面摘要。
     *
     * @param diffs 代码变更
     * @param index 仓库索引
     * @return markdown 摘要；无影响面时返回空串（调用方据此决定是否注入，避免噪声）
     */
    public String summarize(List<CodeDiff> diffs, RepoIndex index) {
        ImpactReport report = analyze(diffs, index);
        return summarize(report);
    }

    /** 向后兼容入口：无索引，通常返回空串。 */
    public String summarize(List<CodeDiff> diffs) {
        return summarize(analyzeWithoutIndex(diffs));
    }

    private String summarize(ImpactReport report) {
        if (report.isEmpty()) {
            return "";
        }
        int topN = Math.min(15, report.entries().size());
        boolean crossFile = report.mode() == Mode.CROSS_FILE;
        StringBuilder sb = new StringBuilder();
        sb.append("本次变更涉及 ").append(report.changedFiles()).append(" 个文件，")
                .append(report.entries().size()).append(" 个被改动方法存在上游调用方")
                .append(crossFile ? "（含跨文件调用）" : "（仅文件内调用）").append("，影响面最广的如下：\n");
        for (int i = 0; i < topN; i++) {
            ImpactEntry e = report.entries().get(i);
            List<String> callers = e.callers();
            String top = callers.size() <= 6 ? String.join(", ", callers)
                    : String.join(", ", callers.subList(0, 6)) + " 等 " + callers.size() + " 个";
            sb.append("- `").append(e.file()).append("` 的 `").append(e.method()).append("()` 被 ")
                    .append(e.callerCount()).append(" 处调用（").append(top).append("）。\n");
        }
        sb.append(crossFile
                ? "跨文件调用方已纳入统计，请重点评估这些方法的回归风险。"
                : "注：当前语言的调用分析仅覆盖文件内，跨文件调用方未能识别。");
        return sb.toString();
    }

    // ============ 内部工具 ============

    /** 调用点的人类可读标签：优先用签名（含类信息），退化时用 文件:行。 */
    private static String callerLabel(AnalysisUnit.CallSite cs) {
        String sig = cs.callerSignature();
        if (sig == null || sig.isBlank()) {
            return "line " + cs.line();
        }
        // 签名形如 com.demo.Controller.use() —— 去掉包名前缀，保留 类.方法
        int hash = sig.indexOf('#');
        String body = hash >= 0 ? sig.substring(hash + 1) : sig;
        int paren = body.indexOf('(');
        String head = paren > 0 ? body.substring(0, paren) : body;
        int dot = head.lastIndexOf('.');
        return dot >= 0 && dot < head.length() - 1 ? head.substring(dot + 1) : head;
    }

    /**
     * 解析 hunk 头，得到新文件侧的改动行范围（1-based，闭区间）。
     *
     * <p>注意 hunk 范围包含上下文行，因此「hunk 覆盖」不等于「该行被修改」，
     * 但作为「改动涉及的方法」的判定依据已足够——真正被改的行必然落在某个 hunk 内。
     */
    static List<int[]> changedLineRanges(String patch) {
        List<int[]> out = new ArrayList<>();
        if (patch == null || patch.isBlank()) {
            return out;
        }
        Matcher m = HUNK.matcher(patch);
        while (m.find()) {
            int start = Integer.parseInt(m.group(1));
            int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            // 新增文件的 hunk 为 @@ -0,0 +1,N @@；count=0 表示纯删除，无新文件行
            if (count <= 0) continue;
            out.add(new int[]{start, start + count - 1});
        }
        return out;
    }

    static boolean overlaps(int[] range, AnalysisUnit.MethodDecl m) {
        return m.startLine() <= range[1] && m.endLine() >= range[0];
    }

    private static String langOf(CodeDiff d) {
        return d.language() == null ? "unknown" : d.language();
    }
}
