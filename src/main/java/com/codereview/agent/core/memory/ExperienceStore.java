package com.codereview.agent.core.memory;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 经验库（长期记忆的检索入口）。
 *
 * <p>封装对 {@link MemoryStore} 的检索，将相关经验格式化为文本，
 * 注入到提示词的【历史经验参考】区块。
 */
@Component
public class ExperienceStore {

    private final MemoryStore memoryStore;

    public ExperienceStore(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /**
     * 获取与当前审查相关的历史经验文本（团队自有的经验，不含全局基线）。
     *
     * @param teamId    团队标识
     * @param agentType 审查 Agent 类型
     * @param text      检索线索（通常为变更摘要）
     * @return 格式化后的经验文本（无相关经验返回空串）
     */
    public String getRelevantExperiences(String teamId, String agentType, String text) {
        List<MemoryEntry> entries = memoryStore.search(text, agentType, 5, teamId, false);
        if (entries.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MemoryEntry e : entries) {
            if ("experience".equals(e.metadata().get("type"))) {
                sb.append("- ").append(e.content()).append('\n');
            }
        }
        return sb.toString().trim();
    }
}
