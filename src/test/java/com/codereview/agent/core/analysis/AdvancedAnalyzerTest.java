package com.codereview.agent.core.analysis;

import com.codereview.agent.core.analysis.index.RepoSourceLocator;
import com.codereview.agent.core.analysis.index.SourceFetcher;
import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.AgentType;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.PullRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 AdvancedAnalyzer 的「完整源码」根治：
 * 旧实现从 diff 片段重建源码，真实 PR 上方法体被截断，STRUCT-* 静默产出 0 条。
 * 修复后走 RepoIndex 拉完整源码，长方法/高复杂度/深嵌套都能被检出，且仅收敛到 hunk 触及的方法。
 */
class AdvancedAnalyzerTest {

    /** 构造一个含「超长方法」的完整 Java 源：longMethod 约 80 行，shortMethod 很短。 */
    private static String fullSource() {
        StringBuilder sb = new StringBuilder();
        sb.append("package demo;\n");
        sb.append("public class Big {\n");
        sb.append("  public void longMethod() {\n");
        for (int i = 0; i < 78; i++) {
            sb.append("    int v").append(i).append(" = ").append(i).append(";\n");
        }
        sb.append("  }\n");
        sb.append("  public void shortMethod() {\n");
        sb.append("    int x = 1;\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** diff hunk 只新增一行，覆盖整体（让 hunk 行范围与被改长方法相交）。 */
    private static CodeDiff diffTouchingLongMethod() {
        String patch = "diff --git a/demo/Big.java b/demo/Big.java\n"
                + "--- a/demo/Big.java\n"
                + "+++ b/demo/Big.java\n"
                + "@@ -1,82 +1,83 @@\n"
                + "+// 新增一行注释\n"
                + " package demo;\n";
        // addedLines/delLines 仅用于构造，不影响分析路径
        return new CodeDiff("demo/Big.java", patch, "java", 83, 82);
    }

    private static RepoSourceLocator locatorReturning(String source) {
        return (owner, repo, ref) -> new SourceFetcher() {
            @Override
            public Optional<String> fetch(String path) {
                return "demo/Big.java".equals(path) ? Optional.of(source) : Optional.empty();
            }

            @Override
            public List<String> listDir(String dir) {
                return List.of();
            }
        };
    }

    private static PullRequest pr() {
        return new PullRequest(7001, "demo/adv", "t", "@bob", "main",
                "default", List.of(diffTouchingLongMethod()), "abcdef");
    }

    @Test
    void detectsLongMethodWithFullSource() {
        AdvancedAnalyzer analyzer = new AdvancedAnalyzer(locatorReturning(fullSource()));
        List<AgentResult> results = analyzer.analyze(pr(), List.of(diffTouchingLongMethod()));

        AgentResult arch = results.stream()
                .filter(r -> r.agentType() == AgentType.ARCHITECTURE).findFirst().orElseThrow();
        List<Finding> struct = arch.findings().stream()
                .filter(f -> f.ruleId().equals("STRUCT-LONG-METHOD")).toList();

        assertEquals(1, struct.size(), "完整源码下应检出长方法（旧实现因 diff 片段恒为 0）");
        assertTrue(struct.get(0).description().contains("longMethod"), "应定位到 longMethod");
    }

    @Test
    void doesNotFlagUntouchedShortMethod() {
        AdvancedAnalyzer analyzer = new AdvancedAnalyzer(locatorReturning(fullSource()));
        List<AgentResult> results = analyzer.analyze(pr(), List.of(diffTouchingLongMethod()));

        AgentResult arch = results.stream()
                .filter(r -> r.agentType() == AgentType.ARCHITECTURE).findFirst().orElseThrow();
        boolean shortFlagged = arch.findings().stream()
                .anyMatch(f -> f.description().contains("shortMethod"));
        assertFalse(shortFlagged, "hunk 未触及的短方法不应被翻出来（范围收敛）");
    }

    @Test
    void fallsBackToZeroWhenNoSourceAvailable() {
        // 无定位器 → 回落 diff 片段模式；片段里方法体被截断，长方法无法识别
        AdvancedAnalyzer analyzer = new AdvancedAnalyzer(); // 无 locator
        List<AgentResult> results = analyzer.analyze(pr(), List.of(diffTouchingLongMethod()));

        AgentResult arch = results.stream()
                .filter(r -> r.agentType() == AgentType.ARCHITECTURE).findFirst().orElseThrow();
        boolean longFlagged = arch.findings().stream()
                .anyMatch(f -> f.ruleId().equals("STRUCT-LONG-METHOD"));
        assertFalse(longFlagged, "无完整源码时（如纯单测）回落旧行为，不误报");
    }
}
