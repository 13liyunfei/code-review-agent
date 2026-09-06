package com.codereview.agent.core.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存知识元数据存储（单机/测试回退实现，重启丢失）。
 */
public class InMemoryKnowledgeMetaStore implements KnowledgeMetaStore {

    private final Map<String, Map<String, KnowledgeMeta>> byTeam = new ConcurrentHashMap<>();

    @Override
    public void save(String teamId, KnowledgeMeta meta) {
        byTeam.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>()).put(meta.id(), meta);
    }

    @Override
    public List<KnowledgeMeta> list(String teamId) {
        List<KnowledgeMeta> out = new ArrayList<>(byTeam.getOrDefault(teamId, Map.of()).values());
        out.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return out;
    }

    @Override
    public Optional<KnowledgeMeta> get(String teamId, String id) {
        return Optional.ofNullable(byTeam.getOrDefault(teamId, Map.of()).get(id));
    }

    @Override
    public void delete(String teamId, String id) {
        Map<String, KnowledgeMeta> m = byTeam.get(teamId);
        if (m != null) {
            m.remove(id);
        }
    }
}
