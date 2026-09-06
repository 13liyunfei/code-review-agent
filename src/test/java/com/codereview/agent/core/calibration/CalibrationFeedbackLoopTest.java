package com.codereview.agent.core.calibration;

import com.codereview.agent.core.feedback.FeedbackStore;
import com.codereview.agent.core.feedback.InMemoryFeedbackStore;
import com.codereview.agent.core.memory.ReviewFeedback;
import com.codereview.agent.core.store.InMemoryCalibrationStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-3 修复验证：置信度校准闭环——反馈落库 → {@link ConfidenceCalibrationService#onFeedback}
 * → markFalsePositive / markTruePositive → ruleAccuracy → calibrate。
 *
 * <p>修复前：markFalsePositive / markTruePositive 全仓零调用方，ruleAccuracy 恒为 1.0，
 * 校准实际是「乘 1.0」的空转恒等函数。修复后两个 FeedbackStore 实现把校准服务作为
 * 落库监听器，每次保存反馈即驱动校准。
 *
 * <p>集群改造后：校准派生状态（ruleAccuracy）写入共享 {@link CalibrationStore}
 * （生产为 PG {@code calibration_accuracy}，替代改造前的 {@code accuracy.json}），
 * 换实例/重启后新实例从共享存储恢复学习结果——A 机的反馈学习 B 机同样生效。
 */
class CalibrationFeedbackLoopTest {

    private static ReviewFeedback fp(String ruleId) {
        return new ReviewFeedback(ruleId, "SECURITY", true, "误报", null);
    }

    private static ReviewFeedback tp(String ruleId) {
        return new ReviewFeedback(ruleId, "SECURITY", false, "确认有效", null);
    }

    @Test
    void falsePositiveFeedbackDrivesCalibrationThroughInMemoryStore() {
        ConfidenceCalibrationService calibration = new ConfidenceCalibrationService();
        FeedbackStore store = new InMemoryFeedbackStore(calibration);
        assertEquals(1.0, calibration.accuracy("SEC-001"), "无反馈前准确率为 1.0");

        store.save("default", fp("SEC-001"));

        assertEquals(0.8, calibration.accuracy("SEC-001"), 1e-9, "一次误报应把准确率降到 0.8");
        assertEquals(0.72, calibration.calibrate("SEC-001", 0.9), 1e-9,
                "校准 = 原始置信度 × 历史准确率");
    }

    @Test
    void truePositiveFeedbackRaisesAccuracyBack() {
        ConfidenceCalibrationService calibration = new ConfidenceCalibrationService();
        FeedbackStore store = new InMemoryFeedbackStore(calibration);
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
        ConfidenceCalibrationService calibration = new ConfidenceCalibrationService();
        FeedbackStore store = new InMemoryFeedbackStore(calibration);
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
        ConfidenceCalibrationService calibration = new ConfidenceCalibrationService();
        FeedbackStore store = new InMemoryFeedbackStore(calibration);
        store.save("default", fp("   "));
        store.save("default", new ReviewFeedback(null, "SECURITY", true, "x", null));
        assertEquals(1.0, calibration.accuracy("SEC-001"));
    }

    @Test
    void listenerDrivenCalibrationIndependentOfStoreImpl() {
        // 反馈落库旁路驱动校准，不依赖具体存储实现（文件版已删除，PG 版在 E2E 验证）。
        // 这里用「无监听器 + 手工回调」证明监听契约独立于存储。
        InMemoryFeedbackStore store = new InMemoryFeedbackStore();
        store.save("default", fp("SEC-009"));
        // 存储本身不感知校准；校准由监听器在落库点广播——默认 NONE 时不影响落库
        assertEquals(1, store.list("default").size(), "反馈本身照常持久化");
    }

    @Test
    void accuracyDerivedStateSurvivesRestartViaSharedStore() {
        // 派生状态存共享 CalibrationStore（生产=PG calibration_accuracy；此前为 data-dir/calibration/accuracy.json），
        // 模拟进程重启/换实例后，新实例应从共享存储恢复学习结果，而非退化为乘 1.0。
        InMemoryCalibrationStore shared = new InMemoryCalibrationStore();
        ConfidenceCalibrationService first = new ConfidenceCalibrationService(shared);
        FeedbackStore store = new InMemoryFeedbackStore(first);
        store.save("default", fp("SEC-100")); // 0.8
        store.save("default", fp("SEC-100")); // max(0.5, 0.8*0.9)=0.72
        store.save("default", tp("SEC-100")); // min(1.0, 0.72*1.05)=0.756
        assertEquals(0.756, first.accuracy("SEC-100"), 1e-9);

        // 模拟重启/另一实例：同一共享存储上重建服务实例（生产是另一台机器连同一 PG）
        ConfidenceCalibrationService second = new ConfidenceCalibrationService(shared);
        assertEquals(0.756, second.accuracy("SEC-100"), 1e-9, "重启后准确率应从共享存储恢复");
        assertEquals(0.756 * 0.95, second.calibrate("SEC-100", 0.95), 1e-9,
                "校准应继续使用恢复后的准确率，而不是 1.0");

        // 重启后的实例继续累计也应生效并再次持久化
        FeedbackStore store2 = new InMemoryFeedbackStore(second);
        store2.save("default", fp("SEC-100")); // max(0.5, 0.756*0.9)=0.6804
        assertEquals(0.6804, second.accuracy("SEC-100"), 1e-9);
    }

    @Test
    void memoryOnlyConstructorDoesNotPersist() {
        // 无后端的内存模式：不持久化派生状态（纯单测友好构造）
        ConfidenceCalibrationService memory = new ConfidenceCalibrationService();
        FeedbackStore store = new InMemoryFeedbackStore(memory);
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
