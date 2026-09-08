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

    /**
     * 断点/历史幂等键：由 PR 身份（仓库 + 编号 + head SHA）稳定派生。
     *
     * <p>同 PR 的多次审查（含崩溃后重试、webhook 重复投递）算出同一个键，才能命中
     * 上次落盘的断点 / 已完成的历史，避免重复审查。含 head SHA：换了新 commit 应
     * 重开审查而非续跑/判重旧结果。headSha 缺失时回落到 {@code repo#id}。
     *
     * <p>该键同时被用作文件名（轨迹/断点存储），因此必须文件系统安全：
     * repo 形如 owner/repo 含 "/"，直接使用会写出非法文件名（表现为轨迹丢失 /
     * 断点落不了盘）。历史实现把此方法放在 Coordinator 内部 private，导致
     * webhook 服务层无法复用来做幂等判重——2026-09-08 上移为公共方法。
     *
     * @param repo    仓库名（owner/repo 形式）
     * @param prId    PR 编号
     * @param headSha head SHA（可为空 → 回落 repo#id）
     * @return 文件系统安全的稳定键
     */
    public static String resumeKey(String repo, long prId, String headSha) {
        String base = (repo == null || repo.isBlank()) ? "?" : repo;
        String key = base + "#" + prId;
        if (headSha != null && !headSha.isBlank()) {
            key = key + "@" + headSha;
        }
        return key.replace('/', '_').replace('\\', '_');
    }

    /** 实例便捷方法：按本 PR 身份算幂等键。 */
    public String resumeKey() {
        return resumeKey(repo, id, headSha);
    }
}
