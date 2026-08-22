package com.codereview.agent.core.memory;

import java.time.Instant;
import java.util.Map;

/**
 * 记忆条目（RAG 知识库与长期经验的统一载体）。
 *
 * @param id        条目 ID（入库后由存储分配，新建时为 null）
 * @param agentType 关联 Agent 类型（或 "RAG" 表示通用知识）
 * @param teamId    所属团队 / 租户标识（实现记忆按团队隔离；{@code __global__} 为跨团队共享基线）
 * @param content   文本内容（代码片段 / 规范条款 / 经验描述）
 * @param metadata  元数据（如 source、type、kbId）
 * @param level     记忆层级
 * @param createdAt 创建时间
 * @param embedding 向量（可空，存储时按需计算）
 */
public record MemoryEntry(
        Long id,
        String agentType,
        String teamId,
        String content,
        Map<String, String> metadata,
        MemoryLevel level,
        Instant createdAt,
        float[] embedding) {
}
