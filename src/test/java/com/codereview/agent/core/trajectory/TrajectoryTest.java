package com.codereview.agent.core.trajectory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件源审查轨迹：不可变日志 + 持久化记录器。
 */
class TrajectoryTest {

    private static ReviewEvent ev(String type) {
        return new ReviewEvent(type, System.currentTimeMillis(), "trace-1", Map.of("k", "v"));
    }

    @Test
    void logIsAppendOnlyAndImmutable() {
        ReviewEventLog empty = ReviewEventLog.empty();
        assertTrue(empty.isEmpty());

        ReviewEventLog one = empty.append(ev("review.started"));
        assertEquals(1, one.size());
        // 原日志不受影响（RCU 语义）
        assertTrue(empty.isEmpty(), "原日志应为空（append 不原地修改）");

        ReviewEventLog two = one.append(ev("review.completed"));
        assertEquals(2, two.size());
        assertEquals(1, one.size(), "原日志仍只含 1 条");

        // events() 返回不可修改视图
        assertThrows(UnsupportedOperationException.class, () -> two.events().add(ev("x")));
    }

    @Test
    void rejectInvalidEvents() {
        assertThrows(IllegalArgumentException.class, () -> new ReviewEvent("", 1, "t", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new ReviewEvent("x", 0, "t", Map.of()));
        ReviewEventLog log = ReviewEventLog.empty();
        assertThrows(IllegalArgumentException.class, () -> log.append(null));
    }

    @Test
    void recorderPersistsJsonl(@TempDir Path tempDir) throws Exception {
        ReviewTrajectoryRecorder recorder = new ReviewTrajectoryRecorder(tempDir.toString());
        String runId = "run-test-1";
        recorder.begin(runId, "teamA");
        recorder.append(runId, "review.started", Map.of("prId", 1L, "repo", "demo"));
        recorder.append(runId, "agent.completed", Map.of("agentType", "SECURITY", "findingCount", 3));
        recorder.append(runId, "review.completed", Map.of("totalFindings", 3));
        recorder.close(runId);

        Path file = tempDir.resolve("teamA").resolve("trajectories").resolve(runId + ".jsonl");
        assertTrue(Files.exists(file), "轨迹文件应已落盘");

        List<String> lines = List.of(Files.readString(file).split("\n"));
        assertEquals(3, lines.size());
        ObjectMapper om = new ObjectMapper();
        JsonNode n0 = om.readTree(lines.get(0));
        assertEquals("review.started", n0.get("type").asText());
        assertNotNull(n0.get("data").get("repo"));
        JsonNode n2 = om.readTree(lines.get(2));
        assertEquals("review.completed", n2.get("type").asText());
        assertFalse(recorder.getInMemory(runId).isPresent(), "close 后内存应释放");
    }

    @Test
    void recorderNullSafe() {
        ReviewTrajectoryRecorder recorder = new ReviewTrajectoryRecorder("./target/traj-test");
        // 未 begin 直接 append 不应抛异常（自动建未知团队会话）
        recorder.append("orphan", "review.started", Map.of());
        assertTrue(recorder.getInMemory("orphan").isPresent());
        recorder.close("orphan");
    }
}
