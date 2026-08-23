package com.codereview.agent.core.rag;

import com.codereview.agent.core.memory.MemoryEntry;

import java.util.List;
import java.util.Map;

/**
 * RAG 知识库存储（与「长期经验 / 短期记忆」严格分层，不复用 MemoryStore 契约）。
 *
 * <p>设计动机（业界最佳实践 + 本项目演进）：原 {@code MemoryStore} 同时承载
 * RAG 知识（{@code agent_type=RAG}）与开发者经验反馈（{@code agent_type=EXPERIENCE}），
 * 共享同一张 {@code memory_store} 表与同一检索通道，仅靠元数据区分，边界模糊易导致
 * 互相干扰（如经验噪声污染知识检索、知识规模膨胀拖累经验召回）。
 *
 * <p>本接口<b>独立</b>于 {@link com.codereview.agent.core.memory.MemoryStore}：
 * <ul>
 *   <li>{@code saveKnowledge} / {@code searchKnowledge} 是 RAG 专属语义，不混入通用记忆读写；</li>
 *   <li>生产实现（{@code PgKnowledgeStore}）与记忆实现（{@code PgVectorMemoryStore}）
 *       <b>物理共享同一张 {@code memory_store} 表</b>（按 {@code agent_type} 区分读写视角），
 *       但<b>逻辑上互为独立接口</b>，各自只暴露自己该暴露的能力，符合接口隔离原则；</li>
 *   <li>经验类（{@code ExperienceStore} / {@code ReflectionAgent}）继续使用 {@code MemoryStore}，
 *       二者在调用方分层，互不串扰。</li>
 * </ul>
 *
 * <p>写入通道：本接口不定义通用 {@code save}，{@code saveKnowledge} 内部通过结构感知切分
 * （{@link StructuredChunker}）逐块入库，由具体实现委托其持有的写入器完成，
 * 避免把「知识库」误暴露为「记忆库」。</p>
 */
public interface KnowledgeStore {

    /**
     * 写入一份知识文档：内部按结构感知策略切分并逐块向量化入库。
     *
     * @param teamId   团队标识（含全局基线 __global__）
     * @param doc      原始文档文本
     * @param meta     基础元数据（source / type / kbId 等）
     * @return 切分出的 chunk 数
     */
    int saveKnowledge(String teamId, String doc, Map<String, String> meta);

    /**
     * RAG 语义检索（限定 agent_type=RAG，含全局基线叠加）。
     *
     * @param query         查询
     * @param topK          返回条数
     * @param teamId        团队
     * @param includeGlobal 是否纳入全局基线
     * @return 命中条目（携带 similarity 元数据）
     */
    List<MemoryEntry> searchKnowledge(String query, int topK, String teamId, boolean includeGlobal);

    /**
     * 按团队 + 元数据键值删除知识条目（团队隔离版本）。
     *
     * <p>重新摄取某份知识（同一 {@code kbId}）前，用它清理已入库的旧向量，避免脏数据
     * 继续参与检索。与 {@code MemoryStore} 同名方法语义一致但接口独立，二者不共享契约。</p>
     *
     * @param teamId 团队标识（仅删除该团队下的匹配条目）
     * @param key    元数据键（如 kbId）
     * @param value  元数据值
     */
    void deleteByMetadata(String teamId, String key, String value);
}
