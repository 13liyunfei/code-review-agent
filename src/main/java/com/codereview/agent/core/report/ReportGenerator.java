package com.codereview.agent.core.report;

import com.codereview.agent.core.feedback.FeedbackStore;
import com.codereview.agent.core.memory.ReviewFeedback;
import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.model.Severity;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报告生成器：负责聚合发现、去重、优先级冲突仲裁、误报抑制与分级统计，产出 {@link ReviewReport}。
 *
 * <p>聚合策略（见文档）：
 * <ul>
 *   <li><b>去重</b>：同一文件 + 同一行区间 + 同一规则视为重复，保留置信度更高、严重级别更高者；</li>
 *   <li><b>冲突仲裁</b>：不同 Agent 在同一位置意见冲突时，按优先级权重（见 {@link ArbitrationPolicy}）择高，落败方进入 overriddenFindings；</li>
 *   <li><b>误报抑制</b>：命中开发者已确认误报（{@link FeedbackStore}）的发现被移入 suppressedFindings，不再计入分级；</li>
 *   <li><b>分级定档</b>：按 BLOCKER / MAJOR / MINOR / INFO 统计数量。</li>
 * </ul>
 */
public class ReportGenerator {

    /**
     * 由各 Agent 结果聚合生成报告（无基础设施级降级记录的重载）。
     *
     * @param prId            PR 标识
     * @param repo            仓库名
     * @param results         各 Agent 结果
     * @param feedbackStore   反馈存储（用于误报抑制，可为 null）
     * @param runId           本次运行 ID（调用链追踪）
     * @param durationMs      审查耗时
     * @param teamId          团队标识
     * @return 聚合后的审查报告
     */
    public ReviewReport aggregate(long prId, String repo, List<AgentResult> results,
                                  FeedbackStore feedbackStore, String runId, long durationMs,
                                  String teamId) {
        return aggregate(prId, repo, results, feedbackStore, runId, durationMs, teamId, List.of());
    }

    /**
     * 由各 Agent 结果聚合生成报告。
     *
     * @param prId            PR 标识
     * @param repo            仓库名
     * @param results         各 Agent 结果
     * @param feedbackStore   反馈存储（用于误报抑制，可为 null）
     * @param runId           本次运行 ID（调用链追踪）
     * @param durationMs      审查耗时
     * @param teamId          团队标识
     * @param infraDegradations 基础设施级降级（如高级静态分析超时）；Agent 级降级由
     *                          {@code results} 中 {@code degraded=true} 的结果自动推导
     * @return 聚合后的审查报告
     */
    public ReviewReport aggregate(long prId, String repo, List<AgentResult> results,
                                  FeedbackStore feedbackStore, String runId, long durationMs,
                                  String teamId, List<AgentDegradation> infraDegradations) {
        // 0. 降级收集：Agent 级（结果自带 degraded 标记）+ 基础设施级（入参），按环节去重。
        //    降级意味着「这个维度没看成」，必须与「确实没问题」区分开，写入报告告警块。
        Map<String, AgentDegradation> degradationByStage = new LinkedHashMap<>();
        if (results != null) {
            for (AgentResult result : results) {
                if (result != null && result.degraded() && result.agentType() != null) {
                    degradationByStage.putIfAbsent(result.agentType().name(),
                            new AgentDegradation(result.agentType().name(), result.error()));
                }
            }
        }
        if (infraDegradations != null) {
            for (AgentDegradation d : infraDegradations) {
                degradationByStage.putIfAbsent(d.stage(), d);
            }
        }

        // 1. 按 (文件 @ 行区间 # 规则) 去重；同键保留置信度更高、严重级别更高者
        Map<String, Finding> merged = new LinkedHashMap<>();
        for (AgentResult result : results) {
            if (result == null || result.findings() == null) {
                continue;
            }
            for (Finding f : result.findings()) {
                String key = f.dedupKey();
                Finding existing = merged.get(key);
                if (existing == null) {
                    merged.put(key, f);
                } else {
                    boolean replace = f.severity().isMoreSevereThan(existing.severity())
                            || (f.severity() == existing.severity() && f.confidence() > existing.confidence());
                    if (replace) {
                        merged.put(key, f);
                    }
                }
            }
        }

        List<Finding> mergedList = new ArrayList<>(merged.values());

        // 2. 优先级冲突仲裁：同一位置不同 Agent 建议冲突时，高优先级胜出
        List<Finding> overridden = new ArrayList<>();
        List<String> arbitrationNotes = new ArrayList<>();
        Set<String> removed = new HashSet<>();
        for (int i = 0; i < mergedList.size(); i++) {
            Finding a = mergedList.get(i);
            if (removed.contains(a.dedupKey())) {
                continue;
            }
            for (int j = i + 1; j < mergedList.size(); j++) {
                Finding b = mergedList.get(j);
                if (removed.contains(b.dedupKey())) {
                    continue;
                }
                if (!ArbitrationPolicy.isConflict(a, b)) {
                    continue;
                }
                Finding winner = ArbitrationPolicy.winner(a, b) == a.agentType() ? a : b;
                Finding loser = winner == a ? b : a;
                removed.add(loser.dedupKey());
                overridden.add(loser);
                arbitrationNotes.add(String.format(
                        "在 %s:L%d 处，%s 与 %s 意见冲突（落败方建议：%s；胜出方建议：%s），按优先级（安全>逻辑>性能>架构>风格）保留 %s。",
                        loser.file(), loser.lineStart(),
                        winner.agentType().getDisplayName(), loser.agentType().getDisplayName(),
                        loser.suggestion(), winner.suggestion(), winner.agentType().getDisplayName()));
            }
        }

        List<Finding> afterArbitration = new ArrayList<>();
        for (Finding f : mergedList) {
            if (!removed.contains(f.dedupKey())) {
                afterArbitration.add(f);
            }
        }

        // 3. 误报抑制：命中开发者已确认误报的发现移入 suppressedFindings
        List<ReviewFeedback> falsePositives = feedbackStore == null
                ? List.of() : feedbackStore.falsePositives(teamId);
        List<Finding> suppressed = new ArrayList<>();
        List<Finding> kept = new ArrayList<>();
        for (Finding f : afterArbitration) {
            if (matchesFalsePositive(f, falsePositives)) {
                suppressed.add(f);
            } else {
                kept.add(f);
            }
        }

        // 4. 分级统计（仅保留未被抑制的发现）
        Map<Severity, Long> severityCount = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) {
            severityCount.put(s, kept.stream().filter(f -> f.severity() == s).count());
        }

        return new ReviewReport(prId, repo, kept, severityCount,
                runId, durationMs, arbitrationNotes, overridden, suppressed, VerificationResult.none(),
                List.of(), new ArrayList<>(degradationByStage.values()));
    }

    /**
     * 判断某发现是否命中已确认误报。
     *
     * @param f  发现
     * @param fps 误报反馈列表
     * @return 是否应被抑制
     */
    private boolean matchesFalsePositive(Finding f, List<ReviewFeedback> fps) {
        for (ReviewFeedback fb : fps) {
            if (!fb.ruleId().equalsIgnoreCase(f.ruleId())) {
                continue;
            }
            if (fb.agentType() != null && !fb.agentType().isBlank()
                    && !fb.agentType().equalsIgnoreCase(f.agentType().name())) {
                continue;
            }
            if (fb.file() != null && !fb.file().isBlank()) {
                boolean fileMatch = f.file().equals(fb.file())
                        || f.file().endsWith("/" + fb.file())
                        || f.file().endsWith(fb.file());
                if (!fileMatch) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }
}
