package com.codereview.agent.core.agent;

import com.codereview.agent.core.agent.impl.LogicAgent;
import com.codereview.agent.core.calibration.ConfidenceCalibrationService;
import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.prompt.PromptTemplate;
import com.codereview.agent.core.prompt.PromptTemplateLoader;
import com.codereview.agent.core.skill.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 agent-kit 结构化输出已接入审查链路（AiServices 不可用时的第二条通路）。
 *
 * <p>这里刻意把 {@code aiService} 传 null —— 即纯 Java / 离线部署场景，
 * 此时本仓库完全依赖 agent-kit 的 {@code StructuredChatModel} 拿到强类型审查结果，
 * 而不是退回"提示词要求返回 JSON + 文本抽取"的老办法。
 *
 * <p>schema 由 {@code ReviewResultDto} 类型自动推导（含嵌套 {@code List<ReviewFindingDto>}），
 * 无需在本仓库维护任何 schema 定义。
 */
class AgentKitStructuredOutputTest {

    static class ScriptedLlm implements LlmClient {
        String response = "";

        @Override public String chat(String prompt) {
            return response;
        }
    }

    /** 只覆盖 render：测试不关心模板加载，只需稳定的提示词。 */
    private static final PromptTemplateLoader LOADER = new PromptTemplateLoader() {
        @Override public PromptTemplate load(String templateName) {
            return null;
        }

        @Override public String render(String templateName, Map<String, Object> variables) {
            return "审查以下变更：\n" + variables.get("diffs");
        }
    };

    private LogicAgent agent(ScriptedLlm llm, Path dataDir) {
        return new LogicAgent(llm, LOADER, new SkillRegistry(List.of(), dataDir),
                new ConfidenceCalibrationService(), null);
    }

    private static CodeDiff diff(String body) {
        return new CodeDiff("PayService.java", body, "java", 1, 0);
    }

    private static ReviewContext ctx() {
        return new ReviewContext(1L, "demo/pay", "@alice", "main", "default");
    }

    @Test
    void structuredJsonIsParsedByAgentKit(@TempDir Path dataDir) {
        ScriptedLlm llm = new ScriptedLlm();
        llm.response = """
                {"findings":[{"ruleId":"LLM-LOGIC","title":"可能的空指针","description":"user 未判空",\
                "suggestion":"增加判空","severity":"MAJOR","file":"PayService.java","line":12,"confidence":0.9}]}
                """;

        List<Finding> findings = agent(llm, dataDir).review(List.of(diff("+int x=1;")), ctx());

        assertEquals(1, findings.size(), "agent-kit 结构化通路应解析出 1 条发现");
        Finding f = findings.get(0);
        assertEquals("可能的空指针", f.title());
        assertEquals("PayService.java", f.file());
        assertEquals(12, f.lineStart());
        assertEquals("LLM-LOGIC", f.ruleId());
    }

    @Test
    void markdownFencedJsonIsParsed(@TempDir Path dataDir) {
        ScriptedLlm llm = new ScriptedLlm();
        llm.response = "好的，审查结果如下：\n```json\n"
                + "{\"findings\":[{\"ruleId\":\"R1\",\"title\":\"资源未关闭\","
                + "\"description\":\"d\",\"suggestion\":\"s\",\"severity\":\"BLOCKER\","
                + "\"file\":\"A.java\",\"line\":3,\"confidence\":0.8}]}\n```\n希望有帮助。";

        List<Finding> findings = agent(llm, dataDir).review(List.of(diff("+int x=1;")), ctx());

        assertEquals(1, findings.size());
        assertEquals("资源未关闭", findings.get(0).title());
    }

    @Test
    void unparseableOutputDegradesToEmptyWithoutThrowing(@TempDir Path dataDir) {
        ScriptedLlm llm = new ScriptedLlm();
        llm.response = "抱歉，我无法完成这次审查。";

        List<Finding> findings = agent(llm, dataDir).review(List.of(diff("+int x=1;")), ctx());

        // 结构化失败后回退文本解析，文本也解析不出 → 空列表，且绝不抛异常打断审查
        assertTrue(findings.isEmpty());
    }

    @Test
    void bareJsonArrayStillHandledByTextFallback(@TempDir Path dataDir) {
        ScriptedLlm llm = new ScriptedLlm();
        // 模型直接返回数组（非 {"findings":[...]}）——结构化解析会失败，
        // 必须复用原始输出回退文本解析，而不是丢掉这次结果
        llm.response = """
                [{"ruleId":"R2","title":"魔法数字","description":"d","suggestion":"s",\
                "severity":"MINOR","file":"B.java","line":7,"confidence":0.6}]
                """;

        List<Finding> findings = agent(llm, dataDir).review(List.of(diff("+int x=1;")), ctx());

        assertFalse(findings.isEmpty(), "结构化失败时应复用原始输出走文本解析，不能丢结果");
        assertEquals("魔法数字", findings.get(0).title());
    }
}
