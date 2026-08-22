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

    /**
     * 完整构造（聚合阶段使用）。
     */
    public ReviewReport(long prId, String repo, List<Finding> findings, Map<Severity, Long> severityCount,
                        String runId, long reviewTimeMs, List<String> arbitrationNotes,
                        List<Finding> overriddenFindings, List<Finding> suppressedFindings,
                        VerificationResult verification) {
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
                arbitrationNotes, overriddenFindings, suppressedFindings, verification);
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

    /**
     * 渲染为 Markdown 文本，便于直接发布到 PR 评论。
     *
     * @return Markdown 格式报告
     */
    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 🤖 多 Agent 协同代码审查报告\n\n");
        sb.append("- **仓库**：").append(repo).append('\n');
        sb.append("- **PR**：#").append(prId).append('\n');
        if (runId != null && !runId.isBlank()) {
            sb.append("- **运行 ID**：`").append(runId).append("`\n");
        }
        sb.append("- **发现问题总数**：").append(findings.size()).append('\n');
        if (reviewTimeMs > 0) {
            sb.append("- **审查耗时**：").append(reviewTimeMs).append(" ms\n");
        }
        sb.append("- **分级统计**：")
                .append("🔴 Blocker ").append(severityCount.getOrDefault(Severity.BLOCKER, 0L))
                .append("，🟠 Major ").append(severityCount.getOrDefault(Severity.MAJOR, 0L))
                .append("，🟡 Minor ").append(severityCount.getOrDefault(Severity.MINOR, 0L))
                .append("，🔵 Info ").append(severityCount.getOrDefault(Severity.INFO, 0L))
                .append("\n\n");

        // 复检验证区块
        if (verification != null && verification.reCheck()) {
            sb.append("## 🔁 修复复检（与上次审查对比）\n\n");
            sb.append("- ✅ 已解决：").append(verification.resolvedCount()).append('\n');
            sb.append("- ⏳ 未解决：").append(verification.unresolvedCount()).append('\n');
            sb.append("- 🆕 新引入：").append(verification.introducedCount()).append("\n\n");
            if (!verification.resolvedItems().isEmpty()) {
                sb.append("**已解决项**：\n");
                verification.resolvedItems().forEach(i -> sb.append("- ").append(i).append('\n'));
                sb.append('\n');
            }
            if (!verification.unresolvedItems().isEmpty()) {
                sb.append("**未解决项**：\n");
                verification.unresolvedItems().forEach(i -> sb.append("- ").append(i).append('\n'));
                sb.append('\n');
            }
            if (!verification.introducedItems().isEmpty()) {
                sb.append("**新引入项**：\n");
                verification.introducedItems().forEach(i -> sb.append("- ").append(i).append('\n'));
                sb.append('\n');
            }
        }

        // 冲突仲裁区块
        if (overriddenFindings != null && !overriddenFindings.isEmpty()) {
            sb.append("## ⚖️ 冲突仲裁（").append(overriddenFindings.size())
                    .append(" 项按优先级裁决）\n\n");
            if (arbitrationNotes != null) {
                arbitrationNotes.forEach(n -> sb.append("- ").append(n).append('\n'));
            }
            sb.append("\n");
        }

        // 误报抑制区块
        if (suppressedFindings != null && !suppressedFindings.isEmpty()) {
            sb.append("## 🚫 已抑制误报（").append(suppressedFindings.size())
                    .append(" 项，依据开发者反馈）\n\n");
            for (Finding f : suppressedFindings) {
                sb.append("- [").append(f.ruleId()).append("] ").append(f.title())
                        .append("（").append(f.file());
                if (f.lineStart() > 0) {
                    sb.append(":L").append(f.lineStart());
                }
                sb.append("，来源 ").append(f.agentType().getDisplayName()).append("）\n");
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
            sb.append("## ").append(severity).append(" 级问题（").append(group.size()).append("）\n\n");
            for (Finding f : group) {
                sb.append("### [").append(f.ruleId()).append("] ").append(f.title()).append('\n');
                sb.append("- **文件**：`").append(f.file()).append("`")
                        .append(" (L").append(f.lineStart());
                if (f.lineEnd() != f.lineStart()) {
                    sb.append("-").append(f.lineEnd());
                }
                sb.append(")\n");
                sb.append("- **审查方**：").append(f.agentType().getDisplayName())
                        .append("（来源 ").append(f.source()).append("，置信度 ")
                        .append(String.format("%.2f", f.confidence())).append("）\n");
                sb.append("- **描述**：").append(f.description()).append('\n');
                sb.append("- **建议**：").append(f.suggestion()).append("\n\n");
            }
        }
        return sb.toString();
    }
}
