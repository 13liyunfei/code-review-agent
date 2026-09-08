package com.codereview.agent.core.analysis;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OSV 数据源的协议层验证——用 JDK HttpServer 桩住 OSV 响应（不依赖外网），
 * 断言真实响应结构（2026-09 实测）到内部模型的映射：
 * ecosystem 大小写 / Maven 全限定名透传 / database_specific.severity 归一 /
 * aliases 里提取 CVE 号 / 非 2xx 抛异常 / npm semver 前缀剥离。
 */
class OsvVulnSourceTest {

    private HttpServer server;
    private int port;
    private volatile String lastRequestBody = "";
    private volatile int statusToReturn = 200;
    private volatile String bodyToReturn = "{\"vulns\":[]}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/query", exchange -> {
            lastRequestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] resp = bodyToReturn.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusToReturn, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OsvVulnSource source() {
        return new OsvVulnSource("http://127.0.0.1:" + port + "/v1/query", "", 0,
                Duration.ofSeconds(5));
    }

    @Test
    void sendsMavenQualifiedNameAndEcosystem() throws Exception {
        bodyToReturn = "{\"vulns\":[]}";
        source().lookupAll(List.of(new ScaScanner.Component("maven", "log4j-core", "2.14.1",
                "org.apache.logging.log4j:log4j-core")));

        assertTrue(lastRequestBody.contains("\"name\":\"org.apache.logging.log4j:log4j-core\""),
                "Maven 应送全限定名: " + lastRequestBody);
        assertTrue(lastRequestBody.contains("\"ecosystem\":\"Maven\""), "ecosystem 应为 Maven 大写: " + lastRequestBody);
        assertTrue(lastRequestBody.contains("\"version\":\"2.14.1\""), "应带精确版本: " + lastRequestBody);
    }

    @Test
    void stripsNpmSemverPrefixBeforeQuery() throws Exception {
        bodyToReturn = "{\"vulns\":[]}";
        source().lookupAll(List.of(new ScaScanner.Component("npm", "lodash", "^4.17.20")));
        assertTrue(lastRequestBody.contains("\"version\":\"4.17.20\""),
                "npm semver 前缀应剥离: " + lastRequestBody);
    }

    @Test
    void mapsDatabaseSpecificSeverityToTiers() throws Exception {
        bodyToReturn = "{\"vulns\":["
                + "{\"id\":\"GHSA-1\",\"aliases\":[\"CVE-2021-44228\"],\"summary\":\"s1\","
                + "\"database_specific\":{\"severity\":\"CRITICAL\"}},"
                + "{\"id\":\"GHSA-2\",\"aliases\":[\"CVE-2022-00001\"],\"summary\":\"s2\","
                + "\"database_specific\":{\"severity\":\"MODERATE\"}}"
                + "]}";
        List<ScaScanner.Vulnerability> hits = source().lookupAll(
                List.of(new ScaScanner.Component("maven", "log4j-core", "2.14.1",
                        "org.apache.logging.log4j:log4j-core")));

        assertEquals(2, hits.size());
        assertEquals("BLOCKER", hits.get(0).severity(), "CRITICAL → BLOCKER");
        assertEquals("CVE-2021-44228", hits.get(0).cve(), "应从 aliases 提取 CVE");
        assertEquals("MINOR", hits.get(1).severity(), "MODERATE → MINOR");
    }

    @Test
    void fallsBackToGhsaIdWhenNoCveAlias() throws Exception {
        bodyToReturn = "{\"vulns\":[{\"id\":\"GHSA-abc-xyz\",\"aliases\":[],"
                + "\"database_specific\":{\"severity\":\"LOW\"}}]}";
        List<ScaScanner.Vulnerability> hits = source().lookupAll(
                List.of(new ScaScanner.Component("npm", "pkg-x", "1.0.0")));
        assertEquals(1, hits.size());
        assertEquals("GHSA-abc-xyz", hits.get(0).cve(), "无 CVE alias 时保留原 id");
    }

    @Test
    void missingSeverityFallsBackToMinor() throws Exception {
        bodyToReturn = "{\"vulns\":[{\"id\":\"GHSA-1\",\"summary\":\"x\"}]}";
        List<ScaScanner.Vulnerability> hits = source().lookupAll(
                List.of(new ScaScanner.Component("npm", "pkg-x", "1.0.0")));
        assertEquals("MINOR", hits.get(0).severity(), "无定级信息时保守 MINOR 而非 INFO");
    }

    @Test
    void httpErrorThrowsNotSilentEmpty() {
        statusToReturn = 500;
        OsvVulnSource s = source();
        assertThrows(IOException.class, () -> s.lookupAll(
                        List.of(new ScaScanner.Component("npm", "pkg-x", "1.0.0"))),
                "HTTP 失败必须抛异常交由上层降级，不得假装无漏洞");
    }

    @Test
    void unknownEcosystemSkipped() throws Exception {
        List<ScaScanner.Vulnerability> hits = source().lookupAll(
                List.of(new ScaScanner.Component("rust", "crate-x", "1.0.0")));
        assertTrue(hits.isEmpty());
        assertEquals("", lastRequestBody, "未知生态不应发请求");
    }

    // ---- 纯函数 ----

    @Test
    void normalizeVersionRules() {
        assertEquals("1.2.3", OsvVulnSource.normalizeVersion("^1.2.3"));
        assertEquals("1.2.3", OsvVulnSource.normalizeVersion("~1.2.3"));
        assertEquals("2.14.1-RC1", OsvVulnSource.normalizeVersion("2.14.1-RC1"), "预发布后缀保留");
        assertEquals(null, OsvVulnSource.normalizeVersion(">=1.0.0 <2.0.0"), "range 不可查");
        assertEquals(null, OsvVulnSource.normalizeVersion("1.x"), "通配不可查");
        assertEquals(null, OsvVulnSource.normalizeVersion("${revision}"), "变量不可查");
        assertEquals(null, OsvVulnSource.normalizeVersion(null));
    }
}
