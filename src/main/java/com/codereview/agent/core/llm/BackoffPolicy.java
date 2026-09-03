package com.codereview.agent.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 退避策略：失败时按配置 sleep 若干毫秒再重试。
 *
 * <p><b>与 {@link RetryClassifier} 的边界</b>：本类只管「算等多久」，
 * 分类「该不该等」由 {@link RetryClassifier} 决定；
 * 永久错误不进退避，立即切下一供应商。
 *
 * <p><b>算法</b>：指数退避（multiplier=2.0 → 200/400/800/...），封顶 maxBackoffMs。
 */
public class BackoffPolicy {

    private static final Logger log = LoggerFactory.getLogger(BackoffPolicy.class);

    private final long initialBackoffMs;
    private final long maxBackoffMs;
    private final double backoffMultiplier;

    public BackoffPolicy(long initialBackoffMs, long maxBackoffMs, double backoffMultiplier) {
        if (initialBackoffMs < 0 || maxBackoffMs < initialBackoffMs) {
            throw new IllegalArgumentException("退避参数非法：initial=" + initialBackoffMs + " max=" + maxBackoffMs);
        }
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier 必须 >= 1.0（线性 1.0 / 指数 > 1.0）");
        }
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
        this.backoffMultiplier = backoffMultiplier;
    }

    /**
     * 计算第 N 次重试前的等待毫秒数（attempt 从 1 开始：1=首次后，2=二次后，…）。
     */
    public long backoffMs(int attempt) {
        if (attempt <= 1) {
            return initialBackoffMs;
        }
        double v = initialBackoffMs * Math.pow(backoffMultiplier, attempt - 1);
        long r = (long) Math.min(v, maxBackoffMs);
        return Math.max(0, r);
    }

    /**
     * 阻塞 sleep 指定毫秒数；线程被中断时恢复中断标记并返回。
     */
    public void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[退避] sleep {}ms 被中断", ms);
        }
    }
}