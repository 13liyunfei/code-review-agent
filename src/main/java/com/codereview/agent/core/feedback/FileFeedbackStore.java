package com.codereview.agent.core.feedback;

import com.codereview.agent.core.memory.ReviewFeedback;
import com.codereview.agent.tenant.Teams;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于本地 JSON 文件的反馈存储（生产推荐）。
 *
 * <p>数据按团队隔离保存在 {@code <data-dir>/<teamId>/feedback.json}，支持跨进程重启保留。
 * 若目录不可写或序列化失败，自动回退到内存模式并告警，保证系统不中断。
 */
public class FileFeedbackStore implements FeedbackStore {

    private static final Logger log = LoggerFactory.getLogger(FileFeedbackStore.class);
    private static final String FILE_NAME = "feedback.json";

    private final ObjectMapper mapper = new ObjectMapper();
    /** teamId → 反馈列表。 */
    private final Map<String, List<ReviewFeedback>> store = new ConcurrentHashMap<>();
    private final Path dataDir;
    private final boolean diskBacked;
    /** 反馈落库监听器（驱动置信度校准等旁路；可为 null）。 */
    private final FeedbackListener listener;

    public FileFeedbackStore(Path dataDir) {
        this(dataDir, null);
    }

    /**
     * @param dataDir  数据目录
     * @param listener 反馈落库监听器（可为 null；由 ReviewAgentConfig 注入校准服务，打通校准闭环）
     */
    public FileFeedbackStore(Path dataDir, FeedbackListener listener) {
        this.dataDir = dataDir;
        this.listener = listener == null ? FeedbackListener.NONE : listener;
        boolean ok = ensureDir(dataDir);
        this.diskBacked = ok && loadAll();
        if (!diskBacked) {
            log.warn("[FeedbackStore] 文件持久化不可用，回退到内存模式（重启后丢失）：{}", dataDir);
        }
    }

    private boolean ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
            return true;
        } catch (IOException e) {
            log.warn("[FeedbackStore] 无法创建数据目录 {}：{}", dir, e.getMessage());
            return false;
        }
    }

    /** 扫描各团队子目录，预加载反馈。 */
    private boolean loadAll() {
        if (!Files.exists(dataDir)) {
            return true;
        }
        try (var stream = Files.list(dataDir)) {
            for (Path teamDir : stream.filter(Files::isDirectory).toList()) {
                Path f = teamDir.resolve(FILE_NAME);
                if (!Files.exists(f)) {
                    continue;
                }
                String teamId = teamDir.getFileName().toString();
                List<ReviewFeedback> loaded = mapper.readValue(
                        Files.readString(f), new TypeReference<List<ReviewFeedback>>() {});
                store.put(teamId, new CopyOnWriteArrayList<>(loaded));
                log.info("[FeedbackStore] 已从 {} 加载团队 {} 的 {} 条反馈", f, teamId, loaded.size());
            }
        } catch (IOException e) {
            log.warn("[FeedbackStore] 读取 {} 失败：{}", dataDir, e.getMessage());
            return false;
        }
        return true;
    }

    private Path fileFor(String teamId) {
        return dataDir.resolve(Teams.sanitize(teamId)).resolve(FILE_NAME);
    }

    @Override
    public void save(String teamId, ReviewFeedback feedback) {
        String t = Teams.sanitize(teamId);
        store.computeIfAbsent(t, k -> new CopyOnWriteArrayList<>()).add(feedback);
        if (diskBacked) {
            persist(t);
        }
        notifyListener(t, feedback);
    }

    /** 广播落库事件给监听器（置信度校准等旁路）。监听器异常不得影响反馈保存本身。 */
    private void notifyListener(String teamId, ReviewFeedback feedback) {
        try {
            listener.onFeedback(teamId, feedback);
        } catch (Exception e) {
            log.warn("[FeedbackStore] 反馈监听器执行失败（不影响保存）：{}", e.getMessage());
        }
    }

    @Override
    public List<ReviewFeedback> list(String teamId) {
        List<ReviewFeedback> list = store.get(Teams.sanitize(teamId));
        return list == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    private void persist(String teamId) {
        Path f = fileFor(teamId);
        try {
            Files.createDirectories(f.getParent());
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(f.toFile(), new ArrayList<>(store.getOrDefault(teamId, List.of())));
        } catch (IOException e) {
            log.warn("[FeedbackStore] 写入 {} 失败（本次反馈仅存于内存）：{}", f, e.getMessage());
        }
    }
}
