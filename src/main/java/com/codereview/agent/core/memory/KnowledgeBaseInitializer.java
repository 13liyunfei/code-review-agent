package com.codereview.agent.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.codereview.agent.core.rag.KnowledgeStore;
import com.codereview.agent.tenant.Teams;

import jakarta.annotation.PostConstruct;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库初始化器（RAG 数据源预灌入，见文档“写入位置：知识库初始化”）。
 *
 * <p>系统启动时把团队规范文档切分并向量化存入 {@link MemoryStore}（生产为 PGVector），
 * 使后续 PR 审查前可检索【相关历史知识】。历史审查报告、内部 Wiki 也按相同方式入库。
 */
@Component
public class KnowledgeBaseInitializer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseInitializer.class);

    private final KnowledgeStore knowledgeStore;

    public KnowledgeBaseInitializer(KnowledgeStore knowledgeStore) {
        this.knowledgeStore = knowledgeStore;
    }

    /**
     * 启动时灌入团队编码规范。
     *
     * <p>整体包裹为失败可降级：知识库预灌入属于“增强项”，即使向量化服务暂时不可达
     * （如网络/代理问题）也不应拖垮整个应用启动，仅告警并继续。
     */
    @PostConstruct
    public void init() {
        try {
            doInit();
        } catch (Exception e) {
            log.error("[KB] 知识库预灌入失败，已跳过（应用继续启动）：{}", e.getMessage(), e);
        }
    }

    private void doInit() {
        String handbook = loadFile("handbook/java-coding-standard.md");
        if (handbook == null) {
            log.warn("[KB] 未找到编码规范手册，跳过预灌入");
            return;
        }
        // 结构感知切块 + 富元数据（层级 section + 重叠），由 KnowledgeStore 完成
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("source", "handbook");
        meta.put("type", "coding_standard");
        int chunkCount = knowledgeStore.saveKnowledge(Teams.GLOBAL, handbook, meta);
        log.info("[KB] 编码规范已结构化切分为 {} 段并入库", chunkCount);
    }

    /**
     * 读取 classpath 文本资源。
     */
    private String loadFile(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(java.util.stream.Collectors.joining("\n"));
        } catch (IOException e) {
            log.error("[KB] 读取规范手册失败：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 按行聚合切分为约 maxChars 的 chunk。
     *
     * @param text      原文
     * @param maxChars  单 chunk 近似上限
     * @return chunk 列表
     */
}
