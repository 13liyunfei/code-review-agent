package com.codereview.agent.core.analysis.index;

import com.codereview.agent.core.analysis.java.JavaParserAnalyzer;
import com.codereview.agent.core.analysis.spi.CodeAnalyzer;
import com.codereview.agent.core.analysis.treesitter.TreeSitterAnalyzer;
import com.codereview.agent.core.analysis.treesitter.TreeSitterLanguages;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 分析引擎路由：按语言把文件分派给合适的引擎。
 *
 * <h2>分派规则</h2>
 * <ul>
 *   <li>{@code java} → {@link JavaParserAnalyzer}（CROSS_FILE，能做跨文件符号解析）；</li>
 *   <li>其余已注册语言 → {@link TreeSitterAnalyzer}（FILE_LOCAL，仅文件内）。</li>
 * </ul>
 *
 * <p>Java 之所以单独走 JavaParser 而非统一用 tree-sitter，是因为影响面分析的核心诉求
 * 「谁调用了我」依赖符号解析，而 tree-sitter 不提供——详见 {@link CodeAnalyzer} 的说明。
 */
public final class AnalysisEngines {

    /** tree-sitter 引擎无状态，可安全复用。 */
    private final TreeSitterAnalyzer treeSitter = new TreeSitterAnalyzer();
    private final int maxMethodLines;

    public AnalysisEngines(int maxMethodLines) {
        this.maxMethodLines = maxMethodLines;
    }

    public static AnalysisEngines defaults() {
        return new AnalysisEngines(0);
    }

    /** 该语言是否有引擎能处理。 */
    public boolean supported(String language) {
        if (language == null || language.isEmpty()) return false;
        return "java".equals(language) || TreeSitterLanguages.find(language).isPresent();
    }

    /**
     * 取语言对应的引擎。
     *
     * @param language    语言标识
     * @param sourceRoots 已物化的源码根列表（如 {@code <tmp>/src/main/java}）。
     *                    必须是源码根而非仓库根——JavaParser 按「源码根 + 包路径」定位类型；
     *                    传错不报错，只是所有跨文件符号解析静默失败
     * @return 引擎；不支持的语言返回空
     */
    public Optional<CodeAnalyzer> forLanguage(String language, List<Path> sourceRoots) {
        if (!supported(language)) return Optional.empty();
        if ("java".equals(language)) {
            return Optional.of(new JavaParserAnalyzer(sourceRoots, maxMethodLines));
        }
        return Optional.of(treeSitter);
    }

    /** 便捷重载：单源码根。 */
    public Optional<CodeAnalyzer> forLanguage(String language, Path sourceRoot) {
        return forLanguage(language, sourceRoot == null ? List.of() : List.of(sourceRoot));
    }
}
