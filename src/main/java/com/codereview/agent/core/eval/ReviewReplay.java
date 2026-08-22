package com.codereview.agent.core.eval;

import com.codereview.agent.tenant.Teams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 确定性回放评测（对齐 dsh {@code llm-replay} / codex {@code rollout}）。
 *
 * <p>从审查轨迹 JSONL（{@code <data-dir>/<teamId>/trajectories/<runId>.jsonl}）回放一次审查，
 * 校验事件序列的<b>结构完整性</b>：必须有 {@code review.started} 开头、{@code review.completed} 结尾，
 * 中间事件类型合法；据此判定该次审查「可回放 / 可信」。配合固定 fixture 输入，可做回归评测
 * （同一 PR 的轨迹在新版本下应保持合法且结论一致）。
 */
@Component
public class ReviewReplay {

    private static final Logger log = LoggerFactory.getLogger(ReviewReplay.class);

    /** 合法事件类型集合（用于意外事件检测）。 */
    private static final java.util.Set<String> KNOWN_TYPES = java.util.Set.of(
            "review.started", "review.resumed", "review.completed",
            "context.diff-loaded", "context.injected", "context.rule-hit",
            "agent.started", "agent.completed", "tool.executed");

    private final Path baseDir;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Spring 装配构造（显式 {@code @Autowired}，避免与测试便捷构造混淆）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ReviewReplay(@Value("${review.data-dir:./data}") String dataDir) {
        this.baseDir = Path.of(dataDir);
    }

    /** 测试便捷构造。 */
    public ReviewReplay(Path baseDir) {
        this.baseDir = baseDir;
    }

    /** 回放结果。 */
    public record ReplayResult(boolean ok, int eventCount, List<String> issues) {
        static ReplayResult ok(int n) {
            return new ReplayResult(true, n, List.of());
        }

        static ReplayResult fail(int n, List<String> issues) {
            return new ReplayResult(false, n, issues);
        }
    }

    /**
     * 回放一次审查轨迹。
     *
     * @param runId  审查运行 ID
     * @param teamId 团队
     * @return 回放结果（ok=false 时 issues 说明缺什么 / 意外什么）
     */
    public ReplayResult replay(String runId, String teamId) {
        Path file = baseDir.resolve(Teams.sanitize(teamId)).resolve("trajectories").resolve(runId + ".jsonl");
        if (!Files.exists(file)) {
            return ReplayResult.fail(0, List.of("轨迹文件不存在：" + file));
        }
        return replayFile(file);
    }

    /** 直接回放指定轨迹文件（便于测试 / 外部 fixture）。 */
    public ReplayResult replayFile(Path file) {
        List<String> issues = new ArrayList<>();
        List<String> types = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node = objectMapper.readTree(line);
                String type = node.path("type").asText();
                if (type.isBlank()) {
                    issues.add("存在无 type 字段的事件");
                    continue;
                }
                types.add(type);
                if (!KNOWN_TYPES.contains(type)) {
                    issues.add("未知事件类型：" + type);
                }
            }
        } catch (IOException e) {
            return ReplayResult.fail(0, List.of("轨迹解析失败：" + e.getMessage()));
        }

        if (types.isEmpty()) {
            return ReplayResult.fail(0, List.of("轨迹为空"));
        }
        if (!"review.started".equals(types.get(0))) {
            issues.add("首个事件应为 review.started，实际为：" + types.get(0));
        }
        if (!"review.completed".equals(types.get(types.size() - 1))) {
            issues.add("末个事件应为 review.completed，实际为：" + types.get(types.size() - 1));
        }
        if (issues.isEmpty()) {
            return ReplayResult.ok(types.size());
        }
        return ReplayResult.fail(types.size(), issues);
    }
}
