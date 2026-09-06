package com.codereview.agent.core.analysis;

import com.codereview.agent.core.model.CodeDiff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ScaScanner 降级链与组件提取的确定性验证。
 *
 * <p>核心语义：数据源失败时「如实降级 + 报告标注」，绝不在查不了时假装没有；
 * 组件提取要为 OSV 拼出全限定名（Maven group:artifact）。
 */
class ScaScannerTest {

    private static CodeDiff pomDiff(String addedXml) {
        StringBuilder patch = new StringBuilder("@@ -1,3 +1,10 @@\n package p;\n");
        for (String line : addedXml.split("\n")) {
            patch.append('+').append(line).append('\n');
        }
        return new CodeDiff("pom.xml", patch.toString(), "xml", 1, 7);
    }

    // ---- 组件提取 ----

    @Test
    void mavenDependencyYieldsQualifiedOsvName() {
        String added = "<dependency>\n"
                + "  <groupId>org.apache.logging.log4j</groupId>\n"
                + "  <artifactId>log4j-core</artifactId>\n"
                + "  <version>2.14.1</version>\n"
                + "</dependency>";
        ScaScanner scanner = ScaScanner.builtinOnly();
        ScaScanner.ScaReport report = scanner.analyze(List.of(pomDiff(added)));

        assertEquals(1, report.components().size());
        ScaScanner.Component c = report.components().get(0);
        assertEquals("log4j-core", c.name());
        assertEquals("org.apache.logging.log4j:log4j-core", c.osvName(), "Maven 应产出全限定名供 OSV");
        assertEquals("2.14.1", c.version());
        assertEquals("builtin", report.sourceUsed(), "builtinOnly 数据源标注");
        assertFalse(report.degraded());
    }

    @Test
    void mavenWithoutGroupIdKeepsShortName() {
        String added = "<artifactId>log4j-core</artifactId>\n<version>2.14.1</version>";
        ScaScanner.ScaReport report = ScaScanner.builtinOnly().analyze(List.of(pomDiff(added)));
        assertEquals("log4j-core", report.components().get(0).osvName(), "无 groupId 退回短名");
    }

    @Test
    void npmDependencyExtracted() {
        String added = "\"dependencies\": {\n  \"lodash\": \"^4.17.20\"\n}";
        StringBuilder patch = new StringBuilder("@@ -1,2 +1,5 @@\n");
        for (String line : added.split("\n")) {
            patch.append('+').append(line).append('\n');
        }
        CodeDiff cd = new CodeDiff("package.json", patch.toString(), "json", 1, 4);
        ScaScanner.ScaReport report = ScaScanner.builtinOnly().analyze(List.of(cd));
        assertEquals(1, report.components().size());
        assertEquals("lodash", report.components().get(0).name());
        assertEquals("^4.17.20", report.components().get(0).version());
    }

    // ---- 降级链 ----

    @Test
    void primaryFailureDegradesToBuiltinAndMarksReport() {
        ScaVulnSource failing = new ScaVulnSource() {
            @Override
            public String id() {
                return "osv";
            }

            @Override
            public List<ScaScanner.Vulnerability> lookupAll(List<ScaScanner.Component> components) throws Exception {
                throw new java.io.IOException("network down");
            }
        };
        // auto 模式：osv 失败 → 内置兜底
        ScaScanner scanner = new ScaScanner(failing, new BuiltinVulnSource());
        String added = "<artifactId>log4j-core</artifactId>\n<version>2.14.1</version>";
        ScaScanner.ScaReport report = scanner.analyze(List.of(pomDiff(added)));

        assertTrue(report.degraded(), "降级应被标注");
        assertEquals("builtin", report.sourceUsed(), "实际数据源应标注为 builtin");
        assertTrue(report.vulnerabilities().stream()
                        .anyMatch(v -> v.cve().equals("CVE-2021-44228")),
                "降级后内置样本应命中 Log4Shell");
        assertTrue(report.sbomJson().contains("\"scanSource\" : \"builtin\""),
                "SBOM 也应标注数据源");
    }

    @Test
    void forcedModeFailureMarksFailedWithoutFakeData() {
        ScaVulnSource failing = new ScaVulnSource() {
            @Override
            public String id() {
                return "osv";
            }

            @Override
            public List<ScaScanner.Vulnerability> lookupAll(List<ScaScanner.Component> components) throws Exception {
                throw new java.io.IOException("network down");
            }
        };
        // 强制 osv 模式：不降级 → 报告空漏洞但标注 failed，杜绝假样本冒充
        ScaScanner scanner = new ScaScanner(failing, null);
        String added = "<artifactId>log4j-core</artifactId>\n<version>2.14.1</version>";
        ScaScanner.ScaReport report = scanner.analyze(List.of(pomDiff(added)));

        assertTrue(report.degraded(), "强制模式失败也应置 degraded 提示异常");
        assertEquals("osv(failed)", report.sourceUsed(), "sourceUsed 应如实标注 failed");
        assertTrue(report.vulnerabilities().isEmpty(), "不得用内置样本冒充真实扫描结果");
    }

    @Test
    void primaryHitReportedWithoutDegradation() throws Exception {
        ScaVulnSource osv = new ScaVulnSource() {
            @Override
            public String id() {
                return "osv";
            }

            @Override
            public List<ScaScanner.Vulnerability> lookupAll(List<ScaScanner.Component> components) {
                ScaScanner.Component c = components.get(0);
                return List.of(new ScaScanner.Vulnerability(c, "CVE-2021-44228", "BLOCKER", "Log4Shell"));
            }
        };
        ScaScanner scanner = new ScaScanner(osv, new BuiltinVulnSource());
        String added = "<artifactId>log4j-core</artifactId>\n<version>2.14.1</version>";
        ScaScanner.ScaReport report = scanner.analyze(List.of(pomDiff(added)));

        assertFalse(report.degraded());
        assertEquals("osv", report.sourceUsed());
        assertEquals(1, report.vulnerabilities().size());
        assertEquals("CVE-2021-44228", report.vulnerabilities().get(0).cve());
    }

    @Test
    void licenseBlacklistStillChecked() {
        String added = "<license>GPL-3.0</license>\n<artifactId>x</artifactId>\n<version>1.0</version>";
        ScaScanner.ScaReport report = ScaScanner.builtinOnly().analyze(List.of(pomDiff(added)));
        assertTrue(report.licenseIssues().stream().anyMatch(s -> s.contains("GPL-3.0")),
                "应检出黑名单许可证");
    }
}
