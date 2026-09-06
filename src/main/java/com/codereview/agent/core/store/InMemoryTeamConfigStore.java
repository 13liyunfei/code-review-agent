package com.codereview.agent.core.store;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存团队配置存储（单机/测试回退实现，重启丢失）。
 */
public class InMemoryTeamConfigStore implements TeamConfigStore {

    private final Map<String, String> store = new ConcurrentHashMap<>();

    @Override
    public Optional<String> loadJson(String teamId, String scope) {
        return Optional.ofNullable(store.get(key(teamId, scope)));
    }

    @Override
    public void saveJson(String teamId, String scope, String json) {
        store.put(key(teamId, scope), json);
    }

    private static String key(String teamId, String scope) {
        return teamId + "|" + scope;
    }
}
