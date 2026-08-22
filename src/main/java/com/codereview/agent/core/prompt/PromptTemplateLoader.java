package com.codereview.agent.core.prompt;

import java.util.Map;

/**
 * 提示词模板加载器抽象（模板来源可插拔）。
 *
 * <p>实现可从 classpath、数据库或远端配置中心加载模板。
 */
public interface PromptTemplateLoader {

    /**
     * 按名称加载模板。
     *
     * @param templateName 模板名（不含扩展名）
     * @return 渲染可用的 {@link PromptTemplate}
     */
    PromptTemplate load(String templateName);

    /**
     * 按名称与变量直接渲染（便捷方法）。
     *
     * @param templateName 模板名
     * @param variables    变量
     * @return 渲染后的提示词
     */
    default String render(String templateName, Map<String, Object> variables) {
        return load(templateName).render(variables);
    }
}
