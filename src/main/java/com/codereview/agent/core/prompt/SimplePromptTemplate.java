package com.codereview.agent.core.prompt;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级占位符提示词模板（零依赖、可离线）。
 *
 * <p>支持 {@code ${key}} 形式的变量替换；变量不存在时保留原占位符，
 * 避免渲染失败。生产环境可替换为 FreeMarker / Velocity 等模板引擎实现
 * （同 {@link PromptTemplate} 接口即可无缝切换）。
 */
public class SimplePromptTemplate implements PromptTemplate {

    /** 匹配 ${name} 占位符。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([a-zA-Z0-9_.]+)}");

    private final String name;
    private final String template;

    /**
     * 构造模板。
     *
     * @param name     模板名
     * @param template 模板内容
     */
    public SimplePromptTemplate(String name, String template) {
        this.name = name;
        this.template = template;
    }

    @Override
    public String render(Map<String, Object> variables) {
        if (template == null) {
            return "";
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = variables == null ? null : variables.get(key);
            // 变量缺失时保留占位符，便于排查；存在时转换为字符串
            String replacement = (value == null) ? matcher.group(0) : value.toString();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @Override
    public String getName() {
        return name;
    }
}
