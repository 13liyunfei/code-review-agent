# RAG 检索加固（P0 五项）测试报告

- **分支**：`feature/rag-hardening`（自 `2a07394` 派生）
- **提交**：`a36992d` feat(rag): harden retrieval — configurable window, LLM query rewrite, hit@k/MRR, freshness, HNSW
- **PR**：[reviewer/code-review-agent#5](http://localhost:3000/reviewer/code-review-agent/pulls/5)（base: `main`）
- **日期**：2026-09-08
- **结论**：✅ **`mvn clean test` 全量 318 测试全绿（0 失败 / 0 错误 / 0 跳过）**；真库 E2E 实证 HNSW 迁移与 freshness 过滤真实生效。

---

## 1. 背景与范围

承接「RAG 与大厂检索方案差距审计」结论，用户确认落地 **P0 全部五项 + golden 指标扩展**（P1/P2 中依赖真实模型 / 大数据量评估的项见 §7 后续标注）。

| # | P0 项 | 业界对标 | 状态 |
|---|-------|---------|------|
| 1 | 候选扩窗 retrieve-k 可配置 | 混合检索先召回 Top-50~200 再精排 | ✅ |
| 2 | 查询改写层（query rewrite） | 弥合代码 patch ↔ 规范文档语义鸿沟 | ✅ |
| 3 | golden 指标扩展（hit@k / MRR） | 排序质量核心评估口径 | ✅ |
| 4 | freshness（知识时效过滤） | 陈旧知识不污染当前审查 | ✅ |
| 5 | HNSW 向量索引（含迁移/回退） | pgvector ≥0.5 推荐 ANN，召回精度优于 ivfflat | ✅ |

## 2. 落地矩阵

### 2.1 候选扩窗（#1）

- **改动**：`RagContextBuilder.CANDIDATE_K=10` 静态常量 → `@Value("${review.rag.retrieve-k:50}")` + `inject-top-n`（默认 5）。链式 `withRetrievalWindow(k, n)` 供测试直接设定。
- **配置**：`review.rag.retrieve-k: ${RAG_RETRIEVE_K:50}`（yml 注释标注对标业界 Top-50~200）。
- **安全**：候选池扩大但下游仍走「阈值过滤(abstain) + Cross-Encoder 重排 + Top-N 注入」，注入质量不降。

### 2.2 查询改写层（#2）

- **接口**：`ReviewQueryRewriter`（`rewrite(rawQuery)` + `name()`），javadoc 明确 **fail-safe 契约：任何实现都不得让查询变得更差**。
- **实现**：
  - `IdentityQueryRewriter`：原样返回（默认 / 离线降级兜底；`rewrite(null)` 返回 `""`）。
  - `LlmQueryRewriter`：构造注入 `LlmClient`，内置中文 prompt 模板；`sanitize()` 先取首行再去引号（修复跨行引号误判）；MIN_LEN=1 / MAX_LEN=200；异常/空白/越界一律回退恒等。
- **装配**：`ReviewAgentConfig` 加 `@ConditionalOnProperty("review.rag.query-rewrite.enabled", havingValue="true")` bean（默认关闭）；`RagContextBuilder.setQueryRewriter` `@Autowired(required=false)` —— 容器无 bean 时保持恒等，链路零感知。
- **接线**：`buildContext` = rawQuery → 改写（有差异打日志 `[RAG] 查询改写（name → agentType）`）→ 检索。

### 2.3 golden 指标扩展（#3）

- **改动**：`RagMetrics` record 增加 `firstHitRank`（1-based，无命中 0）/ `mrr`（1/firstHitRank）+ `hitAtK(k)` 便捷方法；`evaluate()` 仅在 `evalEnabled && groundTruth 非空` 时计算排序指标（兼容无 ground-truth 运行）。
- **语义**：hit@k 由 `firstHitRank <= k` 判定；MRR 为业界排序质量核心指标。

### 2.4 freshness（#4）

- **配置**：`review.rag.max-age-days: ${RAG_MAX_AGE_DAYS:0}`（0 = 不过滤，向后兼容）。
- **接口兼容**：`KnowledgeStore.searchKnowledge` 加 **5 参 default 重载**（委托 4 参）——存量实现与测试桩（StubKnowledgeStore/RecordingStore）零改动编译。
- **实现**：
  - `PgKnowledgeStore`：`freshSql = " AND created_at >= now() - (? || ' seconds')::interval"`，仅 maxAge>0 时拼入 dense/sparse 两条 SQL（占位符顺序随 includeGlobal 动态推进，参数化无注入面）。
  - `InMemoryKnowledgeStore`：`cutoff = now - maxAge`，流式过滤 `created_at`；同包测试辅助 `putDirect()` 自动补 embedding。
- **语义**：`null / <=0` 表示不过滤；只过滤 `agent_type='RAG'` 行，EXPERIENCE 记忆不混入知识检索。

### 2.5 HNSW 索引（#5）

- **配置**：`pgvector.index-type: ${PGVECTOR_INDEX_TYPE:hnsw}`（`InfrastructureConfig` 注入 8 参构造）。
- **行为**（`PgVectorMemoryStore.ensureVectorIndex`，幂等）：
  1. 索引已为期望类型 → 跳过（不重复重建大索引）；
  2. 存量 ivfflat 且期望 hnsw → WARN + `DROP INDEX` 重建迁移（数据量小启动期可接受）；
  3. hnsw 创建失败（pgvector < 0.5）→ WARN 回退 ivfflat，保证服务可启动；
  4. 非法 indexType（非 hnsw/ivfflat）→ `IllegalArgumentException` fail-fast。
- **兼容**：7 参旧构造委托默认 `"hnsw"`；索引名沿用 `idx_memory_embedding`，迁移路径干净。

## 3. 测试证据

### 3.1 新增 / 扩展测试

| 测试类 | 用例数 | 覆盖点 |
|--------|-------|--------|
| `ReviewQueryRewriterTest` | 7 | 恒等返回、LLM 正常改写、去引号取首行、空/超长/异常回退、null 处理（StubLlm 可编程） |
| `RagContextBuilderHardeningTest` | 9 | retrieve-k 默认 50 且可配置、freshness 透传+端到端过滤+默认关闭、改写接线（spy）、改写改变重排顺序、abstain 保持、跨团队隔离回归（RecordingStore） |
| `RagEvaluatorTest`（扩展） | +3 | 首位命中 MRR=1、第 3 位 MRR=1/3 + hitAtK 判定、无命中 MRR=0 |
| `PgHnswIndexE2eTest`（真库） | 2 | hnsw 幂等（已就绪跳过）、hnsw↔ivfflat 双向迁移 + hnsw 失败回退（pgvector 版本探测 ≥0.5） |
| `PgRagFreshnessE2eTest`（真库） | 2 | 1024 维零向量桩插入 now/100 天前两行 → maxAge=30 天只召回新鲜行；EXPERIENCE 行不混入 RAG 检索 |

### 3.2 真库 E2E 日志锚点（实证生效）

```
[PgVector] 存量向量索引为 ivfflat，按配置迁移为 hnsw（DROP 后重建）
[PgVector] 向量索引已创建：hnsw
[PgVector] 向量索引已就绪（hnsw），跳过创建        ← 幂等
[PgKnowledge] 混合检索：team=e2e-nonrag-…, topK=10, 融合命中 0 条  ← freshness 过滤后空召回路径
```

### 3.3 全量回归

```
mvn clean test（clean 防 surefire 残留虚高）
Tests run: 318, Failures: 0, Errors: 0, Skipped: 0   → BUILD SUCCESS
```

## 4. PR 自审查走查结论

逐文件精读 `a36992d` diff（PgVectorMemoryStore / PgKnowledgeStore / InMemoryKnowledgeStore / KnowledgeStore / RagContextBuilder / RagEvaluator / LlmQueryRewriter / ReviewAgentConfig / InfrastructureConfig）：

- ✅ 接口演进全部向后兼容：freshness 5 参 default 重载、7 参存储构造委托、`RagMetrics` 构造点项目内已全量同步（编译门禁兜底）。
- ✅ freshness SQL 参数化无注入面（固定语句 + 占位符）；占位符顺序按 includeGlobal 动态推进逻辑正确。
- ✅ HNSW 三态（幂等/迁移/回退）清晰，索引名不变迁移干净；非法类型 fail-fast。
- ✅ 查询改写 fail-safe 契约落实：异常/空/超长一律回退恒等，检索永不劣化；LLM 输出仅取首行防解释文本污染。
- ✅ DeadCodeGuard 门禁安全：`ReviewQueryRewriter` 接口 + 两个实现均有生产引用（RagContextBuilder / ReviewAgentConfig）。
- 👀 **后续可留意（非本次缺陷）**：
  1. `appendExperience` 经验通道使用改写前 `rawQuery`——经验库条目为自然语言 pattern，理论上复用改写后查询可进一步提升经验召回（保守未改，不影响正确性）；
  2. `PgVectorMemoryStore.currentEmbeddingIndexType` 对未知索引类型返回后走 DROP 重建分支（保守安全）。

## 5. 文档同步

- `README.md` / `README.zh-CN.md`：RAG 章节流程图示（改写/freshness 步骤）、组件 bullet（查询改写/扩窗/freshness/HNSW）、配置 yaml 块（4 新键）、目录注释。
- `application.yml`：`pgvector.index-type` / `review.rag.retrieve-k|inject-top-n|max-age-days|query-rewrite.enabled` 全注释；修正「默认 0.6b」理由为成本口径（hnsw 无 ivfflat 2000 维上限，4b=2560 维亦可建索引）。

## 6. 测试环境

- PostgreSQL 17.11 + pgvector **0.8.6**（HNSW 支持确认）；embedding 1024 维（kinfra-text-embedding-0.6b）。
- 本地 Gitea（localhost:3000）；`origin = reviewer/code-review-agent`。

## 7. P1/P2 后续标注（依赖真实模型 / 大数据量，本期未做）

| 项 | 依赖 | 建议后续 |
|----|------|---------|
| 查询改写端到端增益评测 | 真实 LLM + 带 ground-truth 规范库 | 开 `query-rewrite.enabled` 跑 golden 对比（identity vs LLM 改写的 hit@k/MRR 差值） |
| golden 数据集规模化 | 真实规范文档人工标注 | 扩充 expectedId 标注集，纳入 CI 回归基线 |
| HNSW 大数据量基准 | ≥10w 向量真实库 | ef_construction/ef_search/m 调优 + 构建耗时/召回权衡压测 |
| 混合检索权重调优 | 真实查询分布 | RRF 融合参数 k / dense-sparse 权重在真实分布上标定 |
| 经验通道复用改写查询 | — | 上表 §4 👀1 落地 + 回归 |
