package com.codereview.agent.core.analysis;

import com.codereview.agent.core.analysis.index.AnalysisEngines;
import com.codereview.agent.core.analysis.index.IndexScope;
import com.codereview.agent.core.analysis.index.RepoIndex;
import com.codereview.agent.core.analysis.index.RepoSourceLocator;
import com.codereview.agent.core.analysis.spi.AnalysisUnit;
import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 高级静态分析聚合器（企业级能力落地）。
 *
 * <p>将三项结构级分析统一接入审查管线：
 * <ul>
 *   <li>{@link AstAnalyzer} —— 方法长度 / 圈复杂度 / 嵌套深度（结构语义）；</li>
 *   <li>{@link CallGraphAnalyzer} —— 改动影响面（谁调用了该方法）；</li>
 *   <li>{@link ScaScanner} —— 依赖 CVE / 许可证 / SBOM。</li>
 * </ul>
 *
 * <h2>同源病修复（与影响面分析同一个坑）</h2>
 * 旧实现用 {@link #reconstruct} 从 diff 片段重建源码再喂给 {@link AstAnalyzer}。
 * 真实 PR 的 diff 只含改动处 ±3 行上下文，方法体被截断，{@code AstAnalyzer} 解析出的方法
 * 行号/分支/嵌套全错，结果就是 STRUCT-LONG-METHOD / STRUCT-COMPLEX / STRUCT-NESTING
 * 在真实 PR 上<b>静默产出 0 条结论</b>——测试里看得到，因为测试用「新增文件全量 patch」替生产满足了前提。
 *
 * <p>治本：当能拿到仓库坐标（owner/repo + head SHA）与源码定位器时，走 {@link RepoIndex}
 * 拉取被改文件的<b>完整源码</b>喂给 {@link AstAnalyzer}，并只评估 hunk 实际触及的方法
 * （坐标对齐影响面分析的做法，避免把整文件历史长方法都翻出来刷屏）；
 * 影响面文案优先用索引的<b>跨文件</b>调用方，回落到文件内调用图。
 * 拿不到坐标（如单测）时回落旧路径，行为不变。
 *
 * <p>输出按 Agent 维度拆分为多条 {@link AgentResult}（架构 + 安全），由 {@code Coordinator}
 * 与主流程五 Agent 的结果一并聚合、去重、仲裁。
 */
@Component
public class AdvancedAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AdvancedAnalyzer.class);

    /** 方法长度阈值（行）。 */
    private static final int LONG_METHOD = 60;
    /** 圈复杂度近似阈值（分支数）。 */
    private static final int COMPLEX_METHOD = 10;
    /** 最大嵌套深度阈值。 */
    private static final int DEEP_NESTING = 5;

    /** hunk 头：{@code @@ -a,b +c,d @@} → 取新文件侧起始行与行数。 */
    private static final Pattern HUNK = Pattern.compile(
            "^@@[^@]*\\+(\\d+)(?:,(\\d+))?\\s@@", Pattern.MULTILINE);

    private final RepoSourceLocator locator;
    private final AnalysisEngines engines;
    private final IndexScope scope;

    /** 生产装配：注入源码定位器，开启「完整源码」模式。 */
    @Autowired
    public AdvancedAnalyzer(RepoSourceLocator locator) {
        this(locator, AnalysisEngines.defaults(), IndexScope.DEFAULT);
    }

    /** 便于测试（无定位器 → 回落 diff 片段模式）。 */
    public AdvancedAnalyzer() {
        this(null);
    }

    private AdvancedAnalyzer(RepoSourceLocator locator, AnalysisEngines engines, IndexScope scope) {
        this.locator = locator;
        this.engines = engines == null ? AnalysisEngines.defaults() : engines;
        this.scope = scope == null ? IndexScope.DEFAULT : scope;
    }

    /**
     * 对 PR 变更执行高级静态分析（完整签名：可拉取完整源码）。
     *
     * @param pr    待审查 PR（需 owner/repo/headSha 才能拉完整源码）
     * @param diffs 代码变更
     * @return 拆分为架构 / 安全两条 Agent 结果
     */
    public List<AgentResult> analyze(PullRequest pr, List<CodeDiff> diffs) {
        List<Finding> architecture = new ArrayList<>();
        List<Finding> security = new ArrayList<>();

        // 1. AST + 调用图（仅对 Java 文件）
        final RepoIndex index = buildIndex(pr, diffs);
        try (index) {
            // 跨文件调用方：callee 方法名 → 调用方签名（用于 STRUCT 影响面文案）
            Map<String, List<String>> crossCallers = buildCrossCallers(index);

            for (CodeDiff d : diffs) {
                if (!"java".equals(d.language())) {
                    continue;
                }
                // 完整源码优先；拿不到就回落 diff 片段重建（坐标对不齐，只能不收敛范围）
                Optional<String> full = index.source(d.fileName());
                boolean fullSource = full.isPresent();
                String source = fullSource ? full.get() : reconstruct(d.patch());
                AstAnalyzer.AstReport report = AstAnalyzer.analyze(source, d.fileName());
                CallGraphAnalyzer.CallGraphReport graph = CallGraphAnalyzer.analyze(source, report);

                // 仅全量源码模式下按 hunk 收敛到「实际改动的方法」，避免整文件历史长方法刷屏
                List<int[]> ranges = fullSource ? changedLineRanges(d.patch()) : List.of();

                for (AstAnalyzer.ClassInfo ci : report.classes()) {
                    for (AstAnalyzer.MethodInfo m : ci.methods()) {
                        if (fullSource && !ranges.isEmpty() && !overlapsAny(ranges, m)) {
                            continue;
                        }
                        if (m.length() > LONG_METHOD) {
                            architecture.add(structureFinding(d.fileName(), m.startLine(), "STRUCT-LONG-METHOD",
                                    "方法体过长",
                                    String.format("方法 %s 共 %d 行，超过 %d 行阈值，可读性与可测试性下降。",
                                            m.name(), m.length(), LONG_METHOD),
                                    "拆分为更小的职责单一方法。", impactText(m.name(), crossCallers, graph)));
                        }
                        if (m.branches() > COMPLEX_METHOD) {
                            architecture.add(structureFinding(d.fileName(), m.startLine(), "STRUCT-COMPLEX",
                                    "方法圈复杂度偏高",
                                    String.format("方法 %s 分支数约 %d（阈值 %d），出错概率与维护成本上升。",
                                            m.name(), m.branches(), COMPLEX_METHOD),
                                    "提取条件分支为独立方法或采用策略模式。", impactText(m.name(), crossCallers, graph)));
                        }
                        if (m.maxNesting() > DEEP_NESTING) {
                            architecture.add(structureFinding(d.fileName(), m.startLine(), "STRUCT-NESTING",
                                    "嵌套层级过深",
                                    String.format("方法 %s 最大嵌套深度 %d（阈值 %d）。",
                                            m.name(), m.maxNesting(), DEEP_NESTING),
                                    "提前返回 / 卫语句降低嵌套。", impactText(m.name(), crossCallers, graph)));
                        }
                    }
                }
            }
        }

        // 2. SCA 依赖扫描
        ScaScanner.ScaReport sca = ScaScanner.analyze(diffs);
        for (ScaScanner.Vulnerability v : sca.vulnerabilities()) {
            security.add(new Finding(AgentType.SECURITY, v.component().name() + "@" + v.component().version(),
                    0, 0, toSeverity(v.severity()), "security", "SCA-" + v.cve(),
                    "依赖存在已知漏洞 " + v.cve(), v.description(),
                    "升级到已修复版本或移除该依赖。", 0.9, "SCA"));
        }
        for (String lic : sca.licenseIssues()) {
            architecture.add(new Finding(AgentType.ARCHITECTURE, "pom.xml", 0, 0, Severity.MINOR,
                    "architecture", "SCA-LICENSE", "许可证合规风险", lic,
                    "确认许可证兼容性，必要时寻求法务/替代依赖。", 0.8, "SCA"));
        }

        return List.of(
                new AgentResult(0L, AgentType.ARCHITECTURE, architecture),
                new AgentResult(0L, AgentType.SECURITY, security)
        );
    }

    /** 兼容入口：无 PR 坐标时回落 diff 片段模式（与旧行为一致）。 */
    public List<AgentResult> analyze(List<CodeDiff> diffs) {
        return analyze(null, diffs);
    }

    private static boolean hasCoords(PullRequest pr) {
        return pr.owner() != null && !pr.owner().isBlank()
                && pr.repoName() != null && !pr.repoName().isBlank()
                && pr.headSha() != null && !pr.headSha().isBlank();
    }

    /** 构建源码索引；任何异常一律回落空索引（增强项不能拖垮审查主链路）。 */
    private RepoIndex buildIndex(PullRequest pr, List<CodeDiff> diffs) {
        if (pr == null || locator == null || !hasCoords(pr)) {
            return RepoIndex.empty();
        }
        try {
            RepoIndex idx = RepoIndex.build(locator.locate(pr.owner(), pr.repoName(), pr.headSha()),
                    diffs, scope, engines);
            log.info("[Advanced] 已构建源码索引：文件 {}，跨文件能力={}",
                    idx.stats().fetched(), idx.crossFileCapable());
            return idx;
        } catch (Exception e) {
            log.warn("[Advanced] 源码索引构建失败，回落 diff 片段模式：{}", e.getMessage());
            return RepoIndex.empty();
        }
    }

    /** 从索引倒排构建「被调方法名 → 调用方签名」映射（跨文件感知）。 */
    private static Map<String, List<String>> buildCrossCallers(RepoIndex index) {
        Map<String, List<String>> map = new HashMap<>();
        if (index == null || !index.crossFileCapable()) {
            return map;
        }
        for (AnalysisUnit u : index.units().values()) {
            for (AnalysisUnit.CallSite cs : u.callSites()) {
                if (cs.calleeSignature() == null || cs.callerSignature() == null) {
                    continue;
                }
                map.computeIfAbsent(calleeName(cs.calleeSignature()), k -> new ArrayList<>())
                        .add(cs.callerSignature());
            }
        }
        return map;
    }

    /** 从全限定签名取方法名（去参数）。 */
    private static String calleeName(String signature) {
        int dot = signature.lastIndexOf('.');
        String name = dot >= 0 ? signature.substring(dot + 1) : signature;
        int par = name.indexOf('(');
        return par >= 0 ? name.substring(0, par) : name;
    }

    private static String impactText(String method, Map<String, List<String>> cross,
                                    CallGraphAnalyzer.CallGraphReport graph) {
        List<String> up = cross.getOrDefault(method, List.of());
        if (up.isEmpty()) {
            Set<String> inFile = graph.impact(method);
            if (inFile.isEmpty()) {
                return "";
            }
            return String.format("影响面：该方法被 %d 个上游方法调用（%s），改动需重点关注回归。",
                    inFile.size(), String.join(", ", inFile));
        }
        return String.format("影响面：该方法被 %d 个上游方法调用（%s），改动需重点关注回归。",
                up.size(), String.join(", ", up));
    }

    // ===================== hunk 行号收敛（与影响面分析同口径） =====================

    /** 解析 diff 中所有 hunk 的新文件侧行范围 [start, end]。 */
    private static List<int[]> changedLineRanges(String patch) {
        List<int[]> ranges = new ArrayList<>();
        if (patch == null) {
            return ranges;
        }
        Matcher m = HUNK.matcher(patch);
        while (m.find()) {
            int start = Integer.parseInt(m.group(1));
            int count = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
            // 行数 0 视为至少覆盖 1 行，避免空集把方法全过滤掉
            int end = start + Math.max(count, 1) - 1;
            ranges.add(new int[]{start, end});
        }
        return ranges;
    }

    private static boolean overlapsAny(List<int[]> ranges, AstAnalyzer.MethodInfo m) {
        for (int[] r : ranges) {
            if (m.startLine() <= r[1] && m.endLine() >= r[0]) {
                return true;
            }
        }
        return false;
    }

    private static Finding structureFinding(String file, int line, String ruleId,
                                           String title, String desc, String suggestion, String impact) {
        String fullDesc = impact.isBlank() ? desc : desc + " " + impact;
        return new Finding(AgentType.ARCHITECTURE, file, line, line, Severity.MAJOR,
                "architecture", ruleId, title, fullDesc, suggestion, 0.85, "AST");
    }

    private static Severity toSeverity(String s) {
        return switch (s) {
            case "BLOCKER" -> Severity.BLOCKER;
            case "MAJOR" -> Severity.MAJOR;
            case "MINOR" -> Severity.MINOR;
            default -> Severity.INFO;
        };
    }

    /** 从 unified diff 重建新文件文本（去掉 - 行），供结构分析使用（回落路径）。 */
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
