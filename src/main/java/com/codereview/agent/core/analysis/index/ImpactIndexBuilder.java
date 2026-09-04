package com.codereview.agent.core.analysis.index;

import com.codereview.agent.core.model.PullRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把一次 PR 变成可供影响面分析使用的 {@link RepoIndex}。
 *
 * <h2>存在的理由</h2>
 * {@link RepoIndex#build} 需要四样东西：源码获取器、变更列表、扫描范围、引擎路由。
 * 其中三项在启动时就确定，只有「源码获取器」要等拿到 PR 才知道仓库坐标与 head SHA。
 * 本类就是把「固定配置」与「每次请求才有的坐标」拼在一起的地方，
 * 顺带统一处理降级：拿不到坐标、拉取失败、解析异常，一律退化为 {@link RepoIndex#empty()}，
 * 由 {@code ImpactAnalyzer} 回落到 {@code Mode.NO_SOURCE}，而不是把异常抛进审查主链路。
 *
 * <p>影响面分析是增强项，不是主链路——它挂了最多让 prompt 少一段上下文，
 * 绝不能让整个 PR 审查失败。
 */
public class ImpactIndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(ImpactIndexBuilder.class);

    private final RepoSourceLocator locator;
    private final AnalysisEngines engines;
    private final IndexScope scope;

    public ImpactIndexBuilder(RepoSourceLocator locator, AnalysisEngines engines, IndexScope scope) {
        this.locator = locator;
        this.engines = engines == null ? AnalysisEngines.defaults() : engines;
        this.scope = scope == null ? IndexScope.DEFAULT : scope;
    }

    /**
     * 构建本次 PR 的仓库索引。
     *
     * @param pr 待审查的 PR（需要 {@code owner/repo} 与 head SHA）
     * @return 索引；任何前置条件不满足或构建异常时返回 {@link RepoIndex#empty()}
     */
    public RepoIndex build(PullRequest pr) {
        if (locator == null || pr == null) {
            return RepoIndex.empty();
        }
        String owner = pr.owner();
        String repo = pr.repoName();
        String ref = pr.headSha();
        if (isBlank(owner) || isBlank(repo) || isBlank(ref)) {
            log.debug("[ImpactIndex] 缺少仓库坐标或 head SHA（repo={}, headSha={}），跳过索引构建",
                    pr.repo(), ref);
            return RepoIndex.empty();
        }
        try {
            RepoIndex index = RepoIndex.build(locator.locate(owner, repo, ref), pr.diffs(), scope, engines);
            log.info("[ImpactIndex] PR#{} 索引完成：拉取 {} 文件，分析 {}，失败 {}，跨文件能力={}, 触顶={}",
                    pr.id(), index.stats().fetched(), index.stats().analyzed(),
                    index.stats().failed(), index.crossFileCapable(), index.stats().truncated());
            return index;
        } catch (Exception e) {
            // 索引失败不应阻断审查：影响面片段缺失只影响 prompt 丰富度
            log.warn("[ImpactIndex] 索引构建失败，影响面分析降级：{}", e.getMessage());
            return RepoIndex.empty();
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
