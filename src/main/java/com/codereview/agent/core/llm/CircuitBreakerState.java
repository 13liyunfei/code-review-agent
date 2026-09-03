package com.codereview.agent.core.llm;

/**
 * 熔断器三态机（标准 Hystrix 语义）。
 *
 * <ul>
 *   <li><b>CLOSED</b>：正常态，所有请求直通；累计连续失败达阈值转入 OPEN；</li>
 *   <li><b>OPEN</b>：熔断态，所有请求直接抛 {@link CircuitOpenException} 拒绝下发，
 *       避免对已知不健康的供应商继续浪费 RTT；持续 {@code openSeconds} 后转入 HALF_OPEN；</li>
 *   <li><b>HALF_OPEN</b>：试探态，允许少量请求试投；任一失败立即回 OPEN，
 *       任一成功恢复 CLOSED。</li>
 * </ul>
 */
public enum CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}