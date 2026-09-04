package com.codereview.agent.core.impact;

import com.codereview.agent.core.analysis.index.AnalysisEngines;
import com.codereview.agent.core.analysis.index.IndexScope;
import com.codereview.agent.core.analysis.index.RepoIndex;
import com.codereview.agent.core.analysis.index.SourceFetcher;
import com.codereview.agent.core.model.CodeDiff;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文影响面分析：识别变更方法的上游调用方。
 *
 * <h2>为什么这里必须建索引</h2>
 * 影响面分析需要<b>完整文件</b>：JavaParser 要求语法完整的编译单元，
 * 而 diff 片段连类声明都未必包含。因此每个用例都要先按「仓库内容」建索引，
 * 这与生产路径（{@code ImpactIndexBuilder} 按 PR head SHA 拉源码）是一致的。
 *
 * <p>本组用例覆盖的是「<b>新增文件</b>」形态（patch 即全量内容）；
 * 「修改已有文件」的 hunk 形态见 {@link ImpactAnalyzerIndexTest}。
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

    /**
     * 按「仓库内容」建索引。
     *
     * <p>新增文件的 patch 就是它的全量内容，故索引里放的内容与 patch body 一致——
     * 这保证方法行号与 hunk 行号处在同一坐标系。
     */
    private static RepoIndex indexOf(Map<String, String> files, List<CodeDiff> diffs) {
        return RepoIndex.build(new SourceFetcher() {
            @Override
            public Optional<String> fetch(String path) {
                return Optional.ofNullable(files.get(path));
            }

            @Override
            public List<String> listDir(String dir) {
                List<String> out = new ArrayList<>();
                for (String p : files.keySet()) {
                    String parent = p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : "";
                    if (parent.equals(dir == null ? "" : dir)) out.add(p);
                }
                return out;
            }
        }, diffs, IndexScope.DEFAULT, AnalysisEngines.defaults());
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
        CodeDiff cd = javaDiff("demo/T.java", src);

        try (RepoIndex index = indexOf(Map.of("demo/T.java", src), List.of(cd))) {
            ImpactAnalyzer.ImpactReport report = new ImpactAnalyzer().analyze(List.of(cd), index);
            assertFalse(report.entries().isEmpty(), "应检测到存在上游调用方的影响面条目");
            // m 的影响面应含 a、b
            boolean mHasImpact = report.entries().stream()
                    .anyMatch(e -> "m".equals(e.method())
                            && e.callerCount() >= 2
                            && e.callers().contains("a") && e.callers().contains("b"));
            assertTrue(mHasImpact, "方法 m 应被 a、b 调用，实际=" + report.entries());
        }
    }

    @Test
    void summarizeNonEmptyForImpactfulChange() {
        String src = "package demo;\n"
                + "public class T {\n"
                + "  public void a() { m(); }\n"
                + "  public void m() { int x = 1; }\n"
                + "}\n";
        CodeDiff cd = javaDiff("demo/T.java", src);

        try (RepoIndex index = indexOf(Map.of("demo/T.java", src), List.of(cd))) {
            String summary = new ImpactAnalyzer().summarize(List.of(cd), index);
            assertNotNull(summary);
            assertFalse(summary.isBlank(), "有影响面时应产出非空摘要");
            assertTrue(summary.contains("m"), "摘要应提及变更方法 m");
        }
    }

    @Test
    void summarizeEmptyForNoImpact() {
        // 无调用关系的孤立方法 → 无影响面条目 → 摘要为空
        String src = "package demo;\n"
                + "public class T {\n"
                + "  public void m() { int x = 1; }\n"
                + "}\n";
        CodeDiff cd = javaDiff("demo/T.java", src);

        try (RepoIndex index = indexOf(Map.of("demo/T.java", src), List.of(cd))) {
            String summary = new ImpactAnalyzer().summarize(List.of(cd), index);
            assertTrue(summary.isBlank(), "无调用方时摘要应为空（避免噪声注入）");
        }
    }

    @Test
    void skipsUnsupportedLanguage() {
        CodeDiff cd = new CodeDiff("README.md", "+hello", "unknown", 1, 0);

        try (RepoIndex index = indexOf(Map.of("README.md", "hello"), List.of(cd))) {
            String summary = new ImpactAnalyzer().summarize(List.of(cd), index);
            assertTrue(summary.isBlank(), "无引擎支持的语言不应产出影响面结论");

            // 关键：不是静默跳过，而是记进 unsupportedLangs，让「为什么没结论」可查
            ImpactAnalyzer.ImpactReport report = new ImpactAnalyzer().analyze(List.of(cd), index);
            assertTrue(report.unsupportedLangs().contains("unknown"),
                    "被跳过的语言必须可观测，实际=" + report.unsupportedLangs());
        }
    }
}
