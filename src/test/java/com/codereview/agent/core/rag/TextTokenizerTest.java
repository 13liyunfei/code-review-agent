package com.codereview.agent.core.rag;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TextTokenizer} 单测：中文 bigram + 标识符子词。
 *
 * <p>这是「PG 中文 BM25 能否命中」的地基——PG 的 {@code simple} 词典既不切中文也不拆标识符，
 * 必须由本分词器先把文本切成空格分隔的词串，倒排词位才可能对上。
 */
class TextTokenizerTest {

    @Test
    void chineseIsCutIntoBigrams() {
        Set<String> toks = TextTokenizer.tokenize("参数绑定");
        // bigram：参数 / 数绑 / 绑定 —— 任意二字子串查询都能命中
        assertTrue(toks.contains("参数"), "应含 bigram『参数』，实际 " + toks);
        assertTrue(toks.contains("绑定"), "应含 bigram『绑定』，实际 " + toks);
        assertTrue(toks.contains("数绑"), "应含 bigram『数绑』，实际 " + toks);
    }

    @Test
    void mixedChineseAndIdentifierBothTokenized() {
        Set<String> toks = TextTokenizer.tokenize("禁止拼接SQL，请使用PreparedStatement参数绑定");
        // 英文/标识符侧：camelCase 拆子词 + 小写
        assertTrue(toks.contains("sql"), "应拆出 sql，实际 " + toks);
        assertTrue(toks.contains("prepared"), "应拆出 prepared，实际 " + toks);
        assertTrue(toks.contains("statement"), "应拆出 statement，实际 " + toks);
        // 中文侧：bigram
        assertTrue(toks.contains("参数"), "应含中文 bigram『参数』，实际 " + toks);
    }

    @Test
    void camelCaseAndSnakeCaseShareSubwords() {
        Set<String> a = TextTokenizer.tokenize("getUserById");
        Set<String> b = TextTokenizer.tokenize("user_service");
        assertTrue(a.contains("user") && b.contains("user"), "标识符子词应共享 user，实际 " + a + " / " + b);
        assertTrue(a.contains("id"), "camelCase 应拆出 id，实际 " + a);
    }

    @Test
    void upperCaseAcronymsAreNotShredded() {
        // 回归：全大写缩写若按 camelCase 大写边界切分会变成 S/Q/L 单字母并被丢弃，
        // 而 SQL / URL / HTTP 正是代码审查最高频的术语
        assertTrue(TextTokenizer.tokenize("拼接SQL查询").contains("sql"), "SQL 应整体保留，实际 "
                + TextTokenizer.tokenize("拼接SQL查询"));
        assertTrue(TextTokenizer.tokenize("请求URL地址").contains("url"), "URL 应整体保留");
    }

    @Test
    void singleCjkCharAndShortAsciiHandled() {
        assertTrue(TextTokenizer.tokenize("锁").contains("锁"), "单字 CJK 应保留");
        // ASCII 子词长度 < 2 视为噪音丢弃
        assertTrue(TextTokenizer.tokenize("a b c dd").contains("dd"), "长度>=2 的 ASCII 子词应保留");
    }

    @Test
    void blankInputYieldsEmpty() {
        assertTrue(TextTokenizer.tokenize(null).isEmpty());
        assertTrue(TextTokenizer.tokenize("").isEmpty());
        assertTrue(TextTokenizer.tokenize("   ").isEmpty());
        assertEquals("", TextTokenizer.toTokenString(null));
    }

    @Test
    void toTokenStringIsSpaceJoinedForPgTsvector() {
        String s = TextTokenizer.toTokenString("参数绑定 PreparedStatement");
        assertTrue(s.contains(" "), "应空格分隔，供 to_tsvector('simple', …) 切词");
        assertTrue(s.contains("参数"), "应含中文 bigram，实际 " + s);
        assertTrue(s.contains("prepared"), "应含标识符子词，实际 " + s);
    }

    @Test
    void tsQueryOrJoinsWithOrAndRespectsMaxTerms() {
        String q = TextTokenizer.toTsQueryOr("参数绑定 prepared statement 注入", 40);
        assertTrue(q.contains(" | "), "应为 OR 连接（长查询用 AND 会零命中），实际 " + q);
        assertTrue(q.contains("参数"), "应含中文词，实际 " + q);

        String limited = TextTokenizer.toTsQueryOr("参数绑定 prepared statement 注入", 2);
        assertEquals(2, limited.split("\\|").length, "应受 maxTerms 限制，实际 " + limited);
        assertEquals("", TextTokenizer.toTsQueryOr("", 10), "空查询应返回空串（避免 to_tsquery 语法错误）");
    }
}
