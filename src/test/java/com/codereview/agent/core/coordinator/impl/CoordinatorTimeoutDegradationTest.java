package com.codereview.agent.core.coordinator.impl;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.analysis.AdvancedAnalyzer;
import com.codereview.agent.core.feedback.InMemoryFeedbackStore;
import com.codereview.agent.core.history.InMemoryReviewHistoryStore;
import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.report.AgentDegradation;
import com.codereview.agent.core.report.ReportGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-1 修复验证：协调器超时/异常不再无限阻塞，而是把对应环节标记为「降级」写入报告。
 *
 * <p>修复前：{@code orTimeout} 挂在 allOf 聚合 future 上只打一条 warn，随后的 {@code join()}
 * 无超时仍会无限阻塞；超时/异常的结果被静默丢弃（Agent 直接消失，报告看不出来）。
 * 修复后：每个 Future 独立限时等待，超时即 {@code cancel(true)} 尽力中断，
 * 降级（超时/异常）以 {@link AgentResult#degraded} + {@link AgentDegradation} 如实进入报告。
 *
 * <p>诚实记录 Java 限制：{@link CompletableFuture#cancel(boolean)} 对<b>已开始执行</b>的
 * {@code supplyAsync} stage <b>不会中断</b>其运行线程（与 {@code ExecutorService.submit} 返回的
 * FutureTask 不同）；真实 Agent 的阻塞 LLM 调用同样无法被真正打断。因此本测试不断言
 * 「底层线程被中断」，只验收修复的真实价值：主线程在超时附近返回、超时/异常环节如实
 * 降级进入报告、健康 Agent 的结果不受影响。线程是否泄漏由线程池自身回收策略负责。
 */
class CoordinatorTimeoutDegradationTest {

    // ==================== 测试替身 ====================

    /** 会阻塞指定毫秒的 Agent（可中断）。 */
    private static class BlockingAgent implements ReviewAgent {
        final AgentType type;
        final long sleepMs;
        final AtomicBoolean interrupted = new AtomicBoolean();
        BlockingAgent(AgentType type, long sleepMs) {
            this.type = type;
            this.sleepMs = sleepMs;
        }
        @Override public AgentType getType() { return type; }
        @Override public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                interrupted.set(true);          // 观察点：CF cancel(true) 通常到不了这里（见类 JavaDoc）
                Thread.currentThread().interrupt();
                return List.of();
            }
            return List.of();
        }
    }

    /** 稳定返回固定发现的 Agent。 */
    private static class FixedAgent implements ReviewAgent {
        final AgentType type;
        final Finding finding;
        FixedAgent(AgentType type, Finding finding) {
            this.type = type;
            this.finding = finding;
        }
        @Override public AgentType getType() { return type; }
        @Override public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
            return finding == null ? List.of() : List.of(finding);
        }
    }

    /** 抛异常的 Agent。 */
    private static class ThrowingAgent implements ReviewAgent {
        final AgentType type;
        final RuntimeException error;
        ThrowingAgent(AgentType type, RuntimeException error) {
            this.type = type;
            this.error = error;
        }
        @Override public AgentType getType() { return type; }
        @Override public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
            throw error;
        }
    }

    /** 阻塞版高级静态分析器（验证 advancedFuture 也纳入超时体系）。 */
    private static class BlockingAdvancedAnalyzer extends AdvancedAnalyzer {
        final long sleepMs;
        final AtomicBoolean interrupted = new AtomicBoolean();
        BlockingAdvancedAnalyzer(long sleepMs) {
            this.sleepMs = sleepMs;
        }
        @Override public List<AgentResult> analyze(List<CodeDiff> diffs) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                return List.of();
            }
            return List.of();
        }
    }

    private static CodeDiff diff() {
        return new CodeDiff("T.java", "+x", "java", 1, 0);
    }

    private static Finding finding(AgentType type, String ruleId) {
        return new Finding(type, "T.java", 2, 2, Severity.MAJOR, "security",
                ruleId, "title", "desc", "建议", 0.9, "RULE");
    }

    private static boolean hasStage(ReviewReport report, String stage, String keyword) {
        return report.getDegradations().stream().anyMatch(
                d -> d.stage().equals(stage) && (keyword == null || d.reason().contains(keyword)));
    }

    // ==================== 用例 ====================

    @Test
    void timedOutAgentIsMarkedDegradedNotDropped() {
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            BlockingAgent slow = new BlockingAgent(AgentType.PERFORMANCE, 5000);
            FixedAgent fast = new FixedAgent(AgentType.SECURITY, finding(AgentType.SECURITY, "SEC-FAST"));
            CompletableFutureCoordinator coordinator = new CompletableFutureCoordinator(
                    List.of(slow, fast), new ReportGenerator(), new InMemoryFeedbackStore(),
                    new InMemoryReviewHistoryStore(), null, exec, null, null,
                    com.codereview.agent.core.enhance.ReviewEnhancements.none());
            coordinator.setTimeoutMillis(300);

            long t0 = System.currentTimeMillis();
            ReviewReport report = coordinator.review(new PullRequest(9101, "demo/t", "t", "@bob", "main", List.of(diff())));
            long elapsed = System.currentTimeMillis() - t0;

            // 1) 必须在超时附近返回，而不是等慢 Agent 睡完 5s（修复前 join() 会无限/长时间阻塞）
            assertTrue(elapsed < 3000, "应在 ~300ms 触发超时而非等待阻塞任务完成，实际耗时 " + elapsed + "ms");
            // 2) 慢 Agent 被标记降级并写入报告，而不是被静默丢弃
            assertTrue(report.degraded(), "存在超时环节时报告应标记 degraded");
            assertTrue(hasStage(report, AgentType.PERFORMANCE.name(), "超时"),
                    "报告降级列表应含 PERFORMANCE 超时，实际：" + report.getDegradations());
            // 3) 其余 Agent 的结果不受影响。
            //    注：不在此断言 cancel(true) 是否中断了底层线程——CompletableFuture 对已开始的
            //    supplyAsync stage 不保证中断（见类 JavaDoc 的诚实记录），慢 Agent 由线程池回收。
            assertEquals(1, report.getFindings().size(), "快速 Agent 的发现应保留");
            assertTrue(report.getFindings().stream().anyMatch(f -> f.ruleId().equals("SEC-FAST")));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void throwingAgentIsMarkedDegradedInsteadOfSilentlyDropped() {
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            ThrowingAgent boom = new ThrowingAgent(AgentType.LOGIC, new RuntimeException("LLM 供应商全部失败"));
            FixedAgent fast = new FixedAgent(AgentType.SECURITY, finding(AgentType.SECURITY, "SEC-FAST"));
            CompletableFutureCoordinator coordinator = new CompletableFutureCoordinator(
                    List.of(boom, fast), new ReportGenerator(), new InMemoryFeedbackStore(),
                    new InMemoryReviewHistoryStore(), null, exec, null, null,
                    com.codereview.agent.core.enhance.ReviewEnhancements.none());

            ReviewReport report = coordinator.review(
                    new PullRequest(9102, "demo/t", "t", "@bob", "main", List.of(diff())));

            assertTrue(report.degraded());
            assertTrue(hasStage(report, AgentType.LOGIC.name(), "LLM 供应商全部失败"),
                    "异常原因应进入降级记录，实际：" + report.getDegradations());
            assertTrue(report.getFindings().stream().anyMatch(f -> f.ruleId().equals("SEC-FAST")),
                    "健康 Agent 的结果不应被异常 Agent 拖垮");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void advancedAnalysisTimeoutIsRecordedAsInfraDegradation() {
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            BlockingAdvancedAnalyzer analyzer = new BlockingAdvancedAnalyzer(5000);
            FixedAgent fast = new FixedAgent(AgentType.SECURITY, finding(AgentType.SECURITY, "SEC-FAST"));
            CompletableFutureCoordinator coordinator = new CompletableFutureCoordinator(
                    List.of(fast), new ReportGenerator(), new InMemoryFeedbackStore(),
                    new InMemoryReviewHistoryStore(), analyzer, exec, null, null,
                    com.codereview.agent.core.enhance.ReviewEnhancements.none());
            coordinator.setTimeoutMillis(300);

            long t0 = System.currentTimeMillis();
            ReviewReport report = coordinator.review(
                    new PullRequest(9103, "demo/t", "t", "@bob", "main", List.of(diff())));
            long elapsed = System.currentTimeMillis() - t0;

            assertTrue(elapsed < 3000, "高级分析也应限时返回，实际耗时 " + elapsed + "ms");
            assertTrue(report.degraded());
            assertTrue(hasStage(report, AgentDegradation.STAGE_ADVANCED_ANALYSIS, "超时"),
                    "高级分析超时应以固定阶段名进入降级记录，实际：" + report.getDegradations());
            // 不在此断言底层线程被中断（CompletableFuture 限制，见类 JavaDoc）
            assertTrue(report.getFindings().stream().anyMatch(f -> f.ruleId().equals("SEC-FAST")));
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void healthyRunHasNoDegradationAndDoesNotSpuriouslyTimeout() {
        // 回归：非 Spring 构造时 timeoutMillis 有默认值（修复前为 0 → orTimeout(0) 立即误触发）
        ExecutorService exec = Executors.newFixedThreadPool(4);
        try {
            FixedAgent a = new FixedAgent(AgentType.SECURITY, finding(AgentType.SECURITY, "SEC-1"));
            FixedAgent b = new FixedAgent(AgentType.LOGIC, finding(AgentType.LOGIC, "LOGIC-1"));
            CompletableFutureCoordinator coordinator = new CompletableFutureCoordinator(
                    List.of(a, b), new ReportGenerator(), new InMemoryFeedbackStore(),
                    new InMemoryReviewHistoryStore(), null, exec, null, null,
                    com.codereview.agent.core.enhance.ReviewEnhancements.none());
            // 不设置 timeout（验证默认值 300000 生效）

            ReviewReport report = coordinator.review(
                    new PullRequest(9104, "demo/t", "t", "@bob", "main", List.of(diff())));

            assertFalse(report.degraded(), "全健康执行不应有任何降级");
            assertTrue(report.getDegradations().isEmpty());
            assertEquals(2, report.getFindings().size());
        } finally {
            exec.shutdownNow();
        }
    }
}
