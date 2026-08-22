package com.codereview.agent.core.autofix;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动修复安全策略：fail-closed 不变量验证。
 */
class AutoFixSafetyPolicyTest {

    @Test
    void suggestAlwaysSafe() {
        AutoFixSafetyPolicy.Verdict v = AutoFixSafetyPolicy.evaluate(AutoFixMode.SUGGEST, false);
        assertTrue(v.allowed(), "SUGGEST 模式仅生成建议，应始终安全");
    }

    @Test
    void applyAllowedWhenSandboxAvailable() {
        AutoFixSafetyPolicy.Verdict v = AutoFixSafetyPolicy.evaluate(AutoFixMode.APPLY, true);
        assertTrue(v.allowed(), "APPLY 且沙箱可用时应允许");
    }

    @Test
    void applyDeniedWhenSandboxUnavailable() {
        // fail-closed：沙箱不可用时，APPLY 必须被拒绝，绝不静默放行
        AutoFixSafetyPolicy.Verdict v = AutoFixSafetyPolicy.evaluate(AutoFixMode.APPLY, false);
        assertFalse(v.allowed(), "fail-closed：沙箱不可用时 APPLY 应被拒绝");
        assertTrue(v.reason().contains("fail-closed"),
                "拒绝原因应体现 fail-closed 语义");
    }

    @Test
    void isApplyAllowedConvenience() {
        assertTrue(AutoFixSafetyPolicy.isApplyAllowed(AutoFixMode.SUGGEST, false));
        assertFalse(AutoFixSafetyPolicy.isApplyAllowed(AutoFixMode.APPLY, false));
        assertTrue(AutoFixSafetyPolicy.isApplyAllowed(AutoFixMode.APPLY, true));
    }
}
