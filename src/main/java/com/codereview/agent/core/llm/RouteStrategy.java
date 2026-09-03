package com.codereview.agent.core.llm;

/**
 * 路由策略：在多供应商列表中挑选「下一个值得尝试」的目标。
 *
 * <p><b>抽象动机（JD「持续优化路由分发策略」）</b>：原 ModelGateway 写死「按列表顺序遍历」，
 * 在「快速失败（熔断器 OPEN）」场景下应当跳过已知不健康的供应商，而非每次重新走一遍顺序。
 *
 * <p><b>协作关系</b>：
 * <ul>
 *   <li>{@link CircuitBreakerProvider#available()} 提供「该供应商是否值得下发」的瞬时判定；</li>
 *   <li>本接口返回的供应商仍会被 {@link ModelGateway} 二次校验
 *       （配额是否耗尽、自身可用），契约是「策略只做『该先试谁』的建议」。</li>
 * </ul>
 */
public interface RouteStrategy {

    /**
     * 在候选列表中选择下一个目标供应商。
     *
     * @param candidates 全部候选（已被网关过滤 available+未超配额）；
     *                   传入时已不含 OPEN 中的熔断供应商（路由层先做软过滤）。
     * @return 下一个目标；若无可用则返回 null（网关应抛 ModelUnavailableException）。
     */
    ModelProvider next(java.util.List<ModelProvider> candidates);
}