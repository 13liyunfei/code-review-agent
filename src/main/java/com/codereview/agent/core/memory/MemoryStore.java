package com.codereview.agent.core.memory;

import java.util.List;

/**
 * 记忆存储抽象（RAG 向量库 / 长期记忆的统一接口）。
 *
 * <p>生产环境可替换为 PostgreSQL + pgvector 等实现；本仓库提供
 * {@link InMemoryVectorStore} 离线实现。
 *
 * <p>所有条目均按 {@code teamId} 隔离；{@code __global__} 团队承载跨团队共享基线
 * （如编码规范手册）。检索时通过 {@code includeGlobal} 控制是否在团队自身内容之外
 * 额外纳入全局基线（RAG 知识检索通常为 true，团队经验检索通常为 false）。
 */
public interface MemoryStore {

    /**
     * 保存一条记忆（若 embedding 为空则自动计算）。{@code teamId} 取自 {@link MemoryEntry#teamId()}。
     *
     * @param entry 记忆条目（id 为空时由实现分配）
     * @return 保存后的条目（含 id）
     */
    MemoryEntry save(MemoryEntry entry);

    /**
     * 向量语义检索。
     *
     * @param query         查询文本
     * @param agentType     限定 Agent 类型（传 null 表示不限）
     * @param topK          返回条数
     * @param teamId        团队标识（仅返回该团队 + 视 {@code includeGlobal} 决定的全局基线）
     * @param includeGlobal 是否额外纳入全局基线团队（{@code __global__}）的内容
     * @return 按相似度降序的记忆条目
     */
    List<MemoryEntry> search(String query, String agentType, int topK, String teamId, boolean includeGlobal);

    /**
     * 按元数据键值删除条目（默认空实现；内存库会真正删除，PGVector 可覆盖）。
     *
     * <p>团队删除某份知识时，用它清理已入库的向量，避免脏数据继续参与检索。
     *
     * @param key   元数据键（如 kbId）
     * @param value 元数据值
     */
    default void deleteByMetadata(String key, String value) {
    }

    /**
     * 按团队 + 元数据键值删除条目（团队隔离版本）。
     *
     * @param teamId 团队标识（仅删除该团队下的匹配条目）
     * @param key    元数据键（如 kbId）
     * @param value  元数据值
     */
    void deleteByMetadata(String teamId, String key, String value);
}
