package com.codereview.kit.checkpoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointStoreTest {

    @Test
    void 内存存储_保存覆盖加载删除() {
        InMemoryCheckpointStore store = new InMemoryCheckpointStore();
        store.save(new Checkpoint("r1", Map.of("done", 2), Instant.now()));
        store.save(new Checkpoint("r1", Map.of("done", 3), Instant.now())); // 覆盖
        Optional<Checkpoint> cp = store.load("r1");
        assertTrue(cp.isPresent());
        assertEquals(3, cp.get().state().get("done"));
        store.delete("r1");
        assertTrue(store.load("r1").isEmpty());
        assertTrue(store.list().isEmpty());
    }

    @Test
    void 文件存储_跨实例恢复(@TempDir Path dir) {
        FileCheckpointStore s1 = new FileCheckpointStore(dir);
        s1.save(new Checkpoint("run-x", Map.of("completed", java.util.List.of("a", "b")), Instant.now()));

        FileCheckpointStore s2 = new FileCheckpointStore(dir); // 模拟重启
        Optional<Checkpoint> cp = s2.load("run-x");
        assertTrue(cp.isPresent(), "崩溃后应能从文件恢复");
        assertEquals(2, ((java.util.List<?>) cp.get().state().get("completed")).size());
        assertTrue(s2.list().contains("run-x"));
    }
}
