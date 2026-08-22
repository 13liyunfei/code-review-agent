package com.codereview.agent.integration.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * GitLab Webhook 接收控制器。
 *
 * <p>监听 {@code POST /webhook/gitlab}，接收 GitLab Merge Request Hook 事件。
 *
 * <p>处理流程：
 * <ol>
 *   <li>校验 {@code X-Gitlab-Token} 请求头与配置的 webhook-secret 是否一致；</li>
 *   <li>解析 payload，提取 object_kind / project.id / MR iid / action；</li>
 *   <li>仅在 action 为 {@code open} 或 {@code update} 时触发审查；</li>
 *   <li>异步调用 {@link GitLabReviewService} 执行审查，立即返回 200（避免 GitLab 10s 超时）。</li>
 * </ol>
 *
 * <p>仅在 {@code gitlab.enabled=true} 时注册此 Controller。
 */
@RestController
@ConditionalOnProperty(name = "gitlab.enabled", havingValue = "true")
public class GitLabWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitLabWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GitLabReviewService reviewService;
    private final java.util.concurrent.Executor webhookExecutor;
    private final String webhookSecret;

    /**
     * 构造 Webhook 控制器。
     *
     * @param reviewService  审查编排服务
     * @param webhookSecret  Webhook 密钥（用于校验请求来源）
     */
    public GitLabWebhookController(GitLabReviewService reviewService,
                                   @org.springframework.beans.factory.annotation.Qualifier("webhookExecutor") java.util.concurrent.Executor webhookExecutor,
                                   @Value("${gitlab.webhook-secret:}") String webhookSecret) {
        this.reviewService = reviewService;
        this.webhookExecutor = webhookExecutor;
        this.webhookSecret = webhookSecret;
    }

    /**
     * GitLab Merge Request Webhook 入口。
     *
     * @param token   X-Gitlab-Token 请求头
     * @param body    请求体（原始 JSON 字符串）
     * @return 200（已接收）/ 401（Token 不匹配）/ 400（格式错误）
     */
    @PostMapping("/webhook/gitlab")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
            @RequestBody String body) {

        // 1. 校验 Webhook Token
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            if (token == null || !token.equals(webhookSecret)) {
                log.warn("[Webhook] Token 校验失败，拒绝请求");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid webhook token"));
            }
        }

        try {
            // 2. 解析 payload
            JsonNode root = MAPPER.readTree(body);
            String objectKind = root.path("object_kind").asText("");

            if (!"merge_request".equals(objectKind)) {
                log.debug("[Webhook] 非 merge_request 事件（{}），忽略", objectKind);
                return ResponseEntity.ok(Map.of("status", "ignored", "reason", "not a merge_request event"));
            }

            // 3. 提取关键字段
            JsonNode attrs = root.path("object_attributes");
            long mrIid = attrs.path("iid").asLong(0);
            String action = attrs.path("action").asText("");
            String mrTitle = attrs.path("title").asText("");

            JsonNode project = root.path("project");
            long projectId = project.path("id").asLong(0);
            String projectPath = project.path("path_with_namespace").asText("unknown");

            log.info("[Webhook] 收到 MR 事件：MR !{}（projectId={}, repo={}）, action={}, title=\"{}\"",
                    mrIid, projectId, projectPath, action, mrTitle);

            // 4. 仅在 open / reopen / update 时触发审查
            if (!"open".equals(action) && !"reopen".equals(action) && !"update".equals(action)) {
                log.debug("[Webhook] action={} 不触发审查，跳过", action);
                return ResponseEntity.ok(Map.of("status", "skipped", "action", action));
            }

            if (mrIid == 0 || projectId == 0) {
                log.warn("[Webhook] 无法解析 MR iid 或 projectId，payload 可能格式异常");
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing mrIid or projectId"));
            }

            // 5. 异步执行审查，立即返回 200（避免 GitLab Webhook 10s 超时）
            final long pid = projectId;
            final long iid = mrIid;
            final String path = projectPath;
            CompletableFuture.runAsync(() ->
                    reviewService.reviewMergeRequest(pid, path, iid, teamHeader)
            , webhookExecutor);

            return ResponseEntity.ok(Map.of(
                    "status", "accepted",
                    "mrIid", String.valueOf(iid),
                    "project", path
            ));

        } catch (Exception e) {
            log.error("[Webhook] 解析 Webhook payload 异常：{}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Failed to parse webhook payload: " + e.getMessage()));
        }
    }
}
