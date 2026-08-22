package com.codereview.agent.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 团队（租户）解析器。
 *
 * <p>根据 Git 仓库的 {@code owner}（组织）与 {@code repo} 解析出团队标识：
 * <ol>
 *   <li>优先精确匹配 {@code owner/repo}；</li>
 *   <li>其次匹配 {@code owner}（整个组织）；</li>
 *   <li>均未命中则回退到默认团队。</li>
 * </ol>
 *
 * <p>若调用方显式传入 {@code override}（如控制台通过 {@code X-Team-Id} 请求头指定），
 * 则以覆盖值为准（经净化后生效）。
 */
public class TeamResolver {

    private static final Logger log = LoggerFactory.getLogger(TeamResolver.class);

    private final Map<String, String> mapping;
    private final String defaultTeam;

    public TeamResolver(Map<String, String> mapping, String defaultTeam) {
        this.mapping = mapping == null ? Map.of() : mapping;
        this.defaultTeam = Teams.sanitize(defaultTeam);
    }

    /**
     * 解析团队（无覆盖）。
     *
     * @param owner 仓库所属组织 / 用户
     * @param repo  仓库名
     * @return 团队标识（不会为 null 或空）
     */
    public String resolve(String owner, String repo) {
        String key = owner + "/" + repo;
        String team = mapping.get(key);
        if (team != null) {
            return Teams.sanitize(team);
        }
        team = mapping.get(owner);
        if (team != null) {
            return Teams.sanitize(team);
        }
        if (log.isDebugEnabled()) {
            log.debug("[TeamResolver] 仓库 {}/{} 未命中映射，回退到默认团队 {}", owner, repo, defaultTeam);
        }
        return defaultTeam;
    }

    /**
     * 解析团队（支持显式覆盖）。
     *
     * @param owner    仓库所属组织 / 用户
     * @param repo     仓库名
     * @param override 显式团队覆盖（可空）
     * @return 团队标识
     */
    public String resolve(String owner, String repo, String override) {
        if (override != null && !override.isBlank()) {
            return Teams.sanitize(override);
        }
        return resolve(owner, repo);
    }
}
