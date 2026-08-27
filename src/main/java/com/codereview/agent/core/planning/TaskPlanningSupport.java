package com.codereview.agent.core.planning;

import com.codereview.agent.core.model.AgentResult;
import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.model.Finding;
import com.codereview.agent.core.model.ReviewContext;
import com.codereview.agent.core.agent.ReviewAgent;
import com.codereview.agent.core.trajectory.ReviewTrajectoryRecorder;
import com.codereview.agent.core.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 任务规划织入支撑（可选增强，对应「Workflow 编排与任务拆解」能力）。
 *
 * <p>启用后，Coordinator 在固定并行路径之前先经 LLM 把审查目标拆解为子任务 DAG，
 * 按 assignee 路由到对应 Agent 并行执行（依赖拓扑由 {@link DagExecutor} 保证）。
 * 语义约定：
 * <ul>
 *   <li>任一节点产出 findings 即采用规划结果（部分失败容忍）；</li>
 *   <li>规划失败 / 全部节点失败 / 组件未启用 → 返回空列表，调用方**降级固定并行路径**；</li>
 *   <li>每个节点执行经 {@link TraceContext#wrap} 保持 traceId 跨线程传播。</li>
 * </ul>
 */
public class TaskPlanningSupport {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanningSupport.class);

    private final TaskPlanner planner;
    private final DagExecutor dagExecutor;
    private final boolean enabled;

    public TaskPlanningSupport(TaskPlanner planner, DagExecutor dagExecutor, boolean enabled) {
        this.planner = planner;
        this.dagExecutor = dagExecutor;
        this.enabled = enabled;
    }

    public boolean active() {
        return enabled && planner != null && dagExecutor != null;
    }

    /**
     * 规划并执行。返回空列表表示「未启用 / 规划失败 / 全部节点失败」，调用方应降级固定路径。
     */
    public List<AgentResult> planAndExecute(String goal, List<CodeDiff> diffs, ReviewContext ctx,
                                            List<ReviewAgent> agents, long prId,
                                            ReviewTrajectoryRecorder recorder, String runId) {
        if (!active()) {
            return List.of();
        }
        try {
            String available = agents.stream().map(a -> a.getType().name()).distinct()
                    .reduce((a, b) -> a + "," + b).orElse("默认Agent");
            TaskPlan plan = planner.plan(goal, available);
            if (recorder != null) {
                recorder.append(runId, "plan.created", Map.of("tasks", plan.tasks().size()));
            }
            Map<String, ReviewAgent> byType = new HashMap<>();
            agents.forEach(a -> byType.putIfAbsent(a.getType().name(), a));

            ConcurrentLinkedQueue<AgentResult> collected = new ConcurrentLinkedQueue<>();
            Map<String, DagExecutor.TaskResult> results = dagExecutor.execute(plan, node -> {
                ReviewAgent agent = byType.get(node.assignee());
                if (agent == null) {
                    return DagExecutor.TaskResult.fail(node.id(), "无执行者: " + node.assignee());
                }
                List<Finding> findings = TraceContext.wrap(() -> agent.review(diffs, ctx)).get();
                collected.add(new AgentResult(prId, agent.getType(), findings));
                if (recorder != null) {
                    recorder.append(runId, "plan.task.completed", Map.of(
                            "task", node.id(), "agentType", agent.getType().name(),
                            "findingCount", findings.size()));
                }
                return DagExecutor.TaskResult.ok(node.id(), findings.size() + " findings");
            });
            long ok = results.values().stream().filter(DagExecutor.TaskResult::success).count();
            if (collected.isEmpty()) {
                log.warn("[Planning] 计划无任何产出（成功节点 {}），降级固定路径", ok);
                return List.of();
            }
            log.info("[Planning] 规划路径完成：{} 子任务，成功 {}，产出 {} 条发现",
                    plan.tasks().size(), ok, collected.size());
            return new ArrayList<>(collected);
        } catch (Exception e) {
            log.warn("[Planning] 规划执行异常，降级固定路径：{}", e.getMessage());
            return List.of();
        }
    }
}
