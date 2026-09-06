package com.codereview.agent.core.admin;

import com.codereview.agent.core.admin.dto.KnowledgeInfo;
import com.codereview.agent.core.admin.dto.KnowledgeUpload;
import com.codereview.agent.core.rag.KnowledgeStore;
import com.codereview.agent.tenant.Teams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 团队知识入库服务（RAG 数据源的动态写入入口）。
 *
 * <p>把上传的规范文档 / 操作手册 / 视频文字稿切分、向量化并写入 {@link KnowledgeStore}，
 * 使后续 PR 审查前能检索到【团队规范】；知识元数据（含原文）入 {@link KnowledgeMetaStore}
 * （多实例共享 PG），删除时同步清理向量。所有内容按团队隔离。
 *
 * <p>改造说明：原实现把原始文件落盘 {@code data-dir/<teamId>/knowledge/} 供列表/删除
 * 读取——多机部署下管理端落在 A 机、审查在 B 机的实例看不到该归档。现元数据入 PG 单行，
 * 任一实例上传/删除全局可见，彻底移除本地文件依赖。
 */
@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final KnowledgeStore knowledgeStore;
    private final KnowledgeMetaStore metaStore;

    public KnowledgeIngestionService(KnowledgeStore knowledgeStore, KnowledgeMetaStore metaStore) {
        this.knowledgeStore = knowledgeStore;
        this.metaStore = metaStore;
    }

    /**
     * 入库某团队的一份知识：索引文本到 RAG + 登记元数据（含原文）。
     */
    public KnowledgeInfo ingest(String teamId, KnowledgeUpload upload) throws Exception {
        String t = Teams.sanitize(teamId);
        String id = "kb-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 4);
        boolean indexed = upload.text() != null && !upload.text().isBlank();
        int chunkCount = 0;

        if (indexed) {
            String source = upload.source() != null ? upload.source()
                    : (upload.originalFilename() != null ? upload.originalFilename() : "knowledge");
            String type = upload.type() != null ? upload.type() : "document";
            Map<String, String> meta = new java.util.LinkedHashMap<>();
            meta.put("source", source);
            meta.put("kbId", id);
            meta.put("type", type);
            meta.put("teamId", t);
            chunkCount = knowledgeStore.saveKnowledge(t, upload.text(), meta);
            log.info("[KB] 团队 {} 已结构化索引 {} 段到 RAG 知识库（kbId={}）", t, chunkCount, id);
        }

        metaStore.save(t, new KnowledgeMeta(id, t, upload.originalFilename(), upload.source(),
                upload.category(), upload.type(), indexed, chunkCount, upload.sizeBytes(),
                upload.text(), Instant.now().toString()));
        return toInfo(id, t, upload.originalFilename(), upload.source(), upload.category(),
                upload.type(), indexed, chunkCount, upload.sizeBytes(), Instant.now().toString());
    }

    /** 列出某团队的全部知识（按创建时间倒序）。 */
    public List<KnowledgeInfo> list(String teamId) throws Exception {
        String t = Teams.sanitize(teamId);
        List<KnowledgeInfo> list = new ArrayList<>();
        for (KnowledgeMeta m : metaStore.list(t)) {
            list.add(toInfo(m.id(), m.teamId(), m.filename(), m.source(), m.category(), m.type(),
                    m.indexed(), m.chunkCount(), m.sizeBytes(), m.createdAt()));
        }
        return list;
    }

    /**
     * 删除某团队的知识：清理元数据与已入库的 RAG 向量。
     */
    public void delete(String teamId, String id) throws Exception {
        String t = Teams.sanitize(teamId);
        metaStore.delete(t, id);
        knowledgeStore.deleteByMetadata(t, "kbId", id);
        log.info("[KB] 已删除团队 {} 的知识 {}（含 RAG 向量）", t, id);
    }

    private KnowledgeInfo toInfo(String id, String teamId, String filename, String source,
                                 String category, String type, boolean indexed, int chunkCount,
                                 long sizeBytes, String createdAt) {
        return new KnowledgeInfo(id, filename, source, category, type, indexed, chunkCount, sizeBytes, createdAt);
    }
}
