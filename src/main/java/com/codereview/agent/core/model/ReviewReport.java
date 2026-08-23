package com.codereview.agent.core.model;

import com.codereview.agent.core.report.VerificationResult;

import java.util.List;
import java.util.Map;

/**
 * 最终的结构化审查报告（Coordinator 聚合后产出）。
 *
 * <p>报告按严重级别分组统计，并提供 Markdown 渲染方法，可直接提交到
 * GitHub / GitLab / Gitea PR 评论区作为审查结论。新版在原有基础上增加：
 * <ul>
 *   <li>{@code runId} / {@code reviewTimeMs}：调用链追踪与耗时监控；</li>
 *   <li>{@code overriddenFindings} / {@code arbitrationNotes}：优先级冲突仲裁结果；</li>
 *   <li>{@code suppressedFindings}：依据开发者反馈抑制的误报；</li>
 *   <li>{@code verification}：修复后复检的解决 / 未解决对比。</li>
 * </ul>
 */
public class ReviewReport {

    private final long prId;
    private final String repo;
    private final List<Finding> findings;
    private final Map<Severity, Long> severityCount;

    /** 本次审查运行 ID（调用链追踪）。 */
    private final String runId;
    /** 整体审查耗时（毫秒）。 */
    private final long reviewTimeMs;
    /** 冲突仲裁说明列表。 */
    private final List<String> arbitrationNotes;
    /** 冲突仲裁中被覆盖（落败）的发现。 */
    private final List<Finding> overriddenFindings;
    /** 依据开发者反馈被抑制的误报。 */
    private final List<Finding> suppressedFindings;
    /** 修复后复检的验证结果（首次审查为 none）。 */
    private final VerificationResult verification;
    /** 本次审查展开的业务方自定义 Agent 名称（无则空列表，用于报告如实体现「五 Agent + 自定义」）。 */
    private final List<String> customAgents;

    /**
     * 完整构造（聚合阶段使用）。
     */
    public ReviewReport(long prId, String repo, List<Finding> findings, Map<Severity, Long> severityCount,
                        String runId, long reviewTimeMs, List<String> arbitrationNotes,
                        List<Finding> overriddenFindings, List<Finding> suppressedFindings,
                        VerificationResult verification) {
        this(prId, repo, findings, severityCount, runId, reviewTimeMs, arbitrationNotes,
                overriddenFindings, suppressedFindings, verification, List.of());
    }

    /**
     * 含自定义 Agent 的完整构造（聚合阶段使用）。
     *
     * @param customAgents 本次展开的业务方自定义 Agent 名称列表（可为空）
     */
    public ReviewReport(long prId, String repo, List<Finding> findings, Map<Severity, Long> severityCount,
                        String runId, long reviewTimeMs, List<String> arbitrationNotes,
                        List<Finding> overriddenFindings, List<Finding> suppressedFindings,
                        VerificationResult verification, List<String> customAgents) {
        this.prId = prId;
        this.repo = repo;
        this.findings = findings;
        this.severityCount = severityCount;
        this.runId = runId;
        this.reviewTimeMs = reviewTimeMs;
        this.arbitrationNotes = arbitrationNotes;
        this.overriddenFindings = overriddenFindings;
        this.suppressedFindings = suppressedFindings;
        this.verification = verification;
        this.customAgents = customAgents == null ? List.of() : customAgents;
    }

    /**
     * 向后兼容构造（不含新字段，默认空）。
     */
    public ReviewReport(long prId, String repo, List<Finding> findings, Map<Severity, Long> severityCount) {
        this(prId, repo, findings, severityCount, "", 0L,
                List.of(), List.of(), List.of(), VerificationResult.none());
    }

    /** 返回附带复检结果的新报告（记录不可变，采用拷贝式更新）。 */
    public ReviewReport withVerification(VerificationResult verification) {
        return new ReviewReport(prId, repo, findings, severityCount, runId, reviewTimeMs,
                arbitrationNotes, overriddenFindings, suppressedFindings, verification, customAgents);
    }

    /**
     * 返回替换发现列表并重算分级统计的新报告（供 Profile 过滤等后处理使用）。
     *
     * @param newFindings 后处理后的发现列表
     * @return 新报告（分级统计随之重算）
     */
    public ReviewReport withFindings(List<Finding> newFindings) {
        return new ReviewReport(prId, repo, newFindings, countBySeverity(newFindings),
                runId, reviewTimeMs, arbitrationNotes, overriddenFindings, suppressedFindings, verification, customAgents);
    }

    /**
     * 返回同时替换「发现 / 被抑制误报 / 被仲裁覆盖」三份列表的新报告（供权限收敛等后处理使用）。
     *
     * @param newFindings     最终发现
     * @param newSuppressed   抑制后的误报
     * @param newOverridden   仲裁覆盖（落败）列表
     * @return 新报告
     */
    public ReviewReport withPostProcessing(List<Finding> newFindings,
                                           List<Finding> newSuppressed,
                                           List<Finding> newOverridden) {
        return new ReviewReport(prId, repo, newFindings, countBySeverity(newFindings),
                runId, reviewTimeMs, arbitrationNotes, newOverridden, newSuppressed, verification, customAgents);
    }

    /**
     * 返回附带自定义 Agent 名称的新报告（用于报告如实体现业务方自定义 Agent 参与）。
     *
     * @param customAgents 本次展开的自定义 Agent 名称列表
     * @return 新报告
     */
    public ReviewReport withCustomAgents(List<String> customAgents) {
        return new ReviewReport(prId, repo, findings, severityCount, runId, reviewTimeMs,
                arbitrationNotes, overriddenFindings, suppressedFindings, verification, customAgents);
    }

    /** 按严重级别统计。 */
    private static Map<Severity, Long> countBySeverity(List<Finding> findings) {
        return findings.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Finding::severity, java.util.stream.Collectors.counting()));
    }

    public long getPrId() {
        return prId;
    }

    public String getRepo() {
        return repo;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public Map<Severity, Long> getSeverityCount() {
        return severityCount;
    }

    public String getRunId() {
        return runId;
    }

    public long getReviewTimeMs() {
        return reviewTimeMs;
    }

    public List<String> getArbitrationNotes() {
        return arbitrationNotes;
    }

    public List<Finding> getOverriddenFindings() {
        return overriddenFindings;
    }

    public List<Finding> getSuppressedFindings() {
        return suppressedFindings;
    }

    public VerificationResult getVerification() {
        return verification;
    }

    public List<String> getCustomAgents() {
        return customAgents;
    }

    /**
     * 渲染为 Markdown 文本，便于直接发布到 PR 评论。
     *
     * <p>文案经 {@code i18n/messages*.properties} 国际化，语言由 {@code review.lang=zh|en} 控制
     * （见 {@link com.codereview.agent.core.i18n.ReviewMessages}）。
     *
     * @return Markdown 格式报告
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 🤖 ").append(msg("report.title")).append("\n\n");
        sb.append("- **").append(msg("report.repo")).append("**：").append(repo).append('\n');
        sb.append("- **").append(msg("report.pr")).append("**：#").append(prId).append('\n');
        if (runId != null && !runId.isBlank()) {
            sb.append("- **").append(msg("report.runId")).append("**：`").append(runId).append("`\n");
        }
        if (customAgents != null && !customAgents.isEmpty()) {
            sb.append("- **").append(msg("report.customAgents")).append("**：")
                    .append(String.join(" / ", customAgents)).append('\n');
        }
        sb.append("- **").append(msg("report.totalFindings")).append("**：").append(findings.size()).append('\n');
        if (reviewTimeMs > 0) {
            sb.append("- **").append(msg("report.duration")).append("**：").append(reviewTimeMs).append(" ms\n");
        }
        sb.append("- **").append(msg("report.severityStats")).append("**：")
                .append("🔴 ").append(msg("report.severity.blocker")).append(" ").append(severityCount.getOrDefault(Severity.BLOCKER, 0L))
                .append("，🟠 ").append(msg("report.severity.major")).append(" ").append(severityCount.getOrDefault(Severity.MAJOR, 0L))
                .append("，🟡 ").append(msg("report.severity.minor")).append(" ").append(severityCount.getOrDefault(Severity.MINOR, 0L))
                .append("，🔵 ").append(msg("report.severity.info")).append(" ").append(severityCount.getOrDefault(Severity.INFO, 0L))
                .append("\n\n");

        // 复检验证区块
        if (verification != null && verification.reCheck()) {
            sb.append("## 🔁 ").append(msg("report.recheck.title")).append("\n\n");
            sb.append("- ✅ ").append(msg("report.recheck.resolved")).append("：").append(verification.resolvedCount()).append('\n');
            sb.append("- ⏳ ").append(msg("report.recheck.unresolved")).append("：").append(verification.unresolvedCount()).append('\n');
            sb.append("- 🆕 ").append(msg("report.recheck.introduced")).append("：").append(verification.introducedCount()).append("\n\n");
            if (!verification.resolvedItems().isEmpty()) {
                sb.append("**").append(msg("report.recheck.resolvedItems")).append("**：\n");
                verification.resolvedItems().forEach(i -> sb.append("- ").append(i).append('\n'));
                sb.append('\n');
            }
            if (!verification.unresolvedItems().isEmpty()) {
                sb.append("**").append(msg("report.recheck.unresolvedItems")).append("**：\n");
                verification.unresolvedItems().forEach(i -> sb.append("- ").append(i).append('\n'));
                sb.append('\n');
            }
            if (!verification.introducedItems().isEmpty()) {
                sb.append("**").append(msg("report.recheck.introducedItems")).append("**：\n");
                verification.introducedItems().forEach(i -> sb.append("- ").append(i).append('\n'));
                sb.append('\n');
            }
        }

        // 冲突仲裁区块
        if (overriddenFindings != null && !overriddenFindings.isEmpty()) {
            sb.append("## ⚖️ ").append(msg("report.arbitration.title", overriddenFindings.size()))
                    .append("\n\n");
            if (arbitrationNotes != null) {
                arbitrationNotes.forEach(n -> sb.append("- ").append(n).append('\n'));
            }
            sb.append("\n");
        }

        // 误报抑制区块
        if (suppressedFindings != null && !suppressedFindings.isEmpty()) {
            sb.append("## 🚫 ").append(msg("report.suppressed.title", suppressedFindings.size()))
                    .append("\n\n");
            for (Finding f : suppressedFindings) {
                sb.append("- [").append(f.ruleId()).append("] ").append(f.title())
                        .append("（").append(f.file());
                if (f.lineStart() > 0) {
                    sb.append(":L").append(f.lineStart());
                }
                sb.append("，").append(msg("report.source")).append(" ").append(agentName(f.agentType())).append("）\n");
            }
            sb.append("\n");
        }

        // 各严重级别详情
        for (Severity severity : Severity.values()) {
            List<Finding> group = findings.stream()
                    .filter(f -> f.severity() == severity)
                    .toList();
            if (group.isEmpty()) {
                continue;
            }
            sb.append("## ").append(severityName(severity)).append("（").append(group.size()).append("）\n\n");
            for (Finding f : group) {
                sb.append("### [").append(f.ruleId()).append("] ").append(f.title()).append('\n');
                sb.append("- **").append(msg("report.file")).append("**：`").append(f.file()).append("`")
                        .append(" (L").append(f.lineStart());
                if (f.lineEnd() != f.lineStart()) {
                    sb.append("-").append(f.lineEnd());
                }
                sb.append(")\n");
                sb.append("- **").append(msg("report.reviewer")).append("**：").append(agentName(f.agentType()))
                        .append("（").append(msg("report.source")).append(" ").append(f.source()).append("，")
                        .append(msg("report.confidence")).append(" ")
                        .append(String.format("%.2f", f.confidence())).append("）\n");
                sb.append("- **").append(msg("report.description")).append("**：").append(f.description()).append('\n');
                sb.append("- **").append(msg("report.suggestion")).append("**：").append(f.suggestion()).append("\n\n");
            }
        }
        return sb.toString();
    }

    /** 本地化 Agent 显示名（英文枚举名 → 按语言解析）。 */
    private static String agentName(AgentType agentType) {
        return msg("agent.type." + agentType.name());
    }

    /** 本地化严重级别显示名。 */
    private static String severityName(Severity severity) {
        return switch (severity) {
            case BLOCKER -> msg("severity.BLOCKER");
            case MAJOR -> msg("severity.MAJOR");
            case MINOR -> msg("severity.MINOR");
            case INFO -> msg("severity.INFO");
        };
    }

    private static String msg(String key, Object... args) {
        return com.codereview.agent.core.i18n.ReviewMessages.get(key, args);
    }
}
