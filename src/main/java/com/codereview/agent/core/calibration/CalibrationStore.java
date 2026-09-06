package com.codereview.agent.core.calibration;

import java.util.Map;
import java.util.Optional;

/**
 * 置信度校准派生状态（规则准确率）的持久化后端。
 *
 * <p>替代改造前的 {@code accuracy.json}：多实例集群下校准统计必须在 PG 共享，
 * 否则 A 机的反馈学习 B 机不可见，各实例置信度分层漂移。实现可为
 * PostgreSQL（{@code PgCalibrationStore}）或空（null = 内存模式不持久化，单测/降级）。
 */
public interface CalibrationStore {

    /** 全量读取规则准确率（无记录返回空 Map）。 */
    Map<String, Double> loadAll();

    /** 全量覆盖保存规则准确率（调用方内存态为权威）。 */
    void saveAll(Map<String, Double> ruleAccuracy);
}
