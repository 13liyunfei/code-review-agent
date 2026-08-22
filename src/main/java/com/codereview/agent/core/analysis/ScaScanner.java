package com.codereview.agent.core.analysis;

import com.codereview.agent.core.model.CodeDiff;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SCA（软件组成分析）依赖漏洞扫描器（无外部网络依赖）。
 *
 * <p>从 PR diff 中识别新增的 Maven / npm 依赖，匹配内置 CVE 样本库与许可证黑名单，
 * 生成 SBOM（CycloneDX-lite JSON）。目标契合「大厂 P1」诉求：CVE 扫描 + SBOM + 许可证合规。
 *
 * <p>说明：生产环境应对接 OSV / NVD / 私有漏洞库；此处内置少量高频 CVE 样本用于离线演示，
 * 结构（组件模型 + SBOM 输出 + 漏洞/许可结果）可直接替换为真实数据源。
 */
public final class ScaScanner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern POM_ARTIFACT = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern POM_VERSION = Pattern.compile("<version>([^<]+)</version>");
    private static final Pattern NPM_DEP = Pattern.compile("\"([@a-zA-Z][\\w./@\\-]*)\"\\s*:\\s*\"([\\^~]?[0-9][^\"]{0,30})\"");

    /** 许可证黑名单（传染性强，企业通常禁止直接合入）。 */
    private static final java.util.Set<String> LICENSE_BLACKLIST = java.util.Set.of(
            "GPL-2.0", "GPL-3.0", "AGPL-3.0", "EUPL-1.2");

    private ScaScanner() {
    }

    /** 被扫描出的组件。 */
    public record Component(String ecosystem, String name, String version) {
    }

    /** 命中漏洞。 */
    public record Vulnerability(Component component, String cve, String severity, String description) {
    }

    /** SCA 报告。 */
    public record ScaReport(List<Component> components, List<Vulnerability> vulnerabilities,
                            List<String> licenseIssues, String sbomJson) {
    }

    // 内置 CVE 样本（artifactId -> 受影响版本上限 + CVE 信息）
    private record CveRule(String artifact, int maxMajor, int maxMinor, int maxPatch,
                           String cve, String severity, String description) {
    }

    private static final List<CveRule> CVE_RULES = List.of(
            new CveRule("log4j-core", 2, 14, 1, "CVE-2021-44228", "BLOCKER",
                    "Log4j 2.x < 2.15.0 存在 JNDI 远程代码执行（Log4Shell）。"),
            new CveRule("spring-core", 5, 3, 17, "CVE-2022-22965", "BLOCKER",
                    "Spring Framework < 5.3.18 存在 Spring4Shell RCE。"),
            new CveRule("spring-beans", 5, 3, 17, "CVE-2022-22965", "BLOCKER",
                    "Spring Framework < 5.3.18 存在 Spring4Shell RCE。"),
            new CveRule("commons-collections", 3, 2, 1, "CVE-2015-7501", "MAJOR",
                    "Commons Collections < 3.2.2 存在反序列化 RCE。"),
            new CveRule("jackson-databind", 2, 9, 7, "CVE-2018-7489", "MAJOR",
                    "jackson-databind < 2.9.8 存在反序列化漏洞。"),
            new CveRule("lodash", 4, 17, 20, "CVE-2021-23337", "MAJOR",
                    "lodash < 4.17.21 存在命令注入/原型污染。"),
            new CveRule("minimist", 1, 2, 5, "CVE-2021-44906", "MAJOR",
                    "minimist < 1.2.6 存在原型污染。"),
            new CveRule("axios", 0, 21, 0, "CVE-2020-28168", "MINOR",
                    "axios < 0.21.1 存在 SSRF 代理绕过。")
    );

    /**
     * 扫描 diff 中的依赖变更。
     *
     * @param diffs 代码变更列表
     * @return SCA 报告（含组件、漏洞、许可问题、SBOM）
     */
    public static ScaReport analyze(List<CodeDiff> diffs) {
        List<Component> components = new ArrayList<>();
        List<Vulnerability> vulns = new ArrayList<>();
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

        for (Component c : components) {
            for (CveRule r : CVE_RULES) {
                if (r.artifact().equals(c.name()) && r.maxMajor() >= 0) {
                    int[] v = parseVersion(c.version());
                    if (v != null && withinRange(v, r)) {
                        vulns.add(new Vulnerability(c, r.cve(), r.severity(), r.description()));
                    }
                }
            }
        }

        return new ScaReport(components, vulns, licenseIssues, buildSbom(components, vulns));
    }

    // ===================== 内部提取 =====================

    private static void extractMaven(CodeDiff d, List<Component> out) {
        List<String> added = addedLines(d.patch());
        List<String> artifacts = new ArrayList<>();
        List<String> versions = new ArrayList<>();
        for (String line : added) {
            Matcher am = POM_ARTIFACT.matcher(line);
            if (am.find()) {
                artifacts.add(am.group(1).trim());
            }
            Matcher vm = POM_VERSION.matcher(line);
            if (vm.find()) {
                versions.add(vm.group(1).trim());
            }
        }
        for (int i = 0; i < artifacts.size(); i++) {
            String ver = i < versions.size() ? versions.get(i) : "unknown";
            out.add(new Component("maven", artifacts.get(i), ver));
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

    private static boolean withinRange(int[] v, CveRule r) {
        int major = v[0], minor = v.length > 1 ? v[1] : 0, patch = v.length > 2 ? v[2] : 0;
        if (major != r.maxMajor()) {
            return major < r.maxMajor();
        }
        if (minor != r.maxMinor()) {
            return minor < r.maxMinor();
        }
        return patch <= r.maxPatch();
    }

    private static int[] parseVersion(String v) {
        if (v == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder();
        List<Integer> parts = new ArrayList<>();
        for (char c : v.toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else if (c == '.') {
                if (digits.length() > 0) {
                    parts.add(Integer.parseInt(digits.toString()));
                    digits.setLength(0);
                }
            } else {
                break; // 遇到非数字非点（如 -RC1）停止
            }
        }
        if (digits.length() > 0) {
            parts.add(Integer.parseInt(digits.toString()));
        }
        if (parts.isEmpty()) {
            return null;
        }
        return parts.stream().mapToInt(Integer::intValue).toArray();
    }

    /** 生成 CycloneDX-lite SBOM JSON。 */
    private static String buildSbom(List<Component> components, List<Vulnerability> vulns) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("bomFormat", "CycloneDX");
            root.put("specVersion", "1.5");
            root.put("generatedBy", "code-review-agent-sca");
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
