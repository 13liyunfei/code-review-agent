package com.codereview.agent.integration.gitlab;

import com.codereview.agent.core.coordinator.Coordinator;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.tenant.TeamResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * GitLab MR 审查编排服务。
 *
 * <p>串联完整审查链路：
 * <pre>
 *   Webhook 触发
 *     → GitLabApiClient.fetchMrChanges（拉取 MR diff）
 *     → 转 PullRequest 模型（按 group/project 解析团队，实现租户隔离）
 *     → Coordinator.review（5 Agent 并行审查 + 聚合仲裁）
 *     → GitLabApiClient.postMrNote（回写审查报告到 MR 评论）
 * </pre>
 *
 * <p>异常隔离设计：任一步骤失败不抛异常到上层，仅记录日志，
 * 避免单次 Webhook 处理失败影响服务稳定性。
 */
public class GitLabReviewService {

    private static final Logger log = LoggerFactory.getLogger(GitLabReviewService.class);

    private final GitLabApiClient gitLabClient;
    private final Coordinator coordinator;
    private final TeamResolver teamResolver;

    /**
     * 构造审查编排服务。
     *
     * @param gitLabClient  GitLab API 客户端
     * @param coordinator   多 Agent 协同审查协调者
     * @param teamResolver  团队解析器（group/project → 团队）
     */
    public GitLabReviewService(GitLabApiClient gitLabClient, Coordinator coordinator,
                              TeamResolver teamResolver) {
        this.gitLabClient = gitLabClient;
        this.coordinator = coordinator;
        this.teamResolver = teamResolver;
    }

    /**
     * 对一次 GitLab Merge Request 发起自动审查（团队按 group/project 自动解析）。
     *
     * @param projectId    GitLab 项目 ID（数字）
     * @param projectPath  项目路径（如 {@code group/project}，用于报告展示与团队解析）
     * @param mrIid        MR IID（项目内序号）
     */
    public void reviewMergeRequest(long projectId, String projectPath, long mrIid) {
        reviewMergeRequest(projectId, projectPath, mrIid, null);
    }

    /**
     * 对一次 GitLab Merge Request 发起自动审查（支持显式团队覆盖）。
     *
     * @param projectId     GitLab 项目 ID（数字）
     * @param projectPath   项目路径（如 {@code group/project}）
     * @param mrIid         MR IID（项目内序号）
     * @param teamOverride  显式团队覆盖（可空，由 group/project 映射决定）
     */
    public void reviewMergeRequest(long projectId, String projectPath, long mrIid, String teamOverride) {
        String owner = projectPath.contains("/") ? projectPath.substring(0, projectPath.indexOf('/')) : projectPath;
        String repo = projectPath.contains("/") ? projectPath.substring(projectPath.indexOf('/') + 1) : projectPath;
        String teamId = teamResolver.resolve(owner, repo, teamOverride);
        log.info("[GitLab审查] 开始处理 MR !{}（projectId={}, repo={}，团队={}）", mrIid, projectId, projectPath, teamId);

        // 1. 从 GitLab 拉取 MR 变更
        GitLabApiClient.MrChanges mr = gitLabClient.fetchMrChanges(projectId, mrIid);
        if (mr == null || mr.diffs().isEmpty()) {
            log.warn("[GitLab审查] MR !{} 无法获取变更或变更为空，跳过", mrIid);
            postSkipNote(projectId, mrIid, "无法获取 MR 变更内容（可能无文件变更或权限不足）。");
            return;
        }

        // 2. 转为内部 PullRequest 模型（携带团队标识）
        PullRequest pr = new PullRequest(
                mrIid,
                projectPath,
                mr.title(),
                mr.author(),
                mr.targetBranch(),
                teamId,
                mr.diffs()
        );

        // 3. 多 Agent 协同审查
        ReviewReport report;
        try {
            report = coordinator.review(pr);
        } catch (Exception e) {
            log.error("[GitLab审查] Coordinator 审查异常 MR !{}：{}", mrIid, e.getMessage(), e);
            postSkipNote(projectId, mrIid, "审查引擎异常：" + e.getMessage());
            return;
        }

        // 4. 回写审查报告到 GitLab MR 评论
        String markdown = buildReviewComment(report, mr.sourceBranch(), mr.targetBranch());
        gitLabClient.postMrNote(projectId, mrIid, markdown);

        log.info("[GitLab审查] MR !{} 审查完成：发现问题 {} 条",
                mrIid, report.getFindings().size());
    }

    /**
     * 构建回写到 GitLab MR 的 Markdown 评论。
     *
     * <p>在 {@link ReviewReport#toMarkdown()} 基础上补充分支信息与 Agent 引擎标识。
     */
    private String buildReviewComment(ReviewReport report, String sourceBranch, String targetBranch) {
        StringBuilder sb = new StringBuilder();
        sb.append(report.toMarkdown());
        sb.append("\n---\n");
        sb.append("> 🤖 本报告由多 Agent 协同代码审查系统自动生成（");
        sb.append("逻辑 / 安全 / 性能 / 风格 / 架构 五 Agent 并行审查 + 混元 LLM）\n");
        sb.append("> 源分支 `").append(sourceBranch).append("` → 目标分支 `").append(targetBranch).append("`\n");
        return sb.toString();
    }

    /**
     * 当审查无法正常执行时，在 MR 上发布一条跳过说明。
     */
    private void postSkipNote(long projectId, long mrIid, String reason) {
        String body = "⚠️ **代码审查被跳过**\n\n" + reason
                + "\n\n> 如需手动触发审查，请检查系统配置或联系管理员。";
        gitLabClient.postMrNote(projectId, mrIid, body);
    }
}
