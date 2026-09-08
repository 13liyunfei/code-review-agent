package com.codereview.agent.core.rag;

import com.codereview.agent.core.llm.SimpleHashEmbeddingClient;
import com.codereview.agent.core.memory.ExperienceEntry;
import com.codereview.agent.core.memory.ExperienceLibrary;
import com.codereview.agent.core.memory.ExperienceStage;
import com.codereview.agent.core.memory.ExperienceStore;
import com.codereview.agent.core.memory.InMemoryExperienceLibrary;
import com.codereview.agent.core.memory.RagContextBuilder;
import com.codereview.agent.core.model.CodeDiff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 历史经验回流（记忆闭环「读」侧）端到端测试：
 * {@code RagContextBuilder} 在审查链路把反思沉淀的经验条目以【历史经验参考】分区注入。
 *
 * <p>覆盖：
 *  - 知识 abstain 但经验命中时仍注入（经验通道独立于知识阈值）；
 *  - 命中即刷新 recordHit（spaced-repetition 反遗忘在生产生效）；
 *  - 无匹配经验 / 归档经验不注入（不污染上下文）；
 *  - 团队隔离（teamB 查不到 teamA 的经验）。
 *
 * <p>与 {@link RagContextBuilderTest}（知识通道）互补；知识侧用空 {@link InMemoryKnowledgeStore} 隔离。
 */
class RagContextExperienceInjectionTest {

    private final InMemoryKnowledgeStore emptyKnowledge() {
        return new InMemoryKnowledgeStore(new SimpleHashEmbeddingClient());
    }

    private RagContextBuilder builderWith(ExperienceLibrary lib) {
        return new RagContextBuilder(emptyKnowledge(), new HeuristicReranker(),
                new RagEvaluator(0.0, false), new ExperienceStore(lib));
    }

    private CodeDiff diff(String patch) {
        return new CodeDiff("Demo.java", patch);
    }

    @Test
    void knowledgeAbstainButExperienceHitStillInjected() {
        InMemoryExperienceLibrary lib = new InMemoryExperienceLibrary();
        ExperienceStore store = new ExperienceStore(lib);
        // 复现 3 次 → ACTIVE（同 pattern 证据升级）
        for (int i = 0; i < 3; i++) {
            store.add("teamA", "sql 拼接 注入 风险", "改用 PreparedStatement 参数绑定");
        }
        RagContextBuilder builder = builderWith(lib);

        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(diff("String sql = \"select * from user where id=\" + id; // sql 拼接")));
        assertFalse(ctx.isBlank(), "知识 abstain 时经验命中仍应注入，实际为空");
        assertTrue(ctx.contains("【历史经验参考】"), "应带历史经验分区标题");
        assertTrue(ctx.contains("PreparedStatement"), "命中经验的 advice 应进入上下文");
        assertTrue(ctx.contains("sql 拼接 注入 风险"), "经验 pattern 应可读");
    }

    @Test
    void injectedHitRefreshesSpacedRepetitionClock() {
        InMemoryExperienceLibrary lib = new InMemoryExperienceLibrary();
        ExperienceStore store = new ExperienceStore(lib);
        store.add("teamA", "sec hardcode 密钥", "密钥放配置中心");
        RagContextBuilder builder = builderWith(lib);

        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(diff("String password = \"p@ss\"; // hardcode 密钥")));
        assertTrue(ctx.contains("密钥放配置中心"), "经验应命中注入");

        ExperienceEntry hit = store.get("teamA", 1L).orElseThrow();
        assertTrue(hit.hitCount() >= 1, "注入命中应刷新 hit_count（反遗忘）");
        assertTrue(hit.lastHitAt() > 0, "命中应刷新 lastHitAt");
    }

    @Test
    void noMatchingExperienceStaysBlankWhenKnowledgeAbsent() {
        RagContextBuilder builder = builderWith(new InMemoryExperienceLibrary());
        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(diff("some unrelated refactor rename")));
        assertTrue(ctx.isBlank(), "知识空且无经验命中时应 abstain（返回空串）");
    }

    @Test
    void experienceDoesNotLeakAcrossTeams() {
        InMemoryExperienceLibrary lib = new InMemoryExperienceLibrary();
        ExperienceStore store = new ExperienceStore(lib);
        store.add("teamA", "sql 拼接 注入 风险", "teamA 专属建议 PreparedStatement");
        RagContextBuilder builder = builderWith(lib);

        String ctx = builder.buildContext("teamB", "SecurityAgent",
                List.of(diff("String sql = \"...\" + id; // sql 拼接")));
        assertTrue(ctx.isBlank(), "teamB 不应看到 teamA 的经验");
        assertFalse(ctx.contains("teamA 专属建议"));
    }

    @Test
    void archivedExperienceIsNotInjected() {
        InMemoryExperienceLibrary lib = new InMemoryExperienceLibrary();
        ExperienceStore store = new ExperienceStore(lib);
        store.add("teamA", "R1 sql 拼接 误报样本", "历史建议");
        // 人工误报 2 次 → ARCHIVED（不再参与检索）
        store.recordFeedback("teamA", "R1", true);
        store.recordFeedback("teamA", "R1", true);
        assertTrue(store.get("teamA", 1L).orElseThrow().stage() == ExperienceStage.ARCHIVED);

        RagContextBuilder builder = builderWith(lib);
        String ctx = builder.buildContext("teamA", "SecurityAgent",
                List.of(diff("String sql = \"...\" + id; // sql 拼接")));
        assertTrue(ctx.isBlank(), "归档经验不得再注入检索");
    }
}
