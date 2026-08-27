package com.codereview.agent.core.toolcalling;

import com.codereview.agent.core.toolcalling.impl.BuiltinTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tool Calling 决策循环单测：手写 fake LLM（脚本化决策序列），不引 Mockito。
 */
class ToolCallingLoopTest {

    /** 脚本化 fake：按序返回预设响应。 */
    static class FakeLlm implements com.codereview.agent.core.llm.LlmClient {
        private final Deque<String> script;
        FakeLlm(String... responses) { this.script = new ArrayDeque<>(List.of(responses)); }
        @Override public String chat(String prompt) {
            return script.isEmpty() ? "{\"action\":\"finish\",\"answer\":\"兜底\"}" : script.poll();
        }
    }

    private ToolRegistry registry() {
        ToolRegistry r = new ToolRegistry();
        r.register(new BuiltinTools.CurrentTimeTool());
        r.register(new BuiltinTools.RegexScanTool());
        r.register(new BuiltinTools.FileReadTool(Path.of(".").toAbsolutePath()));
        return r;
    }

    @Test
    void 完整链路_思考调用工具后给出结论() {
        FakeLlm llm = new FakeLlm(
                "{\"action\":\"call_tool\",\"tool\":\"regex_scan\",\"arguments\":{\"text\":\"SELECT * FROM t WHERE id = 1\",\"regex\":\"SELECT\\\\s+\\\\*\"}}",
                "{\"action\":\"finish\",\"answer\":\"发现 SELECT * 用法，建议明确列名\"}");
        ToolCallingLoop.LoopResult r = new ToolCallingLoop(llm, registry(), 5)
                .run("检查 SQL 反模式", "SELECT * FROM t WHERE id = 1");
        assertEquals(1, r.toolCalls().size());
        assertEquals("regex_scan", r.toolCalls().get(0));
        assertTrue(r.answer().contains("SELECT *"));
        assertEquals(2, r.iterations());
    }

    @Test
    void 非法JSON输出优雅降级为最终答案() {
        ToolCallingLoop.LoopResult r = new ToolCallingLoop(new FakeLlm("这是纯文本结论，不是JSON"), registry(), 5)
                .run("目标", null);
        assertTrue(r.answer().contains("纯文本结论"));
        assertTrue(r.toolCalls().isEmpty());
    }

    @Test
    void 未知工具记录观察并继续() {
        FakeLlm llm = new FakeLlm(
                "{\"action\":\"call_tool\",\"tool\":\"no_such_tool\",\"arguments\":{}}",
                "{\"action\":\"finish\",\"answer\":\"工具不可用，直接结论\"}");
        ToolCallingLoop.LoopResult r = new ToolCallingLoop(llm, registry(), 5).run("目标", null);
        assertEquals(0, r.toolCalls().size());
        assertTrue(r.answer().contains("直接结论"));
    }

    @Test
    void 达到最大迭代返回兜底结论() {
        String callTool = "{\"action\":\"call_tool\",\"tool\":\"current_time\",\"arguments\":{}}";
        FakeLlm llm = new FakeLlm(callTool, callTool, callTool);
        ToolCallingLoop.LoopResult r = new ToolCallingLoop(llm, registry(), 3).run("目标", null);
        assertEquals(3, r.iterations());
        assertEquals(3, r.toolCalls().size());
        assertTrue(r.answer().contains("最大迭代"));
    }

    @Test
    void 文件工具拒绝白名单外路径穿越(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("ok.txt"), "hello");
        BuiltinTools.FileReadTool tool = new BuiltinTools.FileReadTool(tmp);
        assertTrue(tool.execute(java.util.Map.of("path", "ok.txt")).success());
        assertFalse(tool.execute(java.util.Map.of("path", "../../etc/passwd")).success());
    }
}
