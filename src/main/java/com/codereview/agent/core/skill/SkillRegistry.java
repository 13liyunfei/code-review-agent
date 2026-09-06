package com.codereview.agent.core.skill;

import com.codereview.agent.core.admin.dto.CustomRuleRequest;
import com.codereview.agent.core.admin.dto.SkillInfo;
import com.codereview.agent.core.store.TeamConfigStore;
import com.codereview.agent.tenant.Teams;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
 * 技能注册中心（Skills 市场的后端核心）。
 *
 * <p>统一管理两类技能：
 * <ul>
 *   <li>内置技能：由 Spring 注入的 {@link Skill} 列表（项目自带，含安全/逻辑/性能/规范/架构），<b>全局共享</b>；</li>
 *   <li>自定义技能：团队在前端提交的 {@link CustomRule}，运行时编译为正则技能，<b>按团队隔离</b>。</li>
 * </ul>
 *
 * <p><b>持久化（改造后）</b>：团队「启停状态 / 自定义规则」经 {@link TeamConfigStore}
 * 落共享存储（生产为 PG {@code team_kv} JSONB 行，替代改造前的 {@code data-dir/<teamId>/*.json}）。
 * 进程内保留内存态做高频读取，写操作写穿共享存储；读取带 {@value #REFRESH_MS}ms 短 TTL 惰性重载，
 * 因此集群下 A 实例管理端改配置，B 实例审查最迟 {@value #REFRESH_MS}ms 后生效——多实例行为一致。
 *
 * <p>所有方法均接收 {@code teamId}；未匹配的仓库回退到默认团队（{@link Teams#DEFAULT}）。
 */
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    /** 团队配置读缓存有效期（毫秒）：跨实例配置变更的最长可见延迟。 */
    static final long REFRESH_MS = 2_000;

    private static final String SCOPE_ENABLED = "skills-enabled";
    private static final String SCOPE_RULES = "custom-rules";

    private final List<Skill> builtInSkills;
    private final TeamConfigStore configStore;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /** teamId → (skillName → 是否启用)；缺省视为全部启用。 */
    private final Map<String, Map<String, Boolean>> enabledByTeam = new ConcurrentHashMap<>();
    /** teamId → (ruleId → 自定义规则定义)。 */
    private final Map<String, Map<String, CustomRule>> customRuleDefsByTeam = new ConcurrentHashMap<>();
    /** teamId → (ruleId → 编译后的自定义技能)。 */
    private final Map<String, Map<String, Skill>> customSkillsByTeam = new ConcurrentHashMap<>();
    /** 已加载的团队集合（懒加载去重 + 刷新时间戳）。 */
    private final Map<String, Long> loadedTeams = new ConcurrentHashMap<>();

    public SkillRegistry(List<Skill> builtInSkills, TeamConfigStore configStore) {
        this.builtInSkills = builtInSkills == null ? List.of() : builtInSkills;
        this.configStore = configStore;
    }

    /** 懒加载某团队的持久化配置（启用状态 + 自定义规则）；带短 TTL 惰性刷新以感知他实例变更。 */
    private void ensureTeamLoaded(String teamId) {
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
                loadEnabled(t);
                loadCustomRules(t);
            } catch (Exception e) {
                log.warn("[SkillRegistry] 加载团队 {} 持久化配置失败，使用默认（全部启用）：{}", t, e.getMessage());
            }
            loadedTeams.put(t, System.currentTimeMillis());
        }
    }

    private void loadEnabled(String teamId) {
        try {
            configStore.loadJson(teamId, SCOPE_ENABLED).ifPresent(json -> {
                try {
                    Map<String, Boolean> map = mapper.readValue(json, new TypeReference<>() {
                    });
                    if (map != null) {
                        enabledByTeam.put(teamId, new ConcurrentHashMap<>(map));
                    }
                } catch (Exception e) {
                    log.warn("[SkillRegistry] 解析团队 {} 启停配置失败：{}", teamId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[SkillRegistry] 读取团队 {} 启停配置失败：{}", teamId, e.getMessage());
        }
    }

    private void loadCustomRules(String teamId) {
        try {
            configStore.loadJson(teamId, SCOPE_RULES).ifPresent(json -> {
                try {
                    List<CustomRule> list = mapper.readValue(json, new TypeReference<>() {
                    });
                    if (list == null) {
                        return;
                    }
                    Map<String, CustomRule> defs = new ConcurrentHashMap<>();
                    Map<String, Skill> skills = new ConcurrentHashMap<>();
                    for (CustomRule r : list) {
                        defs.put(r.id(), r);
                        skills.put(r.id(), new CustomRuleSkill(r));
                    }
                    customRuleDefsByTeam.put(teamId, defs);
                    customSkillsByTeam.put(teamId, skills);
                } catch (Exception e) {
                    log.warn("[SkillRegistry] 解析团队 {} 自定义规则失败：{}", teamId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("[SkillRegistry] 读取团队 {} 自定义规则失败：{}", teamId, e.getMessage());
        }
    }

    private void persistEnabled(String teamId) {
        try {
            Map<String, Boolean> map = enabledByTeam.getOrDefault(teamId, Map.of());
            configStore.saveJson(teamId, SCOPE_ENABLED, mapper.writeValueAsString(map));
        } catch (Exception e) {
            log.warn("[SkillRegistry] 持久化团队 {} 启停状态失败：{}", teamId, e.getMessage());
        }
    }

    private void persistRules(String teamId) {
        try {
            List<CustomRule> list = new ArrayList<>(customRuleDefsByTeam
                    .getOrDefault(teamId, Map.of()).values());
            configStore.saveJson(teamId, SCOPE_RULES, mapper.writeValueAsString(list));
        } catch (Exception e) {
            log.warn("[SkillRegistry] 持久化团队 {} 自定义规则失败：{}", teamId, e.getMessage());
        }
    }

    /**
     * 列出某团队的全部技能（内置 + 自定义）及启用状态（内置技能共享，启停按团队叠加）。
     */
    public List<SkillInfo> listSkills(String teamId) {
        ensureTeamLoaded(teamId);
        List<SkillInfo> infos = new ArrayList<>();
        for (Skill s : builtInSkills) {
            String name = s.getMetadata().name();
            infos.add(new SkillInfo(name, name, name, s.getMetadata().description(),
                    s.getMetadata().category(), isEnabled(teamId, name), false, null));
        }
        Map<String, CustomRule> defs = customRuleDefsByTeam.getOrDefault(teamId, Map.of());
        for (CustomRule r : defs.values()) {
            String name = "custom:" + r.id();
            infos.add(new SkillInfo(r.id(), r.name(), r.name(), r.description(),
                    r.category(), isEnabled(teamId, name), true, "CUSTOM-" + r.id()));
        }
        infos.sort(Comparator.comparing(SkillInfo::category).thenComparing(SkillInfo::name));
        return infos;
    }

    public boolean isEnabled(String teamId, String name) {
        ensureTeamLoaded(teamId);
        return enabledByTeam.getOrDefault(teamId, Map.of()).getOrDefault(name, true);
    }

    public void setEnabled(String teamId, String name, boolean enabled) {
        ensureTeamLoaded(teamId);
        enabledByTeam.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>()).put(name, enabled);
        persistEnabled(teamId);
    }

    /**
     * 新增某团队的自定义规则，返回该规则的信息。
     */
    public SkillInfo addCustomRule(String teamId, CustomRuleRequest req) {
        ensureTeamLoaded(teamId);
        String id = "cr-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 4);
        CustomRule rule = new CustomRule(id, req.name(), req.category(), req.severity(),
                req.pattern(), req.title(), req.description(), req.suggestion(), Instant.now());
        customRuleDefsByTeam.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>()).put(id, rule);
        customSkillsByTeam.computeIfAbsent(teamId, k -> new ConcurrentHashMap<>()).put(id, new CustomRuleSkill(rule));
        persistRules(teamId);
        log.info("[SkillRegistry] 团队 {} 新增自定义规则 {}（{}）", teamId, rule.name(), id);
        return listSkills(teamId).stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
    }

    public void removeCustomRule(String teamId, String id) {
        ensureTeamLoaded(teamId);
        Map<String, CustomRule> defs = customRuleDefsByTeam.get(teamId);
        Map<String, Skill> skills = customSkillsByTeam.get(teamId);
        if (defs != null) {
            defs.remove(id);
        }
        if (skills != null) {
            skills.remove(id);
        }
        Map<String, Boolean> enabled = enabledByTeam.get(teamId);
        if (enabled != null) {
            enabled.remove("custom:" + id);
        }
        persistRules(teamId);
        persistEnabled(teamId);
        log.info("[SkillRegistry] 团队 {} 删除自定义规则 {}", teamId, id);
    }

    /**
     * 获取某团队、某审查维度下已启用的技能集合（审查时实时取用，支持运行期动态增删）。
     */
    public List<Skill> getEnabledSkillsForCategory(String teamId, String category) {
        ensureTeamLoaded(teamId);
        List<Skill> result = new ArrayList<>();
        for (Skill s : builtInSkills) {
            if (category.equals(s.getMetadata().category()) && isEnabled(teamId, s.getMetadata().name())) {
                result.add(s);
            }
        }
        for (Skill s : customSkillsByTeam.getOrDefault(teamId, Map.of()).values()) {
            if (category.equals(s.getMetadata().category()) && isEnabled(teamId, s.getMetadata().name())) {
                result.add(s);
            }
        }
        return result;
    }
}
