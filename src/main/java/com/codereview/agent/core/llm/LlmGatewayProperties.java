package com.codereview.agent.core.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型网关配置（熔断器 / 重试 / 路由策略）。
 *
 * <p>对应 application.yml 的 {@code review.llm.gateway.*} 段，全部有合理默认值，
 * 不显式配置也能工作（与历史行为一致：失败立刻切下一供应商，无熔断无重试）。
 * 启用熔断/重试后相当于在网关层加了一道「快速失败 + 暂时隔离」保护，
 * 是面向 JD「高可用」「故障降级」诉求的核心能力。
 */
@ConfigurationProperties("review.llm.gateway")
public class LlmGatewayProperties {

    /** 熔断器配置。 */
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    /** 重试退避配置。 */
    private Retry retry = new Retry();

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    /** 熔断器（Circuit Breaker）。 */
    public static class CircuitBreaker {
        /** 是否启用。关闭时 ModelGateway 行为与历史一致（失败立即切下一供应商）。 */
        private boolean enabled = true;
        /** 连续失败次数阈值；达到后进入 OPEN。 */
        private int failureThreshold = 5;
        /** OPEN 状态持续时间（秒）。到时转入 HALF_OPEN 允许试探。 */
        private long openSeconds = 30;
        /** HALF_OPEN 状态下允许的最大并发试探次数。 */
        private int halfOpenMaxTrials = 1;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public long getOpenSeconds() { return openSeconds; }
        public void setOpenSeconds(long openSeconds) { this.openSeconds = openSeconds; }
        public int getHalfOpenMaxTrials() { return halfOpenMaxTrials; }
        public void setHalfOpenMaxTrials(int halfOpenMaxTrials) { this.halfOpenMaxTrials = halfOpenMaxTrials; }
    }

    /** 重试退避策略。 */
    public static class Retry {
        /** 是否启用。 */
        private boolean enabled = true;
        /** 同一供应商上的最大尝试次数（含首次调用）。 */
        private int maxAttempts = 3;
        /** 初始退避（毫秒）。 */
        private long initialBackoffMs = 200;
        /** 最大退避上限（毫秒）。 */
        private long maxBackoffMs = 2000;
        /** 退避倍数（线性 1.0 = 固定间隔；指数 2.0 = 翻倍）。 */
        private double backoffMultiplier = 2.0;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getInitialBackoffMs() { return initialBackoffMs; }
        public void setInitialBackoffMs(long initialBackoffMs) { this.initialBackoffMs = initialBackoffMs; }
        public long getMaxBackoffMs() { return maxBackoffMs; }
        public void setMaxBackoffMs(long maxBackoffMs) { this.maxBackoffMs = maxBackoffMs; }
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
    }
}