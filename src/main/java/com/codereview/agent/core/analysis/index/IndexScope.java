package com.codereview.agent.core.analysis.index;

/**
 * 仓库索引的扫描范围。
 *
 * <h2>为什么不能全量扫描</h2>
 * 索引需要逐文件走一次 HTTP（Gitea 没有「批量取文件内容」的端点），
 * 全量扫描一个几千文件的仓库会产生几千次请求，在审查热路径上不可接受。
 * 因此按「被改文件 → 同包 → 一跳引用」逐层扩展，并用 {@link #maxFiles()} 硬限流。
 *
 * <h2>一跳的语义</h2>
 * 只拉「直接引用了被改类的文件」，不再追这些文件的引用者（那是两跳）。
 * 一跳已能覆盖绝大多数真实影响面：改动一个方法，关心的首要问题是
 * 「谁直接调了我」，而非「谁调用了调用我的人」。
 *
 * @param includeSamePackage 是否纳入被改文件的同目录文件（Java 下同包调用最常见）
 * @param resolveImports     是否解析被改文件的 import 并拉取仓库内对应文件（一跳）
 * @param maxFiles           单次索引的文件总数上限，超出即停止扩展（硬限流，防止大仓库拖垮审查）
 */
public record IndexScope(boolean includeSamePackage, boolean resolveImports, int maxFiles) {

    /** 默认：同包 + 一跳 + 200 文件上限。 */
    public static final IndexScope DEFAULT = new IndexScope(true, true, 200);

    /**
     * 只分析被改文件本身（不做跨文件）。
     *
     * <p>注意 {@code maxFiles} 是<b>安全上限</b>而非「要拉多少个」：此处两个扩展开关都关闭，
     * 实际只会拉被改文件，上限沿用 {@link #DEFAULT} 即可。若填 0 会被当成
     * 「不允许拉任何文件」，导致索引为空——那是把上限当成配额用的典型错误。
     */
    public static final IndexScope CHANGED_ONLY =
            new IndexScope(false, false, DEFAULT.maxFiles());

    /** 工厂方法：参数非法时回退到默认，避免调用方传 0 导致什么都拉不到。 */
    public static IndexScope of(boolean samePackage, boolean resolveImports, int maxFiles) {
        if (maxFiles <= 0) {
            return new IndexScope(samePackage, resolveImports, DEFAULT.maxFiles());
        }
        return new IndexScope(samePackage, resolveImports, maxFiles);
    }
}
