package com.codereview.kit.checkpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存检查点存储（进程内崩溃恢复用；跨进程请用文件/DB 实现）。
 */
public class InMemoryCheckpointStore implements CheckpointStore {

    private final Map<String, Checkpoint> store = new ConcurrentHashMap<>();

    @Override
    public void save(Checkpoint checkpoint) {
        store.put(checkpoint.runId(), checkpoint);
    }

    @Override
    public Optional<Checkpoint> load(String runId) {
        return Optional.ofNullable(store.get(runId));
    }

    @Override
    public void delete(String runId) {
        store.remove(runId);
    }

    @Override
    public List<String> list() {
        return new ArrayList<>(store.keySet());
    }
}
