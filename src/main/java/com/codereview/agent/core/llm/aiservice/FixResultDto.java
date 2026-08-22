package com.codereview.agent.core.llm.aiservice;

/**
 * LLM 结构化输出：自动修复建议（仅输出可直接采纳的代码片段）。
 */
public record FixResultDto(String code) {
}
