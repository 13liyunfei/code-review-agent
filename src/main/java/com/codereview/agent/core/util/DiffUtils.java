package com.codereview.agent.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diff 解析工具：从 unified diff 文本中按行定位匹配项，并映射为新文件行号。
 *
 * <p>规则型 Agent 借助本工具在补丁文本中检索风险模式，并给出尽量准确的
 * 行号（依据 hunk 头 {@code @@ -a,b +c,d @@} 推算新增行号），便于开发者定位。
 */
public final class DiffUtils {

    private DiffUtils() {
    }

    /**
     * 匹配结果：命中行号（新文件）与命中行文本。
     *
     * @param lineNumber 新文件中的行号（1-based）
     * @param lineText   命中的整行文本
     */
    public record Match(int lineNumber, String lineText) {
    }

    /**
     * 扫描补丁，返回所有满足谓词的“新增/上下文”行及其行号。
     *
     * @param patch     unified diff 文本
     * @param predicate 行匹配条件（已去除前导 +/- 标记）
     * @return 命中列表（按出现顺序）
     */
    public static List<Match> findMatches(String patch, Predicate<String> predicate) {
        List<Match> matches = new ArrayList<>();
        if (patch == null || patch.isBlank()) {
            return matches;
        }
        int newLine = 0;
        for (String raw : patch.split("\n", -1)) {
            if (raw.startsWith("@@")) {
                // hunk 头：@@ -oldStart,oldCount +newStart,newCount @@
                newLine = parseNewStart(raw);
                continue;
            }
            if (raw.startsWith("-")) {
                // 仅删除行，不影响新文件行号
                continue;
            }
            // 新增行（+）或上下文行（空格）：均计入新文件
            String content = raw.length() > 0 ? raw.substring(1) : "";
            if (predicate.test(content)) {
                matches.add(new Match(newLine, content));
            }
            newLine++;
        }
        return matches;
    }

    /**
     * 扫描补丁，返回所有满足正则的“新增/上下文”行及其行号。
     *
     * @param patch   unified diff 文本
     * @param pattern 正则（匹配整行内容）
     * @return 命中列表
     */
    public static List<Match> findPattern(String patch, Pattern pattern) {
        return findMatches(patch, line -> {
            Matcher m = pattern.matcher(line);
            return m.find();
        });
    }

    /**
     * 从 hunk 头解析新文件起始行号。
     *
     * @param hunkHeader 如 "@@ -10,5 +20,6 @@"
     * @return 新文件起始行号，解析失败返回 0
     */
    private static int parseNewStart(String hunkHeader) {
        // 匹配 +数字 或 +数字,数字
        Pattern p = Pattern.compile("\\+(\\d+)(?:,\\d+)?");
        Matcher m = p.matcher(hunkHeader);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }
}
