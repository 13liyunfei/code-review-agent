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
            case "py" -> "py";
            case "js", "ts" -> "js";
            case "kt" -> "kotlin";
            case "xml" -> "xml";
            case "sql" -> "sql";
            default -> "unknown";
        };
    }
}
