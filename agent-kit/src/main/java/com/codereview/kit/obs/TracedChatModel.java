package com.codereview.kit.obs;

import com.codereview.kit.ChatModel;

import java.util.concurrent.Flow;

/**
 * 可观测 ChatModel 包装：每次调用自动记录 span（耗时 / 可选 token）。
 * 使用方包一层即可获得全链路可观测性，无需改业务代码。
 */
public class TracedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final GenAiTracer tracer;

    public TracedChatModel(ChatModel delegate, GenAiTracer tracer) {
        this.delegate = delegate;
        this.tracer = tracer;
    }

    @Override
    public String chat(String prompt) {
        long start = System.currentTimeMillis();
        String resp = delegate.chat(prompt);
        tracer.record(new GenAiSpan(Long.toHexString(System.nanoTime()), null, "llm.chat",
                System.currentTimeMillis() - start, estimateTokens(prompt), estimateTokens(resp), null));
        return resp;
    }

    @Override
    public Flow.Publisher<String> stream(String prompt) {
        return delegate.stream(prompt);
    }

    static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
