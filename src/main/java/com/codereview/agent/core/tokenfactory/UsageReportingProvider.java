package com.codereview.agent.core.tokenfactory;

import com.codereview.agent.core.llm.ModelProvider;
import com.codereview.agent.core.llm.UsageAwareModelProvider;

import java.util.Objects;

/**
 * 用量补报装饰器：包在「直连上游」的供应商外面，调用成功后把用量补报回 Token 工厂。
 *
 * <p><b>只装饰直连供应商，不装饰工厂供应商</b>：走工厂的调用工厂自己会计量，
 * 再补报一次就是重复计费。装配层负责区分。
 *
 * <p><b>估算兜底</b>：上游没返回 usage 时按字符数粗估（中文 1 字≈1 token、英文 4 字符≈1 token），
 * 并打上 {@code estimated=true}。宁可要一个带标记的估算值，也不要账上记 0——
 * 0 会被当成「没花钱」，而估算值至少能被对账时识别出来。
 */
public class UsageReportingProvider implements ModelProvider {

    /** 估算系数：英文约 4 字符/token，中文约 1.5 字符/token，取折中值。 */
    private static final double CHARS_PER_TOKEN = 3.0d;

    private final UsageAwareModelProvider delegate;
    private final UsageReporter reporter;
    private final String alias;
    private final String upstreamModel;

    public UsageReportingProvider(UsageAwareModelProvider delegate, UsageReporter reporter,
                                  String alias, String upstreamModel) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.alias = alias;
        this.upstreamModel = upstreamModel == null ? delegate.name() : upstreamModel;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public boolean available() {
        return delegate.available();
    }

    @Override
    public String chat(String prompt) throws Exception {
        long t0 = System.currentTimeMillis();
        try {
            UsageAwareModelProvider.ChatOutcome outcome = delegate.chatWithUsage(prompt);
            long latency = System.currentTimeMillis() - t0;
            if (outcome.hasUsage()) {
                reporter.reportSuccess(TokenFactoryChatProvider.currentTraceId(), alias, name(),
                        upstreamModel, outcome.promptTokensOrZero(), outcome.completionTokensOrZero(),
                        latency, false);
            } else {
                int estimatedIn = estimate(prompt);
                int estimatedOut = estimate(outcome.text());
                reporter.reportSuccess(TokenFactoryChatProvider.currentTraceId(), alias, name(),
                        upstreamModel, estimatedIn, estimatedOut, latency, true);
            }
            return outcome.text();
        } catch (Exception e) {
            reporter.reportFailure(TokenFactoryChatProvider.currentTraceId(), alias, name(),
                    upstreamModel, System.currentTimeMillis() - t0, errorCodeOf(e));
            throw e;
        }
    }

    /** 错误码取异常类简名：够短、够区分，且不会把可能含敏感信息的 message 发到工厂。 */
    private static String errorCodeOf(Throwable e) {
        return e == null ? "UNKNOWN" : e.getClass().getSimpleName();
    }

    static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / CHARS_PER_TOKEN));
    }
}
