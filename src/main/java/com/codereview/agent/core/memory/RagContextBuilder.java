package com.codereview.agent.core.memory;

import com.codereview.agent.core.model.CodeDiff;
import com.codereview.agent.core.rag.DiffQueryExtractor;
import com.codereview.agent.core.rag.HeuristicReranker;
import com.codereview.agent.core.rag.IdentityQueryRewriter;
import com.codereview.agent.core.rag.KnowledgeStore;
import com.codereview.agent.core.rag.RagEvaluator;
import com.codereview.agent.core.rag.Reranker;
import com.codereview.agent.core.rag.ReviewQueryRewriter;
import com.codereview.agent.core.rag.TextTokenizer;
import com.codereview.agent.tenant.Teams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * RAG 上下文构建器（见文档"RAG 的使用位置"）。
 *
 * <p>审查前检索规范文档 / 历史 PR / 安全 Wiki，将相关内容作为【相关历史知识】注入提示词。
 * 内部链路对标业界最佳实践：
 * <pre>
 *   提取查询 → [查询改写(可选)] → 混合检索(向量+BM25,RRF融合) → 阈值过滤(abstain)
 *        → Cross-Encoder重排 → Top-5格式化
 * </pre>
 * 各阶段能力由协作组件提供，均可离线运行（无 API 时自动降级到启发式）：
 * <ul>
 *   <li>查询改写：{@link ReviewQueryRewriter}（默认 {@link IdentityQueryRewriter} 恒等；
 *       配置 {@code review.rag.query-rewrite.enabled=true} 时切 LLM 改写，失败自动降级）；</li>
 *   <li>混合检索：{@link KnowledgeStore#searchKnowledge}（PG 实现含 tsvector BM25 + 向量 RRF，
 *       候选窗大小由 {@code review.rag.retrieve-k} 控制，默认 50 对标业界 Top-50~200 召回）；</li>
 *   <li>阈值过滤 / 选择性回答：{@link RagEvaluator#filterByThreshold}；</li>
 *   <li>重排：{@link Reranker}（{@link HeuristicReranker} 默认，可配 {@code ApiReranker}）；</li>
 *   <li>评估 / 可观测：{@link RagEvaluator}（记录命中、相似度、golden 排序指标 firstHitRank/MRR）。</li>
 * </ul>
 * 检索时始终纳入团队自身内容 + 全局基线（编码规范手册），实现"全局基线 + 团队叠加"。
 *
 * <p><b>freshness（知识时效）</b>：{@code review.rag.max-age-days} 控制召回的知识块最大入库年龄
 * （默认 0 = 不过滤），检索侧（Pg/InMemory 的 {@code searchKnowledge} 5 参重载）按 created_at 过滤，
 * 避免过期历史 PR 复盘 / 旧版规范污染当前审查。
 *
 * <p><b>历史经验回流</b>：知识块之外，同时把 {@link ExperienceStore}（反思沉淀的「问题模式 →
 * 建议」条目）命中项以【历史经验参考】分区注入——闭合记忆闭环的"读"侧
 * （写侧由 ReflectionService 在审查完成后执行）。经验命中即刷新遗忘时钟（spaced repetition）。
 */
@Component
public class RagContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(RagContextBuilder.class);

    private final KnowledgeStore knowledgeStore;
    private final Reranker reranker;
    private final RagEvaluator evaluator;
    private final ExperienceStore experienceStore;

    /** 初检召回数（重排前）。业界召回窗 Top-50~200 再精排，默认 50（可配 {@code review.rag.retrieve-k}）。 */
    @Value("${review.rag.retrieve-k:50}")
    private int retrieveK = 50;
    /** 最终注入 Top-N（精排输出）。 */
    @Value("${review.rag.inject-top-n:5}")
    private int injectTopN = 5;
    /** freshness：知识块最大入库年龄（天），0 = 不过滤。 */
    @Value("${review.rag.max-age-days:0}")
    private long maxAgeDays = 0;
    /** MMR 多样性挑选（重排后按「相关性 - 冗余度」挑 Top-N），避免 Top-N 被同章节碎片占满。 */
    @Value("${review.rag.mmr.enabled:true}")
    private boolean mmrEnabled = true;
    /** small-to-big：为命中的叶子块回填父章节上下文（StructuredChunker 写入的 parentExcerpt）。 */
    @Value("${review.rag.parent-context.enabled:true}")
    private boolean parentContextEnabled = true;
    /** MMR 相关性权重（1-λ 为冗余惩罚权重），业界常用 0.7。 */
    private static final double MMR_LAMBDA = 0.7;
    /** 父章节上下文注入的最大字符数。 */
    private static final int PARENT_CONTEXT_MAX = 300;
    /** 查询改写器（默认恒等；由容器按配置注入 LLM 实现，无 Spring 时测试可 new 后覆写）。 */
    private ReviewQueryRewriter queryRewriter = new IdentityQueryRewriter();

    @Autowired
    public RagContextBuilder(KnowledgeStore knowledgeStore,
                             Reranker reranker,
                             RagEvaluator evaluator,
                             ExperienceStore experienceStore) {
        this.knowledgeStore = knowledgeStore;
        this.reranker = reranker;
        this.evaluator = evaluator;
        this.experienceStore = experienceStore;
    }

    /**
     * 容器注入查询改写器（{@code review.rag.query-rewrite.enabled=true} 时由配置类装配
     * LLM 实现；否则容器无此 bean，required=false 保留默认恒等，链路零感知）。
     */
    @Autowired(required = false)
    public void setQueryRewriter(ReviewQueryRewriter queryRewriter) {
        if (queryRewriter != null) {
            this.queryRewriter = queryRewriter;
        }
    }

    /** 测试 / 工具用：直接设定改写器与窗口（跳过 Spring 装配）。 */
    public RagContextBuilder withQueryRewriter(ReviewQueryRewriter rewriter) {
        this.queryRewriter = rewriter == null ? new IdentityQueryRewriter() : rewriter;
        return this;
    }

    /** 测试 / 工具用：直接设定检索窗口。 */
    public RagContextBuilder withRetrievalWindow(int retrieveK, int injectTopN) {
        this.retrieveK = retrieveK;
        this.injectTopN = injectTopN;
        return this;
    }

    /** 测试 / 工具用：直接设定 freshness 天数。 */
    public RagContextBuilder withMaxAgeDays(long days) {
        this.maxAgeDays = days;
        return this;
    }

    /** 测试 / 工具用：是否启用 MMR 多样性挑选。 */
    public RagContextBuilder withMmr(boolean enabled) {
        this.mmrEnabled = enabled;
        return this;
    }

    /** 测试 / 工具用：是否回填父章节上下文（small-to-big）。 */
    public RagContextBuilder withParentContext(boolean enabled) {
        this.parentContextEnabled = enabled;
        return this;
    }

    /**
     * 为指定团队 / Agent 构建 RAG 增强上下文。
     *
     * @param teamId    团队标识（含全局基线叠加）
     * @param agentType 审查 Agent 类型（仅用于日志）
     * @param diffs     代码变更
     * @return 检索到的相关知识文本（无则返回空串，即选择性回答 abstain）
     */
    public String buildContext(String teamId, String agentType, List<CodeDiff> diffs) {
        long t0 = System.currentTimeMillis();
        // 1. 从代码提取查询意图（diff 形态）
        String rawQuery = extractQueryFromDiffs(diffs);
        // 1b. 查询改写：diff 形态 → 规范术语形态（可选；失败降级恒等，绝不劣化）
        String query = queryRewriter.rewrite(rawQuery);
        if (!query.equals(rawQuery)) {
            log.info("[RAG] 查询改写（{} → {}）：{} 字符 → {} 字符",
                    queryRewriter.name(), agentType, rawQuery.length(), query.length());
        }
        // 2. 混合检索（向量 + BM25 + RRF），含全局基线 + freshness 过滤
        Duration maxAge = maxAgeDays > 0 ? Duration.ofDays(maxAgeDays) : null;
        List<MemoryEntry> candidates = knowledgeStore.searchKnowledge(query, retrieveK,
                Teams.sanitize(teamId), true, maxAge);
        // 3. 阈值过滤（低于 minSimilarity 的块剔除；全低于则 abstain）
        List<MemoryEntry> passed = evaluator.filterByThreshold(candidates);
        StringBuilder sb = new StringBuilder();
        if (passed.isEmpty()) {
            log.info("[RAG] 无相关知识（候选 {} 条均低于阈值或为空），知识分区 abstain, 耗时 {}ms",
                    candidates.size(), System.currentTimeMillis() - t0);
        } else {
            // 4. Cross-Encoder 重排：开 MMR 时给更大的候选池，否则 MMR 没有挑选余地
            int rerankPool = mmrEnabled
                    ? Math.min(passed.size(), Math.max(injectTopN * 3, injectTopN))
                    : injectTopN;
            List<MemoryEntry> reranked = reranker.rerank(query, passed, rerankPool);
            // 5. MMR 多样性挑选（相关性 − 冗余度）：避免 Top-N 被同章节高度相似的碎片占满
            List<MemoryEntry> picked = (mmrEnabled && reranked.size() > injectTopN)
                    ? mmrSelect(reranked, query, injectTopN) : reranked;
            // 6. 注入前去重：按 content hash 去除 handbook 重叠切块产生的重复块（避免同一段注入多次）
            List<MemoryEntry> deduped = dedupeByContent(picked);
            if (deduped.size() < picked.size()) {
                log.info("[RAG] 去重：注入前剔除 {} 个重复块（重叠切块导致），{} → {}",
                        picked.size() - deduped.size(), picked.size(), deduped.size());
            }
            // 7. 评估指标 + 格式化（含 small-to-big 父章节上下文回填）
            RagEvaluator.RagMetrics metrics = evaluator.evaluate(deduped, null);
            appendKnowledgeBlocks(sb, deduped);
            log.info("[RAG] 上下文构建：team={}, agent={}, 查询={}字符, 候选 {} → 放行 {} → 注入 Top-{} (maxSim={}), 耗时 {}ms",
                    Teams.sanitize(teamId), agentType, query.length(), candidates.size(),
                    passed.size(), deduped.size(),
                    String.format("%.4f", metrics.maxSimilarity()),
                    System.currentTimeMillis() - t0);
        }
        // 7. 历史经验回流（记忆闭环读侧；独立于知识 abstain——经验命中仍注入）
        appendExperience(sb, teamId, agentType, rawQuery);
        String result = sb.toString().trim();
        if (!result.isBlank()) {
            log.info("[RAG] 注入上下文合计 {} 字符（知识分区 + 历史经验参考）, 总耗时 {}ms",
                    result.length(), System.currentTimeMillis() - t0);
        }
        return result;
    }

    /**
     * 经验条目通道注入：反思沉淀的「问题模式 → 建议」命中项以【历史经验参考】分区回流审查提示词。
     * 命中即由 {@link ExperienceStore} 刷新 recordHit（spaced repetition 反遗忘）。
     * 失败只 WARN 不阻断主链路（与知识通道同级的降级策略）。
     */
    private void appendExperience(StringBuilder sb, String teamId, String agentType, String query) {
        if (experienceStore == null || query == null || query.isBlank()) {
            return;
        }
        try {
            String exp = experienceStore.getRelevantExperiences(Teams.sanitize(teamId), query);
            if (!exp.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append("【历史经验参考】\n").append(exp).append('\n');
                log.info("[RAG] 历史经验注入：team={}, agent={}, 命中 {} 字符",
                        Teams.sanitize(teamId), agentType, exp.length());
            }
        } catch (Exception e) {
            log.warn("[RAG] 经验检索失败，跳过注入（不影响主审查）：{}", e.getMessage());
        }
    }

    /**
     * 按内容去重：handbook 等被重叠切块后，同一语义段会产出多个近似块，
     * 重排后 Top-N 可能被同源重复块占满，导致注入内容高度雷同。此处按 content 归一化后去重。
     *
     * @param entries 重排后的候选块
     * @return 去重后的块（保持原顺序）
     */
    private List<MemoryEntry> dedupeByContent(List<MemoryEntry> entries) {
        List<MemoryEntry> out = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (MemoryEntry e : entries) {
            if (e == null || e.content() == null) {
                continue;
            }
            String key = e.content().trim().replaceAll("\\s+", "");
            if (seen.add(key)) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * 从代码变更中提炼检索查询（<b>结构化</b>：文件 / 类 / 方法 / 符号）。
     *
     * <p>旧实现直接把 diff 拼起来取前 500 字符——里面塞满 {@code +/-}、行号与上下文噪音，
     * 拿这段噪声去撞以自然语言术语为主的规范库，语义鸿沟极大，而且截断后真正改动的方法
     * 常常根本没被包含。现委托 {@link DiffQueryExtractor} 提炼成贴近知识库措辞的查询。
     */
    private String extractQueryFromDiffs(List<CodeDiff> diffs) {
        return DiffQueryExtractor.extract(diffs);
    }

    /**
     * MMR（Maximal Marginal Relevance）挑选：
     * {@code argmax( λ·relevance(块, 查询) − (1−λ)·max redundancy(块, 已选集合) )}。
     *
     * <p>解决的问题：重排后的 Top-N 经常被同一章节的相邻碎片占满（内容高度雷同），
     * 看似注入了 5 条，实际只覆盖 1 个知识点。MMR 在「相关」与「信息量新增」之间取平衡。
     *
     * <p>文本相似度用 {@link TextTokenizer}（中文 bigram + 标识符子词）的 Jaccard——
     * 与启发式重排同口径，中文场景下才不会恒为 0。
     */
    private List<MemoryEntry> mmrSelect(List<MemoryEntry> candidates, String query, int topN) {
        java.util.Set<String> qTokens = TextTokenizer.tokenize(query);
        // 候选词集预计算一次：MMR 是 O(topN × 候选) 的贪心，
        // 若每轮外层都重新分词，长内容会被重复解析 topN 次（纯浪费且随块长放大）。
        List<java.util.Set<String>> candTokens = new java.util.ArrayList<>(candidates.size());
        for (MemoryEntry c : candidates) {
            candTokens.add(TextTokenizer.tokenize(c.content()));
        }
        List<MemoryEntry> selected = new java.util.ArrayList<>();
        List<java.util.Set<String>> selectedTokens = new java.util.ArrayList<>();
        // rest 存候选下标而非对象：①可直接复用预计算的词集；②避免按对象 remove（
        // MemoryEntry 是 record，同内容条目 equals 相同，按对象删会误删/删错）。
        List<Integer> rest = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            rest.add(i);
        }

        while (selected.size() < topN && !rest.isEmpty()) {
            int bestPos = 0;
            int bestIdx = rest.get(0);
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int p = 0; p < rest.size(); p++) {
                java.util.Set<String> cTokens = candTokens.get(rest.get(p));
                double rel = jaccard(qTokens, cTokens);
                double redundancy = 0.0;
                for (java.util.Set<String> s : selectedTokens) {
                    redundancy = Math.max(redundancy, jaccard(s, cTokens));
                }
                double mmr = MMR_LAMBDA * rel - (1 - MMR_LAMBDA) * redundancy;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIdx = rest.get(p);
                    bestPos = p;
                }
            }
            selected.add(candidates.get(bestIdx));
            selectedTokens.add(candTokens.get(bestIdx));
            rest.remove(bestPos);
        }
        return selected;
    }

    private static double jaccard(java.util.Set<String> a, java.util.Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        java.util.Set<String> inter = new java.util.HashSet<>(a);
        inter.retainAll(b);
        java.util.Set<String> union = new java.util.HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0.0 : (double) inter.size() / union.size();
    }

    /**
     * 格式化知识块（含 <b>small-to-big</b> 父章节上下文回填）。
     *
     * <p>命中叶子块时，仅凭一条孤立条款往往看不出它属于哪一章的什么约定；
     * 此处把 {@code StructuredChunker} 写入的 {@code parentExcerpt} 附在块后，
     * 同一父摘要只追加一次（多个兄弟块共享同一父章节）。
     */
    private void appendKnowledgeBlocks(StringBuilder sb, List<MemoryEntry> blocks) {
        java.util.Set<String> appendedParents = new java.util.HashSet<>();
        for (MemoryEntry e : blocks) {
            java.util.Map<String, String> meta =
                    e.metadata() == null ? java.util.Map.of() : e.metadata();
            sb.append("- [").append(meta.getOrDefault("source", "knowledge"))
                    .append("] ").append(e.content()).append('\n');
            if (!parentContextEnabled) {
                continue;
            }
            String parent = meta.get("parentExcerpt");
            if (parent == null || parent.isBlank()) {
                continue;
            }
            String excerpt = parent.length() > PARENT_CONTEXT_MAX
                    ? parent.substring(0, PARENT_CONTEXT_MAX) : parent;
            if (appendedParents.add(excerpt)) {
                sb.append("    ↳ 所属章节上下文：").append(excerpt.replace('\n', ' ')).append('\n');
            }
        }
    }
}
