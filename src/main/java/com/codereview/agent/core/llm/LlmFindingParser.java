package com.codereview.agent.core.llm;

import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 审查意见解析器：把大模型返回的 JSON 数组解析为 {@link Finding} 列表。
 *
 * <p>混元（及其它 OpenAI 兼容模型）返回的文本可能被 ```json 代码块包裹或附带说明文字，
 * 本解析器先抽取首个 {@code [} 到末个 {@code ]} 的子串再解析，保证鲁棒。
 * 解析失败不影响主流程——返回空列表，Agent 仍保留规则型发现。
 */
public final class LlmFindingParser {

    private static final Logger log = LoggerFactory.getLogger(LlmFindingParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmFindingParser() {
    }

    /**
     * 解析 LLM 返回文本为 Finding 列表。
     *
     * @param llmResponse 模型返回文本
     * @param agentType   归属 Agent 类型
     * @param category    问题分类
     * @return 解析出的发现列表（可能为空）
     */
    public static List<Finding> parse(String llmResponse, AgentType agentType, String category) {
        List<Finding> findings = new ArrayList<>();
        if (llmResponse == null || llmResponse.isBlank()) {
            return findings;
        }
        String json = extractJsonArray(llmResponse);
        if (json == null) {
            return findings;
        }
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) {
                return findings;
            }
            for (JsonNode node : arr) {
                try {
                    findings.add(toFinding(node, agentType, category));
                } catch (Exception ex) {
                    log.debug("[LLM] 跳过无法解析的条目：{}", ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[LLM] 解析 findings 失败：{}", e.getMessage());
        }
        return findings;
    }

    /**
     * 单条 JSON 对象转 Finding。
     */
    private static Finding toFinding(JsonNode node, AgentType agentType, String category) {
        Severity severity = parseSeverity(text(node, "severity", "MAJOR"));
        String file = text(node, "file", "unknown");
        int line = node.path("line").asInt(node.path("line_start").asInt(0));
        String title = text(node, "title", "LLM 发现");
        String description = text(node, "description", "");
        String suggestion = text(node, "suggestion", "");
        double confidence = node.path("confidence").asDouble(0.75);
        String ruleId = text(node, "rule_id", text(node, "ruleId", "LLM-" + agentType.name()));
        String source = text(node, "source", "LLM");
        return new Finding(agentType, file, line, line, severity, category,
                ruleId, title, description, suggestion, confidence, source);
    }

    private static Severity parseSeverity(String s) {
        if (s == null) {
            return Severity.MAJOR;
        }
        return switch (s.trim().toUpperCase()) {
            case "BLOCKER" -> Severity.BLOCKER;
            case "MAJOR" -> Severity.MAJOR;
            case "MINOR" -> Severity.MINOR;
            case "INFO" -> Severity.INFO;
            default -> Severity.MAJOR;
        };
    }

    private static String text(JsonNode node, String field, String def) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? def : v.asText(def);
    }

    /**
     * 从可能含代码块/说明的文本中抽取首个 JSON 数组子串。
     */
    private static String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }
}
