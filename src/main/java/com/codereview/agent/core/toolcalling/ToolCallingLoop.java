package com.codereview.agent.core.toolcalling;

import com.codereview.agent.core.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool Calling 决策循环：「思考 → 决策 → 调用工具 → 观察结果 → 继续推理」。
 *
 * <p>每轮把「目标 + 工具清单 + 历史观察」交给 LLM，要求其输出 JSON 决策：
 * <pre>{ "action": "call_tool" | "finish",
 *       "thought": "...", "tool": "工具名", "arguments": {...},
 *       "answer": "最终结论（action=finish 时）" }</pre>
 *
 * <p>安全边界：最大迭代次数防死循环；工具执行异常不炸循环；LLM 输出非法 JSON
 * 时优雅降级为最终答案（按纯文本返回），绝不抛出中断业务。
 */
public class ToolCallingLoop {

    private static final Logger log = LoggerFactory.getLogger(ToolCallingLoop.class);

    private final LlmClient llm;
    private final ToolRegistry registry;
    private final int maxIterations;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolCallingLoop(LlmClient llm, ToolRegistry registry, int maxIterations) {
        this.llm = llm;
        this.registry = registry;
        this.maxIterations = Math.max(1, maxIterations);
    }

    /** 一次完整的工具调用循环结果。 */
    public record LoopResult(String answer, List<String> toolCalls, int iterations) {}

    public LoopResult run(String goal, String context) {
        List<String> transcript = new ArrayList<>();
        List<String> toolCalls = new ArrayList<>();
        for (int i = 1; i <= maxIterations; i++) {
            String decision = llm.chat(buildPrompt(goal, context, transcript));
            JsonNode json = tryParse(decision);
            if (json == null || !json.hasNonNull("action")) {
                // 非法 JSON：降级为最终答案，绝不让业务失败
                log.warn("[ToolLoop] 第 {} 轮 LLM 输出非 JSON，按最终答案返回", i);
                return new LoopResult(decision, List.copyOf(toolCalls), i);
            }
            String action = json.path("action").asText();
            if ("finish".equalsIgnoreCase(action)) {
                String answer = json.path("answer").asText(decision);
                log.info("[ToolLoop] 目标完成，共 {} 轮，工具调用 {} 次", i, toolCalls.size());
                return new LoopResult(answer, List.copyOf(toolCalls), i);
            }
            // call_tool：查找 → 执行 → 观察写入 transcript
            String toolName = json.path("tool").asText();
            AgentTool tool = registry.get(toolName);
            String observation;
            if (tool == null) {
                observation = "错误：工具 " + toolName + " 不存在，可用工具见清单";
            } else {
                AgentTool.ToolResult r = safeExecute(tool, json.path("arguments"));
                toolCalls.add(toolName);
                observation = (r.success() ? "[观察] " : "[工具错误] ") + r.output();
            }
            transcript.add("工具 " + toolName + " → " + truncate(observation));
        }
        String fallback = "已达最大迭代次数（" + maxIterations + "），基于已有观察给出当前结论：\n"
                + String.join("\n", transcript);
        log.warn("[ToolLoop] 达到最大迭代 {}，返回兜底结论", maxIterations);
        return new LoopResult(fallback, List.copyOf(toolCalls), maxIterations);
    }

    private AgentTool.ToolResult safeExecute(AgentTool tool, JsonNode argsNode) {
        try {
            Map<String, Object> args = new LinkedHashMap<>();
            if (argsNode != null && argsNode.isObject()) {
                argsNode.properties().forEach(e -> args.put(e.getKey(), e.getValue().asText()));
            }
            return tool.execute(args);
        } catch (Exception e) {
            log.warn("[ToolLoop] 工具 {} 执行异常：{}", tool.name(), e.getMessage());
            return AgentTool.ToolResult.fail("工具执行异常: " + e.getMessage());
        }
    }

    private String buildPrompt(String goal, String context, List<String> transcript) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是具备工具调用能力的审查助手。可用工具：\n").append(registry.describeForPrompt())
          .append("\n\n目标：").append(goal).append('\n');
        if (context != null && !context.isBlank()) {
            sb.append("\n背景材料：\n").append(truncate(context)).append('\n');
        }
        if (!transcript.isEmpty()) {
            sb.append("\n历史观察：\n");
            transcript.forEach(t -> sb.append("- ").append(t).append('\n'));
        }
        sb.append("\n仅输出一个 JSON 对象：需要工具时 {\"action\":\"call_tool\",\"thought\":\"...\",")
          .append("\"tool\":\"工具名\",\"arguments\":{...}}；已可结论时 {\"action\":\"finish\",\"answer\":\"...\"}。");
        return sb.toString();
    }

    private JsonNode tryParse(String text) {
        try {
            String t = text.trim();
            int s = t.indexOf('{');
            int e = t.lastIndexOf('}');
            if (s < 0 || e <= s) {
                return null;
            }
            return mapper.readTree(t.substring(s, e + 1));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String truncate(String s) {
        return s.length() <= 800 ? s : s.substring(0, 800) + "...(截断)";
    }
}
