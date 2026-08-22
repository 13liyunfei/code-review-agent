package com.codereview.agent.core.llm;

import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 LangChain4j {@link EmbeddingModel} 的真实向量化客户端。
 *
 * <p>替代原 {@code SimpleHashEmbeddingClient}（哈希词袋）为真实语义向量模型
 * （{@code OpenAiEmbeddingModel}，OpenAI 兼容协议，可对接 TokenHub / OpenAI / Azure）。
 * 保持 {@link EmbeddingClient} 接口不变，向量存储 / 语义注入检测等调用方零改动。
 *
 * <p>空文本不调用远端模型：返回与已知维度一致的零向量（首次调用后缓存维度）。
 */
public class LangChain4jEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jEmbeddingClient.class);

    private final EmbeddingModel embeddingModel;

    /** 最近一次成功调用返回的向量维度（用于空文本零向量兜底）。 */
    private volatile int lastDim = 0;

    public LangChain4jEmbeddingClient(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return lastDim > 0 ? new float[lastDim] : new float[0];
        }
        long t0 = System.currentTimeMillis();
        float[] vector = embeddingModel.embed(text).content().vector();
        if (vector != null && vector.length > 0) {
            lastDim = vector.length;
        }
        log.debug("[向量化] 文本={}字符 维度={} 耗时 {}ms", text.length(),
                vector == null ? 0 : vector.length, System.currentTimeMillis() - t0);
        return vector;
    }
}
