package com.codereview.agent.core.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * 离线内置漏洞样本数据源。
 *
 * <p>仅覆盖少量高频 CVE（Log4Shell / Spring4Shell / 反序列化链等），用于：
 * <ul>
 *   <li><b>无网兜底</b>：OSV 不可达时降级到这里，保证 SCA 块不空、不拖垮审查；</li>
 *   <li><b>确定性单测 fixture</b>：内置规则稳定，可作固定断言。</li>
 * </ul>
 *
 * <p><b>它不是真实漏洞库</b>——样本之外的真实漏洞一律不命中。因此任何
 * 依赖此源的报告都会在 {@code sourceUsed=builtin} 上被标注，调用方应据此
 * 区分「内置样本扫描」与「真实 OSV 扫描」，绝不可把两者混为一谈。
 *
 * <p>数据源语义与原 {@code ScaScanner} 静态内置规则完全一致（按 artifact 名
 * 匹配 + 数值版本区间判断），迁移不改变行为。
 */
public final class BuiltinVulnSource implements ScaVulnSource {

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

    @Override
    public String id() {
        return "builtin";
    }

    @Override
    public List<ScaScanner.Vulnerability> lookupAll(List<ScaScanner.Component> components) {
        List<ScaScanner.Vulnerability> out = new ArrayList<>();
        if (components == null) {
            return out;
        }
        for (ScaScanner.Component c : components) {
            for (CveRule r : CVE_RULES) {
                if (r.artifact().equals(c.name()) && r.maxMajor() >= 0) {
                    int[] v = parseVersion(c.version());
                    if (v != null && withinRange(v, r)) {
                        out.add(new ScaScanner.Vulnerability(c, r.cve(), r.severity(), r.description()));
                    }
                }
            }
        }
        return out;
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

    /** 只解析数字与点号前缀（如 {@code 2.14.1-RC1} → {@code [2,14,1]}），失败返回 null。 */
    static int[] parseVersion(String v) {
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
}
