package com.codereview.kit.extension.spi;

import com.codereview.kit.extension.ExtensionPoint;

import java.util.List;

/**
 * 领域 Agent 提供者，kit 扩展点之一。
 *
 * <p>使用方实现后返回自己的 Agent 实例列表（如审查域的 Logic/Security/自定义 Agent），
 * 由编排器按注册顺序叠加进多 Agent 流水线。
 * 泛型 {@code <A>} 对应使用方自己的 Agent 类型。
 */
public interface AgentProvider<A> extends ExtensionPoint {

    /** 提供领域 Agent 实例（可为空列表）。 */
    List<A> provide();
}
