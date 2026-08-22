package com.codereview.agent.core.model;

import com.codereview.agent.tenant.Teams;

import java.util.List;

/**
 * 待审查的 Pull Request 封装（触发阶段的输入）。
 *
 * @param id            PR 编号
 * @param repo          仓库名
 * @param title         PR 标题
 * @param author        提交作者
 * @param branch        目标分支
 * @param teamId        所属团队 / 租户标识（用于规则、知识、记忆、历史、反馈的隔离）
 * @param diffs         各文件的代码变更列表
 */
public record PullRequest(
        long id,
        String repo,
        String title,
        String author,
        String branch,
        String teamId,
        List<CodeDiff> diffs) {

    /**
     * 便捷构造：显式指定全部字段，团队回退到默认团队。
     */
    public PullRequest(long id, String repo, String title, String author, String branch, List<CodeDiff> diffs) {
        this(id, repo, title, author, branch, Teams.DEFAULT, diffs);
    }

    /**
     * 根据 PR 信息构建审查共享上下文（透传团队标识）。
     *
     * @return 对应的 {@link ReviewContext}
     */
    public ReviewContext toContext() {
        return new ReviewContext(id, repo, author, branch, teamId);
    }
}
