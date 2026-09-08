package com.codereview.agent.core.memory;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 记忆升级与遗忘生命周期（集群改造新增）：
 *
 * <ul>
 *   <li><b>证据驱动升级</b>：跨 PR 复现（evidence_pos）达阈值 CANDIDATE→ACTIVE；</li>
 *   <li><b>误报降级</b>：人工误报（evidence_neg）达阈值 → ARCHIVED（不再注入检索）；</li>
 *   <li><b>TTL 遗忘</b>：长期无更新/无命中的条目软删（ARCHIVED）→ 超保留期硬删（PURGED）；</li>
 *   <li><b>spaced-repetition 反遗忘</b>：被反复命中的经验刷新遗忘时钟；</li>
 *   <li><b>团队隔离</b>：经验按 teamId 互不可见。</li>
 * </ul>
 *
 * <p>内存实现与 PG 版规则一致（{@code PgExperienceLibrary} 在 E2E 覆盖 SQL 侧），
 * 这里锁定规则本身。TTL 判据为毫秒时间戳，用例用短 sleep 制造「陈旧」。
 */
class ExperienceLifecycleTest {

    private static final String TEAM = "default";

    private ExperienceLibrary lib() {
        return new InMemoryExperienceLibrary();
    }

    @Test
    void reflectionUpsertStartsCandidateAndUpgradesOnReproductionEvidence() {
        ExperienceLibrary lib = lib();
        lib.upsertReflection(TEAM, "SEC-1 sql 拼接", "参数化查询");
        ExperienceEntry once = lib.get(TEAM, 1L).orElseThrow();
        assertEquals(ExperienceStage.CANDIDATE, once.stage(), "首次沉淀为候选经验");
        assertEquals(1, once.evidencePos());

        // 同 pattern 再复现 2 次 → 证据达阈值 → ACTIVE
        lib.upsertReflection(TEAM, "SEC-1 sql 拼接", "参数化查询（重申）");
        lib.upsertReflection(TEAM, "SEC-1 sql 拼接", "参数化查询（再重申）");
        ExperienceEntry promoted = lib.get(TEAM, 1L).orElseThrow();
        assertEquals(ExperienceStage.ACTIVE, promoted.stage(), "复现证据达 3 次应升级 ACTIVE");
        assertEquals(3, promoted.evidencePos());
        assertTrue(promoted.retrievable());

        // 同 pattern 去重：全团队仅 1 条
        assertEquals(1, lib.size(TEAM));
    }

    @Test
    void negativeFeedbackArchivesEntryAndStopsRetrieval() {
        ExperienceLibrary lib = lib();
        lib.upsertReflection(TEAM, "SEC-2 硬编码密钥 A.java", "密钥放配置中心");
        assertEquals(1, lib.list(TEAM).size());

        // 人工误报累计 2 次 → ARCHIVED（降级遗忘的负证据路径）
        lib.recordFeedback(TEAM, "SEC-2", true);
        assertTrue(lib.get(TEAM, 1L).orElseThrow().retrievable(), "1 次误报仍可检索（低于阈值）");
        lib.recordFeedback(TEAM, "SEC-2", true);

        ExperienceEntry archived = lib.get(TEAM, 1L).orElseThrow();
        assertEquals(ExperienceStage.ARCHIVED, archived.stage(), "误报达 2 次应归档");
        assertEquals(2, archived.evidenceNeg());
        assertTrue(lib.list(TEAM).isEmpty(), "归档经验不得再注入检索");
        assertFalse(archived.retrievable());

        // 管理视图仍可见（供人工审视/恢复）
        assertEquals(1, lib.listAll(TEAM).size());
    }

    @Test
    void positiveFeedbackImmediatelyActivates() {
        ExperienceLibrary lib = lib();
        lib.upsertReflection(TEAM, "SEC-3 空指针 A.java", "判空");
        assertEquals(ExperienceStage.CANDIDATE, lib.get(TEAM, 1L).orElseThrow().stage());

        lib.recordFeedback(TEAM, "SEC-3", false); // 人工正报
        assertEquals(ExperienceStage.ACTIVE, lib.get(TEAM, 1L).orElseThrow().stage(),
                "人工正报应立即 ACTIVE（正证据无需累计）");
    }

    @Test
    void recordHitRefreshesSpacedRepetitionClock() throws Exception {
        ExperienceLibrary lib = lib();
        lib.upsertReflection(TEAM, "LOGIC-1 资源未关闭 A.java", "try-with-resources");
        Thread.sleep(5);
        lib.recordHit(TEAM, "LOGIC-1 资源未关闭 A.java");

        ExperienceEntry hit = lib.get(TEAM, 1L).orElseThrow();
        assertEquals(1, hit.hitCount());
        assertTrue(hit.lastHitAt() > 0, "命中应刷新 lastHitAt（反遗忘）");

        // 刚被命中的经验不会被短 TTL 遗忘
        assertEquals(0, lib.archiveIdle(TEAM, Duration.ofMillis(1)), "命中刷新后不得立即遗忘");
        assertEquals(1, lib.size(TEAM));
    }

    @Test
    void idleCandidateIsArchivedThenHardPurged() throws Exception {
        ExperienceLibrary lib = lib();
        lib.upsertReflection(TEAM, "STYLE-1 魔法数字", "提取常量");
        // 团队隔离的另一边不受影响
        lib.upsertReflection("other", "SEC-9 x", "y");
        Thread.sleep(15); // 制造陈旧

        // 软删：超过 idle 无更新的 CANDIDATE → ARCHIVED
        assertEquals(1, lib.archiveIdle(TEAM, Duration.ofMillis(5)), "陈旧候选应被归档");
        assertEquals(ExperienceStage.ARCHIVED, lib.get(TEAM, 1L).orElseThrow().stage());
        assertTrue(lib.list(TEAM).isEmpty(), "归档后不再参与检索");

        // 归档条目不能被重复归档
        assertEquals(0, lib.archiveIdle(TEAM, Duration.ofMillis(1)));

        // 硬删：ARCHIVED 超过保留期 → 物理删除
        Thread.sleep(5);
        assertEquals(1, lib.purgeArchived(TEAM, Duration.ofMillis(1)), "超保留期归档应物理删除");
        assertTrue(lib.get(TEAM, 1L).isEmpty(), "物理删除后不可见");
        assertTrue(lib.listAll(TEAM).isEmpty());

        // 团队隔离：另一团队条目始终未受影响
        assertEquals(1, lib.size("other"));
    }

    @Test
    void teamIsolationAcrossLibraries() {
        ExperienceLibrary lib = lib();
        lib.upsertReflection("teamA", "SQL-1 注入", "参数化");
        lib.upsertReflection("teamB", "OTHER-1 事", "无关");

        assertEquals(1, lib.size("teamA"));
        assertEquals(1, lib.size("teamB"));
        assertTrue(lib.list("teamA").stream().allMatch(e -> e.pattern().contains("SQL-1")));
        assertTrue(lib.list("teamB").stream().allMatch(e -> e.pattern().contains("OTHER-1")));
    }

    @Test
    void activeExperienceIsRankedAboveCandidateForSameQuery() {
        // ExperienceStore.top：检索注入时 ACTIVE 优先于 CANDIDATE
        ExperienceStore store = new ExperienceStore(new InMemoryExperienceLibrary());
        store.add(TEAM, "LOGIC-1 sql 拼接 常见", "方式A");          // CANDIDATE（1 次）
        for (int i = 0; i < 3; i++) {
            store.add(TEAM, "LOGIC-2 sql 拼接 高频", "方式B");       // 复现 3 次 → ACTIVE
        }

        List<ExperienceEntry> hits = store.top(TEAM, "sql 拼接", 5);
        assertTrue(hits.size() >= 2, "两条同主题经验都应命中，实际 " + hits.size());
        assertEquals(ExperienceStage.ACTIVE, hits.get(0).stage(),
                "同主题下 ACTIVE（经验证）应排 CANDIDATE 之前");
        assertNotNull(hits.get(0).advice());
        assertEquals(2, store.size(TEAM), "两条未遗忘经验都参与 size 统计");
    }

    @Test
    void manualArchiveAndPurgeAdminOps() {
        ExperienceLibrary lib = lib();
        lib.upsertReflection(TEAM, "X-1 pattern", "advice");
        long id = lib.get(TEAM, 1L).orElseThrow().id();

        assertTrue(lib.archive(TEAM, id), "管理端手动归档应成功");
        assertEquals(ExperienceStage.ARCHIVED, lib.get(TEAM, id).orElseThrow().stage());
        assertFalse(lib.list(TEAM).stream().anyMatch(e -> e.id() == id), "归档后不进检索视图");

        assertTrue(lib.purge(TEAM, id), "管理端手动删除应成功");
        assertTrue(lib.get(TEAM, id).isEmpty());
        assertEquals(0, lib.listAll(TEAM).size());
    }
}
