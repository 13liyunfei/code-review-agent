package com.codereview.agent.core.eval;

import com.codereview.agent.core.model.Severity;

/**
 * 基准 ground-truth 单条（人工标注的「这里确实有问题」）。
 *
 * <p><b>为什么要有 labelSource</b>：评审基准的可信度全押在「哪一条是谁标的」上。
 * 机器扫出来的候选与人工确认过的结论**必须可分**——否则 precision/recall 会退化成
 * 「机器 vs 机器」，指标就失去意义（见 {@code tools/bench/label_assist.py} 的说明）。
 *
 * @param ruleId      规则 ID（与审查发现对齐，用于说明"该报什么"）
 * @param category    问题分类（security / logic / performance / style / architecture / …）
 * @param severity    严重级别
 * @param file        文件路径（仓库相对）
 * @param lineStart   起始行（新文件侧）
 * @param lineEnd     结束行
 * @param note        为什么这是真问题（必填，一句话）
 * @param labelSource 标注来源：{@code manual} = 人工读 diff 标的；
 *                    {@code human-confirmed-machine-candidate} = 机器候选 + 人工裁决规则确认
 */
public record BenchGroundTruth(
        String ruleId,
        String category,
        Severity severity,
        String file,
        int lineStart,
        int lineEnd,
        String note,
        String labelSource) {

    /** 是否属于"缺陷类"（相对风格/文档类）——报告里要给出只看缺陷类的另一组指标。 */
    public boolean defectClass() {
        return switch (category == null ? "" : category) {
            case "security", "logic", "concurrency", "resource" -> true;
            default -> false;
        };
    }
}
