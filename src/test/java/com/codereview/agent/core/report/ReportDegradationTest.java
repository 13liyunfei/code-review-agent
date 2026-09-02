package com.codereview.agent.core.report;

import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 降级可观测性验证：Agent 超时/异常（degraded 结果）与基础设施降级
 * 必须如实进入 {@link ReviewReport}，而不是让「0 条发现」被误读为「代码没问题」。
 */
class ReportDegradationTest {

    private static Finding finding(AgentType type, String ruleId) {
        return new Finding(type, "T.java", 2, 2, Severity.MAJOR, "security",
                ruleId, "title", "desc", "建议", 0.9, "RULE");
    }

    @Test
    void degradedAgentResultFlowsIntoReportAsDegradation() {
        AgentResult degraded = AgentResult.degraded(1, AgentType.PERFORMANCE, "执行超时（300000ms）");
        AgentResult healthy = new AgentResult(1, AgentType.SECURITY, List.of(finding(AgentType.SECURITY, "SEC-1")));

        ReviewReport report = new ReportGenerator().aggregate(
                1, "demo", List.of(degraded, healthy), null, "run-1", 12L, "default");

        assertTrue(report.degraded(), "存在降级 Agent 时报告应标记 degraded");
        assertEquals(1, report.getDegradations().size());
        AgentDegradation d = report.getDegradations().get(0);
        assertEquals(AgentType.PERFORMANCE.name(), d.stage());
        assertTrue(d.reason().contains("超时"));
        // 健康 Agent 的发现不受影响
        assertEquals(1, report.getFindings().size());
    }

    @Test
    void infraDegradationsMergeWithAgentDegradationsAndDedupeByStage() {
        AgentResult degradedAgent = AgentResult.degraded(1, AgentType.LOGIC, "LLM 供应商全部失败");
        List<AgentDegradation> infra = List.of(
                new AgentDegradation(AgentDegradation.STAGE_ADVANCED_ANALYSIS, "高级静态分析超时（300000ms）"),
                // 与 Agent 级同阶段重复：putIfAbsent 应保留先到的 Agent 级记录
                new AgentDegradation(AgentType.LOGIC.name(), "重复记录"));

        ReviewReport report = new ReportGenerator().aggregate(
                1, "demo", List.of(degradedAgent), null, "run-2", 12L, "default", infra);

        assertEquals(2, report.getDegradations().size(), "LOGIC 与 advanced-analysis 两个阶段");
        assertEquals(AgentType.LOGIC.name(), report.getDegradations().get(0).stage());
        assertEquals(AgentDegradation.STAGE_ADVANCED_ANALYSIS, report.getDegradations().get(1).stage());
        assertTrue(report.getDegradations().get(0).reason().contains("LLM 供应商全部失败"),
                "同阶段去重应保留先到达的 Agent 级原因");
    }

    @Test
    void markdownRendersDegradationBlockBeforeFindings() {
        ReviewReport report = new ReportGenerator().aggregate(
                1, "demo",
                List.of(AgentResult.degraded(1, AgentType.PERFORMANCE, "执行超时（300000ms）"),
                        new AgentResult(1, AgentType.SECURITY, List.of(finding(AgentType.SECURITY, "SEC-1")))),
                null, "run-3", 12L, "default");

        String md = report.toMarkdown();
        assertTrue(md.contains("⚠️"), "Markdown 应渲染降级告警图标");
        assertTrue(md.contains("执行超时（300000ms）"), "降级原因应出现在报告中");
        // 降级告警应出现在发现明细之前（读者先知道哪些维度没看成）
        assertTrue(md.indexOf("⚠️") < md.indexOf("SEC-1"), "告警块应位于发现区块之前");
    }

    @Test
    void healthyReportHasNoDegradationBlock() {
        ReviewReport report = new ReportGenerator().aggregate(
                1, "demo", List.of(new AgentResult(1, AgentType.SECURITY,
                        List.of(finding(AgentType.SECURITY, "SEC-1")))), null, "run-4", 12L, "default");

        assertFalse(report.degraded());
        assertTrue(report.getDegradations().isEmpty());
        assertFalse(report.toMarkdown().contains("⚠️"));
    }

    @Test
    void nullAndDegradedMixedResultsAreSafe() {
        // List.of 不允许 null，这里用 Arrays.asList 模拟调用方传入 null 结果
        ReviewReport report = new ReportGenerator().aggregate(
                1, "demo",
                java.util.Arrays.asList(null, AgentResult.degraded(1, AgentType.CUSTOM, "自定义 Agent 不可用")),
                null, "run-5", 12L, "default", null);

        assertTrue(report.degraded());
        assertEquals(1, report.getDegradations().size());
        assertEquals(AgentType.CUSTOM.name(), report.getDegradations().get(0).stage());
        assertTrue(report.getFindings().isEmpty());
    }
}
