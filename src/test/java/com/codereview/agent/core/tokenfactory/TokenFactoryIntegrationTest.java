package com.codereview.agent.core.tokenfactory;

import com.codereview.agent.core.llm.TokenUsageRecord;
import com.codereview.agent.core.llm.TokenUsageRecorder;
import com.codereview.agent.core.llm.UsageAwareModelProvider;
import com.codereview.agent.core.trace.TraceContext;
import com.sun.net.httpserver.HttpServer;
import io.tokenfactory.client.TokenFactoryClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Token 工厂接入的端到端验证：用 JDK HttpServer 打桩工厂，不走真实网络。
 */
class TokenFactoryIntegrationTest {

    private HttpServer server;
    private int port;
    private final List<String> receivedBodies = new ArrayList<>();
    private final AtomicReference<Stub> responder =
            new AtomicReference<>(new Stub(200, "{}"));

    /** 桩响应。 */
    record Stub(int status, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String body;
            try (InputStream in = exchange.getRequestBody()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            receivedBodies.add(body);
            Stub stub = responder.get();
            byte[] bytes = stub.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(stub.status(), bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        TraceContext.clear();
    }

    private TokenFactoryClient client() {
        return TokenFactoryClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .accessKey("tf-test-key")
                .appId("code-review-agent")
                .connectTimeout(Duration.ofSeconds(2))
                .requestTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Test
    void factory_provider_reads_usage_and_actual_provider() throws Exception {
        responder.set(new Stub(200, """
                {"id":"chatcmpl-1","model":"default",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"LGTM"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120},
                 "provider":"deepseek","upstream_model":"deepseek-chat",
                 "cost_micros":12345,"trace_id":"trace-1","latency_ms":842}
                """));
        TokenUsageRecorder recorder = new TokenUsageRecorder(16);
        TokenFactoryChatProvider provider =
                new TokenFactoryChatProvider("default", client(), recorder, true);

        UsageAwareModelProvider.ChatOutcome outcome = provider.chatWithUsage("审查这段 diff");

        assertEquals("LGTM", outcome.text());
        assertEquals(100, outcome.promptTokensOrZero());
        assertEquals(20, outcome.completionTokensOrZero());
        // 用量要记在「实际命中的厂商」名下，否则看板上全是别名，出事查不到是谁
        List<TokenUsageRecord> records = recorder.snapshot();
        assertEquals(1, records.size());
        assertEquals("deepseek", records.get(0).providerName());
        assertEquals("deepseek-chat", records.get(0).model());
        assertEquals(120, records.get(0).totalTokens());
        assertEquals("token-factory:default", provider.name());
    }

    @Test
    void factory_provider_treats_blank_content_as_failure() {
        responder.set(new Stub(200, """
                {"id":"chatcmpl-1","model":"default","choices":[]}
                """));
        TokenFactoryChatProvider provider =
                new TokenFactoryChatProvider("default", client(), new TokenUsageRecorder(4), true);
        // 空结果必须当失败：交给网关 failover，而不是拿空串去解析出一份「看起来通过」的报告
        assertThrows(IllegalStateException.class, () -> provider.chatWithUsage("hi"));
    }

    @Test
    void direct_provider_reports_usage_back_to_factory() throws Exception {
        responder.set(new Stub(200, "{\"accepted\":true}"));
        RecordingReporter reporter = new RecordingReporter();
        UsageReportingProvider provider = new UsageReportingProvider(
                new StubUpstream("deepseek", "deepseek-chat", true), reporter, "default", "deepseek-chat");

        assertEquals("OK", provider.chat("hi"));

        assertEquals(1, reporter.successes.size());
        RecordingReporter.Call call = reporter.successes.get(0);
        assertEquals("deepseek", call.providerCode(), "补报要带上实际供应商，工厂侧才能按厂商分摊");
        assertEquals("deepseek-chat", call.upstreamModel());
        assertEquals(11, call.promptTokens());
        assertEquals(7, call.completionTokens());
        assertFalse(call.estimated(), "上游给了真实 usage 就不该标估算");
        assertTrue(reporter.failures.isEmpty());
    }

    @Test
    void direct_provider_estimates_and_flags_when_upstream_returns_no_usage() throws Exception {
        responder.set(new Stub(200, "{\"accepted\":true}"));
        RecordingReporter reporter = new RecordingReporter();
        // usage 为空：宁可给一个带 estimated 标记的估算值，也不要账上记 0
        UsageReportingProvider provider = new UsageReportingProvider(
                new StubUpstream("deepseek", "deepseek-chat", false), reporter, "default", "deepseek-chat");

        provider.chat("hello world");

        RecordingReporter.Call call = reporter.successes.get(0);
        assertTrue(call.estimated(), "无 usage 时必须标估算，否则对账时无法识别");
        assertTrue(call.promptTokens() > 0 && call.completionTokens() > 0, "估算值不能退化成 0");
    }

    @Test
    void direct_provider_reports_failure_and_rethrows() {
        responder.set(new Stub(200, "{\"accepted\":true}"));
        RecordingReporter reporter = new RecordingReporter();
        UsageReportingProvider provider = new UsageReportingProvider(
                new FailingUpstream(), reporter, "default", "glm-5.2");

        assertThrows(IllegalStateException.class, () -> provider.chat("hi"));
        assertEquals(1, reporter.failures.size());
        assertEquals("IllegalStateException", reporter.failures.get(0).errorCode(),
                "错误码用异常类简名：够区分，又不会把可能含敏感信息的 message 发到工厂");
    }

    @Test
    void reporting_failure_never_breaks_the_review_flow() throws Exception {
        responder.set(new Stub(500, "{\"error\":{\"code\":\"BOOM\",\"message\":\"工厂挂了\"}}"));
        // 补报失败只记日志：工厂已经不可用了，再让补报把审查链路打断就是故障传染
        UsageReporter reporter = new TokenFactoryUsageReporter(client(), "default");
        UsageReportingProvider provider = new UsageReportingProvider(
                new StubUpstream("deepseek", "deepseek-chat", true), reporter, "default", "deepseek-chat");

        assertEquals("OK", provider.chat("hi"), "补报失败不应影响主流程");
    }

    @Test
    void trace_id_is_shared_between_review_and_factory() throws Exception {
        responder.set(new Stub(200, "{\"accepted\":true}"));
        TraceContext.set("review-trace-42");
        RecordingReporter reporter = new RecordingReporter();
        UsageReportingProvider provider = new UsageReportingProvider(
                new StubUpstream("deepseek", "deepseek-chat", true), reporter, "default", "deepseek-chat");

        provider.chat("hi");

        assertEquals("review-trace-42", reporter.successes.get(0).traceId(),
                "补报必须带本系统的 traceId，否则工厂里的用量对不回审查链路");
    }

    // ---------------- 测试替身 ----------------

    /** 桩上游：可控制是否返回 usage。 */
    private record StubUpstream(String name, String model, boolean withUsage)
            implements UsageAwareModelProvider {

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String chat(String prompt) throws Exception {
            return chatWithUsage(prompt).text();
        }

        @Override
        public ChatOutcome chatWithUsage(String prompt) {
            return withUsage
                    ? new ChatOutcome("OK", 11, 7)
                    : ChatOutcome.textOnly("OK");
        }
    }

    private static final class FailingUpstream implements UsageAwareModelProvider {
        @Override
        public String name() {
            return "glm";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String chat(String prompt) {
            throw new IllegalStateException("上游 502");
        }

        @Override
        public ChatOutcome chatWithUsage(String prompt) throws Exception {
            throw new IllegalStateException("上游 502");
        }
    }

    private static final class RecordingReporter implements UsageReporter {
        final List<Call> successes = new ArrayList<>();
        final List<Failure> failures = new ArrayList<>();

        @Override
        public void reportSuccess(String traceId, String alias, String providerCode, String upstreamModel,
                                  int promptTokens, int completionTokens, long latencyMs, boolean estimated) {
            successes.add(new Call(traceId, alias, providerCode, upstreamModel,
                    promptTokens, completionTokens, latencyMs, estimated));
        }

        @Override
        public void reportFailure(String traceId, String alias, String providerCode, String upstreamModel,
                                  long latencyMs, String errorCode) {
            failures.add(new Failure(traceId, alias, providerCode, upstreamModel, latencyMs, errorCode));
        }

        record Call(String traceId, String alias, String providerCode, String upstreamModel,
                    int promptTokens, int completionTokens, long latencyMs, boolean estimated) {
        }

        record Failure(String traceId, String alias, String providerCode, String upstreamModel,
                       long latencyMs, String errorCode) {
        }
    }
}
