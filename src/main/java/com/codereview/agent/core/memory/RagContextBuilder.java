package com.codereview.agent.core.memory;

import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.tenant.Teams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 上下文构建器（见文档“RAG 的使用位置”）。
 *
 * <p>审查前检索规范文档 / 历史 PR / 安全 Wiki，将相关内容作为
 * 【相关历史知识】注入提示词。内部链路：提取查询 → 向量检索 → 重排 → 格式化 Top-5。
 * 检索时始终纳入团队自身内容 + 全局基线（编码规范手册），实现“全局基线 + 团队叠加”。
 */
@Component
public class RagContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(RagContextBuilder.class);

    private final MemoryStore memoryStore;

    public RagContextBuilder(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /**
     * 为指定团队 / Agent 构建 RAG 增强上下文。
     *
     * @param teamId    团队标识（含全局基线叠加）
     * @param agentType 审查 Agent 类型
     * @param diffs     代码变更
     * @return 检索到的相关知识文本（无则返回空串）
     */
    public String buildContext(String teamId, String agentType, List<CodeDiff> diffs) {
        long t0 = System.currentTimeMillis();
        // 1. 从代码提取查询意图
        String query = extractQueryFromDiffs(diffs);
        // 2. 向量检索（agentType="RAG" 表示通用知识库；含全局基线）
        List<MemoryEntry> retrieved = memoryStore.search(query, "RAG", 10, Teams.sanitize(teamId), true);
        // 3. 重排（此处沿用相似度排序，生产可接 Cross-Encoder 重排）
        // 4. 取 Top-5 格式化为文本
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(5, retrieved.size());
        for (int i = 0; i < limit; i++) {
            MemoryEntry e = retrieved.get(i);
            sb.append("- [").append(e.metadata().getOrDefault("source", "knowledge"))
                    .append("] ").append(e.content()).append('\n');
        }
        log.info("[RAG] 上下文构建：team={}, agent={}, 查询={}字符, 检索 {} 条, 截取 Top-{} 注入, 耗时 {}ms",
                Teams.sanitize(teamId), agentType, query.length(), retrieved.size(), limit,
                System.currentTimeMillis() - t0);
        return sb.toString().trim();
    }

    /**
     * 从代码变更中提取检索查询（取补丁前若干字符）。
     */
    private String extractQueryFromDiffs(List<CodeDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return "";
        }
        String joined = diffs.stream()
                .map(CodeDiff::patch)
                .reduce("", String::concat);
        return joined.length() > 500 ? joined.substring(0, 500) : joined;
    }
}
