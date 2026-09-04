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

    /**
     * 按真实链路驱动 ToolCallingLoop 的 LLM 桩：观察写入 prompt 前只发 call_tool 决策，
     * 一旦看到 "[观察]"（工具真实执行成功的证据）才 finish。
     *
     * <p>这是集成测试护栏的关键：若工具注册/查找/执行链路被短路，观察永不出现，
     * LLM 会一直 call_tool 直到耗尽 maxIterations → 兜底纯文本 → 工具 findings 为 0 → 用例失败。
     * 因此它不允许「假装配让测试看起来测了工具发现」的回归。
     */
    static class ToolDrivingLlm implements LlmClient {
        private final String finishAnswerJson;
        private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        ToolDrivingLlm(String finishAnswerJson) { this.finishAnswerJson = finishAnswerJson; }
        @Override public String chat(String prompt) {
            if (prompt.contains("[观察]")) {
                try {
                    return "{\"action\":\"finish\",\"answer\":" + mapper.writeValueAsString(finishAnswerJson) + "}";
                } catch (Exception e) {
                    return "{\"action\":\"finish\",\"answer\":\"\"}";
                }
            }
            return "{\"action\":\"call_tool\",\"thought\":\"扫描 System.out 用法\",\"tool\":\"regex_scan\","
                    + "\"arguments\":{\"text\":\"+System.out.println(1);\",\"regex\":\"System\\\\.out\"}}";
        }
    }

    @Test
    void toolEquippedAgentMergesDelegateAndToolFindings() {
        CountingAgent delegate = new CountingAgent();
        String toolAnswer = """
                {"findings":[{"severity":"MAJOR","file":"Pay.java","lineStart":6,
                  "title":"工具发现System.out","description":"生产代码禁止 System.out","suggestion":"改用日志"}]}""";
        ToolRegistry registry = new ToolRegistry();
        registry.register(new com.codereview.kit.toolcalling.BuiltinTools.RegexScanTool());
        // 真实 ToolCallingLoop：LLM 决策 → registry 查找 → RegexScanTool 真实执行 → 观察 → finish。
        // 不再用匿名子类 override run() 短路整条被测链路（旧实现是假装配：工具注册与 LLM 布置全是死布置）。
        ToolEquippedAgent agent = new ToolEquippedAgent(delegate,
                new ToolCallingLoop(new ToolDrivingLlm(toolAnswer), registry, 3));
        List<Finding> findings = agent.review(List.of(diff()), null);
        assertEquals(1, delegate.calls);
        assertEquals(2, findings.size());
        assertTrue(findings.stream().anyMatch(f -> f.title().contains("工具发现")));
        assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("LOGIC-001")));
    }

    @Test
    void experienceStoreWriteRetrieveAndTeamIsolation(@TempDir Path tmp) {
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
    void reflectionServiceDistillsMajorExperienceFromReport(@TempDir Path tmp) {
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
    void llmJudgeComputesPrecisionRecallAndFlagsFalsePositive() {
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
    void extensionRegistryOrderingAndSameNameOverride() {
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
