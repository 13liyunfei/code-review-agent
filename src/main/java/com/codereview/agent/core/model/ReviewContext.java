package com.codereview.agent.core.model;

import com.codereview.agent.tenant.Teams;

/**
 * 一次审查运行的共享上下文（单 PR 内所有 Agent 共享的短期记忆）。
 *
 * @param prId          Pull Request 标识
 * @param repo          仓库名（如 org/backend-service）
 * @param author        作者（如 @alice）
 * @param branch        目标分支
 * @param changedFiles  变更文件数量
 * @param branch        目标分支
 * @param teamId        所属团队 / 租户标识（用于规则、知识、记忆、历史、反馈的隔离）
 * @param impactSummary 变更影响面摘要（由 {@code ImpactAnalyzer} 计算，注入 Agent 提示词；可为空）
 */
public record ReviewContext(
        long prId,
        String repo,
        String author,
        int changedFiles,
        String branch,
        String teamId,
        String impactSummary) {

    /**
     * 便捷构造：仅提供核心标识，团队回退到默认团队。
     *
     * @param prId   PR 标识
     * @param repo   仓库名
     * @param author 作者
     */
    public ReviewContext(long prId, String repo, String author) {
        this(prId, repo, author, 0, "main", Teams.DEFAULT, "");
    }

    /**
     * 便捷构造：显式指定分支与团队。
     */
    public ReviewContext(long prId, String repo, String author, String branch, String teamId) {
        this(prId, repo, author, 0, branch, teamId, "");
    }

    /**
     * 拷贝式更新：返回附带影响面摘要的新上下文（记录不可变，采用拷贝式更新）。
     *
     * @param impactSummary 影响面摘要
     * @return 新上下文
     */
    public ReviewContext withImpactSummary(String impactSummary) {
        return new ReviewContext(prId, repo, author, changedFiles, branch, teamId,
                impactSummary == null ? "" : impactSummary);
    }
}
