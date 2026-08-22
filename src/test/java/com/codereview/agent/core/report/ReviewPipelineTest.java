package com.codereview.agent.core.report;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.analysis.AdvancedAnalyzer;
import com.codereview.agent.core.coordinator.impl.CompletableFutureCoordinator;
import com.codereview.agent.core.feedback.FeedbackStore;
import com.codereview.agent.core.feedback.InMemoryFeedbackStore;
import com.codereview.agent.core.history.InMemoryReviewHistoryStore;
import com.codereview.agent.core.history.ReviewHistoryStore;
import com.codereview.agent.core.memory.ReviewFeedback;
import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖文档新实践的核心逻辑：优先级冲突仲裁、误报抑制、修复后复检验证。
 */
class ReviewPipelineTest {

    private static Finding f(AgentType type, String ruleId, String file, int line,
                            Severity sev, String suggestion) {
        return new Finding(type, file, line, line, sev, "security", ruleId,
                "title-" + ruleId, "desc", suggestion, 0.9, "RULE");
    }

    // ---------- 1. 优先级冲突仲裁策略 ----------

    @Test
    void conflictDetectionAndPriorityWinner() {
        Finding style = f(AgentType.STYLE, "STYLE-1", "A.java", 10, Severity.MINOR, "拆分为小函数");
        Finding perf = f(AgentType.PERFORMANCE, "PERF-1", "A.java", 10, Severity.MINOR, "保持内联以提升性能");

        assertTrue(ArbitrationPolicy.isConflict(style, perf), "不同 Agent 同位置不同建议应判为冲突");
        assertFalse(ArbitrationPolicy.isConflict(style, style), "同 Agent 不应判为冲突");
        assertEquals(AgentType.PERFORMANCE, ArbitrationPolicy.winner(style, perf), "性能优先级应高于风格");
    }

    // ---------- 2. 误报抑制 + 仲裁（ReportGenerator） ----------

    @Test
    void suppressionAndArbitration() {
        FeedbackStore fb = new InMemoryFeedbackStore();
        fb.save("default", new ReviewFeedback("SEC-002", "SECURITY", true, "合规例外", "PaymentService.java"));

        ReportGenerator gen = new ReportGenerator();

        Finding token = f(AgentType.SECURITY, "SEC-002", "PaymentService.java", 5, Severity.MAJOR, "迁移 KMS");
        Finding sql = f(AgentType.SECURITY, "SEC-001", "PaymentService.java", 7, Severity.MAJOR, "参数化");
        Finding styleLos = f(AgentType.STYLE, "STYLE-1", "PaymentService.java", 7, Severity.MINOR, "重命名变量");

        List<AgentResult> results = new ArrayList<>();
        results.add(new AgentResult(1, AgentType.SECURITY, List.of(token, sql)));
        results.add(new AgentResult(1, AgentType.STYLE, List.of(styleLos)));

        ReviewReport report = gen.aggregate(1, "demo/recheck", results, fb, "run-1", 12L, "default");

        // SEC-002 命中文件级误报 → 被抑制
        assertEquals(0, report.getFindings().stream()
                .filter(x -> "SEC-002".equals(x.ruleId())).count());
        assertEquals(1, report.getSuppressedFindings().size());
        // SEC-001 与 STYLE-1 在 L7 冲突，风格落败 → 进入 overridden
        assertEquals(1, report.getOverriddenFindings().size());
        assertEquals(AgentType.STYLE, report.getOverriddenFindings().get(0).agentType());
    }

    // ---------- 3. 修复后复检验证（Coordinator 端到端） ----------

    private static class StubAgent implements ReviewAgent {
        private final AgentType type;
        private Supplier<List<Finding>> supplier;
        StubAgent(AgentType type, Supplier<List<Finding>> supplier) {
            this.type = type;
            this.supplier = supplier;
        }
        @Override public AgentType getType() { return type; }
        @Override public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
            return supplier.get();
        }
    }

    @Test
    void recheckVerification() {
        FeedbackStore fb = new InMemoryFeedbackStore();
        ReviewHistoryStore history = new InMemoryReviewHistoryStore();
        ReportGenerator gen = new ReportGenerator();

        List<Finding> buggy = List.of(
                f(AgentType.SECURITY, "SEC-002", "PaymentService.java", 5, Severity.MAJOR, "迁移 KMS"),
                f(AgentType.SECURITY, "SEC-001", "PaymentService.java", 7, Severity.MAJOR, "参数化"));
        List<Finding> fixed = List.of(
                f(AgentType.SECURITY, "SEC-001", "PaymentService.java", 7, Severity.MAJOR, "参数化"));

        StubAgent agent = new StubAgent(AgentType.SECURITY, () -> buggy);
        CompletableFutureCoordinator coordinator =
                new CompletableFutureCoordinator(List.of(agent), gen, fb, history, new AdvancedAnalyzer());

        PullRequest pr = new PullRequest(9002, "demo/recheck", "t", "@bob", "main", List.of(
                new CodeDiff("PaymentService.java", "@@ -1 +1 @@", "java", 1, 0)));

        coordinator.review(pr);                 // 首次：buggy
        agent.supplier = () -> fixed;
        ReviewReport second = coordinator.review(pr); // 复检：fixed

        VerificationResult v = second.getVerification();
        assertTrue(v.reCheck());
        assertEquals(1, v.resolvedCount(), "移除的 SEC-002 应记为已解决");
        assertEquals(1, v.unresolvedCount(), "保留的 SEC-001 应记为未解决");
        assertEquals(0, v.introducedCount());
    }
}
