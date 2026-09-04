package com.codereview.agent.core.security;

import org.springframework.stereotype.Component;

/**
 * 异常输入检测（Prompt Stuffing / 编码绕过检测）。
 *
 * <p>依据文档 Layer 1 思路：通过长度、信息熵、重复模式三类异常信号识别
 * 填充攻击与编码绕过，作为注入检测的补充维度。
 */
@Component
public class AnomalyDetector {

    /** 异常长输入阈值（疑似填充攻击）。 */
    private static final int MAX_INPUT_LENGTH = 50000;
    /** 高熵阈值（正常自然语言约 3.5~4.5，超阈值疑似 Base64/编码绕过）。 */
    private static final double ENTROPY_THRESHOLD = 5.5;
    /** 重复模式占比阈值（疑似 AAAAA... 填充）。 */
    private static final double REPETITION_THRESHOLD = 0.7;

    /**
     * 判断输入是否异常。
     *
     * @param input 输入文本
     * @return 异常返回 true
     */
    public boolean detect(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        if (input.length() > MAX_INPUT_LENGTH) {
            return true;
        }
        // 香农熵阈值按英文自然语言 / 编码负载（Base64≈6.0）标定；CJK 等宽字符集文本
        // 的字符级熵天然远超 5.5（如长中文描述的熵≈log2(去重字数)>6），逐字熵会对中文
        // 业务内容误伤。因此熵信号只在纯 ASCII 输入上生效（编码绕过负载一定是 ASCII）。
        if (isAsciiOnly(input) && calculateShannonEntropy(input) > ENTROPY_THRESHOLD) {
            return true;
        }
        return calculateRepetition(input) > REPETITION_THRESHOLD;
    }

    /** 是否纯 ASCII（编码绕过 / Base64 / 填充负载均为 ASCII；中文自然内容跳过熵判定）。 */
    private boolean isAsciiOnly(String input) {
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    /**
     * 计算香农熵（比特/字符）。
     */
    private double calculateShannonEntropy(String input) {
        int[] freq = new int[256];
        for (int i = 0; i < input.length(); i++) {
            freq[input.charAt(i) & 0xFF]++;
        }
        double entropy = 0.0;
        int n = input.length();
        for (int f : freq) {
            if (f > 0) {
                double p = (double) f / n;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        return entropy;
    }

    /**
     * 计算重复字符占比（最长连续重复段 / 总长）。
     */
    private double calculateRepetition(String input) {
        int maxRun = 1, run = 1;
        for (int i = 1; i < input.length(); i++) {
            if (input.charAt(i) == input.charAt(i - 1)) {
                run++;
                maxRun = Math.max(maxRun, run);
            } else {
                run = 1;
            }
        }
        return (double) maxRun / input.length();
    }
}
