package com.codereview.agent.core.trajectory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存轨迹存储（单机/测试回退实现，重启丢失）。
 */
public class InMemoryTrajectoryStore implements TrajectoryStore {

    private final ConcurrentHashMap<String, List<ReviewEvent>> store = new ConcurrentHashMap<>();

    @Override
    public void save(String runId, String teamId, List<ReviewEvent> events) {
        store.put(runId, events == null ? List.of() : new ArrayList<>(events));
    }

    @Override
    public Optional<List<ReviewEvent>> load(String runId, String teamId) {
        return Optional.ofNullable(store.get(runId)).map(ArrayList::new);
    }
}
