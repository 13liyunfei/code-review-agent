package com.codereview.agent.core.calibration;

import com.codereview.agent.core.feedback.FileFeedbackStore;
import com.codereview.agent.core.feedback.InMemoryFeedbackStore;
import com.codereview.agent.core.memory.ReviewFeedback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-3 修复验证：置信度校准闭环——反馈落库 → {@link ConfidenceCalibrationService#onFeedback}
 * → markFalsePositive / markTruePositive → ruleAccuracy → calibrate。
 *
 * <p>修复前：markFalsePositive / markTruePositive 全仓零调用方，ruleAccuracy 恒为 1.0，
 * 校准实际是「乘 1.0」的空转恒等函数。修复后两个 FeedbackStore 实现把校准服务作为
 * 落库监听器，每次保存反馈即驱动校准。
 */
class CalibrationFeedbackLoopTest {

    private final ConfidenceCalibrationService calibration = new ConfidenceCalibrationService();

    private static ReviewFeedback fp(String ruleId) {
        return new ReviewFeedback(ruleId, "SECURITY", true, "误报", null);
    }

    private static ReviewFeedback tp(String ruleId) {
        return new ReviewFeedback(ruleId, "SECURITY", false, "确认有效", null);
    }

    @Test
    void falsePositiveFeedbackDrivesCalibrationThroughInMemoryStore() {
        InMemoryFeedbackStore store = new InMemoryFeedbackStore(calibration);
        assertEquals(1.0, calibration.accuracy("SEC-001"), "无反馈前准确率为 1.0");

        store.save("default", fp("SEC-001"));

        assertEquals(0.8, calibration.accuracy("SEC-001"), 1e-9, "一次误报应把准确率降到 0.8");
        assertEquals(0.72, calibration.calibrate("SEC-001", 0.9), 1e-9,
                "校准 = 原始置信度 × 历史准确率");
    }

    @Test
    void truePositiveFeedbackRaisesAccuracyBack() {
        InMemoryFeedbackStore store = new InMemoryFeedbackStore(calibration);
        store.save("default", fp("SEC-001"));
        assertEquals(0.8, calibration.accuracy("SEC-001"), 1e-9);

        store.save("default", tp("SEC-001"));
        store.save("default", tp("SEC-001"));
        store.save("default", tp("SEC-001"));
        store.save("default", tp("SEC-001"));

        double accuracy = calibration.accuracy("SEC-001");
        assertTrue(accuracy > 0.8, "正报应逐步回升准确率，实际 " + accuracy);
        assertTrue(accuracy <= 1.0, "准确率封顶 1.0");
    }

    @Test
    void accuracyFloorNeverDropsBelowHalfAfterManyFalsePositives() {
        InMemoryFeedbackStore store = new InMemoryFeedbackStore(calibration);
        for (int i = 0; i < 50; i++) {
            store.save("default", fp("SEC-001"));
        }
        double accuracy = calibration.accuracy("SEC-001");
        assertTrue(accuracy >= 0.5, "单规则连续误报也不得低于 0.5（防一票否决），实际 " + accuracy);
        // 0.95 置信度的规则即使被连击也不会低于 ~0.475 → 仍可参与聚合而非被清零
        assertTrue(calibration.calibrate("SEC-001", 0.95) >= 0.475);
    }

    @Test
    void blankRuleIdFeedbackIsIgnored() {
        InMemoryFeedbackStore store = new InMemoryFeedbackStore(calibration);
        store.save("default", fp("   "));
        store.save("default", new ReviewFeedback(null, "SECURITY", true, "x", null));
        assertEquals(1.0, calibration.accuracy("SEC-001"));
    }

    @Test
    void fileFeedbackStoreAlsoNotifiesListener(@TempDir Path tempDir) {
        FileFeedbackStore store = new FileFeedbackStore(tempDir, calibration);
        store.save("default", fp("SEC-002"));

        assertEquals(0.8, calibration.accuracy("SEC-002"), 1e-9,
                "文件存储落库同样应驱动校准（闭环不依赖存储实现）");
        assertEquals(1, store.list("default").size(), "反馈本身照常持久化");
    }

    @Test
    void accuracyDerivedStateSurvivesRestartViaSnapshot(@TempDir Path tempDir) {
        // 2026-09-03：派生状态（ruleAccuracy）落快照 <data-dir>/calibration/accuracy.json，
        // 模拟进程重启后新实例应从快照恢复学习结果，而非退化为乘 1.0。
        ConfidenceCalibrationService first = new ConfidenceCalibrationService(tempDir);
        InMemoryFeedbackStore store = new InMemoryFeedbackStore(first);
        store.save("default", fp("SEC-100")); // 0.8
        store.save("default", fp("SEC-100")); // max(0.5, 0.8*0.9)=0.72
        store.save("default", tp("SEC-100")); // min(1.0, 0.72*1.05)=0.756
        assertEquals(0.756, first.accuracy("SEC-100"), 1e-9);

        // 模拟重启：同一 data-dir 上重建服务实例
        ConfidenceCalibrationService second = new ConfidenceCalibrationService(tempDir);
        assertEquals(0.756, second.accuracy("SEC-100"), 1e-9, "重启后准确率应从快照恢复");
        assertEquals(0.756 * 0.95, second.calibrate("SEC-100", 0.95), 1e-9,
                "校准应继续使用恢复后的准确率，而不是 1.0");

        // 重启后的实例继续累计也应生效并再次持久化
        InMemoryFeedbackStore store2 = new InMemoryFeedbackStore(second);
        store2.save("default", fp("SEC-100")); // max(0.5, 0.756*0.9)=0.6804
        assertEquals(0.6804, second.accuracy("SEC-100"), 1e-9);
    }

    @Test
    void memoryOnlyConstructorDoesNotTouchDisk() {
        // 无 data-dir 的内存模式：不写快照、不影响默认行为（纯单测友好构造）
        ConfidenceCalibrationService memory = new ConfidenceCalibrationService();
        InMemoryFeedbackStore store = new InMemoryFeedbackStore(memory);
        store.save("default", fp("SEC-200"));
        assertEquals(0.8, memory.accuracy("SEC-200"), 1e-9);
    }

    @Test
    void throwingListenerNeverBreaksFeedbackSave() {
        // 旁路保障：监听器抛异常不得影响反馈保存
        InMemoryFeedbackStore store = new InMemoryFeedbackStore(
                (teamId, feedback) -> { throw new IllegalStateException("listener boom"); });
        store.save("default", fp("SEC-003"));
        assertEquals(1, store.list("default").size());
    }
}
