package com.codereview.agent.core.extension;

/**
 * 扩展点标记接口（积木架构的「可插拔组件」契约）。
 *
 * <p>所有可被项目自定义叠加/替换的横切组件实现此接口，经 {@link ExtensionRegistry}
 * 注册后按 {@code order()} 升序织入（order 越小越先执行；标准实现用较大 order，
 * 项目自定义用较小 order 即可「标准之上叠加」）。
 *
 * <p>扩展点接口清单（平台约定的可插拔边界，随版本演进）：
 * <ul>
 *   <li>LlmInterceptor —— LLM 调用前置/后置（防注入 / rerank / 限流 / 审计）</li>
 *   <li>RagEnhancer —— 检索结果增强（项目知识库 / 重排 / 去重）</li>
 *   <li>AgentProvider —— 项目自定义审查 Agent</li>
 *   <li>MemoryStrategy —— 记忆读写策略</li>
 *   <li>StageHook —— 工作流阶段钩子（追踪 / 轨迹 / 降级）</li>
 * </ul>
 */
public interface ExtensionPoint {

    /** 扩展名（唯一，用于日志与治理）。 */
    String name();

    /** 织入顺序：越小越先执行。标准实现建议 100，项目自定义建议 0-50。 */
    int order();
}
