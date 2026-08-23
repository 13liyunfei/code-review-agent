package com.codereview.agent.core.coordinator.impl;

import com.codereview.agent.core.admin.CustomAgentDef;
import com.codereview.agent.core.admin.CustomAgentStore;
import com.codereview.agent.core.agent.DeclarativeReviewAgent;
import com.codereview.agent.core.analysis.AdvancedAnalyzer;
import com.codereview.agent.core.feedback.InMemoryFeedbackStore;
import com.codereview.agent.core.history.InMemoryReviewHistoryStore;
import com.codereview.agent.core.impact.ImpactAnalyzer;
import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.llm.aiservice.CodeReviewAiService;
import com.codereview.agent.core.llm.aiservice.ReviewFindingDto;
import com.codereview.agent.core.llm.aiservice.ReviewResultDto;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.report.ReportGenerator;
import com.codereview.agent.core.security.KeywordInjectionDetector;
import com.codereview.agent.core.trajectory.ReviewTrajectoryRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自定义 Agent 接入 Coordinator 的端到端验证：团队在 store 中启用自定义 Agent 后，
 * Coordinator 在 review() 内按 teamId 展开并与内置 Agent 并行；自定义 Agent 产出
 * CUSTOM 类型发现，且轨迹记录 {@code agent.custom.expanded}（可观测/可回放）。
 */
class CoordinatorCustomAgentTest {

    /** 结构化输出桩：返回预设发现。 */
    static class FakeAiService implements CodeReviewAiService {
        ReviewResultDto result = new ReviewResultDto(List.of());

        @Override
        public ReviewResultDto review(String memoryId, String prompt) {
            return result;
        }

        @Override
        public com.codereview.agent.core.llm.aiservice.FixResultDto fix(String memoryId, String issue) {
            return null;
        }
    }

    /** 文本路径桩：返回预设 JSON。 */
    static class RecordingLlmClient implements LlmClient {
        String response = "[]";

        @Override
        public String chat(String prompt) {
            return response;
        }
    }

    private static CodeDiff diff(String body) {
        return new CodeDiff("PayService.java", body, "java", 1, 0);
    }

    @Test
    void customAgentExpandedAndRunsInParallel(@TempDir Path tempDir) {
        // 1) 团队在 store 中启用一个自定义 Agent
        CustomAgentStore store = new CustomAgentStore(tempDir, new KeywordInjectionDetector());
        store.init();
        CustomAgentDef def = store.add("default", "支付合规审查", "检查支付链路合规",
                List.of("不得明文存储卡号"), "MAJOR");
        assertTrue(store.listEnabled("default").stream().anyMatch(d -> d.id().equals(def.id())));

        // 2) 装配 Coordinator（内置 Agent 列表为空，仅验证自定义展开；生产为 5 内置 + 自定义）
        FakeAiService ai = new FakeAiService();
        ai.result = new ReviewResultDto(List.of(
                new ReviewFindingDto("PAY-001", "明文卡号", "desc", "建议", "BLOCKER", "PayService.java", 12, 0.9)));
        ReviewTrajectoryRecorder recorder = new ReviewTrajectoryRecorder(tempDir.toString());

        CompletableFutureCoordinator coordinator = new CompletableFutureCoordinator(
                List.of(), new ReportGenerator(), new InMemoryFeedbackStore(),
                new InMemoryReviewHistoryStore(), new AdvancedAnalyzer(),
                java.util.concurrent.ForkJoinPool.commonPool(), new ImpactAnalyzer(), recorder,
                com.codereview.agent.core.enhance.ReviewEnhancements.none(), null,
                store, new RecordingLlmClient(), ai, new KeywordInjectionDetector());

        // 3) 提交 PR（teamId=default）
        PullRequest pr = new PullRequest(9101, "demo/pay", "t", "@alice", "main",
                List.of(diff("+String card = \"1234\";")));
        ReviewReport report = coordinator.review(pr);

        // 4) 自定义 Agent 真实运行并产出 CUSTOM 发现
        assertTrue(report.getFindings().stream().anyMatch(f -> f.agentType() == AgentType.CUSTOM),
                "报告应包含 CUSTOM 类型发现");
        Finding custom = report.getFindings().stream()
                .filter(f -> f.agentType() == AgentType.CUSTOM).findFirst().get();
        assertEquals("PAY-001", custom.ruleId());
        assertEquals("custom:" + def.id(), custom.category());

        // 5) 轨迹记录展开事件（可观测/可回放）
        Path file = tempDir.resolve("default").resolve("trajectories").resolve(report.getRunId() + ".jsonl");
        assertTrue(Files.exists(file), "审查轨迹应已落盘");
        String content = "";
        try {
            content = Files.readString(file);
        } catch (Exception ignored) {
        }
        assertTrue(content.contains("\"agent.custom.expanded\""), "轨迹应含 agent.custom.expanded");
        assertFalse(content.contains("\"agent.custom.disabled\""), "展开成功不应记录 disabled");
    }

    @Test
    void customAgentStoreFailureDegradesToBuiltinOnly(@TempDir Path tempDir) {
        // store 为 null → Coordinator 仅跑内置 Agent，不抛异常
        ReviewTrajectoryRecorder recorder = new ReviewTrajectoryRecorder(tempDir.toString());
        CompletableFutureCoordinator coordinator = new CompletableFutureCoordinator(
                List.of(), new ReportGenerator(), new InMemoryFeedbackStore(),
                new InMemoryReviewHistoryStore(), new AdvancedAnalyzer(),
                java.util.concurrent.ForkJoinPool.commonPool(), new ImpactAnalyzer(), recorder,
                com.codereview.agent.core.enhance.ReviewEnhancements.none(), null,
                null, new RecordingLlmClient(), new FakeAiService(), new KeywordInjectionDetector());

        PullRequest pr = new PullRequest(9102, "demo/x", "t", "@bob", "main", List.of(diff("+int x=1;")));
        ReviewReport report = coordinator.review(pr);

        // 无内置/无自定义 → 空报告，但不崩
        assertTrue(report.getFindings().isEmpty());
        Path file = tempDir.resolve("default").resolve("trajectories").resolve(report.getRunId() + ".jsonl");
        try {
            String content = Files.readString(file);
            assertTrue(content.contains("\"agent.custom.disabled\"") || !content.contains("agent.custom.expanded"),
                    "store 为 null 时不应展开自定义 Agent");
        } catch (Exception ignored) {
        }
    }
}
