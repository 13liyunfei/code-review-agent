package com.codereview.agent.core.admin.dto;

/**
 * 知识条目信息（前端展示用）。
 *
 * @param id          知识 ID
 * @param filename    文件名
 * @param source      来源名
 * @param category    关联审查维度
 * @param type        类型
 * @param indexed     是否已索引进 RAG（false 多见于视频/PDF 未附文字稿）
 * @param chunkCount  切分入库的段落数
 * @param sizeBytes   文件大小
 * @param createdAt   创建时间
 */
public record KnowledgeInfo(
        String id,
        String filename,
        String source,
        String category,
        String type,
        boolean indexed,
        int chunkCount,
        long sizeBytes,
        String createdAt) {
}
