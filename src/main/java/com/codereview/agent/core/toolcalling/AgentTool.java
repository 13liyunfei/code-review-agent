package com.codereview.agent.core.toolcalling;

import java.util.Map;

/**
 * Agent 可调用的工具接口（Tool Calling / Function Calling 的工具侧契约）。
 *
 * <p>实现类只负责「声明自己 + 执行」，决策（何时调用、传什么参数）由
 * {@link ToolCallingLoop} 驱动 LLM 完成，实现类不感知 LLM。
 */
public interface AgentTool {

    /** 工具唯一名（LLM 决策时引用）。 */
    String name();

    /** 一句话描述（进入 LLM 决策上下文，影响模型是否选用该工具）。 */
    String description();

    /** 参数 JSON Schema 描述（简洁文本即可，如 {@code {"path":"string"}}）。 */
    String parameterSchema();

    /**
     * 执行工具。实现必须自兜底：任何异常捕获后返回失败结果，绝不抛出。
     *
     * @param args LLM 决策给出的参数（键值对）
     * @return 执行结果（success + 面向 LLM 的文本输出）
     */
    ToolResult execute(Map<String, Object> args);

    /** 工具执行结果（success=true 时 output 供 LLM 作为观察继续推理）。 */
    record ToolResult(boolean success, String output) {
        public static ToolResult ok(String output) { return new ToolResult(true, output); }
        public static ToolResult fail(String output) { return new ToolResult(false, output); }
    }
}
