package com.codereview.agent.core.toolcalling;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.kit.toolcalling.ToolCallingLoop;
import com.codereview.agent.core.model.Severity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 工具增强 Agent 装饰器（横切织入模式）：包装任意 {@link ReviewAgent}，
 * 在其执行审查前先经 {@link ToolCallingLoop} 收集情报（工具观察），
 * 再把观察中 LLM 给出的补充发现与委托 Agent 的结果**合并**返回。
 *
 * <p>委托 Agent 零改动；装饰器异常时退化为纯委托（可降级）。装配层按开关包装。
 */
public class ToolEquippedAgent implements ReviewAgent {

    private final ReviewAgent delegate;
    private final ToolCallingLoop loop;
    private final ObjectMapper mapper = new ObjectMapper();

    public ToolEquippedAgent(ReviewAgent delegate, ToolCallingLoop loop) {
        this.delegate = delegate;
        this.loop = loop;
    }

    @Override public AgentType getType() { return delegate.getType(); }

    @Override public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
        List<Finding> merged = new ArrayList<>(delegate.review(diffs, ctx));
        try {
            String diffText = diffs.stream()
                    .map(d -> "--- " + d.fileName() + " ---\n" + d.patch())
                    .collect(Collectors.joining("\n"));
            if (diffText.length() > 6000) {
                diffText = diffText.substring(0, 6000) + "...(截断)";
            }
            ToolCallingLoop.LoopResult r = loop.run(
                    "为 " + getType() + " 维度审查收集情报；如发现明确问题，以 {\"findings\":"
                            + "[{\"severity\":\"MAJOR\",\"file\":\"...\",\"lineStart\":1,\"title\":\"...\","
                            + "\"description\":\"...\",\"suggestion\":\"...\"}]} 格式输出",
                    diffText);
            merged.addAll(parseFindings(r.answer()));
        } catch (Exception e) {
            // 可降级：工具循环失败不影响委托 Agent 的既有结果
        }
        return merged;
    }

    /** 宽松解析 LLM 工具循环结论中的 findings 数组（无 JSON / 解析失败返回空）。 */
    private List<Finding> parseFindings(String answer) {
        try {
            String t = answer.trim();
            int s = t.indexOf('{');
            int e = t.lastIndexOf('}');
            if (s < 0 || e <= s) {
                return List.of();
            }
            JsonNode arr = mapper.readTree(t.substring(s, e + 1)).path("findings");
            if (!arr.isArray()) {
                return List.of();
            }
            List<Finding> out = new ArrayList<>();
            for (JsonNode f : arr) {
                out.add(new Finding(
                        getType(),
                        f.path("file").asText("unknown"),
                        f.path("lineStart").asInt(1),
                        f.path("lineStart").asInt(1),
                        parseSeverity(f.path("severity").asText("MAJOR")),
                        getType().name().toLowerCase(),
                        getType().name() + "-TOOL",
                        f.path("title").asText("工具发现"),
                        f.path("description").asText(""),
                        f.path("suggestion").asText(""),
                        0.8,
                        "tool-loop"));
            }
            return out;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static Severity parseSeverity(String s) {
        try {
            return Severity.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return Severity.MAJOR;
        }
    }
}
