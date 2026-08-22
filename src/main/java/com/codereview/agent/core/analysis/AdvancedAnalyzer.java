package com.codereview.agent.core.analysis;

import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.Severity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

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
 * <p>输出按 Agent 维度拆分为多条 {@link AgentResult}（架构 + 安全），由 {@code Coordinator}
 * 与主流程五 Agent 的结果一并聚合、去重、仲裁。
 */
@Component
public class AdvancedAnalyzer {

    /** 方法长度阈值（行）。 */
    private static final int LONG_METHOD = 60;
    /** 圈复杂度近似阈值（分支数）。 */
    private static final int COMPLEX_METHOD = 10;
    /** 最大嵌套深度阈值。 */
    private static final int DEEP_NESTING = 5;

    /**
     * 对 PR 变更执行高级静态分析。
     *
     * @param diffs 代码变更
     * @return 拆分为架构 / 安全两条 Agent 结果
     */
    public List<AgentResult> analyze(List<CodeDiff> diffs) {
        List<Finding> architecture = new ArrayList<>();
        List<Finding> security = new ArrayList<>();

        // 1. AST + 调用图（仅对 Java 文件）
        for (CodeDiff d : diffs) {
            if (!"java".equals(d.language())) {
                continue;
            }
            String source = reconstruct(d.patch());
            AstAnalyzer.AstReport report = AstAnalyzer.analyze(source, d.fileName());
            CallGraphAnalyzer.CallGraphReport graph =
                    CallGraphAnalyzer.analyze(source, report);

            for (AstAnalyzer.ClassInfo ci : report.classes()) {
                for (AstAnalyzer.MethodInfo m : ci.methods()) {
                    if (m.length() > LONG_METHOD) {
                        architecture.add(structureFinding(d.fileName(), m.startLine(), "STRUCT-LONG-METHOD",
                                "方法体过长",
                                String.format("方法 %s 共 %d 行，超过 %d 行阈值，可读性与可测试性下降。",
                                        m.name(), m.length(), LONG_METHOD),
                                "拆分为更小的职责单一方法。", impactText(graph, m.name())));
                    }
                    if (m.branches() > COMPLEX_METHOD) {
                        architecture.add(structureFinding(d.fileName(), m.startLine(), "STRUCT-COMPLEX",
                                "方法圈复杂度偏高",
                                String.format("方法 %s 分支数约 %d（阈值 %d），出错概率与维护成本上升。",
                                        m.name(), m.branches(), COMPLEX_METHOD),
                                "提取条件分支为独立方法或采用策略模式。", impactText(graph, m.name())));
                    }
                    if (m.maxNesting() > DEEP_NESTING) {
                        architecture.add(structureFinding(d.fileName(), m.startLine(), "STRUCT-NESTING",
                                "嵌套层级过深",
                                String.format("方法 %s 最大嵌套深度 %d（阈值 %d）。",
                                        m.name(), m.maxNesting(), DEEP_NESTING),
                                "提前返回 / 卫语句降低嵌套。", impactText(graph, m.name())));
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

    private static Finding structureFinding(String file, int line, String ruleId,
                                           String title, String desc, String suggestion, String impact) {
        String fullDesc = impact.isBlank() ? desc : desc + " " + impact;
        return new Finding(AgentType.ARCHITECTURE, file, line, line, Severity.MAJOR,
                "architecture", ruleId, title, fullDesc, suggestion, 0.85, "AST");
    }

    private static String impactText(CallGraphAnalyzer.CallGraphReport graph, String method) {
        var up = graph.impact(method);
        if (up.isEmpty()) {
            return "";
        }
        return String.format("影响面：该方法被 %d 个上游方法调用（%s），改动需重点关注回归。",
                up.size(), String.join(", ", up));
    }

    private static Severity toSeverity(String s) {
        return switch (s) {
            case "BLOCKER" -> Severity.BLOCKER;
            case "MAJOR" -> Severity.MAJOR;
            case "MINOR" -> Severity.MINOR;
            default -> Severity.INFO;
        };
    }

    /** 从 unified diff 重建新文件文本（去掉 - 行），供结构分析使用。 */
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
