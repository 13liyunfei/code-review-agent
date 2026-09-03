package com.codereview.agent.core.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

/**
 * Token 用量记录器：环形缓冲（默认 1024 条），覆盖式保存最近 N 次调用记录。
 *
 * <p><b>为什么是环形缓冲而非持久化</b>：当前阶段不落盘——计费业务由 Token 工厂侧负责，
 * 本仓库只做「调用事实」的可观测（供监控端点 / 调试）。后续若需落盘，
 * 替换实现为持久化版本（数据流不变）。
 *
 * <p><b>线程安全</b>：{@link ArrayDeque} 外部加 synchronized；
 * 累计指标用 {@link LongAdder}（高并发下优于 AtomicLong）。
 */
public class TokenUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(TokenUsageRecorder.class);

    private final int capacity;
    private final Deque<TokenUsageRecord> buffer;

    /** 累计指标（按 provider 维度粗聚合，供监控快照）。 */
    private final java.util.Map<String, ProviderAggregate> aggregates = new java.util.concurrent.ConcurrentHashMap<>();

    public TokenUsageRecorder(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity 必须 >= 1");
        }
        this.capacity = capacity;
        this.buffer = new ArrayDeque<>(capacity);
    }

    /** 默认容量 1024 条。 */
    public TokenUsageRecorder() {
        this(1024);
    }

    /** 记录一次调用（成功或失败皆记录；失败时 token 数视为 0）。 */
    public void record(TokenUsageRecord r) {
        if (r == null) {
            return;
        }
        synchronized (buffer) {
            if (buffer.size() >= capacity) {
                buffer.pollFirst();
            }
            buffer.offerLast(r);
        }
        ProviderAggregate agg = aggregates.computeIfAbsent(r.providerName(), k -> new ProviderAggregate(k));
        agg.calls.increment();
        agg.totalTokens.add(r.totalTokens());
        agg.promptTokens.add(r.promptTokens());
        agg.completionTokens.add(r.completionTokens());
        agg.totalDurationMs.add(r.durationMs());
        if (!r.success()) {
            agg.failures.increment();
        }
    }

    /** 最近 N 条（最新在尾；返回 list 是副本）。 */
    public List<TokenUsageRecord> snapshot() {
        synchronized (buffer) {
            return new ArrayList<>(buffer);
        }
    }

    /** 按 provider 聚合的指标快照。 */
    public List<ProviderAggregate> aggregatesSnapshot() {
        return new ArrayList<>(aggregates.values());
    }

    /** 当前缓冲区大小。 */
    public int size() {
        synchronized (buffer) {
            return buffer.size();
        }
    }

    /** 单供应商聚合指标。 */
    public static final class ProviderAggregate {
        private final String name;
        private final LongAdder calls = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder totalTokens = new LongAdder();
        private final LongAdder promptTokens = new LongAdder();
        private final LongAdder completionTokens = new LongAdder();
        private final LongAdder totalDurationMs = new LongAdder();

        public ProviderAggregate() {
            this.name = "?";
        }

        public ProviderAggregate(String name) {
            this.name = name;
        }

        public String name() { return name; }
        public long calls() { return calls.sum(); }
        public long failures() { return failures.sum(); }
        public long totalTokens() { return totalTokens.sum(); }
        public long promptTokens() { return promptTokens.sum(); }
        public long completionTokens() { return completionTokens.sum(); }
        public long avgDurationMs() {
            long c = calls.sum();
            return c == 0 ? 0 : totalDurationMs.sum() / c;
        }
    }
}