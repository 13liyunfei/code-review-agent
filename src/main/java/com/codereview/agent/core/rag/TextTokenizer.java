package com.codereview.agent.core.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 检索分词器：<b>中文 bigram + 英文/标识符子词</b>，供「PG 全文检索 / 内存 BM25 / 启发式重排」共用一套口径。
 *
 * <p><b>为什么需要它</b>：PostgreSQL 的 {@code simple} 词典既不做中文分词、也不做标识符拆分——
 * 无空格的中文长串会被当成一个整体 token（实测："禁止使用字符串拼接的sql" 是<b>一个</b>词位），
 * 于是 "参数绑定" 查 "…必须使用参数绑定…" 命中=false、连 "PreparedStatement" 都命中=false。
 * 稀疏路（BM25，权重 0.3）在中文规范库上等于空转。
 *
 * <p>本分词器把文本切成 {@code to_tsvector('simple', …)} / {@code plainto_tsquery('simple', …)}
 * 能吃的空格分隔词串，从而在不安装 {@code zhparser}/{@code pg_jieba} 的前提下让中文可用：
 * <ul>
 *   <li><b>中文</b>：连续 CJK 段按 <b>bigram</b> 切（"参数绑定" → 参数/数绑/绑定）——
 *       业界无词典分词场景的标准做法（MySQL ngram、ES CJK bigram），
 *       既避开词典依赖，又保证任意子串查询都能命中；</li>
 *   <li><b>英文/数字/标识符</b>：按非字母数字粗切，再做 camelCase / snake_case 子词拆分并转小写
 *       （{@code getUserById} → get/user/by/id），让代码符号与规范文档共享词位；</li>
 *   <li>单字 CJK 保留；ASCII 子词长度 ≥2 才保留（抑制噪音）。</li>
 * </ul>
 *
 * <p>写入侧与检索侧<b>必须使用同一分词器</b>，否则倒排词位对不上（比不分词的后果更隐蔽）。
 */
public final class TextTokenizer {

    /** 粗切分隔符：非字母、非数字、非 CJK 的一切（空格/标点/代码符号）。 */
    private static final Pattern NON_TOKEN = Pattern.compile("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
    /** camelCase 边界 / snake_case 下划线。 */
    private static final Pattern CAMEL_OR_SNAKE = Pattern.compile("(?<!^)(?=[A-Z])|_+");
    /** ASCII 子词最小长度（低于此值视为噪音）。 */
    private static final int MIN_ASCII_LEN = 2;

    private TextTokenizer() {
    }

    /**
     * 分词。
     *
     * @param text 原始文本（文档内容或查询）
     * @return 词集合（稳定顺序，去重）
     */
    public static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String raw : NON_TOKEN.split(text)) {
            if (raw.isEmpty()) {
                continue;
            }
            for (String seg : splitByScript(raw)) {
                if (containsCjk(seg)) {
                    addBigrams(out, seg);
                } else {
                    addAsciiSubwords(out, seg);
                }
            }
        }
        return out;
    }

    /**
     * 生成可喂给 {@code to_tsvector('simple', ?)} / {@code plainto_tsquery('simple', ?)} 的空格分词串。
     *
     * <p>PG 侧拿到的是「已分词 + 空格分隔」的文本，{@code simple} 词典只需按空格切即可生成正确词位。
     *
     * @param text 原始文本
     * @return 空格分隔的词串（无内容时返回空串）
     */
    public static String toTokenString(String text) {
        return String.join(" ", tokenize(text));
    }

    /**
     * 生成 {@code to_tsquery('simple', ?)} 可用的 OR 查询串（{@code a | b | c}）。
     *
     * <p>用 OR 而非 {@code plainto_tsquery} 的 AND：RAG 查询常是整段 diff / 长句，
     * AND 要求全部词命中，长查询几乎必然零命中；OR 保证任一关键词命中即可召回，
     * 再由 {@code ts_rank} 把多词命中的文档排到前面。词数上限避免超长查询拖慢检索。
     *
     * <p>安全性：输出仅含 CJK / 字母 / 数字，不含 {@code & | ! ( ) :} 等 tsquery 语法字符，
     * 不存在 tsquery 注入面。
     *
     * @param text     查询文本
     * @param maxTerms 参与检索的最大词数（超出部分截断）
     * @return OR 连接的 tsquery 串；无词时返回空串
     */
    public static String toTsQueryOr(String text, int maxTerms) {
        Set<String> toks = tokenize(text);
        if (toks.isEmpty()) {
            return "";
        }
        List<String> limited = new ArrayList<>(toks);
        int n = Math.min(maxTerms, limited.size());
        return String.join(" | ", limited.subList(0, n));
    }

    /** 按「CJK 连续段 / 非 CJK 连续段」切分，使中英混排串能被分别处理。 */
    private static List<String> splitByScript(String raw) {
        List<String> segs = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        Boolean curCjk = null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean cjk = isCjk(c);
            if (curCjk == null) {
                curCjk = cjk;
                buf.append(c);
            } else if (cjk == curCjk) {
                buf.append(c);
            } else {
                segs.add(buf.toString());
                buf.setLength(0);
                buf.append(c);
                curCjk = cjk;
            }
        }
        if (!buf.isEmpty()) {
            segs.add(buf.toString());
        }
        return segs;
    }

    /** CJK 段按 bigram 展开（单字直接保留）。 */
    private static void addBigrams(Set<String> out, String cjk) {
        if (cjk.length() == 1) {
            out.add(cjk);
            return;
        }
        for (int i = 0; i + 2 <= cjk.length(); i++) {
            out.add(cjk.substring(i, i + 2));
        }
    }

    /** 英文/数字段：camelCase / snake_case 子词拆分 + 小写。 */
    private static void addAsciiSubwords(Set<String> out, String seg) {
        // 全大写的缩写词（SQL / URL / HTTP / ID）不能被 camelCase 规则按大写边界切碎——
        // 「SQL」会变成 S/Q/L 三个单字母再被长度阈值丢弃，导致代码审查最高频的术语消失。
        if (isAllUpperCaseAscii(seg)) {
            addLower(out, seg);
            return;
        }
        for (String sub : CAMEL_OR_SNAKE.split(seg)) {
            addLower(out, sub);
        }
    }

    private static void addLower(Set<String> out, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.length() >= MIN_ASCII_LEN) {
            out.add(lower);
        }
    }

    /** 是否全为大写 ASCII（缩写词）：无小写字母即视为缩写。 */
    private static boolean isAllUpperCaseAscii(String seg) {
        boolean hasUpper = false;
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (Character.isLowerCase(c)) {
                return false;
            }
            if (Character.isUpperCase(c)) {
                hasUpper = true;
            }
        }
        return hasUpper;
    }

    private static boolean containsCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (isCjk(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCjk(char c) {
        return c >= '一' && c <= '龥';
    }
}
