package com.codereview.agent.core.llm;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.Set;

/**
 * 默认失败分类器（基于异常类型与消息文本）。
 *
 * <p>实现策略见 {@link RetryClassifier} 类注释。本类刻意保持简单——
 * 不引入 HTTP 客户端的 status code 依赖，避免与 LangChain4j 的具体错误模型耦合。
 */
public class DefaultRetryClassifier implements RetryClassifier {

    /** 可重试异常类型集合（按 class.isInstance 判定）。 */
    private static final Set<Class<? extends Throwable>> RETRYABLE_TYPES = Set.of(
            ConnectException.class,
            SocketTimeoutException.class,
            java.net.UnknownHostException.class,
            java.util.concurrent.TimeoutException.class
    );

    /** 消息文本包含下列标记（大小写不敏感）即视为可重试。 */
    private static final Set<String> RETRYABLE_MARKERS = Set.of(
            "429", "503", "504", "502", "rate limit", "too many requests",
            "temporarily unavailable", "try again", "timeout", "connection reset",
            "connection refused");

    /** 永久错误标记——若文本含下列标记，强制按永久处理。 */
    private static final Set<String> PERMANENT_MARKERS = Set.of(
            "未配置", "no api key", "401", "403", "400", "invalid api key",
            "unauthorized", "forbidden", "format error");

    @Override
    public boolean isRetryable(Throwable e) {
        if (e == null) {
            return false;
        }
        // 1) 熔断器异常：永久（重试无意义）
        if (e instanceof CircuitOpenException) {
            return false;
        }
        // 2) 参数错误：永久
        if (e instanceof IllegalArgumentException) {
            return false;
        }
        // 3) 状态错误（缺 Key / 空响应等业务前置错误）：永久
        if (e instanceof IllegalStateException) {
            String msg = safeMessage(e);
            if (msg != null && containsAny(msg, PERMANENT_MARKERS)) {
                return false;
            }
            // 默认保守：IllegalState 通常是配置问题，按永久处理
            return false;
        }
        // 4) 已知可重试异常类型：可重试
        for (Class<? extends Throwable> t : RETRYABLE_TYPES) {
            if (t.isInstance(e)) {
                return true;
            }
        }
        // 5) 消息文本含「429 / 503 / timeout」等：可重试
        String msg = safeMessage(e);
        if (msg != null) {
            if (containsAny(msg, RETRYABLE_MARKERS)) {
                return true;
            }
            if (containsAny(msg, PERMANENT_MARKERS)) {
                return false;
            }
        }
        // 6) 默认：可重试（与历史「失败立即切下一供应商」相比，多一次重试机会；保守选择）
        return true;
    }

    private static String safeMessage(Throwable e) {
        try {
            return e.getMessage();
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static boolean containsAny(String text, Set<String> markers) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String m : markers) {
            if (m == null || m.isEmpty()) {
                continue;
            }
            if (lower.contains(m.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}