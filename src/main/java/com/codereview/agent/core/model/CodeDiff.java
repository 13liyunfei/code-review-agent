package com.codereview.agent.core.model;

/**
 * 单文件代码变更（Diff）。
 *
 * <p>预处理阶段从 Git Webhook / Git CLI 获取 PR 的 Diff 后被构造，
 * 作为各审查 Agent 的输入单元。
 *
 * @param fileName   变更文件名（含相对路径）
 * @param patch      该文件的 unified diff 文本
 * @param language   语言类型（如 java、go、py），用于选择对应的检测规则
 * @param addedLines 新增行数（来自 diff 统计）
 * @param delLines   删除行数
 */
public record CodeDiff(
        String fileName,
        String patch,
        String language,
        int addedLines,
        int delLines) {

    /**
     * 便捷构造：仅含文件名与 patch，语言按扩展名推断。
     *
     * @param fileName 文件名
     * @param patch    diff 文本
     */
    public CodeDiff(String fileName, String patch) {
        this(fileName, patch, inferLanguage(fileName), 0, 0);
    }

    /**
     * 根据文件扩展名推断语言类型。
     *
     * <p>取值对齐业界通用命名（tree-sitter grammar / GitHub linguist），而非自造缩写。
     * 这一点很关键：语言标识是分析引擎的**路由键**，若写成 {@code py} / {@code js}
     * 这类非标准缩写，注册表里按 {@code python} / {@code javascript} 注册就永远匹配不上，
     * 结果是「支持了该语言却静默不生效」——排查时极难发现。
     *
     * <p>历史上本方法把 {@code .py} 映射为 {@code py}、{@code .ts} 映射为 {@code js}
     * （TypeScript 被当成 JavaScript）。由于既有消费方全部只判断 {@code java}，
     * 修正这些取值不影响任何既有规则。
     *
     * @param fileName 文件名
     * @return 语言标识，未知返回 "unknown"
     */
    public static String inferLanguage(String fileName) {
        if (fileName == null) {
            return "unknown";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "unknown";
        }
        return switch (fileName.substring(dot + 1).toLowerCase()) {
            case "java" -> "java";
            case "go" -> "go";
            case "py" -> "python";
            case "js", "jsx", "mjs", "cjs" -> "javascript";
            case "ts" -> "typescript";
            case "tsx" -> "tsx";
            case "kt", "kts" -> "kotlin";
            case "rb" -> "ruby";
            case "rs" -> "rust";
            case "c" -> "c";
            case "h" -> "c";
            case "cpp", "cc", "cxx", "hpp" -> "cpp";
            case "cs" -> "c_sharp";
            case "php" -> "php";
            case "scala" -> "scala";
            case "sh", "bash" -> "bash";
            case "xml" -> "xml";
            case "sql" -> "sql";
            default -> "unknown";
        };
    }
}
