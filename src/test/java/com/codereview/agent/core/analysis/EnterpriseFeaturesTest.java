package com.codereview.agent.core.analysis;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.autofix.AutoFixEngine;
import com.codereview.agent.core.coordinator.Coordinator;
import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.llm.ModelGateway;
import com.codereview.agent.core.llm.ModelProvider;
import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.report.ReportGenerator;
import com.codereview.agent.core.skill.SkillRegistry;
import com.codereview.agent.core.skill.YamlRuleEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 企业级能力（9 大模块）核心逻辑验证：
 * AST 语义分析 / 调用链 / SCA 依赖 / 低代码 YAML 规则 / 统一模型网关 / 自动修复。
 * （定时扫描、人机协作工作流、IDE LSP 属集成/进程类，已通过编译与运行时集成验证。）
 */
class EnterpriseFeaturesTest {

    /** 注入中文消息源，使 AutoFix/报告等对外文案按中文解析（i18n 后测试需真实 MessageSource）。 */
    @org.junit.jupiter.api.BeforeEach
    void injectMessages() {
        org.springframework.context.support.ResourceBundleMessageSource ms =
                new org.springframework.context.support.ResourceBundleMessageSource();
        ms.setBasename("i18n/messages");
        ms.setDefaultEncoding("UTF-8");
        new com.codereview.agent.core.i18n.ReviewMessages("zh", ms);
    }

    private static CodeDiff javaDiff(String fileName, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(fileName).append(" b/").append(fileName).append('\n');
        sb.append("--- a/").append(fileName).append('\n');
        sb.append("+++ b/").append(fileName).append('\n');
        String[] lines = body.split("\n", -1);
        sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String l : lines) {
            sb.append('+').append(l).append('\n');
        }
        return new CodeDiff(fileName, sb.toString(), "java", lines.length, 0);
    }

    @Test
    void astAndCallGraphViaAdvancedAnalyzer() {
        // 构造一个 70 行的长方法
        StringBuilder method = new StringBuilder("package demo;\npublic class T {\n public void m() {\n");
        for (int i = 0; i < 70; i++) {
            method.append("  int x").append(i).append(" = ").append(i).append(";\n");
        }
        method.append(" }\n}\n");
        CodeDiff cd = javaDiff("T.java", method.toString());

        AdvancedAnalyzer analyzer = new AdvancedAnalyzer();
        List<AgentResult> results = analyzer.analyze(List.of(cd));
        boolean hasLongMethod = results.stream()
                .flatMap(r -> r.findings().stream())
                .anyMatch(f -> f.ruleId().equals("STRUCT-LONG-METHOD"));
        assertTrue(hasLongMethod, "应检出结构级「方法过长」问题");
    }

    @Test
    void scaDetectsLog4j() {
        String pom = "<dependency>\n"
                + "  <groupId>org.apache.logging.log4j</groupId>\n"
                + "  <artifactId>log4j-core</artifactId>\n"
                + "  <version>2.14.0</version>\n"
                + "</dependency>\n";
        CodeDiff cd = javaDiff("pom.xml", pom);
        ScaScanner.ScaReport report = ScaScanner.analyze(List.of(cd));
        assertTrue(report.vulnerabilities().stream()
                .anyMatch(v -> v.cve().equals("CVE-2021-44228")), "应检出 Log4Shell");
        assertNotNull(report.sbomJson(), "应生成 SBOM");
        assertTrue(report.sbomJson().contains("log4j-core"), "SBOM 应包含组件");
    }

    @Test
    void yamlRuleEngineImportsTeamRules() {
        SkillRegistry registry = new SkillRegistry(List.of(), Path.of("./target/yaml-test"));
        registry.init();
        YamlRuleEngine engine = new YamlRuleEngine(registry);
        String yaml = "rules:\n"
                + "  - name: 禁止提交 TODO\n"
                + "    category: style\n"
                + "    severity: MINOR\n"
                + "    pattern: '(?i)//.*\\b(todo|fixme)\\b'\n"
                + "    title: 遗留 TODO 标记\n"
                + "    description: 提交中仍包含待办标记\n"
                + "    suggestion: 登记到任务系统并移除\n";
        YamlRuleEngine.ImportResult res = engine.importYaml("default", yaml);
        assertEquals(1, res.imported());
        assertTrue(registry.listSkills("default").stream().anyMatch(s -> s.name().equals("禁止提交 TODO")));
    }

    @Test
    void modelGatewayReturnsConfiguredRealProviderOutput() {
        // 2026-09-03 去 Mock：网关只认装配进来的真实供应商，不再有 mock 兜底
        ModelGateway gw = new ModelGateway(List.of(new ModelProvider() {
            @Override public String name() { return "real"; }
            @Override public boolean available() { return true; }
            @Override public String chat(String prompt) { return "REAL-OK"; }
        }), 10);
        String out = gw.chat("hello");
        assertNotNull(out, "网关应返回真实供应商的结果");
        assertEquals("REAL-OK", out);
        assertFalse(gw.describe().contains("mock"), "网关不应再含任何 mock 供应商");
    }

    @Test
    void autoFixGeneratesSuggestions() {
        // 用 ReportGenerator 构造含 LOGIC-002 的报告
        Finding f = new Finding(AgentType.LOGIC, "A.java", 10, 10,
                com.codereview.agent.core.model.Severity.MAJOR, "logic", "LOGIC-002",
                "直接打印异常堆栈", "绕过统一日志框架。", "使用日志框架记录。", 0.95, "RULE");
        ReportGenerator gen = new ReportGenerator();
        ReviewReport report = gen.aggregate(1L, "r",
                List.of(new AgentResult(1L, AgentType.LOGIC, List.of(f))),
                null, "run1", 0L, "default");
        AutoFixEngine fix = new AutoFixEngine(new LlmClient() {
            public String chat(String p) {
                return "log.error(\"x\", e);";
            }
        }, null);
        String suggestions = fix.generateSuggestions(report);
        assertTrue(suggestions.contains("suggestion"), "应输出 suggestion 代码块");
        assertTrue(suggestions.contains("LOGIC-002"), "应包含对应规则修复");
    }

    @Test
    void autoFixGeneratesInlineItems() {
        Finding f = new Finding(AgentType.LOGIC, "A.java", 10, 10,
                com.codereview.agent.core.model.Severity.MAJOR, "logic", "LOGIC-002",
                "直接打印异常堆栈", "绕过统一日志框架。", "使用日志框架记录。", 0.95, "RULE");
        ReportGenerator gen = new ReportGenerator();
        ReviewReport report = gen.aggregate(1L, "r",
                List.of(new AgentResult(1L, AgentType.LOGIC, List.of(f))),
                null, "run1", 0L, "default");
        AutoFixEngine fix = new AutoFixEngine(new LlmClient() {
            public String chat(String p) {
                return "log.error(\"x\", e);";
            }
        }, null);
        List<AutoFixEngine.FixItem> items = fix.generateFixItems(report);
        assertEquals(1, items.size(), "LOGIC-002 应产出 1 条修复项");
        AutoFixEngine.FixItem item = items.get(0);
        assertEquals("A.java", item.file());
        assertEquals(10, item.line());
        assertEquals("LOGIC-002", item.ruleId());
        assertTrue(item.snippet().contains("log.error"), "片段应含日志修复代码");
    }
}
