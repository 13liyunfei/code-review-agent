package com.codereview.agent.core.report;

import java.util.List;

/**
 * 复检验证结果（见文档“修复后自动复检 → 验证问题是否解决”）。
 *
 * @param reCheck            是否为复检（存在历史记录）
 * @param resolvedCount      已解决问题数
 * @param unresolvedCount    未解决问题数
 * @param introducedCount    新引入问题数
 * @param resolvedItems      已解决项描述
 * @param unresolvedItems    未解决项描述
 * @param introducedItems    新引入项描述
 */
public record VerificationResult(boolean reCheck,
                                 int resolvedCount,
                                 int unresolvedCount,
                                 int introducedCount,
                                 List<String> resolvedItems,
                                 List<String> unresolvedItems,
                                 List<String> introducedItems) {

    /**
     * 非复检（首次审查）的空结果。
     */
    public static VerificationResult none() {
        return new VerificationResult(false, 0, 0, 0,
                List.of(), List.of(), List.of());
    }
}
