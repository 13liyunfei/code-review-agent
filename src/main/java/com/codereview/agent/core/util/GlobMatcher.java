package com.codereview.agent.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * gitignore 风格 glob 匹配（送审闸门与规则解析共用同一套语义）。
 *
 * <p>为什么要单独抽出来而不是各写一份：本项目有<b>两处</b>按路径模式判定的逻辑——
 * 送审闸门（哪些文件不送审）与规则解析（哪条规则适用于这个文件）。两处若各自实现 glob，
 * 迟早出现「闸门认得的写法规则层不认」这类分裂；更糟的是这种分裂没有任何报错，
 * 只表现为「某个规则文件明明配了却不生效」。
 *
 * <p>支持：{@code **}（跨目录）、{@code *}（不跨目录）、{@code ?}（单个非分隔符字符）。
 * <b>不含 {@code /} 的 glob 自动补 {@code **}{@code /} 前缀</b>——用户写 {@code *.jar}
 * 时期望的是「任意目录下的 jar」，而不是「仓库根目录下的 jar」。
 */
public final class GlobMatcher {

    private GlobMatcher() {
    }

    /** 编译单条 glob（区分大小写）。 */
    public static Pattern compile(String glob) {
        return compile(glob, false);
    }

    /**
     * 编译单条 glob。
     *
     * @param glob            规则串
     * @param caseInsensitive 是否忽略大小写（安全类判据用 true：macOS / Windows 上
     *                        {@code .ENV} 与 {@code .env} 是同一个文件，判据不应比文件系统更窄）
     * @return 锚定 Pattern；空串或 null 返回 null
     */
    public static Pattern compile(String glob, boolean caseInsensitive) {
        if (glob == null) {
            return null;
        }
        String g = glob.trim();
        if (g.isEmpty()) {
            return null;
        }
        if (g.indexOf('/') < 0) {
            g = "**/" + g;
        }
        StringBuilder re = new StringBuilder("^");
        int i = 0;
        int n = g.length();
        while (i < n) {
            char c = g.charAt(i);
            if (c == '*') {
                if (i + 1 < n && g.charAt(i + 1) == '*') {
                    if (i + 2 < n && g.charAt(i + 2) == '/') {
                        re.append("(?:.*/)?");   // **/ 允许零层目录（根目录下的目标也算命中）
                        i += 3;
                    } else {
                        re.append(".*");
                        i += 2;
                    }
                } else {
                    re.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                re.append("[^/]");
                i++;
            } else {
                re.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        re.append('$');
        return Pattern.compile(re.toString(), caseInsensitive ? Pattern.CASE_INSENSITIVE : 0);
    }

    /**
     * 批量编译（跳过空串）。
     *
     * @param globs 规则串列表（可为 null）
     * @param caseInsensitive 是否忽略大小写
     * @return 已编译 Pattern 列表
     */
    public static List<Pattern> compileAll(List<String> globs, boolean caseInsensitive) {
        List<Pattern> out = new ArrayList<>();
        if (globs == null) {
            return out;
        }
        for (String g : globs) {
            Pattern p = compile(g, caseInsensitive);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    /** 任一条命中即返回 true。 */
    public static boolean matchesAny(List<Pattern> patterns, String path) {
        if (patterns == null || path == null) {
            return false;
        }
        for (Pattern p : patterns) {
            if (p.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 统一路径形态：反斜杠转正斜杠、去掉前导 {@code ./} 与 {@code /}。
     *
     * @param path 原始路径
     * @return 规范化路径
     */
    public static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String p = path.replace('\\', '/').trim();
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }
}
