package com.codereview.agent.core.gate;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.analysis.AdvancedAnalyzer;
import com.codereview.agent.core.coordinator.impl.CompletableFutureCoordinator;
import com.codereview.agent.core.feedback.InMemoryFeedbackStore;
import com.codereview.agent.core.history.InMemoryReviewHistoryStore;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.report.ReportGenerator;
import com.codereview.agent.core.trajectory.ReviewEvent;
import com.codereview.agent.core.trajectory.ReviewTrajectoryRecorder;
import com.codereview.agent.core.trajectory.TrajectoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证送审闸门在 {@code Coordinator} 上「真的接上了」，而不只是在单测里能跑。
 *
 * <p>为什么单独写这组测试：闸门本身通过全部单元测试，也不代表线上生效——
 * 本项目已经吃过「能力都在仓库里、只是没接线」的亏（例如 AST 分析 18 个文件写好了却没进审查路径）。
 * 因此这里断言的是<b>接线事实</b>：Agent 实际看到的文件清单、门禁全拦时不发起调用、轨迹里有决策记录。
 */
class CoordinatorPathGateTest {

    /** 记录「Agent 到底看到了哪些文件」——这是判断闸门是否生效的最直接证据。 */
    private static class RecordingAgent implements ReviewAgent {
        private final List<String> seen = new ArrayList<>();

        @Override
        public AgentType getType() {
            return AgentType.SECURITY;
        }

        @Override
        public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
            for (CodeDiff d : diffs) {
                seen.add(d.fileName());
            }
            return List.of();
        }
    }

    /** 捕获落盘事件的轨迹存储（用于断言闸门决策进了轨迹）。 */
    private static class CapturingStore implements TrajectoryStore {
        private List<ReviewEvent> events = List.of();

        @Override
        public void save(String runId, String teamId, List<ReviewEvent> events) {
            this.events = events == null ? List.of() : events;
        }

        @Override
        public Optional<List<ReviewEvent>> load(String runId, String teamId) {
            return Optional.of(events);
        }
    }

    private static CodeDiff diff(String path) {
        return new CodeDiff(path, "@@ -1 +1 @@\n+x", CodeDiff.inferLanguage(path), 1, 0);
    }

    private static PullRequest pr(List<CodeDiff> diffs) {
        return new PullRequest(7001, "demo/gate", "t", "@alice", "main", "default", diffs, "sha1");
    }

    private static CompletableFutureCoordinator coordinator(ReviewAgent agent,
                                                           ReviewTrajectoryRecorder recorder) {
        CompletableFutureCoordinator c = new CompletableFutureCoordinator(
                List.of(agent), new ReportGenerator(), new InMemoryFeedbackStore(),
                new InMemoryReviewHistoryStore(), new AdvancedAnalyzer(),
                java.util.concurrent.ForkJoinPool.commonPool(), null, recorder);
        c.setPathGate(new PathGate(new PathGateProperties()));
        return c;
    }

    @Test
    void secretAndBinaryFilesNeverReachAgents() {
        RecordingAgent agent = new RecordingAgent();
        CompletableFutureCoordinator c = coordinator(agent, null);

        c.review(pr(List.of(
                diff("src/main/java/com/x/A.java"),
                diff(".env"),
                diff("libs/app.jar"),
                diff("target/classes/generated.properties"))));

        assertEquals(List.of("src/main/java/com/x/A.java"), agent.seen,
                "密钥/二进制/构建产物文件必须一个都不能进入 Agent 视野");
    }

    @Test
    void gateAllBlockedSkipsEntireReview() {
        RecordingAgent agent = new RecordingAgent();
        CompletableFutureCoordinator c = coordinator(agent, null);

        ReviewReport report = c.review(pr(List.of(diff(".env"), diff("deploy/.ssh/id_rsa"))));

        assertTrue(agent.seen.isEmpty(), "送审清单被清空时不应调用任何 Agent（省掉全部 LLM 调用）");
        assertNotNull(report);
        assertTrue(report.getFindings().isEmpty());
    }

    @Test
    void gateDecisionIsRecordedInTrajectory() {
        RecordingAgent agent = new RecordingAgent();
        CapturingStore store = new CapturingStore();
        ReviewTrajectoryRecorder recorder = new ReviewTrajectoryRecorder(store);
        CompletableFutureCoordinator c = coordinator(agent, recorder);

        c.review(pr(List.of(diff("src/A.java"), diff(".env"), diff("libs/app.jar"))));

        ReviewEvent gated = store.events.stream()
                .filter(e -> "files.gated".equals(e.type()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("轨迹里必须有 files.gated 事件（否则闸门决策不可追溯）"));
        assertEquals(1, gated.data().get("admitted"));
        assertEquals(2, gated.data().get("blocked"));
        assertTrue(String.valueOf(gated.data().get("blockedByReason")).contains("secret_exclude"),
                "按原因聚合必须可回读：" + gated.data().get("blockedByReason"));
        assertFalse(store.events.isEmpty());
        // 未装配闸门时不应出现该事件（保持改造前行为的可辨识性）
        CapturingStore plain = new CapturingStore();
        CompletableFutureCoordinator noGate = new CompletableFutureCoordinator(
                List.of(new RecordingAgent()), new ReportGenerator(), new InMemoryFeedbackStore(),
                new InMemoryReviewHistoryStore(), new AdvancedAnalyzer(),
                java.util.concurrent.ForkJoinPool.commonPool(), null,
                new ReviewTrajectoryRecorder(plain));
        noGate.review(pr(List.of(diff("src/A.java"))));
        assertTrue(plain.events.stream().noneMatch(e -> "files.gated".equals(e.type())),
                "未装配闸门时不应产生 files.gated 事件");
    }
}
