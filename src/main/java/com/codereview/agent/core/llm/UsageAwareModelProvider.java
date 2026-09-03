package com.codereview.agent.core.llm;

/**
 * 能返回 token 用量的模型供应商。
 *
 * <p>{@link ModelProvider#chat(String)} 只回文本，这对纯审查链路够用，
 * 但对「把用量交给公司级 Token 工厂统一计费」不够——补报一条没有 token 数的用量，
 * 等于在账上记了一笔 0。凡是要接计量/计费的供应商，都必须实现本接口。
 *
 * <p>网关侧不强制：{@link ModelGateway} 只依赖 {@link ModelProvider}，
 * 是否需要用量由装配层（{@code ReviewAgentConfig}）按需读取。
 */
public interface UsageAwareModelProvider extends ModelProvider {

    /**
     * 发起调用并带上真实 token 用量。
     *
     * @throws Exception 调用失败（由网关做失败转移）
     */
    ChatOutcome chatWithUsage(String prompt) throws Exception;

    /**
     * 一次调用的结果。
     *
     * @param promptTokens     输入 token；为 {@code null} 表示上游未返回 usage
     * @param completionTokens 输出 token
     */
    record ChatOutcome(String text, Integer promptTokens, Integer completionTokens) {

        public static ChatOutcome textOnly(String text) {
            return new ChatOutcome(text, null, null);
        }

        /** 上游是否返回了可用用量；为 false 时只能退化为估算值。 */
        public boolean hasUsage() {
            return promptTokens != null || completionTokens != null;
        }

        public int promptTokensOrZero() {
            return promptTokens == null ? 0 : promptTokens;
        }

        public int completionTokensOrZero() {
            return completionTokens == null ? 0 : completionTokens;
        }
    }
}
