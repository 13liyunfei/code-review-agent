package com.codereview.agent.core.llm.aiservice;

/**
 * LLM 结构化输出：单条审查发现（对应原 LlmFindingParser 解析的 JSON 条目）。
 *
 * <p>由 LangChain4j AiServices 将模型返回的 JSON 反序列化为该 record，
 * 取代「返回文本 + 正则/子串抽取」的脆弱解析方式。
 */
public record ReviewFindingDto(
        String ruleId,
        String title,
        String description,
        String suggestion,
        String severity,
        String file,
        Integer line,
        Double confidence
) {
}
