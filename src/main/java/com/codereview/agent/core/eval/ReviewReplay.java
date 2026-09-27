package com.codereview.agent.core.eval;

import com.codereview.agent.core.feedback.FeedbackStore;
import com.codereview.agent.core.memory.ReviewFeedback;
import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.permission.VetoPolicy;
import com.codereview.agent.core.report.ReportGenerator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 确定性回放评测：给定**冻结的候选发现**，跑一遍真正的确定性后处理链，得到最终发现集。
 *
 * <p><b>这件事为什么必须存在</b>：任何 prompt 或模型改动之后，唯一能回答"到底变好没有"的
 * 办法是「同一份输入，前后两次跑，比较最终结论」。而 LLM 本身不确定，所以能进 CI 的部分
 * 只能是**确定性链**——本类把它切出来的链路是：
 *
 * <pre>
 *   冻结候选（每 Agent 一批）
 *     → ReportGenerator.aggregate（去重 → 优先级仲裁 → 误报抑制 → 分级统计）
 *     → VetoPolicy.apply（BLOCKER 强否决回收：父不覆盖子）
 *     → 最终 findings
 * </pre>
 *
 * <p>这段链上一个随机源都没有：去重键是 {@code 文件@行区间#规则}，仲裁按 Agent 优先级，
 * 抑制按反馈列表，否决按严重级别。所以它能满足验收判据②「同一次改动前后两次跑分可复现」，
 * 并且 {@link #verifyDeterminism} 会**机械地**证明这一点（跑两次逐条比对）。
 *
 * <p><b>本类明确不测什么</b>：不测模型质量、不测 prompt 效果、不测 RAG 召回。
 * 那些要等真实模型采集接进来（{@code replayInput.origin=captured}）才有意义。
 * <b>把没测的说成测了，是这类基准最危险的失败方式。</b>
 *
 * @see ReviewBenchMetrics 指标与匹配口径
 */
public class ReviewReplay {

    /**
     * 回放结果。
     *
     * <p>抑制/覆盖都给了**两个时点**的计数：{@code *BeforeVeto} 是聚合刚结束时，
     * 另一个是 VetoPolicy 回收之后。**只报一个数会看不出否决有没有生效**——
     * 「被抑制了 1 条」在否决前与否决后含义完全不同（后者说明 BLOCKER 被捞回、抑制列表已空）。
     */
    public record Outcome(List<Finding> findings, ReviewReport report,
                          int candidateCount,
                          int suppressedBeforeVeto, int overriddenBeforeVeto,
                          int suppressedAfterVeto, int overriddenAfterVeto) {
    }

    private final ReportGenerator reportGenerator;
    private final VetoPolicy vetoPolicy;

    public ReviewReplay(ReportGenerator reportGenerator, VetoPolicy vetoPolicy) {
        this.reportGenerator = reportGenerator;
        this.vetoPolicy = vetoPolicy;
    }

    /** 便捷构造（便于测试与离线跑）。 */
    public ReviewReplay() {
        this(new ReportGenerator(), new VetoPolicy());
    }

    /**
     * 回放一条 case。
     *
     * @param c 基准用例
     * @return 最终发现集与中间量（抑制/覆盖的条数也要报出来，否则无法解释指标变化）
     */
    public Outcome replay(BenchCase c) {
        List<Finding> candidates = c.replayInput().findings();
        if (candidates == null || candidates.isEmpty()) {
            return new Outcome(List.of(), null, 0, 0, 0, 0, 0);
        }

        // 1. 按 Agent 分组 → AgentResult（聚合器的输入形态）
        Map<AgentType, List<Finding>> byAgent = new LinkedHashMap<>();
        for (Finding f : candidates) {
            byAgent.computeIfAbsent(f.agentType(), k -> new ArrayList<>()).add(f);
        }
        long prId = stablePrId(c.id());
        List<AgentResult> results = new ArrayList<>();
        for (Map.Entry<AgentType, List<Finding>> e : byAgent.entrySet()) {
            results.add(new AgentResult(prId, e.getKey(), List.copyOf(e.getValue())));
        }

        // 2. 反馈存储：只用 case 里预置的反馈（无盘、无状态，保证可复现）
        FeedbackStore store = new BenchFeedbackStore(c.replayInput().feedback());

        // 3. 确定性链
        ReviewReport aggregated = reportGenerator.aggregate(
                prId, c.repoKey(), results, store,
                "bench-" + c.id(), 0L, BENCH_TEAM);
        int supBefore = aggregated.getSuppressedFindings().size();
        int ovrBefore = aggregated.getOverriddenFindings().size();

        ReviewReport report = vetoPolicy.apply(aggregated);
        List<Finding> finalFindings = report == null ? List.of() : report.getFindings();
        return new Outcome(List.copyOf(finalFindings), report, candidates.size(),
                supBefore, ovrBefore,
                report == null ? 0 : report.getSuppressedFindings().size(),
                report == null ? 0 : report.getOverriddenFindings().size());
    }

    /**
     * 机械校验「同输入同输出」：连续跑两次，比对**每一条发现的关键字段**。
     *
     * <p>比对的是 (ruleId, file, lineStart, lineEnd, severity, agentType, confidence) 的序列，
     * 不是"条数相等"——条数相等但内容换了，是这类基准最阴的失效方式。
     *
     * @return 两次不一致时返回不一致的说明；一致返回空列表
     */
    public List<String> verifyDeterminism(BenchCase c) {
        Outcome a = replay(c);
        Outcome b = replay(c);
        List<String> diffs = new ArrayList<>();
        List<Finding> fa = a.findings();
        List<Finding> fb = b.findings();
        if (fa.size() != fb.size()) {
            diffs.add("条数不同：" + fa.size() + " vs " + fb.size());
            return diffs;
        }
        for (int i = 0; i < fa.size(); i++) {
            Finding x = fa.get(i);
            Finding y = fb.get(i);
            if (!x.dedupKey().equals(y.dedupKey())
                    || x.severity() != y.severity()
                    || x.agentType() != y.agentType()
                    || !Objects.equals(x.suggestion(), y.suggestion())
                    || Math.abs(x.confidence() - y.confidence()) > 1e-9) {
                diffs.add("第 " + i + " 条不一致：" + x.dedupKey() + " vs " + y.dedupKey());
            }
        }
        return diffs;
    }

    /** 由 caseId 派生一个稳定的 prId（不能用随机数 / 时间戳，否则不可复现）。 */
    static long stablePrId(String caseId) {
        return Math.abs((long) caseId.hashCode());
    }

    private static final String BENCH_TEAM = "bench";

    /** 只读反馈存储：实现最小接口，避免把 PG / 文件带到基准里。 */
    private record BenchFeedbackStore(List<ReviewFeedback> feedback) implements FeedbackStore {
        @Override
        public void save(String teamId, ReviewFeedback f) {
            throw new UnsupportedOperationException("基准回放不允许写入反馈（会破坏可复现性）");
        }

        @Override
        public List<ReviewFeedback> list(String teamId) {
            return feedback == null ? List.of() : feedback;
        }
    }
}
