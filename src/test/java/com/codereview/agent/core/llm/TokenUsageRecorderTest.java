package com.codereview.agent.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenUsageRecorderTest {

    @Test
    void records_calls_and_exposes_recent() {
        TokenUsageRecorder r = new TokenUsageRecorder(5);
        r.record(TokenUsageRecord.of("p1", "deepseek-v4-flash", 100, 50, 10, true));
        r.record(TokenUsageRecord.of("p1", "deepseek-v4-flash", 200, 80, 12, true));
        assertEquals(2, r.size());
        assertEquals(2, r.snapshot().size());
    }

    @Test
    void ring_buffer_evicts_oldest_when_full() {
        TokenUsageRecorder r = new TokenUsageRecorder(3);
        for (int i = 1; i <= 5; i++) {
            r.record(TokenUsageRecord.of("p", "m", i * 10, i, i, true));
        }
        assertEquals(3, r.size(), "环形缓冲满后应淘汰最早");
        var recent = r.snapshot();
        // 剩的是 i=3,4,5
        assertEquals(30, recent.get(0).promptTokens());
        assertEquals(40, recent.get(1).promptTokens());
        assertEquals(50, recent.get(2).promptTokens());
    }

    @Test
    void aggregates_per_provider() {
        TokenUsageRecorder r = new TokenUsageRecorder();
        r.record(TokenUsageRecord.of("p1", "m", 100, 50, 10, true));
        r.record(TokenUsageRecord.of("p1", "m", 200, 100, 20, true));
        r.record(TokenUsageRecord.of("p2", "m", 50, 25, 5, false));
        var aggs = r.aggregatesSnapshot();
        assertEquals(2, aggs.size());
        var p1 = aggs.stream().filter(a -> a.name().equals("p1")).findFirst().orElseThrow();
        assertEquals(2, p1.calls());
        assertEquals(450, p1.totalTokens());
        assertEquals(300, p1.promptTokens());
        assertEquals(150, p1.completionTokens());
        var p2 = aggs.stream().filter(a -> a.name().equals("p2")).findFirst().orElseThrow();
        assertEquals(1, p2.calls());
        assertEquals(1, p2.failures());
        assertEquals(5, p2.avgDurationMs(), "p2 1 次调用，时长=5ms");
    }

    @Test
    void invalid_capacity_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new TokenUsageRecorder(0));
        assertThrows(IllegalArgumentException.class, () -> new TokenUsageRecorder(-5));
    }

    @Test
    void null_record_ignored() {
        TokenUsageRecorder r = new TokenUsageRecorder();
        r.record(null);
        assertEquals(0, r.size());
    }

    @Test
    void factory_handles_null_token_counts() {
        TokenUsageRecord rec = TokenUsageRecord.of("p", "m", null, null, 5, true);
        assertEquals(0, rec.promptTokens());
        assertEquals(0, rec.completionTokens());
        assertEquals(0, rec.totalTokens());
        assertNotNull(rec.timestamp());
    }
}