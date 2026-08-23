package com.codereview.agent.core.admin.dto;

import java.util.List;

/**
 * 新增 / 编辑业务方自定义 Agent 的请求体。
 *
 * @param name         展示名（如「支付合规审查」）
 * @param description  角色描述
 * @param focusPoints  审查要点清单
 * @param severityBias 严重级别偏好（BLOCKER/MAJOR/MINOR/INFO）
 * @param enabled      是否启用（编辑时携带；新增默认 true）
 * @param version      乐观锁版本号（编辑时必填，与存储 version 比对）
 */
public record CustomAgentRequest(
        String name,
        String description,
        List<String> focusPoints,
        String severityBias,
        Boolean enabled,
        Long version) {
}
