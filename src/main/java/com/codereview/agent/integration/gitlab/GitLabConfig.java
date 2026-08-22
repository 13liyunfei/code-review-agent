package com.codereview.agent.integration.gitlab;

import com.codereview.agent.core.coordinator.Coordinator;
import com.codereview.agent.tenant.TeamResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * GitLab 集成层配置。
 *
 * <p>仅在 {@code gitlab.enabled=true} 时激活，注册以下 Bean：
 * <ul>
 *   <li>{@link GitLabApiClient} —— GitLab REST API 客户端；</li>
 *   <li>{@link GitLabReviewService} —— 审查编排服务（注入 Coordinator）；</li>
 *   <li>{@link GitLabWebhookController} —— 由 {@code @RestController + @ConditionalOnProperty}
 *       自动注册，无需在此显式声明。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "gitlab.enabled", havingValue = "true")
public class GitLabConfig {

    private static final Logger log = LoggerFactory.getLogger(GitLabConfig.class);

    /**
     * GitLab API 客户端。
     *
     * @param baseUrl  GitLab 实例地址
     * @param apiToken Personal Access Token
     */
    @Bean
    public GitLabApiClient gitLabApiClient(
            @Value("${gitlab.base-url:https://gitlab.com}") String baseUrl,
            @Value("${gitlab.api-token:}") String apiToken) {

        if (apiToken == null || apiToken.isBlank()) {
            log.warn("GitLab api-token 未配置，API 调用将失败");
        }
        log.info("已启用 GitLab 集成（baseUrl={}）", baseUrl);
        return new GitLabApiClient(baseUrl, apiToken, Duration.ofSeconds(30));
    }

    /**
     * GitLab 审查编排服务。
     *
     * @param gitLabApiClient GitLab API 客户端
     * @param coordinator     多 Agent 协同审查协调者
     */
    @Bean
    public GitLabReviewService gitLabReviewService(GitLabApiClient gitLabApiClient,
                                                   Coordinator coordinator,
                                                   TeamResolver teamResolver) {
        return new GitLabReviewService(gitLabApiClient, coordinator, teamResolver);
    }
}
