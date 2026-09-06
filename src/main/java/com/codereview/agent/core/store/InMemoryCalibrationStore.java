package com.codereview.agent.core.store;

import com.codereview.agent.core.calibration.CalibrationStore;

import java.util.HashMap;
import java.util.Map;

/**
 * 内存校准派生状态存储（单机/测试回退实现，重启丢失）。
 */
public class InMemoryCalibrationStore implements CalibrationStore {

    private final Map<String, Double> store = new HashMap<>();

    @Override
    public synchronized Map<String, Double> loadAll() {
        return new HashMap<>(store);
    }

    @Override
    public synchronized void saveAll(Map<String, Double> ruleAccuracy) {
        store.clear();
        if (ruleAccuracy != null) {
            store.putAll(ruleAccuracy);
        }
    }
}
