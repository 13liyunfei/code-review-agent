package com.codereview.agent.core.skill;

import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.store.InMemoryTeamConfigStore;
import com.codereview.agent.tenant.Teams;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性规则层（{@link RuleResolver}）的判据测试。
 *
 * <p>覆盖三件事：① 内置规则层的<b>自洽性</b>（索引与正文一一对应，没有「配了却没有正文」的死条目）；
 * ② 四级覆盖链的<b>优先级与不变量</b>（团队覆盖生效，但配置清空/损坏时内置层绝不消失）；
 * ③ 注入预算上限（规则层再准也不能把 prompt 撑爆）。
 */
class RuleResolverTest {

    private static CodeDiff diff(String path) {
        return new CodeDiff(path, "@@ -1 +1 @@\n+x", CodeDiff.inferLanguage(path), 1, 0);
    }

    private static RuleResolver withStore(InMemoryTeamConfigStore store) {
        return new RuleResolver(store);
    }

    // ---------------------------------------------------------------- 内置层自洽性

    /** 索引与正文必须一一对应：出现「索引指了不存在的正文」时，表现是规则静默不生效。 */
    @Test
    void indexAndRuleFilesAreConsistent() throws Exception {
        RuleResolver resolver = withStore(new InMemoryTeamConfigStore());

        Set<String> onDisk = new HashSet<>();
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:rules/*.md");
        for (Resource r : resources) {
            String name = r.getFilename();
            assertFalse(name == null || name.isBlank(), "规则文件名不得为空");
            onDisk.add(name.substring(0, name.length() - ".md".length()));
        }

        Set<String> indexed = resolver.builtinRuleIds();
        assertTrue(indexed.size() >= 15,
                "内置规则文档应不少于 15 篇，实际 " + indexed.size() + " 篇");
        assertEquals(onDisk, indexed,
                "索引里的 ruleId 与磁盘上的 .md 必须完全一致（多一个=死条目，少一个=有文档但永不生效）");
    }

    @Test
    void builtinRulesMatchByFilePattern() {
        RuleResolver resolver = withStore(new InMemoryTeamConfigStore());

        List<RuleHit> hits = resolver.resolve(Teams.DEFAULT, List.of(
                diff("src/main/java/com/x/service/OrderServiceImpl.java"),
                diff("src/main/resources/mapper/OrderMapper.xml")));

        List<String> ids = new ArrayList<>();
        for (RuleHit h : hits) {
            ids.add(h.ruleId());
            assertEquals(RuleLevel.BUILTIN, h.level());
            assertFalse(h.content().isBlank(), "命中的规则必须有正文：" + h.ruleId());
        }
        assertTrue(ids.contains("java-service-transaction"), "Service 文件应命中事务规范：" + ids);
        assertTrue(ids.contains("java-mapper-xml"), "Mapper XML 应命中 Mapper 规范：" + ids);
    }

    @Test
    void unmatchedPathYieldsNoRule() {
        RuleResolver resolver = withStore(new InMemoryTeamConfigStore());
        List<RuleHit> hits = resolver.resolve(Teams.DEFAULT, List.of(diff("some/unknown/file.zzz")));
        assertTrue(hits.isEmpty(), "没有模式匹配时不应凭空造规则，实际：" + hits);
    }

    // ---------------------------------------------------------------- 四级覆盖链

    @Test
    void teamLayerOverridesBuiltinWithSameId() {
        InMemoryTeamConfigStore store = new InMemoryTeamConfigStore();
        store.saveJson(Teams.DEFAULT, RuleResolver.CONFIG_SCOPE,
                "{\"rules\":[{\"id\":\"java-mapper-xml\",\"title\":\"团队版 Mapper 规范\","
                        + "\"patterns\":[\"**/*Mapper.xml\"],\"content\":\"团队私有要求：禁止 select * 。\"}]}");
        RuleResolver resolver = withStore(store);

        List<RuleHit> hits = resolver.resolve(Teams.DEFAULT,
                List.of(diff("src/main/resources/mapper/OrderMapper.xml")));

        RuleHit hit = hits.stream().filter(h -> "java-mapper-xml".equals(h.ruleId()))
                .findFirst().orElseThrow();
        assertEquals(RuleLevel.TEAM, hit.level(), "同 id 应由团队层覆盖内置层");
        assertTrue(hit.content().contains("团队私有要求"));
        assertEquals(1, hits.stream().filter(h -> "java-mapper-xml".equals(h.ruleId())).count(),
                "同 id 只应出现一次（覆盖而非叠加）");
    }

    /**
     * 核心不变量：把团队规则清空，审查依然有内置规则可依。
     *
     * <p>若这里失败，说明上三层能「删掉」内置层——那么团队配置被清空一次，
     * 审查就变成没有任何规范可依，而且全程无报错。
     */
    @Test
    void emptiedTeamLayerStillResolvesBuiltin() {
        InMemoryTeamConfigStore store = new InMemoryTeamConfigStore();
        store.saveJson(Teams.DEFAULT, RuleResolver.CONFIG_SCOPE, "{\"rules\":[]}");
        RuleResolver resolver = withStore(store);

        List<RuleHit> hits = resolver.resolve(Teams.DEFAULT,
                List.of(diff("src/main/resources/mapper/OrderMapper.xml")));
        assertTrue(hits.stream().anyMatch(h -> "java-mapper-xml".equals(h.ruleId())
                        && h.level() == RuleLevel.BUILTIN),
                "团队层置空后，内置层必须仍在（不可被覆盖为空）");
    }

    /** 坏配置只降级该层，不能连累内置层。 */
    @Test
    void brokenTeamJsonDoesNotKillBuiltinLayer() {
        InMemoryTeamConfigStore store = new InMemoryTeamConfigStore();
        store.saveJson(Teams.DEFAULT, RuleResolver.CONFIG_SCOPE, "{ this is not json");
        RuleResolver resolver = withStore(store);

        List<RuleHit> hits = resolver.resolve(Teams.DEFAULT,
                List.of(diff("src/main/resources/mapper/OrderMapper.xml")));
        assertFalse(hits.isEmpty(), "团队规则 JSON 损坏时，内置层仍应生效");
    }

    @Test
    void requestLevelRuleWinsAndCanBeNonBuiltin() {
        InMemoryTeamConfigStore store = new InMemoryTeamConfigStore();
        RuleResolver resolver = withStore(store);

        List<RuleHit> hits = resolver.resolve(Teams.DEFAULT,
                List.of(diff("src/main/java/com/x/A.java")), List.of("java-security"));

        RuleHit hit = hits.stream().filter(h -> "java-security".equals(h.ruleId()))
                .findFirst().orElseThrow();
        assertEquals(RuleLevel.REQUEST, hit.level(), "显式指定的规则应为请求级");
        assertEquals("<request>", hit.matchedPattern());
    }

    // ---------------------------------------------------------------- 注入预算

    @Test
    void injectionIsCappedByConfig() {
        RuleResolver resolver = withStore(new InMemoryTeamConfigStore());
        // 一个文件同时命中多种模式时，命中数会被上限截断
        List<RuleHit> hits = resolver.resolve(Teams.DEFAULT, List.of(
                diff("src/main/java/com/x/service/OrderServiceImpl.java"),
                diff("src/main/resources/mapper/OrderMapper.xml"),
                diff("pom.xml"),
                diff("src/main/resources/application.yml"),
                diff("Dockerfile"),
                diff("Jenkinsfile"),
                diff("src/test/java/com/x/AThingTest.java")));

        assertTrue(hits.size() <= 5, "默认单次最多注入 5 条规则，实际 " + hits.size());
        assertFalse(hits.isEmpty());
    }

    @Test
    void truncatedContentIsExplicitlyMarked() {
        InMemoryTeamConfigStore store = new InMemoryTeamConfigStore();
        String big = "x".repeat(5000);
        store.saveJson(Teams.DEFAULT, RuleResolver.CONFIG_SCOPE,
                "{\"rules\":[{\"id\":\"big-rule\",\"title\":\"大规则\","
                        + "\"patterns\":[\"src/main/java/**\"],\"content\":\"" + big + "\"}]}");
        RuleResolver resolver = withStore(store);

        List<RuleHit> hits = resolver.resolve(Teams.DEFAULT,
                List.of(diff("src/main/java/com/x/A.java")), List.of("big-rule"));
        RuleHit hit = hits.get(0);
        assertTrue(hit.truncated(), "超长正文必须被标记为截断");
        assertTrue(hit.render().contains("已截断"), "渲染文本必须显式说明正文被截断");
    }
}
