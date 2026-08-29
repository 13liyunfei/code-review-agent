package com.codereview.kit.router;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelRouterTest {

    @Test
    void 按优先级路由主模型() {
        ModelRouter router = new ModelRouter();
        router.register("backup", p -> "backup", 1);
        router.register("primary", p -> "primary:" + p, 100);
        RoutingChatModel model = new RoutingChatModel(router);
        assertEquals("primary:hi", model.chat("hi"));
        assertEquals(1, router.primary().calls().get());
    }

    @Test
    void 主模型失败自动failover到备模型() {
        ModelRouter router = new ModelRouter();
        router.register("backup", p -> "backup-ok", 1);
        router.register("broken", p -> {
            throw new RuntimeException("down");
        }, 100);
        RoutingChatModel model = new RoutingChatModel(router);
        assertEquals("backup-ok", model.chat("x"));
        assertEquals(1, router.primary().failures().get());
        assertEquals(1, router.entries().get(1).calls().get()); // backup 被调用
    }

    @Test
    void 全部失败抛异常() {
        ModelRouter router = new ModelRouter();
        router.register("a", p -> {
            throw new RuntimeException("a down");
        }, 10);
        RoutingChatModel model = new RoutingChatModel(router);
        assertThrows(IllegalStateException.class, () -> model.chat("x"));
    }
}
