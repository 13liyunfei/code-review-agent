package com.codereview.agent.core.profile;

import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审查强度 Profile：STRICT / ADVISORY / SUGGEST 过滤语义。
 */
class ReviewProfileTest {

    private static Finding f(Severity s) {
        return new Finding(AgentType.SECURITY, "A.java", 1, 1, s, "security",
                "R-" + s, "title", "desc", "建议", 0.9, "RULE");
    }

    private static final List<Finding> ALL = List.of(
            f(Severity.BLOCKER), f(Severity.MAJOR), f(Severity.MINOR), f(Severity.INFO));

    @Test
    void strictKeepsMinorAndAbove() {
        List<Finding> r = ReviewProfile.STRICT.apply(ALL);
        assertEquals(3, r.size());
        assertTrue(r.stream().noneMatch(f -> f.severity() == Severity.INFO), "STRICT 应剔除 Info");
    }

    @Test
    void advisoryKeepsMajorAndAbove() {
        List<Finding> r = ReviewProfile.ADVISORY.apply(ALL);
        assertEquals(2, r.size());
        assertTrue(r.stream().allMatch(f -> f.severity() == Severity.BLOCKER || f.severity() == Severity.MAJOR),
                "ADVISORY 只保留 Blocker/Major");
    }

    @Test
    void suggestKeepsEverything() {
        assertEquals(4, ReviewProfile.SUGGEST.apply(ALL).size());
    }
}
