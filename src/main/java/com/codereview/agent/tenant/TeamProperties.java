package com.codereview.agent.tenant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 团队（租户）映射配置。
 *
 * <p>通过 {@code review.teams.mapping} 将 Git 仓库的 owner（组织）或 owner/repo
 * 映射到团队标识；未匹配者回退到 {@code review.teams.default}（默认 {@code default}）。
 *
 * <p>示例（application.yml）：
 * <pre>
 * review:
 *   teams:
 *     default: default
 *     mapping:
 *       "acme": team-acme          # 整个组织 acme 归 team-acme
 *       "acme/payments": team-acme-pay   # 更具体的仓库优先
 *       "opensource/tool-x": team-oss
 * </pre>
 */
@ConfigurationProperties(prefix = "review.teams")
public class TeamProperties {

    /** 未匹配时的默认团队。 */
    private String defaultTeam = Teams.DEFAULT;

    /**
     * 仓库 → 团队映射。键支持两种形式：
     * <ul>
     *   <li>{@code owner}（仅组织名）—— 该组织下所有仓库归属同一团队；</li>
     *   <li>{@code owner/repo} —— 精确到单个仓库，优先级高于仅 {@code owner} 的映射。</li>
     * </ul>
     */
    private Map<String, String> mapping = new LinkedHashMap<>();

    public String getDefaultTeam() {
        return defaultTeam;
    }

    public void setDefaultTeam(String defaultTeam) {
        this.defaultTeam = defaultTeam;
    }

    public Map<String, String> getMapping() {
        return mapping;
    }

    public void setMapping(Map<String, String> mapping) {
        this.mapping = mapping;
    }
}
