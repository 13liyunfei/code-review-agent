package com.codereview.agent.core.agent;

import com.codereview.agent.core.calibration.ConfidenceCalibrationService;
import com.codereview.agent.core.llm.LlmClient;
import com.codereview.agent.core.llm.aiservice.CodeReviewAiService;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.prompt.PromptTemplateLoader;
import com.codereview.agent.core.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * formatDiffs 字符预算（DiffCharBudget）验证。
 *
 * <p>背景：大 PR 的 diff 可能数万~数十万字符，全量塞 prompt 既费 token 又可能顶爆
 * 上下文窗口。修复 = {@link AbstractReviewAgent#diffCharBudget}：超预算时按文件均摊
 * 截断并追加截断标注，默认 -1（不截断，向后兼容）。
 *
 * <p>验证口径：
 * <ol>
 *   <li>预算关闭（默认 -1）：全量输出、无截断标注；</li>
 *   <li>预算充足（≥ 总量）：全量输出、无截断标注；</li>
 *   <li>预算不足：输出被显著压缩、文件 patch 保留头部、末尾有截断标注、被截断文件有单文件标注。</li>
 * </ol>
 */
class DiffCharBudgetTest {

    /** 测试探针：把 protected formatDiffs 暴露出来，便于直接断言。 */
    private static class ProbeAgent extends AbstractReviewAgent {
        ProbeAgent() {
            super(null, null, null, null, "logic", null);
        }

        @Override
        public AgentType getType() {
            return AgentType.LOGIC;
        }

        @Override
        public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
            return List.of();
        }

        String fmt(List<CodeDiff> diffs) {
            return formatDiffs(diffs);
        }
    }

    private static CodeDiff javaDiff(String file, int patchLines) {
        StringBuilder sb = new StringBuilder("diff --git a/" + file + " b/" + file + "\n"
                + "--- a/" + file + "\n+++ b/" + file + "\n");
        for (int i = 0; i < patchLines; i++) {
            sb.append("+int value_").append(i).append(" = ").append(i).append("; // padding line\n");
        }
        return new CodeDiff(file, sb.toString(), "java", patchLines, 0);
    }

    private static String longPatch(int chars) {
        StringBuilder sb = new StringBuilder("+public void method() {\n");
        while (sb.length() < chars) {
            sb.append("+    // padding-").append(sb.length()).append("\n");
        }
        return sb.toString();
    }

    @Test
    void budgetDisabledByDefaultOutputsFullDiff() {
        ProbeAgent agent = new ProbeAgent();
        CodeDiff cd = javaDiff("A.java", 5);
        String out = agent.fmt(List.of(cd));

        assertEquals(-1, agent.diffCharBudget(), "默认预算应为 -1（不截断，向后兼容）");
        assertTrue(out.contains(cd.patch()), "预算关闭时应全量输出 patch");
        assertTrue(out.startsWith("--- A.java ---\n"), "应带文件头标识");
        assertFalse(out.contains("截断"), "预算关闭时不应出现截断标注");
    }

    @Test
    void budgetAboveTotalKeepsFullDiffWithoutMarker() {
        ProbeAgent agent = new ProbeAgent();
        CodeDiff a = javaDiff("A.java", 3);
        CodeDiff b = javaDiff("B.java", 3);
        long total = a.patch().length() + b.patch().length();
        agent.setDiffCharBudget((int) (total + 10_000));

        String out = agent.fmt(List.of(a, b));

        assertTrue(out.contains(a.patch()), "预算充足时 A.java patch 应全量保留");
        assertTrue(out.contains(b.patch()), "预算充足时 B.java patch 应全量保留");
        assertFalse(out.contains("截断"), "预算充足时不应出现截断标注");
    }

    @Test
    void overBudgetTruncatesByFileAndAnnotates() {
        ProbeAgent agent = new ProbeAgent();
        // 单文件超长 patch：原始明显大于预算
        CodeDiff big = new CodeDiff("Huge.java", longPatch(20_000), "java", 1, 0);
        agent.setDiffCharBudget(300);

        String out = agent.fmt(List.of(big));

        // 1) 输出被显著压缩（保留头部 patch，远小于原始）
        assertTrue(out.length() < big.patch().length(), "超预算输出应显著短于原始 diff");
        // 2) 文件头部仍保留（模型能看出改了哪个文件、开头长什么样）
        assertTrue(out.startsWith("--- Huge.java ---\n"), "文件头应保留（模型可识别变更对象）");
        // 3) 单文件截断标注 + 末尾汇总标注都出现
        assertTrue(out.contains("该文件 diff 超预算，已截断"), "被截断文件应带单文件标注");
        assertTrue(out.contains("【diff 过大提示】"), "末尾应带整体截断汇总标注");
        // 4) 标注明确告知模型 diff 不完整（避免把截断误判为「没有更多变更」）
        assertTrue(out.contains("请基于给出的片段审查"), "应提示模型基于片段审查");
    }

    @Test
    void overBudgetAcrossMultipleFilesDistributesFairly() {
        ProbeAgent agent = new ProbeAgent();
        // 两文件均超长、总量远超预算：预算按文件均摊，两个文件都应保留头部，而不是只留列表头部的文件
        CodeDiff a = new CodeDiff("A.java", longPatch(8_000), "java", 1, 0);
        CodeDiff b = new CodeDiff("B.java", longPatch(8_000), "java", 1, 0);
        agent.setDiffCharBudget(600);

        String out = agent.fmt(List.of(a, b));

        assertTrue(out.contains("--- A.java ---"), "均摊截断：A.java 头部应保留");
        assertTrue(out.contains("--- B.java ---"), "均摊截断：B.java 头部应保留（不偏向列表头部文件）");
        assertTrue(out.contains("【diff 过大提示】"), "应带整体截断标注");
        assertTrue(out.contains("2 个文件截断") || out.contains("1 个文件截断"),
                "截断统计应如实反映（2 或 1 个文件被截断）");
    }
}
