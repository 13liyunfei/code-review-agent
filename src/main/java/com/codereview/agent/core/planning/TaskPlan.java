package com.codereview.agent.core.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务计划（DAG）：复杂目标经任务拆解后的子任务依赖图。
 *
 * <p>通过 {@link #of} 工厂创建，构造时完成三项校验：id 唯一、依赖必须存在、**无环**
 * （Kahn 拓扑校验）——非法输入直接拒绝，保证 {@link DagExecutor} 无需重复防御。
 */
public record TaskPlan(String goal, List<TaskNode> tasks) {

    /** 子任务节点：assignee 为执行者提示（Agent 类型 / 工具名），由上层 TaskRunner 解释。 */
    public record TaskNode(String id, String description, List<String> dependsOn, String assignee) {
        public TaskNode {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }

    /** 校验并构建计划（id 唯一 / 依赖存在 / 无环）。 */
    public static TaskPlan of(String goal, List<TaskNode> tasks) {
        Map<String, TaskNode> byId = new HashMap<>();
        for (TaskNode n : tasks) {
            if (n.id() == null || n.id().isBlank()) {
                throw new IllegalArgumentException("任务 id 不能为空");
            }
            if (byId.putIfAbsent(n.id(), n) != null) {
                throw new IllegalArgumentException("重复任务 id: " + n.id());
            }
        }
        for (TaskNode n : tasks) {
            for (String dep : n.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new IllegalArgumentException("任务 " + n.id() + " 依赖不存在的节点: " + dep);
                }
            }
        }
        detectCycle(byId);
        return new TaskPlan(goal, List.copyOf(tasks));
    }

    /** 直通计划：目标单一无需拆解，直接交由一个任务完成（Planner 降级兜底）。 */
    public static TaskPlan singleStep(String goal, String assignee) {
        return of(goal, List.of(new TaskNode("t1", goal, List.of(), assignee)));
    }

    /** Kahn 拓扑校验：无法消完全部节点即存在环。 */
    private static void detectCycle(Map<String, TaskNode> byId) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        byId.values().forEach(n -> indegree.putIfAbsent(n.id(), 0));
        for (TaskNode n : byId.values()) {
            for (String dep : n.dependsOn()) {
                indegree.merge(n.id(), 1, Integer::sum);
                dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(n.id());
            }
        }
        List<String> ready = indegree.entrySet().stream()
                .filter(e -> e.getValue() == 0).map(Map.Entry::getKey).collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        int removed = 0;
        while (!ready.isEmpty()) {
            String cur = ready.remove(ready.size() - 1);
            removed++;
            for (String next : dependents.getOrDefault(cur, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        if (removed != byId.size()) {
            throw new IllegalArgumentException("任务计划存在循环依赖");
        }
    }
}
