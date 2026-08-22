package com.codereview.agent.core.mq;

/**
 * 可靠投递的消息句柄：由 {@link MessageQueue#blockingPopReliable} 返回，
 * 携带消息体 {@link #payload()} 与交付标识 {@link #id()}。
 *
 * <p>消费者处理成功须调用 {@link MessageQueue#ack(String, String)} 确认；
 * 失败则调用 {@link MessageQueue#nack(String, String, boolean)} 重投或进死信。
 * {@link #attempts()} 记录该消息已被投递的次数，供上层决定是否放弃（进死信）。
 */
public final class ReliableDelivery {

    private final String id;
    private final String payload;
    private final int attempts;

    public ReliableDelivery(String id, String payload, int attempts) {
        this.id = id;
        this.payload = payload;
        this.attempts = attempts;
    }

    /** 交付标识（消费方需在 ack/nack 时回传）。 */
    public String id() {
        return id;
    }

    /** 消息体（原始 JSON 字符串）。 */
    public String payload() {
        return payload;
    }

    /** 已被投递的次数（含本次）。 */
    public int attempts() {
        return attempts;
    }

    @Override
    public String toString() {
        return "ReliableDelivery{id='" + id + "', attempts=" + attempts + ", payloadLen="
                + (payload == null ? 0 : payload.length()) + "}";
    }
}
