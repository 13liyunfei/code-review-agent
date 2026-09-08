# RAG 语义正确性修复（similarity 口径 / 中文分词 / 结构化查询 / MMR / small-to-big）测试报告

- **分支**：`feature/rag-hardening`（续 `a36992d`、`31e6656`）
- **PR**：[reviewer/code-review-agent#6](http://localhost:3000/reviewer/code-review-agent/pulls/6)（base: `main`）
- **日期**：2026-09-08
- **结论**：✅ **`mvn clean test` 全量 338 测试全绿（0 失败 / 0 错误 / 0 跳过）**；较上一轮基线 318 新增 20 条。
  真库（PostgreSQL 17.11 + pgvector 0.8.6）对照实验给出「修复前 f / 修复后 t」的硬证据。

---

## 1. 背景：外部审查意见 → 逐条核实 → 判定

用户提供了 deepseek 对本项目 RAG 管线的审查长文，要求先判断「是否真的需要修复」再动手。
核实方式：**读代码 + 真库 `psql` 实证**，不采信任何未经复现的结论。

### 1.1 判定表（8 条指控 + 我补充的 2 条）

| # | 审查意见 | 核实结论 | 处置 |
|---|---------|---------|------|
| 1 | `similarity` 阈值闸门空转 | **属实，且比描述更严重**——PG 路径把 RRF 归一化排名分当余弦用，第 50 名仍有 ~0.55，而闸门是 0.3 → 永不拦截 | ✅ P0 修复 |
| 2 | 中文 BM25「基本失效」 | **属实，且比描述更严重**——不仅中文失效，连 `SQL` / `PreparedStatement` 这类英文词都命中不了（见 §3.1） | ✅ P0 修复 |
| 3 | 切块把标题行丢了 | **属实**——标题只进 metadata，嵌入丢章节语义、LLM 看不到归属 | ✅ P1 修复 |
| 4 | 层级上下文是假的（`parentSection == section`） | **属实**——父子检索（small-to-big）根本无法成立 | ✅ P1 修复 |
| 5 | 检索查询是 diff 原文截断，语义鸿沟大 | **属实**——`+/-`、行号、上下文噪音全在里面，且截断后真正改动的方法常常不在其中 | ✅ P1 修复 |
| 6 | Top-N 被同章节碎片占满 | **属实**——无多样性挑选，5 条注入可能只覆盖 1 个知识点 | ✅ P2 修复（MMR） |
| 7 | `PgVectorMemoryStore:151` 注释错误（称 simple 词典「保证中文按字符切分」） | **属实**——与真库行为相反 | ✅ 修正注释 |
| 8 | 默认打开 query-rewrite | **明确反对**——地基（#1 闸门空转、#2 BM25 全废）没修之前，开了也无法验证收益，还白搭一次 LLM 调用 | ❌ 不采纳（维持默认关闭，地基修好后可按 golden 指标验证再开） |
| 9 | *(我补充)* `InfrastructureConfig` 的 `vector-dim` 代码默认 256，而 yml 是 1024 | **属实且危险**——无 yml 覆盖的环境启动会触发「备份后重建向量列」，存量向量作废 | ✅ 改为 1024 |
| 10 | *(我补充)* `site/zh/guide/rag.md` 文档臆造 | **属实**——文档里的 `review.rag.enabled` / `chunk-size:512` / `top-k:4` 三个配置项在 yml 中**全不存在** | ✅ 整份重写 |

---

## 2. 落地矩阵

### P0-① `similarity` 语义统一为真实余弦（`PgKnowledgeStore`）

**病根**：RRF 融合分 `60/(60+rank)` 经「除以第一名」归一化后被写进 `similarity`，
而 `RagEvaluator` 的 `min-similarity=0.3` 拿它当余弦比较——第一名恒为 1.0、第 50 名仍有 0.545，**闸门永远关不上**。
上一轮把 `retrieve-k` 从 10 提到 50，反而把这道闸门焊得更死（50 条候选 100% 放行）。

**修复**：

- 融合阶段 `similarity` 写 **真实余弦**（`1 - (embedding <=> q)`），RRF 归一化分另存 `rrfScore`（仅供排序与观测，绝不参与阈值判断）；
- 新增 `fillMissingDenseSim()`：仅被稀疏路命中的条目不在稠密路 `widen` 窗口内，补一次 `id IN (...)` 小查询算余弦，保证**同一结果集内语义一致**；
- `InMemoryKnowledgeStore` 同步补写 `rrfScore`，两后端字段口径完全对齐。

> 这是记忆中「旧 bug 的漏网半修」：当年只修了 InMemory 侧，漏了 Pg 侧。

### P0-② 中文 / 标识符分词（`TextTokenizer` 新增）

**病根**：PG `simple` 词典既不做中文分词、也不拆标识符——无空格中文整段是**一个**词位
（真库实测：`'禁止使用字符串拼接的sql':1`），于是中文词查不到、连英文词 `SQL` 也查不到。稀疏路（BM25，权重 0.3）等于空转。

**修复**：新增 `TextTokenizer`（中文 bigram + camelCase/snake_case 子词 + 全大写缩写整体保留），
**写入侧与检索侧共用一套口径**（两侧不一致会比不分词更隐蔽）：

- 写入：`PgVectorMemoryStore` 落库 `to_tsvector('simple', TextTokenizer.toTokenString(content))`；
- 检索：`plainto_tsquery` → `to_tsquery('simple', ?)` + `toTsQueryOr(query, 40)`（**OR 而非 AND**：RAG 查询是整段 diff，AND 要求全词命中，长查询必然零命中，是另一种形式的稀疏路失效）；
- 存量：新增 `migrateTsvectorTokens()` 一次性重建，由 `rag_schema_migration.tsvector-tokenize-v1` 保证幂等，上限 10 万行，失败只 WARN 不阻断启动；
- 顺带修掉 `HeuristicReranker` 的中文 Jaccard 恒 0（原先中文整段成词，与查询词集合交集为空 → 启发式重排全程空转）。

### P0-③ 配置默认值漂移（`InfrastructureConfig` / `ReviewAgentConfig`）

- `pgvector.vector-dim` 代码默认 `256` → **`1024`**（防无 yml 覆盖时误触发向量列重建毁存量数据）；
- `review.rag.min-similarity` 代码默认 `0.0` → **`0.3`**、`eval-enabled` `false` → **`true`**（与 yml 对齐；否则缺 yml 环境闸门静默失效）；
- embedding `model` 4b→0.6b、`dim` 2560→1024（与 yml 已实测可用的 0.6b/1024 口径一致）。

### P1-④ 切块真层级 + 标题进正文（`StructuredChunker`）

- 标题行不再丢弃：正文前缀 `【A > B > C】`（只进 metadata 会两头受损：嵌入丢语义、LLM 只从 source 括号里猜章节）；
- `Deque<String> headingStack` 按 `#` 数量维护**真层级**，`parentSection` 取上一层（原来直接写成 `section`，是假层级）；
- 新增 `headingPath` 与 `parentExcerpt`（父章节首段摘要，≤300 字符）供 small-to-big 回填；
- 去掉假累积的 `sectionBuf`（切分状态全走局部变量，避免共享单例的并发问题）。

### P1-⑤ 结构化检索查询（`DiffQueryExtractor` 新增）

从 diff 提炼 `文件 x（java）；类 X；方法 m1, m2；符号 s1, s2`，替代「diff 拼接取前 500 字符」。

- 常量：`MAX_FILES=5 / MAX_METHODS=8 / MAX_SYMBOLS=20 / FALLBACK_CHARS=300`；
- 三级兜底：`changedIdentifiers()`（扫 `+/-` 行）→ 为空时 `allIdentifiers()`（非标准 unified diff）→ 仍无方法且无符号时追加原文截断；
- 判据是「是否提炼出方法/符号」，而非字符串长度（长度判据会让空壳查询蒙混过关）。

### P2-⑥ MMR 多样性挑选（`RagContextBuilder`）

`λ·relevance − (1−λ)·max_redundancy`，λ=0.7，冗余度用与重排同口径的 `TextTokenizer` Jaccard。
重排池放大到 `injectTopN*3` 供 MMR 挑选（否则没有挑选余地）。开关 `review.rag.mmr.enabled`（默认 true）。

### P2-⑦ small-to-big 父章节回填

注入阶段把命中叶子块的 `parentExcerpt` 附在块后（`↳ 所属章节上下文：`），同一父摘要只追加一次。开关 `review.rag.parent-context.enabled`（默认 true）。

### 文档同步

- `site/zh/guide/rag.md` **整份重写**（原文档三个配置项在 yml 中不存在，属臆造文档，比没文档更危险）；
- 双语 README 的 RAG 章节各补 5 个 bullet；`application.yml` 新增两个开关段并带注释。

---

## 3. 证据

### 3.1 真库对照实验（PostgreSQL 17.11 + pgvector 0.8.6）

```sql
SELECT
  (to_tsvector('simple','禁止使用字符串拼接的SQL，必须使用参数绑定') @@ plainto_tsquery('simple','参数绑定')) AS fix前_中文词,
  (to_tsvector('simple','禁止使用字符串拼接的SQL，必须使用参数绑定') @@ plainto_tsquery('simple','SQL'))         AS fix前_英文词,
  (to_tsvector('simple','禁止 使用 字符 串拼 接的 sql 必须 使用 参数 绑定') @@ to_tsquery('simple','参数 | 数绑 | 绑定')) AS fix后_中文词,
  (to_tsvector('simple','禁止 使用 字符 串拼 接的 sql 必须 使用 参数 绑定') @@ to_tsquery('simple','sql'))       AS fix后_英文词;

 fix前_中文词 | fix前_英文词 | fix后_中文词 | fix后_英文词
--------------+--------------+--------------+--------------
 f            | f            | t            | t
```

结论：稀疏路此前**中文与英文双双失效**（不是「效果差」，是 `f`）；分词修复后双双 `t`。

### 3.2 真库迁移日志（一次性重建存量 tsvector，幂等）

```
[PgVector] 迁移：已按分词器重建 316 行的 search_vector（中文/标识符 BM25 生效）
[PgVector] 表与索引就绪（vector(1024), hnsw 索引, gin(tsvector)）
```

### 3.3 `similarity` 语义与闸门（`PgRagSemanticsE2eTest` 实测日志）

用**受控向量桩**（正交方向）让稠密路排序可预测，插入一条与查询**反方向**的文档（余弦 = −1）：

```
[PgKnowledge] 混合检索：team=e2e-cos-…, topK=10, 融合命中 1 条, 耗时 8ms
[RAG-Eval] 候选 1 条，阈值 0.3 放行 0 条，拦截 1 条
```

修复前该条目 `similarity` 是 RRF 归一化分 **1.0**（第一名恒为 1.0），必然放行；修复后为真实余弦 **−1.0000**，被闸门正确拦截——**abstain 真正生效**。

---

## 4. 测试清单

### 4.1 新增测试（20 条）

| 测试类 | 条数 | 覆盖点 |
|--------|------|--------|
| `TextTokenizerTest` | 8 | 中文 bigram、中英混排、camelCase / snake_case 子词、**全大写缩写不被切碎**（`SQL` 曾变 S/Q/L 被长度阈值丢弃）、OR 查询串、空/空白输入 |
| `DiffQueryExtractorTest` | 4 | 文件/类/方法/符号提炼、上限截断、**纯中文回退原文**、空输入 |
| `RagSemanticsTest` | 4 | MMR 避免 Top-N 被近重复块占满（**on/off 对照组**）、父章节上下文注入、高阈值 abstain、结构化查询送达 store |
| `PgRagSemanticsE2eTest` | 3 | **真库**：分词行 vs 未分词行对照组、similarity = 真实余弦且闸门生效、golden hit@k / MRR |
| `StructuredChunkerTest` | +1 | 嵌套标题产出**真** `parentSection` 与 `parentExcerpt` |

### 4.2 改断言（锁旧行为的断言随语义修正）

- `StructuredChunkerTest.headingsCreateSeparateSectionsWithHierarchyMeta`：旧断言 `parentSection == section` 锁的是**假层级**，改为 `(root)`，并新增嵌套层级用例；
- `RagContextBuilderHardeningTest.defaultIdentityRewriterKeepsRawQuery`：改为对齐 `DiffQueryExtractor.extract(...)`。

### 4.3 全量回归

```
[INFO] Tests run: 338, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

（基线 318 → 338；`mvn clean test` 必带 `clean`，防 surefire 残留导致计数虚高。含 `DeadCodeGuardTest` 门禁。）

---

## 5. 提交前自查（本轮额外修掉的 3 处）

1. **`mmrSelect` 重复分词**：MMR 是 O(topN × 候选) 的贪心，原先每轮外层都重新解析全部候选内容 → 改为进入前预计算一次词集；
2. **`rest.remove(best)` 按对象删除**：`MemoryEntry` 是 record，同内容条目 `equals` 相同，按对象删会误删 → 改为按下标操作；
3. **`appendKnowledgeBlocks` 空指针顺序**：原实现先 `e.metadata().getOrDefault(...)` 再判 null → 先取出局部变量并兜底 `Map.of()`。

另：`docs/business-flow.html` 被外部工具注入了 205 处 `data-page-node-id` 属性（内容一字未变，已用「剥离属性后 diff」确认等价），不属于本次改动，已还原后提交。

---

## 6. 风险与后续

| 项 | 说明 |
|----|------|
| 存量 `search_vector` 重建 | 一次性迁移、幂等、上限 10 万行、失败只 WARN。超 10 万行的库会跳过并打 WARN，需手工分批重建 |
| `min-similarity` 默认 0.3 | 真实余弦下 0.3 已是宽松阈值；若线上出现「知识分区频繁 abstain」，应优先查嵌入模型维度/口径是否一致，而不是直接调低阈值 |
| query-rewrite 仍默认关闭 | 地基（闸门 + BM25）修好后，可用 §4 的 golden hit@k / MRR 在真库上量化收益再决定是否开 |
| 未做真实模型回归 | 本轮 E2E 用受控向量桩保证**确定性**；真实 embedding 下的绝对召回率需另做 golden 集评估（依赖真实 LLM，不进 CI） |
