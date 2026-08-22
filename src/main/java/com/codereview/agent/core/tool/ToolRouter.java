package com.codereview.agent.core.tool;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具路由器（解决“LLM 选错工具”问题，见文档可靠性工程）。
 *
 * <p>核心思路：不让 LLM 直接面对全部工具，而是先过一层意图分类，再把
 * 该意图允许的工具白名单交给 LLM，从机制上避免越权调用。
 *
 * <p>本实现以关键词做确定性意图分类（离线可用）；生产环境可将
 * {@link #classifyIntent(String)} 替换为轻量级 LLM 分类调用，接口不变。
 */
@Component
public class ToolRouter {

    /** 意图标签。 */
    public enum Intent {
        SQL_INJECTION, XSS, SECRET_LEAK, PERFORMANCE, STYLE, GENERAL
    }

    /** 意图 -> 允许的工具名白名单（硬编码映射，LLM 不可绕过）。 */
    private static final Map<Intent, List<String>> INTENT_TOOLS = Map.of(
            Intent.SQL_INJECTION, List.of("semgrep_scan", "taint_analysis", "schema_checker"),
            Intent.XSS, List.of("semgrep_scan", "dom_analyzer"),
            Intent.SECRET_LEAK, List.of("git_leaks", "entropy_scanner"),
            Intent.PERFORMANCE, List.of("profiler", "complexity_analyzer"),
            Intent.STYLE, List.of("style_linter"),
            Intent.GENERAL, List.of()
    );

    /** 工具注册表：工具名 -> 定义。 */
    private final Map<String, ToolDefinition> toolRegistry = new HashMap<>();

    /**
     * 通过构造函数注入全部工具定义并建索引。
     *
     * @param tools 所有可用工具（Spring 自动收集）
     */
    public ToolRouter(List<ToolDefinition> tools) {
        for (ToolDefinition t : tools) {
            toolRegistry.put(t.name(), t);
        }
    }

    /**
     * 根据提示词选择该意图允许的工具子集。
     *
     * @param userPrompt 审查提示词
     * @return 允许的工具定义列表
     */
    public List<ToolDefinition> selectTools(String userPrompt) {
        List<Intent> intents = classifyIntent(userPrompt);
        Set<String> allowedNames = intents.stream()
                .flatMap(i -> INTENT_TOOLS.getOrDefault(i, List.of()).stream())
                .collect(Collectors.toSet());
        return allowedNames.stream()
                .map(toolRegistry::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 意图分类（确定性关键词分类；生产可替换为 LLM）。
     *
     * @param prompt 提示词
     * @return 意图列表
     */
    protected List<Intent> classifyIntent(String prompt) {
        List<Intent> intents = new ArrayList<>();
        String p = prompt == null ? "" : prompt.toLowerCase();
        if (p.contains("sql") || p.contains("注入") || p.contains("inject")) {
            intents.add(Intent.SQL_INJECTION);
        }
        if (p.contains("xss") || p.contains("跨站") || p.contains("dom")) {
            intents.add(Intent.XSS);
        }
        if (p.contains("密钥") || p.contains("secret") || p.contains("token") || p.contains("password")) {
            intents.add(Intent.SECRET_LEAK);
        }
        if (p.contains("性能") || p.contains("performance") || p.contains("复杂度") || p.contains("n+1")) {
            intents.add(Intent.PERFORMANCE);
        }
        if (p.contains("规范") || p.contains("style") || p.contains("命名") || p.contains("格式")) {
            intents.add(Intent.STYLE);
        }
        if (intents.isEmpty()) {
            intents.add(Intent.GENERAL);
        }
        return intents;
    }

    /**
     * 获取所有已注册工具名（辅助）。 */
    public Set<String> registeredToolNames() {
        return toolRegistry.keySet();
    }

    /**
     * 默认工具集（供演示 / 装配参考）。
     */
    public static List<ToolDefinition> defaultTools() {
        return Arrays.asList(
                new ToolDefinition("semgrep_scan", "SAST 静态扫描，检测常见安全漏洞", "security"),
                new ToolDefinition("taint_analysis", "污点分析，追踪不可信输入流向", "security"),
                new ToolDefinition("schema_checker", "数据库 Schema 一致性检查", "security"),
                new ToolDefinition("dom_analyzer", "前端 DOM 渲染安全分析", "security"),
                new ToolDefinition("git_leaks", "密钥泄露扫描", "security"),
                new ToolDefinition("entropy_scanner", "高熵字符串检测（疑似密钥）", "security"),
                new ToolDefinition("profiler", "方法级性能剖析", "performance"),
                new ToolDefinition("complexity_analyzer", "圈复杂度分析", "performance"),
                new ToolDefinition("style_linter", "编码规范检查", "style")
        );
    }
}
