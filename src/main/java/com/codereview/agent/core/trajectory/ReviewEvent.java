package com.codereview.agent.core.trajectory;

import java.util.List;
import java.util.Map;

/**
 * 审查轨迹中的单条事件（不可变）。
 *
 * <p>对齐 deepseek-harness 的 {@code SessionEvent} 与 codex 的 {@code RolloutItem}：
 * 一次审查会话（一个 PR 的一次 run）由一串有序、不可变的事件构成，作为「单一事实源」。
 * 任何进入审查模型、或影响最终结论的内容，都必须先登记为一条事件，
 * 以保证「模型可见即可追溯 / 可重放 / 可审计」（见 {@link ReviewEventLog} 的不变量）。
 *
 * @param type    事件类型（如 review.started / context.injected / agent.completed / review.completed）
 * @param ts      事件时间戳（epoch millis，必须 > 0）
 * @param traceId 全链路追踪 ID（来自 {@code TraceContext}）
 * @param data    事件载荷（任意可序列化键值，禁止存放密钥等敏感信息）
 */
public record ReviewEvent(String type, long ts, String traceId, Map<String, Object> data) {

    /**
     * 轻量校验：防止无效事件污染事实源（对齐 dsh 的 {@code snapshotJsonValue} 前置校验）。
     *
     * @throws IllegalArgumentException 事件类型为空或时间戳非法
     */
    public ReviewEvent {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("ReviewEvent.type 不能为空");
        }
        if (ts <= 0) {
            throw new IllegalArgumentException("ReviewEvent.ts 必须 > 0");
        }
    }
}
