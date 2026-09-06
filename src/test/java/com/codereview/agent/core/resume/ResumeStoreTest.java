package com.codereview.agent.core.resume;

import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.Severity;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 断点续跑存储（集群改造后）：PG 行级 UPSERT 为生产实现、{@code InMemoryResumeStore}
 * 为单测/降级实现——这里锁定接口契约（保存 / 读取 / 正常完成清理 / TTL 残留回收）。
 *
 * <p>替代改造前的 {@code FileResumeStore} 测试：本地 JSON + mtime 判据已删除
 * （孤儿 tmp / 半截 JSON 是文件系统特有问题，PG 行模型天然免疫），
 * TTL 判据改为断点自身的 updatedAt（PG 侧为行级 updated_at 列，原子更新）。
 */
class ResumeStoreTest {

    private static Finding f(AgentType t) {
        return new Finding(t, "A.java", 1, 1, Severity.MAJOR, "security",
                "R-1", "title", "desc", "建议", 0.9, "RULE");
    }

    private static ResumeState state(String runId, String teamId, long updatedAt) {
        return new ResumeState(runId, 9001, "demo/repo", teamId,
                Set.of(AgentType.SECURITY), List.of(f(AgentType.SECURITY)), updatedAt);
    }

    @Test
    void saveLoadCompleteRoundTrip() {
        ResumeStore store = new InMemoryResumeStore();
        ResumeState state = state("runX", "teamA", System.currentTimeMillis());

        store.save(state);
        Optional<ResumeState> loaded = store.load("runX", "teamA");
        assertTrue(loaded.isPresent(), "保存后应能读回断点");
        assertEquals(Set.of(AgentType.SECURITY), loaded.get().doneAgents());
        assertEquals(1, loaded.get().findings().size());

        // 同 runId 再次保存 = 覆盖刷新（审查推进）
        store.save(new ResumeState("runX", 9001, "demo/repo", "teamA",
                Set.of(AgentType.SECURITY, AgentType.LOGIC), List.of(), System.currentTimeMillis()));
        assertEquals(Set.of(AgentType.SECURITY, AgentType.LOGIC),
                store.load("runX", "teamA").orElseThrow().doneAgents());

        // 正常完成 → 清理
        store.complete("runX", "teamA");
        assertTrue(store.load("runX", "teamA").isEmpty());
    }

    @Test
    void loadMissingReturnsEmpty() {
        ResumeStore store = new InMemoryResumeStore();
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

    @Test
    void purgeExpiredRemovesStaleCheckpoint() {
        ResumeStore store = new InMemoryResumeStore();
        store.save(state("old", "teamA",
                System.currentTimeMillis() - Duration.ofDays(2).toMillis()));

        assertEquals(1, store.purgeExpired(Duration.ofHours(24)), "超期断点应被清理");
        assertTrue(store.load("old", "teamA").isEmpty());
    }

    @Test
    void purgeExpiredKeepsFreshCheckpoint() {
        ResumeStore store = new InMemoryResumeStore();
        store.save(state("fresh", "teamA", System.currentTimeMillis()));

        assertEquals(0, store.purgeExpired(Duration.ofHours(24)), "未超期断点不得清理");
        assertTrue(store.load("fresh", "teamA").isPresent(), "未超期断点应保留");
    }

    /**
     * TTL 方案的<b>核心安全性质</b>：只要审查还在推进就会不断 save、刷新 updatedAt，
     * 因此跑得再久的审查也不会被当成残留删掉。
     */
    @Test
    void saveRefreshesUpdatedAtSoLongRunningReviewSurvives() {
        ResumeStore store = new InMemoryResumeStore();
        store.save(state("long", "teamD",
                System.currentTimeMillis() - Duration.ofHours(23).toMillis()));

        // 审查仍在推进：又完成一个 Agent，再次保存 → updatedAt 刷新回当下
        store.save(new ResumeState("long", 9004, "demo/repo", "teamD",
                Set.of(AgentType.SECURITY, AgentType.LOGIC), List.of(), System.currentTimeMillis()));

        assertEquals(0, store.purgeExpired(Duration.ofHours(24)), "仍在推进的审查不得被清理");
        assertTrue(store.load("long", "teamD").isPresent());
    }

    /** TTL 配成 0 / 负数 / 未配置时一律不清理：宁可泄漏，也不能删掉正在跑的审查。 */
    @Test
    void purgeExpiredRejectsNonPositiveTtl() {
        ResumeStore store = new InMemoryResumeStore();
        store.save(state("keep", "teamA", System.currentTimeMillis() - Duration.ofDays(99).toMillis()));

        assertEquals(0, store.purgeExpired(Duration.ZERO), "TTL=0 不得清理");
        assertEquals(0, store.purgeExpired(Duration.ofHours(-1)), "负 TTL 不得清理");
        assertEquals(0, store.purgeExpired(null), "TTL 未配置不得清理");
        assertTrue(store.load("keep", "teamA").isPresent(), "配置异常时断点必须保住");
    }
}
