package com.codereview.agent.core.resume;

import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断点续跑存储：原子落盘 / 读取 / 正常完成清理。
 */
class ResumeStoreTest {

    private static Finding f(AgentType t) {
        return new Finding(t, "A.java", 1, 1, Severity.MAJOR, "security",
                "R-1", "title", "desc", "建议", 0.9, "RULE");
    }

    @Test
    void saveLoadCompleteRoundTrip(@TempDir Path tempDir) {
        FileResumeStore store = new FileResumeStore(tempDir);
        ResumeState state = new ResumeState("runX", 9001, "demo/repo", "teamA",
                Set.of(AgentType.SECURITY), List.of(f(AgentType.SECURITY)), System.currentTimeMillis());

        store.save(state);
        Optional<ResumeState> loaded = store.load("runX", "teamA");
        assertTrue(loaded.isPresent(), "保存后应能读回断点");
        assertEquals(Set.of(AgentType.SECURITY), loaded.get().doneAgents());
        assertEquals(1, loaded.get().findings().size());

        // 断点文件确实落盘（可跨进程恢复）
        Path file = tempDir.resolve("teamA").resolve("resume").resolve("runX.json");
        assertTrue(Files.exists(file), "断点应落盘为 JSON");

        // 正常完成 → 清理
        store.complete("runX", "teamA");
        assertFalse(Files.exists(file), "正常完成后断点应删除");
        assertTrue(store.load("runX", "teamA").isEmpty());
    }

    @Test
    void loadMissingReturnsEmpty(@TempDir Path tempDir) {
        FileResumeStore store = new FileResumeStore(tempDir);
        assertTrue(store.load("nope", "teamA").isEmpty());
    }

    @Test
    void hasPendingLogic() {
        ResumeState allDone = new ResumeState("r", 1, "x", "t",
                Set.of(AgentType.SECURITY, AgentType.LOGIC), List.of(), 0);
        assertFalse(allDone.hasPending(Set.of(AgentType.SECURITY, AgentType.LOGIC)));
        ResumeState partial = new ResumeState("r", 1, "x", "t",
                Set.of(AgentType.SECURITY), List.of(), 0);
        assertTrue(partial.hasPending(Set.of(AgentType.SECURITY, AgentType.LOGIC)));
    }
}
