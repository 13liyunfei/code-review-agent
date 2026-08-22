package com.codereview.agent.core.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * LLM 调用监听器（LangChain4j 官方拦截点）：在模型边界统一记录「请求 / 响应」，
 * 用于全链路追踪与线上问题定位。
 *
 * <p>为何放在这里：本系统的 LLM 调用有两条路径——
 * <ol>
 *   <li>文本回退路径：经 {@link ModelGateway} → {@link LangChain4jChatProvider} → {@code ChatModel}；</li>
 *   <li>AiServices 结构化路径：{@code CodeReviewAiService} 直接调用 {@code ChatModel}（不经过网关）。</li>
 * </ol>
 * 两条路径最终都落到同一个 {@code OpenAiChatModel}，故在此处挂监听器可<b>一次性覆盖全部 LLM I/O</b>，
 * 且天然携带调用线程的 {@code traceId}（MDC），与审查链路日志同源。
 *
 * <p>日志策略：
 * <ul>
 *   <li>INFO：记录模型名、消息数 / 输出长度，内容截断到前 {@value #MAX_CHARS} 字符（防止超大 prompt 刷屏）；</li>
 *   <li>DEBUG：输出完整请求 / 响应内容（需开启对应包 DEBUG 才打印）。</li>
 * </ul>
 */
public class LoggingChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingChatModelListener.class);

    /** 单条日志中请求 / 响应内容的最大展示字符数（超出截断，完整内容见 DEBUG）。 */
    private static final int MAX_CHARS = 800;

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        try {
            ChatRequest req = ctx.chatRequest();
            String model = safeModel(req);
            String content = renderMessages(req);
            log.info("[LLM请求] model={}, 消息数={}, 内容(前{}字符)=\n{}",
                    model, req.messages().size(), MAX_CHARS, truncate(content, MAX_CHARS));
            if (log.isDebugEnabled()) {
                log.debug("[LLM请求-完整] model={}\n{}", model, content);
            }
        } catch (Exception e) {
            log.warn("[LLM请求] 日志提取异常：{}", e.getMessage());
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        try {
            ChatResponse resp = ctx.chatResponse();
            String model = resp == null ? "?" : safe(resp.modelName());
            String text = resp != null && resp.aiMessage() != null ? resp.aiMessage().text() : "";
            int len = text == null ? 0 : text.length();
            log.info("[LLM响应] model={}, 输出长度={}, 内容(前{}字符)=\n{}",
                    model, len, MAX_CHARS, truncate(text, MAX_CHARS));
            if (log.isDebugEnabled()) {
                log.debug("[LLM响应-完整] model={}\n{}", model, text);
            }
        } catch (Exception e) {
            log.warn("[LLM响应] 日志提取异常：{}", e.getMessage());
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        try {
            String model = ctx.chatRequest() == null ? "?" : safe(ctx.chatRequest().modelName());
            Throwable err = ctx.error();
            log.error("[LLM异常] model={}, error={}", model, err == null ? "null" : err.getMessage());
        } catch (Exception e) {
            log.warn("[LLM异常] 日志提取异常：{}", e.getMessage());
        }
    }

    /** 将请求中的多轮消息拼为可读文本（按消息类型前缀）。 */
    private static String renderMessages(ChatRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req.messages() == null) {
            return "<无消息>";
        }
        for (ChatMessage m : req.messages()) {
            sb.append('[').append(m.type()).append("] ")
                    .append(messageText(m)).append('\n');
        }
        return sb.toString();
    }

    /** 兼容不同消息类型的文本提取（UserMessage 用 singleText，System/AiMessage 用 text）。 */
    private static String messageText(ChatMessage m) {
        try {
            if (m instanceof AiMessage am) {
                return am.text();
            }
            if (m instanceof UserMessage um) {
                return um.singleText();
            }
            if (m instanceof SystemMessage sm) {
                return sm.text();
            }
            return m.toString();
        } catch (Exception e) {
            return "<文本提取失败:" + e.getMessage() + ">";
        }
    }

    private static String safeModel(ChatRequest req) {
        try {
            return req == null ? "?" : safe(req.modelName());
        } catch (Exception e) {
            return "?";
        }
    }

    private static String safe(String s) {
        return s == null ? "?" : s;
    }

    /** 截断超长文本，避免日志被巨大 prompt / 响应刷屏。 */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "null";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...(已截断, 共" + s.length() + "字符, 完整内容请开 DEBUG)";
    }
}
