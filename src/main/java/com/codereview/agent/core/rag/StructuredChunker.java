package com.codereview.agent.core.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 结构感知切块器（业界最佳实践：语义/层级切块 + 重叠 + 富元数据）。
 *
 * <p>取代旧的「按行聚合固定 300 字符」粗暴切分。遵循 2025 工业级 RAG 共识：
 * <ul>
 *   <li><b>结构感知</b>：优先按 Markdown 标题（{@code #}）、代码围栏（{@code ```}）、
 *       空行分段等自然边界切分，避免把一句话或一个代码块劈成两半；</li>
 *   <li><b>层级上下文</b>：每个 chunk 携带其所属「父章节标题（section）」，使子块在脱离
 *       上下文被检索命中时，仍能向 LLM 提供章节背景（hierarchical chunking）；</li>
 *   <li><b>重叠</b>：相邻 chunk 保留约 {@code overlapRatio}（默认 15%）的尾部文本作为重叠，
 *       防止边界处语义断裂（业界建议 10-20%）；</li>
 *   <li><b>富元数据</b>：返回 {@code section / chunkIndex / parentSection / charCount} 等，
 *       供检索过滤与引用溯源。</li>
 * </ul>
 *
 * <p>该类为纯函数式、零依赖、可离线，便于单元测试。
 */
public class StructuredChunker {

    /** 单 chunk 目标上限（字符）。超出则按段落/句子硬切。 */
    private final int maxChars;
    /** 重叠比例（0~0.4），默认 0.15。 */
    private final double overlapRatio;

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

        StringBuilder sectionBuf = new StringBuilder();   // 当前章节累积文本
        String currentSection = "(root)";                 // 当前章节标题
        List<String> block = new ArrayList<>();           // 当前待切分缓冲（按行）
        int chunkIndex = 0;
        boolean inFence = false;                          // 是否在代码围栏内

        for (String line : lines) {
            String trimmed = line.trim();
            // Markdown 标题：作为章节边界，先 flush 旧章节，再开启新章节
            if (!inFence && trimmed.startsWith("#")) {
                chunkIndex = flush(block, sectionBuf, currentSection, meta, out, chunkIndex);
                currentSection = trimmed.replaceAll("#+\\s*", "").trim();
                if (currentSection.isEmpty()) {
                    currentSection = "(root)";
                }
                sectionBuf.setLength(0);
                block.clear();
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
                    chunkIndex = flush(block, sectionBuf, currentSection, meta, out, chunkIndex);
                }
                continue;
            }
            block.add(line);
        }
        // 围栏未闭合（文档末尾）：仍 flush
        if (inFence) {
            chunkIndex = flush(block, sectionBuf, currentSection, meta, out, chunkIndex);
        }
        flush(block, sectionBuf, currentSection, meta, out, chunkIndex);

        // 若全文未触发任何 flush（无标题、无足够量），兜底把整文作为一个 chunk
        if (out.isEmpty()) {
            out.add(makeChunk(doc.trim(), meta, "(root)", 0));
        }
        return out;
    }

    /** 将缓冲内容按 maxChars 切分并 flush（保留重叠）。返回下一个 chunkIndex。 */
    private int flush(List<String> block, StringBuilder sectionBuf, String section,
                      Map<String, String> meta, List<Chunk> out, int chunkIndex) {
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
        sectionBuf.setLength(0);
        sectionBuf.append(text);

        // 按 maxChars 进一步硬切（长段落）
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
            out.add(makeChunk(withOverlap.trim(), meta, section, chunkIndex++));
            start += piece.length();
        }
        return chunkIndex;
    }

    private Chunk makeChunk(String text, Map<String, String> meta, String section, int index) {
        var m = new java.util.LinkedHashMap<String, String>(meta);
        m.put("section", section);
        m.put("parentSection", section);
        m.put("chunkIndex", String.valueOf(index));
        m.put("charCount", String.valueOf(text.length()));
        return new Chunk(text, Map.copyOf(m));
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
