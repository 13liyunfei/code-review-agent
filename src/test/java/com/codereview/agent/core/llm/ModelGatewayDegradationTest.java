package com.codereview.agent.core.llm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-2 修复验证：模型网关在所有供应商失败时不再静默返回空串、也不降级到任何 Mock，
 * 而是显式抛出 {@link ModelUnavailableException}——上层据此把该 Agent 标记为降级。
 *
 * <p>修复前：{@code return ""} 让上层把「模型不可用」解析成「0 条发现」，
 * 最终产出一份看起来完全通过的假报告。静默失败比显式报错危险得多。
 *
 * <p>2026-09-03：Mock 兜底已全量移除——网关不再包含 mock 供应商，也不存在兜底分支；
 * 任何 mock 相关用例（fallbackToMockCounts… 等 3 例）随代码一并删除。
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
    void allProvidersFailThrowsExplicitlyWithoutAnyMock() {
        ModelGateway gateway = new ModelGateway(List.of(
                StubProvider.up("real-a", new RuntimeException("连接超时")),
                StubProvider.up("real-b", new RuntimeException("HTTP 500"))), 60);

        ModelUnavailableException ex = assertThrows(ModelUnavailableException.class,
                () -> gateway.chat("hello"));
        assertEquals(2, ex.providerCount());
        assertEquals(2, ex.attempts());
        assertTrue(ex.getMessage().contains("均不可用"), "异常消息应说明全部失败，实际：" + ex.getMessage());
        assertFalse(ex.getMessage().contains("mock"), "异常消息不应再提及 Mock 兜底");
        assertEquals(1, gateway.degradationStats().totalFailures(), "彻底失败应累计");
        assertTrue(gateway.degradationStats().degraded());
    }

    @Test
    void happyPathReturnsFirstAvailableProviderWithNoDegradation() {
        ModelGateway gateway = new ModelGateway(List.of(
                StubProvider.up("real-a", "REAL-A-OK"),
                StubProvider.up("real-b", new RuntimeException("不该被调用"))), 60);

        assertEquals("REAL-A-OK", gateway.chat("hello"));
        assertFalse(gateway.degradationStats().degraded(), "成功调用不应有任何降级计数");
    }

    @Test
    void unavailableProviderIsSkippedAndNotCountedAsAttempt() {
        ModelGateway gateway = new ModelGateway(List.of(
                StubProvider.down("offline"),
                StubProvider.up("real", "REAL-OK")), 60);

        assertEquals("REAL-OK", gateway.chat("hello"));
        assertFalse(gateway.degradationStats().degraded());
    }

    @Test
    void failureMessageCarriesAttemptAndTotalProviderCounts() {
        // 不可用供应商不计入尝试次数，但计入总供应商数——消息应如实反映两者
        ModelGateway gateway = new ModelGateway(List.of(
                StubProvider.down("offline"),
                StubProvider.up("real-a", new RuntimeException("x"))), 60);
        ModelUnavailableException ex = assertThrows(ModelUnavailableException.class,
                () -> gateway.chat("hi"));
        assertEquals(2, ex.providerCount());
        assertEquals(1, ex.attempts());
    }
}
