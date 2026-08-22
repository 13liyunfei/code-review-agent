package com.codereview.agent.core.llm;

import java.util.Arrays;

/**
 * 离线可用的哈希向量化实现（词袋 + 哈希桶，零依赖）。
 *
 * <p>将文本按词元哈希到固定维度向量，做 L2 归一化，使语义相近的文本获得较高的
 * 余弦相似度。虽不及真实语义向量模型，但足以在离线环境下演示“语义检索 / 语义注入检测”
 * 的完整链路。生产环境请替换为真实 Embedding 模型。
 *
 * <p>注：非 Spring 组件，由 {@code ReviewAgentConfig.embeddingClient()} 按配置构造
 * （默认哈希嵌入，或切换为 LangChain4j 真实语义向量）。
 */
public class SimpleHashEmbeddingClient implements EmbeddingClient {

    /** 向量维度。 */
    private static final int DIM = 256;

    @Override
    public float[] embed(String text) {
        float[] vec = new float[DIM];
        if (text == null || text.isBlank()) {
            return vec;
        }
        // 简单分词：按非字母数字切分，转为小写
        String[] tokens = text.toLowerCase().split("[^a-z0-9\\u4e00-\\u9fa5]+");
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            int idx = Math.floorMod(token.hashCode(), DIM);
            vec[idx] += 1.0f;
        }
        // L2 归一化
        double norm = 0.0;
        for (float v : vec) {
            norm += v * v;
        }
        if (norm > 0) {
            double scale = 1.0 / Math.sqrt(norm);
            for (int i = 0; i < DIM; i++) {
                vec[i] = (float) (vec[i] * scale);
            }
        }
        return vec;
    }

    /**
     * 计算两个向量的余弦相似度。
     *
     * @param a 向量 a
     * @param b 向量 b
     * @return 余弦相似度（-1~1）
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
