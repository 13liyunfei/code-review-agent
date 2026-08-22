package com.codereview.agent.core.permission;

import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限收敛：BLOCKER 级强否决不可被误报抑制 / 仲裁覆盖（父不覆盖子）。
 */
class VetoPolicyTest {

    private static Finding f(Severity s, String rule) {
        return new Finding(AgentType.SECURITY, "A.java", 1, 1, s, "security",
                rule, "title", "desc", "建议", 0.9, "RULE");
    }

    @Test
    void rescuesBlockerFromSuppressed() {
        Finding blocker = f(Severity.BLOCKER, "SEC-001");
        Finding minor = f(Severity.MINOR, "STYLE-001");
        ReviewReport report = new ReviewReport(1, "demo", List.of(minor),
                Map.of(Severity.MINOR, 1L), "run1", 10, List.of(),
                List.of(), List.of(blocker), null);

        ReviewReport r = new VetoPolicy().apply(report);

        // BLOCKER 被回收进最终发现
        assertTrue(r.getFindings().stream().anyMatch(x -> x.ruleId().equals("SEC-001")),
                "被抑制的 BLOCKER 应被回收");
        assertTrue(r.getSuppressedFindings().isEmpty(), "抑制列表应移除被回收的 BLOCKER");
        assertEquals(2, r.getFindings().size());
    }

    @Test
    void rescuesBlockerFromOverridden() {
        Finding blocker = f(Severity.BLOCKER, "SEC-002");
        ReviewReport report = new ReviewReport(1, "demo", List.of(),
                Map.of(), "run1", 10, List.of("仲裁说明"),
                List.of(blocker), List.of(), null);

        ReviewReport r = new VetoPolicy().apply(report);

        assertTrue(r.getFindings().stream().anyMatch(x -> x.ruleId().equals("SEC-002")),
                "被仲裁覆盖的 BLOCKER 应被回收");
        assertTrue(r.getOverriddenFindings().isEmpty(), "覆盖列表应移除被回收的 BLOCKER");
    }

    @Test
    void leavesNonBlockerUntouched() {
        Finding major = f(Severity.MAJOR, "LOGIC-001");
        ReviewReport report = new ReviewReport(1, "demo", List.of(),
                Map.of(), "run1", 10, List.of(),
                List.of(), List.of(major), null);

        ReviewReport r = new VetoPolicy().apply(report);

        assertTrue(r.getSuppressedFindings().size() == 1, "非 BLOCKER 抑制不应被回收");
        assertTrue(r.getFindings().isEmpty());
    }
}
