package com.codereview.agent.core.model;

/**
 * 单条审查发现（Finding）。
 *
 * <p>对应文档中 Agent 输出的标准化审查意见，字段与 Agent 间通信协议的
 * payload 保持一致，便于序列化后在消息队列中传递。
 *
 * @param agentType   产出该发现的 Agent 类型
 * @param file        问题所在文件
 * @param lineStart   问题起始行（1-based，0 表示非具体行）
 * @param lineEnd     问题结束行
 * @param severity    严重级别
 * @param category    问题分类（如 security / performance / style）
 * @param ruleId      命中规则 ID（如 SEC-001）
 * @param title       问题标题
 * @param description 问题描述
 * @param suggestion  修复建议
 * @param confidence  置信度（0~1），聚合阶段用于去重时择优保留
 * @param source      发现来源（LLM / Semgrep / CodeQL / RULE 等）
 */
public record Finding(
        AgentType agentType,
        String file,
        int lineStart,
        int lineEnd,
        Severity severity,
        String category,
        String ruleId,
        String title,
        String description,
        String suggestion,
        double confidence,
        String source) implements com.codereview.kit.eval.FindingLike {

    /**
     * 生成去重键：同一文件、同一行区间、同一规则视为同一问题。
     *
     * @return 去重键字符串
     */
    public String dedupKey() {
        return file + "@" + lineStart + "-" + lineEnd + "#" + ruleId;
    }

    /**
     * 工厂方法：基于规则命中的高置信发现（确定性检测）。
     *
     * @param agentType   产出 Agent
     * @param file        文件
     * @param line        行号
     * @param category    分类
     * @param ruleId      规则 ID
     * @param title       标题
     * @param description 描述
     * @param suggestion  建议
     * @return 高置信（0.95）的规则型发现
     */
    public static Finding ruleBased(AgentType agentType, String file, int line,
                                    String category, String ruleId, String title,
                                    String description, String suggestion) {
        return new Finding(agentType, file, line, line, Severity.MAJOR,
                category, ruleId, title, description, suggestion, 0.95, "RULE");
    }
}
