package com.codereview.agent.core.llm;

import dev.langchain4j.model.chat.ChatModel;

/**
 * 基于 LangChain4j {@link ChatModel} 的模型供应商。
 *
 * <p>替代原手写 HTTP 的 {@code OpenAiProvider} / {@code HunyuanProvider}：
 * 统一由 LangChain4j 的 OpenAI 兼容客户端（{@code OpenAiChatModel}）发起调用，
 * 上层 {@link ModelGateway} 的「多厂商 + 配额 + 失败转移」编排保持不变。
 */
public class LangChain4jChatProvider implements ModelProvider {

    private final String name;
    private final ChatModel chatModel;
    private final boolean available;

    /**
     * @param name      供应商名称（用于路由与日志）
     * @param chatModel LangChain4j 聊天模型（OpenAiChatModel 等）
     * @param available 当前是否可用（如缺少 API Key 则为 false，网关自动跳过）
     */
    public LangChain4jChatProvider(String name, ChatModel chatModel, boolean available) {
        this.name = name;
        this.chatModel = chatModel;
        this.available = available;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Override
    public String chat(String prompt) throws Exception {
        String result = chatModel.chat(prompt);
        if (result == null || result.isBlank()) {
            throw new IllegalStateException(name + " 返回空内容");
        }
        return result;
    }
}
