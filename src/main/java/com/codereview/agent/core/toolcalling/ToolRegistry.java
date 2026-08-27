package com.codereview.agent.core.toolcalling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心：Agent 可用工具的唯一清单，供 {@link ToolCallingLoop} 查找与生成决策上下文。
 *
 * <p>线程安全；同名重复注册后者覆盖并告警。
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

    public void register(AgentTool tool) {
        AgentTool prev = tools.put(tool.name(), tool);
        if (prev != null) {
            log.warn("[ToolRegistry] 工具 {} 被重复注册，覆盖前一实现", tool.name());
        }
    }

    public AgentTool get(String name) {
        return tools.get(name);
    }

    public List<AgentTool> list() {
        return List.copyOf(tools.values());
    }

    /** 生成给 LLM 的工具清单文本（决策上下文的一部分）。 */
    public String describeForPrompt() {
        List<String> lines = new ArrayList<>();
        for (AgentTool t : list()) {
            lines.add("- " + t.name() + ": " + t.description() + " 参数: " + t.parameterSchema());
        }
        return String.join("\n", lines);
    }
}
