package com.codereview.kit.extension;

import com.codereview.kit.extension.spi.LlmInterceptor;
import com.codereview.kit.extension.spi.StageHook;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 扩展点 SPI 演示：使用方实现自定义扩展 → 注册 → 按 order 织入 → 生效。
 *
 * <p>证明 agent-kit 的扩展机制"开箱即用"：使用方无需改 kit 源码，
 * 只需实现 SPI 接口并注册，即可自定义叠加行为。
 */
class ExtensionSpiTest {

    /** 使用方自定义扩展 1：低 order（先执行），给提示词加审计前缀。 */
    static class AuditInterceptor implements LlmInterceptor {
        @Override public String name() { return "audit"; }
        @Override public int order() { return 10; }
        @Override public String before(String prompt) { return "[AUDIT] " + prompt; }
    }

    /** 使用方自定义扩展 2：高 order（后执行），给提示词加领域指令。 */
    static class DomainInterceptor implements LlmInterceptor {
        @Override public String name() { return "domain"; }
        @Override public int order() { return 50; }
        @Override public String before(String prompt) { return prompt + "\n[DOMAIN-RULES]"; }
    }

    @Test
    void 自定义LlmInterceptor按order织入提示词链() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(LlmInterceptor.class, new DomainInterceptor());
        registry.register(LlmInterceptor.class, new AuditInterceptor());

        List<LlmInterceptor> chain = registry.list(LlmInterceptor.class);
        assertEquals(2, chain.size());
        assertEquals("audit", chain.get(0).name());   // order 10 先执行
        assertEquals("domain", chain.get(1).name());  // order 50 后执行

        String prompt = "审查这段代码";
        for (LlmInterceptor ext : chain) {
            prompt = ext.before(prompt);
        }
        assertEquals("[AUDIT] 审查这段代码\n[DOMAIN-RULES]", prompt);
    }

    @Test
    void 自定义StageHook收到阶段回调() {
        ExtensionRegistry registry = new ExtensionRegistry();
        List<String> seen = new ArrayList<>();
        registry.register(StageHook.class, new StageHook() {
            @Override public String name() { return "tracer"; }
            @Override public int order() { return 0; }
            @Override public void onStage(String stage, Map<String, Object> ctx) {
                seen.add(stage + ":" + ctx.get("runId"));
            }
        });
        StageHook hook = registry.list(StageHook.class).get(0);
        hook.onStage("review.finished", Map.of("runId", "r-1"));
        assertTrue(seen.contains("review.finished:r-1"));
    }

    @Test
    void 同名注册覆盖且不炸() {
        ExtensionRegistry registry = new ExtensionRegistry();
        registry.register(LlmInterceptor.class, new AuditInterceptor());
        registry.register(LlmInterceptor.class, new AuditInterceptor()); // 同名覆盖
        assertEquals(1, registry.list(LlmInterceptor.class).size());
        assertEquals("audit", registry.list(LlmInterceptor.class).get(0).name());
    }
}
