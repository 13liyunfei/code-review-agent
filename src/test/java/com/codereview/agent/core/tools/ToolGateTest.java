package com.codereview.agent.core.tools;

import com.codereview.agent.core.profile.ReviewProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具分级门控：DEFERRED 默认拒绝（fail-closed），STRICT / 配置放行。
 */
class ToolGateTest {

    @Test
    void directAlwaysAllowed() {
        ToolGate gate = new ToolGate(false);
        assertTrue(gate.allows("read-file", ToolExposure.DIRECT));
    }

    @Test
    void deferredRejectedByDefault() {
        ToolGate gate = new ToolGate(false);
        assertFalse(gate.allows("autofix.apply", ToolExposure.DEFERRED),
                "DEFERRED 重工具默认必须拒绝（fail-closed）");
        assertEquals(1L, gate.stats("autofix.apply")[1], "拒绝次数应统计");
    }

    @Test
    void deferredAllowedWhenEnabledOrStrict() {
        assertTrue(new ToolGate(true).allows("build.verify", ToolExposure.DEFERRED),
                "显式开启 deferred-enabled 应放行");
        assertTrue(new ToolGate(false).allows("build.verify", ToolExposure.DEFERRED, ReviewProfile.STRICT),
                "STRICT 审查强度应放行 DEFERRED 工具");
    }

    @Test
    void codeModeOnlyWhenEnabled() {
        ToolGate gate = new ToolGate(false, true);
        assertTrue(gate.allows("code.apply", ToolExposure.CODE_MODE), "开启 code-mode 应放行");
        assertFalse(new ToolGate(false, false).allows("code.apply", ToolExposure.CODE_MODE));
    }
}
