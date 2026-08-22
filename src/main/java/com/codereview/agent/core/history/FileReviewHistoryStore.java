package com.codereview.agent.core.history;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 基于本地 JSON 文件的审查历史存储（生产推荐）。
 *
 * <p>数据按团队隔离保存在 {@code <data-dir>/<teamId>/review-history.json}，结构为
 * key → 记录列表。团队目录在首次访问时创建；启动时扫描各团队子目录预加载。
 * 若持久化不可用，自动回退到内存模式并告警。
 */
public class FileReviewHistoryStore implements ReviewHistoryStore {

    private static final Logger log = LoggerFactory.getLogger(FileReviewHistoryStore.class);
    private static final String FILE_NAME = "review-history.json";
    private static final int MAX_PER_KEY = 30;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dataDir;
    /** teamId → (key → 记录列表)。 */
    private final Map<String, Map<String, List<ReviewHistoryEntry>>> store = new LinkedHashMap<>();
    private final boolean diskBacked;

    public FileReviewHistoryStore(Path dataDir) {
        this.dataDir = dataDir;
        boolean ok = ensureDir(dataDir);
        this.diskBacked = ok && loadAll();
        if (!diskBacked) {
            log.warn("[HistoryStore] 文件持久化不可用，回退到内存模式：{}", dataDir);
        }
    }

    private boolean ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
            return true;
        } catch (IOException e) {
            log.warn("[HistoryStore] 无法创建目录 {}：{}", dir, e.getMessage());
            return false;
        }
    }

    /** 扫描各团队子目录，预加载历史。 */
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
                Map<String, List<ReviewHistoryEntry>> loaded = mapper.readValue(
                        Files.readString(f), new TypeReference<Map<String, List<ReviewHistoryEntry>>>() {
                        });
                store.put(teamId, loaded);
                log.info("[HistoryStore] 已从 {} 加载团队 {} 的历史", f, teamId);
            }
        } catch (IOException e) {
            log.warn("[HistoryStore] 预加载历史失败：{}", e.getMessage());
        }
        return true;
    }

    private Path fileFor(String teamId) {
        return dataDir.resolve(Teams.sanitize(teamId)).resolve(FILE_NAME);
    }

    private Map<String, List<ReviewHistoryEntry>> teamMap(String teamId) {
        return store.computeIfAbsent(Teams.sanitize(teamId), k -> new LinkedHashMap<>());
    }

    @Override
    public synchronized Optional<ReviewHistoryEntry> getLatest(String teamId, String key) {
        List<ReviewHistoryEntry> list = teamMap(teamId).get(key);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(list.size() - 1));
    }

    @Override
    public synchronized void save(String teamId, ReviewHistoryEntry entry) {
        String t = Teams.sanitize(teamId);
        Map<String, List<ReviewHistoryEntry>> team = teamMap(t);
        List<ReviewHistoryEntry> list = team.computeIfAbsent(entry.key(), k -> new CopyOnWriteArrayList<>());
        list.add(entry);
        while (list.size() > MAX_PER_KEY) {
            list.remove(0);
        }
        if (diskBacked) {
            persist(t);
        }
    }

    @Override
    public synchronized List<ReviewHistoryEntry> list(String teamId) {
        List<ReviewHistoryEntry> all = new ArrayList<>();
        teamMap(teamId).values().forEach(all::addAll);
        return Collections.unmodifiableList(all);
    }

    private void persist(String teamId) {
        Path f = fileFor(teamId);
        try {
            Files.createDirectories(f.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(f.toFile(), teamMap(teamId));
        } catch (IOException e) {
            log.warn("[HistoryStore] 写入 {} 失败：{}", f, e.getMessage());
        }
    }
}
