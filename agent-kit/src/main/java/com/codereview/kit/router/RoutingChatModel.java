package com.codereview.kit.router;

import com.codereview.kit.ChatModel;

import java.util.concurrent.Flow;

/**
 * 路由 ChatModel：实现 {@link ChatModel}，内部按优先级路由 + 调用失败自动 failover。
 *
 * <p>用法：注册主/备模型后，把本对象当作普通 ChatModel 使用即可。
 */
public class RoutingChatModel implements ChatModel {

    private final ModelRouter router;

    public RoutingChatModel(ModelRouter router) {
        this.router = router;
    }

    @Override
    public String chat(String prompt) {
        Exception last = null;
        for (ModelRouter.ModelEntry entry : router.entries()) {
            try {
                String resp = entry.model().chat(prompt);
                entry.calls().incrementAndGet();
                return resp;
            } catch (Exception e) {
                entry.failures().incrementAndGet();
                last = e;
            }
        }
        throw new IllegalStateException("所有模型均调用失败", last);
    }

    @Override
    public Flow.Publisher<String> stream(String prompt) {
        return router.primary().model().stream(prompt);
    }
}
