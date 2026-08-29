package com.codereview.kit.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionTest {

    @Test
    void 消息按条数上限裁剪保留system() {
        ChatSession session = new ChatSession(5, 100000);
        session.add(ChatMessage.system("你是审查助手"));
        for (int i = 0; i < 10; i++) {
            session.add(ChatMessage.user("问题" + i));
        }
        assertEquals(5, session.messages().size());
        assertEquals("system", session.messages().get(0).role());
        assertTrue(session.messages().get(1).content().contains("6")); // 最老的被丢
    }

    @Test
    void 超token预算时按轮裁剪() {
        ChatSession session = new ChatSession(100, 100); // 预算很小
        session.add(ChatMessage.system("短"));
        for (int i = 0; i < 20; i++) {
            session.add(ChatMessage.user("这是一条很长的消息内容用于撑爆 token 预算，".repeat(5)));
        }
        assertTrue(session.messages().size() < 20, "token 超预算应裁剪，实际=" + session.messages().size());
        assertEquals("system", session.messages().get(0).role());
    }

    @Test
    void toPrompt拼接多轮历史() {
        ChatSession session = new ChatSession();
        session.add(ChatMessage.system("sys"));
        session.add(ChatMessage.user("你好"));
        session.add(ChatMessage.assistant("在的"));
        String p = session.toPrompt("现在呢");
        assertTrue(p.contains("system: sys"));
        assertTrue(p.contains("assistant: 在的"));
        assertTrue(p.endsWith("user: 现在呢"));
    }

    @Test
    void clear重置会话() {
        ChatSession session = new ChatSession();
        session.add(ChatMessage.user("x"));
        session.clear();
        assertTrue(session.messages().isEmpty());
    }
}
