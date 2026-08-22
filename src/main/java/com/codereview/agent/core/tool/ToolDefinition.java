package com.codereview.agent.core.tool;

/**
 * 工具定义（供 LLM Function Calling 使用）。
 *
 * @param name        工具名（如 semgrep_scan）
 * @param description 工具能力描述（用于注入提示词，帮助模型决策）
 * @param category    工具分类（如 security / performance）
 */
public record ToolDefinition(String name, String description, String category) {
}
