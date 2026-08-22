package com.codereview.agent.core.prompt;

import java.util.Map;

/**
 * 提示词模板抽象（告别硬编码 Prompt）。
 *
 * <p>依据文档“Prompt 模板化”设计：提示词走模板文件 + 变量渲染，支持动态注入
 * RAG 上下文与历史经验。具体渲染策略由实现类决定。
 */
public interface PromptTemplate {

    /**
     * 使用变量渲染模板，生成最终提示词。
     *
     * @param variables 变量键值对
     * @return 渲染后的提示词文本
     */
    String render(Map<String, Object> variables);

    /**
     * 模板名称（通常与文件名一致，如 security_review）。
     *
     * @return 模板名
     */
    String getName();
}
