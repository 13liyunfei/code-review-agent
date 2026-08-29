package com.codereview.kit.eval;

import java.util.List;

/**
 * 单条评估用例（回归基准的数据单元）。
 *
 * @param id        用例标识
 * @param input     输入（给被测系统的提示词 / 目标）
 * @param groundTruth 预埋 ground-truth（file + ruleKeyword）
 */
public record EvalCase(String id, String input, List<LlmJudge.GroundTruth> groundTruth) {
}
