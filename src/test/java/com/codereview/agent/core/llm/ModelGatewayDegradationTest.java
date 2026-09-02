package com.codereview.agent.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-2 修复验证：模型网关在所有供应商（含兜底）失败时不再静默返回空串，
 * 而是显式抛出 {@link ModelUnavailableException}——上层据此把该 Agent 标记为降级。
 *
 * <p>修复前：{@code return ""} 让上层把「模型不可用」解析成「0 条发现」，
 * 最终产出一份看起来完全通过的假报告。静默失败比显式报错危险得多。
 */
class ModelGatewayDegradationTest {

    /**
     * 可编排的供应商替身：按调用次序依次返回 responses 中的值；
     * 值为 {@link Throwable} 时抛异常（作为该次调用失败）；超出则复用最后一个。
     */
    private static class StubProvider implements ModelProvider {
        private final String name;
        private final boolean available;
        private final Object[] responses;
        private final AtomicInteger calls = new AtomicInteger();

        private StubProvider(String name, boolean available, Object[] responses) {
            this.name = name;
            this.available = available;
            this.responses = responses.length == 0 ? new Object[]{"ok"} : responses;
        }

        /** 可用的供应商。 */
        static StubProvider up(String name, Object... responses) {
            return new StubProvider(name, true, responses);
        }

        /** 不可用的供应商（网关应跳过，不计入尝试次数）。 */
        static StubProvider down(String name) {
            return new StubProvider(name, false, new Object[]{"NOPE"});
        }

        @Override public String name() { return name; }
        @Override public boolean available() { return available; }
        @Override public String chat(String prompt) throws Exception {
            int idx = Math.min(calls.getAndIncrement(), responses.length - 1);
            Object r = responses[idx];
            if (r instanceof Exception e) {
                throw e;
            }
            return String.valueOf(r);
        }
    }

    @Test
    void allProvidersFailWithoutMockFallbackThrowsExplicitly() {
        ModelGateway gateway = new ModelGateway(List.of(
                StubProvider.up("real-a", new RuntimeException("连接超时")),
                StubProvider.up("real-b", new RuntimeException("HTTP 500"))), 60, false);

        ModelUnavailableException ex = assertThrows(ModelUnavailableException.class,
                () -> gateway.chat("hello"));
        assertEquals(2, ex.providerCount());
        assertEquals(2, ex.attempts());
        assertTrue(ex.getMessage().contains("均不可用"), "异常消息应说明全部失败，实际：" + ex.getMessage());
        assertEquals(1, gateway.degradationStats().totalFailures(), "彻底失败应累计");
        assertEquals(0, gateway.degradationStats().mockFallbacks());
    }

    @Test
    void allProvidersFailEvenWhenFallbackAllowedButNoMockConfigured() {
        // 默认（兼容）构造允许 Mock 兜底，但列表里没有 mock → 仍必须抛异常而不是返回空串
        ModelGateway gateway = new ModelGateway(List.of(
                StubProvider.up("real-a", new RuntimeException("down"))), 60, true);

        assertThrows(ModelUnavailableException.class, () -> gateway.chat("hello"));
        assertEquals(1, gateway.degradationStats().totalFailures());
    }

    @Test
    void fallbackToMockCountsAndReturnsWhenRealProvidersFail() {
        // mock 第一次调用在常规轮询中失败，随后走显式兜底分支重试成功 → mockFallbacks 累计
        ModelGateway gateway = new ModelGateway(List.of(
                StubProvider.up("real-a", new RuntimeException("down")),
                StubProvider.up("mock", new RuntimeException("首次调用失败"), "MOCK-OK")), 60, true);

        String out = gateway.chat("hello");
        assertEquals("MOCK-OK", out);
        assertEquals(1, gateway.degradationStats().mockFallbacks(), "走 Mock 兜底应计数");
        assertEquals(0, gateway.degradationStats().totalFailures());
        assertTrue(gateway.degradationStats().degraded(), "走兜底即视为降级（结论不可信）");
    }

    @Test
    void happyPathReturnsFirstAvailableProviderWithNoDegradation() {
        ModelGateway gateway = new ModelGateway(List.of(
                StubProvider.up("real-a", "REAL-A-OK"),
                StubProvider.up("real-b", new RuntimeException("不该被调用"))), 60, false);

        assertEquals("REAL-A-OK", gateway.chat("hello"));
        assertFalse(gateway.degradationStats().degraded(), "成功调用不应有任何降级计数");
    }

    @Test
    void unavailableProviderIsSkippedAndNotCountedAsAttempt() {
        ModelGateway gateway = new ModelGateway(List.of(
                StubProvider.down("offline"),
                StubProvider.up("real", "REAL-OK")), 60, false);

        assertEquals("REAL-OK", gateway.chat("hello"));
        ModelGateway.DegradationStats stats = gateway.degradationStats();
        assertFalse(stats.degraded());
    }

    @Test
    void mockFallbackDisabledHasZeroCountsAfterFailure() {
        // allowMockFallback=false 且全部失败：totalFailures=1、mockFallbacks=0
        ModelGateway gateway = new ModelGateway(List.of(
                StubProvider.up("real-a", new RuntimeException("x"))), 60, false);
        assertThrows(ModelUnavailableException.class, () -> gateway.chat("hi"));
        ModelGateway.DegradationStats stats = gateway.degradationStats();
        assertEquals(1, stats.totalFailures());
        assertEquals(0, stats.mockFallbacks());
        assertTrue(stats.degraded());
    }
}
