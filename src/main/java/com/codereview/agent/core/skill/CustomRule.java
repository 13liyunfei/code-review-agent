package com.codereview.agent.core.skill;

import java.time.Instant;

/**
 * 团队自定义审查规则（持久化模型）。
 *
 * <p>由前端「Skills 市场 → 新增自定义规则」提交，保存到本地 JSON，
 * 重启后自动加载并生效，无需改代码即可让审查系统识别团队特有规范。
 *
 * @param id          规则唯一 ID
 * @param name        规则名（展示用）
 * @param category    归属审查维度（security/logic/performance/style/architecture）
 * @param severity    严重级别（BLOCKER/MAJOR/MINOR/INFO）
 * @param pattern     匹配正则（对整个新增/上下文行做 find 匹配）
 * @param title       发现标题
 * @param description 问题描述
 * @param suggestion  修复建议
 * @param createdAt   创建时间
 */
public record CustomRule(
        String id,
        String name,
        String category,
        String severity,
        String pattern,
        String title,
        String description,
        String suggestion,
        Instant createdAt) {
}
