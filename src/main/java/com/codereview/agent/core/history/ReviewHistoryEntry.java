package com.codereview.agent.core.history;

import java.util.List;

/**
 * 一次审查运行的持久化记录（供“修复后复检”对比使用）。
 *
 * @param prId      PR 编号
 * @param repo      仓库名（如 org/backend-service）
 * @param runId     本次审查运行 ID（调用链追踪）
 * @param timestamp 时间戳（毫秒）
 * @param findings  本次发现摘要列表
 */
public record ReviewHistoryEntry(long prId, String repo, String runId,
                                 long timestamp, List<FindingSummary> findings) {

    /**
     * 历史存储键：同一仓库同一 PR 多次审查共享同一键。
     *
     * @return 存储键
     */
    public String key() {
        return repo + "#" + prId;
    }
}
