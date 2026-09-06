package com.codereview.agent.core.admin;

import java.util.List;
import java.util.Optional;

/**
 * 团队知识元数据存取（替代改造前的 {@code knowledge/} 目录扫描）。
 *
 * <p>生产实现为 PostgreSQL（{@code knowledge_meta} 表，多实例共享：管理端任一实例
 * 上传/删除后，其余实例列表与删除立即可见）；另有内存实现供单机/测试。
 */
public interface KnowledgeMetaStore {

    void save(String teamId, KnowledgeMeta meta);

    /** 按创建时间倒序列出某团队全部知识元数据。 */
    List<KnowledgeMeta> list(String teamId);

    Optional<KnowledgeMeta> get(String teamId, String id);

    void delete(String teamId, String id);
}
