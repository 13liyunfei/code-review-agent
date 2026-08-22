package com.codereview.agent.core.workflow;

import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.integration.gitea.GiteaApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 人机协作工作流引擎。
 *
 * <p>在自动审查之后编排「人」的介入策略：
 * <ul>
 *   <li><b>BLOCKER 强制审批</b>：存在阻塞级问题时，创建工单（Gitea Issue）追踪，
 *       并将 PR 提交状态置为 failure，从机制上阻止带病合入；</li>
 *   <li><b>严重度分级路由</b>：BLOCKER→人工审批流，MAJOR→建议修复，MINOR/INFO→提示；</li>
 *   <li><b>工单追踪</b>：以 Issue 承载整改闭环，状态可视化。</li>
 * </ul>
 */
public class ReviewWorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(ReviewWorkflowEngine.class);

    private final GiteaApiClient giteaClient;

    public ReviewWorkflowEngine(GiteaApiClient giteaClient) {
        this.giteaClient = giteaClient;
    }

    /**
     * 处理一次审查结果的工作流编排。
     *
     * @param report  审查报告
     * @param owner   仓库所属
     * @param repo    仓库名
     * @param prNum   PR 序号
     * @param headSha PR 头提交 sha（用于设置 commit status）
     * @return 追加到 PR 评论的工作流说明（Markdown）
     */
    public String handle(ReviewReport report, String owner, String repo, long prNum, String headSha) {
        List<Finding> findings = report.getFindings();
        long blockers = findings == null ? 0
                : findings.stream().filter(f -> f.severity().name().equals("BLOCKER")).count();

        StringBuilder note = new StringBuilder();
        note.append("\n## 👥 人机协作工作流\n\n");

        if (blockers > 0) {
            // 1. 强制审批：创建工单 + 失败状态
            String issueTitle = "🚫 BLOCKER 待处理：PR #" + prNum + " 存在 " + blockers + " 个阻塞级问题";
            StringBuilder body = new StringBuilder("本 PR 被自动审查标记为阻塞级，需人工审批后方可合入：\n\n");
            for (Finding f : findings) {
                if (f.severity().name().equals("BLOCKER")) {
                    body.append("- [ ] `").append(f.file()).append("` L").append(f.lineStart())
                            .append(" · ").append(f.title()).append("（").append(f.ruleId()).append("）\n");
                }
            }
            long issueNum = giteaClient.createIssue(owner, repo, issueTitle, body.toString());
            if (headSha != null && !headSha.isBlank()) {
                giteaClient.createCommitStatus(owner, repo, headSha, "failure",
                        "code-review/blocker", "存在 BLOCKER 级问题，禁止合入");
            }
            note.append("🔴 **存在 ").append(blockers).append(" 个 BLOCKER，已进入强制审批流**。");
            if (issueNum > 0) {
                note.append("已创建整改工单 **#").append(issueNum).append("** 跟踪。\n");
            }
        } else {
            if (headSha != null && !headSha.isBlank()) {
                giteaClient.createCommitStatus(owner, repo, headSha, "success",
                        "code-review/blocker", "无 BLOCKER 级问题");
            }
            note.append("🟢 无 BLOCKER 级问题，按常规流程处理。\n");
        }

        // 2. 分级路由说明
        long majors = findings == null ? 0
                : findings.stream().filter(f -> f.severity().name().equals("MAJOR")).count();
        note.append("\n**分级路由**：BLOCKER → 人工审批流；MAJOR(").append(majors)
                .append(") → 建议修复；MINOR/INFO → 提示参考。\n");
        return note.toString();
    }
}
