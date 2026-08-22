package com.codereview.agent.core.memory;

import com.codereview.agent.core.llm.EmbeddingClient;
import com.codereview.agent.core.llm.SimpleHashEmbeddingClient;
import com.codereview.agent.tenant.Teams;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 内存向量存储（离线 RAG 库 / 长期记忆）。
 *
 * <p>实现 {@link MemoryStore}：保存时按需计算 embedding，检索时按余弦相似度
 * 排序返回 Top-K。与文档中"PGVector（向量库）"语义一致，可直接替换。
 */
public class InMemoryVectorStore implements MemoryStore {

    private final EmbeddingClient embeddingClient;
    private final Map<Long, MemoryEntry> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);

    public InMemoryVectorStore(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    @Override
    public MemoryEntry save(MemoryEntry entry) {
        // 若未携带向量，自动计算
        float[] embedding = entry.embedding();
        if (embedding == null) {
            embedding = embeddingClient.embed(entry.content());
        }
        long id = entry.id() == null ? idGen.getAndIncrement() : entry.id();
        String teamId = entry.teamId() == null || entry.teamId().isBlank() ? Teams.GLOBAL : entry.teamId();
        MemoryEntry saved = new MemoryEntry(id, entry.agentType(), teamId, entry.content(),
                entry.metadata(), entry.level(), entry.createdAt(), embedding);
        store.put(id, saved);
        return saved;
    }

    @Override
    public List<MemoryEntry> search(String query, String agentType, int topK, String teamId, boolean includeGlobal) {
        float[] q = embeddingClient.embed(query == null ? "" : query);
        List<Scored> scored = new ArrayList<>();
        for (MemoryEntry e : store.values()) {
            if (agentType != null && !agentType.equals(e.agentType())) {
                continue;
            }
            boolean teamMatch = teamId != null && teamId.equals(e.teamId());
            boolean globalMatch = includeGlobal && Teams.GLOBAL.equals(e.teamId());
            if (!teamMatch && !globalMatch) {
                continue;
            }
            double sim = e.embedding() == null ? 0 : SimpleHashEmbeddingClient.cosineSimilarity(q, e.embedding());
            scored.add(new Scored(e, sim));
        }
        return scored.stream()
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(topK)
                .map(s -> s.entry)
                .collect(Collectors.toList());
    }

    @Override
    public void deleteByMetadata(String teamId, String key, String value) {
        String t = teamId == null || teamId.isBlank() ? Teams.GLOBAL : teamId;
        store.values().removeIf(e -> t.equals(e.teamId()) && value.equals(e.metadata().get(key)));
    }

    /** 带分数的检索结果（内部）。 */
    private record Scored(MemoryEntry entry, double score) {
    }
}
