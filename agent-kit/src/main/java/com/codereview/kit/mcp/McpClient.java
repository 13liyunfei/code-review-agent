package com.codereview.kit.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP（Model Context Protocol）客户端——stdio 传输、JSON-RPC 2.0、换行分隔帧。
 *
 * <p>2026 年 MCP 已成为工具互操作的事实标准；本客户端补上 kit 的工具生态接入：
 * <pre>
 * McpClient client = McpClient.start("npx", "-y", "some-mcp-server");
 * List&lt;McpTool&gt; tools = client.listTools();
 * String out = client.callTool("git_status", Map.of());
 * </pre>
 * 测试友好：也支持直接注入 {@code InputStream/OutputStream}（如管道模拟的 fake server）。
 */
public class McpClient implements Closeable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BufferedReader reader;
    private final OutputStream out;
    private final Process process; // 可空（测试用管道流时为空）
    private final AtomicLong idSeq = new AtomicLong(1);

    /** 测试友好构造：直接给流（如 PipedInputStream 模拟的 fake server）。 */
    public McpClient(InputStream in, OutputStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.out = out;
        this.process = null;
    }

    /** 启动外部 MCP server 进程（stdio）。 */
    public static McpClient start(String command, String... args) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(commandParts(command, args));
        pb.redirectErrorStream(false);
        Process p = pb.start();
        McpClient c = new McpClient(p.getInputStream(), p.getOutputStream());
        try {
            c.initialize();
        } catch (Exception e) {
            p.destroyForcibly();
            throw e;
        }
        return c;
    }

    private static List<String> commandParts(String command, String... args) {
        List<String> parts = new ArrayList<>();
        parts.add(command);
        parts.addAll(List.of(args));
        return parts;
    }

    /** 握手：initialize + initialized 通知。 */
    public void initialize() throws IOException {
        long id = idSeq.getAndIncrement();
        send(Map.of("jsonrpc", "2.0", "id", id, "method", "initialize",
                "params", Map.of("protocolVersion", "2025-03-26",
                        "capabilities", Map.of(), "clientInfo", Map.of("name", "agent-kit", "version", "0.1.0"))));
        readResponse(id);
        // initialized 通知（无 id，不需要响应）
        send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
    }

    /** 列出可用工具。 */
    public List<McpTool> listTools() throws IOException {
        long id = idSeq.getAndIncrement();
        send(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/list", "params", Map.of()));
        JsonNode resp = readResponse(id);
        List<McpTool> tools = new ArrayList<>();
        JsonNode arr = resp.path("result").path("tools");
        for (JsonNode t : arr) {
            tools.add(new McpTool(t.path("name").asText(), t.path("description").asText(),
                    MAPPER.convertValue(t.path("inputSchema"), Map.class)));
        }
        return tools;
    }

    /** 调用工具。 */
    public String callTool(String name, Map<String, Object> args) throws IOException {
        long id = idSeq.getAndIncrement();
        send(Map.of("jsonrpc", "2.0", "id", id, "method", "tools/call",
                "params", Map.of("name", name, "arguments", args == null ? Map.of() : args)));
        JsonNode resp = readResponse(id);
        JsonNode content = resp.path("result").path("content");
        StringBuilder sb = new StringBuilder();
        for (JsonNode c : content) {
            if ("text".equals(c.path("type").asText())) {
                sb.append(c.path("text").asText());
            }
        }
        return sb.toString();
    }

    private void send(Map<String, Object> msg) throws IOException {
        out.write(MAPPER.writeValueAsBytes(msg));
        out.write('\n');
        out.flush();
    }

    private JsonNode readResponse(long expectedId) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode node = MAPPER.readTree(line);
            if (node.has("id") && node.path("id").asLong() == expectedId) {
                return node;
            }
            // 忽略服务端主动通知 / 其他 id 的响应
        }
        throw new IOException("MCP server 关闭连接，未收到响应");
    }

    @Override
    public void close() throws IOException {
        try {
            out.close();
        } catch (Exception ignored) {
        }
        if (process != null) {
            process.destroy();
        }
    }
}
