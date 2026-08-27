package com.codereview.agent.core.planning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * DAG 执行器：按依赖拓扑并行执行 {@link TaskPlan}（入度为零的节点并行跑）。
 *
 * <p>失败传播语义：某节点失败（或其任一上游失败）时，**下游节点标记为跳过**而不执行，
 * 不影响无依赖关系的其他分支。计划在 {@link TaskPlan#of} 已校验无环，此处无需重复防御。
 */
public class DagExecutor {

    private static final Logger log = LoggerFactory.getLogger(DagExecutor.class);

    private final Executor executor;

    public DagExecutor(Executor executor) {
        this.executor = executor;
    }

    /** 单节点执行结果。 */
    public record TaskResult(String id, boolean success, String output) {
        public static TaskResult ok(String id, String output) { return new TaskResult(id, true, output); }
        public static TaskResult fail(String id, String output) { return new TaskResult(id, false, output); }
    }

    /**
     * 执行整个计划。
     *
     * @param runner 由调用方提供：把一个 TaskNode 变成执行结果（如分派给某 Agent / 某工具）
     * @return 全部节点的结果（含被跳过的）
     */
    public Map<String, TaskResult> execute(TaskPlan plan, Function<TaskPlan.TaskNode, TaskResult> runner) {
        Map<String, TaskPlan.TaskNode> byId = new HashMap<>();
        plan.tasks().forEach(n -> byId.put(n.id(), n));

        Map<String, CompletableFuture<TaskResult>> futures = new LinkedHashMap<>();
        for (TaskPlan.TaskNode n : plan.tasks()) {
            compute(n, byId, futures, runner);
        }
        CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();

        Map<String, TaskResult> results = new LinkedHashMap<>();
        futures.forEach((id, f) -> results.put(id, f.join()));
        long failed = results.values().stream().filter(r -> !r.success()).count();
        log.info("[DagExecutor] 计划执行完成：{} 个任务，失败/跳过 {} 个", results.size(), failed);
        return results;
    }

    private CompletableFuture<TaskResult> compute(TaskPlan.TaskNode node,
                                                  Map<String, TaskPlan.TaskNode> byId,
                                                  Map<String, CompletableFuture<TaskResult>> futures,
                                                  Function<TaskPlan.TaskNode, TaskResult> runner) {
        if (futures.containsKey(node.id())) {
            return futures.get(node.id());
        }
        List<CompletableFuture<TaskResult>> depFutures = new ArrayList<>();
        for (String dep : node.dependsOn()) {
            depFutures.add(compute(byId.get(dep), byId, futures, runner));
        }
        CompletableFuture<TaskResult> future = CompletableFuture
                .allOf(depFutures.toArray(new CompletableFuture[0]))
                .thenApplyAsync(v -> {
                    boolean depsOk = depFutures.stream().allMatch(d -> d.join().success());
                    if (!depsOk) {
                        return TaskResult.fail(node.id(), "上游任务失败，跳过");
                    }
                    try {
                        return runner.apply(node);
                    } catch (Exception e) {
                        log.warn("[DagExecutor] 任务 {} 执行异常：{}", node.id(), e.getMessage());
                        return TaskResult.fail(node.id(), "执行异常: " + e.getMessage());
                    }
                }, executor);
        futures.put(node.id(), future);
        return future;
    }
}
