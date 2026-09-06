package com.codereview.agent.core.skill;

import com.codereview.agent.core.admin.dto.CustomRuleRequest;
import com.codereview.agent.core.admin.dto.SkillInfo;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.skill.impl.PatternSkill;
import com.codereview.agent.core.store.InMemoryTeamConfigStore;
import com.codereview.agent.core.store.TeamConfigStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证技能注册中心：内置与自定义技能的注册、启用维度过滤、启停开关。
 * 该逻辑是「团队自定义规则能进入审查」的核心链路。
 *
 * <p>集群改造后：团队「启停状态 / 自定义规则」经 {@link TeamConfigStore} 落共享存储
 * （生产为 PG team_kv JSONB 行，替代改造前的 data-dir 文件），写操作写穿、读带短 TTL
 * 惰性刷新——这里用同一共享 store 的两个注册中心实例验证「重启/换实例不丢自定义规则」。
 */
class SkillRegistryTest {

    private static List<Skill> builtIns() {
        return List.of(new PatternSkill("x", "logic", "L1", "t", "d", "s", Severity.MAJOR, 0.9,
                java.util.regex.Pattern.compile("foo")));
    }

    private SkillRegistry newRegistry() {
        return new SkillRegistry(builtIns(), new InMemoryTeamConfigStore());
    }

    /** 在指定共享存储上创建注册中心（clean 语义由调用方用新 store 表达）。 */
    private SkillRegistry registryOn(TeamConfigStore configStore) {
        return new SkillRegistry(builtIns(), configStore);
    }

    /**
     * 回归保护：自定义规则必须写穿共享存储并在「重启/换实例」后重新加载。
     * 曾经因 ObjectMapper 未注册 JavaTimeModule，{@code createdAt}(Instant)
     * 序列化失败导致 custom-rules.json 写入失败、规则重启即丢失。
     */
    @Test
    void customRulePersistsAcrossRestart() {
        TeamConfigStore shared = new InMemoryTeamConfigStore();

        SkillRegistry first = registryOn(shared);
        SkillInfo added = first.addCustomRule("default", new CustomRuleRequest(
                "持久化规则", "logic", "MINOR", "qux", "标题", "描述", "建议"));

        // 已写穿共享存储（团队隔离 scope：default/custom-rules）
        assertTrue(shared.loadJson("default", "custom-rules").isPresent(),
                "自定义规则应写穿共享存储（而非本机文件）");

        // 模拟引擎重启/另一实例：同一共享存储上新建注册中心并加载
        SkillRegistry restarted = registryOn(shared);
        assertEquals(2, restarted.getEnabledSkillsForCategory("default", "logic").size());
        assertTrue(restarted.listSkills("default").stream().anyMatch(s -> s.id().equals(added.id())));
    }

    @Test
    void customRuleAppearsAndIsRoutedByCategory() {
        SkillRegistry reg = newRegistry();
        SkillInfo added = reg.addCustomRule("default", new CustomRuleRequest(
                "我的规则", "logic", "MINOR", "bar", "标题", "描述", "建议"));

        assertTrue(added.custom());
        // 该维度下应包含 1 个内置 + 1 个自定义
        assertEquals(2, reg.getEnabledSkillsForCategory("default", "logic").size());
        // 其它维度不应包含
        assertEquals(0, reg.getEnabledSkillsForCategory("default", "security").size());
    }

    @Test
    void disablingBuiltInRemovesItFromEnabledSet() {
        SkillRegistry reg = newRegistry();
        reg.addCustomRule("default", new CustomRuleRequest(
                "规则2", "logic", "MINOR", "baz", "标题", "描述", "建议"));
        assertEquals(2, reg.getEnabledSkillsForCategory("default", "logic").size());

        reg.setEnabled("default", "x", false);
        List<Skill> enabled = reg.getEnabledSkillsForCategory("default", "logic");
        assertEquals(1, enabled.size());
        assertFalse(reg.isEnabled("default", "x"));
    }

    @Test
    void removeCustomRuleClearsIt() {
        SkillRegistry reg = newRegistry();
        SkillInfo added = reg.addCustomRule("default", new CustomRuleRequest(
                "临时规则", "logic", "MINOR", "qux", "标题", "描述", "建议"));
        assertEquals(2, reg.getEnabledSkillsForCategory("default", "logic").size());

        reg.removeCustomRule("default", added.id());
        assertEquals(1, reg.getEnabledSkillsForCategory("default", "logic").size());
        assertTrue(reg.listSkills("default").stream().noneMatch(s -> s.id().equals(added.id())));
    }

    @Test
    void builtInDisableTogglePersistsToSharedStore() {
        TeamConfigStore shared = new InMemoryTeamConfigStore();
        SkillRegistry first = registryOn(shared);
        first.setEnabled("default", "x", false);

        // 换实例后启停状态仍生效（启停同样走共享存储）
        SkillRegistry second = registryOn(shared);
        assertFalse(second.isEnabled("default", "x"));
        assertEquals(0, second.getEnabledSkillsForCategory("default", "logic").size());
    }
}
