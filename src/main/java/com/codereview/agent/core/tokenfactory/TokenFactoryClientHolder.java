package com.codereview.agent.core.tokenfactory;

import io.tokenfactory.client.TokenFactoryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Token 工厂客户端的惰性持有者。
 *
 * <p><b>为什么惰性</b>：工厂默认关闭（{@code token-factory.enabled=false}），
 * 绝大多数部署根本用不到它。如果在启动时无条件建客户端，等于给所有不需要工厂的
 * 部署都塞了一个不会用的 HTTP 连接池。
 *
 * <p><b>为什么用持有者而不是 {@code @ConditionalOnProperty}</b>：测试里要能塞入替身客户端，
 * 而条件装配的 bean 缺失时注入点会直接报 NoSuchBeanDefinition，反而更难测。
 */
@Component
public class TokenFactoryClientHolder {

    private static final Logger log = LoggerFactory.getLogger(TokenFactoryClientHolder.class);

    private final TokenFactoryProperties properties;
    private volatile TokenFactoryClient client;

    public TokenFactoryClientHolder(TokenFactoryProperties properties) {
        this.properties = properties;
    }

    /** 工厂启用时返回可用客户端；未启用返回 {@code null}。 */
    public TokenFactoryClient client() {
        if (!properties.usable()) {
            return null;
        }
        TokenFactoryClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                client = TokenFactoryClient.builder()
                        .baseUrl(properties.getBaseUrl())
                        .accessKey(properties.getAccessKey())
                        .appId(properties.getAppId())
                        // 链路 ID 与审查链路同源：工厂侧的用量明细能直接对上本系统的 traceId
                        .traceIdSupplier(TokenFactoryChatProvider::currentTraceId)
                        .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                        .requestTimeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                        .build();
                log.info("已接入公司级 Token 工厂：baseUrl={}, appId={}, alias={}",
                        properties.getBaseUrl(), properties.getAppId(), properties.getAlias());
            }
            return client;
        }
    }

    /**
     * 用量补报出口。工厂未启用、或显式关闭补报时返回 {@link UsageReporter#NO_OP}——
     * 调用方无需判空，避免出现「补报失败」的空指针打断审查主流程。
     */
    public UsageReporter reporter() {
        if (!properties.usable() || !properties.isReportDirectUsage()) {
            return UsageReporter.NO_OP;
        }
        return new TokenFactoryUsageReporter(client(), properties.getAlias());
    }

    /** 测试用：注入替身客户端。 */
    void overrideClient(TokenFactoryClient client) {
        this.client = client;
    }
}
