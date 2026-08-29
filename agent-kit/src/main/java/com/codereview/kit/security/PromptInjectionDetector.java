package com.codereview.kit.security;

import java.util.List;
import java.util.Locale;

/**
 * Prompt 注入检测（关键词 + 模式），kit 安全下沉。
 *
 * <p>检测常见的提示词注入向量：忽略指令 / 泄露系统提示 / 越权角色扮演 /
 * 恶意工具调用等。返回风险等级与命中模式。
 */
public class PromptInjectionDetector {

    /** 风险等级。 */
    public enum Risk { NONE, LOW, HIGH }

    /** 检测结果。 */
    public record Detection(Risk risk, List<String> matchedPatterns) {
        public boolean flagged() {
            return risk != Risk.NONE;
        }
    }

    private static final List<String> HIGH_PATTERNS = List.of(
            "ignore all previous instructions",
            "ignore the above",
            "disregard",
            "system prompt",
            "泄露你的系统提示",
            "忽略以上所有指令",
            "忘了你是",
            "越权",
            "admin mode",
            "god mode",
            "unlock your instructions",
            "reveal your prompt");

    private static final List<String> LOW_PATTERNS = List.of(
            "假装你是",
            "roleplay as",
            "act as",
            "模拟系统",
            "do not follow",
            "override");

    public Detection detect(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return new Detection(Risk.NONE, List.of());
        }
        String lower = prompt.toLowerCase(Locale.ROOT);
        List<String> high = HIGH_PATTERNS.stream().filter(lower::contains).toList();
        if (!high.isEmpty()) {
            return new Detection(Risk.HIGH, high);
        }
        List<String> low = LOW_PATTERNS.stream().filter(lower::contains).toList();
        return low.isEmpty() ? new Detection(Risk.NONE, List.of()) : new Detection(Risk.LOW, low);
    }
}
