package com.codereview.agent.core.resume;

import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
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

    /**
     * 把文件最后修改时间往回拨，模拟「很久没被写过」。
     */
    private static void age(Path file, Duration howOld) throws Exception {
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - howOld.toMillis()));
    }

    private static Path checkpointFile(Path tempDir, String teamId, String runId) {
        return tempDir.resolve(teamId).resolve("resume").resolve(runId + ".json");
    }

    @Test
    void purgeExpiredRemovesStaleCheckpoint(@TempDir Path tempDir) throws Exception {
        FileResumeStore store = new FileResumeStore(tempDir);
        store.save(new ResumeState("old", 9002, "demo/repo", "teamA",
                Set.of(AgentType.SECURITY), List.of(f(AgentType.SECURITY)), System.currentTimeMillis()));
        Path file = checkpointFile(tempDir, "teamA", "old");
        age(file, Duration.ofDays(2));

        assertEquals(1, store.purgeExpired(Duration.ofHours(24)), "超期断点应被清理");
        assertFalse(Files.exists(file), "超期断点文件应删除");
        assertTrue(store.load("old", "teamA").isEmpty());
    }

    @Test
    void purgeExpiredKeepsFreshCheckpoint(@TempDir Path tempDir) {
        FileResumeStore store = new FileResumeStore(tempDir);
        store.save(new ResumeState("fresh", 9003, "demo/repo", "teamA",
                Set.of(AgentType.SECURITY), List.of(f(AgentType.SECURITY)), System.currentTimeMillis()));

        assertEquals(0, store.purgeExpired(Duration.ofHours(24)), "未超期断点不得清理");
        assertTrue(store.load("fresh", "teamA").isPresent(), "未超期断点应保留");
    }

    /**
     * TTL 方案的<b>核心安全性质</b>：只要审查还在推进就会不断 save、刷新 mtime，
     * 因此跑得再久的审查也不会被当成残留删掉。
     */
    @Test
    void saveRefreshesMtimeSoLongRunningReviewSurvives(@TempDir Path tempDir) throws Exception {
        FileResumeStore store = new FileResumeStore(tempDir);
        Path file = checkpointFile(tempDir, "teamD", "long");
        store.save(new ResumeState("long", 9004, "demo/repo", "teamD",
                Set.of(AgentType.SECURITY), List.of(f(AgentType.SECURITY)), System.currentTimeMillis()));
        age(file, Duration.ofHours(23));

        // 审查仍在推进：又完成一个 Agent，再次保存 → mtime 刷新回当下
        store.save(new ResumeState("long", 9004, "demo/repo", "teamD",
                Set.of(AgentType.SECURITY, AgentType.LOGIC), List.of(), System.currentTimeMillis()));

        assertEquals(0, store.purgeExpired(Duration.ofHours(24)), "仍在推进的审查不得被清理");
        assertTrue(store.load("long", "teamD").isPresent());
    }

    /**
     * 损坏到无法解析的断点也必须能被清掉——这正是判据用 mtime 而不是 JSON 里 updatedAt 的原因：
     * 半截 JSON 根本读不出 updatedAt，按业务时间清理会让它永久残留。
     */
    @Test
    void purgeExpiredRemovesUnparseableCheckpoint(@TempDir Path tempDir) throws Exception {
        FileResumeStore store = new FileResumeStore(tempDir);
        Path dir = tempDir.resolve("teamC").resolve("resume");
        Files.createDirectories(dir);
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{\"runId\":\"broken\",\"doneAgents\":[");
        age(broken, Duration.ofDays(5));

        assertTrue(store.load("broken", "teamC").isEmpty(), "前置条件：损坏断点读不出来");
        assertEquals(1, store.purgeExpired(Duration.ofHours(24)), "损坏断点同样应被清理");
        assertFalse(Files.exists(broken));
    }

    /** 崩溃若发生在「写完临时文件、ATOMIC_MOVE 之前」，tmp 同样会成为无人回收的残留。 */
    @Test
    void purgeExpiredRemovesOrphanTmpFile(@TempDir Path tempDir) throws Exception {
        FileResumeStore store = new FileResumeStore(tempDir);
        Path dir = tempDir.resolve("teamB").resolve("resume");
        Files.createDirectories(dir);
        Path tmp = dir.resolve("half.json.tmp");
        Files.writeString(tmp, "{\"runId\":\"half\"");
        age(tmp, Duration.ofDays(3));

        assertEquals(1, store.purgeExpired(Duration.ofHours(24)), "孤儿临时文件应被清理");
        assertFalse(Files.exists(tmp));
    }

    @Test
    void purgeExpiredIgnoresUnrelatedFiles(@TempDir Path tempDir) throws Exception {
        FileResumeStore store = new FileResumeStore(tempDir);
        Path dir = tempDir.resolve("teamE").resolve("resume");
        Files.createDirectories(dir);
        Path unrelated = dir.resolve("notes.txt");
        Files.writeString(unrelated, "别删我");
        age(unrelated, Duration.ofDays(30));

        assertEquals(0, store.purgeExpired(Duration.ofHours(24)), "非断点文件不得清理");
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void purgeExpiredWithoutDataDirIsNoop(@TempDir Path tempDir) {
        FileResumeStore store = new FileResumeStore(tempDir.resolve("not-created"));
        assertEquals(0, store.purgeExpired(Duration.ofHours(24)), "根目录不存在时不应报错");
    }

    /** TTL 配成 0 / 负数 / 未配置时一律不清理：宁可泄漏，也不能删掉正在跑的审查。 */
    @Test
    void purgeExpiredRejectsNonPositiveTtl(@TempDir Path tempDir) throws Exception {
        FileResumeStore store = new FileResumeStore(tempDir);
        store.save(new ResumeState("keep", 9005, "demo/repo", "teamA",
                Set.of(AgentType.SECURITY), List.of(f(AgentType.SECURITY)), System.currentTimeMillis()));
        age(checkpointFile(tempDir, "teamA", "keep"), Duration.ofDays(99));

        assertEquals(0, store.purgeExpired(Duration.ZERO), "TTL=0 不得清理");
        assertEquals(0, store.purgeExpired(Duration.ofHours(-1)), "负 TTL 不得清理");
        assertEquals(0, store.purgeExpired(null), "TTL 未配置不得清理");
        assertTrue(store.load("keep", "teamA").isPresent(), "配置异常时断点必须保住");
    }
}
