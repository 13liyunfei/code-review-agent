package com.codereview.agent.integration.gitea;

import com.codereview.agent.core.autofix.AutoFixEngine;
import com.codereview.agent.core.coordinator.Coordinator;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.workflow.ReviewWorkflowEngine;
import com.codereview.agent.core.trace.TraceContext;
import com.codereview.agent.tenant.TeamResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Gitea PR 审查编排服务。
 *
 * <p>串联完整审查链路：
 * <pre>
 *   Webhook 触发
 *     → GiteaApiClient.fetchPrChanges（拉取 PR diff）
 *     → 转 PullRequest 模型（按 owner/repo 解析团队，实现租户隔离）
 *     → Coordinator.review（5 Agent 并行 + 高级静态分析 + 聚合仲裁）
 *     → AutoFixEngine（生成自动修复建议）
 *     → ReviewWorkflowEngine（BLOCKER 强制审批 / 工单 / 提交状态）
     *     → GiteaApiClient.postPrComment（回写审查报告到 PR 顶层评论）
     *     → GiteaApiClient.postReviewComments（批量发布行内评论，使 Apply 按钮出现在代码旁）
 * </pre>
 *
 * <p>异常隔离设计：任一步骤失败不抛异常到上层，仅记录日志，
 * 避免单次 Webhook 处理失败影响服务稳定性。
 */
public class GiteaReviewService {

    private static final Logger log = LoggerFactory.getLogger(GiteaReviewService.class);

    private final GiteaApiClient giteaClient;
    private final Coordinator coordinator;
    private final AutoFixEngine autoFixEngine;
    private final ReviewWorkflowEngine workflowEngine;
    private final TeamResolver teamResolver;

    /**
     * 构造审查编排服务。
     *
     * @param giteaClient     Gitea API 客户端
     * @param coordinator     多 Agent 协同审查协调者
     * @param autoFixEngine   自动修复引擎
     * @param workflowEngine  人机协作工作流引擎
     * @param teamResolver    团队解析器（owner/repo → 团队）
     */
    public GiteaReviewService(GiteaApiClient giteaClient, Coordinator coordinator,
                             AutoFixEngine autoFixEngine, ReviewWorkflowEngine workflowEngine,
                             TeamResolver teamResolver) {
        this.giteaClient = giteaClient;
        this.coordinator = coordinator;
        this.autoFixEngine = autoFixEngine;
        this.workflowEngine = workflowEngine;
        this.teamResolver = teamResolver;
    }

    /**
     * 对一次 Gitea Pull Request 发起自动审查（团队按 owner/repo 自动解析）。
     *
     * @param owner   仓库所属用户/组织名
     * @param repo    仓库名
     * @param prNum   PR 序号
     * @param headSha PR 头提交 sha（用于设置 commit status）
     */
    public void reviewPullRequest(String owner, String repo, long prNum, String headSha) {
        reviewPullRequest(owner, repo, prNum, headSha, null);
    }

    /**
     * 对一次 Gitea Pull Request 发起自动审查（支持显式团队覆盖）。
     *
     * @param owner       仓库所属用户/组织名
     * @param repo        仓库名
     * @param prNum       PR 序号
     * @param headSha     PR 头提交 sha
     * @param teamOverride 显式团队覆盖（可空，由 owner/repo 映射决定）
     */
    public void reviewPullRequest(String owner, String repo, long prNum, String headSha,
                                  String teamOverride) {
        // 确保全链路追踪号存在（Webhook 已设置，此处兜底，保证 Demo/其他入口也能被追踪）
        String traceId = TraceContext.ensure();
        long startTotal = System.currentTimeMillis();
        String teamId = teamResolver.resolve(owner, repo, teamOverride);
        log.info("[Gitea审查] 开始处理 PR #{}（{}/{}，团队={}，traceId={}）", prNum, owner, repo, teamId, traceId);

        // 1. 从 Gitea 拉取 PR 变更
        long t0 = System.currentTimeMillis();
        GiteaApiClient.PrChanges pr = giteaClient.fetchPrChanges(owner, repo, prNum);
        if (pr == null || pr.diffs().isEmpty()) {
            log.warn("[Gitea审查] PR #{} 无法获取变更或变更为空，跳过（耗时 {}ms）",
                    prNum, System.currentTimeMillis() - t0);
            postSkipNote(owner, repo, prNum, "无法获取 PR 变更内容（可能无文件变更或权限不足）。");
            return;
        }
        log.info("[Gitea审查] 步骤[拉取PR变更] 完成：{} 文件，耗时 {}ms", pr.diffs().size(), System.currentTimeMillis() - t0);

        // 2. 转为内部 PullRequest 模型（携带团队标识）
        PullRequest pullRequest = new PullRequest(
                prNum,
                owner + "/" + repo,
                pr.title(),
                pr.author(),
                pr.targetBranch(),
                teamId,
                pr.diffs()
        );

        // 3. 多 Agent 协同审查（含高级静态分析）
        ReviewReport report;
        try {
            long t1 = System.currentTimeMillis();
            report = coordinator.review(pullRequest);
            log.info("[Gitea审查] 步骤[多Agent协同审查] 完成：{} 条发现，耗时 {}ms",
                    report.getFindings().size(), System.currentTimeMillis() - t1);
        } catch (Exception e) {
            log.error("[Gitea审查] Coordinator 审查异常 PR #{}：{}", prNum, e.getMessage(), e);
            postSkipNote(owner, repo, prNum, "审查引擎异常：" + e.getMessage());
            return;
        }

        // 4. 自动修复建议 + 人机协作工作流
        long t2 = System.currentTimeMillis();
        String autoFix = autoFixEngine.generateSuggestions(report);
        String workflow = workflowEngine.handle(report, owner, repo, prNum, headSha);
        log.info("[Gitea审查] 步骤[自动修复+工作流] 完成，耗时 {}ms", System.currentTimeMillis() - t2);

        // 5. 回写审查报告到 Gitea PR 顶层评论（含自动修复建议概览）
        long t3 = System.currentTimeMillis();
        String markdown = buildReviewComment(report, pr.sourceBranch(), pr.targetBranch(), autoFix, workflow);
        giteaClient.postPrComment(owner, repo, prNum, markdown);
        log.info("[Gitea审查] 步骤[回写顶层评论] 完成，耗时 {}ms", System.currentTimeMillis() - t3);

        // 6. 批量发布「行内评论」，让 Gitea 在代码旁显示「应用建议」按钮
        //    Gitea 1.27 不支持逐条直发行内评论，需在创建 review 时通过 comments 数组一次性提交
        long t4 = System.currentTimeMillis();
        List<AutoFixEngine.FixItem> fixes = autoFixEngine.generateFixItems(report);
        List<GiteaApiClient.ReviewCommentItem> items = new ArrayList<>();
        for (AutoFixEngine.FixItem item : fixes) {
            if (item.line() <= 0) {
                continue; // 无法锚定到具体行，仅保留顶层概览
            }
            String block = "### " + item.label() + "\n\n```suggestion\n" + item.snippet() + "\n```";
            items.add(new GiteaApiClient.ReviewCommentItem(item.file(), item.line(), block));
        }
        int applied = giteaClient.postReviewComments(owner, repo, prNum, headSha, items);
        log.info("[Gitea审查] 步骤[发布行内评论] 完成：已发布 {} 条（共 {} 条可修复），耗时 {}ms",
                applied, fixes.size(), System.currentTimeMillis() - t4);

        log.info("[Gitea审查] PR #{} 审查完成：发现问题 {} 条，行内修复建议已发布 {} 条（共 {} 条可修复），总耗时 {}ms",
                prNum, report.getFindings().size(), applied, fixes.size(),
                System.currentTimeMillis() - startTotal);
    }

    /**
     * 构建回写到 Gitea PR 的 Markdown 评论。
     *
     * <p>在 {@link ReviewReport#toMarkdown()} 基础上补充自动修复、工作流与分支信息。
     */
    private String buildReviewComment(ReviewReport report, String sourceBranch, String targetBranch,
                                     String autoFix, String workflow) {
        StringBuilder sb = new StringBuilder();
        sb.append(report.toMarkdown());
        if (autoFix != null && !autoFix.isBlank()) {
            sb.append(autoFix);
        }
        if (workflow != null && !workflow.isBlank()) {
            sb.append(workflow);
        }
        sb.append("\n---\n");
        sb.append("> 🤖 本报告由多 Agent 协同代码审查系统自动生成（");
        sb.append("逻辑 / 安全 / 性能 / 风格 / 架构 五 Agent + AST/调用图/SCA 高级分析 + 混元 LLM）\n");
        sb.append("> 源分支 `").append(sourceBranch).append("` → 目标分支 `").append(targetBranch).append("`\n");
        return sb.toString();
    }

    /**
     * 当审查无法正常执行时，在 PR 上发布一条跳过说明。
     */
    private void postSkipNote(String owner, String repo, long prNum, String reason) {
        String body = "⚠️ **代码审查被跳过**\n\n" + reason
                + "\n\n> 如需手动触发审查，请检查系统配置或联系管理员。";
        giteaClient.postPrComment(owner, repo, prNum, body);
    }
}
