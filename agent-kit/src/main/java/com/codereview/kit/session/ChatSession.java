package com.codereview.kit.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 多轮会话（短期上下文窗口）。
 *
 * <p>维护消息列表并按窗口裁剪（token 估算 + 条数上限），
 * 把历史拼成完整 prompt 交给 {@code ChatModel.chat}——补上 kit 的"多轮上下文"短板。
 */
public class ChatSession {

    private final int maxMessages;
    private final int maxTokens;
    private final List<ChatMessage> messages = new ArrayList<>();
    private int tokenBudget;

    public ChatSession() {
        this(40, 8000);
    }

    /**
     * @param maxMessages 窗口内最大消息条数（超出丢最老的 user/assistant 轮）
     * @param maxTokens   估算 token 预算（简单 4 字符 ≈ 1 token），超出按轮裁剪
     */
    public ChatSession(int maxMessages, int maxTokens) {
        this.maxMessages = maxMessages;
        this.maxTokens = maxTokens;
        this.tokenBudget = maxTokens;
    }

    public ChatSession add(ChatMessage msg) {
        messages.add(msg);
        tokenBudget -= estimateTokens(msg.content());
        trim();
        return this;
    }

    public List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    /** 把全部历史拼成单条 prompt（kit 的 ChatModel 仍是单方法，会话负责组装）。 */
    public String toPrompt(String latestUserPrompt) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            sb.append(m.role()).append(": ").append(m.content()).append("\n");
        }
        if (latestUserPrompt != null && !latestUserPrompt.isBlank()) {
            sb.append("user: ").append(latestUserPrompt);
        }
        return sb.toString();
    }

    public void clear() {
        messages.clear();
        tokenBudget = maxTokens;
    }

    private void trim() {
        // 按 token 预算从老到新裁剪整轮（保留首条 system）
        while (tokenBudget < 0 && messages.size() > 1) {
            ChatMessage removed = messages.remove(1); // 跳过 index 0（system）
            tokenBudget += estimateTokens(removed.content());
        }
        while (messages.size() > maxMessages) {
            ChatMessage removed = messages.remove(1);
            tokenBudget += estimateTokens(removed.content());
        }
    }

    private static int estimateTokens(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
