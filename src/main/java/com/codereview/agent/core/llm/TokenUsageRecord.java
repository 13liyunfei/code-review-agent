package com.codereview.agent.core.llm;

import java.time.Instant;

/**
 * 单次 LLM 调用的 token 用量记录（只读 record；持久化交给 Recorder）。
 *
 * <p>对应 JD「Token 计量」诉求——代码审查场景下计费业务不在范围内，
 * 但「每次调用的 token 用量」是后续计费/限额的事实依据，本结构提供最小落地形态。
 */
public record TokenUsageRecord(
        String providerName,
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long durationMs,
        Instant timestamp,
        boolean success
) {
    public static TokenUsageRecord of(String providerName, String model,
                                       Integer in, Integer out,
                                       long durationMs, boolean success) {
        int input = in == null ? 0 : in;
        int output = out == null ? 0 : out;
        return new TokenUsageRecord(
                providerName == null ? "?" : providerName,
                model == null ? "?" : model,
                input, output, input + output,
                durationMs, Instant.now(), success);
    }
}