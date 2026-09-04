package com.codereview.agent.core.security;

/**
 * diff 输入面注入检测器（关键词 + 隐写字符组合），实现 {@link InjectionDetector}。
 *
 * <p><b>与 {@link DiffInputGuard} 的分工</b>：本类只回答「是否命中」（供
 * {@link InjectionDetector#detect} 的布尔接口与既有调用方使用，如
 * {@code DeclarativeReviewAgent} 的逐文件标注）；需要文件级定位、BLOCK/TAG 分级与
 * 语义复核时用 {@link DiffInputGuard#assess}。
 *
 * <p>相对历史 {@link KeywordInjectionDetector} 单 bean 的增强点：<b>隐写字符检测</b>
 * （零宽 / Bidi / 危险控制符）。关键词正则作用在可见字符上，攻击者用零宽字符拆词
 * （{@code ignore\u200Bprevious}）即可绕过；本组合把不可见字符作为确定性命中补充进去，
 * 使所有消费 {@code InjectionDetector} bean 的路径（自定义 Agent 逐文件标注等）同时具备
 * 该能力，无需逐处接线。
 */
public class DiffInjectionDetector implements InjectionDetector {

    private final KeywordInjectionDetector keyword;
    private final StegInjectionScanner steg;

    public DiffInjectionDetector() {
        this(new KeywordInjectionDetector(), new StegInjectionScanner());
    }

    DiffInjectionDetector(KeywordInjectionDetector keyword, StegInjectionScanner steg) {
        this.keyword = keyword;
        this.steg = steg;
    }

    @Override
    public boolean detect(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        return keyword.detect(input) || !steg.scan(input).isEmpty();
    }
}
