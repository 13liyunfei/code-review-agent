package com.codereview.agent.core.llm;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

/**
 * 基于 LangChain4j {@link ChatModel} 的模型供应商。
 *
 * <p>替代原手写 HTTP 的 {@code OpenAiProvider} / {@code HunyuanProvider}：
 * 统一由 LangChain4j 的 OpenAI 兼容客户端（{@code OpenAiChatModel}）发起调用，
 * 上层 {@link ModelGateway} 的「多厂商 + 配额 + 失败转移」编排保持不变。
 *
 * <p><b>走 {@code chat(ChatRequest)} 而不是 {@code chat(String)}</b>：只有前者会带出
 * {@link TokenUsage}。直连上游时工厂计量不到这笔消耗，必须靠这里的 usage 事后补报，
 * 否则「工厂不可用时花的钱」就成了账外支出。
 */
public class LangChain4jChatProvider implements UsageAwareModelProvider {

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
        return chatWithUsage(prompt).text();
    }

    @Override
    public ChatOutcome chatWithUsage(String prompt) throws Exception {
        ChatResponse response = chatModel.chat(ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build());
        String text = response == null || response.aiMessage() == null
                ? null
                : response.aiMessage().text();
        if (text == null || text.isBlank()) {
            // 空结果必须当失败——否则上层会拿一个空串去解析，产出「看起来通过」的报告
            throw new IllegalStateException(name + " 返回空内容");
        }
        TokenUsage usage = response == null ? null : response.tokenUsage();
        if (usage == null) {
            return ChatOutcome.textOnly(text);
        }
        return new ChatOutcome(text, usage.inputTokenCount(), usage.outputTokenCount());
    }
}
