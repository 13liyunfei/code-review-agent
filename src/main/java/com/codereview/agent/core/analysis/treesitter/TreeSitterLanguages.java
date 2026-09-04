package com.codereview.agent.core.analysis.treesitter;

import org.treesitter.TSLanguage;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterTypescript;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * tree-sitter 语言注册表：**新增一门语言只需在此加一条 spec**，遍历逻辑不用动。
 *
 * <h2>为什么用节点类型名而不是 tree-sitter Query</h2>
 * tree-sitter 官方推荐用 S-expression Query 提取结构，但 Query 语法各语言不同、
 * 且需为每门语言单独维护一份 query 文件。而各语言的**节点类型名**本质上就是
 * query 的扁平化表达，用集合声明即可，配合统一遍历达到同样效果，
 * 且新增语言的成本从「写一个 query」降到「填一组字符串」。
 *
 * <h2>节点名的来源</h2>
 * 各 grammar 的节点名遵循 tree-sitter 约定（已用探针在本机实测确认）：
 * Python 用 {@code function_definition}/{@code call}，Go 与 JS/TS 用
 * {@code function_declaration}/{@code call_expression}。
 */
public final class TreeSitterLanguages {

    private TreeSitterLanguages() {
    }

    /**
     * 一门语言的解析配置。
     *
     * @param language          语言标识（与 {@code CodeDiff.language()} 对齐，小写）
     * @param grammar           grammar 实例工厂。延迟创建——grammar 构造会触发
     *                          native 库加载，不该在注册表初始化时就把所有语言都加载起来
     * @param functionNodeTypes 函数/方法声明的节点类型名
     * @param callNodeTypes     函数调用的节点类型名
     * @param branchNodeTypes   分支/循环节点类型名，用于圈复杂度近似
     */
    public record LanguageSpec(
            String language,
            Supplier<TSLanguage> grammar,
            Set<String> functionNodeTypes,
            Set<String> callNodeTypes,
            Set<String> branchNodeTypes
    ) {
    }

    private static final Set<String> PY_FUNCS = Set.of("function_definition");
    private static final Set<String> PY_CALLS = Set.of("call");
    private static final Set<String> PY_BRANCH = Set.of(
            "if_statement", "for_statement", "while_statement",
            "except_clause", "conditional_expression", "case_clause", "elif_clause");

    private static final Set<String> JS_FUNCS = Set.of(
            "function_declaration", "method_definition", "generator_function_declaration");
    private static final Set<String> JS_CALLS = Set.of("call_expression");
    private static final Set<String> JS_BRANCH = Set.of(
            "if_statement", "for_statement", "for_in_statement", "while_statement",
            "do_statement", "switch_case", "catch_clause", "ternary_expression");

    private static final Set<String> GO_FUNCS = Set.of("function_declaration", "method_declaration");
    private static final Set<String> GO_CALLS = Set.of("call_expression");
    private static final Set<String> GO_BRANCH = Set.of(
            "if_statement", "for_statement", "range_clause",
            "type_case", "communication_case", "select_statement");

    private static final Map<String, LanguageSpec> REGISTRY = Map.of(
            "python", new LanguageSpec("python", TreeSitterPython::new,
                    PY_FUNCS, PY_CALLS, PY_BRANCH),

            "javascript", new LanguageSpec("javascript", TreeSitterJavascript::new,
                    JS_FUNCS, JS_CALLS, JS_BRANCH),

            // 复用 JS 的节点名：TS 是 JS 的超集，grammar 虽独立但节点命名一致
            "typescript", new LanguageSpec("typescript", TreeSitterTypescript::new,
                    JS_FUNCS, JS_CALLS, JS_BRANCH),

            "go", new LanguageSpec("go", TreeSitterGo::new,
                    GO_FUNCS, GO_CALLS, GO_BRANCH)
    );

    /**
     * 历史/非标准标识的别名兜底。
     *
     * <p>{@code CodeDiff.inferLanguage} 已产出标准命名，但 diff 也可能来自
     * 外部构造（测试、其他平台适配器）而带上 {@code py} / {@code js} 这类缩写。
     * 加一层别名是为了不重蹈「语言标识对不上 → 引擎静默不生效」的覆辙。
     */
    private static final Map<String, String> ALIASES = Map.of(
            "py", "python",
            "js", "javascript",
            "jsx", "javascript",
            "ts", "typescript",
            "golang", "go",
            "csharp", "c_sharp",
            "c++", "cpp"
    );

    /** 查找语言配置；未注册返回空。 */
    public static Optional<LanguageSpec> find(String language) {
        if (language == null) {
            return Optional.empty();
        }
        String key = language.trim().toLowerCase();
        LanguageSpec spec = REGISTRY.get(key);
        if (spec == null) {
            String alias = ALIASES.get(key);
            if (alias != null) {
                spec = REGISTRY.get(alias);
            }
        }
        return Optional.ofNullable(spec);
    }

    /** 已注册的语言标识（用于诊断信息与能力声明）。 */
    public static Set<String> supported() {
        return REGISTRY.keySet();
    }
}
