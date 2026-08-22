package com.codereview.agent.core.mq;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存实现消息队列（离线演示 / 单元测试用）。
 *
 * <p>每个队列名对应一个 {@link LinkedBlockingQueue}，提供发布与阻塞消费能力，
 * 与 Redis 实现的语义保持一致，可在不引入中间件的情况下跑通队列协同链路。
 *
 * <p>同时实现可靠投递接口：{@link #blockingPopReliable} 返回带交付标识的句柄，
 * 配合 {@link #ack}/{@link #nack} 提供 at-least-once 语义（断网/崩溃场景下不及
 * Redis 实现持久，但单机内存链路足够）。
 */
public class InMemoryMessageQueue implements MessageQueue {

    /** 队列名 -> 阻塞队列（存放原始消息体）。 */
    private final Map<String, BlockingQueue<String>> queues = new ConcurrentHashMap<>();
    /** 队列名 -> 在途交付：deliveryId -> payload。 */
    private final Map<String, Map<String, String>> inflight = new ConcurrentHashMap<>();
    /** 队列名 -> 死信列表。 */
    private final Map<String, java.util.List<String>> dlqs = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    private BlockingQueue<String> queue(String name) {
        return queues.computeIfAbsent(name, k -> new LinkedBlockingQueue<>());
    }

    private Map<String, String> inflight(String name) {
        return inflight.computeIfAbsent(name, k -> new ConcurrentHashMap<>());
    }

    private java.util.List<String> dlq(String name) {
        return dlqs.computeIfAbsent(name, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
    }

    @Override
    public void publish(String queue, String message) {
        this.queue(queue).offer(message);
    }

    @Override
    public String blockingPop(String queue, long timeoutSeconds) {
        try {
            return this.queue(queue).poll(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public ReliableDelivery blockingPopReliable(String queue, long timeoutSeconds) {
        String payload;
        try {
            payload = this.queue(queue).poll(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (payload == null) {
            return null;
        }
        String id = "inmem-" + idGen.incrementAndGet();
        inflight(queue).put(id, payload);
        return new ReliableDelivery(id, payload, 1);
    }

    @Override
    public void ack(String queue, String deliveryId) {
        inflight(queue).remove(deliveryId);
    }

    @Override
    public void nack(String queue, String deliveryId, boolean requeue) {
        String payload = inflight(queue).remove(deliveryId);
        if (payload == null) {
            return;
        }
        if (requeue) {
            this.queue(queue).offer(payload); // 简易重投（内存实现不持久，attempts 复位）
        } else {
            dlq(queue).add(payload);
        }
    }

    @Override
    public long size(String queue) {
        return this.queue(queue).size();
    }
}
