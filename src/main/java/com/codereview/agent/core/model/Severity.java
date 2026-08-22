package com.codereview.agent.core.model;

/**
 * 问题严重级别（分级定档）。
 *
 * <p>依据文档设计，审查结论按严重程度分为四档：
 * <ul>
 *     <li>{@link #BLOCKER} 必须修复：安全漏洞、逻辑错误等阻塞性问题；</li>
 *     <li>{@link #MAJOR}   建议修复：性能隐患、架构问题等；</li>
 *     <li>{@link #MINOR}   可选优化：代码风格、注释等；</li>
 *     <li>{@link #INFO}    提示信息：仅作参考。</li>
 * </ul>
 *
 * <p>{@code priority} 越大代表越严重，聚合阶段用于冲突仲裁（严重级别高者优先）。
 */
public enum Severity {

    /** 阻塞级：必须修复。 */
    BLOCKER(4),
    /** 严重级：建议修复。 */
    MAJOR(3),
    /** 轻微级：可选优化。 */
    MINOR(2),
    /** 提示级：仅供参考。 */
    INFO(1);

    /** 优先级权重，数值越大越严重。 */
    private final int priority;

    Severity(int priority) {
        this.priority = priority;
    }

    /**
     * 获取严重级别权重，用于聚合阶段的冲突仲裁。
     *
     * @return 优先级数值
     */
    public int getPriority() {
        return priority;
    }

    /**
     * 判断当前级别是否比另一个级别更严重。
     *
     * @param other 待比较的级别
     * @return 若当前更严重返回 true
     */
    public boolean isMoreSevereThan(Severity other) {
        return this.priority > other.priority;
    }
}
