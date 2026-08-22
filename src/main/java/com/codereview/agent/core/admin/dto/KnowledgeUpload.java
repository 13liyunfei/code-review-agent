package com.codereview.agent.core.admin.dto;

/**
 * 知识入库请求（内部传输对象）。
 *
 * @param source        来源名（展示用，默认文件名）
 * @param category      关联审查维度（可选）
 * @param type          类型 document / manual / video
 * @param text          文本正文（提取得到或可粘贴）
 * @param originalFilename 原始文件名
 * @param sizeBytes     文件大小
 * @param storedPath    原始文件已落盘的临时路径（可空，表示仅文本）
 */
public record KnowledgeUpload(
        String source,
        String category,
        String type,
        String text,
        String originalFilename,
        long sizeBytes,
        String storedPath) {
}
