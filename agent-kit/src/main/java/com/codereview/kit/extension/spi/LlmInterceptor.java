package com.codereview.kit.extension.spi;

import com.codereview.kit.extension.ExtensionPoint;

/**
 * LLM 调用拦截器（前置/后置），kit 扩展点之一。
 *
 * <p>使用方实现并注册后，可对每次 {@code ChatModel.chat} 的提示词做改造
 * （防注入 / RAG 增强 / 限流 / 审计），对响应做后处理（脱敏 / 纠偏）。
 */
public interface LlmInterceptor extends ExtensionPoint {

    /** 前置拦截：改造提示词后返回（原样返回表示不改）。 */
    default String before(String prompt) { return prompt; }

    /** 后置拦截：改造模型响应后返回（原样返回表示不改）。 */
    default String after(String prompt, String response) { return response; }
}
