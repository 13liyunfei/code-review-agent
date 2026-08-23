package com.codereview.agent.core.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 受控出口 HttpClient 工厂——大厂「显式出口管控」的最小统一实现。
 *
 * <p>根据 {@link EgressProperties.Endpoint} 精确构建 {@link HttpClient}，三种模式语义明确、
 * 互不污染：
 * <ul>
 *   <li>{@link EgressMode#DIRECT}：{@code ProxySelector.of(null)} 强制直连，忽略系统代理；</li>
 *   <li>{@link EgressMode#SYSTEM}：使用 JVM 默认 {@code ProxySelector}（继承系统 http.proxyHost 等）；</li>
 *   <li>{@link EgressMode#PROXY}：仅本客户端走 {@code proxy-url} 指定的代理，
 *       不影响进程内其他任何连接（内部服务、其他出站依赖均不受影响）。</li>
 * </ul>
 *
 * <p>这是业界「集中式 Integration Proxy / AI Gateway」思路的客户端侧落地：业务只声明出口策略，
 * 凭证与供应商路由由上游网关管控；本机开发可精准代理单个外部依赖而不劫持 localhost。
 */
public final class EgressHttpClientFactory {

    private static final Logger log = LoggerFactory.getLogger(EgressHttpClientFactory.class);

    private EgressHttpClientFactory() {
    }

    /**
     * 按出口配置构建 {@link HttpClient}（已带连接超时）。
     *
     * @param endpoint 出口端点配置（mode / proxy-url / connect-timeout-ms）
     * @param clientName 调用方标识（仅用于日志，如 "rerank" / "llm"）
     * @return 配置好的 HttpClient
     */
    public static HttpClient buildClient(EgressProperties.Endpoint endpoint, String clientName) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(endpoint.getConnectTimeoutMs()));
        applyProxy(builder, endpoint, clientName);
        return builder.build();
    }

    /**
     * 按出口配置返回 {@link HttpClient.Builder}（供 LangChain4j 等需要自建 client 的框架注入）。
     */
    public static HttpClient.Builder buildBuilder(EgressProperties.Endpoint endpoint, String clientName) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(endpoint.getConnectTimeoutMs()));
        applyProxy(builder, endpoint, clientName);
        return builder;
    }

    private static void applyProxy(HttpClient.Builder builder,
                                   EgressProperties.Endpoint endpoint, String clientName) {
        EgressMode mode = endpoint.getMode();
        switch (mode) {
            case DIRECT:
                builder.proxy(ProxySelector.of(null));
                log.debug("[Egress] {} 客户端：DIRECT 强制直连（忽略系统代理）", clientName);
                break;
            case SYSTEM:
                // 不显式设置即使用 JVM 默认 ProxySelector（继承系统代理）
                log.debug("[Egress] {} 客户端：SYSTEM 继承系统代理", clientName);
                break;
            case PROXY:
                String url = endpoint.getProxyUrl();
                if (url == null || url.isBlank()) {
                    log.warn("[Egress] {} 配置为 PROXY 但未提供 proxy-url，回退 DIRECT 直连", clientName);
                    builder.proxy(ProxySelector.of(null));
                    break;
                }
                ProxySelector ps = parseProxyUrl(url);
                if (ps == null) {
                    log.warn("[Egress] {} proxy-url 解析失败（{}），回退 DIRECT 直连", clientName, url);
                    builder.proxy(ProxySelector.of(null));
                    break;
                }
                builder.proxy(ps);
                log.info("[Egress] {} 客户端：PROXY 精准代理（{}，不劫持 localhost）", clientName, url);
                break;
            default:
                builder.proxy(ProxySelector.of(null));
        }
    }

    /**
     * 解析 {@code http://host:port} 或 {@code socks://host:port} 为 ProxySelector。
     * 仅支持 HTTP / SOCKS 两类；其他协议返回 null（触发回退）。
     */
    private static ProxySelector parseProxyUrl(String url) {
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port < 0) {
                return null;
            }
            java.net.Proxy.Type type;
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                type = java.net.Proxy.Type.HTTP;
            } else if ("socks".equalsIgnoreCase(scheme) || "socks5".equalsIgnoreCase(scheme)) {
                type = java.net.Proxy.Type.SOCKS;
            } else {
                return null;
            }
            return ProxySelector.of(new InetSocketAddress(host, port));
        } catch (Exception e) {
            log.warn("[Egress] proxy-url 解析异常（{}）：{}", url, e.getMessage());
            return null;
        }
    }
}
