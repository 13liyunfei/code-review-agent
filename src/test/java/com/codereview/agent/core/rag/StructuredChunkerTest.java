package com.codereview.agent.core.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖维度 ④：结构感知 / 层级 Chunking + 富元数据。
 *
 * 验证 StructuredChunker 在以下场景的行为：
 *  - 按 Markdown 标题切分章节（层级上下文）；
 *  - 代码围栏（```）成对整体保留，不被按行劈断；
 *  - 长段落按 maxChars 硬切并保留重叠；
 *  - 每个 chunk 携带 section / chunkIndex / parentSection / charCount 富元数据；
 *  - 边界：空文档、非法构造参数。
 */
class StructuredChunkerTest {

    private final StructuredChunker chunker = new StructuredChunker();

    @Test
    void emptyDocumentProducesNoChunks() {
        List<StructuredChunker.Chunk> chunks = chunker.chunk("", Map.of());
        assertTrue(chunks.isEmpty());
        assertTrue(chunker.chunk("   \n  ", Map.of()).isEmpty());
    }

    @Test
    void headingsCreateSeparateSectionsWithHierarchyMeta() {
        String doc = """
                # 第一章 安全规范
                第一条：禁止明文密码。
                # 第二章 性能规范
                第二条：避免 N+1 查询。
                """;
        List<StructuredChunker.Chunk> chunks = chunker.chunk(doc, Map.of("source", "handbook"));
        assertEquals(2, chunks.size());

        // 每个 chunk 的 section 元数据对应其所属标题
        assertEquals("第一章 安全规范", chunks.get(0).metadata().get("section"));
        assertEquals("第二章 性能规范", chunks.get(1).metadata().get("section"));
        // 真层级：一级标题没有父章节 → (root)（旧实现把 parentSection 直接写成 section，是假层级）
        assertEquals("(root)", chunks.get(0).metadata().get("parentSection"),
                "一级章节的父章节应为 (root)，而非自身");
        // 标题链：headingPath 记录完整层级路径
        assertEquals("第一章 安全规范", chunks.get(0).metadata().get("headingPath"));
        // 标题进正文：嵌入侧不丢章节语义、注入侧 LLM 看得到归属
        assertTrue(chunks.get(0).text().startsWith("【第一章 安全规范】"),
                "章节标题链应作为正文前缀进入 chunk，而非只进 metadata");
        // 基础元数据被合并进每个 chunk
        assertEquals("handbook", chunks.get(0).metadata().get("source"));
    }

    @Test
    void nestedHeadingsProduceRealParentSectionAndExcerpt() {
        String doc = """
                # 第一章 安全规范
                本章适用于所有后端服务。
                ## 1.1 认证与密码
                第一条：禁止明文存储密码。
                ## 1.2 注入防护
                第二条：禁止字符串拼接 SQL。
                """;
        List<StructuredChunker.Chunk> chunks = chunker.chunk(doc, Map.of("source", "handbook"));
        // 父章节（第一章）+ 两个子章节
        assertTrue(chunks.size() >= 3, "应有父章节块与两个子章节块，实际 " + chunks.size());

        StructuredChunker.Chunk child = chunks.stream()
                .filter(c -> "1.1 认证与密码".equals(c.metadata().get("section")))
                .findFirst().orElseThrow();
        // 真父子：子章节的 parentSection 是「第一章 安全规范」，不是自己
        assertEquals("第一章 安全规范", child.metadata().get("parentSection"));
        assertEquals("第一章 安全规范 > 1.1 认证与密码", child.metadata().get("headingPath"));
        assertTrue(child.text().startsWith("【第一章 安全规范 > 1.1 认证与密码】"));

        // small-to-big：父章节摘要回填，供注入阶段把叶子块放回父章节语境
        String excerpt = child.metadata().get("parentExcerpt");
        assertNotNull(excerpt, "子章节块应携带父章节摘要（parentExcerpt）");
        assertTrue(excerpt.contains("本章适用于所有后端服务"),
                "父章节摘要应来自父章节正文，实际：" + excerpt);
    }

    @Test
    void codeFencePreservedAsWholeBlock() {
        String doc = """
                # 示例代码
                下面是推荐的写法：
                ```java
                public void demo() {
                    // 整段代码
                    System.out.println("hello");
                }
                ```
                以上为示例。
                """;
        List<StructuredChunker.Chunk> chunks = chunker.chunk(doc, Map.of());
        // 标题后的正文 + 闭合围栏触发 flush；结尾正文再 flush —— 至少为 2 块
        assertTrue(chunks.size() >= 2);
        // 至少有一块包含完整代码围栏（不被按行劈断）
        boolean hasFullFence = chunks.stream()
                .anyMatch(c -> c.text().contains("```java")
                        && c.text().contains("System.out.println")
                        && c.text().contains("```"));
        assertTrue(hasFullFence, "代码围栏应作为整体块保留");
    }

    @Test
    void longParagraphSplitsAndCarriesOverlapWithCharCountMeta() {
        // 构造一个远大于 maxChars(700) 的单段纯文本，触发硬切 + 重叠
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("这是用于测试结构感知切分的较长句子内容用来触发硬性切分逻辑并且验证重叠是否正确保留。");
        }
        String doc = sb.toString();
        List<StructuredChunker.Chunk> chunks = chunker.chunk(doc, Map.of());
        assertTrue(chunks.size() > 1, "超长段落应被切分为多块");
        // 重叠验证：第 i 块尾部应与第 i+1 块头部存在公共子串
        boolean overlapFound = false;
        for (int i = 0; i < chunks.size() - 1; i++) {
            String a = chunks.get(i).text();
            String b = chunks.get(i + 1).text();
            // 取 a 最后 20 字符，检查是否出现在 b 开头附近
            String tail = a.length() > 20 ? a.substring(a.length() - 20) : a;
            if (b.contains(tail.trim())) {
                overlapFound = true;
                break;
            }
        }
        assertTrue(overlapFound, "相邻 chunk 应保留重叠文本");
        // 每块都写入 charCount 元数据
        for (StructuredChunker.Chunk c : chunks) {
            assertTrue(c.metadata().containsKey("charCount"));
            int cnt = Integer.parseInt(c.metadata().get("charCount"));
            assertEquals(c.text().length(), cnt);
        }
    }

    @Test
    void chunkIndexSequentialAndBaseMetaMerged() {
        String doc = "# A\nsome text\n# B\nother text\n# C\nmore text";
        List<StructuredChunker.Chunk> chunks = chunker.chunk(doc, Map.of("kbId", "kb1"));
        assertEquals(3, chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(String.valueOf(i), chunks.get(i).metadata().get("chunkIndex"));
            assertEquals("kb1", chunks.get(i).metadata().get("kbId"));
        }
    }

    @Test
    void invalidConstructorArgsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StructuredChunker(50, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new StructuredChunker(700, 0.5));
    }

    @Test
    void noHeadingDocumentFallsBackToSingleChunk() {
        String doc = "没有任何标题的纯文本段落，应当整段作为一个 chunk 兜底返回。";
        List<StructuredChunker.Chunk> chunks = chunker.chunk(doc, Map.of());
        assertEquals(1, chunks.size());
        assertEquals("(root)", chunks.get(0).metadata().get("section"));
    }

    @Test
    void nullBaseMetaTreatedAsEmpty() {
        List<StructuredChunker.Chunk> chunks = chunker.chunk("# H\nbody", null);
        assertEquals(1, chunks.size());
        assertFalse(chunks.get(0).metadata().containsKey("source"));
    }
}
