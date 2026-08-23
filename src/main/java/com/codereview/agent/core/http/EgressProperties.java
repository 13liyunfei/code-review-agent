package com.codereview.agent.core.http;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 受控出口（egress）配置——所有外部 SaaS 出站依赖统一从此读取出口策略。
 *
 * <p>每个外部依赖（LLM、Rerank 等）通过独立的前缀声明自己的出口方式，
 * 互不干扰，也不影响内部服务。取代原先脆弱的全局 {@code bypass-system-proxy} 布尔。
 *
 * <p>配置示例（application.yml）：
 * <pre>
 *   review:
 *     egress:
 *       llm:                       # 大模型网关出口
 *         mode: direct             # direct | system | proxy
 *       rerank:                    # Rerank 重排出口
 *         mode: direct
 *         proxy-url: ${RERANK_PROXY:}   # 仅 mode=proxy 时生效
 * </pre>
 */
@ConfigurationProperties(prefix = "review.egress")
public class EgressProperties {

    /** 大模型网关（TokenHub / OpenAI 兼容）出口策略。 */
    private final Endpoint llm = new Endpoint();

    /** Rerank cross-encoder 出口策略。 */
    private final Endpoint rerank = new Endpoint();

    public Endpoint getLlm() {
        return llm;
    }

    public Endpoint getRerank() {
        return rerank;
    }

    /**
     * 单个外部端点的出口配置。
     */
    public static class Endpoint {
        /** 出口模式，默认直连（生产形态）。 */
        private EgressMode mode = EgressMode.DIRECT;

        /**
         * 显式代理地址，仅 {@link EgressMode#PROXY} 模式生效。
         * 形如 {@code http://127.0.0.1:52320}（开发机 Clash）或公司合规出口网关。
         */
        private String proxyUrl;

        /** 连接超时（毫秒），默认 5000。 */
        private int connectTimeoutMs = 5000;

        public EgressMode getMode() {
            return mode;
        }

        public void setMode(EgressMode mode) {
            this.mode = mode;
        }

        public String getProxyUrl() {
            return proxyUrl;
        }

        public void setProxyUrl(String proxyUrl) {
            this.proxyUrl = proxyUrl;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }
    }
}
