package com.codereview.agent.core.calibration;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 置信度校准服务（越用越准，降低误报）。
 *
 * <p>依据文档“基于历史反馈动态调整”方案：系统运行一段时间后，根据人工标记的
 * 误报 / 正报来修正规则的置信度基准，使聚合去重时能更合理地取舍。
 *
 * <p>校准公式：{@code calibrated = rawConfidence * historicalAccuracy}，
 * 其中 historicalAccuracy 初始为 1.0，误报时衰减、正报时提升。
 */
@Service
public class ConfidenceCalibrationService {

    /** 规则 ID -> 历史准确率（初始 1.0）。 */
    private final Map<String, Double> ruleAccuracy = new ConcurrentHashMap<>();

    /**
     * 开发者标记“误报”后调用：降低该规则的置信度基准。
     *
     * @param ruleId 规则 ID
     */
    public void markFalsePositive(String ruleId) {
        // 误报：准确率乘 0.9 衰减（下限由 calibrate 限制）
        ruleAccuracy.merge(ruleId, 0.8, (old, v) -> old * 0.9);
    }

    /**
     * 开发者标记“正报”后调用：提升该规则的置信度基准。
     *
     * @param ruleId 规则 ID
     */
    public void markTruePositive(String ruleId) {
        // 正报：准确率乘 1.05 提升，封顶 1.0
        ruleAccuracy.merge(ruleId, 1.0, (old, v) -> Math.min(1.0, old * 1.05));
    }

    /**
     * 使用历史数据校准原始置信度。
     *
     * @param ruleId        规则 ID
     * @param rawConfidence 工具 / 模型给出的原始置信度
     * @return 校准后的置信度（限定在 0~1）
     */
    public double calibrate(String ruleId, double rawConfidence) {
        double accuracy = ruleAccuracy.getOrDefault(ruleId, 1.0);
        double calibrated = rawConfidence * accuracy;
        return Math.min(1.0, Math.max(0.0, calibrated));
    }
}
