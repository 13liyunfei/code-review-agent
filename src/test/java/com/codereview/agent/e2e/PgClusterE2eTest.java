package com.codereview.agent.e2e;

import com.codereview.agent.core.admin.CustomAgentStore;
import com.codereview.agent.core.calibration.CalibrationStore;
import com.codereview.agent.core.calibration.ConfidenceCalibrationService;
import com.codereview.agent.core.feedback.FeedbackStore;
import com.codereview.agent.core.memory.ExperienceEntry;
import com.codereview.agent.core.memory.ExperienceLibrary;
import com.codereview.agent.core.memory.ExperienceStage;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.resume.ResumeState;
import com.codereview.agent.core.resume.ResumeStore;
import com.codereview.agent.core.security.KeywordInjectionDetector;
import com.codereview.agent.core.skill.SkillRegistry;
import com.codereview.agent.core.store.InMemoryTeamConfigStore;
import com.codereview.agent.core.store.PgCalibrationStore;
import com.codereview.agent.core.store.PgDb;
import com.codereview.agent.core.store.PgExperienceLibrary;
import com.codereview.agent.core.store.PgFeedbackStore;
import com.codereview.agent.core.store.PgResumeStore;
import com.codereview.agent.core.store.PgTeamConfigStore;
import com.codereview.agent.core.store.PgTrajectoryStore;
import com.codereview.agent.core.trajectory.ReviewEvent;
import com.codereview.agent.core.trajectory.TrajectoryStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【集群一致性 E2E】真实 PostgreSQL（pgvector 已装）+ pgvector.enabled=true 装配下的
 * 多实例语义验证——用两个独立 {@link PgDb} 连接池模拟「A 机沉淀 / B 机可见」。
 *
 * <p>覆盖用户原始关切：多机集群化部署时，第一次请求在 A 机沉淀的团队知识/经验/断点，
 * 第二次请求落在 B 机必须可见。改造前这些状态落在 data-dir 本地 JSON 文件 → 必然分叉；
 * 改造后全部落 PG 共享行，此处以真库验证：
 * <ul>
 *   <li>断点续跑 / 审查轨迹：A 写 B 读，正常完成清理；</li>
 *   <li>团队配置（自定义 Agent / 技能启停 / 自定义规则）：A 写 B 读（TeamConfigStore）；</li>
 *   <li>校准派生状态 + 反馈：A 反馈落库 → B 实例校准可见；</li>
 *   <li>经验记忆生命周期：证据驱动升级 / 误报降级 / TTL 软删 / 硬删（SQL 内原子完成）；</li>
 *   <li>检索命中刷新（spaced-repetition 反遗忘）。</li>
 * </ul>
 *
 * <p>无本地 PG 时整类跳过（GitHub Actions / 无库机器仍可跑全量单测）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PgClusterE2eTest {

    private static final String HOST = "localhost";
    private static final int PORT = 5432;
    private static final String DB = "codereview";
    private static final String USER = "yunfei";

    private static boolean pgUp;

    @BeforeAll
    static void requirePg() {
        try (PgDb probe = new PgDb(HOST, PORT, DB, USER, "")) {
            probe.initSchema();
            pgUp = true;
        } catch (Throwable t) {
            pgUp = false;
        }
        Assumptions.assumeTrue(pgUp, "无本地 PostgreSQL(pgvector)，跳过集群 E2E");
    }

    private PgDb db() {
        PgDb d = new PgDb(HOST, PORT, DB, USER, "");
        try {
            d.initSchema();
        } catch (Exception e) {
            throw new IllegalStateException("PgDb 初始化失败", e);
        }
        return d;
    }

    /** 每轮唯一键后缀，便于清理。 */
    private static String uq() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static Finding f(String ruleId) {
        return new Finding(AgentType.SECURITY, "A.java", 1, 1, Severity.MAJOR, "security",
                ruleId, "title", "desc", "建议", 0.9, "RULE");
    }

    private static ReviewEvent ev(String type, long ts) {
        return new ReviewEvent(type, ts, "trace-e2e", Map.of("prId", 1));
    }

    /** 定向清理测试写入的痕迹（按 team 前缀与 runId）。 */
    private void cleanup(PgDb d, String team, String runId, String rulePrefix) throws Exception {
        try (Connection c = d.conn(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM experience_entry WHERE team_id = '" + team + "'");
            s.executeUpdate("DELETE FROM team_kv WHERE team_id = '" + team + "'");
            s.executeUpdate("DELETE FROM resume_state WHERE team_id = '" + team + "'");
            s.executeUpdate("DELETE FROM trajectory_store WHERE team_id = '" + team + "'");
            s.executeUpdate("DELETE FROM review_feedback WHERE team_id = '" + team + "'");
            s.executeUpdate("DELETE FROM calibration_accuracy WHERE rule_id LIKE '" + rulePrefix + "%'");
            s.executeUpdate("DELETE FROM resume_state WHERE run_id = '" + runId + "'");
            s.executeUpdate("DELETE FROM trajectory_store WHERE run_id = '" + runId + "'");
        }
    }

    // ============ 1. 断点续跑 + 审查轨迹：A 写 B 读 ============

    @Test
    void resumeAndTrajectorySharedAcrossInstances() throws Exception {
        String team = "e2e-team-" + uq();
        String runId = "e2e-run-" + uq();
        try (PgDb dbA = db(); PgDb dbB = db()) {
            // 实例 A：审查推进 → 保存断点
            ResumeStore storeA = new PgResumeStore(dbA);
            storeA.save(new ResumeState(runId, 7001, "e2e/repo", team,
                    Set.of(AgentType.SECURITY),
                    List.of(f("SEC-E2E-1")), System.currentTimeMillis()));
            assertTrue(storeA.load(runId, team).isPresent(), "A 实例应能读到刚写入的断点");

            // 实例 B（第二次请求落 B 机）：同 runId 应能续跑，不重跑已完成 Agent
            ResumeStore storeB = new PgResumeStore(dbB);
            Optional<ResumeState> onB = storeB.load(runId, team);
            assertTrue(onB.isPresent(), "B 实例必须看到 A 实例写入的断点（集群共享）");
            assertEquals(Set.of(AgentType.SECURITY), onB.orElseThrow().doneAgents());
            assertEquals("SEC-E2E-1", onB.orElseThrow().findings().get(0).ruleId());

            // 审查完成 → B 实例清理断点，A 实例同样不可见
            storeB.complete(runId, team);
            assertTrue(storeA.load(runId, team).isEmpty(), "清理在共享存储上对 A 同样生效");

            // 轨迹：A 写完整事件 → B 读（回放/审计不依赖写入实例）
            TrajectoryStore trajA = new PgTrajectoryStore(dbA);
            trajA.save(runId, team, List.of(
                    ev("review.started", 1000), ev("agent.completed", 1001), ev("review.completed", 1002)));
            TrajectoryStore trajB = new PgTrajectoryStore(dbB);
            List<ReviewEvent> events = trajB.load(runId, team).orElseThrow();
            assertEquals(3, events.size());
            assertEquals("review.completed", events.get(2).type(), "B 实例应读到完整轨迹");
        } finally {
            try (PgDb d = db()) {
                cleanup(d, team, runId, "none");
            }
        }
    }

    // ============ 2. 团队配置（自定义 Agent / 技能）：A 配 B 生效 ============

    @Test
    void customAgentAndSkillConfigSharedAcrossInstances() throws Exception {
        String team = "e2e-team-" + uq();
        try (PgDb dbA = db(); PgDb dbB = db()) {
            PgTeamConfigStore cfgA = new PgTeamConfigStore(dbA);

            // 实例 A：新增自定义 Agent
            CustomAgentStore storeA = new CustomAgentStore(cfgA, new KeywordInjectionDetector());
            var def = storeA.add(team, "支付合规E2E", "检查支付合规", List.of("不得明文存储卡号"), "MAJOR");
            assertTrue(storeA.listEnabled(team).stream().anyMatch(d -> d.id().equals(def.id())));

            // 实例 A：技能注册中心加一条自定义规则（另一个 scope）
            SkillRegistry skillsA = new SkillRegistry(List.of(), cfgA);
            var rule = skillsA.addCustomRule(team,
                    new com.codereview.agent.core.admin.dto.CustomRuleRequest(
                            "E2E规则", "logic", "MINOR", "e2e-pattern", "标题", "描述", "建议"));
            assertTrue(skillsA.listSkills(team).stream().anyMatch(s -> s.id().equals(rule.id())));

            // 实例 B（全新内存态，共享 PG team_kv）：必须可见 A 的配置
            PgTeamConfigStore cfgB = new PgTeamConfigStore(dbB);
            CustomAgentStore storeB = new CustomAgentStore(cfgB, new KeywordInjectionDetector());
            assertNotNull(storeB.get(team, def.id()), "B 实例必须看到 A 实例配置的自定义 Agent");
            SkillRegistry skillsB = new SkillRegistry(List.of(), cfgB);
            assertTrue(skillsB.listSkills(team).stream().anyMatch(s -> s.id().equals(rule.id())),
                    "B 实例必须看到 A 实例配置的自定义规则");

            // B 实例启停 → A 侧经短 TTL 惰性刷新（此处新实例直读）同样收敛
            storeB.setEnabled(team, def.id(), false);
            CustomAgentStore storeC = new CustomAgentStore(new PgTeamConfigStore(dbA), new KeywordInjectionDetector());
            assertEquals(0, storeC.listEnabled(team).size(), "启停状态应跨实例收敛");
        } finally {
            try (PgDb d = db()) {
                cleanup(d, team, "none", "none");
            }
        }
    }

    // ============ 3. 反馈 + 校准派生状态：A 反馈 B 学习 ============

    @Test
    void feedbackAndCalibrationSharedAcrossInstances() throws Exception {
        String team = "e2e-team-" + uq();
        String ruleId = "SEC-E2E-CAL-" + uq();
        try (PgDb dbA = db(); PgDb dbB = db()) {
            CalibrationStore calA = new PgCalibrationStore(dbA);
            ConfidenceCalibrationService calSvcA = new ConfidenceCalibrationService(calA);
            FeedbackStore feedbackA = new PgFeedbackStore(dbA, calSvcA);

            // 实例 A：开发者误报反馈 → 落库并驱动校准（0.8）
            feedbackA.save(team, new com.codereview.agent.core.memory.ReviewFeedback(
                    ruleId, "SECURITY", true, "误报", null));
            assertEquals(0.8, calSvcA.accuracy(ruleId), 1e-9);

            // 实例 B：新实例从共享校准表恢复学习结果（不再是乘 1.0）
            ConfidenceCalibrationService calSvcB = new ConfidenceCalibrationService(
                    new PgCalibrationStore(dbB));
            assertEquals(0.8, calSvcB.accuracy(ruleId), 1e-9, "B 实例应恢复 A 实例的学习结果");

            // 实例 B 能列出 A 写入的反馈（误报抑制聚合不依赖写入实例）
            FeedbackStore feedbackB = new PgFeedbackStore(dbB, null);
            assertEquals(1, feedbackB.list(team).size());
            assertTrue(feedbackB.falsePositives(team).stream()
                    .anyMatch(fb -> fb.ruleId().equals(ruleId)));
        } finally {
            try (PgDb d = db()) {
                cleanup(d, team, "none", ruleId);
            }
        }
    }

    // ============ 4. 经验记忆生命周期（真实 SQL 原子升级/遗忘）============

    @Test
    void experienceLifecycleEvidenceUpgradeForgetAndPurgeOnPg() throws Exception {
        String team = "e2e-team-" + uq();
        String pattern = "SEC-E2E sql 拼接注入";
        try (PgDb dbA = db(); PgDb dbB = db()) {
            ExperienceLibrary libA = new PgExperienceLibrary(dbA);

            // 反思沉淀（第 1 次）→ CANDIDATE
            libA.upsertReflection(team, pattern, "参数化查询");
            ExperienceEntry first = libA.get(team, findId(libA, team, pattern)).orElseThrow();
            assertEquals(ExperienceStage.CANDIDATE, first.stage());
            assertEquals(1, first.evidencePos());

            // 跨 PR 复现两次（模拟后续两次审查仍命中同规则）→ 证据 3 → SQL 侧升级 ACTIVE
            libA.upsertReflection(team, pattern, "参数化查询 v2");
            libA.upsertReflection(team, pattern, "参数化查询 v3");
            // 实例 B（另一连接）读同一行 → 升级可见
            ExperienceLibrary libB = new PgExperienceLibrary(dbB);
            ExperienceEntry active = libB.list(team).stream()
                    .filter(e -> e.pattern().equals(pattern)).findFirst().orElseThrow();
            assertEquals(ExperienceStage.ACTIVE, active.stage(), "复现证据达 3 应升级 ACTIVE（B 可见）");
            assertEquals(3, active.evidencePos());
            assertEquals(1, libB.size(team), "同 pattern 去重，仅 1 条");

            // 人工误报 ×2 → SQL 侧降级 ARCHIVED（检索不可见，管理视图可见）
            libB.recordFeedback(team, "SEC-E2E", true);
            libB.recordFeedback(team, "SEC-E2E", true);
            assertTrue(libB.list(team).isEmpty(), "归档经验不得参与检索");
            ExperienceEntry archived = libB.listAll(team).stream()
                    .filter(e -> e.pattern().equals(pattern)).findFirst().orElseThrow();
            assertEquals(ExperienceStage.ARCHIVED, archived.stage());
            assertEquals(2, archived.evidenceNeg());

            // 反遗忘：另沉淀一条常用经验并命中刷新 → 不会被短 TTL 误删
            String hot = "LOGIC-E2E 热点经验";
            libA.upsertReflection(team, hot, "热点建议");
            libA.recordHit(team, hot);
            assertEquals(0, libB.archiveIdle(team, Duration.ofMillis(1)),
                    "刚命中的经验不得被短 TTL 遗忘");

            // TTL 软删：把一条候选经验的 updated_at 拨回 30 天 → archiveIdle 归档
            backdate(dbA, team, hot, 30);
            assertEquals(1, libB.archiveIdle(team, Duration.ofHours(24)),
                    "超 30 天无更新的经验应软删");
            assertEquals(ExperienceStage.ARCHIVED,
                    libB.listAll(team).stream().filter(e -> e.pattern().equals(hot))
                            .findFirst().orElseThrow().stage());

            // 硬删：把归档经验的 updated_at 再拨回 7 天 → purgeArchived 物理删除
            backdate(dbA, team, hot, 7);
            assertTrue(libB.purgeArchived(team, Duration.ofDays(3)) >= 1,
                    "超保留期的归档应物理删除");
            assertFalse(libB.listAll(team).stream().anyMatch(e -> e.pattern().equals(hot)),
                    "硬删后不可见");
        } finally {
            try (PgDb d = db()) {
                cleanup(d, team, "none", "none");
            }
        }
    }

    private static long findId(ExperienceLibrary lib, String team, String pattern) {
        return lib.listAll(team).stream().filter(e -> e.pattern().equals(pattern))
                .findFirst().orElseThrow().id();
    }

    private void backdate(PgDb d, String team, String pattern, int days) throws Exception {
        try (Connection c = d.conn();
             var ps = c.prepareStatement(
                     "UPDATE experience_entry SET updated_at = now() - (? || ' days')::interval "
                             + "WHERE team_id = ? AND pattern = ?")) {
            ps.setInt(1, days);
            ps.setString(2, team);
            ps.setString(3, pattern);
            ps.executeUpdate();
        }
    }

    // ============ 5. 兜底：无 PG 时 InMemory 语义不回退错误 ============

    @Test
    void inMemoryFallbackStillIsolated() {
        // 即便不走 PG，内存回退实现也要保持团队隔离（防止「没配 PG 就串数据」）
        var a = new InMemoryTeamConfigStore();
        a.saveJson("t1", "x", "{\"k\":1}");
        assertFalse(a.loadJson("t2", "x").isPresent());
    }
}
