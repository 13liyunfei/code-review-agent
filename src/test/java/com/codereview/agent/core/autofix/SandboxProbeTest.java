package com.codereview.agent.core.autofix;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 沙箱探测器：探测结果必须合法（fail-closed 语义：探测失败视为不可用）。
 */
class SandboxProbeTest {

    @Test
    void detectAlwaysReturnsValidStatus() {
        SandboxProbe.Status s = new SandboxProbe().detect();
        assertNotNull(s);
        assertNotNull(s.detail());
        // 可用 → 必须列出发现的 runner；不可用 → detail 说明原因
        if (s.available()) {
            assertFalse(s.foundRunners().isEmpty(), "可用时必须报告命中的 runner");
        } else {
            assertTrue(s.detail().contains("未探测"), "不可用时应说明原因");
        }
    }
}
