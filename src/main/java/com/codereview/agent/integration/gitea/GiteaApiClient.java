package com.codereview.agent.integration.gitea;

import com.codereview.agent.core.model.CodeDiff;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Gitea REST API v1 客户端。
 *
 * <p>基于 JDK {@link HttpClient}，通过 Access Token（{@code Authorization: token xxx}）鉴权，
 * 提供以下能力：
 * <ul>
 *   <li>{@link #fetchPrChanges} —— 拉取 PR 变更（标题 / 作者 / 分支 / 各文件 diff，支持分页）；</li>
 *   <li>{@link #postPrComment} —— 在 PR 上发布评论（审查报告回写）。</li>
 * </ul>
 *
 * <p>调用失败时记录日志并返回 null / false，上层 {@link GiteaReviewService}
 * 会据此降级处理，与系统「4 级降级链」理念一致。
 */
public class GiteaApiClient {

    private static final Logger log = LoggerFactory.getLogger(GiteaApiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String apiBase;        // e.g. http://localhost:3000/api/v1
    private final String apiToken;

    /**
     * 构造 Gitea API 客户端。
     *
     * @param baseUrl Gitea 实例地址（如 http://localhost:3000）
     * @param apiToken Access Token（需 repo / issue 权限）
     */
    public GiteaApiClient(String baseUrl, String apiToken) {
        this.apiBase = stripTrailingSlash(baseUrl) + "/api/v1";
        this.apiToken = apiToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("[Gitea API] 初始化 apiBase={}", this.apiBase);
    }

    // ===================== 核心 API =====================

    /**
     * 拉取 PR 变更信息（含各文件 diff）。
     *
     * <p>组合两个接口：
     * <ul>
     *   <li>{@code GET /repos/:owner/:repo/pulls/:index} —— PR 元数据（标题/作者/分支）；</li>
     *   <li>{@code GET /repos/:owner/:repo/pulls/:index.diff} —— 完整 unified diff 文本
     *       （files 接口在新版 Gitea 中不返回 patch 字段，故改用此路由自行解析）。</li>
     * </ul>
     *
     * @param owner 仓库所属用户/组织名
     * @param repo  仓库名
     * @param prNum PR 序号（项目内）
     * @return 解析后的 PR 变更信息；失败返回 null
     */
    public PrChanges fetchPrChanges(String owner, String repo, long prNum) {
        try {
            // 1. PR 元数据
            JsonNode pr = getJson("/repos/" + owner + "/" + repo + "/pulls/" + prNum);
            if (pr == null) {
                return null;
            }
            String title = pr.path("title").asText("(无标题)");
            String author = pr.path("user").path("login").asText("unknown");
            String sourceBranch = pr.path("head").path("ref").asText("");
            String targetBranch = pr.path("base").path("ref").asText("main");

            // 2. 拉取完整 unified diff 文本并按文件切分
            String rawDiff = getText("/repos/" + owner + "/" + repo + "/pulls/" + prNum + ".diff");
            List<CodeDiff> diffs = parseUnifiedDiff(rawDiff);

            log.info("[Gitea API] PR #{} 共 {} 个文件变更（title=\"{}\", {} → {}）",
                    prNum, diffs.size(), title, sourceBranch, targetBranch);

            return new PrChanges(title, author, sourceBranch, targetBranch, diffs);

        } catch (Exception e) {
            log.error("[Gitea API] 获取 PR 变更异常 {}/{}#{}：{}", owner, repo, prNum, e.getMessage());
            return null;
        }
    }

    /**
     * 在 PR 上发布评论。
     *
     * <p>调用 {@code POST /repos/:owner/:repo/issues/:index/comments}
     * （Gitea 中 PR 复用 Issue 的评论体系），请求体为 {@code {"body": "<markdown>"}}。
     *
     * @param owner  仓库所属用户/组织名
     * @param repo   仓库名
     * @param prNum  PR 序号
     * @param body   评论内容（支持 Markdown）
     * @return true=发布成功
     */
    public boolean postPrComment(String owner, String repo, long prNum, String body) {
        String url = apiBase + "/repos/" + owner + "/" + repo + "/issues/" + prNum + "/comments";
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("body", body);
            byte[] bodyBytes = MAPPER.writeValueAsBytes(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "token " + apiToken)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.error("[Gitea API] 发布评论失败 status={} body={}",
                        response.statusCode(), truncate(response.body(), 500));
                return false;
            }

            log.info("[Gitea API] 评论已发布到 PR #{}（{}字符）", prNum, body.length());
            return true;

        } catch (Exception e) {
            log.error("[Gitea API] 发布评论异常 {}/{}#{}：{}", owner, repo, prNum, e.getMessage());
            return false;
        }
    }

    /**
     * 在 PR 的代码评审中批量发布「行内评论」（锚定到具体文件 + 行）。
     *
     * <p><b>重要（Gitea 1.27 变更）</b>：Gitea 1.27 已移除独立的
     * {@code POST /repos/:owner/:repo/pulls/:index/comments} 与
     * {@code POST /pulls/:index/reviews/:id/comments}（后者仅保留 GET 列表），
     * 行内评论只能在「创建评审（review）」时通过 {@code comments} 数组一次性写入。
     * 故本方法调用 {@code POST /repos/:owner/:repo/pulls/:index/reviews}，
     * 以 {@code event=COMMENT} 一次性提交评审 + 所有行内评论。
     *
     * <p>行内评论会渲染 {@code ```suggestion} 块并显示「应用建议」(Apply) 按钮，
     * 便于开发者一键采纳修复。
     *
     * @param owner  仓库所属用户/组织名
     * @param repo   仓库名
     * @param prNum  PR 序号
     * @param sha    头提交 sha（commit_id，决定评论锚定的代码版本）
     * @param items  行内评论列表（文件 + 行 + 内容，line 必须落在 diff 右侧 RIGHT 行内）
     * @return 成功发布的条数（0 表示全部失败）
     */
    public int postReviewComments(String owner, String repo, long prNum, String sha,
                                  List<ReviewCommentItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        String url = apiBase + "/repos/" + owner + "/" + repo + "/pulls/" + prNum + "/reviews";
        try {
            ArrayNode comments = MAPPER.createArrayNode();
            for (ReviewCommentItem it : items) {
                ObjectNode c = MAPPER.createObjectNode();
                c.put("body", it.body());
                c.put("commit_id", sha);
                c.put("path", it.path());
                c.put("line", it.line());
                c.put("side", "RIGHT");
                comments.add(c);
            }
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("commit_id", sha);
            payload.put("body", "🤖 由多 Agent 协同代码审查系统自动提交的评审建议");
            payload.put("event", "COMMENT");
            payload.set("comments", comments);

            byte[] bodyBytes = MAPPER.writeValueAsBytes(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "token " + apiToken)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.error("[Gitea API] 批量发布行内评论失败 status={} body={}",
                        response.statusCode(), truncate(response.body(), 500));
                return 0;
            }

            log.info("[Gitea API] 已通过 review 批量发布 {} 条行内评论到 PR #{}", items.size(), prNum);
            return items.size();

        } catch (Exception e) {
            log.error("[Gitea API] 批量发布行内评论异常 {}/{}#{}：{}", owner, repo, prNum, e.getMessage());
            return 0;
        }
    }

    // ===================== 扩展 API（定时扫描 / 工作流） =====================

    /**
     * 拉取目标分支最新提交对应的 diff（用于定时巡检）。
     *
     * @param owner  仓库所属
     * @param repo   仓库名
     * @param branch 分支（如 main）
     * @return 该分支最新提交的 unified diff 文本；失败返回 null
     */
    public String fetchLatestCommitDiff(String owner, String repo, String branch) {
        try {
            JsonNode commits = getJson("/repos/" + owner + "/" + repo + "/commits?sha="
                    + branch + "&limit=1");
            if (commits == null || !commits.isArray() || commits.isEmpty()) {
                return null;
            }
            String sha = commits.get(0).path("sha").asText("");
            return getText("/repos/" + owner + "/" + repo + "/commits/" + sha + ".diff");
        } catch (Exception e) {
            log.error("[Gitea API] 获取最新提交 diff 异常 {}/{}@{}：{}", owner, repo, branch, e.getMessage());
            return null;
        }
    }

    /**
     * 创建 Issue（用于工作流工单追踪 / 定时扫描技术债务归档）。
     *
     * @param owner 仓库所属
     * @param repo  仓库名
     * @param title 标题
     * @param body  正文
     * @return Issue 序号（>0 成功，<=0 失败）
     */
    public long createIssue(String owner, String repo, String title, String body) {
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("title", title);
            payload.put("body", body);
            byte[] bytes = MAPPER.writeValueAsBytes(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/repos/" + owner + "/" + repo + "/issues"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "token " + apiToken)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.error("[Gitea API] 创建 Issue 失败 status={} body={}",
                        resp.statusCode(), truncate(resp.body(), 300));
                return -1;
            }
            JsonNode node = MAPPER.readTree(resp.body());
            return node.path("number").asLong(0);
        } catch (Exception e) {
            log.error("[Gitea API] 创建 Issue 异常 {}/{}：{}", owner, repo, e.getMessage());
            return -1;
        }
    }

    /**
     * 设置提交状态（用于工作流「禁止带病合入」）。
     *
     * @param owner       仓库所属
     * @param repo        仓库名
     * @param sha         提交 sha
     * @param state       success / failure / pending
     * @param context     状态上下文标识
     * @param description 描述
     * @return true=成功
     */
    public boolean createCommitStatus(String owner, String repo, String sha, String state,
                                      String context, String description) {
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("state", state);
            payload.put("context", context);
            payload.put("description", description);
            byte[] bytes = MAPPER.writeValueAsBytes(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBase + "/repos/" + owner + "/" + repo + "/statuses/" + sha))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "token " + apiToken)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.error("[Gitea API] 设置提交状态失败 status={} body={}",
                        resp.statusCode(), truncate(resp.body(), 300));
                return false;
            }
            log.info("[Gitea API] 已设置提交状态 {}/{}@{} -> {}", owner, repo, sha, state);
            return true;
        } catch (Exception e) {
            log.error("[Gitea API] 设置提交状态异常：{}", e.getMessage());
            return false;
        }
    }

    // ===================== 内部工具 =====================

    /** 发送 GET 请求并解析 JSON，失败返回 null。 */
    private JsonNode getJson(String path) throws Exception {
        String body = getText(path);
        return body == null ? null : MAPPER.readTree(body);
    }

    /** 发送 GET 请求返回原始文本，失败返回 null。 */
    private String getText(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "token " + apiToken)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            log.error("[Gitea API] GET {} 失败 status={} body={}",
                    path, response.statusCode(), truncate(response.body(), 300));
            return null;
        }
        return response.body();
    }

    /**
     * 将完整 unified diff 文本按文件切分为 {@link CodeDiff} 列表。
     *
     * <p>解析规则：
     * <ul>
     *   <li>以 {@code diff --git a/xxx b/xxx} 行作为文件边界切分；</li>
     *   <li>文件名优先取 {@code +++ b/path}，删除文件（{@code +++ /dev/null}）取 {@code --- a/path}；</li>
     *   <li>统计 @@ 段内 {@code +} / {@code -} 行数作为增删行数；</li>
     *   <li>跳过无 @@ 段的条目（二进制文件 / 纯 mode 变更）。</li>
     * </ul>
     */
    private List<CodeDiff> parseUnifiedDiff(String rawDiff) {
        List<CodeDiff> diffs = new ArrayList<>();
        if (rawDiff == null || rawDiff.isBlank()) {
            return diffs;
        }

        for (String section : rawDiff.split("(?m)^diff --git ")) {
            if (section.isBlank()) {
                continue;    // split 产生的首个空段
            }
            String fileDiff = "diff --git " + section;

            String fileName = null;
            int added = 0;
            int deleted = 0;
            boolean hasHunk = false;

            for (String line : fileDiff.split("\n")) {
                if (line.startsWith("+++ b/")) {
                    fileName = line.substring(6).trim();
                } else if (line.startsWith("+++ /dev/null")) {
                    // 删除文件：文件名由 --- a/ 行提供
                } else if (fileName == null && line.startsWith("--- a/")) {
                    fileName = line.substring(6).trim();
                } else if (line.startsWith("@@")) {
                    hasHunk = true;
                } else if (hasHunk && line.startsWith("+") && !line.startsWith("+++")) {
                    added++;
                } else if (hasHunk && line.startsWith("-") && !line.startsWith("---")) {
                    deleted++;
                }
            }

            if (fileName == null || fileName.isEmpty() || !hasHunk) {
                continue;    // 二进制 / 无实际内容变更，跳过
            }

            diffs.add(new CodeDiff(fileName, fileDiff, CodeDiff.inferLanguage(fileName), added, deleted));
        }
        return diffs;
    }

    /** 截断过长字符串用于日志输出。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ===================== 返回值模型 =====================

    /**
     * 行内评论条目（postReviewComments 入参）。
     *
     * @param path 文件相对路径
     * @param line 行号（1-based，指向变更后的右侧代码 side=RIGHT）
     * @param body 评论内容（建议含 suggestion 代码块）
     */
    public record ReviewCommentItem(String path, int line, String body) {
    }

    /**
     * PR 变更信息（fetchPrChanges 返回值）。
     *
     * @param title        PR 标题
     * @param author       PR 作者登录名
     * @param sourceBranch 源分支
     * @param targetBranch 目标分支
     * @param diffs        各文件变更列表（已转为 {@link CodeDiff}）
     */
    public record PrChanges(
            String title,
            String author,
            String sourceBranch,
            String targetBranch,
            List<CodeDiff> diffs) {
    }
}
