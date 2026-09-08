package com.codereview.agent.core.analysis;

import com.codereview.agent.core.model.CodeDiff;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SCA（软件组成分析）依赖漏洞扫描器。
 *
 * <p>从 PR diff 中识别新增的 Maven / npm 依赖，交给可插拔的 {@link ScaVulnSource}
 * 查询已知漏洞，并做许可证黑名单检查、生成 SBOM（CycloneDX-lite JSON）。
 *
 * <p><b>数据源</b>：生产 / 开发 / 测试默认走 {@link OsvVulnSource}（OSV 真实漏洞库），
 * 由 {@code ScaSourceConfig} 按配置装配：
 * <ul>
 *   <li>{@code sca.source=auto}（默认）——OSV 优先，网络不可用时降级
 *       {@link BuiltinVulnSource} 内置样本，并在报告标注 {@code sourceUsed/degraded}；</li>
 *   <li>{@code sca.source=osv}——强制真实 OSV，失败不降级（报告如实标注 failed）；</li>
 *   <li>{@code sca.source=builtin}——仅离线内置样本（单测 / 纯离线环境）。</li>
 * </ul>
 * 内置样本<b>不是真实漏洞库</b>，任何依赖它的报告都必须被 {@code sourceUsed=builtin}
 * 标注出来，杜绝把「样本演示」当成「真实扫描」。
 *
 * <p>降级/失败一律可观测：打 WARN 日志 + 报告标注，绝不在「查不了」时假装「没有」。
 */
public final class ScaScanner {

    private static final Logger log = LoggerFactory.getLogger(ScaScanner.class);

    private static final Pattern POM_GROUP = Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern POM_ARTIFACT = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern POM_VERSION = Pattern.compile("<version>([^<]+)</version>");
    private static final Pattern NPM_DEP = Pattern.compile("\"([@a-zA-Z][\\w./@\\-]*)\"\\s*:\\s*\"([\\^~]?[0-9][^\"]{0,30})\"");

    /** 许可证黑名单（传染性强，企业通常禁止直接合入）。 */
    private static final java.util.Set<String> LICENSE_BLACKLIST = java.util.Set.of(
            "GPL-2.0", "GPL-3.0", "AGPL-3.0", "EUPL-1.2");

    private final ScaVulnSource primary;
    private final ScaVulnSource fallback;

    /**
     * @param primary  首选数据源（auto=osv）
     * @param fallback 降级数据源（auto=builtin；强制模式传 null 表示不降级）
     */
    public ScaScanner(ScaVulnSource primary, ScaVulnSource fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    /** 纯内置样本扫描器（离线兜底 / 确定性单测）。 */
    public static ScaScanner builtinOnly() {
        return new ScaScanner(new BuiltinVulnSource(), null);
    }

    // ===================== 组件模型 =====================

    /**
     * 被扫描出的组件。
     *
     * @param osvName OSV 查询用全限定名：Maven 为 {@code groupId:artifactId}（OSV 用全限定名
     *                索引，短名查不中）；npm 与 name 相同。无法解析出 groupId 时退回短名。
     */
    public record Component(String ecosystem, String name, String version, String osvName) {
        public Component(String ecosystem, String name, String version) {
            this(ecosystem, name, version, name);
        }
    }

    /** 命中漏洞。 */
    public record Vulnerability(Component component, String cve, String severity, String description) {
    }

    /**
     * SCA 报告。
     *
     * @param sourceUsed   实际使用的数据源（{@code osv} / {@code builtin} / {@code osv(failed)} / {@code none}）
     * @param degraded     是否发生降级（OSV 不可用回退内置样本 = true；强制模式失败也置 true 并标 failed）
     */
    public record ScaReport(List<Component> components, List<Vulnerability> vulnerabilities,
                            List<String> licenseIssues, String sbomJson,
                            String sourceUsed, boolean degraded) {
        public boolean isEmpty() {
            return components.isEmpty();
        }
    }

    // ===================== 主入口 =====================

    /**
     * 扫描 diff 中的依赖变更（走配置装配的数据源链）。
     *
     * @param diffs 代码变更列表
     * @return SCA 报告（组件、漏洞、许可问题、SBOM、实际数据源标注）
     */
    public ScaReport analyze(List<CodeDiff> diffs) {
        List<Component> components = new ArrayList<>();
        List<String> licenseIssues = new ArrayList<>();

        for (CodeDiff d : diffs) {
            String name = d.fileName().toLowerCase();
            if (name.endsWith("pom.xml")) {
                extractMaven(d, components);
                extractLicense(d, licenseIssues);
            } else if (name.endsWith("package.json")) {
                extractNpm(d, components);
            }
        }

        VulnQueryResult vq = queryVulnerabilities(components);
        String sbom = buildSbom(components, vq.vulnerabilities(), vq.sourceUsed());
        return new ScaReport(components, vq.vulnerabilities(), licenseIssues,
                sbom, vq.sourceUsed(), vq.degraded());
    }

    // ===================== 漏洞查询（带降级链） =====================

    private record VulnQueryResult(List<ScaScanner.Vulnerability> vulnerabilities,
                                   String sourceUsed, boolean degraded) {
    }

    private VulnQueryResult queryVulnerabilities(List<Component> components) {
        if (components.isEmpty()) {
            return new VulnQueryResult(List.of(), "none", false);
        }
        try {
            List<Vulnerability> hits = primary.lookupAll(components);
            log.info("[SCA] 数据源 {} 完成：{} 个组件，命中 {} 个漏洞",
                    primary.id(), components.size(), hits.size());
            return new VulnQueryResult(hits, primary.id(), false);
        } catch (Exception e) {
            if (fallback != null) {
                log.warn("[SCA] 数据源 {} 查询失败（{}），降级内置样本 {}——报告已标注 degraded",
                        primary.id(), e.getMessage(), fallback.id());
                return new VulnQueryResult(safeLookup(fallback, components), fallback.id(), true);
            }
            log.warn("[SCA] 数据源 {} 查询失败且为强制模式（不降级）：{}——报告标注 sourceUsed=failed",
                    primary.id(), e.getMessage());
            return new VulnQueryResult(List.of(), primary.id() + "(failed)", true);
        }
    }

    private static List<Vulnerability> safeLookup(ScaVulnSource source, List<Component> components) {
        try {
            return source.lookupAll(components);
        } catch (Exception e) {
            log.error("[SCA] 降级数据源 {} 也失败：{}", source.id(), e.getMessage());
            return List.of();
        }
    }

    // ===================== 内部提取 =====================

    /**
     * 提取 Maven 新增依赖。
     *
     * <p>groupId 与 artifactId 来自 diff 新增行，按行序把最近的 {@code <groupId>}
     * 配对到后续 {@code <artifactId>}（dependency 块内 group 恒先于 artifact）。
     * OSV 用全限定名 {@code groupId:artifactId} 索引，故 name 之外保留 osvName。
     */
    private static void extractMaven(CodeDiff d, List<Component> out) {
        List<String> groups = new ArrayList<>();
        List<String> artifacts = new ArrayList<>();
        List<String> versions = new ArrayList<>();
        String lastGroup = null;
        for (String line : addedLines(d.patch())) {
            Matcher gm = POM_GROUP.matcher(line);
            if (gm.find()) {
                lastGroup = gm.group(1).trim();
                continue; // groupId 先到，配给后续 artifact
            }
            Matcher am = POM_ARTIFACT.matcher(line);
            if (am.find()) {
                artifacts.add(am.group(1).trim());
                groups.add(lastGroup);
            }
            Matcher vm = POM_VERSION.matcher(line);
            if (vm.find()) {
                versions.add(vm.group(1).trim());
            }
        }
        for (int i = 0; i < artifacts.size(); i++) {
            String art = artifacts.get(i);
            String ver = i < versions.size() ? versions.get(i) : "unknown";
            String group = groups.get(i);
            String osvName = group == null || group.isBlank() ? art : group + ":" + art;
            out.add(new Component("maven", art, ver, osvName));
        }
    }

    private static void extractNpm(CodeDiff d, List<Component> out) {
        for (String line : addedLines(d.patch())) {
            Matcher m = NPM_DEP.matcher(line);
            while (m.find()) {
                String pkg = m.group(1);
                String ver = m.group(2);
                if (isLikelyVersion(ver)) {
                    out.add(new Component("npm", pkg, ver));
                }
            }
        }
    }

    private static void extractLicense(CodeDiff d, List<String> issues) {
        for (String line : addedLines(d.patch())) {
            for (String lic : LICENSE_BLACKLIST) {
                if (line.contains(lic)) {
                    issues.add("检测到黑名单许可证 " + lic + "（" + d.fileName() + "）");
                }
            }
        }
    }

    private static boolean isLikelyVersion(String v) {
        return v != null && (Character.isDigit(v.charAt(0)) || v.startsWith("^") || v.startsWith("~"));
    }

    private static List<String> addedLines(String patch) {
        List<String> r = new ArrayList<>();
        if (patch == null) {
            return r;
        }
        for (String raw : patch.split("\n")) {
            if (raw.startsWith("+") && !raw.startsWith("+++")) {
                r.add(raw.substring(1));
            }
        }
        return r;
    }

    // ===================== SBOM =====================

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 生成 CycloneDX-lite SBOM JSON（带 scanSource 标注，便于事后追溯数据来源）。 */
    private static String buildSbom(List<Component> components, List<Vulnerability> vulns, String sourceUsed) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("bomFormat", "CycloneDX");
            root.put("specVersion", "1.5");
            root.put("generatedBy", "code-review-agent-sca");
            root.put("scanSource", sourceUsed);
            ArrayNode comps = root.putArray("components");
            for (Component c : components) {
                ObjectNode cn = comps.addObject();
                cn.put("type", "library");
                cn.put("group", c.ecosystem());
                cn.put("name", c.name());
                cn.put("version", c.version());
            }
            ArrayNode vulnArr = root.putArray("vulnerabilities");
            for (Vulnerability v : vulns) {
                ObjectNode vn = vulnArr.addObject();
                vn.put("id", v.cve());
                vn.put("severity", v.severity());
                vn.put("description", v.description());
                vn.put("component", v.component().name() + "@" + v.component().version());
            }
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            return "{}";
        }
    }
}
