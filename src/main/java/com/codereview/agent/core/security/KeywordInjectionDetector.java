package com.codereview.agent.core.security;

import com.codereview.kit.security.PromptInjectionDetector;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 基于 <b>agent-kit 基座能力</b> + 领域增强的 Prompt 注入检测器（可离线，零依赖）。
 *
 * <p><b>与 agent-kit 的分工（本仓库作为 agent-kit 首个落地用户的适配实践）：
 * </b>
 * <ol>
 *   <li><b>第一层（基座复用）</b>：委托 {@link PromptInjectionDetector} 的通用模式库，
 *       直接复用基座持续维护的注入特征，本仓库不再重复维护一份通用模式表。</li>
 *   <li><b>第二层（领域增强）</b>：代码审查场景专属的正则模式。基座使用<b>字面量子串</b>匹配，
 *       对 {@code 忽略以上指令}（无「所有」）、{@code developer  mode}（多空格）这类变体
 *       覆盖不到；正则可容忍空白差异与可选词，是基座能力在本场景的必要补充。</li>
 * </ol>
 *
 * <p><b>风险分级的关键适配（重要）：</b>基座把 {@code override}、{@code act as}、
 * {@code 假装你是} 等词判为 {@code Risk.LOW}。但 Java 代码中 {@code @Override} 注解
 * 无处不在——若把 LOW 也当作命中，几乎所有 Java PR 都会被误判为注入攻击而遭拦截。
 * 因此本检测器<b>仅将 {@code HIGH} 升级为拦截</b>，LOW 视为可疑但<b>不拦截</b>，
 * 交由上层语义检测与人工复核处理。
 *
 * <p>纵深防御：本检测器为第一层输入过滤；生产环境可叠加
 * {@code SemanticInjectionDetector}（语义向量）提升绕过抗性。
 */
public class KeywordInjectionDetector implements InjectionDetector {

    /** agent-kit 基座检测器：通用注入模式库与风险分级（HIGH / LOW / NONE）。 */
    private final PromptInjectionDetector kitDetector = new PromptInjectionDetector();

    /**
     * 代码审查场景的领域增强模式（正则）。
     *
     * <p>相比基座的字面量子串匹配，正则能容忍空白差异与可选词，
     * 覆盖 {@code 忽略 以上 指令}、{@code developer  mode} 等基座漏掉的变体。
     */
    private static final List<Pattern> DOMAIN_PATTERNS = List.of(
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
        // 第一层：agent-kit 基座。仅 HIGH 拦截——LOW 会误杀 Java 的 @Override 注解。
        if (kitDetector.detect(input).risk() == PromptInjectionDetector.Risk.HIGH) {
            return true;
        }
        // 第二层：领域正则增强，补齐基座字面量匹配覆盖不到的变体。
        return DOMAIN_PATTERNS.stream().anyMatch(p -> p.matcher(input).find());
    }
}
