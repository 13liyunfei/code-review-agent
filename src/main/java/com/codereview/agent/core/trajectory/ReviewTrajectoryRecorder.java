package com.codereview.agent.core.trajectory;

import com.codereview.agent.core.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 审查轨迹记录器（事件源持久化）。
 *
 * <p>对齐 deepseek-harness 的会话事件日志与 codex 的 rollout 轨迹：
 * 每次审查（一个 {@code runId}）对应一条 {@link ReviewEventLog}，记录从「审查开始 →
 * 上下文注入 → 各 Agent 完成 → 审查结束」的全链路事件。记录既驻留内存（便于实时查询），
 * 也在审查结束时经 {@link TrajectoryStore} 持久化（集群部署下入 PG {@code trajectory_store}，
 * 任意实例可按 runId 读取——审计与回放不依赖写入实例）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>内存态用 {@link AtomicReference} 包裹不可变 {@link ReviewEventLog}，
 *       追加操作无锁且快照安全（典型 RCU 模式）；</li>
 *   <li>持久化失败仅告警不阻断审查主流程；</li>
 *   <li>透传 {@link TraceContext} 的 traceId，使轨迹与日志链路可关联；</li>
 *   <li>Recorder 为<b>可选增强</b>：Coordinator 在 recorder 为 null 时不调用本类，零侵入。</li>
 * </ul>
 */
@Component
public class ReviewTrajectoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(ReviewTrajectoryRecorder.class);

    private final TrajectoryStore store;

    /** runId -> 会话（团队标识 + 当前不可变日志）。 */
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public ReviewTrajectoryRecorder(TrajectoryStore store) {
        this.store = store;
    }

    /** 内部会话：绑定团队标识与当前日志快照。 */
    private static final class Session {
        final String teamId;
        final AtomicReference<ReviewEventLog> logRef;

        Session(String teamId, ReviewEventLog log) {
            this.teamId = teamId;
            this.logRef = new AtomicReference<>(log);
        }
    }

    /**
     * 开启一次审查轨迹（幂等：重复开启会重置该 runId 的日志）。
     *
     * @param runId  审查运行 ID（与 traceId 对齐）
     * @param teamId 团队 / 租户标识
     */
    public void begin(String runId, String teamId) {
        String tid = (teamId == null || teamId.isBlank()) ? "default" : teamId;
        sessions.put(runId, new Session(tid, ReviewEventLog.empty()));
    }

    /**
     * 追加一条自定义事件（自动填充时间戳与 traceId）。
     *
     * @param runId 审查运行 ID
     * @param type  事件类型
     * @param data  事件载荷
     */
    public void append(String runId, String type, Map<String, Object> data) {
        append(runId, new ReviewEvent(type, Instant.now().toEpochMilli(),
                TraceContext.getTraceId(), data == null ? Map.of() : data));
    }

    /**
     * 追加一条已构造的事件（若会话不存在则自动建一个未知团队会话，避免丢事件）。
     *
     * @param runId 审查运行 ID
     * @param event 事件（非 null）
     */
    public void append(String runId, ReviewEvent event) {
        if (event == null) {
            return;
        }
        Session s = sessions.computeIfAbsent(runId, k -> new Session("default", ReviewEventLog.empty()));
        s.logRef.updateAndGet(log -> log.append(event));
    }

    /**
     * 读取内存中的当前轨迹（审查进行中或已落盘后均可）。
     *
     * @param runId 审查运行 ID
     * @return 当前事件日志（可能为空）
     */
    public Optional<ReviewEventLog> getInMemory(String runId) {
        Session s = sessions.get(runId);
        return s == null ? Optional.empty() : Optional.of(s.logRef.get());
    }

    /**
     * 结束并持久化一次审查轨迹。
     *
     * <p>经 {@link TrajectoryStore} 保存完整事件序列（集群下入 PG），随后从内存移除（释放资源）。
     * 持久化失败仅告警，不影响主流程。
     *
     * @param runId 审查运行 ID
     */
    public void close(String runId) {
        Session s = sessions.remove(runId);
        if (s == null) {
            return;
        }
        ReviewEventLog eventLog = s.logRef.get();
        if (eventLog.isEmpty()) {
            return;
        }
        try {
            store.save(runId, s.teamId, eventLog.events());
        } catch (Exception e) {
            log.warn("[Trajectory] 轨迹持久化失败（不阻断审查）：runId={}, 原因={}", runId, e.getMessage());
        }
    }
}
