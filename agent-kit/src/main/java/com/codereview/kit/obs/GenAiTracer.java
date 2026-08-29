package com.codereview.kit.obs;

/**
 * GenAI 可观测性 tracer（记录调用 span，供链路追踪 / 成本核算 / 质量评估）。
 */
public interface GenAiTracer {

    /** 记录一次已完成调用。 */
    void record(GenAiSpan span);

    /** 便捷：记录一次 LLM 调用（自动计时）。 */
    default GenAiSpan record(String operation, Runnable call) {
        long start = System.currentTimeMillis();
        call.run();
        long ms = System.currentTimeMillis() - start;
        GenAiSpan span = new GenAiSpan(genId(), null, operation, ms, null, null, null);
        record(span);
        return span;
    }

    private String genId() {
        return Long.toHexString(System.nanoTime());
    }
}
