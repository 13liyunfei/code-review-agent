package com.codereview.agent.core.eval;

import com.codereview.agent.core.memory.ReviewFeedback;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性回放测试：证明「同一份输入，跑两次得到逐条一致的结果」，并验证链上两段关键逻辑
 * （去重、BLOCKER 否决回收）确实生效——否则基准的分数变化无法归因。
 */
class ReviewReplayTest {

    private static Finding f(AgentType ag, String file, int line, String ruleId, Severity sev) {
        return new Finding(ag, file, line, line, sev, "logic", ruleId, "t", "d", "s", 0.8, "LLM");
    }

    private static BenchCase caseOf(List<Finding> findings, List<ReviewFeedback> fb) {
        return new BenchCase("test-case", "code-review-agent", "git-history", "abc1234",
                "subject", 1, 1, 0, "sha",
                List.of(new BenchGroundTruth("R-1", "logic", Severity.MAJOR,
                        "A.java", 10, 10, "n", "manual")),
                new BenchCase.ReplayInput("test", "note", findings, fb));
    }

    @Test
    void replayIsDeterministicAcrossRuns() {
        BenchCase c = caseOf(List.of(
                f(AgentType.LOGIC, "A.java", 10, "R-1", Severity.MAJOR),
                f(AgentType.SECURITY, "A.java", 10, "R-1", Severity.MAJOR),
                f(AgentType.STYLE, "A.java", 900, "STYLE-90", Severity.MINOR)), List.of());
        ReviewReplay replay = new ReviewReplay();
        assertTrue(replay.verifyDeterminism(c).isEmpty(),
                "同一输入两次回放必须逐条一致：" + replay.verifyDeterminism(c));
    }

    @Test
    void duplicateFromAnotherAgentIsDedupedToOne() {
        // 两条同 (文件@行#规则) 的发现：去重后只应剩一条（保留更严重/更高置信的那条）
        BenchCase c = caseOf(List.of(
                f(AgentType.LOGIC, "A.java", 10, "R-1", Severity.MAJOR),
                f(AgentType.SECURITY, "A.java", 10, "R-1", Severity.MAJOR)), List.of());
        ReviewReplay.Outcome oc = new ReviewReplay().replay(c);
        assertEquals(1, oc.findings().size(), "同 dedupKey 应被去重成一条");
        assertEquals(2, oc.candidateCount());
    }

    @Test
    void blockerMarkedAsFalsePositiveIsRescuedByVeto() {
        // 反馈把这条 BLOCKER 标成误报 ⇒ 聚合阶段会抑制它 ⇒ VetoPolicy 必须把它捞回来。
        // 这是本项目「降噪不得覆盖底线」的硬规则，基准必须能感知它的存亡。
        Finding blocker = f(AgentType.SECURITY, "A.java", 10, "SEC-001", Severity.BLOCKER);
        ReviewFeedback fb = new ReviewFeedback("SEC-001", null, true, "标成误报", "A.java");
        BenchCase c = caseOf(List.of(blocker), List.of(fb));
        ReviewReplay.Outcome oc = new ReviewReplay().replay(c);
        assertEquals(1, oc.suppressedBeforeVeto(), "聚合阶段先把它抑制掉");
        assertEquals(0, oc.suppressedAfterVeto(), "否决之后抑制列表应已空（被捞回）");
        assertEquals(1, oc.findings().size(), "被 VetoPolicy 捞回来了");
        assertEquals("SEC-001", oc.findings().get(0).ruleId());
    }

    @Test
    void nonBlockerFalsePositiveStaysSuppressed() {
        Finding minor = f(AgentType.STYLE, "A.java", 900, "STYLE-90", Severity.MINOR);
        ReviewFeedback fb = new ReviewFeedback("STYLE-90", null, true, "标成误报", "A.java");
        BenchCase c = caseOf(List.of(minor), List.of(fb));
        ReviewReplay.Outcome oc = new ReviewReplay().replay(c);
        assertTrue(oc.findings().isEmpty(), "非 BLOCKER 的误报应保持被抑制");
        assertEquals(1, oc.suppressedBeforeVeto());
        assertEquals(1, oc.suppressedAfterVeto());
    }

    @Test
    void emptyInputYieldsEmptyOutcomeWithoutExploding() {
        ReviewReplay.Outcome oc = new ReviewReplay().replay(caseOf(List.of(), List.of()));
        assertTrue(oc.findings().isEmpty());
        assertEquals(0, oc.candidateCount());
    }

    @Test
    void stablePrIdIsStableAndNonNegative() {
        assertEquals(ReviewReplay.stablePrId("cra-7410a93"), ReviewReplay.stablePrId("cra-7410a93"));
        assertTrue(ReviewReplay.stablePrId("cra-7410a93") >= 0);
    }
}
