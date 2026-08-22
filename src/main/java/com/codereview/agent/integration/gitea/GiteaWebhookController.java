package com.codereview.agent.integration.gitea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.codereview.agent.core.trace.TraceContext;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Gitea Webhook 接收控制器。
 *
 * <p>监听 {@code POST /webhook/gitea}，接收 Gitea Pull Request 事件。
 *
 * <p>处理流程：
 * <ol>
 *   <li>校验 {@code X-Gitea-Signature} 请求头（HMAC-SHA256(body, secret)）与配置密钥是否匹配；</li>
 *   <li>解析 payload，提取 action / PR 序号 / 仓库全名；</li>
 *   <li>仅在 action 为 {@code opened} / {@code reopened} / {@code synchronized} 时触发审查；</li>
 *   <li>异步调用 {@link GiteaReviewService} 执行审查，立即返回 200（避免 Webhook 超时重试）。</li>
 * </ol>
 *
 * <p>仅在 {@code gitea.enabled=true} 时注册此 Controller。
 */
@RestController
@ConditionalOnProperty(name = "gitea.enabled", havingValue = "true")
public class GiteaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GiteaWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GiteaReviewService reviewService;
    private final java.util.concurrent.Executor webhookExecutor;
    private final String webhookSecret;
    private final boolean allowUnsigned;

    /**
     * 构造 Webhook 控制器。
     *
     * @param reviewService 审查编排服务
     * @param webhookSecret Webhook 密钥（用于校验请求来源，空则跳过校验）
     * @param allowUnsigned 是否允许无签名的 Webhook 请求（仅本地 dev 开启，生产务必 false）
     */
    public GiteaWebhookController(GiteaReviewService reviewService,
                                  @org.springframework.beans.factory.annotation.Qualifier("webhookExecutor") java.util.concurrent.Executor webhookExecutor,
                                  @Value("${gitea.webhook-secret:}") String webhookSecret,
                                  @Value("${gitea.webhook-allow-unsigned:false}") boolean allowUnsigned) {
        this.reviewService = reviewService;
        this.webhookExecutor = webhookExecutor;
        this.webhookSecret = webhookSecret;
        this.allowUnsigned = allowUnsigned;
    }

    /**
     * Gitea Pull Request Webhook 入口。
     *
     * @param signature X-Gitea-Signature 请求头（HMAC-SHA256 hex）
     * @param body      请求体（原始 JSON 字符串）
     * @return 200（已接收）/ 401（签名不匹配）/ 400（格式错误）
     */
    @PostMapping("/webhook/gitea")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @RequestHeader(value = "X-Gitea-Signature", required = false) String signature,
            @RequestHeader(value = "X-Team-Id", required = false) String teamHeader,
            @RequestBody String body) {

        // 0. 生成全链路追踪号（入口即生成，贯穿后续所有环节，便于线上问题定位）
        String traceId = TraceContext.newTraceId();
        TraceContext.set(traceId);
        log.info("[Gitea Webhook] 收到请求：bodyLen={}, teamHeader={}, traceId={}",
                body.length(), teamHeader, traceId);

        // 1. 校验 Webhook 签名（HMAC-SHA256）
        if (webhookSecret != null && !webhookSecret.isBlank()) {
            boolean sigMissing = (signature == null || signature.isBlank());
            if (sigMissing) {
                if (allowUnsigned) {
                    // 本地 dev：Gitea webhook 通常未配 secret，放行以便端到端联调。
                    log.warn("[Gitea Webhook] 引擎已配置 webhook-secret，但请求未携带 X-Gitea-Signature 头；"
                            + "dev 开关 webhook-allow-unsigned=true，放行（生产环境必须保持 secret 一致）");
                } else {
                    log.warn("[Gitea Webhook] 签名校验失败：引擎已配置 webhook-secret，但 Gitea 未携带签名头"
                            + "（Gitea webhook 未配 secret）。请在 Gitea webhook 设置里填入与引擎相同的 secret，"
                            + "或将引擎 GITEA_WEBHOOK_SECRET 清空。bodyLen={}", body.length());
                    return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                            .body(Map.of("error", "Invalid webhook signature"));
                }
            } else if (!signature.equalsIgnoreCase(hmacSha256Hex(body, webhookSecret))) {
                log.warn("[Gitea Webhook] 签名校验失败：secret 不一致。expected={}, received={}, bodyLen={}",
                        hmacSha256Hex(body, webhookSecret), signature, body.length());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "Invalid webhook signature"));
            }
        }

        try {
            // 2. 解析 payload（Gitea PR 事件为 GitHub 风格结构）
            JsonNode root = MAPPER.readTree(body);
            String action = root.path("action").asText("");
            long prNum = root.path("number").asLong(0);
            String repoFullName = root.path("repository").path("full_name").asText("");
            String prTitle = root.path("pull_request").path("title").asText("");
            String headSha = root.path("pull_request").path("head").path("sha").asText("");

            if (prNum == 0 || repoFullName.isEmpty() || !repoFullName.contains("/")) {
                log.debug("[Gitea Webhook] 非 PR 事件或字段缺失（action={}），忽略", action);
                return ResponseEntity.ok(Map.of("status", "ignored", "reason", "not a pull_request event"));
            }

            log.info("[Gitea Webhook] 收到 PR 事件：PR #{}（repo={}）, action={}, title=\"{}\"",
                    prNum, repoFullName, action, prTitle);

            // 3. 仅在 opened / reopened / synchronized 时触发审查
            boolean trigger = "opened".equals(action) || "reopened".equals(action)
                    || "synchronized".equals(action);
            if (!trigger) {
                log.debug("[Gitea Webhook] action={} 不触发审查，跳过", action);
                return ResponseEntity.ok(Map.of("status", "skipped", "action", action));
            }

            // 4. 拆分 owner / repo，异步执行审查，立即返回 200（避免 Webhook 超时）
            String owner = repoFullName.substring(0, repoFullName.indexOf('/'));
            String repo = repoFullName.substring(repoFullName.indexOf('/') + 1);

            CompletableFuture.runAsync(TraceContext.wrap(() ->
                    reviewService.reviewPullRequest(owner, repo, prNum, headSha, teamHeader)
            ), webhookExecutor);

            return ResponseEntity.ok(Map.of(
                    "status", "accepted",
                    "pr", String.valueOf(prNum),
                    "repo", repoFullName
            ));

        } catch (Exception e) {
            log.error("[Gitea Webhook] 解析 Webhook payload 异常：{}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Failed to parse webhook payload: " + e.getMessage()));
        }
    }

    /** 计算 HMAC-SHA256 并转为小写 hex（与 Gitea 签名格式一致）。 */
    private static String hmacSha256Hex(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return "";    // 算法不可用（理论上不会发生）
        }
    }
}
