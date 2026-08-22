package com.codereview.agent.ide;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IDE LSP 服务端端到端验证：用管道模拟编辑器发送 JSON-RPC 消息，
 * 确认其能响应 initialize 并返回诊断（publishDiagnostics）。
 */
class IdeReviewServerTest {

    private static final String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,"
            + "\"method\":\"initialize\",\"params\":{\"capabilities\":{}}}";

    private static final String didOpen = "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\","
            + "\"params\":{\"textDocument\":{\"uri\":\"file:///A.java\",\"languageId\":\"java\",\"version\":1,"
            + "\"text\":\"public class A {\\n void m(){ \\n String sql=\\\"SELECT * FROM t\\\";\\n e.printStackTrace();\\n }\\n}\"}}}";

    private static byte[] frame(String msg) {
        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
        String header = "Content-Length: " + body.length + "\r\n\r\n";
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[h.length + body.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(body, 0, out, h.length, body.length);
        return out;
    }

    @Test
    void respondsToInitializeAndPublishesDiagnostics() throws IOException, InterruptedException {
        PipedOutputStream clientToServer = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream(clientToServer);
        PipedInputStream clientRead = new PipedInputStream();
        PipedOutputStream serverOut = new PipedOutputStream(clientRead);

        IdeReviewServer server = new IdeReviewServer();
        Thread t = new Thread(() -> {
            try {
                server.run(serverIn, serverOut);
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();

        // 1) 发送 initialize
        clientToServer.write(frame(initialize));
        clientToServer.flush();
        // 2) 发送 didOpen（触发诊断）
        clientToServer.write(frame(didOpen));
        clientToServer.flush();

        // 3) 读取服务端输出（最多 5 秒）
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            int avail = clientRead.available();
            if (avail > 0) {
                byte[] buf = new byte[avail];
                int n = clientRead.read(buf);
                if (n > 0) {
                    collected.write(buf, 0, n);
                }
            }
            if (collected.toString().contains("publishDiagnostics")
                    && collected.toString().contains("capabilities")) {
                break;
            }
            Thread.sleep(100);
        }

        String out = collected.toString();
        assertTrue(out.contains("\"capabilities\""), "应响应 initialize 的 capabilities");
        assertTrue(out.contains("publishDiagnostics"), "应在 didOpen 后推送诊断");
        assertTrue(out.contains("LOGIC-002") || out.contains("PERF-001"),
                "诊断应包含规则命中（LOGIC-002 异常堆栈 或 PERF-001 SELECT *）");

        clientToServer.close();
    }
}
