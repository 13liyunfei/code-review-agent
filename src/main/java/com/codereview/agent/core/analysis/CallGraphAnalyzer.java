package com.codereview.agent.core.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调用链与影响面分析器（无外部依赖）。
 *
 * <p>在已解析出的方法结构（{@link AstAnalyzer.AstReport}）基础上，扫描方法体内的方法调用表达式，
 * 构建「调用方 → 被调用方」关系；据此回答「改了这个方法，哪些上游调用方会受影响」。
 *
 * <p>能力边界：以单文件为单位（PR diff 通常跨文件较少），做方法级有向调用图；
 * 跨文件/跨模块调用需配合全量索引，此处给出文件内影响面（已覆盖绝大多数「局部改动」场景）。
 */
public final class CallGraphAnalyzer {

    private CallGraphAnalyzer() {
    }

    /** 调用图分析结果。 */
    public record CallGraphReport(
            Map<String, Set<String>> callees,   // 方法 -> 它调用的其他方法
            Map<String, Set<String>> callers) {  // 方法 -> 调用它的其他方法

        /**
         * 计算某个方法被哪些方法（传递闭包）调用，即「改动它会影响的上游」。
         *
         * @param method 方法名
         * @return 受影响的调用方方法名集合（不含自身）
         */
        public Set<String> impact(String method) {
            Set<String> visited = new LinkedHashSet<>();
            Deque<String> q = new ArrayDeque<>(callers.getOrDefault(method, Set.of()));
            while (!q.isEmpty()) {
                String cur = q.poll();
                if (!visited.add(cur)) {
                    continue;
                }
                for (String up : callers.getOrDefault(cur, Set.of())) {
                    if (!up.equals(method)) {
                        q.add(up);
                    }
                }
            }
            return visited;
        }
    }

    private static final Pattern CALL = Pattern.compile("([A-Za-z_]\\w*)\\s*\\(");
    private static final Set<String> KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "new", "return", "throw",
            "synchronized", "assert", "this", "super", "try", "do", "else");

    /**
     * 分析源码调用关系。
     *
     * @param source 完整源码（重建后）
     * @param report 对应的 AST 报告（提供方法行区间）
     * @return 调用图报告
     */
    public static CallGraphReport analyze(String source, AstAnalyzer.AstReport report) {
        Map<String, Set<String>> callees = new HashMap<>();
        Map<String, Set<String>> callers = new HashMap<>();
        if (source == null || report == null) {
            return new CallGraphReport(callees, callers);
        }

        List<AstAnalyzer.MethodInfo> methods = allMethods(report);
        String[] lines = source.split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            AstAnalyzer.MethodInfo owner = enclosingMethod(methods, lineNo);
            if (owner == null) {
                continue;
            }
            Matcher m = CALL.matcher(lines[i]);
            while (m.find()) {
                String callee = m.group(1);
                if (KEYWORDS.contains(callee)) {
                    continue;
                }
                callees.computeIfAbsent(owner.name(), k -> new HashSet<>()).add(callee);
                callers.computeIfAbsent(callee, k -> new HashSet<>()).add(owner.name());
            }
        }

        return new CallGraphReport(callees, callers);
    }

    private static List<AstAnalyzer.MethodInfo> allMethods(AstAnalyzer.AstReport report) {
        List<AstAnalyzer.MethodInfo> r = new ArrayList<>();
        for (AstAnalyzer.ClassInfo ci : report.classes()) {
            r.addAll(ci.methods());
        }
        // 顶层方法（未归入任何类型，理论上极少）
        return r;
    }

    private static AstAnalyzer.MethodInfo enclosingMethod(List<AstAnalyzer.MethodInfo> methods, int line) {
        for (AstAnalyzer.MethodInfo m : methods) {
            if (line >= m.startLine() && line <= m.endLine()) {
                return m;
            }
        }
        return null;
    }
}
