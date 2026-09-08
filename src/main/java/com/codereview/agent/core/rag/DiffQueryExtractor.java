package com.codereview.agent.core.rag;

import com.codereview.agent.core.model.CodeDiff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从代码变更中提炼<b>结构化检索查询</b>（业界 query construction，而非裸 patch 截断）。
 *
 * <p><b>为什么需要它</b>：检索查询过去是「把 diff 拼起来取前 500 字符」——里面塞满了
 * {@code +/-}、行号、hunk 头、上下文噪音。拿这段噪声去撞以自然语言术语为主的规范库，
 * 语义鸿沟极大；而且截断到 500 字符后，真正改动的方法可能根本没被包含。
 *
 * <p>本提取器把 diff 提炼成贴近知识库措辞的结构化查询：
 * <pre>
 * 文件 src/.../UserService.java（java）；类 UserService；方法 login, loadUser；符号 PreparedStatement, executeQuery, SQLException
 * </pre>
 * 既让稠密向量拿到「去噪后的语义骨架」，也让 BM25 拿到高信息密度的词。
 *
 * <p><b>降级契约</b>：若结构化信息为空（如纯配置/文档改动不含任何标识符），
 * 退化为 patch 前若干字符——<b>绝不返回空查询</b>，保证不比旧行为更差。
 */
public final class DiffQueryExtractor {

    /** 参与查询的文件数上限。 */
    private static final int MAX_FILES = 5;
    /** 方法名上限。 */
    private static final int MAX_METHODS = 8;
    /** 高频符号上限。 */
    private static final int MAX_SYMBOLS = 20;
    /** 兜底截断字符数（未提炼出方法/符号时使用）。 */
    private static final int FALLBACK_CHARS = 300;

    /** 方法签名中的方法名（hunk header 的函数上下文）。 */
    private static final Pattern METHOD_PATTERN = Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
    /** 标识符（含类型/异常/注解名）。 */
    private static final Pattern IDENT_PATTERN = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{2,}");
    /** hunk 头：{@code @@ -1,5 +1,7 @@ optional context}。 */
    private static final Pattern HUNK_PATTERN = Pattern.compile("^@@+[^@]*@@+\\s*(.*)$");

    /** 语言关键字/高频无信息词：不参与查询构造。 */
    private static final Set<String> STOPWORDS = Set.of(
            "public", "private", "protected", "static", "final", "void", "class", "interface",
            "return", "import", "package", "new", "null", "true", "false", "this", "super",
            "if", "else", "for", "while", "try", "catch", "finally", "throw", "throws",
            "string", "int", "long", "double", "boolean", "float", "char", "byte", "short",
            "var", "const", "let", "def", "func", "function", "val", "override", "abstract",
            "impl", "extends", "implements", "lambda", "get", "set", "put", "add", "the", "and");

    private DiffQueryExtractor() {
    }

    /**
     * 提炼结构化检索查询。
     *
     * @param diffs 代码变更
     * @return 结构化查询（永不返回 null；无变更时返回空串）
     */
    public static String extract(List<CodeDiff> diffs) {
        if (diffs == null || diffs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Set<String> classes = new LinkedHashSet<>();
        Set<String> methods = new LinkedHashSet<>();
        Map<String, Integer> symbolFreq = new LinkedHashMap<>();

        int fileCount = 0;
        for (CodeDiff d : diffs) {
            if (d == null) {
                continue;
            }
            if (d.fileName() != null && !d.fileName().isBlank() && fileCount < MAX_FILES) {
                sb.append("文件 ").append(d.fileName());
                if (d.language() != null && !d.language().isBlank() && !"unknown".equals(d.language())) {
                    sb.append("（").append(d.language()).append("）");
                }
                sb.append("；");
                String cls = classNameOf(d.fileName());
                if (cls != null) {
                    classes.add(cls);
                }
                fileCount++;
            }
            if (d.patch() == null || d.patch().isBlank()) {
                continue;
            }
            for (String m : hunkMethods(d.patch())) {
                if (methods.size() < MAX_METHODS * 3) {
                    methods.add(m);
                }
            }
            List<String> idents = changedIdentifiers(d.patch());
            if (idents.isEmpty()) {
                // 非标准 unified diff（无 +/- 前缀，如 IDE 插件 / 手工构造的文本片段）：
                // 退化为整段抽标识符，避免提炼出「只有文件名」的空壳查询
                idents = allIdentifiers(d.patch());
            }
            for (String s : idents) {
                symbolFreq.merge(s, 1, Integer::sum);
            }
        }

        if (!classes.isEmpty()) {
            sb.append("类 ").append(join(classes, MAX_METHODS)).append("；");
        }
        // 判据是「是否提炼出实质语义信息（方法/符号）」，而非字符串长度——
        // 纯中文文档改动也能凑出「文件 x.md；类 x；」这种超过长度阈值的空壳查询
        boolean informative = !methods.isEmpty() || !symbolFreq.isEmpty();
        if (!methods.isEmpty()) {
            sb.append("方法 ").append(join(methods, MAX_METHODS)).append("；");
        }
        if (!symbolFreq.isEmpty()) {
            sb.append("符号 ").append(topSymbols(symbolFreq, MAX_SYMBOLS));
        }
        if (informative) {
            return sb.toString().trim();
        }

        // 兜底：没有任何方法/符号（纯中文/纯配置改动）→ 追加原文截断，
        // 保证查询既不为空、又保留原始语义（绝不比「裸 patch 截断」差）
        StringBuilder raw = new StringBuilder();
        for (CodeDiff d : diffs) {
            if (d != null && d.patch() != null) {
                raw.append(d.patch());
            }
        }
        String fallback = raw.toString().trim();
        if (fallback.length() > FALLBACK_CHARS) {
            fallback = fallback.substring(0, FALLBACK_CHARS);
        }
        String prefix = sb.toString().trim();
        return prefix.isBlank() ? fallback : prefix + " " + fallback;
    }

    /** 从文件名推类名：{@code src/.../UserService.java} → {@code UserService}。 */
    private static String classNameOf(String fileName) {
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        // 去掉测试后缀等噪声，保留主体
        if (name.endsWith("Test") || name.endsWith("Tests")) {
            name = name.substring(0, name.length() - (name.endsWith("Tests") ? 5 : 4));
        }
        return name.isBlank() || !Character.isLetter(name.charAt(0)) ? null : name;
    }

    /** 从 hunk header 的函数上下文抽方法名。 */
    private static List<String> hunkMethods(String patch) {
        List<String> out = new ArrayList<>();
        for (String line : patch.split("\n")) {
            Matcher h = HUNK_PATTERN.matcher(line);
            if (!h.find()) {
                continue;
            }
            String ctx = h.group(1).trim();
            if (ctx.isEmpty()) {
                continue;
            }
            Matcher m = METHOD_PATTERN.matcher(ctx);
            if (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /** 从 {@code +}/{-} 改动行抽标识符（跳过 diff 文件头行）。 */
    private static List<String> changedIdentifiers(String patch) {
        List<String> out = new ArrayList<>();
        for (String line : patch.split("\n")) {
            if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("@@")) {
                continue;
            }
            if (!(line.startsWith("+") || line.startsWith("-"))) {
                continue;
            }
            collectIdentifiers(line, out);
        }
        return out;
    }

    /** 非标准 unified diff（无 {@code +/-} 前缀）的兜底：从整段文本抽标识符。 */
    private static List<String> allIdentifiers(String patch) {
        List<String> out = new ArrayList<>();
        collectIdentifiers(patch, out);
        return out;
    }

    private static void collectIdentifiers(String text, List<String> out) {
        Matcher m = IDENT_PATTERN.matcher(text);
        while (m.find()) {
            String id = m.group();
            String lower = id.toLowerCase(Locale.ROOT);
            // 长度 >=3（SQL/URL/API 等三字母缩写有信息量）且非语言关键字
            if (id.length() >= 3 && !STOPWORDS.contains(lower)) {
                out.add(id);
            }
        }
    }

    /** 按频次（同频次按长度降序，长标识符信息量更大）取 top-N。 */
    private static String topSymbols(Map<String, Integer> freq, int n) {
        List<String> keys = new ArrayList<>(freq.keySet());
        keys.sort((a, b) -> {
            int c = Integer.compare(freq.get(b), freq.get(a));
            return c != 0 ? c : Integer.compare(b.length(), a.length());
        });
        return join(keys.subList(0, Math.min(n, keys.size())), n);
    }

    private static String join(Iterable<String> items, int n) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String s : items) {
            if (i >= n) {
                break;
            }
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(s);
            i++;
        }
        return sb.toString();
    }
}
