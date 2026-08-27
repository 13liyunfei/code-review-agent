package com.codereview.agent.core.toolcalling.impl;

import com.codereview.agent.core.toolcalling.AgentTool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * 内置示例工具集（开箱即用的最小工具样例，演示完整 Tool Calling 链路）。
 *
 * <p>新增工具只需实现 {@link AgentTool} 并向 {@link com.codereview.agent.core.toolcalling.ToolRegistry}
 * 注册，决策逻辑由 {@link com.codereview.agent.core.toolcalling.ToolCallingLoop} 通用驱动。
 */
public final class BuiltinTools {

    private BuiltinTools() {}

    /** 当前时间工具：最简演示，验证「决策 → 调用 → 观察」闭环。 */
    public static class CurrentTimeTool implements AgentTool {
        @Override public String name() { return "current_time"; }
        @Override public String description() { return "获取当前服务器时间（yyyy-MM-dd HH:mm:ss）"; }
        @Override public String parameterSchema() { return "{}"; }
        @Override public ToolResult execute(Map<String, Object> args) {
            return ToolResult.ok(LocalDateTime.now().withNano(0).toString().replace('T', ' '));
        }
    }

    /** 正则扫描工具：对给定文本执行正则匹配并返回命中行（审查场景：验证规则是否命中）。 */
    public static class RegexScanTool implements AgentTool {
        @Override public String name() { return "regex_scan"; }
        @Override public String description() { return "在给定文本中执行正则匹配，返回命中行列表"; }
        @Override public String parameterSchema() { return "{\"text\":\"待扫描文本\",\"regex\":\"正则表达式\"}"; }
        @Override public ToolResult execute(Map<String, Object> args) {
            String text = String.valueOf(args.getOrDefault("text", ""));
            String regex = String.valueOf(args.getOrDefault("regex", ""));
            try {
                Pattern p = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
                List<String> hits = text.lines().filter(l -> p.matcher(l).find()).limit(20).toList();
                return ToolResult.ok(hits.isEmpty() ? "未命中" : String.join("\n", hits));
            } catch (PatternSyntaxException e) {
                return ToolResult.fail("非法正则: " + e.getMessage());
            }
        }
    }

    /** 受限文件读取工具：仅允许读取白名单根目录内文件（防路径穿越），供 Agent 查看仓库材料。 */
    public static class FileReadTool implements AgentTool {
        private final Path baseDir;
        public FileReadTool(Path baseDir) {
            this.baseDir = baseDir == null ? Path.of(".").toAbsolutePath().normalize() : baseDir;
        }
        @Override public String name() { return "file_read"; }
        @Override public String description() { return "读取白名单目录内指定相对路径的文本文件（前 100 行）"; }
        @Override public String parameterSchema() { return "{\"path\":\"相对 baseDir 的文件路径\"}"; }
        @Override public ToolResult execute(Map<String, Object> args) {
            String rel = String.valueOf(args.getOrDefault("path", ""));
            try {
                Path target = baseDir.resolve(rel).normalize();
                if (!target.startsWith(baseDir)) {
                    return ToolResult.fail("拒绝访问：路径越界 " + rel);
                }
                if (!Files.isRegularFile(target)) {
                    return ToolResult.fail("文件不存在: " + rel);
                }
                String body = Files.readAllLines(target, StandardCharsets.UTF_8).stream()
                        .limit(100).collect(Collectors.joining("\n"));
                return ToolResult.ok(body.isBlank() ? "(空文件)" : body);
            } catch (IOException e) {
                return ToolResult.fail("读取失败: " + e.getMessage());
            }
        }
    }
}
