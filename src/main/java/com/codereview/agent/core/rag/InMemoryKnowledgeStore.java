package com.codereview.agent.core.rag;

import com.codereview.agent.core.llm.EmbeddingClient;
import com.codereview.agent.core.llm.SimpleHashEmbeddingClient;
import com.codereview.agent.core.memory.MemoryEntry;
import com.codereview.agent.core.memory.MemoryLevel;
import com.codereview.agent.tenant.Teams;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 内存版 RAG 知识库（离线 / 测试用，完整实现混合检索 + RRF 融合）。
 *
 * <p>对标业界「混合检索（BM25 + 稠密向量）+ Reciprocal Rank Fusion」：
 * <ul>
 *   <li><b>稠密向量</b>：沿用 {@link EmbeddingClient}（默认哈希，可替换真实模型）；</li>
 *   <li><b>稀疏 BM25</b>：本地实现 BM25 打分（对专有名词 / 代码标识符等关键词友好）；</li>
 *   <li><b>RRF 融合</b>：两路结果按下式融合，权重可调（默认向量 0.7 + BM25 0.3）：
 *       {@code score = w_dense/(k+rank_dense) + w_sparse/(k+rank_sparse)}，k 默认 60；</li>
 *   <li><b>团队隔离</b>：与 {@link com.codereview.agent.core.memory.PgVectorMemoryStore} 一致，
 *       RAG 检索含全局基线（{@code __global__}）。</li>
 * </ul>
 * 所有结果写入 {@code similarity} 元数据（取融合分归一化），供 {@link RagEvaluator} 阈值过滤。
 *
 * <p>本类仅实现 {@link KnowledgeStore}（RAG 专属语义），不复用 {@code MemoryStore} 契约，
 * 与记忆库在类型层面严格隔离。</p>
 */
public class InMemoryKnowledgeStore implements KnowledgeStore {

    private final EmbeddingClient embeddingClient;
    private final Map<Long, MemoryEntry> store = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(1);
    private final StructuredChunker chunker = new StructuredChunker();
    /** BM25 参数。 */
    private static final double K1 = 1.5;
    private static final double B = 0.75;
    /** RRF 常数。 */
    private static final double RRF_K = 60.0;

    public InMemoryKnowledgeStore(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    @Override
    public int saveKnowledge(String teamId, String doc, Map<String, String> meta) {
        String t = Teams.sanitize(teamId);
        List<StructuredChunker.Chunk> chunks = chunker.chunk(doc, meta);
        for (StructuredChunker.Chunk c : chunks) {
            MemoryEntry entry = new MemoryEntry(null, "RAG", t, c.text(), c.metadata(),
                    MemoryLevel.LONG_TERM, Instant.now(), null);
            float[] embedding = entry.embedding();
            if (embedding == null || embedding.length == 0) {
                embedding = embeddingClient.embed(entry.content());
            }
            long id = entry.id() == null ? idGen.getAndIncrement() : entry.id();
            String teamId2 = entry.teamId() == null || entry.teamId().isBlank() ? Teams.GLOBAL : entry.teamId();
            MemoryEntry saved = new MemoryEntry(id, entry.agentType(), teamId2, entry.content(),
                    entry.metadata(), entry.level(), entry.createdAt(), embedding);
            store.put(id, saved);
        }
        return chunks.size();
    }

    @Override
    public List<MemoryEntry> searchKnowledge(String query, int topK, String teamId, boolean includeGlobal) {
        return hybridSearch(query, topK, teamId, includeGlobal, null);
    }

    @Override
    public List<MemoryEntry> searchKnowledge(String query, int topK, String teamId,
                                             boolean includeGlobal, java.time.Duration maxAge) {
        return hybridSearch(query, topK, teamId, includeGlobal, maxAge);
    }

    /**
     * 混合检索：BM25 + 稠密向量，RRF 融合。
     */
    private List<MemoryEntry> hybridSearch(String query, int topK, String teamId,
                                           boolean includeGlobal, java.time.Duration maxAge) {
        String t = Teams.sanitize(teamId);
        java.time.Instant cutoff = (maxAge == null || maxAge.isNegative() || maxAge.isZero())
                ? null : java.time.Instant.now().minus(maxAge);
        List<MemoryEntry> pool = store.values().stream()
                .filter(e -> "RAG".equals(e.agentType()))
                .filter(e -> {
                    if (cutoff != null && e.createdAt() != null && e.createdAt().isBefore(cutoff)) {
                        return false; // freshness：超过 maxAge 的陈旧知识不参与召回
                    }
                    boolean teamMatch = t.equals(e.teamId());
                    boolean globalMatch = includeGlobal && Teams.GLOBAL.equals(e.teamId());
                    return teamMatch || globalMatch;
                })
                .collect(Collectors.toList());
        if (pool.isEmpty()) {
            return List.of();
        }

        // 1) 稠密向量排序（余弦）
        float[] q = embeddingClient.embed(query == null ? "" : query);
        Map<Long, Double> dense = new HashMap<>();
        for (MemoryEntry e : pool) {
            double sim = e.embedding() == null ? 0
                    : SimpleHashEmbeddingClient.cosineSimilarity(q, e.embedding());
            dense.put(e.id(), sim);
        }
        List<MemoryEntry> denseRanked = pool.stream()
                .sorted((a, b) -> Double.compare(dense.get(b.id()), dense.get(a.id())))
                .collect(Collectors.toList());

        // 2) BM25 排序
        Map<Long, Double> bm25 = bm25Scores(query, pool);
        List<MemoryEntry> sparseRanked = pool.stream()
                .sorted((a, b) -> Double.compare(bm25.get(b.id()), bm25.get(a.id())))
                .collect(Collectors.toList());

        // 3) RRF 融合
        Map<Long, Double> rrf = new HashMap<>();
        for (int i = 0; i < denseRanked.size(); i++) {
            rrf.merge(denseRanked.get(i).id(), 0.7 / (RRF_K + i + 1), Double::sum);
        }
        for (int i = 0; i < sparseRanked.size(); i++) {
            rrf.merge(sparseRanked.get(i).id(), 0.3 / (RRF_K + i + 1), Double::sum);
        }
        List<MemoryEntry> fused = pool.stream()
                .sorted((a, b) -> Double.compare(rrf.get(b.id()), rrf.get(a.id())))
                .limit(topK)
                .collect(Collectors.toList());

        // similarity 元数据统一为「真实语义相似度」（余弦），与 PgKnowledgeStore 口径一致，
        // 供 RagEvaluator 阈值过滤跨后端可比。RRF 融合分仅用于排序，另存 rrfScore 不参与闸门。
        double maxRrf = fused.isEmpty() ? 1.0 : rrf.getOrDefault(fused.get(0).id(), 0.0);
        List<MemoryEntry> result = new ArrayList<>();
        for (MemoryEntry e : fused) {
            double sim = dense.getOrDefault(e.id(), 0.0);
            double rrfNorm = maxRrf > 0 ? rrf.getOrDefault(e.id(), 0.0) / maxRrf : 0.0;
            Map<String, String> m = new HashMap<>(e.metadata() == null ? Map.of() : e.metadata());
            m.put("similarity", String.format("%.4f", sim));
            m.put("rrfScore", String.format("%.4f", rrfNorm));
            result.add(new MemoryEntry(e.id(), e.agentType(), e.teamId(), e.content(),
                    Map.copyOf(m), e.level(), e.createdAt(), e.embedding()));
        }
        return result;
    }

    /** 本地 BM25 实现（基于词频 + 文档长度归一）。 */
    private Map<Long, Double> bm25Scores(String query, List<MemoryEntry> pool) {
        Map<String, Integer> df = new HashMap<>();
        Map<Long, Map<String, Integer>> tf = new HashMap<>();
        Map<Long, Integer> docLen = new HashMap<>();
        for (MemoryEntry e : pool) {
            Map<String, Integer> f = new HashMap<>();
            for (String tok : HeuristicReranker.tokenize(e.content())) {
                f.merge(tok, 1, Integer::sum);
            }
            tf.put(e.id(), f);
            docLen.put(e.id(), e.content().length());
            for (String tok : f.keySet()) {
                df.merge(tok, 1, Integer::sum);
            }
        }
        double avgLen = docLen.values().stream().mapToInt(Integer::intValue).average().orElse(1.0);
        int N = pool.size();
        Map<Long, Double> scores = new HashMap<>();
        List<String> qToks = new ArrayList<>(HeuristicReranker.tokenize(query));
        for (MemoryEntry e : pool) {
            double score = 0.0;
            Map<String, Integer> f = tf.get(e.id());
            int len = docLen.get(e.id());
            for (String qt : qToks) {
                Integer n = f.get(qt);
                if (n == null) continue;
                double idf = Math.log((N - df.getOrDefault(qt, 0) + 0.5) / (df.getOrDefault(qt, 0) + 0.5) + 1.0);
                double denom = n + K1 * (1 - B + B * len / avgLen);
                score += idf * (n * (K1 + 1)) / denom;
            }
            scores.put(e.id(), score);
        }
        return scores;
    }

    @Override
    public void deleteByMetadata(String teamId, String key, String value) {
        String t = Teams.sanitize(teamId);
        store.values().removeIf(e -> t.equals(e.teamId()) && value.equals(e.metadata().get(key)));
    }

    /**
     * 直接注入一条已构造条目（同包测试用：可指定 createdAt 验证 freshness 过滤）。
     * 未携带向量时自动补算，保证后续检索可用；search 侧按写入语义同步分词索引由内存检索即时计算。
     */
    void putDirect(MemoryEntry entry) {
        MemoryEntry e = entry;
        if (e.embedding() == null || e.embedding().length == 0) {
            float[] emb = embeddingClient.embed(e.content());
            e = new MemoryEntry(e.id(), e.agentType(), e.teamId(), e.content(),
                    e.metadata(), e.level(), e.createdAt(), emb);
        }
        store.put(e.id() == null ? idGen.getAndIncrement() : e.id(), e);
    }
}
