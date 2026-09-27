package com.codereview.agent.core.skill;

/**
 * 一条命中的规则。
 *
 * <p>字段里刻意保留 {@code matchedPattern}：只记录「命中了哪条规则」的话，
 * 当规则被误命中（例如 {@code **}{@code /target/**} 打到了不该打的路径）时，
 * 排查要从规则正文反推匹配逻辑；带上命中的 pattern，问题会直接指向那一行配置。
 *
 * @param ruleId         规则标识
 * @param title          规则标题（给模型看的短标题）
 * @param matchedPattern 命中的文件模式（请求级规则为 {@code <request>}）
 * @param level          规则来源层级
 * @param content        规则正文（已按上限截断）
 * @param truncated      正文是否被截断（截断必须显式标注，否则模型会以为规则就这么多）
 */
public record RuleHit(
        String ruleId,
        String title,
        String matchedPattern,
        RuleLevel level,
        String content,
        boolean truncated) {

    /**
     * 紧凑构造器：拒绝缺关键字段的命中项。
     */
    public RuleHit {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId 不能为空");
        }
        if (level == null) {
            throw new IllegalArgumentException("level 不能为 null");
        }
        if (content == null) {
            content = "";
        }
    }

    /**
     * 渲染为注入提示词的一行块。
     *
     * @return 形如 {@code - [builtin:java-mapper-xml] MyBatis Mapper XML 审查要点（命中 **}{@code /*Mapper.xml）} + 正文
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("- [").append(level.code()).append(':').append(ruleId).append("] ")
                .append(title == null ? ruleId : title)
                .append("（命中 ").append(matchedPattern).append("）\n")
                .append(content);
        if (truncated) {
            sb.append("\n  …… （规则正文已截断）");
        }
        return sb.toString();
    }
}
