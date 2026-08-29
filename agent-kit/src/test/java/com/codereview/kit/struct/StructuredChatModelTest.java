package com.codereview.kit.struct;

import com.codereview.kit.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StructuredChatModelTest {

    public record Decision(String action, int level) {
    }

    static class ScriptModel implements ChatModel {
        private final List<String> script;
        private final AtomicInteger calls = new AtomicInteger();

        ScriptModel(String... script) {
            this.script = List.of(script);
        }

        @Override public String chat(String prompt) {
            int i = Math.min(calls.getAndIncrement(), script.size() - 1);
            return script.get(i);
        }
    }

    private Map<String, Object> decisionSchema() {
        return StructuredChatModel.objectSchema(
                Map.of("action", StructuredChatModel.field("string"),
                        "level", StructuredChatModel.field("integer")),
                "action", "level");
    }

    @Test
    void 首次即合法JSON解析成功() {
        StructuredChatModel model = new StructuredChatModel(
                new ScriptModel("{\"action\":\"approve\",\"level\":3}"));
        Decision d = model.chatStructured("决策", decisionSchema(), Decision.class, 2);
        assertEquals("approve", d.action());
        assertEquals(3, d.level());
    }

    @Test
    void 非法输出自动重试至成功() {
        ScriptModel llm = new ScriptModel("not json", "```json\n{\"action\":\"reject\",\"level\":1}\n```");
        StructuredChatModel model = new StructuredChatModel(llm);
        Decision d = model.chatStructured("决策", decisionSchema(), Decision.class, 2);
        assertEquals("reject", d.action());
        assertEquals(2, llm.calls.get()); // 1 次失败重试 + 1 次成功
    }

    @Test
    void 重试耗尽抛异常() {
        StructuredChatModel model = new StructuredChatModel(new ScriptModel("bad", "bad", "bad"));
        assertThrows(IllegalStateException.class,
                () -> model.chatStructured("决策", decisionSchema(), Decision.class, 2));
    }
}
