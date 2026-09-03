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

    private static ModelProvider fakeCircuitOpen(String name) {
        ModelProvider base = fakeAvailable(name);
        CircuitBreakerProvider cb = new CircuitBreakerProvider(base, 1,
                Duration.ofSeconds(60), 1);
        // 强制触发 OPEN
        try {
            cb.chat("force-fail-1");
        } catch (Exception ignored) {
            // expected
        }
        try {
            cb.chat("force-fail-2");
        } catch (Exception ignored) {
            // expected
        }
        return cb;
    }
}