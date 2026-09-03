package com.codereview.agent.core.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PriorityRouteStrategyTest {

    @Test
    void returns_first_available_in_order() {
        ModelProvider a = fakeAvailable("a");
        ModelProvider b = fakeAvailable("b");
        ModelProvider picked = new PriorityRouteStrategy().next(List.of(a, b));
        assertSame(a, picked);
    }

    @Test
    void skips_unavailable_and_picks_next() {
        ModelProvider a = fakeUnavailable("a");
        ModelProvider b = fakeAvailable("b");
        ModelProvider picked = new PriorityRouteStrategy().next(List.of(a, b));
        assertSame(b, picked);
    }

    @Test
    void skips_open_circuit_breaker() {
        ModelProvider a = fakeCircuitOpen("a");
        ModelProvider b = fakeAvailable("b");
        ModelProvider picked = new PriorityRouteStrategy().next(List.of(a, b));
        assertSame(b, picked, "OPEN 中的熔断供应商应被跳过");
    }

    @Test
    void returns_null_when_none_available() {
        ModelProvider picked = new PriorityRouteStrategy().next(List.of(
                fakeUnavailable("x"), fakeCircuitOpen("y")));
        assertNull(picked);
    }

    @Test
    void empty_list_returns_null() {
        assertNull(new PriorityRouteStrategy().next(List.of()));
        assertNull(new PriorityRouteStrategy().next(null));
    }

    private static ModelProvider fakeAvailable(String name) {
        return new ModelProvider() {
            @Override public String name() { return name; }
            @Override public boolean available() { return true; }
            @Override public String chat(String prompt) { return "ok-" + name; }
        };
    }

    private static ModelProvider fakeUnavailable(String name) {
        return new ModelProvider() {
            @Override public String name() { return name; }
            @Override public boolean available() { return false; }
            @Override public String chat(String prompt) { return "ok-" + name; }
        };
    }

    /**
     * 构造一个处于 OPEN 状态的熔断器供应商。
     *
     * <p><b>被包装的桩必须会失败</b>：这里曾用 {@link #fakeAvailable(String)}（永远返回成功），
     * 于是「强制触发 OPEN」的两步一次都没失败，熔断器始终 CLOSED——
     * 断言的是「OPEN 应被跳过」，实际测的是「CLOSED 不被跳过」，测试反而锁住了错误行为。
     */
    private static ModelProvider fakeCircuitOpen(String name) {
        ModelProvider base = fakeFailing(name);
        CircuitBreakerProvider cb = new CircuitBreakerProvider(base, 1,
                Duration.ofSeconds(60), 1);
        // 强制触发 OPEN（阈值=1，一次失败即开）
        try {
            cb.chat("force-fail-1");
            fail("桩供应商必须失败，否则熔断器永远不会 OPEN");
        } catch (Exception ignored) {
            // expected
        }
        try {
            cb.chat("force-fail-2");
        } catch (Exception ignored) {
            // expected
        }
        assertEquals(CircuitBreakerState.OPEN, cb.snapshot().state(),
                "前置条件：熔断必须已 OPEN，否则后面的断言没有意义");
        return cb;
    }

    private static ModelProvider fakeFailing(String name) {
        return new ModelProvider() {
            @Override public String name() { return name; }
            @Override public boolean available() { return true; }
            @Override public String chat(String prompt) {
                throw new IllegalStateException("stub-failure-" + name);
            }
        };
    }
}