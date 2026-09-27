package com.codereview.agent.core.gate;

/**
 * 送审闸门的判定原因——每一道闸门对应一个取值，供「按原因聚合」与事后追责。
 *
 * <p>设计要点（借鉴 alibaba/open-code-review 的 {@code internal/agent/selection.go}
 * <b>六道闸门</b>的分层思路，只借鉴设计、不抄实现）：
 * <ul>
 *   <li><b>原因是枚举而不是字符串</b>：字符串原因迟早会出现 {@code .env} / {@code env}
 *       两套写法，聚合时分裂成两行，指标随即失去意义；</li>
 *   <li><b>{@link #blocked()} 显式标注「这算不算拦截」</b>：除 {@link #ADMITTED} 外全部计入拦截统计，
 *       避免「新增一道闸门却忘了加进统计」这类静默漏统计——那种缺陷在监控上看不出来，却会让
 *       「被拦掉的文件」凭空消失。</li>
 * </ul>
 */
public enum GateReason {

    /** 通过全部闸门，进入送审清单。 */
    ADMITTED("admitted", false),

    /** 二进制 / 富媒体文件：内容是字节流，喂给模型只有噪声与 token 成本。 */
    BINARY("binary", true),

    /**
     * 密钥与凭据路径。
     *
     * <p><b>安全类闸门</b>：优先级最高，用户的 include / exclude / allow 一律无法覆盖。
     */
    SECRET_EXCLUDE("secret_exclude", true),

    /** 命中用户配置的排除规则（策略类闸门，可被 allow 放开）。 */
    USER_EXCLUDE("user_exclude", true),

    /** 用户配置了 include 白名单，但本文件不在其中（策略类闸门）。 */
    NOT_INCLUDED("not_included", true),

    /** 扩展名不在可审查集合内——仅当白名单模式显式开启时才可能发生（策略类闸门）。 */
    UNSUPPORTED_EXT("unsupported_ext", true),

    /** 构建产物 / 依赖 / IDE 元数据目录（策略类闸门，可被 allow 放开或按目录关闭）。 */
    DEFAULT_PATH("default_path", true),

    /**
     * 单文件 diff 超过配置上限。
     *
     * <p>这是<b>成本闸门</b>而不是安全闸门：拦下它会牺牲召回率，因此默认关闭
     * （见 {@code review.gate.max-diff-chars}），开启后原因可查、可回滚。
     */
    TOO_LARGE("too_large", true);

    private final String code;
    private final boolean blocked;

    GateReason(String code, boolean blocked) {
        this.code = code;
        this.blocked = blocked;
    }

    /** 稳定的机器可读编码（日志、轨迹、指标聚合都用它，不要用 {@code name()}）。 */
    public String code() {
        return code;
    }

    /** 该原因是否代表「被拦下」。 */
    public boolean blocked() {
        return blocked;
    }
}
