package com.codereview.agent.core.admin;

import com.codereview.agent.core.security.InjectionDetector;
import com.codereview.agent.tenant.Teams;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * <p>复用 {@link com.codereview.agent.core.skill.SkillRegistry} 的团队目录范式：
 * 落盘 {@code data-dir/&lt;teamId&gt;/custom-agents.json}，内存态即时更新，写穿持久化，
 * 新增/编辑/删除后下一次 PR 即生效（无需重启）。所有写操作前对业务方提交内容进行
 * {@link InjectionDetector} 预检，命中即拒绝（防止业务方自己写入越权提示）。
 */
public class CustomAgentStore {

    private static final Logger log = LoggerFactory.getLogger(CustomAgentStore.class);

    private final Path dataDir;
    private final InjectionDetector injectionDetector;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /** teamId → (agentId → def)；内存态，写穿落盘。 */
    private final Map<String, Map<String, CustomAgentDef>> byTeam = new ConcurrentHashMap<>();
    /** 已加载的团队集合（去重懒加载）。 */
    private final Map<String, Boolean> loadedTeams = new ConcurrentHashMap<>();

    public CustomAgentStore(Path dataDir, InjectionDetector injectionDetector) {
        this.dataDir = dataDir;
        this.injectionDetector = injectionDetector;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(dataDir);
        } catch (Exception e) {
            log.warn("[CustomAgentStore] 初始化失败：{}", e.getMessage());
        }
    }

    private Path teamDir(String teamId) {
        return dataDir.resolve(Teams.sanitize(teamId));
    }

    private Path file(String teamId) {
        return teamDir(teamId).resolve("custom-agents.json");
    }

    /** 懒加载某团队的持久化定义。 */
    private void ensureLoaded(String teamId) {
        String t = Teams.sanitize(teamId);
        if (loadedTeams.containsKey(t)) {
            return;
        }
        synchronized (loadedTeams) {
            if (loadedTeams.containsKey(t)) {
                return;
            }
            try {
                Files.createDirectories(teamDir(t));
                Path f = file(t);
                if (Files.exists(f)) {
                    List<CustomAgentDef> list = mapper.readValue(Files.readString(f), new TypeReference<>() {
                    });
                    Map<String, CustomAgentDef> map = new ConcurrentHashMap<>();
                    if (list != null) {
                        for (CustomAgentDef d : list) {
                            map.put(d.id(), d);
                        }
                    }
                    byTeam.put(t, map);
                }
            } catch (Exception e) {
                log.warn("[CustomAgentStore] 加载团队 {} 自定义 Agent 失败，使用空：{}", t, e.getMessage());
            }
            loadedTeams.put(t, Boolean.TRUE);
        }
    }

    private void persist(String teamId) {
        try {
            Map<String, CustomAgentDef> map = byTeam.getOrDefault(teamId, Map.of());
            List<CustomAgentDef> list = new ArrayList<>(map.values());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file(teamId).toFile(), list);
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
