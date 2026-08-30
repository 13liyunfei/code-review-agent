package com.codereview.agent.core.llm;

import com.codereview.agent.core.trace.TraceContext;
import com.codereview.kit.obs.AggregateTracer;
import com.codereview.kit.obs.GenAiSpan;
import com.codereview.kit.obs.LoggingGenAiTracer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 调用追踪适配 agent-kit obs 的回归测试。
 *
 * <p>验证点：本仓库不再自建指标体系——模型边界的 LangChain4j 监听器把每次调用
 * 翻译成 agent-kit 的 {@link GenAiSpan}（带业务 traceId + 真实 token 用量），
 * 记录与聚合全部交给基座。
 */
class AgentKitLlmTracingTest {

    private final ChatRequest request = ChatRequest.builder()
            .messages(UserMessage.from("请审查这段代码"))
            .modelName("qwen-plus")
            .build();

    @AfterEach
    void clearTrace() {
        TraceContext.clear();
    }

    @Test
    void successfulCallIsAggregatedByAgentKit() {
        AggregateTracer agg = new AggregateTracer();
        LoggingChatModelListener listener = new LoggingChatModelListener(agg);
        Map<Object, Object> attrs = new HashMap<>();

        listener.onRequest(new ChatModelRequestContext(request, null, attrs));
        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder()
                        .aiMessage(AiMessage.from("[{\"rule\":\"x\"}]"))
                        .modelName("qwen-plus")
                        .tokenUsage(new TokenUsage(120, 45))
                        .build(),
                request, null, attrs));

        AggregateTracer.Stats stats = agg.snapshot();
        assertEquals(1, stats.calls());
        assertEquals(0, stats.errors());
        assertEquals(120, stats.inputTokens(), "token 应取模型回传的真实用量，而非字符数估算");
        assertEquals(45, stats.outputTokens());
    }

    @Test
    void spanCarriesBusinessTraceIdAndModelName() {
        LoggingGenAiTracer tracer = new LoggingGenAiTracer();
        LoggingChatModelListener listener = new LoggingChatModelListener(tracer);
        Map<Object, Object> attrs = new HashMap<>();
        TraceContext.set("trace-9f3c1a");

        listener.onRequest(new ChatModelRequestContext(request, null, attrs));
        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).modelName("qwen-plus").build(),
                request, null, attrs));

        assertEquals(1, tracer.spans().size());
        GenAiSpan span = tracer.spans().get(0);
        assertEquals("trace-9f3c1a", span.traceId(), "span 必须带上业务链路 id，否则并行 Agent 的调用串不起来");
        assertEquals("qwen-plus", span.model());
        assertEquals("1", span.attribute("messages"));
        assertFalse(span.failed());
    }

    @Test
    void failedCallIsCountedAsError() {
        AggregateTracer agg = new AggregateTracer();
        LoggingChatModelListener listener = new LoggingChatModelListener(agg);
        Map<Object, Object> attrs = new HashMap<>();

        listener.onRequest(new ChatModelRequestContext(request, null, attrs));
        listener.onError(new ChatModelErrorContext(new IllegalStateException("连接超时"), request, null, attrs));

        AggregateTracer.Stats stats = agg.snapshot();
        assertEquals(1, stats.calls());
        assertEquals(1, stats.errors());
        assertEquals(1.0, stats.errorRate(), 0.001);
    }

    @Test
    void durationIsMeasuredAcrossRequestAndResponse() {
        LoggingGenAiTracer tracer = new LoggingGenAiTracer();
        LoggingChatModelListener listener = new LoggingChatModelListener(tracer);
        Map<Object, Object> attrs = new HashMap<>();

        listener.onRequest(new ChatModelRequestContext(request, null, attrs));
        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).modelName("m").build(),
                request, null, attrs));

        assertTrue(tracer.spans().get(0).durationMs() >= 0);
    }

    @Test
    void listenerWithoutTracerStaysSilentAndSafe() {
        LoggingChatModelListener listener = new LoggingChatModelListener();
        Map<Object, Object> attrs = new HashMap<>();

        listener.onRequest(new ChatModelRequestContext(request, null, attrs));
        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).modelName("m").build(),
                request, null, attrs));
        listener.onError(new ChatModelErrorContext(new RuntimeException("boom"), request, null, attrs));
        // 无 tracer 时退化为纯日志：不抛异常即为通过
    }

    @Test
    void brokenTracerNeverBreaksTheCall() {
        LoggingChatModelListener listener = new LoggingChatModelListener(span -> {
            throw new RuntimeException("tracer 挂了");
        });
        Map<Object, Object> attrs = new HashMap<>();

        listener.onRequest(new ChatModelRequestContext(request, null, attrs));
        listener.onResponse(new ChatModelResponseContext(
                ChatResponse.builder().aiMessage(AiMessage.from("ok")).modelName("m").build(),
                request, null, attrs));
        // 观测是旁路：tracer 故障不得冒泡到模型调用
    }

    @Test
    void multiAgentParallelCallsShareOneTraceId() {
        LoggingGenAiTracer tracer = new LoggingGenAiTracer();
        LoggingChatModelListener listener = new LoggingChatModelListener(tracer);
        TraceContext.set("trace-parallel");

        // 5 个 Agent 各自一次调用（模拟 CompletableFutureCoordinator 的扇出）
        for (int i = 0; i < 5; i++) {
            Map<Object, Object> attrs = new HashMap<>();
            listener.onRequest(new ChatModelRequestContext(request, null, attrs));
            listener.onResponse(new ChatModelResponseContext(
                    ChatResponse.builder().aiMessage(AiMessage.from("r" + i)).modelName("m").build(),
                    request, null, attrs));
        }

        List<GenAiSpan> spans = tracer.spans();
        assertEquals(5, spans.size());
        assertTrue(spans.stream().allMatch(s -> "trace-parallel".equals(s.traceId())),
                "同一次审查的并行调用必须能按 traceId 聚合");
    }
}
