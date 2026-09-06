package com.codereview.agent.core.coordinator.impl;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.analysis.AdvancedAnalyzer;
import com.codereview.agent.core.analysis.index.AnalysisEngines;
import com.codereview.agent.core.analysis.index.ImpactIndexBuilder;
import com.codereview.agent.core.analysis.index.IndexScope;
import com.codereview.agent.core.analysis.index.RepoSourceLocator;
import com.codereview.agent.core.analysis.index.SourceFetcher;
import com.codereview.agent.core.enhance.ReviewEnhancements;
import com.codereview.agent.core.feedback.InMemoryFeedbackStore;
import com.codereview.agent.core.history.InMemoryReviewHistoryStore;
import com.codereview.agent.core.impact.ImpactAnalyzer;
import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.report.ReportGenerator;
import com.codereview.agent.core.resume.InMemoryResumeStore;
import com.codereview.agent.core.resume.ResumeState;
import com.codereview.agent.core.trajectory.InMemoryTrajectoryStore;
import com.codereview.agent.core.trajectory.ReviewEvent;
import com.codereview.agent.core.trajectory.ReviewTrajectoryRecorder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 增强能力接入后的端到端验证：确保「事件源轨迹 + 影响面切片」接入后，
 * Coordinator 仍能正常覆盖代码审查（产出报告），且新能力真实生效。
 *
 * <p>轨迹存储为接口（{@code InMemoryTrajectoryStore} 单测 / PG 生产）：审查事件在
 * 结束时写入轨迹存储，断言从存储读回事件序列——不依赖本机 JSONL 文件。
 */
class CoordinatorEnhancementTest {

    private static CodeDiff javaDiff(String fileName, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(fileName).append(" b/").append(fileName).append('\n');
        sb.append("--- a/").append(fileName).append('\n');
        sb.append("+++ b/").append(fileName).append('\n');
        String[] lines = body.split("\n", -1);
        sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String l : lines) {
            sb.append('+').append(l).append('\n');
        }
        return new CodeDiff(fileName, sb.toString(), "java", lines.length, 0);
    }

    private static Finding f(String file, int line) {
        return new Finding(AgentType.SECURITY, file, line, line, Severity.MAJOR, "security",
                "SEC-001", "title", "desc", "建议", 0.9, "RULE");
    }

    /** 捕获最近一次传入的 ReviewContext，验证影响面已注入；并统计调用次数（验证断点续跑）。 */
    private static class CaptureAgent implements ReviewAgent {
        final AgentType type;
        final Supplier<List<Finding>> supplier;
        final AtomicReference<ReviewContext> lastCtx = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        CaptureAgent(AgentType type, Supplier<List<Finding>> supplier) {
            this.type = type;
            this.supplier = supplier;
        }
        @Override public AgentType getType() { return type; }
        @Override public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
            lastCtx.set(ctx);
            calls.incrementAndGet();
            return supplier.get();
        }
    }

    /** 从轨迹存储读出事件类型列表（断言用）。 */
    private static List<String> eventTypes(InMemoryTrajectoryStore store, String runId, String teamId) {
        return store.load(runId, teamId).orElse(List.of()).stream().map(ReviewEvent::type).toList();
    }

    @Test
    void reviewStillWorksWithTrajectoryAndImpact() {
        String src = "package demo;\n"
                + "public class T {\n"
                + "  public void a() { m(); }\n"
                + "  public void m() { int x = 1; }\n"
                + "}\n";
        CodeDiff cd = javaDiff("demo/T.java", src);

        CaptureAgent agent = new CaptureAgent(AgentType.SECURITY, () -> List.of(f("demo/T.java", 6)));

        // 影响面索引：生产里由 Gitea 拉源码，这里用 Map 打桩（核心层只认接口，不认平台）
        RepoSourceLocator locator = (owner, repo, ref) -> new SourceFetcher() {
            @Override
            public Optional<String> fetch(String path) {
                return "demo/T.java".equals(path) ? Optional.of(src) : Optional.empty();
            }

            @Override
            public List<String> listDir(String dir) {
                return List.of();
            }
        };
        ImpactIndexBuilder indexBuilder = new ImpactIndexBuilder(
                locator, AnalysisEngines.defaults(), IndexScope.DEFAULT);

        InMemoryTrajectoryStore trajectoryStore = new InMemoryTrajectoryStore();
        ReviewTrajectoryRecorder recorder = new ReviewTrajectoryRecorder(trajectoryStore);
        CompletableFutureCoordinator coordinator = new CompletableFutureCoordinator(
                List.of(agent), new ReportGenerator(), new InMemoryFeedbackStore(),
                new InMemoryReviewHistoryStore(), new AdvancedAnalyzer(),
                java.util.concurrent.ForkJoinPool.commonPool(),
                new ImpactAnalyzer(), recorder, null, null, null, null, null, null, null,
                indexBuilder);

        // head SHA 必须传：影响面分析据此拉取与本次 diff 同一时刻的源码
        PullRequest pr = new PullRequest(9003, "demo/enh", "t", "@bob", "main",
                "default", List.of(cd), "deadbeef");
        ReviewReport report = coordinator.review(pr);

        // 1) 核心约束：仍覆盖代码审查，产出报告与发现
        assertNotNull(report);
        assertEquals(1, report.getFindings().size(), "应仍检出 SEC-001");

        // 2) 影响面已注入 Agent 上下文
        ReviewContext ctx = agent.lastCtx.get();
        assertNotNull(ctx);
        assertNotNull(ctx.impactSummary(), "影响面摘要字段不应为 null");
        assertTrue(ctx.impactSummary().contains("m"),
                "影响面摘要应提及变更方法 m（注入生效）");

        // 3) 轨迹已入存储且含关键事件（不依赖本机 JSONL）
        List<String> types = eventTypes(trajectoryStore, report.getRunId(), "default");
        assertTrue(types.contains("review.started"), "轨迹应含 review.started");
        assertTrue(types.contains("agent.completed"), "轨迹应含 agent.completed");
        assertTrue(types.contains("review.completed"), "轨迹应含 review.completed");
        // 索引统计落轨迹：「为什么这次没结论」要能事后诊断，而不是只能靠猜
        assertTrue(types.contains("context.index-built"), "轨迹应含索引构建统计");
    }

    @Test
    void resumesFromCheckpointWithoutRerunningDoneAgents() {
        // 断点续跑幂等键改为「从 PR 身份派生」——这是修复 P0 的核心：
        // 旧实现用随机 traceId 当键，同 PR 重试永远命中不了断点，feature 生产上从不生效。
        // 这里用与 Coordinator 相同的派生规则算出键，预置断点使其可命中。
        String headSha = "sha1abc";
        // 注意：repo 含 "/" 会被 resumeKey 替换为 "_"，故键里是 "demo_resume" 而非 "demo/resume"
        String expectedKey = "demo_resume#9004@" + headSha;

        // 预置断点：SECURITY 已完成并产出 1 条 MAJOR 发现
        Finding restored = new Finding(AgentType.SECURITY, "T.java", 2, 2, Severity.MAJOR,
                "security", "SEC-RESTORED", "title", "desc", "建议", 0.9, "RULE");
        InMemoryResumeStore resumeStore = new InMemoryResumeStore();
        resumeStore.save(new ResumeState(expectedKey, 9004, "demo/resume", "default",
                java.util.Set.of(AgentType.SECURITY), List.of(restored), System.currentTimeMillis()));

        CaptureAgent security = new CaptureAgent(AgentType.SECURITY, () -> List.of(f("T.java", 6)));
        CaptureAgent logic = new CaptureAgent(AgentType.LOGIC, () -> List.of(f("T.java", 7)));
        InMemoryTrajectoryStore trajectoryStore = new InMemoryTrajectoryStore();
        ReviewTrajectoryRecorder recorder = new ReviewTrajectoryRecorder(trajectoryStore);
        CompletableFutureCoordinator coordinator = new CompletableFutureCoordinator(
                List.of(security, logic), new ReportGenerator(), new InMemoryFeedbackStore(),
                new InMemoryReviewHistoryStore(), new AdvancedAnalyzer(),
                java.util.concurrent.ForkJoinPool.commonPool(), null, recorder,
                new ReviewEnhancements(resumeStore, null, null));

        // 带 headSha 的 PR：键 = repo#id@headSha，与预置断点一致 → 可命中
        PullRequest pr = new PullRequest(9004, "demo/resume", "t", "@bob", "main",
                "default", List.of(new CodeDiff("T.java", "+x", "java", 1, 0)), headSha);
        ReviewReport report = coordinator.review(pr);

        // 1) 核心约束：仍产出报告，已完成 Agent 不重跑、未完成 Agent 执行
        assertEquals(expectedKey, report.getRunId(), "runId 必须源自 PR 身份（修复 P0）");
        assertEquals(0, security.calls.get(), "已完成 Agent 不得重跑（断点续跑）");
        assertEquals(1, logic.calls.get(), "未完成 Agent 应执行");
        assertTrue(report.getFindings().stream().anyMatch(x -> x.ruleId().equals("SEC-RESTORED")),
                "断点恢复的发现应并入报告");
        // 2) 正常完成 → 断点清理（以派生键为准）
        assertTrue(resumeStore.load(expectedKey, "default").isEmpty(), "审查完成后断点应清理");
        // 3) 轨迹含 review.resumed 事件（断点续跑可审计）
        List<String> types = eventTypes(trajectoryStore, expectedKey, "default");
        assertTrue(types.contains("review.resumed"), "轨迹应含 review.resumed");
    }

    @Test
    void advisoryProfileFiltersMinorFindings() {
        CaptureAgent agent = new CaptureAgent(AgentType.STYLE, () -> List.of(
                new Finding(AgentType.STYLE, "T.java", 1, 1, Severity.MINOR, "style",
                        "STYLE-MINOR", "title", "desc", "建议", 0.5, "RULE")));
        CompletableFutureCoordinator coordinator = new CompletableFutureCoordinator(
                List.of(agent), new ReportGenerator(), new InMemoryFeedbackStore(),
                new InMemoryReviewHistoryStore(), new AdvancedAnalyzer());

        PullRequest pr = new PullRequest(9005, "demo/profile", "t", "@bob", "main",
                List.of(new CodeDiff("T.java", "+x", "java", 1, 0)));
        ReviewReport report = coordinator.review(pr);

        // 默认 profile=ADVISORY：MINOR 被过滤，最终报告为空
        assertTrue(report.getFindings().isEmpty(), "ADVISORY 应过滤 Minor 级发现");
    }
}
