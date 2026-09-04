package com.codereview.agent.core.model;

import com.codereview.agent.tenant.Teams;

import java.util.List;

/**
 * 待审查的 Pull Request 封装（触发阶段的输入）。
 *
 * @param id            PR 编号
 * @param repo          仓库名（{@code owner/repo} 形式）
 * @param title         PR 标题
 * @param author        提交作者
 * @param branch        目标分支
 * @param teamId        所属团队 / 租户标识（用于规则、知识、记忆、历史、反馈的隔离）
 * @param diffs         各文件的代码变更列表
 * @param headSha       PR 头提交 SHA。影响面分析据此拉取「本次 diff 对应时刻」的完整文件内容；
 *                      缺失时影响面分析降级为不产出结论（而不是拿分支最新内容导致行号错位）
 */
public record PullRequest(
        long id,
        String repo,
        String title,
        String author,
        String branch,
        String teamId,
        List<CodeDiff> diffs,
        String headSha) {

    /** 便捷构造：显式指定全部字段，团队回退到默认团队，无 head SHA（影响面分析降级）。 */
    public PullRequest(long id, String repo, String title, String author, String branch, List<CodeDiff> diffs) {
        this(id, repo, title, author, branch, Teams.DEFAULT, diffs, null);
    }

    /**
     * 携带团队标识，无 head SHA。
     *
     * <p>保留此重载是为了不打断既有调用方；但生产入口（Gitea/GitLab 服务）
     * 应改用完整构造把 head SHA 传进来，否则影响面分析拿不到源码。
     */
    public PullRequest(long id, String repo, String title, String author, String branch,
                       String teamId, List<CodeDiff> diffs) {
        this(id, repo, title, author, branch, teamId, diffs, null);
    }

    /**
     * 根据 PR 信息构建审查共享上下文（透传团队标识）。
     *
     * @return 对应的 {@link ReviewContext}
     */
    public ReviewContext toContext() {
        return new ReviewContext(id, repo, author, branch, teamId);
    }

    /**
     * 从 {@code owner/repo} 中取出 owner；无斜杠时返回空串。
     *
     * <p>不另设字段存 owner：仓库坐标目前以合并字符串贯穿全链路（历史、轨迹、团队映射都用它），
     * 拆成两个字段要改的地方远多于收益。这里只做解析，调用方按空串判定「坐标不可用」。
     */
    public String owner() {
        int i = repo == null ? -1 : repo.indexOf('/');
        return i > 0 ? repo.substring(0, i) : "";
    }

    /** 从 {@code owner/repo} 中取出 repo 名；无斜杠时返回原串。 */
    public String repoName() {
        if (repo == null) return "";
        int i = repo.indexOf('/');
        return i >= 0 && i < repo.length() - 1 ? repo.substring(i + 1) : repo;
    }
}
