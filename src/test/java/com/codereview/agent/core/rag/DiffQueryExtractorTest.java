package com.codereview.agent.core.rag;

import com.codereview.agent.core.model.CodeDiff;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DiffQueryExtractor} 单测：把 diff 提炼成结构化检索查询，而非裸 patch 截断。
 */
class DiffQueryExtractorTest {

    private static final String UNIFIED_DIFF = """
            --- a/src/main/java/com/demo/UserService.java
            +++ b/src/main/java/com/demo/UserService.java
            @@ -10,6 +10,8 @@ public User loadUser(String id) {
                 public User loadUser(String id) {
            -        String sql = "select * from user where id = " + id;
            +        String sql = "select * from user where id = ?";
            +        PreparedStatement ps = conn.prepareStatement(sql);
                 }
            """;

    @Test
    void extractsFileClassMethodAndSymbolsFromUnifiedDiff() {
        String q = DiffQueryExtractor.extract(List.of(
                new CodeDiff("src/main/java/com/demo/UserService.java", UNIFIED_DIFF)));
        assertTrue(q.contains("UserService.java"), "应含变更文件，实际 " + q);
        assertTrue(q.contains("类 UserService"), "应从文件名推出类名，实际 " + q);
        assertTrue(q.contains("方法"), "应含 hunk header 的方法名，实际 " + q);
        assertTrue(q.contains("符号"), "应含改动行标识符，实际 " + q);
        assertTrue(q.contains("PreparedStatement") || q.contains("prepareStatement"),
                "应包含关键符号 PreparedStatement，实际 " + q);
        // 关键：不再把整个 patch 塞进查询（旧实现取前 500 字符）
        assertTrue(q.length() < UNIFIED_DIFF.length(), "结构化查询应比裸 patch 更精炼");
        assertTrue(!q.contains("--- a/"), "不应包含 diff 文件头噪音");
    }

    @Test
    void nonUnifiedDiffFallsBackToAllIdentifiers() {
        // 非标准 diff（无 +/- 前缀，如 IDE 插件 / 手工构造片段）：
        // 若只扫改动行会提炼出「只有文件名」的空壳查询，须退化为整段抽标识符
        String q = DiffQueryExtractor.extract(List.of(
                new CodeDiff("Demo.java", "String sql = \"select * from user where id=\" + id; // sql 拼接")));
        assertTrue(q.contains("sql"), "非标准 diff 也应提炼出关键符号 sql，实际 " + q);
    }

    @Test
    void emptyOrNullInputsAreSafe() {
        assertEquals("", DiffQueryExtractor.extract(null));
        assertEquals("", DiffQueryExtractor.extract(List.of()));
    }

    @Test
    void pureChineseFallsBackToRawPatch() {
        // 完全没有标识符的中文片段：结构化信息不足 → 退回原文截断，绝不返回空查询
        String q = DiffQueryExtractor.extract(List.of(
                new CodeDiff("说明.md", "这是一段没有任何标识符的中文说明文档，用于验证兜底路径。")));
        assertTrue(!q.isBlank(), "兜底路径也必须有查询，不能为空");
        assertTrue(q.contains("中文说明"), "兜底应保留原文内容，实际 " + q);
    }
}
