package com.codereview.agent.core.coordinator.impl;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.analysis.AdvancedAnalyzer;
import com.codereview.agent.core.feedback.InMemoryFeedbackStore;
import com.codereview.agent.core.history.InMemoryReviewHistoryStore;
import com.codereview.agent.core.impact.ImpactAnalyzer;
import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.planning.TaskPlanningSupport;
import com.codereview.kit.planning.TaskPlanner;
import com.codereview.agent.core.report.ReportGenerator;
import com.codereview.agent.core.trajectory.ReviewTrajectoryRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务规划织入集成测试：验证 Planning 路径真正接入 Coordinator，
 * 以及未启用时行为与旧版完全一致（可选增强原则）。
 */
class CoordinatorPlanningTest {

    static final String PLAN_JSON = """
            {"tasks":[
              {"id":"t1","description":"逻辑审查","dependsOn":[],"assignee":"LOGIC"},
              {"id":"t2","description":"安全审查","dependsOn":["t1"],"assignee":"SECURITY"}]}""";

    static class ScriptLlm implements LlmClient {
        private final String response;
        ScriptLlm(String response) { this.response = response; }
        @Override public String chat(String prompt) { return response; }
    }

    /** 可计数的假 Agent：每次 review 记一笔，固定返回一条 finding。 */
    static class FakeAgent implements ReviewAgent {
        private final AgentType type;
        final AtomicInteger calls = new AtomicInteger();
        FakeAgent(AgentType type) { this.type = type; }
        @Override public AgentType getType() { return type; }
        @Override public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
            calls.incrementAndGet();
            return List.of(new Finding(type, "Pay.java", 1, 1, Severity.MAJOR,
                    type.name().toLowerCase(), type.name() + "-001",
                    type.name() + " 问题", "描述", "建议", 0.9, "test"));
        }
    }

    private static CodeDiff diff() {
        return new CodeDiff("Pay.java", "+void pay() {}", "java", 1, 0);
    }

    @Test
    void 启用规划时走DAG路径且各Agent仅执行一次(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp);
        ReviewTrajectoryRecorder recorder = new ReviewTrajectoryRecorder(tmp.toString());
        FakeAgent logic = new FakeAgent(AgentType.LOGIC);
        FakeAgent security = new FakeAgent(AgentType.SECURITY);

        TaskPlanningSupport planning = new TaskPlanningSupport(
                new TaskPlanner(new ScriptLlm(PLAN_JSON)),
                new com.codereview.kit.planning.DagExecutor(java.util.concurrent.ForkJoinPool.commonPool()),
                true);

        CompletableFutureCoordinator coordinator = new CompletableFutureCoordinator(
                List.of(logic, security), new ReportGenerator(), new InMemoryFeedbackStore(),
                new InMemoryReviewHistoryStore(), new AdvancedAnalyzer(),
                java.util.concurrent.ForkJoinPool.commonPool(), new ImpactAnalyzer(), recorder,
                com.codereview.agent.core.enhance.ReviewEnhancements.none(), null,
                null, null, null, null, planning);

        ReviewReport report = coordinator.review(new PullRequest(
                9201, "demo/pay", "规划路径验证", "@alice", "main", List.of(diff())));

        // DAG 路径：每个 Agent 恰好被规划调用一次（固定并行路径被跳过）
        assertEquals(1, logic.calls.get());
        assertEquals(1, security.calls.get());
        assertTrue(report.getFindings().stream().anyMatch(f -> f.agentType() == AgentType.LOGIC));
        assertTrue(report.getFindings().stream().anyMatch(f -> f.agentType() == AgentType.SECURITY));
        // 轨迹含规划事件
        String traj = Files.readString(Path.of(tmp.toString(), "default", "trajectories")
                .resolve(listFirst(tmp))).contains("plan.created") ? "y" : "";
        assertEquals("y", traj);
    }

    private static String listFirst(Path tmp) throws Exception {
        try (var s = Files.list(Path.of(tmp.toString(), "default", "trajectories"))) {
            return s.findFirst().orElseThrow().getFileName().toString();
        }
    }

    @Test
    void 未启用规划时行为与旧版一致(@TempDir Path tmp) {
        ReviewTrajectoryRecorder recorder = new ReviewTrajectoryRecorder(tmp.toString());
        FakeAgent logic = new FakeAgent(AgentType.LOGIC);

        // 14 参构造（planningSupport=null）→ 走固定并行路径
        CompletableFutureCoordinator coordinator = new CompletableFutureCoordinator(
                List.of(logic), new ReportGenerator(), new InMemoryFeedbackStore(),
                new InMemoryReviewHistoryStore(), new AdvancedAnalyzer(),
                java.util.concurrent.ForkJoinPool.commonPool(), new ImpactAnalyzer(), recorder,
                com.codereview.agent.core.enhance.ReviewEnhancements.none(), null,
                null, null, null, null);

        ReviewReport report = coordinator.review(new PullRequest(
                9202, "demo/pay", "固定路径验证", "@alice", "main", List.of(diff())));

        assertEquals(1, logic.calls.get());
        assertTrue(report.getFindings().stream().anyMatch(f -> f.agentType() == AgentType.LOGIC));
    }
}
