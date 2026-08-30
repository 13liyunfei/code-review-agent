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
 * 并把每次调用落成一个 agent-kit {@link GenAiSpan} 交给 {@link GenAiTracer}。
 *
 * <p>为何放在这里：本系统的 LLM 调用有两条路径——
 * <ol>
 *   <li>文本回退路径：经 {@link ModelGateway} → {@link LangChain4jChatProvider} → {@code ChatModel}；</li>
 *   <li>AiServices 结构化路径：{@code CodeReviewAiService} 直接调用 {@code ChatModel}（不经过网关）。</li>
 * </ol>
 * 两条路径最终都落到同一个 {@code OpenAiChatModel}，故在此处挂监听器可<b>一次性覆盖全部 LLM I/O</b>，
 * 且天然携带调用线程的 {@code traceId}（MDC），与审查链路日志同源。
 *
 * <p><b>与 agent-kit 的分工</b>：
 * <ul>
 *   <li>本类只做"翻译"——把 LangChain4j 的回调翻译成 {@link GenAiSpan}，带上业务链路 id
 *       （{@link TraceContext}）与模型回传的真实 token 用量；</li>
 *   <li>span 的记录 / 聚合 / 成本核算全部交给 agent-kit 的 tracer
 *       （{@code AggregateTracer}、{@code LoggingGenAiTracer} 或二者的组合），本仓库不自建指标体系。</li>
 * </ul>
 *
 * <p>日志策略：
 * <ul>
 *   <li>INFO：记录模型名、消息数 / 输出长度，内容截断到前 {@value #MAX_CHARS} 字符（防止超大 prompt 刷屏）；</li>
 *   <li>DEBUG：输出完整请求 / 响应内容（需开启对应包 DEBUG 才打印）。</li>
 * </ul>
 *
 * <p>观测是旁路：tracer 抛异常不会影响模型调用，也不会打断审查链路。
 */
public class LoggingChatModelListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingChatModelListener.class);

    /** 单条日志中请求 / 响应内容的最大展示字符数（超出截断，完整内容见 DEBUG）。 */
    private static final int MAX_CHARS = 800;

    /** 在同一次调用的 request / response / error 三个 context 间共享的计时键。 */
    private static final String START_NANOS = "llm.span.startNanos";

    /** span 记录器（可空：为空时退化为纯日志，与历史行为一致）。 */
    private final GenAiTracer tracer;

    public LoggingChatModelListener() {
        this(null);
    }

    public LoggingChatModelListener(GenAiTracer tracer) {
        this.tracer = tracer;
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
            record(ctx.attributes(), model, tokenUsage(resp), null,
                    ctx.chatRequest() == null ? 0 : ctx.chatRequest().messages().size(), len);
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
        } catch (Exception e) {
            log.warn("[LLM异常] 日志提取异常：{}", e.getMessage());
        }
    }

    /**
     * 把本次调用落成 agent-kit span。
     *
     * <p>耗时取自 request / response 共享的 {@link #START_NANOS}（纳秒单调时钟）；
     * traceId 取业务链路上下文 {@link TraceContext}，从而在聚合侧能把一次审查里
     * 5 个并行 Agent 的调用按链路串起来。
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
