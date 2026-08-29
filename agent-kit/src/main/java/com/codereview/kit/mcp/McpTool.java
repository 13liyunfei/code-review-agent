package com.codereview.kit.mcp;

import java.util.Map;

/**
 * MCP 工具描述（tools/list 的扁平化视图）。
 *
 * @param name        工具名（tools/call 的 name）
 * @param description 用途描述
 * @param inputSchema JSON Schema（Map 形式，便于直传）
 */
public record McpTool(String name, String description, Map<String, Object> inputSchema) {
}
