package com.codereview.agent.core.analysis.spi;

import java.util.List;

/**
 * 单个文件的分析结果——引擎对外输出的统一结构。
 *
 * <h2>关于 {@code calleeSignature} 可能为 null</h2>
 * {@link CallSite#calleeSignature()} 在 {@link CodeAnalyzer.Capability#FILE_LOCAL}
 * 引擎下**恒为 null**（没有类型信息，无法解析目标声明）。这不是「没查到」，
 * 而是「这引擎查不了」。调用方应读 {@link #capability()} 区分二者，
 * 不要把 null 当成「无人调用」——那是本仓库踩过的坑。
 *
 * @param path        仓库内相对路径
 * @param language    语言标识（小写）
 * @param capability  产出本结果的引擎能力等级，决定各字段的可信范围
 * @param methods     本文件内的方法声明
 * @param callSites   本文件内的调用点
 * @param complete    true=基于完整文件分析；false=只拿到片段（容错解析），结论可能不全
 * @param failure     解析失败原因；null 表示解析成功
 */
public record AnalysisUnit(
        String path,
        String language,
        CodeAnalyzer.Capability capability,
        List<MethodDecl> methods,
        List<CallSite> callSites,
        boolean complete,
        String failure
) {

    public static AnalysisUnit of(String path, String language,
                                  CodeAnalyzer.Capability capability,
                                  List<MethodDecl> methods, List<CallSite> callSites) {
        return new AnalysisUnit(path, language, capability, List.copyOf(methods),
                List.copyOf(callSites), true, null);
    }

    /** 容错解析（输入是片段而非完整文件）产出的结果，标记 complete=false。 */
    public static AnalysisUnit partial(String path, String language,
                                       CodeAnalyzer.Capability capability,
                                       List<MethodDecl> methods, List<CallSite> callSites) {
        return new AnalysisUnit(path, language, capability, List.copyOf(methods),
                List.copyOf(callSites), false, null);
    }

    public static AnalysisUnit failed(String path, String language, String reason) {
        return new AnalysisUnit(path, language, null, List.of(), List.of(), false, reason);
    }

    public boolean ok() {
        return failure == null;
    }

    /**
     * 方法声明。
     *
     * @param ownerType    归属类型。CROSS_FILE 引擎给出全限定名，FILE_LOCAL 给出简单类名
     * @param name         方法名
     * @param signature    唯一标识。CROSS_FILE 为全限定签名（含参数类型，可区分重载，
     *                     如 {@code com.demo.Service.audit(java.lang.String)}）；
     *                     FILE_LOCAL 退化为 {@code ownerType#name}
     * @param startLine    起始行（1-based）
     * @param endLine      结束行（1-based，含）
     * @param branches     圈复杂度近似（分支/循环/条件表达式计数）
     */
    public record MethodDecl(
            String ownerType,
            String name,
            String signature,
            int startLine,
            int endLine,
            int branches
    ) {
        /** 方法体行数（含声明行）。 */
        public int length() {
            return Math.max(1, endLine - startLine + 1);
        }
    }

    /**
     * 一次方法调用。
     *
     * @param callerSignature 调用方方法签名
     * @param calleeName      被调用方法名（简单名，恒有值）
     * @param calleeSignature 被调用方全限定签名；FILE_LOCAL 引擎下为 null
     * @param line            调用发生行（1-based）
     * @param resolved        是否成功解析到目标声明
     */
    public record CallSite(
            String callerSignature,
            String calleeName,
            String calleeSignature,
            int line,
            boolean resolved
    ) {
        public static CallSite unresolved(String caller, String calleeName, int line) {
            return new CallSite(caller, calleeName, null, line, false);
        }
    }
}
