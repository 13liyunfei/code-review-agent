package com.codereview.agent.core.store;

import com.codereview.agent.core.calibration.CalibrationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * PostgreSQL 校准派生状态存储（{@code calibration_accuracy} 表，rule_id 主键）。
 *
 * <p>saveAll 采用逐行 UPSERT（规则量小，全量写无压力），loadAll 全量读出。
 */
public class PgCalibrationStore implements CalibrationStore {

    private static final Logger log = LoggerFactory.getLogger(PgCalibrationStore.class);

    private final PgDb db;

    public PgCalibrationStore(PgDb db) {
        this.db = db;
    }

    @Override
    public Map<String, Double> loadAll() {
        try (var conn = db.conn();
             var ps = conn.prepareStatement("SELECT rule_id, accuracy FROM calibration_accuracy");
             var rs = ps.executeQuery()) {
            Map<String, Double> out = new HashMap<>();
            while (rs.next()) {
                out.put(rs.getString(1), rs.getDouble(2));
            }
            return out;
        } catch (Exception e) {
            log.warn("[Calibration] 规则准确率读取失败（从空开始）：{}", e.getMessage());
            return Map.of();
        }
    }

    @Override
    public void saveAll(Map<String, Double> ruleAccuracy) {
        if (ruleAccuracy == null) {
            return;
        }
        try (var conn = db.conn()) {
            conn.setAutoCommit(false);
            try (var ps = conn.prepareStatement("""
                    INSERT INTO calibration_accuracy (rule_id, accuracy, updated_at)
                    VALUES (?, ?, now())
                    ON CONFLICT (rule_id) DO UPDATE SET accuracy = EXCLUDED.accuracy, updated_at = now()
                    """)) {
                for (var e : ruleAccuracy.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setDouble(2, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (Exception ex) {
                conn.rollback();
                throw ex;
            }
        } catch (Exception e) {
            log.warn("[Calibration] 规则准确率写入失败（本次学习仅存内存）：{}", e.getMessage());
        }
    }
}
