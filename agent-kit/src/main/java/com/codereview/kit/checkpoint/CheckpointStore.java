package com.codereview.kit.checkpoint;

import java.util.List;
import java.util.Optional;

/**
 * 检查点存储（崩溃恢复 / 断点续跑的基础设施抽象）。
 */
public interface CheckpointStore {

    /** 保存（覆盖同 runId 的旧检查点）。 */
    void save(Checkpoint checkpoint);

    /** 读取（无则空）。 */
    Optional<Checkpoint> load(String runId);

    /** 删除。 */
    void delete(String runId);

    /** 列出全部 runId。 */
    List<String> list();
}
