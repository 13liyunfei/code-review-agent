package com.codereview.kit.obs;

/**
 * 一次 GenAI 调用的可观测 span（对齐 OTel GenAI 语义约定的最小字段）。
 *
 * @param spanId      本 span id（trace 内唯一）
 * @param parentId    父 span id（可空）
 * @param operation   操作名（如 llm.chat / tool.call / plan）
 * @param durationMs  耗时
 * @param inputTokens 输入 token 数（可空）
 * @param outputTokens 输出 token 数（可空）
 * @param cost        成本（估算，可空）
 */
public record GenAiSpan(String spanId, String parentId, String operation,
                        long durationMs, Integer inputTokens, Integer outputTokens, Double cost) {
}
