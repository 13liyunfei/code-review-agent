package com.codereview.agent.core.coordinator;

import com.codereview.agent.core.model.PullRequest;
import com.codereview.agent.core.model.ReviewReport;

/**
 * 协调者（Coordinator）接口——星型拓扑的中央调度节点。
 *
 * <p>依据文档设计，Coordinator 是协同核心：接收 PR 触发，并行下发审查任务，
 * 汇聚各 Agent 结果，执行去重、冲突仲裁与分级定档，最终生成结构化报告。
 */
public interface Coordinator {

    /**
     * 对一次 Pull Request 发起多 Agent 协同审查。
     *
     * @param pr 待审查的 PR
     * @return 聚合后的结构化审查报告
     */
    ReviewReport review(PullRequest pr);
}
