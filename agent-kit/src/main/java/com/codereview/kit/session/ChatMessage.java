package com.codereview.kit.session;

/**
 * 会话消息（多轮上下文的基本单元）。
 *
 * @param role    角色：system / user / assistant / tool
 * @param content 文本内容
 */
public record ChatMessage(String role, String content) {

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    public static ChatMessage tool(String content) {
        return new ChatMessage("tool", content);
    }
}
