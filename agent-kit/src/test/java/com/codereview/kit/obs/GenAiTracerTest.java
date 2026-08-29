package com.codereview.kit.obs;

import com.codereview.kit.ChatModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenAiTracerTest {

    @Test
    void TracedChatModel自动记录span() {
        LoggingGenAiTracer tracer = new LoggingGenAiTracer();
        ChatModel traced = new TracedChatModel(prompt -> "回复:" + prompt, tracer);
        traced.chat("你好");
        traced.chat("再来");
        assertEquals(2, tracer.spans().size());
        GenAiSpan span = tracer.spans().get(0);
        assertEquals("llm.chat", span.operation());
        assertTrue(span.durationMs() >= 0);
        assertTrue(span.inputTokens() > 0);
        assertTrue(span.outputTokens() > 0);
    }

    @Test
    void 手动记录调用耗时() {
        LoggingGenAiTracer tracer = new LoggingGenAiTracer();
        GenAiSpan span = tracer.record("tool.call", () -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ignored) {
            }
        });
        assertTrue(span.durationMs() >= 10);
        assertEquals(1, tracer.spans().size());
        tracer.reset();
        assertTrue(tracer.spans().isEmpty());
    }
}
