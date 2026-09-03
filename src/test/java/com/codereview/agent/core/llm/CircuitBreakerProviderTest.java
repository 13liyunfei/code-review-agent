package com.codereview.agent.core.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerProviderTest {

    /** 造一个可控的 ModelProvider：通过 supplier 函数决定每次 chat 的行为。 */
    private static ModelProvider fake(String name, Function<Integer, String> behavior) {
        AtomicInteger calls = new AtomicInteger();
        return new ModelProvider() {
            @Override public String name() { return name; }
            @Override public boolean available() { return true; }
            @Override public String chat(String prompt) throws Exception {
                int n = calls.incrementAndGet();
                return behavior.apply(n);
            }
        };
    }

    @Test
    void opens_after_threshold_consecutive_failures_then_fast_fails() {
        ModelProvider delegate = fake("p1", n -> { throw new RuntimeException("boom"); });
        CircuitBreakerProvider cb = new CircuitBreakerProvider(delegate, 3,
                Duration.ofSeconds(30), 1);

        // 前 3 次都失败
        for (int i = 0; i < 3; i++) {
            assertThrows(RuntimeException.class, () -> cb.chat("hi"));
        }
        // 第 4 次：OPEN，应抛 CircuitOpenException
        CircuitOpenException ex = assertThrows(CircuitOpenException.class, () -> cb.chat("hi"));
        assertEquals("p1", ex.getProviderName());
        assertEquals(CircuitBreakerState.OPEN, cb.snapshot().state());
        assertFalse(cb.available(), "OPEN 期间不可用");
    }

    @Test
    void half_open_after_open_window_then_success_closes() throws Exception {
        // 用 1s 短窗口便于测试
        AtomicInteger callCount = new AtomicInteger();
        ModelProvider delegate = fake("p2", n -> {
            callCount.incrementAndGet();
            if (n <= 3) throw new RuntimeException("fail-" + n);
            return "ok-" + n;
        });
        CircuitBreakerProvider cb = new CircuitBreakerProvider(delegate, 3,
                Duration.ofMillis(500), 1);
        for (int i = 0; i < 3; i++) {
            assertThrows(RuntimeException.class, () -> cb.chat("hi"));
        }
        assertEquals(CircuitBreakerState.OPEN, cb.snapshot().state());

        // 等 OPEN 过期
        Thread.sleep(600);
        // 试探一次成功 → CLOSED
        String r = cb.chat("hi");
        assertEquals("ok-4", r);
        assertEquals(CircuitBreakerState.CLOSED, cb.snapshot().state());
        assertTrue(cb.available());
        // 验证 callCount 是 4（试探成功不再累计失败计数）
        assertEquals(4, callCount.get());
    }

    @Test
    void half_open_failure_returns_to_open_immediately() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        ModelProvider delegate = fake("p3", n -> {
            callCount.incrementAndGet();
            throw new RuntimeException("always-fail");
        });
        CircuitBreakerProvider cb = new CircuitBreakerProvider(delegate, 2,
                Duration.ofMillis(300), 1);
        // CLOSED → 2 次失败 → OPEN
        assertThrows(RuntimeException.class, () -> cb.chat("hi"));
        assertThrows(RuntimeException.class, () -> cb.chat("hi"));
        assertEquals(CircuitBreakerState.OPEN, cb.snapshot().state());
        assertEquals(2, callCount.get());

        // 等过期 → HALF_OPEN → 试探又失败 → 立即回 OPEN
        Thread.sleep(400);
        assertThrows(RuntimeException.class, () -> cb.chat("hi"));
        assertEquals(CircuitBreakerState.OPEN, cb.snapshot().state());
        // 此时再调一次，立即 OPEN 拒发（不再下发到 delegate）
        int before = callCount.get();
        assertThrows(CircuitOpenException.class, () -> cb.chat("hi"));
        assertEquals(before, callCount.get(), "OPEN 期间不应再下发到 delegate");
    }

    @Test
    void success_resets_consecutive_failures() throws Exception {
        AtomicInteger counter = new AtomicInteger();
        ModelProvider delegate = fake("p4", n -> {
            int c = counter.incrementAndGet();
            if (c == 2) throw new RuntimeException("one-fail");
            return "ok";
        });
        CircuitBreakerProvider cb = new CircuitBreakerProvider(delegate, 3,
                Duration.ofSeconds(30), 1);
        assertEquals("ok", cb.chat("a"));
        assertThrows(RuntimeException.class, () -> cb.chat("b"));
        assertEquals("ok", cb.chat("c"));
        // 失败计数应已重置为 0
        assertEquals(0, cb.snapshot().consecutiveFailures());
        assertEquals(CircuitBreakerState.CLOSED, cb.snapshot().state());
    }

    @Test
    void snapshot_remaining_open_ms_positive_while_open() {
        ModelProvider delegate = fake("p5", n -> { throw new RuntimeException("x"); });
        CircuitBreakerProvider cb = new CircuitBreakerProvider(delegate, 1,
                Duration.ofSeconds(60), 1);
        assertThrows(RuntimeException.class, () -> cb.chat("hi"));
        CircuitBreakerProvider.CircuitSnapshot snap = cb.snapshot();
        assertEquals(CircuitBreakerState.OPEN, snap.state());
        assertTrue(snap.remainingOpenMs() > 0 && snap.remainingOpenMs() <= 60_000,
                "剩余 OPEN 时间应在 (0, 60s] 之间");
    }
}