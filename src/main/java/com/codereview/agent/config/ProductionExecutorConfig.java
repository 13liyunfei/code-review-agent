package com.codereview.agent.config;

import com.codereview.agent.integration.gitea.GiteaWebhookController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** 生产级有界线程池，避免使用公共 ForkJoinPool 导致任务无界堆积。 */
@Configuration
public class ProductionExecutorConfig {

    private static final Logger log = LoggerFactory.getLogger(ProductionExecutorConfig.class);

    @Bean(name = "webhookExecutor", destroyMethod = "shutdown")
    public Executor webhookExecutor(
            @Value("${review.concurrency.webhook-threads:8}") int threads,
            @Value("${review.concurrency.webhook-queue-capacity:2000}") int capacity) {
        return bounded("webhook", threads, capacity);
    }

    @Bean(name = "reviewExecutor", destroyMethod = "shutdown")
    public Executor reviewExecutor(
            @Value("${review.concurrency.review-threads:32}") int threads,
            @Value("${review.concurrency.review-queue-capacity:1000}") int capacity) {
        return bounded("review", threads, capacity);
    }

    @Bean(name = "agentExecutor", destroyMethod = "shutdown")
    public Executor agentExecutor(
            @Value("${review.concurrency.agent-threads:64}") int threads,
            @Value("${review.concurrency.agent-queue-capacity:512}") int capacity) {
        return bounded("agent", threads, capacity);
    }

    @Bean(name = "ioExecutor", destroyMethod = "shutdown")
    public Executor ioExecutor(
            @Value("${review.concurrency.io-threads:32}") int threads,
            @Value("${review.concurrency.io-queue-capacity:1000}") int capacity) {
        return bounded("io", threads, capacity);
    }

    private ThreadPoolExecutor bounded(String prefix, int threads, int capacity) {
        int n = Math.max(1, threads);
        BlockingQueue<Runnable> queue = new java.util.concurrent.ArrayBlockingQueue<>(Math.max(1, capacity));
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "code-review-" + prefix + "-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        return new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS, queue, factory,
                new ThreadPoolExecutor.DiscardPolicy() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        log.warn("线程池[{}]任务队列已满({}/{}), 丢弃任务", prefix, e.getQueue().size(), capacity);
                        super.rejectedExecution(r, e);
                    }
                });
    }

    private static final AtomicInteger THREAD_ID = new AtomicInteger();
}
