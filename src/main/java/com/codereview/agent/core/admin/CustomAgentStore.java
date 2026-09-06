package com.codereview.agent.core.admin;

import com.codereview.agent.core.security.InjectionDetector;
import com.codereview.agent.core.store.TeamConfigStore;
import com.codereview.agent.tenant.Teams;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义审查 Agent 存储（按团队隔离）。
 *
 * <p>持久化（改造后）：经 {@link TeamConfigStore} 落共享存储（生产为 PG {@code team_kv}
 * JSONB 行，替代改造前的 {@code data-dir/<teamId>/custom-agents.json}）——多实例共享同一配置，
 * A 实例管理端变更，B 实例审查最迟 {@value #REFRESH_MS}ms 生效。内存态即时更新 + 写穿。
 * 所有写操作前对业务方提交内容进行 {@link InjectionDetector} 预检，命中即拒绝。
 */
public class CustomAgentStore {

    private static final Logger log = LoggerFactory.getLogger(CustomAgentStore.class);

    /** 团队配置读缓存有效期（毫秒）。 */
    static final long REFRESH_MS = 2_000;

    private static final String SCOPE = "custom-agents";

    private final TeamConfigStore configStore;
    private final InjectionDetector injectionDetector;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /** teamId → (agentId → def)；内存态，写穿落库。 */
    private final Map<String, Map<String, CustomAgentDef>> byTeam = new ConcurrentHashMap<>();
    /** 已加载的团队集合（懒加载去重 + 刷新时间戳）。 */
    private final Map<String, Long> loadedTeams = new ConcurrentHashMap<>();

    public CustomAgentStore(TeamConfigStore configStore, InjectionDetector injectionDetector) {
        this.configStore = configStore;
        this.injectionDetector = injectionDetector;
    }

    /** 懒加载某团队的持久化定义；带短 TTL 惰性刷新以感知他实例变更。 */
    private void ensureLoaded(String teamId) {
        String t = Teams.sanitize(teamId);
        Long last = loadedTeams.get(t);
        if (last != null && System.currentTimeMillis() - last < REFRESH_MS) {
            return;
        }
        synchronized (loadedTeams) {
            Long again = loadedTeams.get(t);
            if (again != null && System.currentTimeMillis() - again < REFRESH_MS) {
                return;
            }
            try {
                configStore.loadJson(t, SCOPE).ifPresent(json -> {
                    try {
                        List<CustomAgentDef> list = mapper.readValue(json, new TypeReference<>() {
                        });
                        Map<String, CustomAgentDef> map = new ConcurrentHashMap<>();
                        if (list != null) {
                            for (CustomAgentDef d : list) {
                                map.put(d.id(), d);
                            }
                        }
                        byTeam.put(t, map);
                    } catch (Exception e) {
                        log.warn("[CustomAgentStore] 解析团队 {} 自定义 Agent 失败，使用空：{}", t, e.getMessage());
                    }
                });
            } catch (Exception e) {
                log.warn("[CustomAgentStore] 加载团队 {} 自定义 Agent 失败，使用空：{}", t, e.getMessage());
            }
            loadedTeams.put(t, System.currentTimeMillis());
        }
    }

    private void persist(String teamId) {
        try {
            Map<String, CustomAgentDef> map = byTeam.getOrDefault(teamId, Map.of());
            List<CustomAgentDef> list = new ArrayList<>(map.values());
            configStore.saveJson(teamId, SCOPE, mapper.writeValueAsString(list));
        } catch (Exception e) {
            log.warn("[CustomAgentStore] 持久化团队 {} 自定义 Agent 失败：{}", teamId, e.getMessage());
        }
    }

    /**
     * 校验并预检业务方提交内容（名称/描述/要点）是否含 Prompt 注入风险。
     *
     * @return 风险文本（含命中内容）或 null（安全）
     */
    public String injectionRisk(String name, String description, List<String> focusPoints) {
        if (injectionDetector == null) {
            return null;
        }
        StringBuilder risk = new StringBuilder();
        if (injectionDetector.detect(name)) {
            risk.append("name;");
        }
        if (injectionDetector.detect(description)) {
            risk.append("description;");
        }
        if (focusPoints != null) {
            for (String fp : focusPoints) {
                if (injectionDetector.detect(fp)) {
                    risk.append("focusPoint;");
                    break;
                }
            }
        }
        return risk.isEmpty() ? null : risk.toString();
    }

    /** 列出某团队全部自定义 Agent（含启用态），按名称排序。 */
    public List<CustomAgentDef> list(String teamId) {
        ensureLoaded(teamId);
        List<CustomAgentDef> list = new ArrayList<>(byTeam.getOrDefault(teamId, Map.of()).values());
        list.sort(Comparator.comparing(CustomAgentDef::name));
        return list;
    }

    /** 列出某团队已启用的自定义 Agent（调度时使用）。 */
    public List<CustomAgentDef> listEnabled(String teamId) {
        return list(teamId).stream().filter(CustomAgentDef::enabled).toList();
    }

    public CustomAgentDef get(String teamId, String id) {
        ensureLoaded(teamId);
        return byTeam.getOrDefault(teamId, Map.of()).get(id);
    }

    /** 新增自定义 Agent（含注入预检）。 */
    public CustomAgentDef add(String teamId, String name, String description,
                              List<String> focusPoints, String severityBias) {
        String risk = injectionRisk(name, description, focusPoints);
        if (risk != null) {
            throw new IllegalArgumentException("提交内容命中 Prompt 注入风险，拒绝保存（字段：" + risk + "）");
        }
        ensureLoaded(teamId);
        String id = "ca-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 4);
        CustomAgentDef def = CustomAgentDef.create(id, Teams.sanitize(teamId), name, description, focusPoints, severityBias);
        byTeam.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>()).put(id, def);
        persist(teamId);
        log.info("[CustomAgentStore] 团队 {} 新增自定义 Agent {}（{}）", teamId, name, id);
        return def;
    }

    /** 编辑更新（乐观锁：version 不匹配抛异常）。 */
    public CustomAgentDef update(String teamId, String id, String name, String description,
                                 List<String> focusPoints, String severityBias, boolean enabled, long version) {
        String risk = injectionRisk(name, description, focusPoints);
        if (risk != null) {
            throw new IllegalArgumentException("提交内容命中 Prompt 注入风险，拒绝保存（字段：" + risk + "）");
        }
        ensureLoaded(teamId);
        CustomAgentDef existing = byTeam.getOrDefault(teamId, Map.of()).get(id);
        if (existing == null) {
            throw new IllegalArgumentException("自定义 Agent 不存在：" + id);
        }
        if (existing.version() != version) {
            throw new IllegalStateException("版本冲突（乐观锁）：当前 version=" + existing.version() + "，提交 version=" + version);
        }
        CustomAgentDef updated = existing.withUpdate(name, description, focusPoints, severityBias, enabled);
        byTeam.get(teamId).put(id, updated);
        persist(teamId);
        log.info("[CustomAgentStore] 团队 {} 更新自定义 Agent {}（version={}）", teamId, id, updated.version());
        return updated;
    }

    public void remove(String teamId, String id) {
        ensureLoaded(teamId);
        Map<String, CustomAgentDef> map = byTeam.get(teamId);
        if (map != null) {
            map.remove(id);
        }
        persist(teamId);
        log.info("[CustomAgentStore] 团队 {} 删除自定义 Agent {}", teamId, id);
    }

    public void setEnabled(String teamId, String id, boolean enabled) {
        ensureLoaded(teamId);
        CustomAgentDef existing = byTeam.getOrDefault(teamId, Map.of()).get(id);
        if (existing == null) {
            throw new IllegalArgumentException("自定义 Agent 不存在：" + id);
        }
        byTeam.get(teamId).put(id, new CustomAgentDef(
                existing.id(), existing.teamId(), existing.name(), existing.description(),
                existing.focusPoints(), existing.severityBias(), enabled,
                existing.createdAt(), Instant.now(), existing.version() + 1));
        persist(teamId);
        log.info("[CustomAgentStore] 团队 {} 自定义 Agent {} 启用态={}", teamId, id, enabled);
    }
}
