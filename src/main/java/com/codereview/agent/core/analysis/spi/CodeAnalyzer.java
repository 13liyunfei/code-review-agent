package com.codereview.agent.core.analysis.spi;

/**
 * 代码分析引擎的统一抽象。
 *
 * <h2>为什么要有这层</h2>
 * 不同语言能拿到的分析深度差得很远，而且这个差距是**原理性的**、不是实现程度问题：
 * <ul>
 *   <li>Java 有成熟的符号解析器（JavaParser 的 symbol-solver），能做类型推断与重载消歧，
 *       因此能回答「全仓库谁调用了这个方法」；</li>
 *   <li>其余语言走 tree-sitter，它只产出语法树，**不做符号解析**（作用域、类型推断、
 *       重载消歧都得自己写，那是编译器前端的工作量），因此只能回答「本文件内谁调用了它」。</li>
 * </ul>
 * 抽象层的作用不是把两者伪装成一样的能力，而是让**能力差异显式化**：上层先读
 * {@link #capability()}，再决定能输出什么结论。这样「为什么 Go 看不到跨文件调用方」
 * 就不再是谜，而是引擎自己声明的。
 *
 * <h2>能力分级的意义</h2>
 * 若不加分级，多语言支持会退化成「各语言返回一样的结构，但字段悄悄为 null」，
 * 调用方无从判断是「确实没有调用方」还是「这引擎根本查不了」——这正是静默失效的温床
 * （本仓库 AST 层曾因类似原因在真实 PR 上恒产出 0 条结论而无人察觉）。
 */
public interface CodeAnalyzer {

    /**
     * 本引擎能处理的语言标识（小写，来自 {@code CodeDiff.language()}）。
     *
     * @param language 语言标识，如 {@code java} / {@code python} / {@code go}
     * @return true 表示由本引擎处理
     */
    boolean supports(String language);

    /**
     * 本引擎的分析能力等级。**调用方必须先读它**，再决定如何使用结果。
     *
     * @return 能力等级，不会为 null
     */
    Capability capability();

    /**
     * 分析单个文件的完整源码。
     *
     * <p>语言由调用方显式传入而非从文件扩展名推断：扩展名可能缺失、
     * 多义（{@code .m}、{@code .h}），或与实际内容不符；
     * 而 {@code CodeDiff} 本身就带着解析好的语言标识。
     *
     * @param path     仓库内相对路径（用于日志与结论定位）
     * @param language 语言标识（小写），与 {@link #supports(String)} 同一套取值
     * @param source   文件**全量**内容。必须是完整文件而非 diff 片段——
     *                 JavaParser 这类解析器要求语法完整的编译单元，喂 hunk 片段会直接解析失败。
     *                 tree-sitter 容错能力强，片段也能出结果，但会标记
     *                 {@link AnalysisUnit#complete()}=false 提示结论不完整。
     * @return 分析结果；解析失败时返回 {@link AnalysisUnit#failed(String, String, String)}
     */
    AnalysisUnit analyze(String path, String language, String source);

    /** 引擎名称，用于日志与诊断。 */
    String name();

    /**
     * 分析能力等级。
     */
    enum Capability {
        /**
         * 仅文件内分析：能给出方法声明与本文件内的调用关系，但**没有类型信息**，
         * 同名方法无法消歧，跨文件调用方查不到。
         */
        FILE_LOCAL,

        /**
         * 跨文件分析：方法调用能解析到全限定签名（含参数类型，可区分重载），
         * 因此可以在仓库范围内精确定位调用方。
         */
        CROSS_FILE
    }
}
