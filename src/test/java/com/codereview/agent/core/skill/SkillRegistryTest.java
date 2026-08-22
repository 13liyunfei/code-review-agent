package com.codereview.agent.core.skill;

import com.codereview.agent.core.admin.dto.CustomRuleRequest;
import com.codereview.agent.core.admin.dto.SkillInfo;
import com.codereview.agent.core.model.Severity;
import com.codereview.agent.core.skill.impl.PatternSkill;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证技能注册中心：内置与自定义技能的注册、启用维度过滤、启停开关。
 * 该逻辑是「团队自定义规则能进入审查」的核心链路。
 */
class SkillRegistryTest {

    /** 清空持久化目录，保证每个用例从干净状态开始（避免启停/自定义跨用例串扰）。 */
    private void clean(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时文件偶发占用，忽略
                }
            });
        } catch (IOException ignored) {
            // 目录不存在等情况，忽略
        }
    }

    private SkillRegistry newRegistry() {
        Path dir = Path.of("./target/skill-test");
        clean(dir);
        return registryAt(dir, false);
    }

    /** 在指定目录创建并初始化注册中心（clean=true 时先清空，模拟全新启动）。 */
    private SkillRegistry registryAt(Path dir, boolean cleanFirst) {
        if (cleanFirst) {
            clean(dir);
        }
        List<Skill> builtIns = List.of(
                new PatternSkill("x", "logic", "L1", "t", "d", "s", Severity.MAJOR, 0.9,
                        java.util.regex.Pattern.compile("foo")));
        SkillRegistry reg = new SkillRegistry(builtIns, dir);
        reg.init();
        return reg;
    }

    /**
     * 回归保护：自定义规则必须落盘并在重启后重新加载。
     * 曾经因 ObjectMapper 未注册 JavaTimeModule，{@code createdAt}(Instant)
     * 序列化失败导致 custom-rules.json 写入失败、规则重启即丢失。
     */
    @Test
    void customRulePersistsAcrossRestart() {
        Path dir = Path.of("./target/skill-persist-test");
        clean(dir);

        SkillRegistry first = registryAt(dir, false);
        SkillInfo added = first.addCustomRule("default", new CustomRuleRequest(
                "持久化规则", "logic", "MINOR", "qux", "标题", "描述", "建议"));

        // 文件已落盘（团队隔离：data-dir/<teamId>/custom-rules.json）
        assertTrue(Files.exists(dir.resolve("default").resolve("custom-rules.json")));

        // 模拟引擎重启：同一目录新建注册中心并加载
        SkillRegistry restarted = registryAt(dir, false);
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
}
