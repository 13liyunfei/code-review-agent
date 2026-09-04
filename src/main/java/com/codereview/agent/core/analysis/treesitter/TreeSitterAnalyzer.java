package com.codereview.agent.core.analysis.treesitter;

import com.codereview.agent.core.analysis.spi.AnalysisUnit;
import com.codereview.agent.core.analysis.spi.CodeAnalyzer;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 非 Java 语言引擎：tree-sitter，能力等级 {@link Capability#FILE_LOCAL}。
 */
public class TreeSitterAnalyzer implements CodeAnalyzer {

    private static final Set<String> CLASS_TYPES = Set.of(
            "class_definition", "class_declaration", "interface_declaration", "record_declaration");

    @Override
    public String name() {
        return "tree-sitter";
    }

    @Override
    public boolean supports(String language) {
        return TreeSitterLanguages.find(language).isPresent();
    }

    @Override
    public Capability capability() {
        return Capability.FILE_LOCAL;
    }

    @Override
    public AnalysisUnit analyze(String path, String language, String source) {
        var specOpt = TreeSitterLanguages.find(language);
        if (specOpt.isEmpty()) {
            return AnalysisUnit.failed(path, language, "不支持的语言: " + language);
        }
        var spec = specOpt.get();
        if (source == null || source.isBlank()) {
            return AnalysisUnit.failed(path, language, "源码为空");
        }

        TSLanguage grammar;
        TSNode root;
        try {
            grammar = spec.grammar().get();
            TSParser parser = new TSParser();
            parser.setLanguage(grammar);
            root = parser.parseString(null, source).getRootNode();
        } catch (Throwable t) {
            // native 库缺失或平台不支持时不能拖垮整次审查
            return AnalysisUnit.failed(path, language,
                    "tree-sitter 不可用: " + t.getClass().getSimpleName());
        }
        if (root == null) {
            return AnalysisUnit.failed(path, language, "解析结果为空");
        }

        Ctx ctx = new Ctx(spec, source);
        walk(root, null, ctx);

        // hasError 说明输入不是语法完整的单元（片段/截断），结论可能不全
        return root.hasError()
                ? AnalysisUnit.partial(path, language, capability(), ctx.methods, ctx.calls)
                : AnalysisUnit.of(path, language, capability(), ctx.methods, ctx.calls);
    }

    private static final class Ctx {
        final TreeSitterLanguages.LanguageSpec spec;
        final String src;
        final List<AnalysisUnit.MethodDecl> methods = new ArrayList<>();
        final List<AnalysisUnit.CallSite> calls = new ArrayList<>();

        Ctx(TreeSitterLanguages.LanguageSpec spec, String src) {
            this.spec = spec;
            this.src = src;
        }
    }

    private void walk(TSNode node, String ownerType, Ctx ctx) {
        String type = node.getType();

        // 进入 class/interface 容器 → 更新归属类型名
        String owner = ownerType;
        if (CLASS_TYPES.contains(type)) {
            String n = fieldText(node, "name", ctx.src);
            if (n != null) owner = n;
        }

        if (ctx.spec.functionNodeTypes().contains(type)) {
            String name = fieldText(node, "name", ctx.src);
            if (name != null) {
                int start = node.getStartPoint().getRow() + 1;
                int end = node.getEndPoint().getRow() + 1;
                String signature = (owner == null ? "(module)" : owner) + "#" + name;
                int branches = countMatching(node, ctx.spec.branchNodeTypes());
                ctx.methods.add(new AnalysisUnit.MethodDecl(
                        owner == null ? "(module)" : owner,
                        name, signature, start, end, branches));

                collectCalls(node, signature, ctx);
                // 继续深入：嵌套函数（Python 内定义函数、JS 闭包）也应作为独立方法识别
            }
        }

        for (int i = 0; i < node.getNamedChildCount(); i++) {
            walk(node.getNamedChild(i), owner, ctx);
        }
    }

    /** 在方法子树内收集调用点。FILE_LOCAL 无法解析目标声明，故 signature 恒为 null。 */
    private void collectCalls(TSNode methodNode, String callerSignature, Ctx ctx) {
        collectCallsRecursive(methodNode, callerSignature, ctx);
    }

    private void collectCallsRecursive(TSNode node, String callerSignature, Ctx ctx) {
        if (ctx.spec.callNodeTypes().contains(node.getType())) {
            String callee = calleeName(node, ctx.src);
            if (callee != null) {
                ctx.calls.add(AnalysisUnit.CallSite.unresolved(
                        callerSignature, callee, node.getStartPoint().getRow() + 1));
            }
        }
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            collectCallsRecursive(node.getNamedChild(i), callerSignature, ctx);
        }
    }

    /**
     * 取被调用方法名。
     *
     * <p>各语言形状不同，但都挂在 {@code function} 字段上：
     * Python 是 {@code identifier} 或 {@code attribute}（取 attribute 部分），
     * JS/TS 是 {@code identifier} 或 {@code member_expression}（取 property 部分），
     * Go 是 {@code identifier} 或 {@code selector_expression}（取 field 部分）。
     * 统一策略：取该字段的**最后一个具名子节点**——对 attribute/member/selector
     * 而言正是方法名本身，对裸 identifier 而言就是它自己。
     */
    private String calleeName(TSNode callNode, String src) {
        TSNode fn = childByFieldName(callNode, "function");
        if (fn == null) return null;
        if (fn.getNamedChildCount() == 0) {
            return slice(fn, src);
        }
        TSNode last = fn.getNamedChild(fn.getNamedChildCount() - 1);
        return last == null ? null : slice(last, src);
    }

    private int countMatching(TSNode root, Set<String> types) {
        int[] n = {0};
        countRecursive(root, types, n);
        return n[0];
    }

    private void countRecursive(TSNode node, Set<String> types, int[] acc) {
        if (types.contains(node.getType())) acc[0]++;
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            countRecursive(node.getNamedChild(i), types, acc);
        }
    }

    private String fieldText(TSNode node, String field, String src) {
        TSNode c = childByFieldName(node, field);
        return c == null ? null : slice(c, src);
    }

    /**
     * 取字段子节点。bonede 绑定的 {@code getChildByFieldName} 在字段不存在时
     * 行为依赖 native 返回值，这里统一按「越界即无」处理。
     */
    private TSNode childByFieldName(TSNode node, String field) {
        try {
            return node.getChildByFieldName(field);
        } catch (Throwable t) {
            return null;
        }
    }

    private String slice(TSNode node, String src) {
        int s = node.getStartByte();
        int e = node.getEndByte();
        if (s < 0 || e > src.length() || s >= e) return null;
        return src.substring(s, e);
    }
}
