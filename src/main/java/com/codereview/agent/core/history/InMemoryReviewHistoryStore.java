package com.codereview.agent.core.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版审查历史存储（无盘 / 测试环境默认）。所有历史按团队 / 租户隔离。
 */
public class InMemoryReviewHistoryStore implements ReviewHistoryStore {

    private final int maxEntries;
    /** teamId → (key → 该 key 下的历史记录列表)。 */
    private final Map<String, Map<String, List<ReviewHistoryEntry>>> store = new LinkedHashMap<>();

    public InMemoryReviewHistoryStore() {
        this(30);
    }

    public InMemoryReviewHistoryStore(int maxEntries) {
        this.maxEntries = maxEntries;
    }

    @Override
    public synchronized Optional<ReviewHistoryEntry> getLatest(String teamId, String key) {
        Map<String, List<ReviewHistoryEntry>> teamMap = store.get(teamId);
        if (teamMap == null) {
            return Optional.empty();
        }
        List<ReviewHistoryEntry> list = teamMap.get(key);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(list.size() - 1));
    }

    @Override
    public synchronized void save(String teamId, ReviewHistoryEntry entry) {
        Map<String, List<ReviewHistoryEntry>> teamMap =
                store.computeIfAbsent(teamId, k -> new LinkedHashMap<>());
        List<ReviewHistoryEntry> list =
                teamMap.computeIfAbsent(entry.key(), k -> new ArrayList<>());
        list.add(entry);
        while (list.size() > maxEntries) {
            list.remove(0);
        }
    }

    @Override
    public synchronized List<ReviewHistoryEntry> list(String teamId) {
        Map<String, List<ReviewHistoryEntry>> teamMap = store.get(teamId);
        if (teamMap == null || teamMap.isEmpty()) {
            return Collections.emptyList();
        }
        List<ReviewHistoryEntry> all = new ArrayList<>();
        teamMap.values().forEach(all::addAll);
        return Collections.unmodifiableList(all);
    }
}
