package com.codereview.agent.core.memory;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 经验条目存取与生命周期（记忆升级 + 遗忘的持久化后端接口）。
 *
 * <p>实现可为 PostgreSQL（{@code PgExperienceLibrary}，集群共享）
 * 或内存（{@code InMemoryExperienceLibrary}，单机/测试）。升级/遗忘规则见
 * {@link ExperienceStage}；越用越不掉（spaced repetition）与 TTL 遗忘在此落实。
 */
public interface ExperienceLibrary {

    /**
     * 反思沉淀/复现一条经验：新 pattern 插入（CANDIDATE），同 pattern 再次出现
     * = 跨 PR 复现证据 +1，复现达到阈值自动升级 ACTIVE。
     */
    void upsertReflection(String teamId, String pattern, String advice);

    /**
     * 人工反馈证据：规则命中该 pattern 前缀的经验条目。
     *
     * @param falsePositive true=人工误报（负证据，累计达阈值降级 ARCHIVED）；
     *                      false=人工正报（正证据，立即 ACTIVE）
     */
    void recordFeedback(String teamId, String ruleId, boolean falsePositive);

    /** 检索命中：刷新 lastHitAt / hitCount（spaced-repetition 反遗忘）。 */
    void recordHit(String teamId, String pattern);

    /** 检索视图：未遗忘（CANDIDATE + ACTIVE）的条目。 */
    List<ExperienceEntry> list(String teamId);

    /** 管理视图：全部条目（含归档），按最近更新倒序。 */
    List<ExperienceEntry> listAll(String teamId);

    /** 按 ID 定位（管理操作）。 */
    Optional<ExperienceEntry> get(String teamId, long id);

    /** 当前未遗忘条目数（与改造前 {@code size} 语义一致）。 */
    int size(String teamId);

    /**
     * 遗忘（软删）：超过 {@code idle} 无命中/无更新的 CANDIDATE/ACTIVE → ARCHIVED。
     *
     * @return 归档条数
     */
    int archiveIdle(String teamId, Duration idle);

    /**
     * 遗忘（硬删）：ARCHIVED 超过 {@code purgeAfter} 的条目物理删除。
     *
     * @return 删除条数
     */
    int purgeArchived(String teamId, Duration purgeAfter);

    /** 全团队版本的 {@link #archiveIdle}（维护任务按存储级清理，团队集合动态无枚举）。 */
    int archiveIdleAll(Duration idle);

    /** 全团队版本的 {@link #purgeArchived}。 */
    int purgeArchivedAll(Duration purgeAfter);

    /** 管理操作：单条归档（手动遗忘）。 */
    boolean archive(String teamId, long id);

    /** 管理操作：单条物理删除。 */
    boolean purge(String teamId, long id);
}
