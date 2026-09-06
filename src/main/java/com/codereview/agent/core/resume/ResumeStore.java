package com.codereview.agent.core.resume;

import java.time.Duration;
import java.util.Optional;

/**
 * 断点续跑存储（崩溃后同 runId 继续，不重跑已完成 Agent）。
 *
 * <p>实现可基于 PostgreSQL（{@code PgResumeStore}，集群部署推荐——多实例共享同一份断点状态，
 * 请求落在任意实例都能续跑）或内存（{@code InMemoryResumeStore}，单机/测试）。
 *
 * <p>替代改造前的 {@code FileResumeStore}：本地 JSON 文件在多机部署下 A 机写的断点 B 机读不到，
 * 且无跨进程并发控制；PG 行级原子写入天然解决两者。
 */
public interface ResumeStore {

    /** 保存（覆盖）一次断点快照。 */
    void save(ResumeState state);

    /** 读取断点（不存在返回空）。 */
    Optional<ResumeState> load(String runId, String teamId);

    /** 审查正常完成时清理断点。 */
    void complete(String runId, String teamId);

    /**
     * 清理超过 {@code maxAge} 未更新的残留断点（崩溃遗留断点的唯一回收路径）。
     * 判据为断点最后更新时间（PG 侧为 updated_at 列）。
     *
     * @param maxAge 超过该时长未更新的断点视为残留；null 或非正数时不清理
     * @return 实际清理的断点数
     */
    int purgeExpired(Duration maxAge);
}
