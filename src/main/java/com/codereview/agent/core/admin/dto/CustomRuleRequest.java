package com.codereview.agent.core.admin.dto;

/**
 * 新增/更新团队自定义规则的请求体。
 *
 * @param name        规则名
 * @param category    归属维度（security/logic/performance/style/architecture）
 * @param severity    严重级别（BLOCKER/MAJOR/MINOR/INFO）
 * @param pattern     匹配正则
 * @param title       发现标题
 * @param description 问题描述
 * @param suggestion  修复建议
 */
public record CustomRuleRequest(
        String name,
        String category,
        String severity,
        String pattern,
        String title,
        String description,
        String suggestion) {
}
