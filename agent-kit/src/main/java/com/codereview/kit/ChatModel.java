package com.codereview.kit;

import java.util.concurrent.Flow;

/**
 * LLM 会话抽象（kit 唯一的模型边界）。
 *
 * <p>刻意保持与经典 LLM 客户端一致的最小形态：单方法 {@code chat} + 可选流式 {@code stream}。
 * 任何项目只需一行适配器即可把自研网关 / OpenAI SDK / 内部 MaaS 接入 kit。
 * 与具体供应商解耦，也便于测试（脚本化 fake）。
 */
public interface ChatModel {

    /**
     * 向大模型发送完整提示词并返回文本回复。
     *
     * @param prompt 完整提示词（含系统指令与用户输入）
     * @return 模型文本输出
     */
    String chat(String prompt);

    /**
     * 流式对话：按块推送回复文本（零依赖的 JDK Flow.Publisher）。
     *
     * <p>默认实现把 {@link #chat(String)} 结果一次性推给订阅者；
     * 接入真实流式供应商时覆盖此方法（如 SSE / websocket 逐块推送）。
     *
     * @param prompt 完整提示词
     * @return 文本块发布者（onNext 逐块 / onComplete 结束 / onError 异常）
     */
    default Flow.Publisher<String> stream(String prompt) {
        return subscriber -> {
            subscriber.onSubscribe(new Flow.Subscription() {
                private boolean done;

                @Override public void request(long n) {
                    if (done) {
                        return;
                    }
                    done = true;
                    try {
                        subscriber.onNext(chat(prompt));
                        subscriber.onComplete();
                    } catch (Throwable t) {
                        subscriber.onError(t);
                    }
                }

                @Override public void cancel() {
                    done = true;
                }
            });
        };
    }
}
