package com.codereview.agent.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 记忆遗忘维护任务（经验条目 TTL 衰减 + 物理清理）。
 *
 * <p>两层遗忘（对齐五态生命周期的遗忘侧）：
 * <ol>
 *   <li><b>软删（TTL 衰减）</b>：CANDIDATE/ACTIVE 条目超过 {@code experience-idle}
 *       无命中/无更新 → ARCHIVED，退出检索注入面。被反复使用的条目每次命中都刷新
 *       updatedAt（spaced repetition），因此不会误删常用经验——遗忘只作用于「记忆衰退」；
 *       </li>
 *   <li><b>硬删</b>：ARCHIVED 超过 {@code purge-after} → 物理删除，不可恢复。</li>
 * </ol>
 *
 * <p>跨团队执行（团队集合由仓库映射动态产生，无固定枚举）。与 {@link ResumeJanitor}
 * 错峰（默认 03:40，避开断点清理 03:30 与巡检 02:00）。
 */
@Component
public class MemoryMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceScheduler.class);

    private final ExperienceLibrary library;
    private final Duration idle;
    private final Duration purgeAfter;

    public MemoryMaintenanceScheduler(ExperienceLibrary library,
                                      @Value("${review.memory.experience-idle:180d}") Duration idle,
                                      @Value("${review.memory.purge-after:90d}") Duration purgeAfter) {
        this.library = library;
        this.idle = idle;
        this.purgeAfter = purgeAfter;
    }

    /** 每日清理一次（默认 03:40）。 */
    @Scheduled(cron = "${review.memory.cleanup-cron:0 40 3 * * *}")
    public void maintain() {
        try {
            int archived = library.archiveIdleAll(idle);
            int purged = library.purgeArchivedAll(purgeAfter);
            if (archived + purged > 0) {
                log.info("[Memory] 记忆遗忘维护完成：TTL 归档 {} 条（idle={}），物理清理 {} 条（purgeAfter={}）",
                        archived, idle, purged, purgeAfter);
            } else {
                log.debug("[Memory] 记忆遗忘维护完成：无需要遗忘的条目（idle={}, purgeAfter={}）", idle, purgeAfter);
            }
        } catch (Exception e) {
            log.warn("[Memory] 记忆遗忘维护异常（本轮跳过）：{}", e.getMessage());
        }
    }
}
