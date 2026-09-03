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
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 断点续跑存储（文件持久化）。
 *
 * <p>每个进行中的审查会话对应一个 {@link ResumeState}，原子落盘为
 * {@code <data-dir>/<teamId>/resume/<runId>.json}；正常完成后删除（{@link #complete}）。
 * 写入采用「临时文件 + 原子重命名」，崩溃也不会出现半截 JSON。
 * 崩溃遗留的孤儿断点由 {@link #purgeExpired} 兜底回收（{@link ResumeJanitor} 定时驱动）。
 *
 * <p>与 {@link com.codereview.agent.core.trajectory.ReviewTrajectoryRecorder} 互补：
 * 轨迹是「发生了什么」的审计日志（只增），断点是「审到哪了」的可恢复快照（可覆盖）。
 */
@Component
public class FileResumeStore {

    private static final Logger log = LoggerFactory.getLogger(FileResumeStore.class);

    /** 断点子目录名（位于 {@code <data-dir>/<teamId>/} 之下）。 */
    private static final String RESUME_DIR = "resume";

    /** 临时文件后缀：写入完成前先落这个名字，再原子重命名。 */
    private static final String TMP_SUFFIX = ".json.tmp";

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
            Path dir = baseDir.resolve(Teams.sanitize(state.teamId())).resolve(RESUME_DIR);
            Files.createDirectories(dir);
            Path target = dir.resolve(state.runId() + ".json");
            Path tmp = target.resolveSibling(state.runId() + TMP_SUFFIX);
            Files.writeString(tmp, objectMapper.writeValueAsString(state));
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("[Resume] 断点保存失败（不阻断审查）：runId={}, 原因={}", state.runId(), e.getMessage());
        }
    }

    /** 读取断点（不存在返回空）。 */
    public Optional<ResumeState> load(String runId, String teamId) {
        Path file = resolve(runId, teamId);
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
        Path file = resolve(runId, teamId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[Resume] 断点清理失败（无碍）：runId={}, 原因={}", runId, e.getMessage());
        }
    }

    /**
     * 清理超过 {@code maxAge} 未更新的残留断点——<b>崩溃遗留断点的唯一回收路径</b>。
     *
     * <p>正常完成的断点由 {@link #complete} 删除。但进程崩溃 / 机器重启后，若那个 runId
     * 再也不被触发（PR 被关闭、合并、或换了新的 commit），断点文件就会<b>永久残留在磁盘上</b>。
     * 本方法由 {@link ResumeJanitor} 定时调用，兜底回收这类孤儿文件。
     *
     * <p><b>判据用文件最后修改时间（mtime），而不是 JSON 里的 updatedAt</b>：损坏到无法解析的
     * 断点（写入被中断，留下半截 JSON）同样需要被清掉，而它根本读不出 updatedAt。
     * 只要审查还在推进就会不断 {@link #save} 覆盖、刷新 mtime，所以「超过 TTL 没有任何写入」
     * 就等价于「这次审查已经死了」——误删正在进行的审查不会发生。
     *
     * <p>连 {@code .json.tmp} 一并清理：崩溃若发生在写完临时文件、{@code ATOMIC_MOVE} 之前，
     * 临时文件同样会成为无人回收的残留。
     *
     * @param maxAge 超过该时长未更新的断点视为残留；null 或非正数时不清理
     * @return 实际删除的文件数
     */
    public int purgeExpired(Duration maxAge) {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            return 0;
        }
        if (!Files.isDirectory(baseDir)) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - maxAge.toMillis();
        List<Path> teamDirs;
        try (Stream<Path> teams = Files.list(baseDir)) {
            teamDirs = teams.toList();
        } catch (IOException e) {
            log.warn("[Resume] 断点根目录扫描失败（跳过本轮清理）：dir={}, 原因={}", baseDir, e.getMessage());
            return 0;
        }
        int removed = 0;
        for (Path team : teamDirs) {
            Path resumeDir = team.resolve(RESUME_DIR);
            if (Files.isDirectory(resumeDir)) {
                removed += purgeDir(resumeDir, cutoff);
            }
        }
        return removed;
    }

    private int purgeDir(Path dir, long cutoffMillis) {
        List<Path> files;
        try (Stream<Path> list = Files.list(dir)) {
            files = list.toList();
        } catch (IOException e) {
            log.warn("[Resume] 断点目录扫描失败（跳过）：dir={}, 原因={}", dir, e.getMessage());
            return 0;
        }
        int removed = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (!name.endsWith(".json") && !name.endsWith(TMP_SUFFIX)) {
                continue;
            }
            try {
                if (Files.getLastModifiedTime(file).toMillis() < cutoffMillis) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            } catch (IOException e) {
                // 单个文件失败不影响其余：删不掉的下轮再试
                log.warn("[Resume] 残留断点删除失败（下轮重试）：file={}, 原因={}",
                        file.getFileName(), e.getMessage());
            }
        }
        return removed;
    }

    private Path resolve(String runId, String teamId) {
        return baseDir.resolve(Teams.sanitize(teamId)).resolve(RESUME_DIR).resolve(runId + ".json");
    }
}
