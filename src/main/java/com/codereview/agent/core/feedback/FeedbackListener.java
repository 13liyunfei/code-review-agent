package com.codereview.agent.core.feedback;

import com.codereview.agent.core.memory.ReviewFeedback;

/**
 * 反馈落库监听器：每次有开发者反馈被持久化时触发。
 *
 * <p><b>为什么需要它</b>：反馈存储（{@link FeedbackStore}）是「开发者反馈」的唯一
 * 持久化咽喉（REST API、工作流都汇到这里）。要打通置信度校准闭环——让
 * {@code markFalsePositive / markTruePositive} 真正被调用（此前全仓零调用，校准空转）——
 * 在落库点广播事件，比在每个调用方各自接线更稳：将来新增反馈入口只需存储实现不变。
 *
 * <p>实现方只消费数据，不做任何写回（观测旁路，抛异常不得影响反馈保存本身）。
 */
@FunctionalInterface
public interface FeedbackListener {

    /**
     * 一条反馈已落库。
     *
     * @param teamId   团队标识（已 sanitize）
     * @param feedback 反馈条目
     */
    void onFeedback(String teamId, ReviewFeedback feedback);

    /** 空实现（未配置监听器时使用）。 */
    FeedbackListener NONE = (teamId, feedback) -> { };
}
