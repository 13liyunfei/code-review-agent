package com.codereview.agent.core.tokenfactory;

/**
 * 用量上报出口。
 *
 * <p>存在的唯一理由：业务方在工厂不可用时会直连上游，这笔钱工厂计量不到。
 * 直连是必须的（不能因为工厂挂了就停摆），但账不能因此出现缺口——
 * 否则「用量下降」只是统计丢了，不是真的省了钱。
 */
public interface UsageReporter {

    UsageReporter NO_OP = new UsageReporter() {
        @Override
        public void reportSuccess(String traceId, String alias, String providerCode, String upstreamModel,
                                  int promptTokens, int completionTokens, long latencyMs, boolean estimated) {
            // 故意什么都不做
        }

        @Override
        public void reportFailure(String traceId, String alias, String providerCode, String upstreamModel,
                                  long latencyMs, String errorCode) {
            // 故意什么都不做
        }
    };

    /**
     * 上报一次成功的直连调用。
     *
     * @param estimated token 数是否为估算值（上游没返回 usage 时按字符数估）
     */
    void reportSuccess(String traceId, String alias, String providerCode, String upstreamModel,
                       int promptTokens, int completionTokens, long latencyMs, boolean estimated);

    /** 上报一次失败的直连调用（失败也要留痕，否则成功率会被算高）。 */
    void reportFailure(String traceId, String alias, String providerCode, String upstreamModel,
                       long latencyMs, String errorCode);
}
