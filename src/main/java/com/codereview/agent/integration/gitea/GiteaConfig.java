package com.codereview.agent.integration.gitea;

import com.codereview.agent.core.autofix.AutoFixEngine;
import com.codereview.agent.core.coordinator.Coordinator;
import com.codereview.agent.core.history.ReviewHistoryStore;
import com.codereview.agent.core.scheduler.ScheduledScanService;
import com.codereview.agent.core.workflow.ReviewWorkflowEngine;
import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.tenant.TeamResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Gitea 集成层配置。
 *
 * <p>仅在 {@code gitea.enabled=true} 时激活，注册以下 Bean：
 * <ul>
 *   <li>{@link GiteaApiClient} —— Gitea REST API 客户端；</li>
 *   <li>{@link GiteaReviewService} —— 审查编排服务（含自动修复 + 工作流）；</li>
 *   <li>{@link ReviewWorkflowEngine} —— BLOCKER 强制审批 / 工单 / 提交状态；</li>
 *   <li>{@link ScheduledScanService} —— 定时巡检（仅 {@code scan.enabled=true} 时）；</li>
 *   <li>{@link GiteaWebhookController} —— 由 {@code @RestController + @ConditionalOnProperty}
 *       自动注册，无需在此显式声明。</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "gitea.enabled", havingValue = "true")
public class GiteaConfig {

    private static final Logger log = LoggerFactory.getLogger(GiteaConfig.class);

    /**
     * Gitea API 客户端。
     *
     * @param baseUrl  Gitea 实例地址（如 http://localhost:3000）
     * @param apiToken Access Token（需 repo / issue 权限）
     */
    @Bean
    public GiteaApiClient giteaApiClient(
            @Value("${gitea.base-url:http://localhost:3000}") String baseUrl,
            @Value("${gitea.api-token:}") String apiToken) {

        if (apiToken == null || apiToken.isBlank()) {
            log.warn("Gitea api-token 未配置，API 调用将失败");
        }
        log.info("已启用 Gitea 集成（baseUrl={}）", baseUrl);
        return new GiteaApiClient(baseUrl, apiToken);
    }

    /**
     * 人机协作工作流引擎（依赖 Gitea API 创建工单 / 设置提交状态）。
     */
    @Bean
    public ReviewWorkflowEngine reviewWorkflowEngine(GiteaApiClient giteaApiClient) {
        return new ReviewWorkflowEngine(giteaApiClient);
    }

    /**
     * Gitea 审查编排服务。
     *
     * @param giteaApiClient  Gitea API 客户端
     * @param coordinator     多 Agent 协同审查协调者
     * @param autoFixEngine   自动修复引擎（Spring 自动装配）
     * @param workflowEngine  人机协作工作流引擎
     */
    @Bean
    public GiteaReviewService giteaReviewService(GiteaApiClient giteaApiClient,
                                                 Coordinator coordinator,
                                                 AutoFixEngine autoFixEngine,
                                                 ReviewWorkflowEngine workflowEngine,
                                                 TeamResolver teamResolver,
                                                 LlmClient llmClient,
                                                 org.springframework.core.env.Environment env) {
        // 可选增强（默认关闭）：经验反思沉淀 + LLM 应用评估
        var dataDir = java.nio.file.Path.of(env.getProperty("review.data-dir", "./data"));
        var reflection = Boolean.parseBoolean(env.getProperty("review.reflection.enabled", "false"))
                ? new com.codereview.agent.core.memory.ReflectionService(
                        new com.codereview.agent.core.memory.ExperienceStore(null, dataDir), llmClient)
                : null;
        var judge = Boolean.parseBoolean(env.getProperty("review.eval.enabled", "false"))
                ? new com.codereview.agent.core.eval.LlmJudge(llmClient)
                : null;
        return new GiteaReviewService(giteaApiClient, coordinator, autoFixEngine, workflowEngine, teamResolver,
                reflection, judge);
    }

    /**
     * 定时扫描服务（每晚主动巡检目标分支，技术债务跟踪）。
     * 仅当 {@code scan.enabled=true} 时装配。
     */
    @Bean
    @ConditionalOnProperty(name = "scan.enabled", havingValue = "true")
    public ScheduledScanService scheduledScanService(GiteaApiClient giteaApiClient,
                                                     Coordinator coordinator,
                                                     ReviewHistoryStore historyStore,
                                                     TeamResolver teamResolver,
                                                     @Value("${scan.repos:}") String repos) {
        List<ScheduledScanService.RepoTarget> targets = parseRepos(repos);
        log.info("已启用定时扫描，目标仓库数={}（cron 默认 02:00）", targets.size());
        return new ScheduledScanService(giteaApiClient, coordinator, historyStore, targets, true, teamResolver);
    }

    /** 解析 scan.repos：格式 "owner/repo:branch,owner2/repo2"（缺省分支为 main）。 */
    private List<ScheduledScanService.RepoTarget> parseRepos(String repos) {
        List<ScheduledScanService.RepoTarget> r = new ArrayList<>();
        if (repos == null || repos.isBlank()) {
            return r;
        }
        for (String item : repos.split(",")) {
            item = item.trim();
            if (item.isEmpty()) {
                continue;
            }
            String owner;
            String repo;
            String branch = "main";
            String path = item;
            int colon = item.indexOf(':');
            if (colon > 0) {
                path = item.substring(0, colon);
                branch = item.substring(colon + 1);
            }
            int slash = path.indexOf('/');
            if (slash <= 0) {
                log.warn("scan.repos 项格式错误（应为 owner/repo[:branch]）：{}", item);
                continue;
            }
            owner = path.substring(0, slash);
            repo = path.substring(slash + 1);
            r.add(new ScheduledScanService.RepoTarget(owner, repo, branch));
        }
        return r;
    }
}
