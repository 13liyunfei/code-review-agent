package com.codereview.agent.core.store;

import com.codereview.agent.core.admin.InMemoryKnowledgeMetaStore;
import com.codereview.agent.core.admin.KnowledgeMetaStore;
import com.codereview.agent.core.calibration.CalibrationStore;
import com.codereview.agent.core.feedback.FeedbackListener;
import com.codereview.agent.core.feedback.FeedbackStore;
import com.codereview.agent.core.feedback.InMemoryFeedbackStore;
import com.codereview.agent.core.history.InMemoryReviewHistoryStore;
import com.codereview.agent.core.history.ReviewHistoryStore;
import com.codereview.agent.core.memory.ExperienceLibrary;
import com.codereview.agent.core.memory.InMemoryExperienceLibrary;
import com.codereview.agent.core.resume.InMemoryResumeStore;
import com.codereview.agent.core.resume.ResumeStore;
import com.codereview.agent.core.trajectory.InMemoryTrajectoryStore;
import com.codereview.agent.core.trajectory.TrajectoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 状态存储装配（多机集群化的核心开关）。
 *
 * <p>把改造前散落在 {@code data-dir} 本地 JSON 文件的状态（断点/经验/反馈/历史/校准/
 * 团队配置/轨迹/知识元数据）统一收敛到「可插拔后端」：
 * <ul>
 *   <li>{@code pgvector.enabled=true}（集群前提：PG + pgvector）→ 全部走 PostgreSQL 实现，
 *       多实例读写同一份状态，A 机沉淀 B 机可见；</li>
 *   <li>否则 → 全部回退内存实现并告警（单机/测试，重启丢失）。</li>
 * </ul>
 *
 * <p>业务门面（SkillRegistry / CustomAgentStore / ExperienceStore 等）只依赖接口，
 * 由 {@code ReviewAgentConfig} 组装。该结构使「无本地文件」成为系统默认——审查进程无本地状态。
 */
@Configuration
public class StateStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(StateStoreConfig.class);

    // ===================== PostgreSQL（pgvector.enabled=true） =====================

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
    public ResumeStore pgResumeStore(PgDb pgDb) {
        return new PgResumeStore(pgDb);
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
    public TrajectoryStore pgTrajectoryStore(PgDb pgDb) {
        return new PgTrajectoryStore(pgDb);
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
    public TeamConfigStore pgTeamConfigStore(PgDb pgDb) {
        return new PgTeamConfigStore(pgDb);
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
    public CalibrationStore pgCalibrationStore(PgDb pgDb) {
        return new PgCalibrationStore(pgDb);
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
    public ExperienceLibrary pgExperienceLibrary(PgDb pgDb) {
        return new PgExperienceLibrary(pgDb);
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
    public FeedbackStore pgFeedbackStore(PgDb pgDb, ObjectProvider<FeedbackListener> listeners) {
        return new PgFeedbackStore(pgDb, listeners.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
    public ReviewHistoryStore pgReviewHistoryStore(PgDb pgDb) {
        return new PgReviewHistoryStore(pgDb);
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
    public KnowledgeMetaStore pgKnowledgeMetaStore(PgDb pgDb) {
        return new PgKnowledgeMetaStore(pgDb);
    }

    // ===================== 内存回退（pgvector.enabled=false / 缺失） =====================

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "false", matchIfMissing = true)
    public ResumeStore inMemoryResumeStore() {
        log.warn("pgvector 未启用：断点存储回退内存（单机/测试，多实例部署必须启用 PostgreSQL）");
        return new InMemoryResumeStore();
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "false", matchIfMissing = true)
    public TrajectoryStore inMemoryTrajectoryStore() {
        return new InMemoryTrajectoryStore();
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "false", matchIfMissing = true)
    public TeamConfigStore inMemoryTeamConfigStore() {
        log.warn("pgvector 未启用：团队配置存储回退内存（多实例部署必须启用 PostgreSQL）");
        return new InMemoryTeamConfigStore();
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "false", matchIfMissing = true)
    public CalibrationStore inMemoryCalibrationStore() {
        return new InMemoryCalibrationStore();
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "false", matchIfMissing = true)
    public ExperienceLibrary inMemoryExperienceLibrary() {
        log.warn("pgvector 未启用：经验库回退内存（单机/测试，多实例部署必须启用 PostgreSQL）");
        return new InMemoryExperienceLibrary();
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "false", matchIfMissing = true)
    public FeedbackStore inMemoryFeedbackStore(ObjectProvider<FeedbackListener> listeners) {
        return new InMemoryFeedbackStore(listeners.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "false", matchIfMissing = true)
    public ReviewHistoryStore inMemoryReviewHistoryStore() {
        return new InMemoryReviewHistoryStore();
    }

    @Bean
    @ConditionalOnProperty(name = "pgvector.enabled", havingValue = "false", matchIfMissing = true)
    public KnowledgeMetaStore inMemoryKnowledgeMetaStore() {
        return new InMemoryKnowledgeMetaStore();
    }
}
