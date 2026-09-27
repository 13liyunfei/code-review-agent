package com.codereview.agent.core.gate;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 送审闸门配置（{@code review.gate.*}）。
 *
 * <p>默认值一律选「安全的那个」：{@link #enabled 总开关默认开启}——safe-by-default 不能
 * 依赖运维记得打开，否则第一个上线的环境就是没防护的那个。
 *
 * <p>配置示例（application.yml）：
 * <pre>
 *   review:
 *     gate:
 *       enabled: true
 *       exclude:                # 追加排除（gitignore 风格 glob）
 *         - "*.generated.java"  # 不含 / 的规则自动匹配任意深度
 *       include: []             # 非空时成为白名单：不在其中一律拦
 *       allow:                  # 只放开「策略类」闸门（噪声目录 / 用户排除）
 *         - "build-log.txt"
 *       extra-secret-patterns:  # 只增不减：无法用它放行内置清单里的路径
 *         - "config/secrets-*.yaml"
 *       max-diff-chars: 0       # 0 = 关闭单文件体积闸门
 *       allow-list-enabled: false
 * </pre>
 */
@ConfigurationProperties(prefix = "review.gate")
public class PathGateProperties {

    /** 总开关，默认开启。 */
    private boolean enabled = true;

    /** 用户排除规则（glob）。 */
    private List<String> exclude = new ArrayList<>();

    /** 用户包含白名单（glob）。非空时生效：不在其中的文件记为 {@code not_included}。 */
    private List<String> include = new ArrayList<>();

    /**
     * 显式放行规则（glob）。
     *
     * <p><b>只能放开策略类闸门</b>（{@code user_exclude} / {@code not_included} /
     * {@code unsupported_ext} / {@code default_path}）。二进制与密钥属于安全类闸门，
     * 永远无法被放开——「allow 什么都拦不住」和「allow 什么都放得过」是同一个 bug 的两面。
     */
    private List<String> allow = new ArrayList<>();

    /** 追加密钥路径规则（glob）。与内置清单取并集，无法用来放行内置清单里的路径。 */
    private List<String> extraSecretPatterns = new ArrayList<>();

    /**
     * 单文件 diff 字符上限；{@code 0} = 关闭（默认）。
     *
     * <p>默认关闭的理由：这是<b>成本闸门</b>，拦下的代价是漏审。业界有把它默认打开的
     * 实现（超过上下文预算 80% 即判 too_large），但那要求调用方自己先做文件切分；
     * 本项目尚未做切分，默认打开会静默丢文件。开启后原因 {@code too_large} 可查。
     */
    private int maxDiffChars = 0;

    /**
     * 扩展名白名单模式，默认关闭。
     *
     * <p>关闭的理由同样是「静默丢文件比多花 token 更危险」：白名单一旦成为默认，
     * 团队引入一门新语言时，改动会<strong>悄无声息地不被审查</strong>——没有任何报错，
     * 直到有人在线上出事才发现。要开就显式开，并且清楚自己在承担什么。
     */
    private boolean allowListEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getExclude() {
        return exclude;
    }

    public void setExclude(List<String> exclude) {
        this.exclude = exclude == null ? new ArrayList<>() : exclude;
    }

    public List<String> getInclude() {
        return include;
    }

    public void setInclude(List<String> include) {
        this.include = include == null ? new ArrayList<>() : include;
    }

    public List<String> getAllow() {
        return allow;
    }

    public void setAllow(List<String> allow) {
        this.allow = allow == null ? new ArrayList<>() : allow;
    }

    public List<String> getExtraSecretPatterns() {
        return extraSecretPatterns;
    }

    public void setExtraSecretPatterns(List<String> extraSecretPatterns) {
        this.extraSecretPatterns = extraSecretPatterns == null ? new ArrayList<>() : extraSecretPatterns;
    }

    public int getMaxDiffChars() {
        return maxDiffChars;
    }

    public void setMaxDiffChars(int maxDiffChars) {
        this.maxDiffChars = maxDiffChars;
    }

    public boolean isAllowListEnabled() {
        return allowListEnabled;
    }

    public void setAllowListEnabled(boolean allowListEnabled) {
        this.allowListEnabled = allowListEnabled;
    }
}
