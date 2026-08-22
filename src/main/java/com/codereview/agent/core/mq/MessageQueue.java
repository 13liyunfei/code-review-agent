package com.codereview.agent.core.mq;

/**
 * 消息队列抽象（星型拓扑的任务分发与结果回传通道）。
 *
 * <p>设计上对应文档中基于 Redis 的异步任务分发：各 Agent 拥有专属队列，
 * 审查完成后回写聚合队列，Coordinator 消费聚合队列完成最终一致性聚合。
 * 本仓库提供 {@link InMemoryMessageQueue} 作为离线实现，生产可替换为 Redis 实现。
 */
public interface MessageQueue {

    /**
     * 向指定队列发布一条消息。
     *
     * @param queue   队列名
     * @param message 消息体（建议为 JSON 字符串）
     */
    void publish(String queue, String message);

    /**
     * 阻塞式消费：从队列取出一条消息，超时返回 null。
     *
     * @param queue           队列名
     * @param timeoutSeconds  阻塞等待的最长时间（秒）
     * @return 消息体；超时无消息返回 null
     */
    String blockingPop(String queue, long timeoutSeconds);

    /**
     * 查询队列当前积压消息数。
     *
     * @param queue 队列名
     * @return 积压数量
     */
    long size(String queue);

    /**
     * 可靠消费：原子地将消息从队列移入「处理中」列表并返回带交付标识的句柄。
     *
     * <p>与 {@link #blockingPop} 不同，本方法保证：
     * <ul>
     *   <li>取出后消息暂存于处理中列表，消费者崩溃也不会丢失（由后台恢复线程重投）；</li>
     *   <li>仅当 {@link #ack(String, String)} 被调用才真正删除；</li>
     *   <li>{@link #nack(String, String, boolean)} 可重投或转入死信队列。</li>
     * </ul>
     *
     * @param queue           队列名
     * @param timeoutSeconds  阻塞等待的最长时间（秒）
     * @return 交付句柄；超时无消息返回 null
     */
    ReliableDelivery blockingPopReliable(String queue, long timeoutSeconds);

    /**
     * 确认消费成功：从处理中列表移除该消息（彻底删除）。
     *
     * @param queue       队列名
     * @param deliveryId  来自 {@link ReliableDelivery#id()}
     */
    void ack(String queue, String deliveryId);

    /**
     * 标记消费失败。
     *
     * @param queue       队列名
     * @param deliveryId  来自 {@link ReliableDelivery#id()}
     * @param requeue     true=重新入队（按 attempts 计数，超限转死信）；false=直接进死信队列
     */
    void nack(String queue, String deliveryId, boolean requeue);
}
