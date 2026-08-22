package com.codereview.agent.integration.gitlab;

import com.codereview.agent.core.model.CodeDiff;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * GitLab REST API v4 客户端。
 *
 * <p>基于 JDK {@link HttpClient}（无需额外 HTTP 依赖），通过 Personal Access Token 鉴权，
 * 提供以下能力：
 * <ul>
 *   <li>{@link #fetchMrChanges} —— 拉取 MR 变更（标题 / 作者 / 分支 / 各文件 diff）；</li>
 *   <li>{@link #postMrNote} —— 在 MR 上发布评论（审查报告回写）。</li>
 * </ul>
 *
 * <p>调用失败时记录日志并返回 null / false，上层 {@link GitLabReviewService}
 * 会据此降级处理，与系统「4 级降级链」理念一致。
 */
public class GitLabApiClient {

    private static final Logger log = LoggerFactory.getLogger(GitLabApiClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final String apiBase;        // e.g. https://gitlab.com/api/v4
    private final String apiToken;

    /**
     * 构造 GitLab API 客户端。
     *
     * @param baseUrl         GitLab 实例地址（如 https://gitlab.com）
     * @param apiToken        Personal Access Token（需 api scope）
     * @param requestTimeout  单次请求超时
     */
    public GitLabApiClient(String baseUrl, String apiToken, Duration requestTimeout) {
        this.apiBase = stripTrailingSlash(baseUrl) + "/api/v4";
        this.apiToken = apiToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ===================== 核心 API =====================

    /**
     * 拉取 MR 变更信息（含各文件 diff）。
     *
     * <p>调用 {@code GET /api/v4/projects/:id/merge_requests/:iid/changes}，
     * 解析返回中的 MR 元数据与 {@code changes} 数组。
     *
     * @param projectId GitLab 项目 ID（数字）
     * @param mrIid     MR IID（项目内序号）
     * @return 解析后的 MR 变更信息；失败返回 null
     */
    public MrChanges fetchMrChanges(long projectId, long mrIid) {
        String url = apiBase + "/projects/" + projectId + "/merge_requests/" + mrIid + "/changes";
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("PRIVATE-TOKEN", apiToken)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.error("[GitLab API] 获取 MR 变更失败 status={} body={}",
                        response.statusCode(), truncate(response.body(), 500));
                return null;
            }

            JsonNode root = MAPPER.readTree(response.body());

            // 提取 MR 元数据
            String title = root.path("title").asText("(无标题)");
            String author = root.path("author").path("name").asText("unknown");
            String sourceBranch = root.path("source_branch").asText("");
            String targetBranch = root.path("target_branch").asText("main");

            // 解析 changes 数组 → CodeDiff 列表
            JsonNode changes = root.path("changes");
            List<CodeDiff> diffs = new ArrayList<>();
            if (changes.isArray()) {
                for (JsonNode change : changes) {
                    CodeDiff diff = parseChange(change);
                    if (diff != null) {
                        diffs.add(diff);
                    }
                }
            }

            log.info("[GitLab API] MR !{} 共 {} 个文件变更（title=\"{}\", {} → {}）",
                    mrIid, diffs.size(), title, sourceBranch, targetBranch);

            return new MrChanges(title, author, sourceBranch, targetBranch, diffs);

        } catch (Exception e) {
            log.error("[GitLab API] 获取 MR 变更异常 projectId={} mrIid={}：{}",
                    projectId, mrIid, e.getMessage());
            return null;
        }
    }

    /**
     * 在 MR 上发布评论（Note）。
     *
     * <p>调用 {@code POST /api/v4/projects/:id/merge_requests/:iid/notes}，
     * 请求体为 {@code {"body": "<markdown>"}}。
     *
     * @param projectId GitLab 项目 ID
     * @param mrIid     MR IID
     * @param body      评论内容（支持 Markdown）
     * @return true=发布成功
     */
    public boolean postMrNote(long projectId, long mrIid, String body) {
        String url = apiBase + "/projects/" + projectId + "/merge_requests/" + mrIid + "/notes";
        try {
            ObjectNode payload = MAPPER.createObjectNode();
            payload.put("body", body);
            byte[] bodyBytes = MAPPER.writeValueAsBytes(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("PRIVATE-TOKEN", apiToken)
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.error("[GitLab API] 发布评论失败 status={} body={}",
                        response.statusCode(), truncate(response.body(), 500));
                return false;
            }

            log.info("[GitLab API] 评论已发布到 MR !{}（{}字符）", mrIid, body.length());
            return true;

        } catch (Exception e) {
            log.error("[GitLab API] 发布评论异常 projectId={} mrIid={}：{}",
                    projectId, mrIid, e.getMessage());
            return false;
        }
    }

    // ===================== 内部工具 =====================

    /**
     * 将 GitLab changes 数组中的单个 change 节点解析为 {@link CodeDiff}。
     *
     * <p>字段映射：
     * <ul>
     *   <li>文件名 → {@code new_path}（删除文件用 {@code old_path}）；</li>
     *   <li>diff 文本 → {@code diff}（标准 unified diff）；</li>
     *   <li>语言 → 由文件扩展名推断。</li>
     * </ul>
     * 跳过 diff 为空的条目（二进制文件等）。
     */
    private CodeDiff parseChange(JsonNode change) {
        boolean deleted = change.path("deleted_file").asBoolean(false);
        String newPath = change.path("new_path").asText("");
        String oldPath = change.path("old_path").asText("");
        String diff = change.path("diff").asText("");

        // 文件名：删除文件用 old_path，其余用 new_path
        String fileName = (deleted || newPath.isEmpty()) ? oldPath : newPath;
        if (fileName.isEmpty() || diff.isEmpty()) {
            return null;
        }

        // 统计增删行数（从 diff 中 + / - 行计数）
        int added = 0;
        int deleted2 = 0;
        for (String line : diff.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                added++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                deleted2++;
            }
        }

        return new CodeDiff(fileName, diff, CodeDiff.inferLanguage(fileName), added, deleted2);
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
     * MR 变更信息（fetchMrChanges 返回值）。
     *
     * @param title        MR 标题
     * @param author       MR 作者名称
     * @param sourceBranch 源分支
     * @param targetBranch 目标分支
     * @param diffs        各文件变更列表（已转为 {@link CodeDiff}）
     */
    public record MrChanges(
            String title,
            String author,
            String sourceBranch,
            String targetBranch,
            List<CodeDiff> diffs) {
    }
}
