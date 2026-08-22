package com.codereview.agent.core.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 无操作聊天模型（Mock 兜底）。
 *
 * <p>当未配置任何真实模型 API Key 时，作为 {@link ChatModel} Bean 的占位实现，
 * 保证 {@code AiServices} 装配链在离线环境也可用；上层 Agent 在结构化输出为空/失败时
 * 自动回退到 {@link MockLlmClient} 文本路径，整体行为与改造前一致。
 */
public class NoOpChatModel implements ChatModel {

    @Override
    public ChatResponse chat(ChatRequest request) {
        return ChatResponse.builder().aiMessage(AiMessage.from("")).build();
    }
}
