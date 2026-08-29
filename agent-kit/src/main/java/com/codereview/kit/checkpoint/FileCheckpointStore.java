package com.codereview.kit.checkpoint;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 文件检查点存储：每个 runId 一个 JSON 文件（跨进程 / 崩溃后重启恢复）。
 */
public class FileCheckpointStore implements CheckpointStore {

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileCheckpointStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建检查点目录: " + dir, e);
        }
    }

    @Override
    public void save(Checkpoint checkpoint) {
        try {
            Files.write(fileOf(checkpoint.runId()),
                    mapper.writeValueAsBytes(Map.of(
                            "runId", checkpoint.runId(),
                            "state", checkpoint.state(),
                            "createdAt", checkpoint.createdAt().toString())));
        } catch (IOException e) {
            throw new IllegalStateException("检查点写入失败", e);
        }
    }

    @Override
    public Optional<Checkpoint> load(String runId) {
        Path f = fileOf(runId);
        if (!Files.exists(f)) {
            return Optional.empty();
        }
        try {
            var node = mapper.readTree(Files.readAllBytes(f));
            return Optional.of(new Checkpoint(node.path("runId").asText(),
                    mapper.convertValue(node.path("state"), Map.class),
                    Instant.parse(node.path("createdAt").asText())));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void delete(String runId) {
        try {
            Files.deleteIfExists(fileOf(runId));
        } catch (IOException ignored) {
        }
    }

    @Override
    public List<String> list() {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        } catch (IOException e) {
            return List.of();
        }
    }

    private Path fileOf(String runId) {
        return dir.resolve(runId.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".json");
    }
}
