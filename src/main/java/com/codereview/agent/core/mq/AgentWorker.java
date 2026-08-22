package com.codereview.agent.core.mq;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.model.AgentResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单 Agent 消费 Worker（对应文档 AgentWorker.java 的设计）。
 *
 * <p>每个 Agent 拥有专属队列（如 {@code queue:agent:LOGIC}），Worker 阻塞消费任务，
 * 调用 Agent 执行审查，并将 {@link AgentResult} 推送到聚合队列
 * {@link QueueNames#AGGREGATOR}，由 Coordinator 汇聚。
 */
public class AgentWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(AgentWorker.class);

    /** 消费者阻塞超时（秒）。 */
    private static final long POP_TIMEOUT = 5;
    /** 单条消息最大重试次数（与 RedisMessageQueue.MAX_ATTEMPTS 对齐）。 */
    private static final int MAX_ATTEMPTS = 5;

    private final ReviewAgent agent;
    private final String queueName;
    private final MessageQueue mq;
    private final ObjectMapper objectMapper;

    /** 运行标志，stop() 后退出消费循环。 */
    private volatile boolean running = true;

    public AgentWorker(ReviewAgent agent, MessageQueue mq, ObjectMapper objectMapper) {
        this.agent = agent;
        this.queueName = QueueNames.agentQueue(agent.getType().name());
        this.mq = mq;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run() {
        log.info("[Worker] {} 启动，监听队列 {}", agent.getType(), queueName);
        while (running) {
            ReliableDelivery delivery = mq.blockingPopReliable(queueName, POP_TIMEOUT);
            if (delivery == null) {
                continue; // 超时无消息，继续下一轮
            }
            try {
                ReviewTask task = objectMapper.readValue(delivery.payload(), ReviewTask.class);
                // 执行审查（调用 LLM / 规则）
                AgentResult result = new AgentResult(
                        task.prId(), agent.getType(), agent.review(task.diffs(), task.context()));
                // 推送到聚合队列
                mq.publish(QueueNames.AGGREGATOR, objectMapper.writeValueAsString(result));
                mq.ack(queueName, delivery.id());
                log.debug("[Worker] {} 完成 PR#{} 审查，结果已推送聚合队列",
                        agent.getType(), task.prId());
            } catch (Exception e) {
                // 未达上限则重投，否则由队列转死信
                boolean requeue = delivery.attempts() < MAX_ATTEMPTS;
                mq.nack(queueName, delivery.id(), requeue);
                log.error("[Worker] {} 处理消息失败（attempts={}）：{}",
                        agent.getType(), delivery.attempts(), e.getMessage());
            }
        }
        log.info("[Worker] {} 已停止", agent.getType());
    }

    /**
     * 停止消费循环。
     */
    public void stop() {
        this.running = false;
    }
}
