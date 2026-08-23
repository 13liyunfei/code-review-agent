package com.codereview.agent.core.http;

/**
 * 出站（egress）网络模式——大厂「受控出口」实践的核心抽象。
 *
 * <p>任何需要访问外部 SaaS（LLM / Rerank / 第三方 API）的依赖，都不再依赖「系统全局代理」
 * （如本机 Clash TUN 会劫持 localhost、干扰内部服务），而是显式声明自己的出口方式。
 * 内部服务（PostgreSQL / Redis / Gitea）始终直连，与本抽象无关，绝不承受代理副作用。
 *
 * <ul>
 *   <li>{@link #DIRECT}：强制直连（忽略系统代理）。<b>生产默认</b>——服务器在合规网络内直接出网，
 *       或 {@code base-url} 指向公司内网 AI Gateway（Envoy AI Gateway / LiteLLM Proxy 等），
 *       由网关统一持有供应商 key、做限流/重试/计费/审计；</li>
 *   <li>{@link #SYSTEM}：继承 JVM 系统代理（{@code http.proxyHost} 等）。仅在确实需要走机器级
 *       代理出网、且已确认不会干扰内部服务时使用；</li>
 *   <li>{@link #PROXY}：仅本出站请求走显式指定的代理地址（{@code proxy-url}），
 *       不影响其他任何连接。开发机经 Clash 等本地代理访问 Cohere/Jina 时使用，
 *       精准代理、不劫持 localhost。</li>
 * </ul>
 */
public enum EgressMode {
    /** 强制直连，忽略系统代理（生产推荐默认值）。 */
    DIRECT,
    /** 继承 JVM 系统代理设置。 */
    SYSTEM,
    /** 仅本出站请求走显式指定的代理地址（见 {@code proxy-url}）。 */
    PROXY
}
