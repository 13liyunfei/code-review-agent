package com.codereview.agent.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 熔断器装饰器：把任意 {@link ModelProvider} 包一层三态机。
 *
 * <p><b>解决的问题（对应 JD「高可用」「故障降级」）</b>：原 {@link ModelGateway}
 * 只在「单次调用内」按顺序 try 下一供应商，遇到连续失败的供应商每次都要
 * 重新建立连接、等超时才能跳过——既浪费 RTT，又在高峰时放大故障；
 * 熔断器让「已知不健康」的供应商在窗口期内直接拒绝下发，把 RTT 留给真正能用的供应商。
 *
 * <p><b>三态机</b>：见 {@link CircuitBreakerState}。OPEN 期间所有请求立即抛
 * {@link CircuitOpenException}，由 {@link ModelGateway} 视为「跳过本供应商」，
 * <b>不进入</b>{@link RetryClassifier} 重试路径——熔断本身就说明重试无意义。
 *
 * <p><b>线程安全</b>：状态转换走 {@code synchronized}；HALF_OPEN 试验并发限制走
 * {@link AtomicInteger}。{@link #available()} 不加锁，仅读 volatile 字段快照，
 * 接受轻微不一致（路由层是「软跳过」，不会因此打挂供应商）。
 */
public class CircuitBreakerProvider implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerProvider.class);

    private final ModelProvider delegate;
    private final int failureThreshold;
    private final Duration openDuration;
    private final int halfOpenMaxTrials;

    private volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant openedAt;
    private final AtomicInteger halfOpenInFlight = new AtomicInteger(0);

    /**
     * @param delegate          被包装的真实供应商
     * @param failureThreshold  连续失败阈值；达到后 OPEN
     * @param openDuration      OPEN 状态持续时间
     * @param halfOpenMaxTrials HALF_OPEN 状态允许的最大并发试探数
     */
    public CircuitBreakerProvider(ModelProvider delegate, int failureThreshold, Duration openDuration,
                                  int halfOpenMaxTrials) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate 不能为空");
        }
        if (failureThreshold < 1) {
            throw new IllegalArgumentException("failureThreshold 必须 >= 1");
        }
        if (openDuration == null || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("openDuration 必须 > 0");
        }
        if (halfOpenMaxTrials < 1) {
            throw new IllegalArgumentException("halfOpenMaxTrials 必须 >= 1");
        }
        this.delegate = delegate;
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.halfOpenMaxTrials = halfOpenMaxTrials;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    /**
     * 当前是否可用（路由层据此跳过 OPEN 中的供应商）。
     *
     * <p>HIGH-LEVEL 语义：
     * <ul>
     *   <li>CLOSED → 看 {@code delegate.available()}；</li>
     *   <li>OPEN 且尚未到 OPEN 持续时间 → 不可用；</li>
     *   <li>OPEN 到期 → 转入 HALF_OPEN；HALF_OPEN 视试探名额判定；</li>
     *   <li>HALF_OPEN 试探名额耗尽 → 不可用。</li>
     * </ul>
     */
    @Override
    public boolean available() {
        if (!delegate.available()) {
            return false;
        }
        CircuitBreakerState s = currentState();
        if (s == CircuitBreakerState.CLOSED) {
            return true;
        }
        if (s == CircuitBreakerState.OPEN) {
            return false;
        }
        // HALF_OPEN：试探名额未用完即可用
        return halfOpenInFlight.get() < halfOpenMaxTrials;
    }

    /**
     * 实际调用委托方；按当前状态决定是否下发。
     *
     * @throws CircuitOpenException 熔断中（OPEN），由网关跳过本供应商
     * @throws Exception            真实调用失败（透传给网关的失败转移/重试）
     */
    @Override
    public String chat(String prompt) throws Exception {
        CircuitBreakerState s = currentState();
        if (s == CircuitBreakerState.OPEN) {
            throw new CircuitOpenException(name(), openedAt, openDuration.getSeconds());
        }
        if (s == CircuitBreakerState.HALF_OPEN) {
            // 试探名额校验：超限拒绝（与 available() 保持一致语义，避免并发试探放飞）
            int inflight = halfOpenInFlight.incrementAndGet();
            if (inflight > halfOpenMaxTrials) {
                halfOpenInFlight.decrementAndGet();
                throw new CircuitOpenException(name(), openedAt, openDuration.getSeconds());
            }
        }
        try {
            String result = delegate.chat(prompt);
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure();
            throw e;
        }
    }

    /** 当前状态（含 OPEN→HALF_OPEN 的时间触发）。 */
    private CircuitBreakerState currentState() {
        if (state == CircuitBreakerState.OPEN && openedAt != null
                && Duration.between(openedAt, Instant.now()).compareTo(openDuration) >= 0) {
            synchronized (this) {
                if (state == CircuitBreakerState.OPEN
                        && openedAt != null
                        && Duration.between(openedAt, Instant.now()).compareTo(openDuration) >= 0) {
                    state = CircuitBreakerState.HALF_OPEN;
                    halfOpenInFlight.set(0);
                    log.info("[熔断器] {}: OPEN 到期 → HALF_OPEN（允许 {} 次试探）", name(), halfOpenMaxTrials);
                }
            }
        }
        return state;
    }

    /** 调用成功：清零失败计数；HALF_OPEN 成功 → CLOSED。 */
    private void onSuccess() {
        synchronized (this) {
            consecutiveFailures.set(0);
            if (state == CircuitBreakerState.HALF_OPEN) {
                halfOpenInFlight.decrementAndGet();
                state = CircuitBreakerState.CLOSED;
                log.info("[熔断器] {}: HALF_OPEN 试探成功 → CLOSED", name());
            }
        }
    }

    /** 调用失败：累计计数；HALF_OPEN 失败 → 立即回 OPEN。 */
    private void onFailure() {
        synchronized (this) {
            if (state == CircuitBreakerState.HALF_OPEN) {
                halfOpenInFlight.decrementAndGet();
                state = CircuitBreakerState.OPEN;
                openedAt = Instant.now();
                log.warn("[熔断器] {}: HALF_OPEN 试探失败 → 立即 OPEN（再持续 {}）", name(), openDuration);
                return;
            }
            int f = consecutiveFailures.incrementAndGet();
            if (f >= failureThreshold && state == CircuitBreakerState.CLOSED) {
                state = CircuitBreakerState.OPEN;
                openedAt = Instant.now();
                log.warn("[熔断器] {}: 连续失败 {} 次 ≥ 阈值 {} → OPEN（持续 {}）",
                        name(), f, failureThreshold, openDuration);
            }
        }
    }

    /** 健康快照（供监控端点暴露）。 */
    public CircuitSnapshot snapshot() {
        CircuitBreakerState s = currentState();
        long remainingMs = 0;
        if (s == CircuitBreakerState.OPEN && openedAt != null) {
            long elapsed = Duration.between(openedAt, Instant.now()).toMillis();
            remainingMs = Math.max(0, openDuration.toMillis() - elapsed);
        }
        return new CircuitSnapshot(name(), s, consecutiveFailures.get(), halfOpenInFlight.get(),
                openedAt, remainingMs, failureThreshold, openDuration.getSeconds());
    }

    /**
     * 不可变健康快照。{@code remainingOpenMs} 仅 OPEN 状态有意义（距允许试探的剩余毫秒）。
     */
    public record CircuitSnapshot(String providerName,
        CircuitBreakerState state,
        int consecutiveFailures,
        int halfOpenInFlight,
        Instant openedAt,
        long remainingOpenMs,
        int failureThreshold,
        long openSeconds) {
    }
}