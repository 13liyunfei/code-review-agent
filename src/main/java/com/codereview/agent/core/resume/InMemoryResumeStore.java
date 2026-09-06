package com.codereview.agent.core.resume;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存断点存储（单机/测试回退实现，重启丢失）。
 *
 * <p>与 {@code PgResumeStore} 同一接口，供无 PG 环境（单测、本地离线演示）与
 * {@code ResumeJanitor} / Coordinator 解耦测试使用。
 */
public class InMemoryResumeStore implements ResumeStore {

    /** runId → 断点状态。 */
    private final Map<String, ResumeState> store = new ConcurrentHashMap<>();

    @Override
    public void save(ResumeState state) {
        if (state != null) {
            store.put(state.runId(), state);
        }
    }

    @Override
    public Optional<ResumeState> load(String runId, String teamId) {
        return Optional.ofNullable(store.get(runId));
    }

    @Override
    public void complete(String runId, String teamId) {
        store.remove(runId);
    }

    @Override
    public int purgeExpired(Duration maxAge) {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - maxAge.toMillis();
        int removed = 0;
        for (var it = store.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().updatedAt() < cutoff) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }
}
