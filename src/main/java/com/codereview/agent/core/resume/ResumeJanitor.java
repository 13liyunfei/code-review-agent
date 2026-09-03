package com.codereview.agent.core.resume;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 断点残留清理任务（孤儿断点的兜底回收）。
 *
 * <p>{@link FileResumeStore#complete} 只在审查<b>正常完成</b>时被调用。进程被杀、机器重启之后，
 * 若那个 runId 再也不被触发（PR 被关闭 / 合并 / 换了新的 commit），断点 JSON 就会永远躺在磁盘上。
 * 文件存储最容易被忽略的运维问题不是性能、不是一致性，而是这种<b>缓慢的磁盘泄漏</b>：
 * 每次崩溃漏一个，攒几个月就攒出一目录垃圾。
 *
 * <p>为什么不放进 {@code ScheduledScanService}：那是技术债务巡检服务，装配需要
 * {@code GiteaApiClient} / {@code Coordinator} 一整套依赖，且 {@code scan.enabled=false} 时整体跳过。
 * 断点清理是存储自身的生命周期管理，与是否开启巡检无关，必须独立。
 *
 * <p>为什么不用 {@code updatedAt} 而用 mtime、以及为什么不会误删正在进行的审查，
 * 见 {@link FileResumeStore#purgeExpired}。
 */
@Component
public class ResumeJanitor {

    private static final Logger log = LoggerFactory.getLogger(ResumeJanitor.class);

    /**
     * 单轮清理数量超过该值即告警：说明不是偶发崩溃，而是清理链路本身没在正常工作
     * （比如 TTL 配错、或者大量审查卡死后无人重试）。
     */
    private static final int NOISY_THRESHOLD = 100;

    private final FileResumeStore store;
    private final Duration maxAge;

    public ResumeJanitor(FileResumeStore store,
                         @Value("${review.resume.ttl:24h}") Duration maxAge) {
        this.store = store;
        this.maxAge = maxAge;
    }

    /** 每日清理一次残留断点（默认 03:30，避开巡检的 02:00）。 */
    @Scheduled(cron = "${review.resume.cleanup-cron:0 30 3 * * *}")
    public void purge() {
        int removed;
        try {
            removed = store.purgeExpired(maxAge);
        } catch (RuntimeException e) {
            // 清理属于旁路维护，绝不影响审查主链路
            log.warn("[Resume] 残留断点清理异常（本轮跳过）：{}", e.getMessage());
            return;
        }
        if (removed == 0) {
            log.debug("[Resume] 残留断点清理完成：无残留（TTL={}）", maxAge);
        } else if (removed > NOISY_THRESHOLD) {
            log.warn("[Resume] 残留断点清理完成：删除 {} 个（TTL={}），数量异常偏高，请检查是否有审查持续崩溃",
                    removed, maxAge);
        } else {
            log.info("[Resume] 残留断点清理完成：删除 {} 个（TTL={}）", removed, maxAge);
        }
    }
}
