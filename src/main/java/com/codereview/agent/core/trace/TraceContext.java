package com.codereview.agent.core.trace;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 全链路追踪上下文（基于 SLF4J {@link MDC}）。
 *
 * <p>设计目标：让一次完整的代码审查（Webhook → 协调器 → 5 个并行子 Agent → LLM 调用 →
 * 向量库检索 → Gitea 回写）在日志中共享同一个 {@code traceId}，从而可据 traceId 在海量日志中
 * 还原任意一次请求的完整调用链，用于线上问题定位与耗时分析。
 *
 * <p>关键点：
 * <ul>
 *   <li>入口（Webhook / Demo）生成一次 traceId 并写入 MDC；</li>
 *   <li>所有下游日志无需改动格式，只要日志 pattern 里含 {@code %X{traceId}} 即可自动带上；</li>
 *   <li>本系统大量使用 {@code CompletableFuture.supplyAsync} 跨线程执行，MDC 默认<b>不会</b>
 *       自动传播到 ForkJoinPool 工作线程，故提供 {@link #wrap(Runnable)} / {@link #wrap(Supplier)}
 *       在任务提交前捕获、任务执行时恢复、结束后清理，保证链路不断；</li>
 *   <li>{@link #ensure()} 在任一节点都可安全获取或创建 traceId，保证未显式设置的入口也能被追踪。</li>
 * </ul>
 */
public final class TraceContext {

    /** MDC 键名，须与日志 pattern 中的 {@code %X{traceId}} 一致。 */
    public static final String KEY = "traceId";

    private TraceContext() {
    }

    /**
     * 获取或创建当前线程的 traceId，并确保已写入 MDC。
     *
     * @return 当前 traceId
     */
    public static String ensure() {
        String t = MDC.get(KEY);
        if (t == null || t.isBlank()) {
            t = newTraceId();
            MDC.put(KEY, t);
        }
        return t;
    }

    /** 读取当前 traceId（可能为 null）。 */
    public static String getTraceId() {
        return MDC.get(KEY);
    }

    /** 显式设置 traceId（仅入口处调用）。 */
    public static void set(String traceId) {
        MDC.put(KEY, traceId);
    }

    /** 清除当前线程的 traceId（线程归还池前调用，避免污染后续复用）。 */
    public static void clear() {
        MDC.remove(KEY);
    }

    /** 生成短小且足够唯一的 traceId（12 位十六进制）。 */
    public static String newTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * 捕获当前线程的 MDC 上下文（快照），用于跨线程传递。
     *
     * @return MDC 快照（无则返回 null）
     */
    public static Map<String, String> capture() {
        return MDC.getCopyOfContextMap();
    }

    /**
     * 在子线程恢复 MDC 上下文。
     *
     * @param ctx 快照；为 null 时清空
     */
    public static void restore(Map<String, String> ctx) {
        if (ctx == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(ctx);
        }
    }

    // ===================== 跨线程包装（保证 traceId 不丢失） =====================

    /**
     * 包装 Runnable，使其执行期间携带父线程的 MDC，结束后恢复父线程原 MDC（而非清空）。
     *
     * <p><b>关键修正</b>：原先 finally 用 {@link MDC#clear()}，在 {@code CompletableFuture.supplyAsync}
     * （默认 {@code ForkJoinPool.commonPool()}）场景下，{@code join()} 时调用线程会<b>就地执行</b>某个子任务，
     * 任务结束 {@code clear()} 会把<b>调用线程自身</b>的 traceId 一并清掉，导致 join 之后的协调器收尾 /
     * GiteaReviewService / AutoFixEngine 日志全部丢失 traceId（显示为 N/A）。改为“恢复执行前快照”，
     * 即使就地执行在调用线程上，调用线程的 traceId 也会保留。
     */
    public static Runnable wrap(Runnable r) {
        Map<String, String> inherited = capture();
        return () -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            restore(inherited);
            try {
                r.run();
            } finally {
                restorePrev(prev);
            }
        };
    }

    /**
     * 包装 Supplier，使其执行期间携带父线程的 MDC，结束后恢复父线程原 MDC（而非清空）。
     *
     * @see #wrap(Runnable) 关于“恢复而非清空”的原因说明
     */
    public static <T> Supplier<T> wrap(Supplier<T> s) {
        Map<String, String> inherited = capture();
        return () -> {
            Map<String, String> prev = MDC.getCopyOfContextMap();
            restore(inherited);
            try {
                return s.get();
            } finally {
                restorePrev(prev);
            }
        };
    }

    /** 恢复执行前的 MDC 快照；为 null 时清空（还原到“无上下文”状态）。 */
    private static void restorePrev(Map<String, String> prev) {
        if (prev == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(prev);
        }
    }
}
