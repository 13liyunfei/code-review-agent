package com.codereview.agent.core.analysis.java;

import com.codereview.agent.core.analysis.spi.AnalysisUnit;
import com.codereview.agent.core.analysis.spi.CodeAnalyzer;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Java 引擎：JavaParser + symbol-solver，能力等级 {@link Capability#CROSS_FILE}。
 *
 * <h2>为什么 Java 不用 tree-sitter</h2>
 * tree-sitter 只产出语法树、不做符号解析，要拿「谁调用了这个方法」得自己实现
 * 作用域分析、类型推断、重载消歧——那是编译器前端的工作量。而 JavaParser 的
 * symbol-solver 直接给出全限定签名（含参数类型，天然区分重载），跨文件调用图
 * 只需在其上建一层倒排索引。
 *
 * <h2>为什么必须喂全量文件</h2>
 * JavaParser 要求语法完整的编译单元。喂 diff 的 hunk 片段会直接解析失败
 * （报 {@code Parse error. Found "void", expected one of "class" "enum" ...}）。
 * 这不是可以容错绕过的限制——它是 JavaParser 能提供精确符号解析的前提。
 * 因此调用方必须先取到完整文件内容。
 *
 * <h2>解析失败的处理</h2>
 * 第三方库（不在仓库源码树内）的调用无法解析，这是**预期行为**而非错误：
 * 影响面分析关心的是仓库内部的调用方。故解析失败时降级为
 * {@link AnalysisUnit.CallSite#unresolved}，保留方法名，不丢弃整个方法的其他结论。
 */
public class JavaParserAnalyzer implements CodeAnalyzer {

    private final CombinedTypeSolver typeSolver;
    private final ParserConfiguration config;
    private final int maxMethodLines;

    /**
     * @param sourceRoots     <b>源码根</b>目录列表（如 {@code <tmp>/src/main/java}、
     *                        {@code <tmp>/src/test/java}），不是仓库根目录。
     *                        JavaParserTypeSolver 按「源码根 + 包路径」定位类型文件，
     *                        给成仓库根会导致所有跨文件符号解析失败——
     *                        表现为调用点 {@code resolved=false}，且**不报错**，只是结论为空。
     *                        为 null 或空时退化为单文件分析（跨文件解析不可用）
     * @param maxMethodLines  超过此行数的方法体跳过分析（防止巨型文件耗尽内存），0 表示不限
     */
    public JavaParserAnalyzer(List<Path> sourceRoots, int maxMethodLines) {
        this.typeSolver = new CombinedTypeSolver(new ReflectionTypeSolver(false));
        if (sourceRoots != null) {
            for (Path p : sourceRoots) {
                if (p != null && Files.isDirectory(p)) {
                    this.typeSolver.add(new JavaParserTypeSolver(p));
                }
            }
        }
        this.config = new ParserConfiguration()
                .setSymbolResolver(new JavaSymbolSolver(typeSolver))
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.maxMethodLines = maxMethodLines;
    }

    public JavaParserAnalyzer(List<Path> sourceRoots) {
        this(sourceRoots, 0);
    }

    /** 单源码根便捷构造。 */
    public JavaParserAnalyzer(Path sourceRoot) {
        this(sourceRoot == null ? List.of() : List.of(sourceRoot), 0);
    }

    @Override
    public String name() {
        return "javaparser";
    }

    @Override
    public boolean supports(String language) {
        return "java".equals(language);
    }

    @Override
    public Capability capability() {
        return Capability.CROSS_FILE;
    }

    @Override
    public AnalysisUnit analyze(String path, String language, String source) {
        if (!supports(language)) {
            return AnalysisUnit.failed(path, language, "JavaParserAnalyzer 只处理 java，收到: " + language);
        }
        ParseResult<CompilationUnit> result;
        try {
            result = new JavaParser(config).parse(source);
        } catch (Throwable t) {
            // 解析器本身抛出（如栈溢出、OOM 前兆）时不能拖垮整次审查
            return AnalysisUnit.failed(path, "java", "解析异常: " + t.getClass().getSimpleName());
        }
        if (!result.isSuccessful() || result.getResult().isEmpty()) {
            String reason = result.getProblems().isEmpty()
                    ? "无法解析（可能不是完整 Java 文件）"
                    : result.getProblems().get(0).getMessage();
            return AnalysisUnit.failed(path, "java", reason);
        }

        CompilationUnit cu = result.getResult().get();
        List<AnalysisUnit.MethodDecl> methods = new ArrayList<>();
        List<AnalysisUnit.CallSite> callSites = new ArrayList<>();

        for (TypeDeclaration<?> type : cu.getTypes()) {
            String owner = qualifiedName(cu, type);
            for (MethodDeclaration m : type.getMethods()) {
                if (maxMethodLines > 0 && lineSpan(m) > maxMethodLines) {
                    continue;
                }
                String signature = resolveSignature(m)
                        .orElseGet(() -> owner + "." + m.getNameAsString() + "(?)");
                methods.add(new AnalysisUnit.MethodDecl(
                        owner,
                        m.getNameAsString(),
                        signature,
                        m.getBegin().map(p -> p.line).orElse(-1),
                        m.getEnd().map(p -> p.line).orElse(-1),
                        countBranches(m)));
                collectCalls(m, signature, callSites);
            }
        }
        return AnalysisUnit.of(path, "java", capability(), methods, callSites);
    }

    /**
     * 提取方法调用并尝试解析到全限定签名。
     *
     * <p>解析失败（第三方库、反射调用、源码树不完整）时降级为 unresolved，
     * 保留方法名——宁可只有个名字，也不要连「这里发生了一次调用」都丢掉。
     */
    private void collectCalls(MethodDeclaration method, String callerSignature,
                              List<AnalysisUnit.CallSite> out) {
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            int line = call.getBegin().map(p -> p.line).orElse(-1);
            String calleeName = call.getNameAsString();
            String calleeSignature = null;
            try {
                ResolvedMethodDeclaration rd = call.resolve();
                calleeSignature = rd.getQualifiedSignature();
            } catch (Throwable ignored) {
                // 未解析：可能是第三方库或本仓库源码树未覆盖该文件
            }
            out.add(calleeSignature == null
                    ? AnalysisUnit.CallSite.unresolved(callerSignature, calleeName, line)
                    : new AnalysisUnit.CallSite(callerSignature, calleeName, calleeSignature, line, true));
        }
    }

    private Optional<String> resolveSignature(MethodDeclaration m) {
        try {
            return Optional.of(m.resolve().getQualifiedSignature());
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private String qualifiedName(CompilationUnit cu, TypeDeclaration<?> type) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
        String simple = type instanceof ClassOrInterfaceDeclaration c ? c.getNameAsString()
                : type instanceof EnumDeclaration e ? e.getNameAsString()
                : type instanceof RecordDeclaration r ? r.getNameAsString()
                : type.getNameAsString();
        // 内部类：JavaParser 的 FQN 用 Outer.Inner 表示
        return pkg + simple;
    }

    /** 圈复杂度近似：分支、循环、条件表达式、短路运算符、catch、case。 */
    private int countBranches(MethodDeclaration m) {
        int n = 0;
        n += m.findAll(IfStmt.class).size();
        n += m.findAll(ForStmt.class).size();
        n += m.findAll(ForEachStmt.class).size();
        n += m.findAll(WhileStmt.class).size();
        n += m.findAll(DoStmt.class).size();
        n += m.findAll(CatchClause.class).size();
        n += m.findAll(SwitchEntry.class).size();
        n += m.findAll(ConditionalExpr.class).size();
        for (BinaryExpr e : m.findAll(BinaryExpr.class)) {
            if (e.getOperator() == BinaryExpr.Operator.AND
                    || e.getOperator() == BinaryExpr.Operator.OR) {
                n++;
            }
        }
        return n;
    }

    private int lineSpan(Node n) {
        int a = n.getBegin().map(p -> p.line).orElse(0);
        int b = n.getEnd().map(p -> p.line).orElse(0);
        return Math.max(0, b - a + 1);
    }
}
