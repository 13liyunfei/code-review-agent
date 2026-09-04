package com.codereview.agent.core.coordinator.impl;

import com.codereview.agent.core.agent.DeclarativeReviewAgent;
import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.admin.CustomAgentDef;
import com.codereview.agent.core.admin.CustomAgentStore;
import com.codereview.agent.core.analysis.AdvancedAnalyzer;
import com.codereview.agent.core.analysis.index.ImpactIndexBuilder;
import com.codereview.agent.core.analysis.index.RepoIndex;
import com.codereview.agent.core.coordinator.Coordinator;
import com.codereview.agent.core.enhance.ReviewEnhancements;
import com.codereview.agent.core.impact.ImpactAnalyzer;
import com.codereview.agent.core.permission.VetoPolicy;
import com.codereview.agent.core.profile.ReviewProfile;
import com.codereview.agent.core.resume.FileResumeStore;
import com.codereview.agent.core.resume.ResumeState;
import com.codereview.agent.core.tools.ToolGate;
import com.codereview.agent.core.trajectory.ReviewEvent;
import com.codereview.agent.core.trajectory.ReviewTrajectoryRecorder;
import com.codereview.agent.core.feedback.FeedbackStore;
import com.codereview.agent.core.history.FindingSummary;
import com.codereview.agent.core.history.ReviewHistoryEntry;
import com.codereview.agent.core.history.ReviewHistoryStore;
import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.llm.aiservice.CodeReviewAiService;
import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.planning.TaskPlanningSupport;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.memory.RagContextBuilder;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.report.AgentDegradation;
import com.codereview.agent.core.report.ReportGenerator;
import com.codereview.agent.core.report.VerificationResult;
import com.codereview.agent.core.security.InjectionDetector;
import com.codereview.agent.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 基于 {@link CompletableFuture} 的协调者实现（生产推荐混合模式）。
 *
 * <p>设计要点（见文档）：
 * <ul>
 *   <li>为每个 Agent 创建一个 Future，并行执行审查；</li>
 *   <li>使用<b>逐 Future 独立限时等待</b>（共享 deadline 递减，默认 5 分钟）实现超时控制；
 *       修复前曾用 {@code allOf + orTimeout}，但那只作用于聚合节点，后续 {@code join()} 仍会无限阻塞；</li>
 *   <li>支持“部分失败”：个别 Agent 超时 / 异常时，其余结果仍可用，不阻塞主线程，
 *       且降级环节以 {@code AgentResult.degraded} + {@code AgentDegradation} 如实进入报告；</li>
 *   <li>生成 runId 并计时，落地到审查历史，支持“修复后复检”增量对比；</li>
 *   <li>结果汇聚后交由 {@link ReportGenerator} 去重、优先级仲裁、误报抑制、定档。</li>
 * </ul>
 */
public class CompletableFutureCoordinator implements Coordinator {

    private static final Logger log = LoggerFactory.getLogger(CompletableFutureCoordinator.class);

    /** 审查整体超时时间（毫秒），默认 5 分钟。显式初值保证非 Spring 构造（单测）也有默认值。 */
    @Value("${review.agent.timeout-ms:300000}")
    private long timeoutMillis = 300_000L;

    /** 审查强度 Profile（默认 ADVISORY：只保留 Major 及以上）。 */
    @Value("${review.profile:ADVISORY}")
    private String profileConfig;

    private final List<ReviewAgent> agents;
    private final ReportGenerator reportGenerator;
    private final FeedbackStore feedbackStore;
    private final ReviewHistoryStore historyStore;
    private final AdvancedAnalyzer advancedAnalyzer;
    private final Executor agentExecutor;
    /** 影响面分析器（可空：为 null 时跳过影响面注入）。 */
    private final ImpactAnalyzer impactAnalyzer;
    /** 审查轨迹记录器（可空：为 null 时不记录轨迹，零侵入）。 */
    private final ReviewTrajectoryRecorder recorder;
    /** 断点续跑存储（P0-③，可空）。 */
    private final FileResumeStore resumeStore;
    /** 权限收敛：BLOCKER 免于抑制/覆盖（P1-⑥，可空）。 */
    private final VetoPolicy vetoPolicy;
    /** 工具分级门控（P1-⑦，可空）。 */
    private final ToolGate toolGate;
    /** RAG 上下文构建器（可空：为 null 时跳过 RAG 注入，审查仍可用）。 */
    private final RagContextBuilder ragContextBuilder;
    /** 自定义 Agent 存储（可空：为 null 时不展开业务方自定义 Agent）。 */
    private final CustomAgentStore customAgentStore;
    /** 大模型客户端（自定义 Agent 调 LLM 用，可空）。 */
    private final LlmClient llmClient;
    /** LangChain4j AiServices（结构化输出，可空 → 回退文本）。 */
    private final CodeReviewAiService aiService;
    /** Prompt 注入检测器（自定义 Agent 防御用，可空）。 */
    private final InjectionDetector injectionDetector;
    /** 任务规划织入支撑（可空：为 null 或未启用时走固定并行路径）。 */
    private final TaskPlanningSupport planningSupport;
    /**
     * 影响面索引构建器（可空：为 null 时影响面分析只能看到 diff 片段，拿不到结论）。
     *
     * <p>这是影响面分析从「测试里能跑」变成「线上真生效」的关键接线：
     * 分析器本身再准，没有完整文件内容也无从下手。
     */
    private final ImpactIndexBuilder indexBuilder;

    public CompletableFutureCoordinator(List<ReviewAgent> agents, ReportGenerator reportGenerator,
                                       FeedbackStore feedbackStore, ReviewHistoryStore historyStore,
                                       AdvancedAnalyzer advancedAnalyzer) {
        this(agents, reportGenerator, feedbackStore, historyStore, advancedAnalyzer,
                ForkJoinPool.commonPool(), null, null, ReviewEnhancements.none());
    }

    public CompletableFutureCoordinator(List<ReviewAgent> agents, ReportGenerator reportGenerator,
                                       FeedbackStore feedbackStore, ReviewHistoryStore historyStore,
                                       AdvancedAnalyzer advancedAnalyzer, Executor agentExecutor) {
        this(agents, reportGenerator, feedbackStore, historyStore, advancedAnalyzer,
                agentExecutor, null, null, ReviewEnhancements.none());
    }

    public CompletableFutureCoordinator(List<ReviewAgent> agents, ReportGenerator reportGenerator,
                                       FeedbackStore feedbackStore, ReviewHistoryStore historyStore,
                                       AdvancedAnalyzer advancedAnalyzer, Executor agentExecutor,
                                       ImpactAnalyzer impactAnalyzer, ReviewTrajectoryRecorder recorder) {
        this(agents, reportGenerator, feedbackStore, historyStore, advancedAnalyzer,
                agentExecutor, impactAnalyzer, recorder, ReviewEnhancements.none());
    }

    public CompletableFutureCoordinator(List<ReviewAgent> agents, ReportGenerator reportGenerator,
                                       FeedbackStore feedbackStore, ReviewHistoryStore historyStore,
                                       AdvancedAnalyzer advancedAnalyzer, Executor agentExecutor,
                                       ImpactAnalyzer impactAnalyzer, ReviewTrajectoryRecorder recorder,
                                       ReviewEnhancements enhancements) {
        this(agents, reportGenerator, feedbackStore, historyStore, advancedAnalyzer,
                agentExecutor, impactAnalyzer, recorder, enhancements, null);
    }

    public CompletableFutureCoordinator(List<ReviewAgent> agents, ReportGenerator reportGenerator,
                                       FeedbackStore feedbackStore, ReviewHistoryStore historyStore,
                                       AdvancedAnalyzer advancedAnalyzer, Executor agentExecutor,
                                       ImpactAnalyzer impactAnalyzer, ReviewTrajectoryRecorder recorder,
                                       ReviewEnhancements enhancements,
                                       RagContextBuilder ragContextBuilder) {
        this(agents, reportGenerator, feedbackStore, historyStore, advancedAnalyzer, agentExecutor,
                impactAnalyzer, recorder, enhancements, ragContextBuilder, null, null, null, null, null);
    }

    public CompletableFutureCoordinator(List<ReviewAgent> agents, ReportGenerator reportGenerator,
                                       FeedbackStore feedbackStore, ReviewHistoryStore historyStore,
                                       AdvancedAnalyzer advancedAnalyzer, Executor agentExecutor,
                                       ImpactAnalyzer impactAnalyzer, ReviewTrajectoryRecorder recorder,
                                       ReviewEnhancements enhancements,
                                       RagContextBuilder ragContextBuilder,
                                       CustomAgentStore customAgentStore,
                                       LlmClient llmClient,
                                       CodeReviewAiService aiService,
                                       InjectionDetector injectionDetector) {
        this(agents, reportGenerator, feedbackStore, historyStore, advancedAnalyzer, agentExecutor,
                impactAnalyzer, recorder, enhancements, ragContextBuilder, customAgentStore,
                llmClient, aiService, injectionDetector, null);
    }

    public CompletableFutureCoordinator(List<ReviewAgent> agents, ReportGenerator reportGenerator,
                                       FeedbackStore feedbackStore, ReviewHistoryStore historyStore,
                                       AdvancedAnalyzer advancedAnalyzer, Executor agentExecutor,
                                       ImpactAnalyzer impactAnalyzer, ReviewTrajectoryRecorder recorder,
                                       ReviewEnhancements enhancements,
                                       RagContextBuilder ragContextBuilder,
                                       CustomAgentStore customAgentStore,
                                       LlmClient llmClient,
                                       CodeReviewAiService aiService,
                                       InjectionDetector injectionDetector,
                                       TaskPlanningSupport planningSupport) {
        this(agents, reportGenerator, feedbackStore, historyStore, advancedAnalyzer, agentExecutor,
                impactAnalyzer, recorder, enhancements, ragContextBuilder, customAgentStore,
                llmClient, aiService, injectionDetector, planningSupport, null);
    }

    public CompletableFutureCoordinator(List<ReviewAgent> agents, ReportGenerator reportGenerator,
                                       FeedbackStore feedbackStore, ReviewHistoryStore historyStore,
                                       AdvancedAnalyzer advancedAnalyzer, Executor agentExecutor,
                                       ImpactAnalyzer impactAnalyzer, ReviewTrajectoryRecorder recorder,
                                       ReviewEnhancements enhancements,
                                       RagContextBuilder ragContextBuilder,
                                       CustomAgentStore customAgentStore,
                                       LlmClient llmClient,
                                       CodeReviewAiService aiService,
                                       InjectionDetector injectionDetector,
                                       TaskPlanningSupport planningSupport,
                                       ImpactIndexBuilder indexBuilder) {
        this.agents = agents;
        this.reportGenerator = reportGenerator;
        this.feedbackStore = feedbackStore;
        this.historyStore = historyStore;
        this.advancedAnalyzer = advancedAnalyzer;
        this.agentExecutor = agentExecutor;
        this.impactAnalyzer = impactAnalyzer;
        this.recorder = recorder;
        this.resumeStore = enhancements == null ? null : enhancements.resumeStore();
        this.vetoPolicy = enhancements == null ? null : enhancements.vetoPolicy();
        this.toolGate = enhancements == null ? null : enhancements.toolGate();
        this.ragContextBuilder = ragContextBuilder;
        this.customAgentStore = customAgentStore;
        this.llmClient = llmClient;
        this.aiService = aiService;
        this.injectionDetector = injectionDetector;
        this.planningSupport = planningSupport;
        this.indexBuilder = indexBuilder;
    }

    /**
     * 设置审查超时（毫秒）。Spring 经 @Value 注入；测试可显式调小以验证超时路径。
     *
     * @param timeoutMillis 超时毫秒数（<=0 时回退默认 300000）
     */
    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis > 0 ? timeoutMillis : 300_000L;
    }

    /** 生效的审查强度 Profile（未配置 / 非法时回退 ADVISORY）。 */
    private ReviewProfile effectiveProfile() {
        if (profileConfig == null || profileConfig.isBlank()) {
            return ReviewProfile.ADVISORY;
        }
        try {
            return ReviewProfile.valueOf(profileConfig.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ReviewProfile.ADVISORY;
        }
    }

    /**
     * 构建影响面摘要（注入 Agent prompt 的上下文片段）。
     *
     * <h2>为什么必须先建索引</h2>
     * 影响面分析回答的是「谁在调用我改动的方法」，而 diff 只带 ±3 行上下文，
     * 连一个完整方法体都未必装得下，更别说跨文件的调用方。
     * 所以这里先按 PR 的 head SHA 把相关源码拉下来建索引（用完在 try-with-resources 里释放临时目录），
     * 再把索引交给 {@link ImpactAnalyzer} 做真正的分析。
     *
     * <p>索引不可用时（未配置 / 无 head SHA / 拉取失败）<b>不做任何伪装</b>：
     * 由分析器回落为 {@code Mode.NO_SOURCE}，明确表达「这次没结论是因为缺输入」，
     * 而不是「这个改动没有影响面」——后者正是旧实现长期以「恒产出 0 条」掩盖故障的原因。
     *
     * @param pr    PR（提供 owner/repo 与 head SHA）
     * @param diffs 变更列表
     * @param runId 运行号（用于轨迹记录）
     * @return 摘要；无结论时为空串，调用方据此决定是否注入，避免噪声
     */
    private String buildImpactSummary(PullRequest pr, List<CodeDiff> diffs, String runId) {
        if (impactAnalyzer == null) {
            return "";
        }
        if (indexBuilder == null) {
            return impactAnalyzer.summarize(diffs);
        }
        try (RepoIndex index = indexBuilder.build(pr)) {
            String summary = impactAnalyzer.summarize(diffs, index);
            // 索引统计落轨迹：让「为什么这次没结论」可被事后诊断，而不是只能靠猜
            if (recorder != null) {
                recorder.append(runId, "context.index-built", Map.of(
                        "fetched", index.stats().fetched(),
                        "analyzed", index.stats().analyzed(),
                        "failed", index.stats().failed(),
                        "truncated", index.stats().truncated(),
                        "crossFile", index.crossFileCapable(),
                        "summaryLength", summary.length()));
            }
            return summary;
        } catch (Exception e) {
            log.warn("[Coordinator] 影响面索引构建失败，跳过注入（不影响主审查）：{}", e.getMessage());
            return "";
        }
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

        // 审查轨迹：开启会话并记录「审查开始」事件（事件源不变量：模型可见即可追溯）
        if (recorder != null) {
            recorder.begin(runId, teamId);
            recorder.append(runId, "review.started", Map.of(
                    "prId", pr.id(), "repo", pr.repo(), "teamId", teamId, "agentCount", agents.size()));
            // 模型输入规模登记：diff 构成（文件数 / 行数 / 语言），保证「喂给模型的输入可追溯」
            int added = diffs.stream().mapToInt(CodeDiff::addedLines).sum();
            int del = diffs.stream().mapToInt(CodeDiff::delLines).sum();
            long javaFiles = diffs.stream().filter(d -> "java".equals(d.language())).count();
            recorder.append(runId, "context.diff-loaded", Map.of(
                    "files", diffs.size(), "addedLines", added, "delLines", del, "javaFiles", javaFiles));
        }

        // 断点续跑（对齐 codex suspend/recover）：同 runId 已有未完成审查 → 恢复已完成 Agent，只重跑剩余
        List<ReviewAgent> pendingAgents = agents;
        List<AgentResult> resumedResults = new ArrayList<>();
        if (resumeStore != null) {
            Optional<ResumeState> prev = resumeStore.load(runId, teamId);
            if (prev.isPresent()) {
                ResumeState rs = prev.get();
                Set<AgentType> done = rs.doneAgents();
                pendingAgents = agents.stream()
                        .filter(a -> !done.contains(a.getType()))
                        .toList();
                Map<AgentType, List<Finding>> byType = rs.findings().stream()
                        .collect(Collectors.groupingBy(Finding::agentType));
                byType.forEach((t, fs) -> resumedResults.add(new AgentResult(pr.id(), t, fs)));
                log.info("[Coordinator] 检测到断点（runId={}），恢复续跑：已完成 {} 个 Agent，剩余 {} 个",
                        runId, done.size(), pendingAgents.size());
                if (recorder != null) {
                    recorder.append(runId, "review.resumed", Map.of(
                            "doneAgents", done.size(), "pendingAgents", pendingAgents.size()));
                }
            }
        }

        // 上下文影响面切片：计算变更方法的上游调用方，注入 Agent 提示词（对齐 codex context-fragments）
        String impactSummary = buildImpactSummary(pr, diffs, runId);
        // RAG 增强：检索团队相关规范/历史知识，注入 Agent 提示词（对齐业界 RAG 最佳实践）
        // 仅在 RagContextBuilder 可用时执行；异常不阻断主审查链路
        final String ragContext;
        if (ragContextBuilder != null) {
            String built;
            try {
                built = ragContextBuilder.buildContext(teamId, "MULTI-AGENT", diffs);
            } catch (Exception e) {
                log.warn("[Coordinator] RAG 上下文构建失败，跳过注入（不影响主审查）：{}", e.getMessage());
                built = "";
            }
            ragContext = built == null ? "" : built;
            if (recorder != null && !ragContext.isBlank()) {
                recorder.append(runId, "context.injected", Map.of(
                        "type", "rag-knowledge", "summaryLength", ragContext.length()));
            }
        } else {
            ragContext = "";
        }
        final ReviewContext enrichedCtx = ctx
                .withImpactSummary(impactSummary)
                .withRagContext(ragContext);
        if (recorder != null && !impactSummary.isBlank()) {
            recorder.append(runId, "context.injected", Map.of(
                    "type", "impact-surface", "summaryLength", impactSummary.length()));
        }

        // 业务方自定义 Agent 展开（按 teamId 隔离，与 5 个内置子 Agent 并行；可降级、零侵入）
        List<ReviewAgent> effectiveAgents = pendingAgents;
        List<String> customAgentNames = List.of();
        if (customAgentStore != null) {
            try {
                List<CustomAgentDef> customDefs = customAgentStore.listEnabled(teamId);
                if (!customDefs.isEmpty()) {
                    List<ReviewAgent> expanded = new ArrayList<>(pendingAgents);
                    for (CustomAgentDef def : customDefs) {
                        expanded.add(new DeclarativeReviewAgent(def, llmClient, aiService, injectionDetector));
                    }
                    effectiveAgents = expanded;
                    customAgentNames = customDefs.stream().map(CustomAgentDef::name).toList();
                    if (recorder != null) {
                        recorder.append(runId, "agent.custom.expanded", Map.of(
                                "count", customDefs.size(),
                                "names", customDefs.stream().map(CustomAgentDef::name).toList()));
                    }
                    log.info("[Coordinator] 团队 {} 注入 {} 个自定义 Agent，总 Agent 数={}",
                            teamId, customDefs.size(), effectiveAgents.size());
                }
            } catch (Exception e) {
                // 可降级：自定义 Agent 子系统异常不影响内置 5 Agent
                log.warn("[Coordinator] 自定义 Agent 展开失败，仅跑内置 Agent：{}", e.getMessage());
                if (recorder != null) {
                    recorder.append(runId, "agent.custom.disabled", Map.of("reason", e.getMessage()));
                }
            }
        }

        // 上一轮审查记录（用于复检验证）
        ReviewHistoryEntry previous = historyStore == null ? null
                : historyStore.getLatest(teamId, pr.repo() + "#" + pr.id()).orElse(null);

        // 0.9 任务拆解规划（可选增强，review.planning.enabled=true 生效）：LLM 把审查目标拆解为
        //     子任务 DAG，按依赖拓扑并行执行；空结果 = 未启用 / 规划失败，自动降级固定并行路径
        List<AgentResult> plannedResults = planningSupport == null ? List.of()
                : planningSupport.planAndExecute("审查 PR #" + pr.id() + "：" + pr.title(),
                        diffs, enrichedCtx, effectiveAgents, pr.id(), recorder, runId);

        List<AgentResult> mainResults = new ArrayList<>();
        if (!plannedResults.isEmpty()) {
            mainResults.addAll(plannedResults);
        }

        // 1. 为每个 Agent 创建 Future，并行审查（TraceContext.wrap 保证 traceId 跨线程传播）；
        //    规划路径已产出结果时跳过固定并行
        List<CompletableFuture<AgentResult>> futures = List.of();
        if (plannedResults.isEmpty()) {
            futures = effectiveAgents.stream()
                .map(agent -> CompletableFuture.supplyAsync(TraceContext.wrap(() -> {
                    long a0 = System.currentTimeMillis();
                    if (recorder != null) {
                        recorder.append(runId, "agent.started", Map.of("agentType", agent.getType().name()));
                    }
                    log.info("[Coordinator] 子Agent[{}] 开始审查（并行执行）", agent.getType());
                    List<Finding> findings = agent.review(diffs, enrichedCtx);
                    log.info("[Coordinator] 子Agent[{}] 完成：发现 {} 条，耗时 {}ms",
                            agent.getType(), findings.size(), System.currentTimeMillis() - a0);
                    return new AgentResult(pr.id(), agent.getType(), findings);
                }), agentExecutor))
                .toList();
        }

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

        // 2. 并行结果收集：每个 Future 独立限时等待，超时/异常 → 该 Agent 标记为「降级」并入报告。
        //    修复前（P0-1）：orTimeout 挂在 allOf 聚合 future 上只触发一条 warn，随后的 join()
        //    无超时仍会无限阻塞；advancedFuture 也没进超时体系。现在以同一 deadline 逐个限时取结果，
        //    超时即 cancel(true) 中断底层执行线程，绝不无限等待，且降级在报告/轨迹中如实可见。
        long deadline = System.currentTimeMillis() + Math.max(1L, timeoutMillis);
        List<AgentDegradation> degradations = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<AgentResult> future = futures.get(i);
            ReviewAgent agent = effectiveAgents.get(i);
            long remaining = Math.max(deadline - System.currentTimeMillis(), 1L);
            try {
                AgentResult result = future.get(remaining, TimeUnit.MILLISECONDS);
                mainResults.add(result);
                saveCheckpoint(runId, pr, teamId, resumedResults, mainResults);
            } catch (TimeoutException te) {
                future.cancel(true); // 中断底层执行线程，避免任务泄漏
                String reason = "执行超时（" + timeoutMillis + "ms）";
                log.warn("[Coordinator] 子Agent[{}] 超时（{}ms），已中断并标记降级", agent.getType(), timeoutMillis);
                mainResults.add(AgentResult.degraded(pr.id(), agent.getType(), reason));
                degradations.add(new AgentDegradation(agent.getType().name(), reason));
                if (recorder != null) {
                    recorder.append(runId, "agent.degraded", Map.of(
                            "agentType", agent.getType().name(), "reason", reason));
                }
            } catch (Exception e) {
                String reason = rootMessage(e);
                log.warn("[Coordinator] 子Agent[{}] 异常，已标记降级：{}", agent.getType(), reason);
                mainResults.add(AgentResult.degraded(pr.id(), agent.getType(), reason));
                degradations.add(new AgentDegradation(agent.getType().name(), reason));
                if (recorder != null) {
                    recorder.append(runId, "agent.degraded", Map.of(
                            "agentType", agent.getType().name(), "reason", reason));
                }
            }
        }
        List<AgentResult> results = new ArrayList<>(resumedResults);
        results.addAll(mainResults);

        // 2.1 高级静态分析同样纳入限时等待（修复前 join() 无超时，可能无限阻塞主线程）
        if (advancedFuture != null) {
            long remaining = Math.max(deadline - System.currentTimeMillis(), 1L);
            try {
                results.addAll(advancedFuture.get(remaining, TimeUnit.MILLISECONDS));
            } catch (TimeoutException te) {
                advancedFuture.cancel(true);
                String reason = "高级静态分析超时（" + timeoutMillis + "ms）";
                log.warn("[Coordinator] 高级静态分析超时，已中断并标记降级：{}ms", timeoutMillis);
                degradations.add(new AgentDegradation(AgentDegradation.STAGE_ADVANCED_ANALYSIS, reason));
                if (recorder != null) {
                    recorder.append(runId, "advanced.degraded", Map.of("reason", reason));
                }
            } catch (Exception e) {
                String reason = rootMessage(e);
                log.warn("[Coordinator] 高级静态分析异常，已标记降级：{}", reason);
                degradations.add(new AgentDegradation(AgentDegradation.STAGE_ADVANCED_ANALYSIS, reason));
                if (recorder != null) {
                    recorder.append(runId, "advanced.degraded", Map.of("reason", reason));
                }
            }
        }

        // 审查轨迹：记录各 Agent 完成事件（类型 + 发现数，便于事后回放/审计）；降级的已单独留痕
        if (recorder != null) {
            for (AgentResult r : results) {
                if (r.degraded()) {
                    continue;
                }
                recorder.append(runId, "agent.completed", Map.of(
                        "agentType", r.agentType().name(), "findingCount", r.findings().size()));
            }
        }

        long durationMs = System.currentTimeMillis() - start;

        // 4. 汇聚、去重、优先级仲裁、误报抑制、定档（降级记录随报告输出告警块）
        ReviewReport report = reportGenerator.aggregate(
                pr.id(), pr.repo(), results, feedbackStore, runId, durationMs, teamId, degradations);

        // 4.0 报告如实标注本次参与的业务方自定义 Agent（无则不显示）
        if (!customAgentNames.isEmpty()) {
            report = report.withCustomAgents(customAgentNames);
        }

        // 4.1 权限收敛（P1-⑥）：BLOCKER 级强否决不可被误报抑制 / 仲裁覆盖（父不覆盖子）
        if (vetoPolicy != null) {
            report = vetoPolicy.apply(report);
        }

        // 4.2 审查强度 Profile（P1-④）：按 STRICT/ADVISORY/SUGGEST 过滤最终对外发现
        ReviewProfile profile = effectiveProfile();
        if (profile != ReviewProfile.SUGGEST) {
            report = report.withFindings(profile.apply(report.getFindings()));
        }

        // 5. 复检验证（与上一轮对比已解决 / 未解决 / 新引入）
        VerificationResult verification = computeVerification(previous, report.getFindings());
        report = report.withVerification(verification);

        // 6. 落地本轮审查历史（供下次复检与质量趋势统计）
        if (historyStore != null) {
            historyStore.save(teamId, new ReviewHistoryEntry(
                    pr.id(), pr.repo(), runId, System.currentTimeMillis(),
                    toSummaries(report.getFindings())));
        }

        // 6.1 正常完成 → 清理断点（不留脏状态）
        if (resumeStore != null) {
            resumeStore.complete(runId, teamId);
        }

        log.info("[Coordinator] PR#{} 审查完成（runId={}，profile={}）：最终 {} 条，抑制误报 {} 条，仲裁覆盖 {} 条，" +
                        "降级环节 {} 个，复检已解决 {} 条 / 未解决 {} 条 / 新引入 {} 条",
                pr.id(), runId, profile, report.getFindings().size(), report.getSuppressedFindings().size(),
                report.getOverriddenFindings().size(), report.getDegradations().size(),
                verification.resolvedCount(),
                verification.unresolvedCount(), verification.introducedCount());

        // 审查轨迹：记录「审查结束」并落盘（若 recorder 可用）
        if (recorder != null) {
            recorder.append(runId, "review.completed", Map.of(
                    "totalFindings", report.getFindings().size(), "durationMs", durationMs));
            recorder.close(runId);
        }
        return report;
    }

    /**
     * 保存断点快照：已完成 Agent 类型 + 已产出发现（供崩溃后同 runId 续跑）。
     * 仅统计主审查 Agent（高级静态分析每次重跑，不参与断点）。
     */
    private void saveCheckpoint(String runId, PullRequest pr, String teamId,
                                List<AgentResult> resumed, List<AgentResult> mainResults) {
        if (resumeStore == null) {
            return;
        }
        Set<AgentType> done = new HashSet<>();
        List<Finding> findings = new ArrayList<>();
        for (AgentResult r : resumed) {
            if (r.degraded()) {
                continue;
            }
            done.add(r.agentType());
            findings.addAll(r.findings());
        }
        for (AgentResult r : mainResults) {
            if (r.degraded()) {
                continue; // 降级（超时/异常）的 Agent 不写入断点：续跑时应重试而不是当作已完成
            }
            done.add(r.agentType());
            findings.addAll(r.findings());
        }
        resumeStore.save(new ResumeState(runId, pr.id(), pr.repo(), teamId, done, findings,
                System.currentTimeMillis()));
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

    /**
     * 从 {@link CompletableFuture#get} 抛出的包装异常中解出真正原因。
     * {@code ExecutionException} 包裹的是 Agent 执行体抛出的异常，直接取 cause 才有人类可读信息。
     *
     * @param e 捕获的异常（可能是 ExecutionException / CompletionException 包装）
     * @return 根因消息；无消息时回退根因类名
     */
    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while ((cause instanceof ExecutionException || cause instanceof java.util.concurrent.CompletionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return (msg == null || msg.isBlank()) ? cause.getClass().getSimpleName() : msg;
    }
}
