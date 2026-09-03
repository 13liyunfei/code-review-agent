package com.codereview.agent.core.llm;

import java.time.Instant;

/**
 * 熔断器开启时抛出的「快速失败」异常。
 *
 * <p>由 {@link ModelGateway} 视为「该供应商暂时不可用」，
 * 直接跳到下一供应商；不会进入「重试退避」流程（熔断已说明本端问题在重试 N 次无意义）。
 */
public class CircuitOpenException extends RuntimeException {

    private final String providerName;
    private final Instant openedAt;
    private final long openSeconds;

    public CircuitOpenException(String providerName, Instant openedAt, long openSeconds) {
        super(providerName + " 熔断中（OPEN，将于 " + openSeconds + "s 后转入 HALF_OPEN）");
        this.providerName = providerName;
        this.openedAt = openedAt;
        this.openSeconds = openSeconds;
    }

    public String getProviderName() {
        return providerName;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public long getOpenSeconds() {
        return openSeconds;
    }
}