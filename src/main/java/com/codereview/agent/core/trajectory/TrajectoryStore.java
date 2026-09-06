package com.codereview.agent.core.trajectory;

import java.util.List;
import java.util.Optional;

/**
 * 审查轨迹存储（事件源持久化，供事后审计与确定性回放）。
 *
 * <p>替代改造前的 JSONL 落盘：集群部署下轨迹入 PG（{@code trajectory_store}），
 * 任何实例都可按 runId 读取任一次审查的事件序列（回放评测不依赖写入实例）。
 */
public interface TrajectoryStore {

    /** 保存（覆盖）一次审查的完整事件序列。 */
    void save(String runId, String teamId, List<ReviewEvent> events);

    /** 按 runId 读取事件序列（不存在返回空）。 */
    Optional<List<ReviewEvent>> load(String runId, String teamId);
}
