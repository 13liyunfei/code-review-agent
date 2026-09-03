package com.codereview.agent.core.llm;

/**
 * 失败分类器：把异常归类为「可重试（暂时性）」与「不可重试（永久性）」。
 *
 * <p>对应 JD「故障降级」诉求：原 ModelGateway 失败立即切下一供应商，对临时错误
 * （如 HTTP 429/503、网络抖动、超时）浪费一次重试机会，对永久错误（如 401 未配置 Key、
 * 400 提示词格式）也会白白走退避后才放弃。
 *
 * <p><b>策略</b>：默认实现 {@link DefaultRetryClassifier} 按以下规则归类：
 * <ul>
 *   <li>{@link CircuitOpenException}：不可重试（熔断本身就说明重试无意义）；</li>
 *   <li>{@link IllegalArgumentException}：不可重试（参数错误，重试仍错）；</li>
 *   <li>{@link IllegalStateException} 含「未配置」「空内容」：不可重试；</li>
 *   <li>{@link java.net.ConnectException} / {@link java.net.SocketTimeoutException}：
 *       可重试（网络临时故障）；</li>
 *   <li>{@link RuntimeException} 含 429/503/504 标记：可重试；</li>
 *   <li>其他异常：保守按可重试（与历史「失败转移」一致，宁多切一次也别漏）。</li>
 * </ul>
 */
public interface RetryClassifier {

    /**
     * @return true 表示「暂时性错误」，可在本供应商上退避重试；
     *         false 表示「永久性错误」，应立即跳到下一供应商。
     */
    boolean isRetryable(Throwable e);
}