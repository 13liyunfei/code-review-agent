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

    @Override
    public void save(String teamId, ReviewFeedback feedback) {
        store.computeIfAbsent(Teams.sanitize(teamId), k -> new CopyOnWriteArrayList<>()).add(feedback);
    }

    @Override
    public List<ReviewFeedback> list(String teamId) {
        List<ReviewFeedback> list = store.get(Teams.sanitize(teamId));
        return list == null ? List.of() : List.copyOf(list);
    }
}
