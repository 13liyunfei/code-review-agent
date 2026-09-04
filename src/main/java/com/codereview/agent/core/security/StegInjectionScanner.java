package com.codereview.agent.core.security;

import java.util.ArrayList;
import java.util.List;

/**
 * 隐写注入扫描器：检测 diff 新增行中的「不可见 / 方向控制字符」藏匿指令。
 *
 * <p><b>为什么需要它（2025 年实锤攻击入口）：</b>关键词检测与语义检测都作用于「可见字符」，
 * 而真实代码审查事故（Copilot CVE-2025-53773、Pillar Security "Rules File Backdoor"）多用
 * <b>零宽字符</b>（U+200B 等）或 <b>Bidi 覆盖符</b>（U+202E 等）把指令拆散/反转藏进代码与
 * 规则文件——人类看不见、关键词正则匹配不到（{@code ignore\u200Bprevious} 拆词即绕过）。这类
 * 字符出现在源码 diff 中<b>本身就没有任何合法理由</b>，是零误报的确定性恶意信号，优先级高于语义层。
 *
 * <p><b>扫描边界（只扫新增行）：</b>PR diff 由 Git 服务端基于基线仓库真实对比生成——
 * 其中 {@code -} 删除行与 context 行<b>是基线已有内容，攻击者不可控</b>；攻击者能写入的只有
 * {@code +} 新增行（无论想污染合并后代码，还是想瞬时注入 review bot 的 LLM 上下文，都只能经
 * {@code +} 行）。故只扫新增行即可覆盖威胁面且零误报。按 hunk 头 {@code @@ -a,b +c,d @@}
 * 解析出<b>新文件行号</b>，便于 Finding 精确落点。
 *
 * <p><b>容错：</b>输入不一定是完整 unified diff（如短文本内容槽、无 hunk 头的裸片段）时，
 * 退化为「整段按新增内容扫描」，行号从 0 起算（仅保证命中、不保证行号精确）。
 */
public class StegInjectionScanner {

    /** 命中类型：零宽字符（视觉不可见，用于拆词绕过检测）。 */
    public static final String TYPE_ZERO_WIDTH = "ZERO_WIDTH";
    /** 命中类型：双向文本覆盖符（可反转视觉阅读顺序，用于把指令藏成“看似无害”）。 */
    public static final String TYPE_BIDI = "BIDI_OVERRIDE";
    /** 命中类型：危险控制符（NUL/ESC/DEL 等，可干扰下游文本处理管线）。 */
    public static final String TYPE_CONTROL = "CONTROL";

    /** 单条命中记录。 */
    public record Hit(int line, String type, String codepoint) {
        @Override
        public String toString() {
            return "line=" + line + " " + type + " " + codepoint;
        }
    }

    // ===== 字符集判定 =====

    /** 零宽 / 不可见格式字符（BMP 内逐 char 判定即可）。 */
    static boolean isZeroWidth(char c) {
        return (c >= 0x200B && c <= 0x200D)   // ZWSP / ZWNJ / ZWJ
                || c == 0x200E || c == 0x200F // LRM / RLM
                || (c >= 0x2060 && c <= 0x2064) // WJ / FUNCTION / INVISIBLE * / ZWJ-var 等不可见格式符
                || c == 0xFEFF               // BOM / ZWNBSP
                || c == 0x00AD               // 软连字符（视觉不可见）
                || c == 0x180E;              // 蒙古文元音分隔符（历史上不可见）
    }

    /** Bidi 方向覆盖符：可强制改变文本视觉呈现顺序。 */
    static boolean isBidiOverride(char c) {
        return (c >= 0x202A && c <= 0x202E)   // LRE / RLE / PDF / LRO / RLO
                || (c >= 0x2066 && c <= 0x2069); // LRI / RLI / FSI / PDI
    }

    /**
     * 危险控制符：C0 中除 \t \r \n 之外的全部 + DEL + C1 区。
     * \r \n \t 是源码合法空白，不算恶意。
     */
    static boolean isDangerousControl(char c) {
        return (c < 0x20 && c != '\t' && c != '\r' && c != '\n')
                || c == 0x7F
                || (c >= 0x80 && c <= 0x9F);
    }

    private static String typeOf(char c) {
        if (isZeroWidth(c)) {
            return TYPE_ZERO_WIDTH;
        }
        if (isBidiOverride(c)) {
            return TYPE_BIDI;
        }
        if (isDangerousControl(c)) {
            return TYPE_CONTROL;
        }
        return null;
    }

    /**
     * 扫描文本（unified diff 或裸文本）中的隐写注入字符。
     *
     * @param input diff / 文本
     * @return 命中列表（按出现顺序；无命中返回空列表）
     */
    public List<Hit> scan(String input) {
        List<Hit> hits = new ArrayList<>();
        if (input == null || input.isBlank()) {
            return hits;
        }
        boolean hasHunk = input.contains("@@");
        int newLine = 0; // 新文件行号游标：指向「下一个待处理内容行」的行号
        for (String raw : input.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (hasHunk) {
                // 完整 unified diff：按 hunk 语义推进新旧行号
                if (line.startsWith("@@")) {
                    newLine = parseNewStart(line);
                    continue;
                }
                if (line.startsWith("\\")) { // "\ No newline at end of file"
                    continue;
                }
                if (line.startsWith("+++") || line.startsWith("---")) {
                    continue;
                }
                if (line.startsWith("+")) {
                    // 新增行：先以当前游标作为其新文件行号扫描，再进位（context 与 + 行都占新文件行号）
                    scanLine(line.substring(1), newLine, hits);
                    newLine++;
                } else if (line.startsWith("-")) {
                    // 删除行：不进合并后代码，跳过（攻击者无法经删除行注入）
                } else {
                    // context 行（空格开头）：新旧文件都在，占用一个新文件行号
                    newLine++;
                }
            } else {
                // 裸片段 / 短文本（无 hunk 头）：无法区分 context，把 + 行与普通文本行都按新增内容扫
                if (line.startsWith("+") && !line.startsWith("+++")) {
                    scanLine(line.substring(1), newLine, hits);
                    newLine++;
                } else if (!line.startsWith("-")) {
                    scanLine(line, newLine, hits);
                    newLine++;
                }
            }
        }
        return hits;
    }

    /** 逐字符扫一行，命中即记录行号与码点。 */
    private void scanLine(String content, int line, List<Hit> hits) {
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            String type = typeOf(c);
            if (type != null) {
                hits.add(new Hit(line, type, String.format("U+%04X", (int) c)));
            }
        }
    }

    /** 解析 hunk 头中的新文件起始行号；解析失败返回 0（命中行号退化为计数）。 */
    private int parseNewStart(String hunkHeader) {
        int plus = hunkHeader.indexOf('+', 1);
        if (plus < 0) {
            return 0;
        }
        int comma = hunkHeader.indexOf(',', plus);
        int end = comma < 0 ? hunkHeader.indexOf(' ', plus) : comma;
        if (end < 0) {
            end = hunkHeader.length();
        }
        try {
            return Integer.parseInt(hunkHeader.substring(plus + 1, end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
