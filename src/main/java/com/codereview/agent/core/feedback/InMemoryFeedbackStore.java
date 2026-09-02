package com.codereview.agent.core.feedback;

import com.codereview.agent.core.memory.ReviewFeedback;
import com.codereview.agent.tenant.Teams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版反馈存储（无盘环境 / 单元测试默认实现）。
 *
 * <p>进程重启后数据不保留；生产环境请使用 {@link FileFeedbackStore} 持久化。
 * 数据按团队隔离。
 */
public class InMemoryFeedbackStore implements FeedbackStore {

    /** teamId → 反馈列表。 */
    private final Map<String, List<ReviewFeedback>> store = new ConcurrentHashMap<>();
    /** 反馈落库监听器（驱动置信度校准等旁路；可为 null）。 */
    private final FeedbackListener listener;

    public InMemoryFeedbackStore() {
        this(null);
    }

    /**
     * @param listener 反馈落库监听器（可为 null；测试中可直接注入校准服务验证闭环）
     */
    public InMemoryFeedbackStore(FeedbackListener listener) {
        this.listener = listener == null ? FeedbackListener.NONE : listener;
    }

    @Override
    public void save(String teamId, ReviewFeedback feedback) {
        String t = Teams.sanitize(teamId);
        store.computeIfAbsent(t, k -> new CopyOnWriteArrayList<>()).add(feedback);
        try {
            listener.onFeedback(t, feedback);
        } catch (Exception ignored) {
            // 旁路：监听器异常不影响反馈保存
        }
    }

    @Override
    public List<ReviewFeedback> list(String teamId) {
        List<ReviewFeedback> list = store.get(Teams.sanitize(teamId));
        return list == null ? List.of() : List.copyOf(list);
    }
}
