package com.codereview.agent.core.resume;

import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.Finding;

import java.util.List;
import java.util.Set;

/**
 * 断点续跑状态（对齐 codex {@code suspend_turn_and_shutdown} + {@code recover_turn_if_idle}）。
 *
 * <p>长 PR 审查中途崩溃（进程被杀 / 机器重启）后，同 {@code runId} 再次发起审查时，
 * 从断点恢复：<b>已完成的 Agent 不重跑</b>（其结果已持久化），只重跑剩余 Agent，
 * 避免大库「推一次 PR 全量重审」的浪费。
 *
 * @param runId       审查运行 ID（与 traceId 对齐）
 * @param prId        PR 标识
 * @param repo        仓库名
 * @param teamId      团队 / 租户
 * @param doneAgents  已完成并落盘的 Agent 类型（恢复时跳过）
 * @param findings    已完成 Agent 产出的发现（恢复时并入结果）
 * @param updatedAt   最近一次保存时间戳
 */
public record ResumeState(
        String runId,
        long prId,
        String repo,
        String teamId,
        Set<AgentType> doneAgents,
        List<Finding> findings,
        long updatedAt) {

    /** 是否还有未完成的 Agent（true=存在不在已完成集合中的 Agent，可继续恢复）。 */
    public boolean hasPending(Set<AgentType> allTypes) {
        return allTypes.stream().anyMatch(t -> !doneAgents.contains(t));
    }
}
