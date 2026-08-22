package com.codereview.agent.core.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量级 AST 语义分析器（无外部依赖）。
 *
 * <p>相比纯正则文本匹配，本分析器通过括号匹配还原源码的结构层级
 * （类 / 方法 / 代码块），并提取：方法签名、方法体长度、圈复杂度近似值、最大嵌套深度。
 * 用以支撑「结构级」审查发现（超长方法、高复杂度、深嵌套），而非逐行正则。
 *
 * <p>说明：为保持离线可编译、零外部依赖，这里采用手写词法扫描 + 括号栈还原，
 * 不引入 JavaParser。对常规 Java 代码覆盖率足够，极端语法（如字符串内括号）已做噪声剔除。
 */
public final class AstAnalyzer {

    private AstAnalyzer() {
    }

    /** 方法结构信息。 */
    public record MethodInfo(String name, int startLine, int endLine,
                             int length, int branches, int maxNesting) {
    }

    /** 类型（类/接口/枚举）结构信息。 */
    public record ClassInfo(String name, int startLine, int endLine, List<MethodInfo> methods) {
    }

    /** 一个文件的结构化分析报告。 */
    public record AstReport(String fileName, List<ClassInfo> classes,
                            int totalLines, int totalMethods,
                            int longMethodCount, int complexMethodCount) {
    }

    /** 方法签名识别：修饰符 + 返回类型 + 方法名 + 形参列表 + '{'。 */
    private static final Pattern METHOD_SIG = Pattern.compile(
            "(?:[\\w.<>\\[\\],\\s$]+\\s+)([A-Za-z_]\\w*)\\s*\\(([^;]*?)\\)\\s*(?:throws[\\w,\\s.<>]+)?\\{");

    private static final Pattern TYPE_DEC = Pattern.compile(
            "\\b(class|interface|enum|@interface)\\s+([A-Za-z_]\\w*)");

    private static final Pattern BRANCH = Pattern.compile(
            "\\b(if|for|while|switch|catch)\\b|\\?|&&|\\|\\|");

    private static final java.util.Set<String> KEYWORDS = java.util.Set.of(
            "if", "for", "while", "switch", "catch", "new", "return", "throw",
            "synchronized", "assert", "this", "super");

    /**
     * 分析源码，产出结构化报告。
     *
     * @param source   完整源码文本（可从 diff 重建，见 AdvancedAnalyzer）
     * @param fileName 文件名（仅用于报告标注）
     * @return 结构化分析报告
     */
    public static AstReport analyze(String source, String fileName) {
        String clean = stripNoise(source);
        String[] lines = clean.split("\n", -1);

        List<ClassInfo> classes = new ArrayList<>();
        List<MethodInfo> methods = new ArrayList<>();

        // 括号栈：记录每个代码块的起始行与所属类型（type / method）
        Deque<Block> stack = new ArrayDeque<>();
        int totalLines = lines.length;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineNo = i + 1;
            int open = countChar(line, '{');
            int close = countChar(line, '}');

            // 处理行首出现的 '{'：它可能开启一个新块（类型或方法）
            // 简化处理：在遇到 '{' 前，先判断上一行/当前行是否声明了类型或方法
            if (open > 0) {
                Block parent = stack.peek();
                // 判断块类型：优先方法签名，其次类型声明
                String methodName = matchMethod(line, lines, i);
                String typeName = matchType(line, lines, i);
                String name;
                boolean isMethod;
                if (methodName != null) {
                    name = methodName;
                    isMethod = true;
                } else if (typeName != null) {
                    name = typeName;
                    isMethod = false;
                } else if (parent != null && parent.isMethod) {
                    // 内部块（if/for/while/匿名类等），继承方法上下文
                    name = parent.name;
                    isMethod = true;
                } else {
                    name = parent == null ? "<root>" : parent.name;
                    isMethod = parent != null && parent.isMethod;
                }
                for (int k = 0; k < open; k++) {
                    stack.push(new Block(name, lineNo, isMethod, parent == null ? 0 : parent.depth + 1));
                }
            }

            // 统计：当处于方法体内时，累计分支与嵌套
            Block cur = stack.peek();
            if (cur != null && cur.isMethod) {
                // 找到最靠近的方法块（栈中第一个 isMethod）
                Block methodBlock = findMethodBlock(stack);
                if (methodBlock != null) {
                    methodBlock.branches += countBranches(line);
                    methodBlock.curDepth += open - close;
                    methodBlock.maxNesting = Math.max(methodBlock.maxNesting, methodBlock.curDepth);
                }
            }

            for (int k = 0; k < close; k++) {
                Block popped = stack.pollFirst();
                if (popped != null && popped.isMethod) {
                    int len = lineNo - popped.startLine + 1;
                    MethodInfo mi = new MethodInfo(popped.name, popped.startLine, lineNo,
                            len, popped.branches, popped.maxNesting);
                    methods.add(mi);
                } else if (popped != null) {
                    List<MethodInfo> ms = filterMethods(methods, popped.startLine, lineNo);
                    classes.add(new ClassInfo(popped.name, popped.startLine, lineNo, ms));
                }
            }
        }

        // 栈未闭合的兜底（语法残缺）：把剩余方法/类型补上
        while (!stack.isEmpty()) {
            Block b = stack.pollFirst();
            if (b.isMethod) {
                methods.add(new MethodInfo(b.name, b.startLine, totalLines,
                        totalLines - b.startLine + 1, b.branches, b.maxNesting));
            } else {
                classes.add(new ClassInfo(b.name, b.startLine, totalLines,
                        filterMethods(methods, b.startLine, totalLines)));
            }
        }

        int longCount = 0;
        int complexCount = 0;
        for (MethodInfo m : methods) {
            if (m.length() > 60) {
                longCount++;
            }
            if (m.branches() > 10) {
                complexCount++;
            }
        }

        return new AstReport(fileName, classes, totalLines, methods.size(), longCount, complexCount);
    }

    // ===================== 内部工具 =====================

    private static final class Block {
        final String name;
        final int startLine;
        final boolean isMethod;
        final int depth;
        int branches;
        int curDepth;
        int maxNesting;

        Block(String name, int startLine, boolean isMethod, int depth) {
            this.name = name;
            this.startLine = startLine;
            this.isMethod = isMethod;
            this.depth = depth;
            this.curDepth = depth;
            this.maxNesting = depth;
            this.branches = 0;
        }
    }

    private static Block findMethodBlock(Deque<Block> stack) {
        for (Block b : stack) {
            if (b.isMethod) {
                return b;
            }
        }
        return null;
    }

    private static List<MethodInfo> filterMethods(List<MethodInfo> methods, int from, int to) {
        List<MethodInfo> r = new ArrayList<>();
        for (MethodInfo m : methods) {
            if (m.startLine() >= from && m.endLine() <= to) {
                r.add(m);
            }
        }
        return r;
    }

    private static String matchMethod(String line, String[] lines, int idx) {
        // 拼接当前行与上一行（签名可能折行）
        String ctx = (idx > 0 ? lines[idx - 1] : "") + " " + line;
        Matcher m = METHOD_SIG.matcher(ctx);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private static String matchType(String line, String[] lines, int idx) {
        String ctx = (idx > 0 ? lines[idx - 1] : "") + " " + line;
        Matcher m = TYPE_DEC.matcher(ctx);
        if (m.find()) {
            return m.group(2);
        }
        return null;
    }

    private static int countBranches(String line) {
        Matcher m = BRANCH.matcher(line);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** 剔除注释与字符串字面量，避免其中括号/关键字干扰结构解析。 */
    private static String stripNoise(String source) {
        if (source == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        boolean blockComment = false;
        int i = 0;
        char[] cs = source.toCharArray();
        while (i < cs.length) {
            char c = cs[i];
            if (blockComment) {
                if (c == '*' && i + 1 < cs.length && cs[i + 1] == '/') {
                    blockComment = false;
                    i += 2;
                    continue;
                }
                if (c == '\n') {
                    sb.append('\n');
                }
                i++;
                continue;
            }
            if (c == '/' && i + 1 < cs.length && cs[i + 1] == '/') {
                while (i < cs.length && cs[i] != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < cs.length && cs[i + 1] == '*') {
                blockComment = true;
                i += 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < cs.length && cs[i] != quote) {
                    if (cs[i] == '\\') {
                        i++;
                    }
                    i++;
                }
                i++; // 跳过结束引号
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}
