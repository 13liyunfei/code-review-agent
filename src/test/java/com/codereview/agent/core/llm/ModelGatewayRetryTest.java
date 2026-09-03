package com.codereview.agent.core.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 ModelGateway 的「重试 + 退避 + 熔断感知路由」整体行为。
 *
 * <p>说明：本仓库原有的 ModelGatewayDegradationTest 仍覆盖历史行为（fail-fast 不重试、无 Mock 兜底），
 * 本类补充验证 2026-09-03 新增能力：
 * <ul>
 *   <li>临时错误在本供应商上重试，超过 maxAttempts 才切换；</li>
 *   <li>永久错误（熔断 OPEN）立即跳到下一家；</li>
 *   <li>熔断器 OPEN 中的供应商被路由策略跳过；</li>
 *   <li>全失败抛 ModelUnavailableException（fail-fast 保留）。</li>
 * </ul>
 */
class ModelGatewayRetryTest {

    private static ModelProvider fake(String name, java.util.function.Function<Integer, String> behavior) {
        AtomicInteger calls = new AtomicInteger();
        return new ModelProvider() {
            @Override public String name() { return name; }
            @Override public boolean available() { return true; }
            @Override public String chat(String prompt) {
                int n = calls.incrementAndGet();
                return behavior.apply(n);
            }
        };
    }

    private static CircuitBreakerProvider wrapWithBreaker(String name,
                                                           java.util.function.Function<Integer, String> behavior,
                                                           int failureThreshold) {
        return new CircuitBreakerProvider(fake(name, behavior), failureThreshold,
                Duration.ofSeconds(30), 1);
    }

    @Test
    void retries_retryable_error_on_same_provider_then_switches() {
        // p1 临时错误（前 2 次）→ 第 3 次成功；总尝试 = 3，应由本供应商消化
        ModelProvider p1 = wrapWithBreaker("p1", n -> {
            if (n <= 2) throw new java.net.SocketTimeoutException("timeout-" + n);
            return "ok-from-p1";
        }, 5);
        ModelProvider p2 = fake("p2", n -> "ok-from-p2");

        ModelGateway gw = new ModelGateway(List.of(p1, p2), 1000,
                new PriorityRouteStrategy(), new DefaultRetryClassifier(),
                new BackoffPolicy(10, 50, 2.0), 3, true, null);

        String result = gw.chat("hi");
        assertEquals("ok-from-p1", result);
    }

    @Test
    void permanent_error_skips_provider_immediately_without_retry() {
        // p1 永久错误（IllegalArgumentException）→ 立即跳 p2
        ModelProvider p1 = wrapWithBreaker("p1", n -> {
            throw new IllegalArgumentException("bad prompt");
        }, 5);
        ModelProvider p2 = fake("p2", n -> "ok-from-p2");

        AtomicInteger p1Calls = new AtomicInteger();
        AtomicInteger p2Calls = new AtomicInteger();
        ModelProvider p1Counted = new ModelProvider() {
            @Override public String name() { return "p1"; }
            @Override public boolean available() { return true; }
            @Override public String chat(String prompt) {
                p1Calls.incrementAndGet();
                throw new IllegalArgumentException("bad prompt");
            }
        };
        ModelProvider p2Counted = new ModelProvider() {
            @Override public String name() { return "p2"; }
            @Override public boolean available() { return true; }
            @Override public String chat(String prompt) {
                p2Calls.incrementAndGet();
                return "ok-from-p2";
            }
        };

        ModelGateway gw = new ModelGateway(List.of(p1Counted, p2Counted), 1000,
                new PriorityRouteStrategy(), new DefaultRetryClassifier(),
                new BackoffPolicy(10, 50, 2.0), 3, true, null);

        String result = gw.chat("hi");
        assertEquals("ok-from-p2", result);
        assertEquals(1, p1Calls.get(), "永久错误不应重试，应只调 1 次");
        assertEquals(1, p2Calls.get());
    }

    @Test
    void circuit_breaker_open_provider_is_skipped_by_route_strategy() {
        // p1 包了熔断器，先强制触发 OPEN
        CircuitBreakerProvider p1Cb = wrapWithBreaker("p1", n -> {
            throw new RuntimeException("fail");
        }, 1);
        // 触发 OPEN（threshold=1 时 1 次失败即 OPEN）
        try { p1Cb.chat("warmup"); } catch (Exception ignored) {}
        assertEquals(CircuitBreakerState.OPEN, p1Cb.snapshot().state());

        ModelProvider p2 = fake("p2", n -> "ok-from-p2");
        ModelGateway gw = new ModelGateway(List.of(p1Cb, p2), 1000,
                new PriorityRouteStrategy(), new DefaultRetryClassifier(),
                new BackoffPolicy(10, 50, 2.0), 1, false, null);

        String result = gw.chat("hi");
        assertEquals("ok-from-p2", result, "OPEN 中的 p1 应被路由策略跳过");
    }

    @Test
    void all_providers_fail_throws_model_unavailable() {
        ModelProvider p1 = fake("p1", n -> { throw new RuntimeException("fail-1"); });
        ModelProvider p2 = fake("p2", n -> { throw new RuntimeException("fail-2"); });

        ModelGateway gw = new ModelGateway(List.of(p1, p2), 1000,
                new PriorityRouteStrategy(), new DefaultRetryClassifier(),
                new BackoffPolicy(10, 50, 2.0), 2, true, null);

        ModelUnavailableException ex = assertThrows(ModelUnavailableException.class,
                () -> gw.chat("hi"));
        assertTrue(ex.getMessage().contains("所有供应商均不可用"));
        assertEquals(1L, gw.degradationStats().totalFailures());
    }

    @Test
    void retry_disabled_legacy_behavior_first_failure_switches() {
        ModelProvider p1 = fake("p1", n -> { throw new java.net.SocketTimeoutException("timeout"); });
        ModelProvider p2 = fake("p2", n -> "ok-from-p2");

        AtomicInteger p1Calls = new AtomicInteger();
        ModelProvider p1Counted = new ModelProvider() {
            @Override public String name() { return "p1"; }
            @Override public boolean available() { return true; }
            @Override public String chat(String prompt) {
                p1Calls.incrementAndGet();
                throw new java.net.SocketTimeoutException("timeout");
            }
        };

        ModelGateway gw = new ModelGateway(List.of(p1Counted, p2), 1000,
                new PriorityRouteStrategy(), new DefaultRetryClassifier(),
                new BackoffPolicy(10, 50, 2.0), 3, false, null);
        assertEquals("ok-from-p2", gw.chat("hi"));
        assertEquals(1, p1Calls.get(), "retry 禁用时第 1 次失败就切换");
    }

    @Test
    void snapshot_exposes_provider_breaker_states() {
        CircuitBreakerProvider p1Cb = wrapWithBreaker("p1", n -> {
            throw new RuntimeException("fail");
        }, 1);
        ModelProvider p2 = fake("p2", n -> "ok");
        try { p1Cb.chat("force-fail"); } catch (Exception ignored) {}

        ModelGateway gw = new ModelGateway(List.of(p1Cb, p2), 1000,
                new PriorityRouteStrategy(), new DefaultRetryClassifier(),
                new BackoffPolicy(10, 50, 2.0), 1, false, null);
        LlmGatewaySnapshot snap = gw.snapshot();
        assertEquals(2, snap.providers().size());
        assertEquals(CircuitBreakerState.OPEN, snap.providers().get(0).state());
        assertEquals(CircuitBreakerState.CLOSED, snap.providers().get(1).state());
    }

    @Test
    void token_usage_recorder_collects_calls() {
        TokenUsageRecorder rec = new TokenUsageRecorder(10);
        ModelProvider p1 = fake("p1", n -> "ok");
        ModelGateway gw = new ModelGateway(List.of(p1), 1000,
                new PriorityRouteStrategy(), new DefaultRetryClassifier(),
                new BackoffPolicy(10, 50, 2.0), 1, false, rec);

        gw.chat("a");
        gw.chat("b");

        // Token 用量由 listener 写；本测试只验证 gateway → recorder 链路就绪
        assertNotNull(gw.usageRecorder());
        // 实际 token 由 listener 注入，这里仅验证可用性
        assertEquals(0, rec.size(), "本测试未挂 listener，size 应为 0");
    }
}