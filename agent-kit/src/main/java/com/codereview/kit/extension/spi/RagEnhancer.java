package com.codereview.kit.extension.spi;

import com.codereview.kit.extension.ExtensionPoint;

import java.util.List;

/**
 * 检索结果增强器（RAG），kit 扩展点之一。
 *
 * <p>使用方实现后，可对检索命中的片段做重排 / 去重 / 注入项目知识库。
 * 泛型 {@code <T>} 对应使用方自己的检索命中类型。
 */
public interface RagEnhancer<T> extends ExtensionPoint {

    /** 增强检索结果，返回最终参与生成的命中列表。 */
    List<T> enhance(List<T> hits, String query);
}
