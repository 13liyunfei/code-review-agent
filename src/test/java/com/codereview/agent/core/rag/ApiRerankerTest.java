package com.codereview.agent.core.rag;

import com.codereview.agent.core.memory.MemoryEntry;
import com.codereview.agent.core.memory.MemoryLevel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 ApiReranker（维度 ③ 的 cross-encoder 生产实现）：
 *  - 结构化解析 Cohere / Jina 风格响应（{"results":[{"index":N}]}），按 index 重排；
 *  - 文本中出现 "index" 子串不应误匹配（验证不再用手写 indexOf 扫描）；
 *  - API 出错 / 非 200 时安全降级到启发式。
 *
 * 使用 JDK 内置 HttpServer 起本地端点，无需外部网络。
 */
class ApiRerankerTest {

    private HttpServer server;
    private int port;

    private MemoryEntry entry(long id, String content) {
        return new MemoryEntry(id, "RAG", "default", content,
                Map.of(), MemoryLevel.LONG_TERM, Instant.now(), null);
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesCohereStyleResponseAndReorders() throws Exception {
        // Cohere 返回 results 数组，index 顺序即相关性降序；故意把 index=1 的文档排在前面
        String body = "{\"results\":[{\"index\":1,\"relevance_score\":0.9},"
                + "{\"index\":0,\"relevance_score\":0.3}]}";
        server.createContext("/rerank", exchange -> {
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        HeuristicReranker fallback = new HeuristicReranker();
        ApiReranker reranker = new ApiReranker("cohere",
                "http://127.0.0.1:" + port + "/rerank", "fake-key", "model", 2000, fallback);
        List<MemoryEntry> candidates = List.of(
                entry(10, "噪声文档 与查询无关"),
                entry(20, "关键文档 查询高度相关"));
        List<MemoryEntry> ranked = reranker.rerank("关键文档 查询", candidates, 2);
        assertEquals(2, ranked.size());
        // index=1 对应 candidates[1]，应排第一
        assertEquals(20L, ranked.get(0).id());
    }

    @Test
    void doesNotMisMatchIndexSubstringInDocumentText() throws Exception {
        // 文档内容本身含 "index" 英文词，验证解析只认 results[].index 而非任意子串
        String body = "{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}";
        server.createContext("/rerank", exchange -> {
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        HeuristicReranker fallback = new HeuristicReranker();
        ApiReranker reranker = new ApiReranker("cohere",
                "http://127.0.0.1:" + port + "/rerank", "fake-key", "model", 2000, fallback);
        List<MemoryEntry> candidates = List.of(
                entry(1, "this document mentions the index field for sorting"),
                entry(2, "another doc with index column in sql"));
        List<MemoryEntry> ranked = reranker.rerank("query", candidates, 2);
        assertEquals(1, ranked.size());
        assertEquals(1L, ranked.get(0).id());
    }

    @Test
    void fallsBackWhenNon200() throws Exception {
        server.createContext("/rerank", exchange -> exchange.sendResponseHeaders(500, -1));
        server.start();
        HeuristicReranker fallback = new HeuristicReranker();
        ApiReranker reranker = new ApiReranker("cohere",
                "http://127.0.0.1:" + port + "/rerank", "fake-key", "model", 2000, fallback);
        List<MemoryEntry> candidates = List.of(
                entry(1, "明文密码 password"),
                entry(2, "咖啡机说明"));
        List<MemoryEntry> ranked = reranker.rerank("明文密码 password", candidates, 2);
        // 降级后仍返回候选（不抛异常），且相关块仍在前
        assertEquals(2, ranked.size());
        assertTrue(ranked.stream().anyMatch(e -> e.id() == 1L));
    }

    @Test
    void fallsBackWhenApiKeyMissing() {
        HeuristicReranker fallback = new HeuristicReranker();
        // 无 api-key / url → 直接走 fallback，不发起请求
        ApiReranker reranker = new ApiReranker("cohere", "", null, "model", 2000, fallback);
        List<MemoryEntry> candidates = List.of(
                entry(1, "明文密码 password"),
                entry(2, "咖啡机说明"));
        List<MemoryEntry> ranked = reranker.rerank("明文密码 password", candidates, 2);
        assertEquals(2, ranked.size());
    }

    @Test
    void usesExplicitEgressProxyWhenConfigured() throws Exception {
        // 验证新的 7 参构造：显式声明 PROXY 出口（仅本请求走代理，不劫持 localhost）
        String body = "{\"results\":[{\"index\":1,\"relevance_score\":0.9},"
                + "{\"index\":0,\"relevance_score\":0.3}]}";
        server.createContext("/rerank", exchange -> {
            byte[] resp = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();

        com.codereview.agent.core.http.EgressProperties.Endpoint ep =
                new com.codereview.agent.core.http.EgressProperties().getRerank();
        ep.setMode(com.codereview.agent.core.http.EgressMode.PROXY);
        ep.setProxyUrl("http://127.0.0.1:9999"); // 本地无此代理，验证 client 构建成功且请求失败安全降级

        HeuristicReranker fallback = new HeuristicReranker();
        ApiReranker reranker = new ApiReranker("cohere",
                "http://127.0.0.1:" + port + "/rerank", "fake-key", "model", 2000, fallback, ep);
        List<MemoryEntry> candidates = List.of(entry(10, "噪声"), entry(20, "关键"));
        // 即使 proxy 不可达，也应安全降级而非抛异常
        List<MemoryEntry> ranked = reranker.rerank("关键", candidates, 2);
        assertEquals(2, ranked.size());
    }
}
