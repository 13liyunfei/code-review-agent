package com.codereview.agent.core.tokenfactory;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 公司级 Token 工厂接入配置（{@code token-factory.*}）。
 *
 * <p><b>默认关闭</b>：不配置即完全不生效，{@code code-review-agent} 的行为与接入前一致
 * （直连 {@code tokenhub.*} 声明的上游）。这条「关掉就彻底没有」的边界必须清楚——
 * 一个半接半不接的网关最难排查。
 *
 * <p><b>为什么接入工厂而不是各自管 Key</b>：
 * <ul>
 *   <li>密钥不落地：本系统只持有工厂签发的 AK，真实厂商 Key 只在工厂侧配置；</li>
 *   <li>计量集中：本系统不再自己算钱，改由工厂按统一计价规则出账；</li>
 *   <li>额度可控：工厂侧可对本系统下发日/月额度，超限由工厂 REJECT；</li>
 *   <li>容灾由工厂兜底：供应商 failover、熔断、重试都在工厂侧完成，本系统只保留轻量韧性层。</li>
 * </ul>
 *
 * <p>配置示例：
 * <pre>{@code
 * token-factory:
 *   enabled: true
 *   base-url: http://token-factory:8090
 *   access-key: ${TOKEN_FACTORY_KEY:}
 *   app-id: code-review-agent
 *   alias: default
 *   priority: true            # true=工厂优先（失败才回落直连）；false=直连优先
 *   report-direct-usage: true # 直连上游时把用量补报回工厂
 * }</pre>
 */
@ConfigurationProperties(prefix = "token-factory")
public class TokenFactoryProperties {

    /** 是否启用工厂接入。 */
    private boolean enabled = false;

    /** 工厂地址（不含结尾斜杠）。 */
    private String baseUrl = "http://localhost:8090";

    /** 工厂签发的 access key。留空则本系统拒绝启用（fail-fast，避免配了开关却没配凭据）。 */
    private String accessKey = "";

    /** 上报给工厂的应用标识，用于按应用拆分用量。 */
    private String appId = "code-review-agent";

    /** 工厂侧的模型别名（工厂负责把它路由到真实供应商）。 */
    private String alias = "default";

    /**
     * 工厂供应商在候选列表中的位置。
     * {@code true}=排第一（优先走工厂，工厂全挂才回落直连）；
     * {@code false}=排最后（默认直连，工厂只做备用）。
     */
    private boolean priority = true;

    /** 直连上游时是否把用量补报回工厂（关掉会让账出现缺口）。 */
    private boolean reportDirectUsage = true;

    /** 单次请求超时（秒）。审查 prompt 可能很长，默认给到 120s。 */
    private long timeoutSeconds = 120;

    /** 连接超时（毫秒）——工厂不可达时要快速失败，别拖住整条审查链路。 */
    private long connectTimeoutMs = 3000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String getAlias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public boolean isPriority() {
        return priority;
    }

    public void setPriority(boolean priority) {
        this.priority = priority;
    }

    public boolean isReportDirectUsage() {
        return reportDirectUsage;
    }

    public void setReportDirectUsage(boolean reportDirectUsage) {
        this.reportDirectUsage = reportDirectUsage;
    }

    public long getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(long timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    /** 是否具备启用条件：开了开关且配了 AK。 */
    public boolean usable() {
        return enabled && accessKey != null && !accessKey.isBlank();
    }
}
