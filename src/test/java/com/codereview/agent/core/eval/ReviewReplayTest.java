package com.codereview.agent.core.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确定性回放评测：轨迹事件序列结构完整性校验。
 */
class ReviewReplayTest {

    private static final String EV = """
            {"type":"review.started","timestamp":1,"traceId":"t1","data":{"prId":1}}
            {"type":"context.diff-loaded","timestamp":2,"traceId":"t1","data":{"files":1}}
            {"type":"agent.completed","timestamp":3,"traceId":"t1","data":{"agentType":"SECURITY"}}
            {"type":"review.completed","timestamp":4,"traceId":"t1","data":{"totalFindings":1}}
            """;

    @Test
    void validTrajectoryReplaysOk(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("t.jsonl");
        Files.writeString(file, EV);

        ReviewReplay.ReplayResult r = new ReviewReplay(tempDir).replayFile(file);
        assertTrue(r.ok(), "合法轨迹应回放通过：" + r.issues());
        assertTrue(r.eventCount() == 4);
    }

    @Test
    void missingCompletedFails(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("t.jsonl");
        Files.writeString(file, EV.replace("{\"type\":\"review.completed\"", "{\"type\":\"x-completed\""));

        ReviewReplay.ReplayResult r = new ReviewReplay(tempDir).replayFile(file);
        assertFalse(r.ok(), "缺失 review.completed 应判定失败");
        assertFalse(r.issues().isEmpty());
    }

    @Test
    void emptyTrajectoryFails(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("t.jsonl");
        Files.writeString(file, "");
        assertFalse(new ReviewReplay(tempDir).replayFile(file).ok(), "空轨迹应判定失败");
    }
}
