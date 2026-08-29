package com.codereview.kit.planning;

import com.codereview.kit.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务拆解 + DAG 执行单测：fake LLM 脚本化输出，同步 Executor 保证顺序可断言。
 */
class PlanningTest {

    static class FakeLlm implements ChatModel {
        private final String response;
        FakeLlm(String response) { this.response = response; }
        @Override public String chat(String prompt) { return response; }
    }

    /** 同步顺序执行器：记录执行序，供依赖顺序断言。 */
    static class OrderedExecutor implements Executor {
        final ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        @Override public void execute(Runnable command) { command.run(); }
    }

    private static final String PLAN_JSON = """
            {"tasks":[
              {"id":"t1","description":"加载 diff","dependsOn":[],"assignee":"工具"},
              {"id":"t2","description":"逻辑审查","dependsOn":["t1"],"assignee":"LOGIC"},
              {"id":"t3","description":"安全审查","dependsOn":["t1"],"assignee":"SECURITY"},
              {"id":"t4","description":"聚合报告","dependsOn":["t2","t3"],"assignee":"默认Agent"}]}""";

    @Test
    void LLM输出合法JSON时拆解为DAG() {
        TaskPlan plan = new TaskPlanner(new FakeLlm(PLAN_JSON)).plan("审查 PR 并出报告", "LOGIC,SECURITY");
        assertEquals(4, plan.tasks().size());
        assertEquals(2, plan.tasks().stream().filter(t -> t.id().equals("t4"))
                .findFirst().orElseThrow().dependsOn().size());
    }

    @Test
    void LLM输出非法时降级为单任务直通() {
        TaskPlan plan = new TaskPlanner(new FakeLlm("我不会输出JSON")).plan("复杂目标", null);
        assertEquals(1, plan.tasks().size());
        assertEquals("t1", plan.tasks().get(0).id());
    }

    @Test
    void 计划校验_循环依赖被拒绝() {
        assertThrows(IllegalArgumentException.class, () -> TaskPlan.of("g", List.of(
                new TaskPlan.TaskNode("a", "A", List.of("b"), "X"),
                new TaskPlan.TaskNode("b", "B", List.of("a"), "X"))));
        assertThrows(IllegalArgumentException.class, () -> TaskPlan.of("g", List.of(
                new TaskPlan.TaskNode("a", "A", List.of("不存在"), "X"))));
    }

    @Test
    void DAG按依赖拓扑执行_菱形顺序正确() {
        TaskPlan plan = new TaskPlanner(new FakeLlm(PLAN_JSON)).plan("g", null);
        OrderedExecutor ex = new OrderedExecutor();
        Map<String, DagExecutor.TaskResult> results = new DagExecutor(ex).execute(plan, node -> {
            ex.order.add(node.id());
            return DagExecutor.TaskResult.ok(node.id(), "done");
        });
        assertEquals(4, results.size());
        results.values().forEach(r -> assertTrue(r.success()));
        List<String> order = List.copyOf(ex.order);
        assertTrue(order.indexOf("t1") < order.indexOf("t2"), "t1 先于 t2");
        assertTrue(order.indexOf("t1") < order.indexOf("t3"), "t1 先于 t3");
        assertTrue(order.indexOf("t2") < order.indexOf("t4"), "t2 先于 t4");
        assertTrue(order.indexOf("t3") < order.indexOf("t4"), "t3 先于 t4");
    }

    @Test
    void 上游失败时下游被跳过() {
        TaskPlan plan = new TaskPlanner(new FakeLlm(PLAN_JSON)).plan("g", null);
        Map<String, DagExecutor.TaskResult> results = new DagExecutor(Runnable::run).execute(plan,
                node -> node.id().equals("t1")
                        ? DagExecutor.TaskResult.fail("t1", "加载失败")
                        : DagExecutor.TaskResult.ok(node.id(), "done"));
        assertFalse(results.get("t1").success());
        assertFalse(results.get("t2").success()); // 上游失败 → 跳过
        assertFalse(results.get("t3").success());
        assertFalse(results.get("t4").success());
        assertTrue(results.get("t2").output().contains("跳过"));
    }
}
