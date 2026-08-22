package com.codereview.agent.core.autofix;

import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.llm.aiservice.CodeReviewAiService;
import com.codereview.agent.core.llm.aiservice.FixResultDto;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewReport;
import com.codereview.agent.core.tools.ToolExposure;
import com.codereview.agent.core.tools.ToolGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 自动修复引擎。
 *
 * <p>针对审查发现，产出可一键应用的修复建议：
 * <ul>
 *   <li><b>确定性替换</b>：对已知规则（如 printStackTrace / System.out / SELECT *）
 *       给出固定修复片段；</li>
 *   <li><b>LLM 生成（LangChain4j AiServices）</b>：其余发现交由大模型生成针对性修复代码，
 *       结构化输出（{@link FixResultDto}），失败时回退文本路径；</li>
 *   <li>输出 <code>suggestion</code> 格式 Markdown 代码块，可直接回写为 PR 行内建议。</li>
 * </ul>
 *
 * <p>说明：精确行内补丁（patch）需要取回 PR 文件原文做差异替换，本引擎聚焦「修复建议生成」，
 * 输出 Gitea/PR 兼容的 suggestion 代码块，开发者可一键采纳。
 */
@Component
public class AutoFixEngine {

    private static final Logger log = LoggerFactory.getLogger(AutoFixEngine.class);

    private final LlmClient llmClient;
    private final CodeReviewAiService aiService;
    /** 运行模式（SUGGEST 默认；APPLY 需沙箱可用）。 */
    private final AutoFixMode mode;
    /** 隔离沙箱（只读工作区、禁外网）是否可用。 */
    private final boolean sandboxAvailable;
    /** 工具门控（可空：为 null 时 DEFERRED 一律拒绝，fail-closed）。 */
    private final ToolGate toolGate;

    /** 确定性修复模板：ruleId -> (i18n key, 修复代码片段)。 */
    private static final Map<String, String[]> DETERMINISTIC = Map.ofEntries(
            Map.entry("LOGIC-002", new String[]{"autofix.fix.log2logger",
                    "log.error(\"xxx 发生异常\", e);"}),
            Map.entry("LOGIC-003", new String[]{"autofix.fix.sysout2logger",
                    "log.info(\"{}\", value);"}),
            Map.entry("PERF-001", new String[]{"autofix.fix.explicitColumns",
                    "SELECT id, name, status FROM orders WHERE id = ?"}),
            Map.entry("STYLE-002", new String[]{"autofix.fix.todo2issue",
                    "// 已登记 ISSUE-xxx，移除临时 TODO"})
    );

    /**
     * 测试 / 独立构造（默认 SUGGEST 模式、无沙箱，fail-closed）。
     */
    public AutoFixEngine(LlmClient llmClient, CodeReviewAiService aiService) {
        this(llmClient, aiService, AutoFixMode.SUGGEST, false, null);
    }

    /**
     * 测试构造（显式指定模式与沙箱可用性，无工具门控）。
     */
    public AutoFixEngine(LlmClient llmClient, CodeReviewAiService aiService,
                         AutoFixMode mode, boolean sandboxAvailable) {
        this(llmClient, aiService, mode, sandboxAvailable, null);
    }

    /**
     * Spring 装配构造（模式 + 沙箱探测 + 工具门控）。
     *
     * @param llmClient         大模型客户端
     * @param aiService        LangChain4j AiServices（可为 null → 回退文本路径）
     * @param mode             运行模式（默认 SUGGEST）
     * @param sandboxConfigured 显式配置的沙箱可用性（留空则走 {@link SandboxProbe} 探测）
     * @param sandboxProbe     沙箱探测器（探测失败按不可用处理，fail-closed）
     * @param toolGate         工具门控（DEFERRED 重工具默认拒绝，仅显式授权 / STRICT 放行）
     */
    @Autowired
    public AutoFixEngine(LlmClient llmClient, CodeReviewAiService aiService,
                         @Value("${review.autofix.mode:SUGGEST}") AutoFixMode mode,
                         @Value("${review.autofix.sandbox-available:}") String sandboxConfigured,
                         SandboxProbe sandboxProbe,
                         ToolGate toolGate) {
        this(llmClient, aiService, mode, resolveSandbox(sandboxConfigured, sandboxProbe), toolGate);
    }

    private AutoFixEngine(LlmClient llmClient, CodeReviewAiService aiService,
                          AutoFixMode mode, boolean sandboxAvailable, ToolGate toolGate) {
        this.llmClient = llmClient;
        this.aiService = aiService;
        this.mode = mode;
        this.sandboxAvailable = sandboxAvailable;
        this.toolGate = toolGate;
        log.info("[自动修复] 初始化：mode={}, sandboxAvailable={}（fail-closed 边界已启用）",
                mode, sandboxAvailable);
    }

    /** 解析沙箱可用性：显式配置优先，否则探测器探测，探测失败一律不可用（fail-closed）。 */
    private static boolean resolveSandbox(String configured, SandboxProbe probe) {
        if (configured != null && !configured.isBlank()) {
            return Boolean.parseBoolean(configured);
        }
        if (probe != null) {
            return probe.detect().available();
        }
        return false;
    }

    /**
     * 当前运行模式。
     */
    public AutoFixMode getMode() {
        return mode;
    }

    /**
     * 是否允许实际应用（写入代码）修复。
     *
     * <p>对齐 fail-closed 不变量：{@code SUGGEST} 始终允许（不产生变更）；
     * {@code APPLY} 仅在沙箱可用时允许，否则坚决拒绝。同时「实际写代码」属于
     * DEFERRED 重工具，须经 {@link ToolGate} 放行（显式授权或 STRICT 强度）。
     *
     * @return true=允许应用
     */
    public boolean canApply() {
        if (!AutoFixSafetyPolicy.isApplyAllowed(mode, sandboxAvailable)) {
            return false;
        }
        if (toolGate != null && !toolGate.allows("autofix.apply", ToolExposure.DEFERRED)) {
            return false;
        }
        return true;
    }

    /**
     * 任何「实际写入代码」的修复路径都必须先经此守卫。
     *
     * @throws IllegalStateException 当前模式/沙箱状态不允许应用（fail-closed）
     */
    public void requireApplyAllowed() {
        if (!canApply()) {
            throw new IllegalStateException(
                    "fail-closed：当前禁止应用修复（mode=" + mode + ", sandboxAvailable=" + sandboxAvailable + "）");
        }
    }

    /**
     * 单条可应用的修复项（结构化，便于锚定到 PR 行内评论）。
     *
     * @param file    问题文件（相对路径）
     * @param line    问题行号（1-based；<=0 表示无法锚定到具体行）
     * @param ruleId  命中规则
     * @param label   修复标题
     * @param snippet 修复代码片段（suggestion 块内容）
     */
    public record FixItem(String file, int line, String ruleId, String label, String snippet) {}

    /**
     * 为审查报告生成修复建议区块（Markdown）。
     *
     * <p>用于 PR 顶层概览评论；每行内「应用建议」按钮由 {@link #generateFixItems} 逐条发布。
     *
     * @param report 审查报告
     * @return Markdown（suggestion 代码块），无修复项返回空串
     */
    public String generateSuggestions(ReviewReport report) {
        List<FixItem> items = generateFixItems(report);
        if (items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n## ").append(com.codereview.agent.core.i18n.ReviewMessages.get("autofix.title")).append("\n\n");
        sb.append("> ").append(com.codereview.agent.core.i18n.ReviewMessages.get("autofix.hint")).append("\n\n");
        int count = 0;
        for (FixItem it : items) {
            count++;
            sb.append(String.format("### %d. %s\n", count, it.label()));
            sb.append("> ").append(com.codereview.agent.core.i18n.ReviewMessages.get(
                    "autofix.itemFile", it.file(), it.line(), it.ruleId())).append("\n\n");
            sb.append("```suggestion\n").append(it.snippet()).append("\n```\n\n");
        }
        return sb.toString();
    }

    /**
     * 解析审查报告，产出结构化修复项列表（过滤掉无可用片段的发现）。
     *
     * @param report 审查报告
     * @return 修复项列表（可能为空）
     */
    public List<FixItem> generateFixItems(ReviewReport report) {
        List<Finding> findings = report.getFindings();
        if (findings == null || findings.isEmpty()) {
            return List.of();
        }
        List<FixItem> items = new ArrayList<>();
        for (Finding f : findings) {
            String[] fix = DETERMINISTIC.get(f.ruleId());
            String label;
            String snippet;
            if (fix != null) {
                label = com.codereview.agent.core.i18n.ReviewMessages.get(fix[0]);
                snippet = fix[1];
            } else {
                label = com.codereview.agent.core.i18n.ReviewMessages.get("autofix.llmLabel", f.ruleId());
                snippet = llmFix(f);
            }
            if (snippet == null || snippet.isBlank()) {
                continue;
            }
            items.add(new FixItem(f.file(), f.lineStart(), f.ruleId(), label, snippet));
        }
        return items;
    }

    private String llmFix(Finding f) {
        String prompt = String.format(
                "你是一名资深工程师。请针对以下代码审查发现，给出最小、可直接采纳的修复代码（仅输出代码，不要解释）：\n" +
                        "文件：%s（第 %d 行）\n问题：%s\n描述：%s\n现有建议：%s",
                f.file(), f.lineStart(), f.title(), f.description(), f.suggestion());
        long t0 = System.currentTimeMillis();
        log.debug("[自动修复] 开始为规则 {} 生成修复（文件 {} L{}）", f.ruleId(), f.file(), f.lineStart());
        // 1) LangChain4j AiServices 结构化输出
        if (aiService != null) {
            try {
                FixResultDto dto = aiService.fix("AUTOFIX-" + f.ruleId(), prompt);
                if (dto != null && dto.code() != null && !dto.code().isBlank()) {
                    log.info("[自动修复] AiServices 生成修复成功：rule={}, 代码={}字符, 耗时 {}ms",
                            f.ruleId(), dto.code().length(), System.currentTimeMillis() - t0);
                    return dto.code().trim();
                }
                log.info("[自动修复] AiServices 返回空，回退文本路径：rule={}", f.ruleId());
            } catch (Exception e) {
                log.warn("[自动修复] AiServices 生成失败，回退文本路径（{}）：{}（耗时 {}ms）",
                        f.ruleId(), e.getMessage(), System.currentTimeMillis() - t0);
            }
        }
        // 2) 文本回退
        try {
            String r = llmClient.chat(prompt);
            log.info("[自动修复] 文本路径生成修复：rule={}, 代码={}字符, 耗时 {}ms",
                    f.ruleId(), r == null ? 0 : r.length(), System.currentTimeMillis() - t0);
            return r == null ? "" : r.trim();
        } catch (Exception e) {
            log.warn("[自动修复] LLM 生成修复失败（{}）：{}", f.ruleId(), e.getMessage());
            return "";
        }
    }
}
