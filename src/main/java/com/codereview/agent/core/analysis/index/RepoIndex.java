package com.codereview.agent.core.analysis.index;

import com.codereview.agent.core.analysis.spi.AnalysisUnit;
import com.codereview.agent.core.analysis.spi.CodeAnalyzer;
import com.codereview.agent.core.model.CodeDiff;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 仓库级代码索引：把「改动了什么」和「谁在用它」连起来。
 *
 * <h2>为什么必须物化成临时源码树</h2>
 * JavaParser 的 {@code JavaParserTypeSolver} 只能从**文件系统目录**解析类型。
 * 它可以解析内存中的字符串，但遇到跨文件引用时必须能按包路径找到对应源文件。
 * 因此索引把拉取到的内容按原路径写到临时目录，形成一个「迷你源码树」——
 * 这是跨文件符号解析能工作的前提，也是本类存在的理由。
 *
 * <h2>扫描范围的收敛</h2>
 * 逐文件 HTTP 拉取，全量扫描不可行。按 {@link IndexScope} 从被改文件出发
 * 逐层扩展（同包 → 一跳 import），并用 maxFiles 硬限流。
 *
 * <h2>生命周期</h2>
 * 持有临时目录，用完必须 {@link #close()}（否则审查多了会堆积垃圾）。
 */
public final class RepoIndex implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RepoIndex.class);

    /** {@code import com.foo.Bar;} —— 只匹配单类导入，通配符代价大收益小，不处理。 */
    private static final Pattern IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:static\\s+)?([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;",
            Pattern.MULTILINE);

    /**
     * 不可能在本仓库里出现的包前缀（JDK 与 JVM 系标准库）。
     *
     * <p>代价不是「少拉几个文件」这么轻：{@code import java.util.List;} 这类占了真实源码
     * import 的绝大多数，逐个去拉会刷满一屏 404，并挤占 {@link IndexScope#maxFiles()} 配额，
     * 真正需要的同项目文件反而被挤掉。第三方依赖（org.slf4j 等）包名无法预知，
     * 仍会试着拉一次——那是 404 但不致命，故不在此列。
     */
    private static final List<String> NON_PROJECT_PREFIXES = List.of(
            "java.", "javax.", "jakarta.", "sun.", "com.sun.", "jdk.",
            "kotlin.", "scala.", "groovy.");

    private final Path sourceRoot;
    private final Map<String, AnalysisUnit> units;
    private final Map<String, List<AnalysisUnit.CallSite>> callersByCallee;
    private final Map<String, AnalysisUnit.MethodDecl> methodsBySignature;
    private final Map<String, String> sources;
    private final Stats stats;
    private final boolean crossFileCapable;

    private RepoIndex(Path sourceRoot, Map<String, AnalysisUnit> units,
                      Map<String, List<AnalysisUnit.CallSite>> callersByCallee,
                      Map<String, AnalysisUnit.MethodDecl> methodsBySignature,
                      Map<String, String> sources,
                      Stats stats, boolean crossFileCapable) {
        this.sourceRoot = sourceRoot;
        this.units = units;
        this.callersByCallee = callersByCallee;
        this.methodsBySignature = methodsBySignature;
        this.sources = sources;
        this.stats = stats;
        this.crossFileCapable = crossFileCapable;
    }

    /** 空索引（无跨文件能力时的占位，避免调用方判空）。 */
    public static RepoIndex empty() {
        return new RepoIndex(null, Map.of(), Map.of(), Map.of(), Map.of(), Stats.ZERO, false);
    }

    /**
     * 构建索引。
     *
     * @param fetcher 源码获取器
     * @param changed 本次 PR 的变更（索引从它们出发扩展）
     * @param scope   扫描范围
     * @param engines 分析引擎路由（按语言选择 JavaParser / tree-sitter）
     * @return 索引；构建失败返回 {@link #empty()}
     */
    public static RepoIndex build(SourceFetcher fetcher, List<CodeDiff> changed,
                                  IndexScope scope, AnalysisEngines engines) {
        if (fetcher == null || changed == null || changed.isEmpty()) {
            return empty();
        }
        Path root;
        try {
            root = Files.createTempDirectory("cra-repo-index-");
        } catch (IOException e) {
            log.warn("[RepoIndex] 无法创建临时目录，退化为无跨文件能力：{}", e.getMessage());
            return empty();
        }

        Set<String> wanted = new LinkedHashSet<>();
        for (CodeDiff d : changed) {
            if (d != null && d.fileName() != null && engines.supported(d.language())) {
                wanted.add(d.fileName());
            }
        }
        int limit = scope.maxFiles();

        // 先取被改文件：同包与一跳都依赖它的内容（包名、import）
        Map<String, String> contents = new LinkedHashMap<>();
        for (String p : new ArrayList<>(wanted)) {
            if (contents.size() >= limit) break;
            fetcher.fetch(p).ifPresent(c -> contents.put(p, c));
        }

        if (scope.includeSamePackage()) {
            for (CodeDiff d : changed) {
                if (d == null || d.fileName() == null) continue;
                String dir = parentDir(d.fileName());
                for (String sibling : fetcher.listDir(dir)) {
                    if (contents.size() >= limit) break;
                    if (engines.supported(languageOfPath(sibling))) {
                        wanted.add(sibling);
                    }
                }
            }
        }

        if (scope.resolveImports()) {
            List<String> roots = inferSourceRoots(changed);
            for (Map.Entry<String, String> e : contents.entrySet()) {
                if (!"java".equals(languageOfPath(e.getKey()))) continue;
                for (String imp : extractImports(e.getValue())) {
                    if (!isProjectImport(imp)) continue;
                    if (contents.size() >= limit) break;
                    for (String r : roots) {
                        wanted.add(r + imp.replace('.', '/') + ".java");
                    }
                }
            }
        }

        // 拉取扩展出来的文件
        for (String p : wanted) {
            if (contents.size() >= limit) break;
            if (contents.containsKey(p)) continue;
            fetcher.fetch(p).ifPresent(c -> contents.put(p, c));
        }

        // 物化成迷你源码树：JavaParser 的跨文件解析依赖文件系统布局
        int materialized = 0;
        for (Map.Entry<String, String> e : contents.entrySet()) {
            try {
                Path target = root.resolve(e.getKey()).normalize();
                if (!target.startsWith(root)) continue;   // 防目录穿越
                Files.createDirectories(target.getParent());
                Files.writeString(target, e.getValue());
                materialized++;
            } catch (IOException io) {
                log.debug("[RepoIndex] 物化失败 {}：{}", e.getKey(), io.getMessage());
            }
        }

        // 分析（引擎必须在源码树物化完成后构造，故放在此处）
        // 按语言缓存实例：JavaParserAnalyzer 构造时会让 typeSolver 扫描源码树建缓存，
        // 逐文件重建等于对每个文件重扫一遍全树，是纯粹浪费
        List<Path> sourceRoots = sourceRootsOf(root, inferSourceRoots(changed));
        Map<String, CodeAnalyzer> engineCache = new HashMap<>();
        Map<String, AnalysisUnit> units = new LinkedHashMap<>();
        Map<String, List<AnalysisUnit.CallSite>> inverted = new LinkedHashMap<>();
        Map<String, AnalysisUnit.MethodDecl> bySig = new LinkedHashMap<>();
        int failed = 0;
        boolean crossFile = false;

        for (Map.Entry<String, String> e : contents.entrySet()) {
            String path = e.getKey();
            String lang = languageOfPath(path);
            CodeAnalyzer engine = engineCache.computeIfAbsent(lang,
                    l -> engines.forLanguage(l, sourceRoots).orElse(null));
            if (engine == null) continue;
            AnalysisUnit unit = engine.analyze(path, lang, e.getValue());
            units.put(path, unit);
            if (!unit.ok()) {
                failed++;
                continue;
            }
            if (unit.capability() == CodeAnalyzer.Capability.CROSS_FILE) {
                crossFile = true;
            }
            for (AnalysisUnit.MethodDecl m : unit.methods()) {
                bySig.put(m.signature(), m);
            }
            // 只有能解析到全限定签名的调用才进倒排——FILE_LOCAL 的按名匹配会引入大量误报
            for (AnalysisUnit.CallSite cs : unit.callSites()) {
                if (cs.calleeSignature() != null) {
                    inverted.computeIfAbsent(cs.calleeSignature(), k -> new ArrayList<>()).add(cs);
                }
            }
        }

        Stats stats = new Stats(contents.size(), units.size(), failed, materialized,
                contents.size() >= limit);
        log.info("[RepoIndex] 索引完成：拉取 {} 文件，分析成功 {}，失败 {}，跨文件能力={}",
                stats.fetched(), stats.analyzed(), stats.failed(), crossFile);
        return new RepoIndex(root, Map.copyOf(units), Map.copyOf(inverted),
                Map.copyOf(bySig), Map.copyOf(contents), stats, crossFile);
    }

    /**
     * 查询某方法的上游调用方。
     *
     * @param signature 方法全限定签名
     * @return 调用点列表；无调用方或索引不支持跨文件时返回空列表
     */
    public List<AnalysisUnit.CallSite> callersOf(String signature) {
        if (signature == null || !crossFileCapable) {
            return List.of();
        }
        return callersByCallee.getOrDefault(signature, List.of());
    }

    /** 本索引是否具备跨文件查询能力（取决于是否有 CROSS_FILE 引擎参与）。 */
    public boolean crossFileCapable() {
        return crossFileCapable;
    }

    /** 取某文件的分析单元。 */
    public Optional<AnalysisUnit> unit(String path) {
        return Optional.ofNullable(units.get(path));
    }

    /** 取某文件的完整源码（索引阶段从仓库拉取并物化的内容）。 */
    public Optional<String> source(String path) {
        return Optional.ofNullable(sources.get(path));
    }

    public Map<String, AnalysisUnit> units() {
        return units;
    }

    public Stats stats() {
        return stats;
    }

    /** 物化的临时源码根目录；空索引为 null。 */
    public Path sourceRoot() {
        return sourceRoot;
    }

    @Override
    public void close() {
        if (sourceRoot == null) return;
        try (var walk = Files.walk(sourceRoot)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败无关紧要，OS 重启会回收
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    /**
     * 索引构建统计——用于**可观测**。
     *
     * <p>静默降级是这类分析最容易出的问题：拉取失败、解析失败、触发限流，
     * 上层看到的都是「0 条结论」，与「确实没有调用方」无法区分。
     * 把这些数字暴露出去，才能让「为什么没结论」可诊断。
     *
     * @param fetched      成功拉取的文件数
     * @param analyzed     成功分析的文件数
     * @param failed       分析失败的文件数
     * @param materialized 成功写入临时源码树的文件数
     * @param truncated    是否因达到 maxFiles 上限而提前停止扩展
     */
    public record Stats(int fetched, int analyzed, int failed, int materialized, boolean truncated) {
        public static final Stats ZERO = new Stats(0, 0, 0, 0, false);
    }

    // ============ 内部工具 ============

    static String parentDir(String path) {
        int i = path == null ? -1 : path.lastIndexOf('/');
        return i <= 0 ? "" : path.substring(0, i);
    }

    /**
     * 按路径推断语言。
     *
     * <p>复用 {@link CodeDiff#inferLanguage} 而非在本地再写一份扩展名映射——
     * 两处各写一套必然漂移，漂移的结果就是引擎路由静默失配。
     */
    static String languageOfPath(String path) {
        return CodeDiff.inferLanguage(path);
    }

    static List<String> extractImports(String source) {
        if (source == null) return List.of();
        List<String> out = new ArrayList<>();
        Matcher m = IMPORT.matcher(source);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * 该 import 是否可能指向本仓库内的源码。
     *
     * <p>启发式：排除 JDK/JVM 标准库固定前缀。剩下的可能是同项目类，也可能是第三方依赖——
     * 后者会 404，但只浪费一次请求，且不影响已拿到文件的分析正确性。
     */
    static boolean isProjectImport(String imp) {
        if (imp == null || imp.isEmpty()) return false;
        for (String p : NON_PROJECT_PREFIXES) {
            if (imp.startsWith(p)) return false;
        }
        return true;
    }

    /**
     * 从被改文件路径推断源码根前缀（如 {@code src/main/java/}）。
     *
     * <p>启发式：取路径中最后一个 {@code /java/} 之前的部分。这覆盖
     * Maven/Gradle 标准布局；非标准布局推断不出时会退化为「无前缀」，
     * 此时 import 解析的目标路径可能 404，只是少拿到一些调用方，不影响正确性。
     */
    static List<String> inferSourceRoots(List<CodeDiff> changed) {
        Set<String> roots = new LinkedHashSet<>();
        for (CodeDiff d : changed) {
            if (d == null || d.fileName() == null) continue;
            int i = d.fileName().lastIndexOf("/java/");
            if (i >= 0) {
                roots.add(d.fileName().substring(0, i + "/java/".length()));
            }
        }
        return roots.isEmpty() ? List.of("") : new ArrayList<>(roots);
    }

    /**
     * 把相对源码根前缀解析为临时目录下的绝对路径。
     *
     * <p>这是跨文件符号解析能工作的关键：JavaParserTypeSolver 按
     * 「源码根 + 包路径」定位类型文件，给成仓库根会导致
     * {@code com/demo/Service.java} 在 {@code <root>/com/demo/} 下找不到
     * （实际在 {@code <root>/src/main/java/com/demo/}），
     * 结果是跨文件调用全部 {@code resolved=false} 且**不报错**。
     *
     * @param tempRoot      物化的临时仓库根
     * @param relativeRoots 推断出的相对源码根（如 {@code src/main/java/}）
     * @return 绝对源码根列表；末尾追加仓库根作为非标准布局的兜底
     */
    static List<Path> sourceRootsOf(Path tempRoot, List<String> relativeRoots) {
        List<Path> out = new ArrayList<>();
        for (String r : relativeRoots) {
            Path p = tempRoot.resolve(r).normalize();
            if (Files.isDirectory(p) && !out.contains(p)) {
                out.add(p);
            }
        }
        // 兜底：源码不在标准 src/*/java 布局下时，仓库根本身就是源码根
        if (!out.contains(tempRoot)) {
            out.add(tempRoot);
        }
        return out;
    }

    /** 去重集合工具：保持插入顺序，避免索引结果顺序随哈希抖动。 */
    static <T> List<T> dedup(List<T> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    /** 供测试：判断集合是否含某元素（避免测试里重复写 contains）。 */
    static <T> boolean contains(Set<T> set, T v) {
        return set != null && set.contains(v);
    }
}
