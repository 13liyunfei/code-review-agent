package com.codereview.agent.core.calibration;

import com.codereview.agent.core.feedback.FeedbackListener;
import com.codereview.agent.core.memory.ReviewFeedback;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 *
 * <p><b>闭环（P0-3 修复）</b>：本服务实现 {@link FeedbackListener}，由反馈存储
 * （{@code FileFeedbackStore / InMemoryFeedbackStore}）在每次 {@code save} 时驱动
 * {@link #onFeedback} → {@link #markFalsePositive} / {@link #markTruePositive}。
 * 此前这两个方法与 {@code ruleAccuracy} 无任何调用方，校准恒等于乘 1.0（空转）。
 *
 * <p><b>持久化（2026-09-03）</b>：规则准确率是<b>派生状态</b>——反馈事件本身已由
 * {@code FileFeedbackStore} 落盘，但派生出的 {@code ruleAccuracy} 若只存内存，进程重启后
 * 校准学习全部归零，重新退化为「乘 1.0」。本服务每次标记后把 {@code ruleAccuracy}
 * 快照原子写入 {@code <data-dir>/calibration/accuracy.json}，构造时自动加载，重启不丢失。
 * 无 {@code data-dir} 的构造（单测/内存模式）不做持久化。
 *
 * <p><b>准确率下界（防一票否决）</b>：单次误报最多把准确率打到下界 {@link #MIN_ACCURACY}，
 * 不会因一次标记就近乎清零；正报回升封顶 1.0。
 */
public class ConfidenceCalibrationService implements FeedbackListener {

    private static final Logger log = LoggerFactory.getLogger(ConfidenceCalibrationService.class);

    /** 规则准确率下界：一次误报最多把置信度折到 0.5，保留规则继续参与聚合的资格。 */
    static final double MIN_ACCURACY = 0.5;

    /** 规则 ID -> 历史准确率（初始 1.0）。 */
    private final Map<String, Double> ruleAccuracy = new ConcurrentHashMap<>();

    /** 准确率快照文件（null = 不持久化，内存模式）。 */
    private final Path accuracyFile;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 内存模式（单测 / 无盘环境）：不持久化派生状态。 */
    public ConfidenceCalibrationService() {
        this(null);
    }

    /**
     * @param dataDir 数据根目录；派生准确率快照写入 {@code <dataDir>/calibration/accuracy.json}
     */
    public ConfidenceCalibrationService(Path dataDir) {
        this.accuracyFile = dataDir == null ? null : dataDir.resolve("calibration").resolve("accuracy.json");
        loadSnapshot();
    }

    /**
     * 开发者标记“误报”后调用：降低该规则的置信度基准（指数衰减，下限 {@link #MIN_ACCURACY}）。
     *
     * @param ruleId 规则 ID
     */
    public void markFalsePositive(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            return;
        }
        // 首次误报直接落到 0.8，之后每次乘 0.9 衰减，但不再低于 0.5（防一票否决）
        ruleAccuracy.merge(ruleId, 0.8, (old, v) -> Math.max(MIN_ACCURACY, old * 0.9));
        persist();
    }

    /**
     * 开发者标记“正报”后调用：提升该规则的置信度基准（指数回升，封顶 1.0）。
     *
     * @param ruleId 规则 ID
     */
    public void markTruePositive(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            return;
        }
        ruleAccuracy.merge(ruleId, 1.0, (old, v) -> Math.min(1.0, old * 1.05));
        persist();
    }

    /**
     * 使用历史数据校准原始置信度。
     *
     * @param ruleId        规则 ID
     * @param rawConfidence 工具 / 模型给出的原始置信度
     * @return 校准后的置信度（限定在 0~1）
     */
    public double calibrate(String ruleId, double rawConfidence) {
        double accuracy = accuracy(ruleId);
        double calibrated = rawConfidence * accuracy;
        return Math.min(1.0, Math.max(0.0, calibrated));
    }

    /**
     * 读取某规则当前的历史准确率（无记录时 1.0）。
     *
     * @param ruleId 规则 ID
     * @return 历史准确率
     */
    public double accuracy(String ruleId) {
        return ruleAccuracy.getOrDefault(ruleId, 1.0);
    }

    /**
     * 反馈落库回调（{@link FeedbackListener}）：按规则级证据更新准确率。
     *
     * <p>凡是非空 ruleId 的反馈都作为该规则的准确率证据——误报衰减、正报回升；
     * 文件级过滤仍由报告聚合阶段的误报抑制负责，这里只管「这条规则准不准」。
     *
     * @param teamId   团队标识（当前校准按规则全局生效，不区分团队）
     * @param feedback 反馈条目
     */
    @Override
    public void onFeedback(String teamId, ReviewFeedback feedback) {
        if (feedback == null || feedback.ruleId() == null || feedback.ruleId().isBlank()) {
            return;
        }
        if (feedback.isFalsePositive()) {
            markFalsePositive(feedback.ruleId());
        } else {
            markTruePositive(feedback.ruleId());
        }
    }

    /** 构造时加载已有快照；缺失 / 损坏时不阻断启动（退化为无学习记录）。 */
    private void loadSnapshot() {
        if (accuracyFile == null || !Files.exists(accuracyFile)) {
            return;
        }
        try {
            Map<String, Double> loaded = mapper.readValue(
                    Files.readString(accuracyFile), new TypeReference<Map<String, Double>>() {});
            ruleAccuracy.putAll(loaded);
            log.info("[Calibration] 已从 {} 恢复 {} 条规则准确率", accuracyFile, loaded.size());
        } catch (IOException e) {
            log.warn("[Calibration] 读取准确率快照 {} 失败（本次从空开始）：{}", accuracyFile, e.getMessage());
        }
    }

    /** 每次标记后原子写快照（tmp + ATOMIC_MOVE，与断点存储同款）。持久化失败不影响校准本身。 */
    private void persist() {
        if (accuracyFile == null) {
            return;
        }
        try {
            Files.createDirectories(accuracyFile.getParent());
            Path tmp = accuracyFile.resolveSibling(accuracyFile.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), ruleAccuracy);
            Files.move(tmp, accuracyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("[Calibration] 写入准确率快照 {} 失败（本次学习仅存内存）：{}", accuracyFile, e.getMessage());
        }
    }
}
