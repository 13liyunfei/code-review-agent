package com.codereview.kit.mcp;

import com.codereview.kit.toolcalling.AgentTool;

import java.util.Map;

/**
 * 把 MCP 工具适配为 kit 的 {@link AgentTool}，让工具决策循环直接调用 MCP 生态工具。
 */
public class McpToolAdapter implements AgentTool {

    private final McpClient client;
    private final McpTool tool;

    public McpToolAdapter(McpClient client, McpTool tool) {
        this.client = client;
        this.tool = tool;
    }

    @Override
    public String name() {
        return tool.name();
    }

    @Override
    public String description() {
        return tool.description();
    }

    @Override
    public String parameterSchema() {
        return tool.inputSchema() == null ? "{}" : tool.inputSchema().toString();
    }

    @Override
    public ToolResult execute(Map<String, Object> args) {
        try {
            String out = client.callTool(tool.name(), args);
            return ToolResult.ok(out);
        } catch (Exception e) {
            return ToolResult.fail("MCP 调用失败: " + e.getMessage());
        }
    }
}
