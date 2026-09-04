package com.codereview.agent.core.impact;

import com.codereview.agent.core.analysis.index.AnalysisEngines;
import com.codereview.agent.core.analysis.index.IndexScope;
import com.codereview.agent.core.analysis.index.RepoIndex;
import com.codereview.agent.core.analysis.index.SourceFetcher;
import com.codereview.agent.core.model.CodeDiff;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 影响面分析的**真实形态**验证。
 *
 * <p>旧实现在这里恒产出 0 条结论，原因见 {@link ImpactAnalyzer} 的类注释：
 * diff 的 hunk 片段不含 {@code class X {}，方法解析出来却无处挂载。
 * 而旧测试之所以能通过，是因为用了「新增文件全量 patch」（{@code @@ -0,0 +1,N @@}）——
 * 测试 helper 悄悄替生产代码满足了前提。
 *
 * <p>本测试刻意构造<b>真实 PR 的 hunk 形态</b>，确保修复是真实有效的，而非换个测试骗自己。
 */
class ImpactAnalyzerIndexTest {

    // ---- 仓库内容：Service 被 Controller 跨文件调用 ----
    //
    // 关键：这里的内容必须是 **PR head 的版本**（即改动后的文件），
    // 因为索引从 PR head SHA 拉取完整文件，方法行号属于「新文件」坐标系，
    // 才能与 hunk 头里的新文件行号直接比较。若喂改动前的内容，
    // 行号会整体偏移，导致定位到错误的邻居方法——这正是下方 hunk 设计要防的事。

    private static final String SERVICE = """
            package com.demo;

            public class Service {
                public void refill() {
                    System.out.println("v1");
                    audit("refill");
                }

                public void audit(String who) {
                    System.out.println(who);
                }
            }
            """;

    private static final String CONTROLLER = """
            package com.demo;

            public class Controller {
                private Service svc = new Service();

                public void use() {
                    svc.refill();
                }

                public void useAgain() {
                    svc.refill();
                }

                public void check() {
                    svc.audit("bob");
                }
            }
            """;

    /**
     * 真实 PR 的 hunk：只含改动处 ±3 行上下文，**不含 class 声明行**。
     * 这正是旧实现失效的输入形态。
     *
     * <p>行号经过核对：改动后 refill 占 4-7 行，audit 占 9-11 行；
     * 本 hunk 覆盖新文件 1-8 行，因此<b>只与 refill 相交</b>——
     * 这既验证了「能定位到被改方法」，也验证了「不会把邻居方法误算进来」。
     */
    private static final String SERVICE_HUNK = String.join("\n",
            "@@ -1,7 +1,8 @@",
            " package com.demo;",
            " ",
            " public class Service {",
            "     public void refill() {",
            "         System.out.println(\"v1\");",
            "+        audit(\"refill\");",
            "     }",
            " ",
            "");

    private static Map<String, String> repo() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("src/main/java/com/demo/Service.java", SERVICE);
        m.put("src/main/java/com/demo/Controller.java", CONTROLLER);
        return m;
    }

    private static SourceFetcher stub(Map<String, String> files) {
        return new SourceFetcher() {
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
        };
    }

    /**
     * 决定性用例：真实 hunk 形态下，必须能识别出跨文件调用方。
     *
     * <p>旧实现在此断言上是 0 条（真实 PR 上恒失效），这是本次治本的核心验收点。
     */
    @Test
    void realHunkPatchFindsCrossFileCallers() {
        CodeDiff diff = new CodeDiff("src/main/java/com/demo/Service.java",
                SERVICE_HUNK, "java", 1, 0);

        try (RepoIndex index = RepoIndex.build(stub(repo()), List.of(diff),
                IndexScope.DEFAULT, AnalysisEngines.defaults())) {

            assertTrue(index.crossFileCapable(), "Java 引擎应声明跨文件能力");
            assertEquals(0, index.stats().failed(), "两个文件都应解析成功");

            ImpactAnalyzer.ImpactReport report = new ImpactAnalyzer().analyze(List.of(diff), index);

            System.out.println("[TEST] mode=" + report.mode()
                    + " entries=" + report.entries().size()
                    + " 详情=" + report.entries());

            assertFalse(report.entries().isEmpty(),
                    "真实 hunk patch 必须能产出影响面结论（旧实现此处恒为 0）");
            assertEquals(ImpactAnalyzer.Mode.CROSS_FILE, report.mode());

            // refill 被 Controller 的 use / useAgain 两处跨文件调用
            var refill = report.entries().stream()
                    .filter(e -> "refill".equals(e.method()))
                    .findFirst();
            assertTrue(refill.isPresent(), "改动的是 refill，应有它的结论");
            assertTrue(refill.get().crossFile(), "Java 应给出跨文件调用方");
            assertEquals(2, refill.get().callerCount(),
                    "refill 应被 use 与 useAgain 两处跨文件调用，实际=" + refill.get().callers());
        }
    }

    /** 只分析与改动行范围相交的方法，而不是文件内所有方法。 */
    @Test
    void onlyMethodsTouchedByHunkAreReported() {
        // hunk 只覆盖 refill（第 4-6 行），audit（第 8-10 行）未被改动
        CodeDiff diff = new CodeDiff("src/main/java/com/demo/Service.java",
                SERVICE_HUNK, "java", 1, 0);

        try (RepoIndex index = RepoIndex.build(stub(repo()), List.of(diff),
                IndexScope.DEFAULT, AnalysisEngines.defaults())) {

            ImpactAnalyzer.ImpactReport report = new ImpactAnalyzer().analyze(List.of(diff), index);

            assertTrue(report.entries().stream().noneMatch(e -> "audit".equals(e.method())),
                    "未被 hunk 覆盖的 audit 不应出现在结论里，实际=" + report.entries());
        }
    }

    /** 无索引时明确降级，而不是伪装成「没有影响面」。 */
    @Test
    void withoutIndexReportsNoSourceInsteadOfEmptyClaim() {
        CodeDiff diff = new CodeDiff("src/main/java/com/demo/Service.java",
                SERVICE_HUNK, "java", 1, 0);

        ImpactAnalyzer.ImpactReport report = new ImpactAnalyzer().analyze(List.of(diff));

        assertEquals(ImpactAnalyzer.Mode.NO_SOURCE, report.mode(),
                "拿不到完整文件时必须说明原因，而不是返回「无影响面」");
        assertTrue(report.entries().isEmpty());
    }

    /** 非 Java 语言：tree-sitter 能给出文件内调用方，但明确标记为非跨文件。 */
    @Test
    void nonJavaLanguageGetsFileLocalCallers() {
        String pyFile = """
                def refill():
                    print("v1")

                def use():
                    refill()

                def use_again():
                    refill()
                """;
        Map<String, String> files = Map.of("svc/refill.py", pyFile);
        String hunk = """
                @@ -1,3 +1,4 @@
                 def refill():
                     print("v1")
                +    audit()
                """;
        CodeDiff diff = new CodeDiff("svc/refill.py", hunk, "python", 1, 0);

        try (RepoIndex index = RepoIndex.build(stub(files), List.of(diff),
                IndexScope.DEFAULT, AnalysisEngines.defaults())) {

            ImpactAnalyzer.ImpactReport report = new ImpactAnalyzer().analyze(List.of(diff), index);

            System.out.println("[TEST-py] mode=" + report.mode() + " entries=" + report.entries());

            assertFalse(report.entries().isEmpty(), "Python 也应能识别文件内调用方");
            assertEquals(ImpactAnalyzer.Mode.FILE_LOCAL, report.mode(),
                    "tree-sitter 无符号解析能力，必须声明为 FILE_LOCAL");
            assertTrue(report.entries().stream().noneMatch(ImpactAnalyzer.ImpactEntry::crossFile),
                    "FILE_LOCAL 引擎不得声称跨文件");
        }
    }

    /**
     * JDK / JVM 标准库的 import 不应被当作源码去拉。
     *
     * <p>这不是优化而是正确性的一部分：真实源码里 {@code import java.util.List;} 这类占了
     * import 的绝大多数，逐个去拉会刷满一屏 404 并挤占 {@code maxFiles} 配额，
     * 导致真正需要的同项目文件被挤掉——影响面分析因此「看起来在工作，实际什么也没找到」。
     */
    @Test
    void jdkImportsAreNotFetchedButProjectImportsAre() {
        String src = """
                package com.demo;

                import java.util.List;
                import java.util.ArrayList;
                import com.other.Thing;

                public class Service {
                    public List<String> refill() {
                        return new ArrayList<>();
                    }
                }
                """;
        Map<String, String> files = new LinkedHashMap<>();
        files.put("src/main/java/com/demo/Service.java", src);
        files.put("src/main/java/com/other/Thing.java",
                "package com.other;\npublic class Thing {}\n");

        List<String> requested = new ArrayList<>();
        SourceFetcher spy = new SourceFetcher() {
            @Override
            public Optional<String> fetch(String path) {
                requested.add(path);
                return Optional.ofNullable(files.get(path));
            }

            @Override
            public List<String> listDir(String dir) {
                return List.of();
            }
        };

        String hunk = """
                @@ -5,4 +5,5 @@
                     public List<String> refill() {
                         return new ArrayList<>();
                +        // tweak
                     }
                """;
        CodeDiff diff = new CodeDiff("src/main/java/com/demo/Service.java", hunk, "java", 1, 0);

        try (RepoIndex index = RepoIndex.build(spy, List.of(diff), IndexScope.DEFAULT,
                AnalysisEngines.defaults())) {
            System.out.println("[TEST-imports] 请求过的路径=" + requested);

            assertTrue(requested.stream().noneMatch(p -> p.contains("/java/util/")),
                    "JDK 包不应被请求，实际=" + requested);
            assertTrue(requested.contains("src/main/java/com/other/Thing.java"),
                    "同项目的 import 应被展开，实际=" + requested);
            assertEquals(2, index.stats().fetched(), "只应拉到被改文件 + 同项目 import 目标");
        }
    }

    /**
     * 扫描范围必须真实生效：同包扩展能拿到调用方，关闭则只有被改文件本身。
     *
     * <p>注意被改的是 Service，它<b>不 import</b> Controller——「谁调用了我」这个方向
     * 无法从被改文件自身的内容推出，只能靠同包扫描覆盖。这正是
     * {@link IndexScope#includeSamePackage()} 存在的意义。
     */
    @Test
    void indexStatsAreObservable() {
        CodeDiff diff = new CodeDiff("src/main/java/com/demo/Service.java",
                SERVICE_HUNK, "java", 1, 0);

        // 同包扩展：拉到同目录的 Controller
        try (RepoIndex index = RepoIndex.build(stub(repo()), List.of(diff),
                IndexScope.of(true, false, 200), AnalysisEngines.defaults())) {

            System.out.println("[TEST-stats-samePkg] " + index.stats());
            assertEquals(2, index.stats().fetched(), "同包扩展应拉到 Controller");
            assertEquals(0, index.stats().failed());
            assertFalse(index.stats().truncated(), "未触及上限不应标记为截断");
        }

        // 只看被改文件：拉不到调用方，但不该报错
        try (RepoIndex index = RepoIndex.build(stub(repo()), List.of(diff),
                IndexScope.CHANGED_ONLY, AnalysisEngines.defaults())) {

            System.out.println("[TEST-stats-changedOnly] " + index.stats());
            assertEquals(1, index.stats().fetched(), "关闭扩展后应只拉被改文件");
        }
    }

    /** 统计字段让「为什么没结论」可诊断——静默降级正是旧实现的病灶。 */
    @Test
    void statsDistinguishFailureFromNoCallers() {
        CodeDiff diff = new CodeDiff("src/main/java/com/demo/Broken.java",
                "@@ -1,3 +1,4 @@\n this is not java at all {{{\n", "java", 1, 0);
        Map<String, String> broken = Map.of(
                "src/main/java/com/demo/Broken.java", "this is not java at all {{{\n");

        try (RepoIndex index = RepoIndex.build(stub(broken), List.of(diff),
                IndexScope.DEFAULT, AnalysisEngines.defaults())) {

            System.out.println("[TEST-stats-broken] " + index.stats());
            assertEquals(1, index.stats().fetched());
            assertEquals(1, index.stats().failed(), "语法非法的文件必须计入 failed 而不是静默跳过");
        }
    }
}
