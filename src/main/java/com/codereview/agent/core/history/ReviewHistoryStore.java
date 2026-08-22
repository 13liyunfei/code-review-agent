package com.codereview.agent.core.history;

import java.util.List;
import java.util.Optional;

/**
 * 审查历史存储（支持“修复后复检”增量对比与质量趋势统计）。
 *
 * <p>所有历史按团队 / 租户隔离：{@code data-dir/&lt;teamId&gt;/review-history.json}。
 */
public interface ReviewHistoryStore {

    /**
     * 获取某团队、某 PR 最近一次（不含当前运行）的审查记录。
     *
     * @param teamId 团队标识
     * @param key    存储键（{@link ReviewHistoryEntry#key()}）
     * @return 最近一次历史记录（无则返回空）
     */
    Optional<ReviewHistoryEntry> getLatest(String teamId, String key);

    /**
     * 保存某团队的一次审查记录（同一 key 保留最近 {@code maxEntries} 条）。
     *
     * @param teamId 团队标识
     * @param entry  审查记录
     */
    void save(String teamId, ReviewHistoryEntry entry);

    /**
     * 列出某团队的全部历史记录（用于质量趋势统计）。
     *
     * @param teamId 团队标识
     * @return 历史记录列表
     */
    List<ReviewHistoryEntry> list(String teamId);
}
