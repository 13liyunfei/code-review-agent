package com.codereview.kit.struct;

import com.codereview.kit.ChatModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化输出（类型安全契约 + 自动校验重试）。
 *
 * <p>对标 Pydantic AI / OpenAI structured outputs：使用方声明 JSON Schema 约束，
 * 模型输出经反序列化校验，失败自动重试（最多 {@code maxRetries} 次），
 * 返回强类型对象——消除手写 JSON 解析的脆弱性。
 */
public class StructuredChatModel {

    private final ChatModel delegate;
    private final ObjectMapper mapper = new ObjectMapper();

    public StructuredChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    /**
     * 结构化对话：输出必须是满足 {@code schema} 的 JSON 对象。
     *
     * @param prompt    提示词
     * @param schema    JSON Schema（{"type":"object","properties":{...},"required":[...]}）
     * @param type      目标类型
     * @param maxRetries 解析/校验失败最大重试次数
     * @return 解析后的强类型对象
     * @throws IllegalStateException 重试耗尽仍未通过
     */
    public <T> T chatStructured(String prompt, Map<String, Object> schema, Class<T> type, int maxRetries) {
        String jsonSchema = toSchemaJson(schema);
        for (int i = 0; i <= maxRetries; i++) {
            String resp = delegate.chat(prompt + "\n\n只输出满足以下 JSON Schema 的 JSON 对象（不要 Markdown 代码块）：\n" + jsonSchema);
            T parsed = tryParse(resp, type);
            if (parsed != null) {
                return parsed;
            }
        }
        throw new IllegalStateException("结构化输出解析失败（重试 " + maxRetries + " 次后放弃）");
    }

    /** 便捷构造：常用字段的 object schema。 */
    public static Map<String, Object> objectSchema(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of(required));
        return schema;
    }

    /** 便捷构造：string / integer / number / boolean 字段。 */
    public static Map<String, Object> field(String type) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("type", type);
        return f;
    }

    private String toSchemaJson(Map<String, Object> schema) {
        try {
            return mapper.writeValueAsString(schema);
        } catch (Exception e) {
            throw new IllegalArgumentException("非法 schema", e);
        }
    }

    private <T> T tryParse(String text, Class<T> type) {
        try {
            String t = text.trim();
            int s = t.indexOf('{');
            int e = t.lastIndexOf('}');
            if (s < 0 || e <= s) {
                return null;
            }
            JsonNode node = mapper.readTree(t.substring(s, e + 1));
            return mapper.treeToValue(node, type);
        } catch (Exception ex) {
            return null;
        }
    }
}
