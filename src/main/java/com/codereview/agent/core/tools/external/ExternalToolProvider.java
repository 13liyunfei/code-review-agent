package com.codereview.agent.core.tools.external;

import java.util.Map;

/**
 * 外部工具提供者 SPI（对齐 codex {@code mcp_tool} / dsh 插件工具）。
 *
 * <p>使 Skill 体系可接入外部能力（商业 SCA 库、IDE 命令、MCP 服务器等），不硬编码依赖：
 * 实现本接口并通过 {@link ExternalToolRegistry#register} 注册即可在审查链路中按名调用。
 *
 * <p><b>MCP 接入点说明</b>：如需对接标准 MCP 协议，编写一个
 * {@code McpToolProvider implements ExternalToolProvider}，内部持有 MCP 客户端
 * （如 {@code io.modelcontextprotocol:java-sdk}），在 {@link #invoke} 中转发 tool 调用即可。
 * 引擎自身不强制引入 MCP SDK，保持离线可编译。
 */
public interface ExternalToolProvider {

    /** 提供者唯一名称（如 {@code "sca-vendor"}、{@code "mcp-codex"}）。 */
    String name();

    /** 能力描述（展示 / 审计用）。 */
    String description();

    /** 支持的工具清单（如 {@code ["scan", "license-check"]}）。 */
    java.util.Set<String> capabilities();

    /**
     * 调用一个工具。
     *
     * @param tool 工具名（须在 {@link #capabilities()} 中）
     * @param args 参数
     * @return 结构化结果（JSON 字符串或文本）
     * @throws IllegalArgumentException 工具不存在 / 参数非法
     */
    String invoke(String tool, Map<String, Object> args) throws Exception;
}
