package com.codereview.agent.core.skill;

/**
 * 规则来源层级（四级覆盖链，优先级从高到低）。
 *
 * <p>对齐 alibaba/open-code-review 的规则四层优先级（{@code --rule} 参数 &gt; 项目
 * {@code .opencodereview/rule.json} &gt; 用户全局规则 &gt; 内置 system 层），只借鉴分层设计。
 *
 * <p>关键不变量：<b>内置层不可被覆盖为空</b>。上三层只做「叠加与覆盖同 id 条目」，
 * 永远删不掉内置规则——否则团队把规则配置清空一次，审查就变成「没有规范可依」，
 * 而这种失效是完全静默的（审查照常跑，只是不再报规则问题）。
 */
public enum RuleLevel {

    /** 请求级：本次审查显式指定的规则（优先级最高）。 */
    REQUEST("request", 0),

    /** 团队级：按团队隔离存储的规则。 */
    TEAM("team", 1),

    /** 全局级：跨团队共享的规则。 */
    GLOBAL("global", 2),

    /** 内置层：随代码发布的规则文档，永远存在。 */
    BUILTIN("builtin", 3);

    private final String code;
    private final int order;

    RuleLevel(String code, int order) {
        this.code = code;
        this.order = order;
    }

    /** 稳定的机器可读编码（轨迹与日志用）。 */
    public String code() {
        return code;
    }

    /** 数值越小优先级越高。 */
    public int order() {
        return order;
    }
}
