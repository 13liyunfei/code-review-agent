package com.codereview.agent.core.rag;

import com.codereview.agent.core.http.EgressHttpClientFactory;
import com.codereview.agent.core.http.EgressMode;
import com.codereview.agent.core.http.EgressProperties;
import com.codereview.agent.core.memory.MemoryEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 托管 cross-encoder 重排器（对接 Cohere / Jina 等 Rerank API）。
 *
 * <p>真实 cross-encoder 在查询与每个候选文档联合编码，相关性判断远胜向量点积，
 * 是工业级 RAG 的「质量闸门」。实现要点：
 * <ul>
 *   <li>支持两种主流协议：Cohere {@code /rerank} 与 Jina {@code /v1/rerank}（通过 {@code provider} 切换）；</li>
 *   <li><b>失败安全</b>：网络/配额/超时任一失败立即降级到构造时传入的 {@link HeuristicReranker}，
 *       绝不因重排服务不可用而阻断审查链路；</li>
 *   <li><b>受控出口</b>：出站策略由 {@link EgressProperties.Endpoint} 显式声明（DIRECT/SYSTEM/PROXY），
 *       不再依赖系统全局代理；内部服务（PG/Redis）完全不受其影响。</li>
 * </ul>
 *
 * <p>配置示例（application.yml）：
 * <pre>
 *   review:
 *     rag.rerank:
 *       enabled: true
 *       provider: cohere            # 或 jina
 *       base-url: https://api.cohere.ai/v1/rerank
 *       api-key: ${RERANK_API_KEY:}
 *       model: rerank-english-v3.0
 *     egress.rerank:
 *       mode: direct                # direct | system | proxy
 *       proxy-url: ${RERANK_PROXY:} # 仅 mode=proxy 时生效
 * </pre>
 */
public class ApiReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(ApiReranker.class);

    private final String provider;     // "cohere" | "jina"
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int timeoutMs;
    private final Reranker fallback;
    private final HttpClient http;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 最优构造：显式声明出站出口策略。
     *
     * @param egress 出口端点配置（mode / proxy-url / connect-timeout-ms），来自 {@code review.egress.rerank}
     */
    public ApiReranker(String provider, String baseUrl, String apiKey, String model,
                       int timeoutMs, Reranker fallback, EgressProperties.Endpoint egress) {
        this.provider = provider == null ? "cohere" : provider.toLowerCase();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.timeoutMs = timeoutMs <= 0 ? 2000 : timeoutMs;
        this.fallback = fallback != null ? fallback : new HeuristicReranker();
        EgressProperties.Endpoint ep = egress != null ? egress : new EgressProperties().getRerank();
        this.http = EgressHttpClientFactory.buildClient(ep, "rerank");
    }

    /** 向后兼容：未指定出口时默认 DIRECT 直连（取代旧 bypassSystemProxy=false 语义）。 */
    public ApiReranker(String provider, String baseUrl, String apiKey, String model,
                       int timeoutMs, Reranker fallback) {
        this(provider, baseUrl, apiKey, model, timeoutMs, fallback,
                defaultDirectEndpoint());
    }

    private static EgressProperties.Endpoint defaultDirectEndpoint() {
        EgressProperties.Endpoint ep = new EgressProperties().getRerank();
        ep.setMode(EgressMode.DIRECT);
        return ep;
    }

    @Override
    public List<MemoryEntry> rerank(String query, List<MemoryEntry> candidates, int topN) {
        if (apiKey == null || apiKey.isBlank() || baseUrl == null || baseUrl.isBlank()) {
            log.debug("[Rerank] 未配置 API（key/url 空），降级启发式重排");
            return fallback.rerank(query, candidates, topN);
        }
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        try {
            String body = buildBody(query, candidates, topN);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[Rerank] API 返回 {}，降级启发式重排", resp.statusCode());
                return fallback.rerank(query, candidates, topN);
            }
            return parseResponse(resp.body(), candidates, topN);
        } catch (Exception e) {
            log.warn("[Rerank] 调用异常（{}），降级启发式重排", e.getMessage());
            return fallback.rerank(query, candidates, topN);
        }
    }

    private String buildBody(String query, List<MemoryEntry> candidates, int topN) {
        StringBuilder docs = new StringBuilder("[");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) docs.append(',');
            docs.append('"').append(escape(candidates.get(i).content())).append('"');
        }
        docs.append(']');
        // Cohere 与 Jina 的 rerank 请求体形状一致：model / query / documents / top_n
        return String.format(
                "{\"model\":\"%s\",\"query\":\"%s\",\"documents\":%s,\"top_n\":%d}",
                model, escape(query), docs, topN);
    }

    private List<MemoryEntry> parseResponse(String json, List<MemoryEntry> candidates, int topN) {
        // 结构化解析：Cohere / Jina 均返回 {"results":[{"index":N, "relevance_score":x}, ...]}
        // 按 results 数组顺序（API 已按相关性降序）收集 index 并映射回候选。
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                log.warn("[Rerank] 响应缺少 results 数组，降级启发式重排");
                return fallback.rerank(json, candidates, topN);
            }
            List<MemoryEntry> out = new ArrayList<>();
            for (JsonNode r : results) {
                JsonNode idxNode = r.get("index");
                if (idxNode == null || !idxNode.isInt()) {
                    continue;
                }
                int idx = idxNode.asInt();
                if (idx >= 0 && idx < candidates.size()) {
                    out.add(candidates.get(idx));
                }
                if (out.size() >= topN) {
                    break;
                }
            }
            if (out.isEmpty()) {
                log.warn("[Rerank] 解析结果为空（index 越界或结构异常），降级启发式重排");
                return fallback.rerank(json, candidates, topN);
            }
            return out;
        } catch (Exception e) {
            log.warn("[Rerank] 响应解析失败（{}），降级启发式重排", e.getMessage());
            return fallback.rerank(json, candidates, topN);
        }
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
