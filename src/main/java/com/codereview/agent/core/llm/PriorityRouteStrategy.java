package com.codereview.agent.core.llm;

import java.util.List;

/**
 * 默认路由策略：保持列表顺序，但跳过熔断器 OPEN 中的供应商。
 *
 * <p><b>行为契约</b>：
 * <ul>
 *   <li>遍历 candidates，按声明顺序选第一个「available」的供应商；</li>
 *   <li>若某个供应商包了 {@link CircuitBreakerProvider} 且处于 OPEN，自动跳过；</li>
 *   <li>全部不可用 → 返回 null（网关据此抛 {@link ModelUnavailableException}）。</li>
 * </ul>
 *
 * <p><b>与历史兼容</b>：在熔断器关闭时，行为与「按顺序 failover」完全一致——只是多了
 * 「快速跳过 OPEN 中供应商」的能力。
 */
public class PriorityRouteStrategy implements RouteStrategy {

    @Override
    public ModelProvider next(List<ModelProvider> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        for (ModelProvider p : candidates) {
            if (p == null) {
                continue;
            }
            if (p.available()) {
                return p;
            }
        }
        return null;
    }
}