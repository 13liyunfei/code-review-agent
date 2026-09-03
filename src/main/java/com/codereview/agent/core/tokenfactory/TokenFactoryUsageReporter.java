package com.codereview.agent.core.tokenfactory;

import io.tokenfactory.client.TokenFactoryClient;
import io.tokenfactory.client.dto.UsageReportRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 把直连上游的用量补报到 Token 工厂。
 *
 * <p><b>补报失败绝不能影响业务</b>：这里所有异常都被吞掉并降级成日志。
 * 工厂已经不可用了（否则也不会走直连），此时再让补报失败把审查链路打断，
 * 等于把「一个服务挂了」放大成「两个服务都挂了」——典型的故障传染。
 *
 * <p><b>日志降噪</b>：连续失败超过阈值后从 WARN 降到 DEBUG。
 * 工厂长时间不可用时，逐条 WARN 会把真正有用的错误淹掉。
 */
public class TokenFactoryUsageReporter implements UsageReporter {

    private static final Logger log = LoggerFactory.getLogger(TokenFactoryUsageReporter.class);

    /** 连续失败超过这个次数后，日志降级为 DEBUG。 */
    private static final int NOISY_AFTER = 10;

    private final TokenFactoryClient client;
    private final String defaultAlias;
    private final AtomicLong consecutiveFailures = new AtomicLong();

    public TokenFactoryUsageReporter(TokenFactoryClient client, String defaultAlias) {
        this.client = client;
        this.defaultAlias = defaultAlias;
    }

    @Override
    public void reportSuccess(String traceId, String alias, String providerCode, String upstreamModel,
                              int promptTokens, int completionTokens, long latencyMs, boolean estimated) {
        try {
            UsageReportRequest request = new UsageReportRequest(
                    traceId,
                    alias == null || alias.isBlank() ? defaultAlias : alias,
                    providerCode, upstreamModel,
                    promptTokens, completionTokens, latencyMs,
                    true, null, estimated);
            client.reportUsage(request);
            consecutiveFailures.set(0);
        } catch (Exception e) {
            fail("补报用量失败", traceId, e);
        }
    }

    @Override
    public void reportFailure(String traceId, String alias, String providerCode, String upstreamModel,
                              long latencyMs, String errorCode) {
        try {
            UsageReportRequest request = UsageReportRequest.failure(
                    traceId,
                    alias == null || alias.isBlank() ? defaultAlias : alias,
                    providerCode, upstreamModel, latencyMs, errorCode);
            client.reportUsage(request);
            consecutiveFailures.set(0);
        } catch (Exception e) {
            fail("补报失败调用失败", traceId, e);
        }
    }

    private void fail(String what, String traceId, Exception e) {
        long failures = consecutiveFailures.incrementAndGet();
        if (failures <= NOISY_AFTER) {
            log.warn("{}（traceId={}，连续第 {} 次）：{}", what, traceId, failures, e.getMessage());
        } else {
            log.debug("{}（traceId={}，连续第 {} 次）：{}", what, traceId, failures, e.getMessage());
        }
    }
}
