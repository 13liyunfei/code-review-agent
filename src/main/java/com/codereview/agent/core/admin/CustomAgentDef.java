package com.codereview.agent.core.admin;

import java.time.Instant;
import java.util.List;

/**
 * 业务方自定义审查 Agent 的声明式定义（持久化模型）。
 *
 * <p>自定义 Agent 不开放代码/工具调用，仅允许声明「角色描述 + 审查要点 + 严重级别偏好」，
 * 由 {@link DeclarativeReviewAgent} 套用代码硬编码的不可覆盖系统指令骨架渲染提示词。
 * 定义按 teamId 隔离，落盘于 {@code data-dir/<teamId>/custom-agents.json}。
 *
 * @param id           唯一 ID（ca- + 时间戳 + 短 UUID）
 * @param teamId       所属团队（隔离键）
 * @param name         展示名（如「支付合规审查」）
 * @param description  角色描述（注入系统指令的**固定骨架**内容槽，非可覆盖区）
 * @param focusPoints  审查要点清单（逐条作为审查维度提示）
 * @param severityBias 默认严重级别偏好（BLOCKER/MAJOR/MINOR/INFO）
 * @param enabled      是否启用（默认 true）
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 * @param version      乐观锁版本号（编辑防并发覆盖）
 */
public record CustomAgentDef(
        String id,
        String teamId,
        String name,
        String description,
        List<String> focusPoints,
        String severityBias,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    /** 构造新建实例（version=1，enabled=true）。 */
    public static CustomAgentDef create(String id, String teamId, String name,
                                        String description, List<String> focusPoints, String severityBias) {
        Instant now = Instant.now();
        return new CustomAgentDef(id, teamId, name, description, focusPoints,
                severityBias, true, now, now, 1L);
    }

    /** 拷贝式更新（version+1，updatedAt=now），启用态沿用入参。 */
    public CustomAgentDef withUpdate(String name, String description,
                                     List<String> focusPoints, String severityBias, boolean enabled) {
        return new CustomAgentDef(id, teamId, name, description, focusPoints,
                severityBias, enabled, createdAt, Instant.now(), version + 1);
    }
}
