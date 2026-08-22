package com.codereview.agent.core.tools.external;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部工具 SPI：注册 / 按名路由 / 调用 / 未注册 fail-fast。
 */
class ExternalToolRegistryTest {

    private static final ExternalToolProvider FAKE = new ExternalToolProvider() {
        @Override
        public String name() {
            return "fake-vendor";
        }

        @Override
        public String description() {
            return "测试供应商";
        }

        @Override
        public Set<String> capabilities() {
            return Set.of("scan", "license-check");
        }

        @Override
        public String invoke(String tool, Map<String, Object> args) {
            return "result:" + tool + ":" + args.getOrDefault("k", "?");
        }
    };

    @Test
    void registerFindAndInvoke() {
        ExternalToolRegistry registry = new ExternalToolRegistry();
        registry.register(FAKE);

        assertTrue(registry.find("scan").isPresent(), "注册后应按工具名找到提供者");
        assertEquals(1, registry.providers().size());
        assertEquals("result:scan:v1", registry.invoke("scan", Map.of("k", "v1")));
    }

    @Test
    void invokeUnregisteredFailsFast() {
        ExternalToolRegistry registry = new ExternalToolRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.invoke("nope", Map.of()),
                "未注册工具应 fail-fast");
    }

    @Test
    void unregisterRemovesRouting() {
        ExternalToolRegistry registry = new ExternalToolRegistry();
        registry.register(FAKE);
        registry.unregister("fake-vendor");
        assertFalse(registry.find("scan").isPresent(), "注销后工具应不可路由");
    }
}
