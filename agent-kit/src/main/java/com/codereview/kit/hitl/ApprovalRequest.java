package com.codereview.kit.hitl;

import java.time.Instant;

/**
 * 人工审批请求（HITL 中断点：Agent 执行到关键步骤停下来等人）。
 *
 * @param requestId 请求标识
 * @param task      待审批事项描述
 * @param payload   附加上下文（如变更内容 / 建议修复）
 * @param status    PENDING / APPROVED / REJECTED / TIMEOUT
 * @param createdAt 创建时间
 */
public record ApprovalRequest(String requestId, String task, String payload,
                              Status status, Instant createdAt) {

    public enum Status { PENDING, APPROVED, REJECTED, TIMEOUT }
}
