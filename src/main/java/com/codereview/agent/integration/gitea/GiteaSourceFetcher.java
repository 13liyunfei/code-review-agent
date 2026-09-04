package com.codereview.agent.integration.gitea;

import com.codereview.agent.core.analysis.index.SourceFetcher;

import java.util.List;
import java.util.Optional;

/**
 * 把 {@link GiteaApiClient} 适配为索引所需的 {@link SourceFetcher}。
 *
 * <p>索引只关心「按路径取内容 / 按目录列条目」，不关心底下是 Gitea 还是别的平台。
 * 这层适配让 {@code RepoIndex} 与 Gitea 解耦：单测可用 Map 打桩，
 * 将来接 GitLab/GitHub 只需再写一个适配器。
 *
 * @param client Gitea API 客户端
 * @param owner  仓库所属用户/组织名
 * @param repo   仓库名
 * @param ref    提交 SHA 或分支名。建议用 PR 的 head SHA——
 *               用分支名会拿到分支最新内容，可能比本次审查的 diff 更新，导致行号对不上
 */
public record GiteaSourceFetcher(GiteaApiClient client, String owner, String repo, String ref)
        implements SourceFetcher {

    @Override
    public Optional<String> fetch(String path) {
        if (client == null) return Optional.empty();
        return Optional.ofNullable(client.fetchFileContent(owner, repo, ref, path));
    }

    @Override
    public List<String> listDir(String dir) {
        if (client == null) return List.of();
        return client.listDirectory(owner, repo, ref, dir);
    }
}
