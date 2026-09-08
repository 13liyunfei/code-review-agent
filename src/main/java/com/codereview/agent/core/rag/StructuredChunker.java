package com.codereview.agent.core.rag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构感知切块器（业界最佳实践：语义/层级切块 + 重叠 + 富元数据）。
 *
 * <p>取代旧的「按行聚合固定 300 字符」粗暴切分。遵循 2025 工业级 RAG 共识：
 * <ul>
 *   <li><b>结构感知</b>：优先按 Markdown 标题（{@code #}）、代码围栏（{@code ```}）、
 *       空行分段等自然边界切分，避免把一句话或一个代码块劈成两半；</li>
 *   <li><b>真层级上下文</b>：按 {@code #} 数量维护标题栈，为每个 chunk 生成
 *       {@code headingPath}（{@code A > B > C}）与<b>真实父章节</b> {@code parentSection}——
 *       早期版本把 {@code parentSection} 直接写成 {@code section}（假层级），
 *       导致「检索到叶子块却拿不到父章节」的父子检索无法实现；</li>
 *   <li><b>标题进正文</b>：chunk 正文前缀携带 {@code 【headingPath】}。
 *       标题只进 metadata 不进 body 会有双重损失——嵌入时丢掉章节语义、
 *       注入时 LLM 看不到该块属于哪一章；</li>
 *   <li><b>父子回填（small-to-big）</b>：为每个块附加 {@code parentExcerpt}
 *       （父章节首段摘要，≤300 字符），供注入阶段把命中叶子块放回父章节语境；</li>
 *   <li><b>重叠</b>：相邻 chunk 保留约 {@code overlapRatio}（默认 15%）的尾部文本作为重叠，
 *       防止边界处语义断裂（业界建议 10-20%）；</li>
 *   <li><b>富元数据</b>：返回 {@code section / parentSection / headingPath /
 *       parentExcerpt / chunkIndex / charCount} 等，供检索过滤与引用溯源。</li>
 * </ul>
 *
 * <p>该类为纯函数式、零依赖、可离线，便于单元测试；切分状态全部走局部变量
 * （{@code InMemoryKnowledgeStore} 等处共享单例，实例字段会引入并发问题）。
 */
public class StructuredChunker {

    /** 单 chunk 目标上限（字符）。超出则按段落/句子硬切。 */
    private final int maxChars;
    /** 重叠比例（0~0.4），默认 0.15。 */
    private final double overlapRatio;
    /** 父章节摘要最大字符数（small-to-big 回填用）。 */
    private static final int PARENT_EXCERPT_MAX = 300;

    public StructuredChunker(int maxChars, double overlapRatio) {
        if (maxChars <= 50) {
            throw new IllegalArgumentException("maxChars 必须 > 50");
        }
        if (overlapRatio < 0 || overlapRatio > 0.4) {
            throw new IllegalArgumentException("overlapRatio 必须在 [0, 0.4]");
        }
        this.maxChars = maxChars;
        this.overlapRatio = overlapRatio;
    }

    public StructuredChunker() {
        this(700, 0.15);
    }

    /** 切块结果：含文本与富元数据。 */
    public record Chunk(String text, Map<String, String> metadata) {
    }

    /**
     * 将整篇文档切分为结构感知 chunk。
     *
     * @param doc      原始文档（Markdown / 纯文本 / 代码稿）
     * @param baseMeta 基础元数据（如 source / type / kbId），会合并进每个 chunk
     * @return chunk 列表（至少 1 个，空文档返回单空块）
     */
    public List<Chunk> chunk(String doc, Map<String, String> baseMeta) {
        List<Chunk> out = new ArrayList<>();
        if (doc == null || doc.isBlank()) {
            return out;
        }
        Map<String, String> meta = baseMeta == null ? Map.of() : baseMeta;
        String[] lines = doc.split("\n", -1);

        Deque<String> headingStack = new ArrayDeque<>();   // 标题层级栈（按 # 数量维护）
        List<String> block = new ArrayList<>();            // 当前待切分缓冲（按行）
        int chunkIndex = 0;
        boolean inFence = false;                           // 是否在代码围栏内

        for (String line : lines) {
            String trimmed = line.trim();
            // Markdown 标题：作为章节边界，先 flush 旧章节，再按层级维护标题栈
            if (!inFence && trimmed.startsWith("#")) {
                chunkIndex = flush(block, meta, out, chunkIndex, headingStack);
                pushHeading(headingStack, trimmed);
                continue;
            }
            // 代码围栏（成对 ```）：整段作为整体块，不按行/标题劈断
            if (trimmed.startsWith("```")) {
                if (!inFence) {
                    inFence = true;
                    block.add(line);
                } else {
                    block.add(line);
                    inFence = false;
                    // 闭合围栏：作为一个完整块 flush
                    chunkIndex = flush(block, meta, out, chunkIndex, headingStack);
                }
                continue;
            }
            block.add(line);
        }
        // 围栏未闭合（文档末尾）：仍 flush
        if (inFence) {
            chunkIndex = flush(block, meta, out, chunkIndex, headingStack);
        }
        flush(block, meta, out, chunkIndex, headingStack);

        // 若全文未触发任何 flush（无标题、无足够量），兜底把整文作为一个 chunk
        if (out.isEmpty()) {
            out.add(makeChunk(doc.trim(), meta, List.of(), 0));
        }
        // 后处理：为有父章节的块回填父章节摘要（small-to-big 上下文）
        return withParentExcerpts(out);
    }

    /** 按 {@code #} 数量维护标题栈：同级顶掉旧的，更深的层叠在其下。 */
    private static void pushHeading(Deque<String> stack, String headingLine) {
        int level = 0;
        while (level < headingLine.length() && headingLine.charAt(level) == '#') {
            level++;
        }
        String title = headingLine.replaceAll("#+\\s*", "").trim();
        if (title.isEmpty()) {
            title = "(root)";
        }
        while (stack.size() >= Math.max(level, 1)) {
            stack.pollLast();
        }
        stack.addLast(title);
    }

    /** 将缓冲内容按 maxChars 切分并 flush（保留重叠）。返回下一个 chunkIndex。 */
    private int flush(List<String> block, Map<String, String> meta, List<Chunk> out,
                      int chunkIndex, Deque<String> headingStack) {
        if (block.isEmpty()) {
            return chunkIndex;
        }
        StringBuilder full = new StringBuilder();
        for (String l : block) {
            full.append(l).append('\n');
        }
        String text = full.toString().trim();
        block.clear();
        if (text.isEmpty()) {
            return chunkIndex;
        }

        // 按 maxChars 进一步硬切（长段落）
        List<String> path = new ArrayList<>(headingStack);
        List<String> pieces = splitBySize(text, maxChars);
        int start = 0;
        for (int i = 0; i < pieces.size(); i++) {
            String piece = pieces.get(i);
            String withOverlap;
            if (i > 0 && start > 0) {
                int overlapLen = (int) (piece.length() * overlapRatio);
                overlapLen = Math.min(overlapLen, start);
                withOverlap = text.substring(start - overlapLen, start) + "\n" + piece;
            } else {
                withOverlap = piece;
            }
            out.add(makeChunk(withOverlap.trim(), meta, path, chunkIndex++));
            start += piece.length();
        }
        return chunkIndex;
    }

    private Chunk makeChunk(String text, Map<String, String> meta, List<String> path, int index) {
        var m = new LinkedHashMap<String, String>(meta);
        String section = path.isEmpty() ? "(root)" : path.get(path.size() - 1);
        // 真层级：父章节取标题栈的上一层（早期版本直接写成 section，父子检索无法成立）
        String parentSection = path.size() >= 2 ? path.get(path.size() - 2) : "(root)";
        String headingPath = path.isEmpty() ? "(root)" : String.join(" > ", path);
        // 标题链进正文：嵌入侧不丢章节语义、注入侧 LLM 看得到归属
        // （只进 metadata 会两头受损：嵌入少语义、LLM 只从 source 括号里猜章节）
        String body = path.isEmpty() ? text : "【" + headingPath + "】\n" + text;
        m.put("section", section);
        m.put("parentSection", parentSection);
        m.put("headingPath", headingPath);
        m.put("chunkIndex", String.valueOf(index));
        m.put("charCount", String.valueOf(body.length()));
        return new Chunk(body, Map.copyOf(m));
    }

    /**
     * 为每个块回填父章节摘要（small-to-big 上下文）：命中叶子块时，注入阶段可把父章节语境一起带上，
     * 避免「只拿到一条孤立条款、却不知道它属于哪一章的什么约定」。
     * 父章节取其首个 chunk 正文并截断；无父章节（顶层）则原样返回。
     */
    private List<Chunk> withParentExcerpts(List<Chunk> chunks) {
        Map<String, String> firstByPath = new LinkedHashMap<>();
        for (Chunk c : chunks) {
            firstByPath.putIfAbsent(c.metadata().getOrDefault("headingPath", ""), c.text());
        }
        List<Chunk> result = new ArrayList<>(chunks.size());
        for (Chunk c : chunks) {
            String parentPath = parentPathOf(c.metadata().getOrDefault("headingPath", ""));
            String excerpt = parentPath == null ? null : firstByPath.get(parentPath);
            if (excerpt == null || excerpt.isBlank()) {
                result.add(c);
                continue;
            }
            var m = new LinkedHashMap<>(c.metadata());
            m.put("parentExcerpt", excerpt.length() > PARENT_EXCERPT_MAX
                    ? excerpt.substring(0, PARENT_EXCERPT_MAX) : excerpt);
            result.add(new Chunk(c.text(), Map.copyOf(m)));
        }
        return result;
    }

    /** {@code A > B > C} → {@code A > B}；无父章节返回 null。 */
    private static String parentPathOf(String headingPath) {
        int idx = headingPath.lastIndexOf(" > ");
        return idx <= 0 ? null : headingPath.substring(0, idx);
    }

    /** 按字符上限切分（保留句子完整性：优先在换行/句号处断）。 */
    private List<String> splitBySize(String text, int max) {
        List<String> pieces = new ArrayList<>();
        if (text.length() <= max) {
            pieces.add(text);
            return pieces;
        }
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(i + max, text.length());
            // 尝试在 end 前回退到最近换行/句号，避免切断句子
            int cut = end;
            if (end < text.length()) {
                int lastNl = text.lastIndexOf('\n', end);
                int lastDot = Math.max(text.lastIndexOf('。', end), text.lastIndexOf('.', end));
                int best = Math.max(lastNl, lastDot);
                if (best > i + max / 2) {
                    cut = best + 1;
                }
            }
            pieces.add(text.substring(i, cut).trim());
            i = cut;
        }
        return pieces;
    }
}
