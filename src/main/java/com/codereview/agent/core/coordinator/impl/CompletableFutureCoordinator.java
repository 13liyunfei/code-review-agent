package com.codereview.agent.core.coordinator.impl;

import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.analysis.AdvancedAnalyzer;
import com.codereview.agent.core.coordinator.Coordinator;
import com.codereview.agent.core.feedback.FeedbackStore;
import com.codereview.agent.core.history.FindingSummary;
import com.codereview.agent.core.history.ReviewHistoryEntry;
import com.codereview.agent.core.history.ReviewHistoryStore;
import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.report.ReportGenerator;
import com.codereview.agent.core.report.VerificationResult;
import com.codereview.agent.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 基于 {@link CompletableFuture} 的协调者实现（生产推荐混合模式）。
 *
 * <p>设计要点（见文档）：
 * <ul>
 *   <li>为每个 Agent 创建一个 Future，并行执行审查；</li>
 *   <li>使用 {@code allOf + orTimeout} 实现整体超时控制（默认 5 分钟）；</li>
 *   <li>支持“部分失败”：个别 Agent 超时 / 异常时，其余结果仍可用，不阻塞主线程；</li>
 *   <li>生成 runId 并计时，落地到审查历史，支持“修复后复检”增量对比；</li>
 *   <li>结果汇聚后交由 {@link ReportGenerator} 去重、优先级仲裁、误报抑制、定档。</li>
 * </ul>
 */
public class CompletableFutureCoordinator implements Coordinator {

    private static final Logger log = LoggerFactory.getLogger(CompletableFutureCoordinator.class);

    /** 审查整体超时时间（毫秒），默认 5 分钟。 */
    @Value("${review.agent.timeout-ms:300000}")
    private long timeoutMillis;

    private final List<ReviewAgent> agents;
    private final ReportGenerator reportGenerator;
    private final FeedbackStore feedbackStore;
    private final ReviewHistoryStore historyStore;
    private final AdvancedAnalyzer advancedAnalyzer;
    private final Executor agentExecutor;

    public CompletableFutureCoordinator(List<ReviewAgent> agents, ReportGenerator reportGenerator,
                                       FeedbackStore feedbackStore, ReviewHistoryStore historyStore,
                                       AdvancedAnalyzer advancedAnalyzer) {
        this(agents, reportGenerator, feedbackStore, historyStore, advancedAnalyzer, ForkJoinPool.commonPool());
    }

    public CompletableFutureCoordinator(List<ReviewAgent> agents, ReportGenerator reportGenerator,
                                       FeedbackStore feedbackStore, ReviewHistoryStore historyStore,
                                       AdvancedAnalyzer advancedAnalyzer, Executor agentExecutor) {
        this.agents = agents;
        this.reportGenerator = reportGenerator;
        this.feedbackStore = feedbackStore;
        this.historyStore = historyStore;
        this.advancedAnalyzer = advancedAnalyzer;
        this.agentExecutor = agentExecutor;
    }

    @Override
    public ReviewReport review(PullRequest pr) {
        ReviewContext ctx = pr.toContext();
        List<CodeDiff> diffs = pr.diffs();
        // 全链路追踪：runId 与 traceId 统一，使审查历史/报告与日志链路可据同一 ID 关联
        String runId = TraceContext.ensure();
        long start = System.currentTimeMillis();
        String teamId = pr.teamId();

        log.info("[Coordinator] 开始审查 PR#{}（仓库={}，团队={}，Agent 数={}，runId={}）",
                pr.id(), pr.repo(), teamId, agents.size(), runId);

        // 上一轮审查记录（用于复检验证）
        ReviewHistoryEntry previous = historyStore == null ? null
                : historyStore.getLatest(teamId, pr.repo() + "#" + pr.id()).orElse(null);

        // 1. 为每个 Agent 创建 Future，并行审查（TraceContext.wrap 保证 traceId 跨线程传播）
        List<CompletableFuture<AgentResult>> futures = agents.stream()
                .map(agent -> CompletableFuture.supplyAsync(TraceContext.wrap(() -> {
                    long a0 = System.currentTimeMillis();
                    log.info("[Coordinator] 子Agent[{}] 开始审查（并行执行）", agent.getType());
                    List<Finding> findings = agent.review(diffs, ctx);
                    log.info("[Coordinator] 子Agent[{}] 完成：发现 {} 条，耗时 {}ms",
                            agent.getType(), findings.size(), System.currentTimeMillis() - a0);
                    return new AgentResult(pr.id(), agent.getType(), findings);
                }), agentExecutor))
                .toList();

        // 1.1 高级静态分析（AST 结构 / 调用图影响面 / SCA 依赖）并行运行
        CompletableFuture<List<AgentResult>> advancedFuture = advancedAnalyzer == null ? null
                : CompletableFuture.supplyAsync(TraceContext.wrap(() -> {
                    long a0 = System.currentTimeMillis();
                    log.info("[Coordinator] 高级静态分析 开始（AST/调用图/SCA）");
                    List<AgentResult> r = advancedAnalyzer.analyze(diffs);
                    int total = r.stream().mapToInt(ar -> ar.findings().size()).sum();
                    log.info("[Coordinator] 高级静态分析 完成：{} 个 Agent 结果，共 {} 条，耗时 {}ms",
                            r.size(), total, System.currentTimeMillis() - a0);
                    return r;
                }), agentExecutor);

        // 2. allOf + 超时控制（回调不阻塞主线程）
        CompletableFuture<Void> allDone = CompletableFuture.allOf(
                futures.toArray(new CompletableFuture[0]));
        allDone.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        log.warn("[Coordinator] PR#{} 部分 Agent 超时或异常：{}",
                                pr.id(), ex.getMessage());
                    }
                });

        // 3. 收集结果（个别失败 / 超时以空结果占位，保证整体可用）
        List<AgentResult> results = new ArrayList<>();
        for (CompletableFuture<AgentResult> future : futures) {
            try {
                results.add(future.join());
            } catch (Exception e) {
                log.warn("[Coordinator] 单个 Agent 执行失败，已跳过：{}", e.getMessage());
            }
        }
        // 3.1 收集高级分析结果
        if (advancedFuture != null) {
            try {
                results.addAll(advancedFuture.join());
            } catch (Exception e) {
                log.warn("[Coordinator] 高级分析执行失败，已跳过：{}", e.getMessage());
            }
        }

        long durationMs = System.currentTimeMillis() - start;

        // 4. 汇聚、去重、优先级仲裁、误报抑制、定档
        ReviewReport report = reportGenerator.aggregate(
                pr.id(), pr.repo(), results, feedbackStore, runId, durationMs, teamId);

        // 5. 复检验证（与上一轮对比已解决 / 未解决 / 新引入）
        VerificationResult verification = computeVerification(previous, report.getFindings());
        report = report.withVerification(verification);

        // 6. 落地本轮审查历史（供下次复检与质量趋势统计）
        if (historyStore != null) {
            historyStore.save(teamId, new ReviewHistoryEntry(
                    pr.id(), pr.repo(), runId, System.currentTimeMillis(),
                    toSummaries(report.getFindings())));
        }

        log.info("[Coordinator] PR#{} 审查完成（runId={}）：最终 {} 条，抑制误报 {} 条，仲裁覆盖 {} 条，" +
                        "复检已解决 {} 条 / 未解决 {} 条 / 新引入 {} 条",
                pr.id(), runId, report.getFindings().size(), report.getSuppressedFindings().size(),
                report.getOverriddenFindings().size(), verification.resolvedCount(),
                verification.unresolvedCount(), verification.introducedCount());
        return report;
    }

    /**
     * 计算复检验证结果：对比上一轮与本轮发现。
     */
    private VerificationResult computeVerification(ReviewHistoryEntry previous, List<Finding> current) {
        if (previous == null || previous.findings() == null || previous.findings().isEmpty()) {
            return VerificationResult.none();
        }
        Map<String, FindingSummary> prevByKey = previous.findings().stream()
                .collect(Collectors.toMap(FindingSummary::dedupKey, s -> s, (a, b) -> a));
        Map<String, FindingSummary> currByKey = toSummaries(current).stream()
                .collect(Collectors.toMap(FindingSummary::dedupKey, s -> s, (a, b) -> a));

        List<String> resolved = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<String> introduced = new ArrayList<>();

        for (String key : prevByKey.keySet()) {
            if (currByKey.containsKey(key)) {
                unresolved.add(label(currByKey.get(key)));
            } else {
                resolved.add(label(prevByKey.get(key)));
            }
        }
        for (String key : currByKey.keySet()) {
            if (!prevByKey.containsKey(key)) {
                introduced.add(label(currByKey.get(key)));
            }
        }

        return new VerificationResult(true, resolved.size(), unresolved.size(), introduced.size(),
                resolved, unresolved, introduced);
    }

    private List<FindingSummary> toSummaries(List<Finding> findings) {
        return findings.stream()
                .map(f -> new FindingSummary(f.ruleId(), f.agentType().name(), f.file(),
                        f.lineStart(), f.lineEnd(), f.severity(), f.title()))
                .toList();
    }

    private String label(FindingSummary s) {
        String loc = s.lineStart() > 0 ? (s.file() + ":L" + s.lineStart()) : s.file();
        return "[" + s.ruleId() + "] " + s.title() + "（" + loc + "）";
    }
}
