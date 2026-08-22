package com.codereview.agent.core.llm;

/**
 * 向量化客户端抽象（用于 RAG 检索与语义注入检测）。
 *
 * <p>生产环境可替换为 OpenAI / 本地向量模型实现；本仓库提供离线可用的
 * {@code SimpleHashEmbeddingClient}，使整条链路无需外部依赖即可运行。
 */
public interface EmbeddingClient {

    /**
     * 将文本向量化。
     *
     * @param text 输入文本
     * @return 浮点向量
     */
    float[] embed(String text);
}
