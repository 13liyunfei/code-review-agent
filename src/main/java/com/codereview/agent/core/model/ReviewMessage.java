package com.codereview.agent.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Agent 间标准化通信消息（星型拓扑的传输载体）。
 *
 * <p>依据文档设计，所有专业 Agent 只与 Coordinator 通信，消息采用统一协议：
 * <pre>
 * {
 *   "message_id": "uuid",
 *   "sender": "security_agent",
 *   "receiver": "coordinator",
 *   "type": "review_comment",
 *   "payload": { ... },          // JSON 字符串
 *   "timestamp": "2026-08-16T16:10:00Z"
 * }
 * </pre>
 */
public class ReviewMessage {

    /** 消息类型枚举。 */
    public enum Type {
        /** 任务下发：Coordinator 将审查任务派发给某 Agent。 */
        TASK_DISPATCH,
        /** 审查意见：Agent 回传审查发现。 */
        REVIEW_COMMENT,
        /** 聚合完成：Coordinator 通知结果已汇聚。 */
        AGGREGATION_DONE,
        /** 心跳 / 状态。 */
        HEARTBEAT
    }

    private final String messageId;
    private final String sender;
    private final String receiver;
    private final Type type;
    private final String payload;
    private final String timestamp;

    @JsonCreator
    public ReviewMessage(
            @JsonProperty("message_id") String messageId,
            @JsonProperty("sender") String sender,
            @JsonProperty("receiver") String receiver,
            @JsonProperty("type") Type type,
            @JsonProperty("payload") String payload,
            @JsonProperty("timestamp") String timestamp) {
        this.messageId = messageId;
        this.sender = sender;
        this.receiver = receiver;
        this.type = type;
        this.payload = payload;
        this.timestamp = timestamp;
    }

    /**
     * 构造一条消息，自动生成 messageId 与时间戳。
     *
     * @param sender   发送方
     * @param receiver 接收方
     * @param type     消息类型
     * @param payload  负载（JSON 字符串）
     */
    public ReviewMessage(String sender, String receiver, Type type, String payload) {
        this(UUID.randomUUID().toString(),
                sender,
                receiver,
                type,
                payload,
                Instant.now().toString());
    }

    public String getMessageId() {
        return messageId;
    }

    public String getSender() {
        return sender;
    }

    public String getReceiver() {
        return receiver;
    }

    public Type getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public String getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "ReviewMessage{" +
                "messageId='" + messageId + '\'' +
                ", sender='" + sender + '\'' +
                ", receiver='" + receiver + '\'' +
                ", type=" + type +
                ", timestamp='" + timestamp + '\'' +
                '}';
    }
}
