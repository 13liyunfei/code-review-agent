package com.codereview.agent.core.rag;

/**
 * 恒等改写器：原样返回查询（默认实现 / 离线降级兜底）。
 *
 * <p>无 LLM 依赖、零开销、行为完全可预测；作为 {@code LlmQueryRewriter} 的降级目标，
 * 保证「改写层不可用 ≠ 检索链路不可用」。
 */
public class IdentityQueryRewriter implements ReviewQueryRewriter {

    @Override
    public String rewrite(String rawQuery) {
        return rawQuery == null ? "" : rawQuery;
    }

    @Override
    public String name() {
        return "identity";
    }
}
