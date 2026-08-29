package com.codereview.kit.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.codereview.kit.toolcalling.AgentTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 客户端测试：用管道流模拟 fake MCP server（换行分隔 JSON-RPC）。
 */
class McpClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private McpClient client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() throws IOException {
        if (client != null) {
            client.close();
        }
        executor.shutdownNow();
    }

    @Test
    void 握手_列出工具_调用工具全链路() throws Exception {
        PipedInputStream serverIn = new PipedInputStream();
        PipedOutputStream clientOut = new PipedOutputStream(serverIn);
        PipedInputStream clientIn = new PipedInputStream();
        PipedOutputStream serverOut = new PipedOutputStream(clientIn);

        // fake server：读请求 → 按 method 回响应
        executor.submit(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(serverIn));
                 OutputStream out = serverOut) {
                String line;
                while ((line = reader.readLine()) != null) {
                    var req = MAPPER.readTree(line);
                    String method = req.path("method").asText();
                    long id = req.path("id").asLong();
                    Object result = switch (method) {
                        case "initialize" -> Map.of("protocolVersion", "2025-03-26",
                                "capabilities", Map.of("tools", Map.of()), "serverInfo", Map.of("name", "fake"));
                        case "tools/list" -> Map.of("tools", java.util.List.of(
                                Map.of("name", "echo", "description", "回显文本",
                                        "inputSchema", Map.of("type", "object",
                                                "properties", Map.of("text", Map.of("type", "string"))))));
                        case "tools/call" -> Map.of("content", java.util.List.of(
                                Map.of("type", "text", "text", "echo:" + req.path("params").path("arguments").path("text").asText())));
                        default -> Map.of();
                    };
                    out.write(MAPPER.writeValueAsBytes(Map.of("jsonrpc", "2.0", "id", id, "result", result)));
                    out.write('\n');
                    out.flush();
                }
            } catch (Exception ignored) {
            }
        });

        client = new McpClient(clientIn, clientOut);
        client.initialize();

        var tools = client.listTools();
        assertEquals(1, tools.size());
        assertEquals("echo", tools.get(0).name());

        String result = client.callTool("echo", Map.of("text", "hi"));
        assertEquals("echo:hi", result);

        // 适配器：MCP 工具可被工具决策循环直接调用
        AgentTool adapted = new McpToolAdapter(client, tools.get(0));
        assertTrue(adapted.execute(Map.of("text", "x")).success());
        assertEquals("echo:x", adapted.execute(Map.of("text", "x")).output());
        assertFalse(adapted.name().isBlank());
    }
}
