package com.codereview.agent.core.store;

import java.util.Optional;

/**
 * 团队级 JSON 配置存取（替代 {@code data-dir/<teamId>/*.json}）。
 *
 * <p>供按团队隔离的整份 JSON 配置使用——自定义规则 / 技能启停 / 自定义 Agent：
 * 以 {@code (teamId, scope)} 为键存一行 JSONB，多实例读写同一 PG 视图；
 * 读方本地缓存 + 短 TTL 惰性刷新即可获得集群一致（见调用方实现）。
 */
public interface TeamConfigStore {

    /** 读取某团队某 scope 的配置 JSON（不存在返回空）。 */
    Optional<String> loadJson(String teamId, String scope);

    /** 覆盖保存某团队某 scope 的配置 JSON。 */
    void saveJson(String teamId, String scope, String json);
}
