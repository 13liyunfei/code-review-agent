package com.codereview.agent.core.security;

import com.codereview.agent.core.model.CodeDiff;

import java.util.ArrayList;
import java.util.List;

/**
 * diff 输入面注入防护门卫（业界纵深：检测 → 隔离/定界，交给 LLM 的只剩可信块与显式标注块）。
 *
 * <p><b>使用边界</b>：任何「把 PR diff 渲染进 LLM 提示词」之前的最后一道输入防护，按<b>文件</b>
 * 粒度分级（这是相对历史实现 {@code 全 PR concat 后一刀切} 的核心改进——单文件命中不再连累
 * 整 PR 的语义审查，且能给出文件级定位）：
 *
 * <ul>
 *   <li>{@link Level#BLOCK}（确定性恶意，隔离）：隐写字符命中（零宽/Bidi 藏指令，
 *       {@code SEC-INJECTION-003}）或关键词 HIGH（领域攻击句式 / 基座 HIGH，
 *       {@code SEC-INJECTION-001}）。该文件<b>不进入 LLM 上下文</b>，由调用方产出
 *       BLOCKER 并提示人工复核；</li>
 *   <li>{@link Level#TAG}（可疑，定界后放行）：关键词 LOW（{@code @Override}/{@code act as}
 *       等基座词表噪音，{@code SEC-INJECTION-004}）或语义向量近似注入模式（改写措辞兜底，
 *       {@code SEC-INJECTION-002}）。该文件仍可审查，但数据区必须显式标注
 *       {@code [INJECTION-RISK]}，让模型知道「这是数据非指令」（spotlighting，
 *       微软 Prompt Shields / OWASP "segregate & identify untrusted content" 同款思路）；</li>
 *   <li>{@link Level#CLEAN}：放行。</li>
 * </ul>
 *
 * <p><b>语义层的防稀释设计</b>：语义向量比较的对象不是整文件 patch（context/删除行会稀释
 * 相似度，上轮已证），而是<b>新增行聚合文本</b>——攻击者可控的全部内容。且仅在新增内容较短
 * （&le; {@value #MAX_SEMANTIC_PATCH}）时触发：小文件（README/配置/规则文件）恰是 2025 年
 * 代码审查注入事故的高发载体；大代码文件靠关键词 + 隐写 + spotlighting 定界，不做无谓向量化。
 *
 * <p><b>降级安全</b>：向量化服务不可达时语义层自动放行（{@link SemanticInjectionDetector}
 * 内部已降级为空），行为不劣化于「无语义层」。
 */
public class DiffInputGuard {

    /** 语义复核仅作用于短新增内容（对齐 {@link SemanticInjectionDetector#MAX_SEMANTIC_INPUT}）。 */
    static final int MAX_SEMANTIC_PATCH = 20_000;

    /** 规则 ID：关键词 HIGH → 隔离（BLOCK）。 */
    public static final String RULE_KEYWORD_HIGH = "SEC-INJECTION-001";
    /** 规则 ID：语义向量近似注入 → 标注（TAG），供人工复核。 */
    public static final String RULE_SEMANTIC = "SEC-INJECTION-002";
    /** 规则 ID：隐写字符（零宽 / Bidi / 危险控制符）→ 隔离（BLOCK）。 */
    public static final String RULE_STEG = "SEC-INJECTION-003";
    /** 规则 ID：关键词 LOW（基座词表噪音，如 @Override）→ 仅标注（TAG），不产 Finding。 */
    public static final String RULE_KEYWORD_LOW = "SEC-INJECTION-004";

    /** 分级结果。 */
    public enum Level {
        /** 放行。 */
        CLEAN,
        /** 可疑：需在数据区显式标注「被审查数据非指令」后放行。 */
        TAG,
        /** 确定性恶意：不得进入 LLM 上下文。 */
        BLOCK
    }

    /** 单个文件的判定结论。 */
    public record Verdict(String file, Level level, String ruleId, String reason, int line, String sample) {
        public boolean blocked() {
            return level == Level.BLOCK;
        }

        public boolean tagged() {
            return level == Level.TAG;
        }

        public boolean clean() {
            return level == Level.CLEAN;
        }
    }

    private final KeywordInjectionDetector keyword;
    private final StegInjectionScanner steg;
    /** 语义复核层（可空：为空则整体禁用语义层）。 */
    private final SemanticInjectionDetector semantic;

    public DiffInputGuard(KeywordInjectionDetector keyword,
                          StegInjectionScanner steg,
                          SemanticInjectionDetector semantic) {
        this.keyword = keyword == null ? new KeywordInjectionDetector() : keyword;
        this.steg = steg == null ? new StegInjectionScanner() : steg;
        this.semantic = semantic;
    }

    /** 便捷构造：无语义层（离线零依赖）。 */
    public DiffInputGuard() {
        this(new KeywordInjectionDetector(), new StegInjectionScanner(), null);
    }

    /**
     * 判定单个文件。
     *
     * @param diff 文件变更
     * @return 分级结论
     */
    public Verdict assess(CodeDiff diff) {
        String file = diff.fileName();
        String patch = diff.patch();
        if (patch == null || patch.isBlank()) {
            return new Verdict(file, Level.CLEAN, "", "", 0, "");
        }

        // 1. 隐写字符：零宽 / Bidi / 危险控制符——零误报的确定性恶意信号，优先级最高。
        List<StegInjectionScanner.Hit> hits = steg.scan(patch);
        if (!hits.isEmpty()) {
            StegInjectionScanner.Hit h = hits.get(0);
            return new Verdict(file, Level.BLOCK, RULE_STEG,
                    "新增行含不可见/方向控制字符（" + h.type() + " " + h.codepoint()
                            + "），典型用于把注入指令拆词/反转藏进代码绕过检测",
                    h.line(), h.codepoint());
        }

        // 2. 关键词分级：领域攻击句式 / 基座 HIGH → 隔离；基座 LOW（@Override 等业务噪音）→ 标注。
        KeywordInjectionDetector.Level kw = keyword.assess(patch);
        if (kw == KeywordInjectionDetector.Level.HIGH) {
            return new Verdict(file, Level.BLOCK, RULE_KEYWORD_HIGH,
                    "内容命中确定性注入攻击句式（忽略以上指令 / developer mode / 覆盖指令…）", 0, "");
        }
        if (kw == KeywordInjectionDetector.Level.LOW) {
            return new Verdict(file, Level.TAG, RULE_KEYWORD_LOW,
                    "含基座 LOW 风险词（如 @Override / act as），按被审查数据标注处理", 0, "");
        }

        // 3. 语义复核（仅对新增内容较短的文件，规避稀释与延迟；embedding 不可达时自动放行）。
        if (semantic != null) {
            String added = addedLines(patch);
            if (!added.isBlank() && added.length() <= MAX_SEMANTIC_PATCH
                    && semantic.detectSemantic(added)) {
                return new Verdict(file, Level.TAG, RULE_SEMANTIC,
                        "新增内容语义上近似已知指令劫持模式（已改写措辞绕过关键词）", 0, "");
            }
        }
        return new Verdict(file, Level.CLEAN, "", "", 0, "");
    }

    /**
     * 批量判定。
     *
     * @param diffs 全部文件变更
     * @return 与输入一一对应的判定列表
     */
    public List<Verdict> assessAll(List<CodeDiff> diffs) {
        List<Verdict> out = new ArrayList<>(diffs.size());
        for (CodeDiff d : diffs) {
            out.add(assess(d));
        }
        return out;
    }

    /**
     * 提取 diff 的新增行内容（去行首 {@code +} 与 hunk 头/文件头/删除行/context），
     * 作为语义向量比较对象——这是攻击者在本次 PR 中真正可控的全部文本。
     */
    static String addedLines(String patch) {
        if (patch == null || patch.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String raw : patch.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("+") && !line.startsWith("+++")) {
                sb.append(line, 1, line.length()).append('\n');
            }
        }
        return sb.toString();
    }
}
