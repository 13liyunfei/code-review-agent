package com.codereview.agent.core.security;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 基于关键词 / 正则的 Prompt 注入检测器（可离线，零依赖）。
 *
 * <p>覆盖文档列举的典型注入句式（中英文），作为成本最低的第一道防线；
 * 生产环境可叠加语义向量检测（{@code SemanticInjectionDetector}）提升绕过抗性。
 */
public class KeywordInjectionDetector implements InjectionDetector {

    /** 典型注入模式（中英文）。 */
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("(?i)ignore\\s+(all\\s+)?(previous|above|prior)\\s+instructions"),
            Pattern.compile("(?i)忽略\\s*以上\\s*(所有)?\\s*指令"),
            Pattern.compile("(?i)forget\\s+everything\\s+you\\s+were\\s+told"),
            Pattern.compile("(?i)你现在?\\s*(处于|进入)?\\s*开发者?\\s*模式"),
            Pattern.compile("(?i)system\\s*[:：]\\s*new\\s+instructions"),
            Pattern.compile("(?i)developer\\s+mode"),
            Pattern.compile("(?i)覆盖\\s*(你|系统|所有)\\s*指令")
    );

    @Override
    public boolean detect(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        return PATTERNS.stream().anyMatch(p -> p.matcher(input).find());
    }
}
