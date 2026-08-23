package com.codereview.agent.core.admin;

import com.codereview.agent.core.admin.dto.KnowledgeInfo;
import com.codereview.agent.core.admin.dto.KnowledgeUpload;
import com.codereview.agent.core.rag.KnowledgeStore;
import com.codereview.agent.tenant.Teams;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 团队知识入库服务（RAG 数据源的动态写入入口）。
 *
 * <p>把上传的规范文档 / 操作手册 / 视频文字稿切分、向量化并写入 {@link MemoryStore}，
 * 使后续 PR 审查前能检索到【团队规范】；原始文件与元数据落盘到
 * {@code <data-dir>/<teamId>/knowledge/}，删除时同步清理向量。所有内容按团队隔离。
 */
@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final KnowledgeStore knowledgeStore;
    private final Path dataDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public KnowledgeIngestionService(KnowledgeStore knowledgeStore,
                                    @Value("${review.data-dir:./data}") String dataDir) {
        this.knowledgeStore = knowledgeStore;
        this.dataDir = Path.of(dataDir);
    }

    private Path knowledgeDir(String teamId) {
        return dataDir.resolve(Teams.sanitize(teamId)).resolve("knowledge");
    }

    /**
     * 入库某团队的一份知识：索引文本到 RAG + 落盘原始文件/元数据。
     */
    public KnowledgeInfo ingest(String teamId, KnowledgeUpload upload) throws Exception {
        Path dir = knowledgeDir(teamId);
        Files.createDirectories(dir);
        String t = Teams.sanitize(teamId);
        String id = "kb-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 4);
        boolean indexed = upload.text() != null && !upload.text().isBlank();
        int chunkCount = 0;

        if (indexed) {
            String source = upload.source() != null ? upload.source()
                    : (upload.originalFilename() != null ? upload.originalFilename() : "knowledge");
            String type = upload.type() != null ? upload.type() : "document";
            // 结构感知切块 + 富元数据：交由 KnowledgeStore.saveKnowledge 完成（层级 + 重叠 + section）
            Map<String, String> meta = new java.util.LinkedHashMap<>();
            meta.put("source", source);
            meta.put("kbId", id);
            meta.put("type", type);
            meta.put("teamId", t);
            chunkCount = knowledgeStore.saveKnowledge(t, upload.text(), meta);
            log.info("[KB] 团队 {} 已结构化索引 {} 段到 RAG 知识库（kbId={}）", t, chunkCount, id);
        }

        // 落盘原始文件（优先用已保存的临时路径）或纯文本
        String storedPath = null;
        if (upload.storedPath() != null) {
            Path target = dir.resolve(id + "__" + sanitize(
                    upload.originalFilename() != null ? upload.originalFilename() : "file"));
            Files.copy(Path.of(upload.storedPath()), target, StandardCopyOption.REPLACE_EXISTING);
            storedPath = target.toString();
        } else if (upload.text() != null && upload.originalFilename() == null) {
            Path target = dir.resolve(id + ".txt");
            Files.writeString(target, upload.text());
            storedPath = target.toString();
        }

        KnowledgeMeta meta = new KnowledgeMeta(id, upload.originalFilename(),
                upload.source(), upload.category(), upload.type(),
                indexed, chunkCount, upload.sizeBytes(), Instant.now().toString(), storedPath);
        mapper.writerWithDefaultPrettyPrinter().writeValue(dir.resolve(id + ".meta.json").toFile(), meta);
        return toInfo(meta);
    }

    /**
     * 列出某团队的全部知识（按创建时间倒序）。
     */
    public List<KnowledgeInfo> list(String teamId) throws Exception {
        Path dir = knowledgeDir(teamId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        List<KnowledgeInfo> list = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                if (p.getFileName().toString().endsWith(".meta.json")) {
                    list.add(toInfo(mapper.readValue(p.toFile(), KnowledgeMeta.class)));
                }
            }
        }
        list.sort(Comparator.comparing(KnowledgeInfo::createdAt).reversed());
        return list;
    }

    /**
     * 删除某团队的知识：清理原始文件、元数据与已入库的 RAG 向量。
     */
    public void delete(String teamId, String id) throws Exception {
        Path dir = knowledgeDir(teamId);
        if (!Files.exists(dir)) {
            return;
        }
        knowledgeStore.deleteByMetadata(teamId, "kbId", id);
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                String fn = p.getFileName().toString();
                if (fn.startsWith(id + "__") || fn.equals(id + ".txt") || fn.equals(id + ".meta.json")) {
                    Files.deleteIfExists(p);
                }
            }
        }
        log.info("[KB] 已删除团队 {} 的知识 {}（含 RAG 向量）", Teams.sanitize(teamId), id);
    }

    private KnowledgeInfo toInfo(KnowledgeMeta m) {
        return new KnowledgeInfo(m.id(), m.filename(), m.source(), m.category(),
                m.type(), m.indexed(), m.chunkCount(), m.sizeBytes(), m.createdAt());
    }

    private String sanitize(String s) {
        if (s == null) {
            return "file";
        }
        return s.replaceAll("[^a-zA-Z0-9._\\-一-龥]", "_");
    }

    /** 知识元数据持久化记录。 */
    public record KnowledgeMeta(String id, String filename, String source, String category,
                               String type, boolean indexed, int chunkCount,
                               long sizeBytes, String createdAt, String storedPath) {
    }
}
