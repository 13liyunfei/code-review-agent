package com.codereview.agent.core.impact;

import com.codereview.agent.core.model.CodeDiff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文影响面分析：复用 AST + 调用图，识别变更方法的上游调用方。
 */
class ImpactAnalyzerTest {

    /** 仿 EnterpriseFeaturesTest 的 javaDiff 助手：构造统一 diff（全为新增行）。 */
    private static CodeDiff javaDiff(String fileName, String body) {
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(fileName).append(" b/").append(fileName).append('\n');
        sb.append("--- a/").append(fileName).append('\n');
        sb.append("+++ b/").append(fileName).append('\n');
        String[] lines = body.split("\n", -1);
        sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String l : lines) {
            sb.append('+').append(l).append('\n');
        }
        return new CodeDiff(fileName, sb.toString(), "java", lines.length, 0);
    }

    @Test
    void detectsUpstreamCallers() {
        // m() 被 a()、b() 调用；影响面分析应识别 m 存在上游调用方
        String src = "package demo;\n"
                + "public class T {\n"
                + "  public void a() { m(); }\n"
                + "  public void b() { m(); }\n"
                + "  public void m() { int x = 1; }\n"
                + "}\n";
        CodeDiff cd = javaDiff("T.java", src);

        ImpactAnalyzer.ImpactReport report = new ImpactAnalyzer().analyze(List.of(cd));
        assertFalse(report.entries().isEmpty(), "应检测到存在上游调用方的影响面条目");
        // m 的影响面应含 a、b
        boolean mHasImpact = report.entries().stream()
                .anyMatch(e -> "m".equals(e.method())
                        && e.callerCount() >= 2
                        && e.callers().contains("a") && e.callers().contains("b"));
        assertTrue(mHasImpact, "方法 m 应被 a、b 调用");
    }

    @Test
    void summarizeNonEmptyForImpactfulChange() {
        String src = "package demo;\n"
                + "public class T {\n"
                + "  public void a() { m(); }\n"
                + "  public void m() { int x = 1; }\n"
                + "}\n";
        CodeDiff cd = javaDiff("T.java", src);
        String summary = new ImpactAnalyzer().summarize(List.of(cd));
        assertNotNull(summary);
        assertFalse(summary.isBlank(), "有影响面时应产出非空摘要");
        assertTrue(summary.contains("m"), "摘要应提及变更方法 m");
    }

    @Test
    void summarizeEmptyForNoImpact() {
        // 无调用关系的孤立方法 → 无影响面条目 → 摘要为空
        String src = "package demo;\n"
                + "public class T {\n"
                + "  public void m() { int x = 1; }\n"
                + "}\n";
        CodeDiff cd = javaDiff("T.java", src);
        String summary = new ImpactAnalyzer().summarize(List.of(cd));
        assertTrue(summary.isBlank(), "无调用方时摘要应为空（避免噪声注入）");
    }

    @Test
    void skipsNonJava() {
        CodeDiff cd = new CodeDiff("README.md", "+hello", "unknown", 1, 0);
        String summary = new ImpactAnalyzer().summarize(List.of(cd));
        assertTrue(summary.isBlank(), "非 Java 文件应跳过影响面分析");
    }
}
