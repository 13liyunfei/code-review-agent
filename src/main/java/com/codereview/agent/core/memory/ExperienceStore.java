package com.codereview.agent.core.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 经验库（长期记忆的检索入口 + 文件经验条目层）。
 *
 * <p>两段能力：
 * <ul>
 *   <li>**向量检索**（原有）：封装 {@link MemoryStore} 检索相关经验，格式化注入
 *       提示词【历史经验参考】区块（{@link #getRelevantExperiences}）；</li>
 *   <li>**文件经验条目**（新增）：沉淀「问题模式 → 有效建议」条目，按团队落盘
 *       {@code <dataDir>/<teamId>/experience.json}，关键词重合度检索（零外部依赖），
 *       由 {@link ReflectionService} 反思写入。</li>
 * </ul>
 */
@Component
public class ExperienceStore {

    /** 一条可复用的审查经验（文件条目层）。 */
    public record Experience(String pattern, String advice, long createdAt) {}

    private final MemoryStore memoryStore; // 可空（独立测试 / 纯文件模式）
    private final Path dataDir;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Autowired
    public ExperienceStore(MemoryStore memoryStore) {
        this(memoryStore, Path.of("./data"));
    }

    public ExperienceStore(MemoryStore memoryStore, Path dataDir) {
        this.memoryStore = memoryStore;
        this.dataDir = dataDir == null ? Path.of("./data") : dataDir;
    }

    /**
     * 获取与当前审查相关的历史经验文本（团队自有经验，不含全局基线）。
     * 向量命中 + 文件条目关键词命中合并输出。
     */
    public String getRelevantExperiences(String teamId, String agentType, String text) {
        StringBuilder sb = new StringBuilder();
        if (memoryStore != null) {
            List<MemoryEntry> entries = memoryStore.search(text, agentType, 5, teamId, false);
            for (MemoryEntry e : entries) {
                if ("experience".equals(e.metadata().get("type"))) {
                    sb.append("- ").append(e.content()).append('\n');
                }
            }
        }
        for (Experience e : top(teamId, text, 3)) {
            sb.append("- ").append(e.pattern()).append(" → ").append(e.advice()).append('\n');
        }
        return sb.toString().trim();
    }

    /** 写入一条文件经验（同 pattern 去重，更新时间戳；异常静默不阻断主链路）。 */
    public synchronized void add(String teamId, String pattern, String advice) {
        if (pattern == null || pattern.isBlank() || advice == null || advice.isBlank()) {
            return;
        }
        try {
            List<Experience> list = new ArrayList<>(load(teamId));
            list.removeIf(e -> e.pattern().equalsIgnoreCase(pattern.trim()));
            list.add(new Experience(pattern.trim(), advice.trim(), System.currentTimeMillis()));
            persist(teamId, list);
        } catch (Exception e) {
            // 经验沉淀失败不阻断审查主链路
        }
    }

    /** 按查询关键词重合度检索 Top-N（无命中返回空）。 */
    public List<Experience> top(String teamId, String query, int limit) {
        try {
            Set<String> q = tokenize(query);
            Comparator<Experience> byOverlap = Comparator.comparingInt(
                    (Experience e) -> overlap(q, tokenize(e.pattern()))).reversed();
            return load(teamId).stream()
                    .sorted(byOverlap)
                    .filter(e -> overlap(q, tokenize(e.pattern())) > 0)
                    .limit(Math.max(1, limit))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    public int size(String teamId) {
        try {
            return load(teamId).size();
        } catch (Exception e) {
            return 0;
        }
    }

    private Set<String> tokenize(String s) {
        if (s == null) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase().split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (w.length() >= 2) {
                out.add(w);
            }
        }
        return out;
    }

    private int overlap(Set<String> a, Set<String> b) {
        int n = 0;
        for (String w : b) {
            if (a.contains(w)) {
                n++;
            }
        }
        return n;
    }

    private Path file(String teamId) {
        return dataDir.resolve(sanitize(teamId)).resolve("experience.json");
    }

    private List<Experience> load(String teamId) throws IOException {
        Path f = file(teamId);
        if (!Files.exists(f)) {
            return new ArrayList<>();
        }
        return List.of(mapper.readValue(f.toFile(), Experience[].class));
    }

    private void persist(String teamId, List<Experience> list) throws IOException {
        Path f = file(teamId);
        Files.createDirectories(f.getParent());
        mapper.writeValue(f.toFile(), list);
    }

    private static String sanitize(String teamId) {
        return teamId == null || teamId.isBlank() ? "default"
                : teamId.replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]", "_");
    }
}
