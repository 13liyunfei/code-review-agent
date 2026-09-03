package com.codereview.agent.core.llm;

import com.codereview.agent.core.trace.TraceContext;
import com.codereview.kit.obs.GenAiSpan;
import com.codereview.kit.obs.GenAiTracer;
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
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * LLM 调用监听器（LangChain4j 官方拦截点）：在模型边界统一记录「请求 / 响应」，
 * 把每次调用落成一个 agent-kit {@link GenAiSpan} 交给 {@link GenAiTracer}，
 * 并把 token 用量落进可选的 {@link TokenUsageRecorder}（供 SLA 端点暴露）。
 *
 * <p><b>为什么放在这里</b>：本系统的 LLM 调用有两条路径——
 * <ol>
 *   <li>文本回退路径：经 {@link ModelGateway} → {@link LangChain4jChatProvider} → {@code ChatModel}；</li>
 *   <li>AiServices 结构化路径：{@code CodeReviewAiService} 直接调用 {@code ChatModel}（不经过网关）。</li>
 * </ol>
 * 两条路径最终都落到同一个 {@code OpenAiChatModel}，故在此处挂监听器可<b>一次性覆盖全部 LLM I/O</b>，
 * 且天然携带调用线程的 {@code traceId}（MDC），与审查链路日志同源。
 *
 * <p><b>观测是旁路</b>：tracer / recorder 抛异常不会影响模型调用，也不会打断审查链路。
 */
public class LoggingChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingChatModelListener.class);

    /** 单条日志中请求 / 响应内容的最大展示字符数（超出截断，完整内容见 DEBUG）。 */
    private static final int MAX_CHARS = 800;

    /** 在同一次调用的 request / response / error 三个 context 间共享的计时键。 */
    private static final String START_NANOS = "llm.span.startNanos";

    /** span 记录器（可空：为空时退化为纯日志 + 用量记录）。 */
    private final GenAiTracer tracer;

    /** token 用量记录器（可空：为空时不记录用量）。 */
    private final TokenUsageRecorder usageRecorder;

    public LoggingChatModelListener() {
        this(null, null);
    }

    public LoggingChatModelListener(GenAiTracer tracer) {
        this(tracer, null);
    }

    public LoggingChatModelListener(GenAiTracer tracer, TokenUsageRecorder usageRecorder) {
        this.tracer = tracer;
        this.usageRecorder = usageRecorder;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        try {
            ChatRequest req = ctx.chatRequest();
            if (ctx.attributes() != null) {
                ctx.attributes().put(START_NANOS, System.nanoTime());
            }
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
            TokenUsage usage = tokenUsage(resp);
            long costMs = elapsedMs(ctx.attributes());
            record(ctx.attributes(), model, usage, null,
                    ctx.chatRequest() == null ? 0 : ctx.chatRequest().messages().size(), len);
            recordUsage(model, usage, costMs, true);
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
            record(ctx.attributes(), model, null, err,
                    ctx.chatRequest() == null ? 0 : ctx.chatRequest().messages().size(), 0);
            recordUsage(model, null, elapsedMs(ctx.attributes()), false);
        } catch (Exception e) {
            log.warn("[LLM异常] 日志提取异常：{}", e.getMessage());
        }
    }

    /**
     * 把本次调用落成 agent-kit span。
     */
    private void record(Map<Object, Object> attributes, String model, TokenUsage usage,
                        Throwable error, int messageCount, int outputChars) {
        if (tracer == null) {
            return;
        }
        try {
            long startNanos = attributes == null || attributes.get(START_NANOS) == null
                    ? System.nanoTime()
                    : (long) attributes.get(START_NANOS);
            Integer in = usage == null ? null : usage.inputTokenCount();
            Integer out = usage == null ? null : usage.outputTokenCount();
            tracer.record(GenAiSpan.builder("llm.chat")
                    .traceId(TraceContext.getTraceId())
                    .model("?".equals(model) ? null : model)
                    .durationMs(Math.max(0, (System.nanoTime() - startNanos) / 1_000_000))
                    .tokens(in, out)
                    .error(error)
                    .attribute("messages", String.valueOf(messageCount))
                    .attribute("outputChars", String.valueOf(outputChars))
                    .build());
        } catch (Exception e) {
            log.warn("[LLM观测] span 记录失败（不影响调用）：{}", e.getMessage());
        }
    }

    /** 把本次调用落进 token 用量环形缓冲（失败亦记，方便看错误率）。 */
    private void recordUsage(String model, TokenUsage usage, long costMs, boolean success) {
        if (usageRecorder == null) {
            return;
        }
        try {
            Integer in = usage == null ? null : usage.inputTokenCount();
            Integer out = usage == null ? null : usage.outputTokenCount();
            usageRecorder.record(TokenUsageRecord.of(model, model, in, out, costMs, success));
        } catch (Exception e) {
            log.warn("[LLM观测] token 用量记录失败（不影响调用）：{}", e.getMessage());
        }
    }

    private long elapsedMs(Map<Object, Object> attributes) {
        if (attributes == null) {
            return 0;
        }
        Object o = attributes.get(START_NANOS);
        if (!(o instanceof Long)) {
            return 0;
        }
        return Math.max(0, (System.nanoTime() - (long) o) / 1_000_000);
    }

    private static TokenUsage tokenUsage(ChatResponse resp) {
        return resp == null ? null : resp.tokenUsage();
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