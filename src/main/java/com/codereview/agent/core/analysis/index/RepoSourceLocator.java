package com.codereview.agent.core.analysis.index;

/**
 * 按仓库坐标产出 {@link SourceFetcher} 的定位器。
 *
 * <h2>为什么要多一层</h2>
 * {@link SourceFetcher} 是「已绑定到某个仓库 + 某个 ref」的实例，
 * 而协调者手里的 {@code PullRequest} 只是带着 {@code owner/repo} 与 head SHA 的数据。
 * 核心层若直接 new Gitea 适配器，就把 core 反向依赖到了 integration——
 * 因此这里只声明「给我仓库坐标，我还你一个取源码的」，由集成层提供实现。
 *
 * <p>顺带的好处：单测里用 Map 打桩即可，不需要起 Gitea。
 */
public interface RepoSourceLocator {

    /**
     * 定位到指定仓库 / 引用的源码获取器。
     *
     * @param owner 仓库所属用户或组织名
     * @param repo  仓库名
     * @param ref   提交 SHA 或分支名（建议 head SHA）
     * @return 源码获取器；拿不到时返回 null 或一个恒返回空的实例均可，调用方会降级
     */
    SourceFetcher locate(String owner, String repo, String ref);
}
