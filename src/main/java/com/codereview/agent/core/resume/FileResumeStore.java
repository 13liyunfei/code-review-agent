package com.codereview.agent.core.resume;

import com.codereview.agent.tenant.Teams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 断点续跑存储（文件持久化）。
 *
 * <p>每个进行中的审查会话对应一个 {@link ResumeState}，原子落盘为
 * {@code <data-dir>/<teamId>/resume/<runId>.json}；正常完成后删除（{@link #complete}）。
 * 写入采用「临时文件 + 原子重命名」，崩溃也不会出现半截 JSON。
 *
 * <p>与 {@link com.codereview.agent.core.trajectory.ReviewTrajectoryRecorder} 互补：
 * 轨迹是「发生了什么」的审计日志（只增），断点是「审到哪了」的可恢复快照（可覆盖）。
 */
@Component
public class FileResumeStore {

    private static final Logger log = LoggerFactory.getLogger(FileResumeStore.class);

    private final Path baseDir;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * Spring 装配构造（显式 {@code @Autowired}，避免与测试便捷构造混淆）。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public FileResumeStore(@Value("${review.data-dir:./data}") String dataDir) {
        this.baseDir = Path.of(dataDir);
    }

    /** 便于测试构造（指定根目录）。 */
    public FileResumeStore(Path baseDir) {
        this.baseDir = baseDir;
    }

    /** 保存（覆盖）一次断点快照。 */
    public void save(ResumeState state) {
        if (state == null) {
            return;
        }
        try {
            Path dir = baseDir.resolve(Teams.sanitize(state.teamId())).resolve("resume");
            Files.createDirectories(dir);
            Path target = dir.resolve(state.runId() + ".json");
            Path tmp = target.resolveSibling(state.runId() + ".json.tmp");
            Files.writeString(tmp, objectMapper.writeValueAsString(state));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("[Resume] 断点保存失败（不阻断审查）：runId={}, 原因={}", state.runId(), e.getMessage());
        }
    }

    /** 读取断点（不存在返回空）。 */
    public Optional<ResumeState> load(String runId, String teamId) {
        Path file = baseDir.resolve(Teams.sanitize(teamId)).resolve("resume").resolve(runId + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(file.toFile(), ResumeState.class));
        } catch (IOException e) {
            log.warn("[Resume] 断点读取失败（按无断点处理）：runId={}, 原因={}", runId, e.getMessage());
            return Optional.empty();
        }
    }

    /** 审查正常完成时清理断点。 */
    public void complete(String runId, String teamId) {
        Path file = baseDir.resolve(Teams.sanitize(teamId)).resolve("resume").resolve(runId + ".json");
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[Resume] 断点清理失败（无碍）：runId={}, 原因={}", runId, e.getMessage());
        }
    }
}
