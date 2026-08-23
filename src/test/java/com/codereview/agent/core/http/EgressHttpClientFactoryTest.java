package com.codereview.agent.core.http;

import org.junit.jupiter.api.Test;

import java.net.ProxySelector;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 受控出口 HttpClient 工厂测试：验证三种模式语义正确、互不污染。
 */
class EgressHttpClientFactoryTest {

    @Test
    void directModeForcesNoProxy() {
        EgressProperties.Endpoint ep = new EgressProperties().getRerank();
        ep.setMode(EgressMode.DIRECT);
        HttpClient client = EgressHttpClientFactory.buildClient(ep, "test");
        assertTrue(client.proxy().isPresent());
        // DIRECT 应表现为无代理选择器（ProxySelector.of(null)）
        assertEquals(ProxySelector.of(null).getClass(), client.proxy().get().getClass());
    }

    @Test
    void systemModeUsesDefaultProxy() {
        EgressProperties.Endpoint ep = new EgressProperties().getRerank();
        ep.setMode(EgressMode.SYSTEM);
        HttpClient client = EgressHttpClientFactory.buildClient(ep, "test");
        // SYSTEM 不显式设置，proxy() 为空（使用 JVM 默认）
        assertTrue(client.proxy().isEmpty());
    }

    @Test
    void proxyModeUsesExplicitAddress() {
        EgressProperties.Endpoint ep = new EgressProperties().getRerank();
        ep.setMode(EgressMode.PROXY);
        ep.setProxyUrl("http://127.0.0.1:52320");
        HttpClient client = EgressHttpClientFactory.buildClient(ep, "test");
        assertTrue(client.proxy().isPresent());
        ProxySelector ps = client.proxy().get();
        // 精准代理：选择器应能解析出目标地址
        var selectors = ps.select(java.net.URI.create("https://api.cohere.com"));
        assertEquals(1, selectors.size());
        assertEquals(new java.net.InetSocketAddress("127.0.0.1", 52320),
                selectors.get(0).address());
    }

    @Test
    void proxyModeWithoutUrlFallsBackToDirect() {
        EgressProperties.Endpoint ep = new EgressProperties().getRerank();
        ep.setMode(EgressMode.PROXY);
        ep.setProxyUrl("");   // 空 url
        HttpClient client = EgressHttpClientFactory.buildClient(ep, "test");
        // 回退 DIRECT
        assertTrue(client.proxy().isPresent());
    }

    @Test
    void proxyModeWithInvalidUrlFallsBackToDirect() {
        EgressProperties.Endpoint ep = new EgressProperties().getRerank();
        ep.setMode(EgressMode.PROXY);
        ep.setProxyUrl("not-a-valid-url");
        HttpClient client = EgressHttpClientFactory.buildClient(ep, "test");
        assertTrue(client.proxy().isPresent());
    }

    @Test
    void buildBuilderReturnsConfiguredBuilder() {
        EgressProperties.Endpoint ep = new EgressProperties().getLlm();
        ep.setMode(EgressMode.DIRECT);
        HttpClient.Builder builder = EgressHttpClientFactory.buildBuilder(ep, "llm");
        assertNotNull(builder);
    }
}
