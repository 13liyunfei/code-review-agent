package com.codereview.agent.core.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * OSV（Google Open Source Vulnerabilities）真实漏洞库数据源。
 *
 * <p>逐组件 POST {@code https://api.osv.dev/v1/query}（请求带精确版本，服务端判断
 * 是否落在受影响区间），返回完整漏洞对象（含 aliases / database_specific.severity）。
 *
 * <p><b>为什么不用 querybatch</b>：实测 querybatch 的 vulns 条目只含
 * {@code {id, modified}} 摘要，拿不到 summary/severity/aliases，仍须逐个回查，
 * 反而多一轮网络往返；PR diff 的组件数通常有限，逐组件 /v1/query 一次到位。
 *
 * <p><b>协议事实（2026-09 实测）</b>：
 * <ul>
 *   <li>Maven 的包名必须是<b>全限定 {@code groupId:artifactId}</b>（如
 *       {@code org.apache.logging.log4j:log4j-core}），短名 {@code log4j-core} 查不中；</li>
 *   <li>ecosystem 为 {@code Maven} / {@code npm}（首字母大写）；</li>
 *   <li>severity 最稳的来源是 {@code database_specific.severity}
 *       （CRITICAL/HIGH/MODERATE/LOW），severity[] 的 score 是 CVSS vector 字符串不含数值。</li>
 * </ul>
 *
 * <p><b>代理</b>：OSV 为 Google 托管，本机（Clash 代理）才可达；构造参数可指定
 * 代理 host/port，仅本数据源的 HttpClient 走代理，不影响内网直连的其它出站调用。
 * 网络失败以异常上抛，由 {@link ScaScanner} 决定降级——此处绝不吞掉假装「无漏洞」。
 */
public final class OsvVulnSource implements ScaVulnSource {

    private static final Logger log = LoggerFactory.getLogger(OsvVulnSource.class);

    private static final URI OSV_QUERY_URI = URI.create("https://api.osv.dev/v1/query");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CVE = Pattern.compile("^CVE-\\d{4}-\\d{4,}$");

    /** 无 CVSS 可读时的保守档位：有漏洞记录但无法定级，按 MINOR 提示而非 INFO 过滤掉。 */
    private static final String FALLBACK_SEVERITY = "MINOR";

    private final URI endpoint;
    private final HttpClient client;
    private final Duration timeout;

    /**
     * @param proxyHost 代理主机（如 {@code 127.0.0.1}）；空/空白 = 直连（部署环境能直连 OSV 时）
     * @param proxyPort 代理端口
     * @param timeout   单次请求超时
     */
    public OsvVulnSource(String proxyHost, int proxyPort, Duration timeout) {
        this(OSV_QUERY_URI.toString(), proxyHost, proxyPort, timeout);
    }

    /** 测试专用：endpoint 可指向本地桩服务器（默认构造指向真实 OSV）。 */
    OsvVulnSource(String endpoint, String proxyHost, int proxyPort, Duration timeout) {
        this.endpoint = URI.create(endpoint);
        this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        HttpClient.Builder b = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // 代理链路 HTTP/2 CONNECT 常出 framing 错
                .connectTimeout(this.timeout);
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            b.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        this.client = b.build();
    }

    @Override
    public String id() {
        return "osv";
    }

    @Override
    public List<ScaScanner.Vulnerability> lookupAll(List<ScaScanner.Component> components) throws Exception {
        List<ScaScanner.Vulnerability> out = new ArrayList<>();
        if (components == null) {
            return out;
        }
        for (ScaScanner.Component c : components) {
            String eco = osvEcosystem(c.ecosystem());
            String ver = normalizeVersion(c.version());
            if (eco == null || ver == null) {
                continue; // 不认识的生态 / 非精确版本（range/变量）——OSV 无法按名查，跳过
            }
            String name = (c.osvName() == null || c.osvName().isBlank()) ? c.name() : c.osvName();
            out.addAll(queryOne(c, eco, name, ver));
        }
        return out;
    }

    // ===================== 单组件查询 =====================

    private List<ScaScanner.Vulnerability> queryOne(ScaScanner.Component component,
                                                    String ecosystem, String name, String version)
            throws Exception {
        String body = String.format(
                "{\"package\":{\"name\":%s,\"ecosystem\":%s},\"version\":%s}",
                quote(name), quote(ecosystem), quote(version));
        HttpRequest req = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new java.io.IOException(
                    "OSV 查询失败 HTTP " + resp.statusCode() + " (" + name + "@" + version + ")");
        }

        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode vulns = root.path("vulns");
        if (!vulns.isArray()) {
            return List.of();
        }
        List<ScaScanner.Vulnerability> hits = new ArrayList<>();
        for (JsonNode v : vulns) {
            String id = v.path("id").asText(null);
            if (id == null || id.isBlank()) {
                continue;
            }
            String cveId = firstCve(v.path("aliases"));
            String description = describe(v);
            hits.add(new ScaScanner.Vulnerability(
                    component, cveId != null ? cveId : id, severityOf(v), description));
        }
        log.debug("[SCA-osv] {}@{}（{}）命中 {} 条", name, version, ecosystem, hits.size());
        return hits;
    }

    // ===================== 响应解析 =====================

    /** aliases 里优先取 CVE 号（比 GHSA id 对用户更有辨识度）；无 CVE 用原 id。 */
    private static String firstCve(JsonNode aliases) {
        if (aliases != null && aliases.isArray()) {
            for (JsonNode a : aliases) {
                String s = a.asText("");
                if (CVE.matcher(s).matches()) {
                    return s;
                }
            }
        }
        return null;
    }

    /**
     * 严重度归一：BLOCKER/MAJOR/MINOR/INFO（对齐 {@code Severity} 档位）。
     *
     * <p>来源优先级：{@code database_specific.severity}（GitHub Advisory 四档文本）
     * → 无则保守 MINOR（有漏洞记录但无定级信息）。OSV 的 severity[] 里 score 是
     * CVSS vector 字符串（不含 base score），无法直接算分，故不采用。
     */
    static String severityOf(JsonNode v) {
        JsonNode db = v.path("database_specific").path("severity");
        if (db.isTextual()) {
            String s = switch (db.asText().toUpperCase()) {
                case "CRITICAL" -> "BLOCKER";
                case "HIGH" -> "MAJOR";
                case "MODERATE", "MEDIUM" -> "MINOR";
                case "LOW" -> "MINOR";
                default -> FALLBACK_SEVERITY;
            };
            return s;
        }
        return FALLBACK_SEVERITY;
    }

    /** summary 优先，空则截取 details 前 200 字符。 */
    private static String describe(JsonNode v) {
        String summary = v.path("summary").asText("");
        if (!summary.isBlank()) {
            return summary;
        }
        String details = v.path("details").asText("");
        if (!details.isBlank()) {
            return details.length() <= 200 ? details : details.substring(0, 200) + "…";
        }
        return "(无描述)";
    }

    // ===================== 协议映射 =====================

    /** 内部生态名 → OSV ecosystem（大小写敏感，实测确认）。 */
    private static String osvEcosystem(String ecosystem) {
        if (ecosystem == null) {
            return null;
        }
        return switch (ecosystem.toLowerCase()) {
            case "maven" -> "Maven";
            case "npm" -> "npm";
            default -> null;
        };
    }

    /**
     * 版本规范化：剥掉 npm semver 前缀（{@code ^1.2.3 / ~1.2.3 → 1.2.3}），
     * 以声明的下界版本送查——命中即保守提示，不会把已修复版本误报。
     * range（含空格、&gt;、&lt;、星号、x）、变量、空值一律返回 null（OSV 只接受具体版本）。
     */
    static String normalizeVersion(String version) {
        if (version == null) {
            return null;
        }
        String v = version.trim();
        if (v.startsWith("^") || v.startsWith("~")) {
            v = v.substring(1);
        }
        if (v.isEmpty()) {
            return null;
        }
        if (!v.matches("[0-9]+(\\.[0-9]+){0,3}([-+][0-9A-Za-z.\\-]+)?")) {
            return null; // 含 range/通配/变量，无法精确查询
        }
        return v;
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
