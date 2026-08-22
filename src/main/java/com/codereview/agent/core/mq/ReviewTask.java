package com.codereview.agent.core.mq;

import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.ReviewContext;

import java.util.List;

/**
 * 派发给单个 Agent 的审查任务消息体（经消息队列传输）。
 *
 * @param prId    PR 标识
 * @param diffs   代码变更列表
 * @param context 审查上下文
 */
public record ReviewTask(long prId, List<CodeDiff> diffs, ReviewContext context) {
}
