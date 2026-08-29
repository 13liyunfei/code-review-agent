package com.codereview.kit.planning;

import com.codereview.kit.ChatModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务拆解器（Task Decomposition / Planning）：把复杂目标经 LLM 拆解为可执行的 DAG。
 *
 * <p>LLM 输出 JSON：{@code {"tasks":[{"id":"t1","description":"...","dependsOn":[],"assignee":"LOGIC"}]}}。
 * 解析失败 / 校验失败（含环）一律优雅降级为**单任务直通计划**（交给默认 Agent 整体处理），
 * 绝不让规划失败阻塞业务——与系统「可降级」原则一致。
 */
public class TaskPlanner {

    private static final Logger log = LoggerFactory.getLogger(TaskPlanner.class);

    private final ChatModel chatModel;
    private final ObjectMapper mapper = new ObjectMapper();

    public TaskPlanner(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * @param goal            复杂目标（如"审查该 PR 并输出修复方案"）
     * @param availableAgents 可用执行者提示（Agent 类型名列表，供 LLM 分配 assignee）
     */
    public TaskPlan plan(String goal, String availableAgents) {
        try {
            String prompt = """
                    你是任务规划器。把目标拆解为可并行的子任务 DAG。
                    可用执行者: %s
                    仅输出 JSON：{"tasks":[{"id":"t1","description":"做什么","dependsOn":[],"assignee":"执行者"}]}
                    依赖用 id 引用；无依赖则 dependsOn 为空数组；拆成 2-4 个粒度适中的任务。
                    目标：%s""".formatted(availableAgents == null ? "默认Agent" : availableAgents, goal);
            JsonNode json = parseJson(chatModel.chat(prompt));
            if (json == null || !json.has("tasks") || !json.get("tasks").isArray()
                    || json.get("tasks").isEmpty()) {
                return fallback(goal, "LLM 未返回有效任务数组");
            }
            List<TaskPlan.TaskNode> nodes = new ArrayList<>();
            for (JsonNode t : json.get("tasks")) {
                List<String> deps = new ArrayList<>();
                t.path("dependsOn").forEach(d -> deps.add(d.asText()));
                nodes.add(new TaskPlan.TaskNode(
                        t.path("id").asText(),
                        t.path("description").asText(),
                        deps,
                        t.path("assignee").asText("默认Agent")));
            }
            TaskPlan plan = TaskPlan.of(goal, nodes); // 校验失败会抛出，进入 catch 降级
            log.info("[Planner] 目标拆解为 {} 个子任务", plan.tasks().size());
            return plan;
        } catch (Exception e) {
            return fallback(goal, e.getMessage());
        }
    }

    private TaskPlan fallback(String goal, String reason) {
        log.warn("[Planner] 拆解失败（{}），降级为单任务直通", reason);
        return TaskPlan.singleStep(goal, "默认Agent");
    }

    private JsonNode parseJson(String text) {
        try {
            String t = text.trim();
            int s = t.indexOf('{');
            int e = t.lastIndexOf('}');
            return (s < 0 || e <= s) ? null : mapper.readTree(t.substring(s, e + 1));
        } catch (Exception ex) {
            return null;
        }
    }
}
