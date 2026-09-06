package com.codereview.agent.core.admin;

/**
 * 团队知识元数据（入库登记，含原文——替代改造前的 {@code knowledge/*.meta.json + .txt} 落盘）。
 */
public record KnowledgeMeta(
        String id,
        String teamId,
        String filename,
        String source,
        String category,
        String type,
        boolean indexed,
        int chunkCount,
        long sizeBytes,
        String content,
        String createdAt) {
}
