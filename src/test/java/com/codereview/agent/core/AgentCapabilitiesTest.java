package com.codereview.agent.core;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.kit.eval.LlmJudge;
import com.codereview.kit.extension.ExtensionPoint;
import com.codereview.kit.extension.ExtensionRegistry;
import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.memory.ExperienceStore;
import com.codereview.agent.core.memory.ReflectionService;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.report.ReportGenerator;
import com.codereview.kit.toolcalling.ToolCallingLoop;
import com.codereview.agent.core.toolcalling.ToolEquippedAgent;
import com.codereview.kit.toolcalling.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Agent 通用能力四件套集成测试：工具增强 Agent / 经验库+反思 / LLM 评估 / 扩展注册中心。
 */
class AgentCapabilitiesTest {

    static class ScriptLlm implements LlmClient {
        private final String response;
        ScriptLlm(String response) { this.response = response; }
        @Override public String chat(String prompt) { return response; }
    }

    static class CountingAgent implements ReviewAgent {
        private final AgentType type = AgentType.LOGIC;
        int calls;
        @Override public AgentType getType() { return type; }
        @Override public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
            calls++;
            return List.of(new Finding(type, "Pay.java", 5, 5, Severity.MAJOR, "logic",
                    "LOGIC-001", "委托发现", "描述", "建议", 0.9, "test"));
        }
    }

    private static CodeDiff diff() {
        return new CodeDiff("Pay.java", "+System.out.println(1);", "java", 1, 0);
    }

    @Test
    void 工具增强Agent_合并委托结果与工具发现() {
        CountingAgent delegate = new CountingAgent();
        String toolAnswer = """
                {"findings":[{"severity":"MAJOR","file":"Pay.java","lineStart":6,
                  "title":"工具发现System.out","description":"生产代码禁止 System.out","suggestion":"改用日志"}]}""";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.codereview.kit.toolcalling.BuiltinTools.RegexScanTool());
        ToolEquippedAgent agent = new ToolEquippedAgent(delegate,
                new ToolCallingLoop(new ScriptLlm(
                        "{\"action\":\"call_tool\",\"tool\":\"regex_scan\",\"arguments\":{\"text\":\"System.out.println(1);\",\"regex\":\"System\\\\.out\"}}"),
                        registry, 3) {
                    @Override public LoopResult run(String goal, String context) {
                        return new LoopResult(toolAnswer, List.of("regex_scan"), 2);
                    }
                });
        List<Finding> findings = agent.review(List.of(diff()), null);
        assertEquals(1, delegate.calls);
        assertEquals(2, findings.size());
        assertTrue(findings.stream().anyMatch(f -> f.title().contains("工具发现")));
        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("LOGIC-001")));
    }

    @Test
    void 经验库_写入检索与团队隔离(@TempDir Path tmp) {
        ExperienceStore store = new ExperienceStore(null, tmp);
        store.add("teamA", "sql-injection 拼接漏洞", "使用参数化查询");
        store.add("teamA", "system-out 调试输出", "改用 SLF4J 日志");
        store.add("teamB", "other 经验", "其他建议");
        assertEquals(2, store.size("teamA"));
        List<ExperienceStore.Experience> hits = store.top("teamA", "修复 sql-injection 注入", 1);
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).pattern().contains("sql-injection"));
        assertTrue(store.top("teamB", "sql-injection", 5).isEmpty());
        store.add("teamA", "sql-injection 拼接漏洞", "更新建议"); // 同 pattern 去重
        assertEquals(2, store.size("teamA"));
    }

    @Test
    void 反思服务_从报告沉淀MAJOR以上经验(@TempDir Path tmp) {
        ExperienceStore store = new ExperienceStore(null, tmp);
        ReflectionService service = new ReflectionService(store, null);
        ReviewReport report = new ReportGenerator().aggregate(1, "r", List.of(
                new com.codereview.agent.core.model.AgentResult(1, AgentType.SECURITY, List.of(
                        new Finding(AgentType.SECURITY, "A.java", 1, 1, Severity.BLOCKER, "security",
                                "SEC-001", "硬编码密钥", "d", "改用配置中心", 0.9, "test"),
                        new Finding(AgentType.SECURITY, "B.java", 2, 2, Severity.INFO, "security",
                                "SEC-002", "低级提示", "d", "建议", 0.9, "test")))),
                null, "run1", 10, "default");
        int added = service.reflectFromReport("default", report);
        assertTrue(added >= 1);
        assertTrue(store.size("default") >= 1);
    }

    @Test
    void LLM评估_精确匹配计算precision_recall且judge误报被识别() {
        LlmJudge<com.codereview.agent.core.model.Finding> judge = new LlmJudge<>(new ScriptLlm("{\"verdict\":\"FP\"}"));
        ReviewReport report = new ReportGenerator().aggregate(1, "r", List.of(
                new com.codereview.agent.core.model.AgentResult(1, AgentType.SECURITY, List.of(
                        new Finding(AgentType.SECURITY, "A.java", 1, 1, Severity.BLOCKER, "security",
                                "SEC-001", "硬编码密钥命中", "d", "s", 0.9, "test"),
                        new Finding(AgentType.SECURITY, "C.java", 9, 9, Severity.MAJOR, "style",
                                "STYLE-009", "无关风格", "d", "s", 0.9, "test")))),
                null, "run1", 10, "default");
        LlmJudge.EvalResult r = judge.evaluate(report.getFindings(), List.of(
                new LlmJudge.GroundTruth("A.java", "硬编码")));
        assertEquals(1, r.tp());
        assertEquals(1, r.fp());
        assertEquals(0, r.fn());
        assertEquals(0.5, r.precision(), 1e-6);
        assertEquals(1.0, r.recall(), 1e-6);
        assertTrue(r.judgeSummary().contains("判误报 1 条"));
    }

    interface Demo extends ExtensionPoint {}

    record DemoExt(String name, int order, String marker) implements Demo {}

    @Test
    void 扩展注册中心_order排序与同名覆盖() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(Demo.class, new DemoExt("standard", 100, "S"));
        registry.register(Demo.class, new DemoExt("custom", 10, "C"));
        registry.register(Demo.class, new DemoExt("custom", 10, "C2")); // 同名覆盖
        List<Demo> chain = registry.list(Demo.class);
        assertEquals(2, chain.size());
        assertEquals("custom", chain.get(0).name());
        assertEquals("standard", chain.get(1).name());
        assertFalse(registry.list(ExtensionPoint.class).isEmpty() && chain.isEmpty());
    }
}
