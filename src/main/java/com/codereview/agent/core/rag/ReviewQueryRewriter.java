package com.codereview.agent.core.rag;

/**
 * 检索查询改写器（业界 RAG 关键组件：query rewrite / query expansion）。
 *
 * <p>审查场景存在「代码 patch 形态」与「规范文档形态」的语义鸿沟——原始查询是从 diff
 * 截取的补丁文本（充满代码符号、行号、上下文噪音），而知识库内容以自然语言规范 / 术语
 * 为主。改写器负责把代码形态的查询转换为更贴合知识库语义的检索查询，提升召回命中率。
 *
 * <p>实现策略：
 * <ul>
 *   <li>{@link IdentityQueryRewriter}：原样返回（默认 / 离线降级，零依赖）；</li>
 *   <li>{@link LlmQueryRewriter}：大模型改写（需配置 {@code review.rag.query-rewrite.enabled=true}），
 *       提炼类名 / 方法名 / 异常类型 / 技术主题等关键词；失败或产出为空时自动降级原查询；</li>
 * </ul>
 *
 * <p>可靠性契约：<b>任何实现都不得让查询变得更差</b>——改写失败 / 超时 / 输出非法时必须
 * 返回原始查询（fail-safe），由调用方继续走混合检索。本接口为纯逻辑、无 Spring 依赖。
 */
public interface ReviewQueryRewriter {

    /**
     * 改写检索查询。
     *
     * @param rawQuery 原始查询（通常为 diff 提取的补丁文本）
     * @return 改写后的查询；改写不可用 / 失败时必须原样返回 {@code rawQuery}
     */
    String rewrite(String rawQuery);

    /**
     * 组件名（供日志可观测）。
     */
    default String name() {
        return getClass().getSimpleName();
    }
}
