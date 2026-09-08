# RAG 知识库

审查基于团队的上下文。引擎对团队知识切块、嵌入、检索，让 Agent 引用规范、手册与历史，而不是凭空猜测。

## 流水线

```
团队文档（规范 / 手册 / 视频）
  → 结构感知切块（标题链进正文 + headingPath + 父章节摘要）
  → 嵌入（走模型网关）
  → 写入 pgvector（search_vector 经中文/标识符分词）
  → 审查时：结构化查询 → [可选 LLM 改写] → 混合检索 → 阈值闸门 → 重排 → MMR 去冗 → 注入
```

## 切块

- **结构感知** —— 按 Markdown 标题、代码围栏、空行切分，长段落按 700 字符硬切并保留 15% 重叠。
- **标题链进正文** —— 每个 chunk 正文以 `【A > B > C】` 前缀携带完整标题链，既让嵌入带上章节语义，也让模型看得懂这块属于哪一章。
- **真层级** —— `parentSection` 是标题栈的上一层（不是自身），`headingPath` 记录 `A > B > C`。
- **父子摘要** —— 子块携带 `parentExcerpt`（父章节首段 ≤300 字符），命中叶子块时可回填父章节语境（small-to-big）。

## 检索

- **混合检索** —— 稠密向量 + BM25，RRF 融合（dense 0.7 / sparse 0.3）。
- **中文分词** —— PostgreSQL 的 `simple` 词典**不切中文**：无空格的中文整段会变成一个词位，查「参数绑定」甚至查「SQL」都不命中。因此写入与检索两侧都先经 `TextTokenizer`（中文 bigram + camelCase/snake_case 子词，缩写词整体保留）。存量数据会在启动时按迁移 `tsvector-tokenize-v1` 一次性重建。
- **查询结构化** —— 查询不是「diff 前 500 字符」，而是从变更中提炼的 `文件 / 类 / 方法 / 符号`；无标识符时退回原文截断。可选开 LLM 改写（失败自动降级，绝不劣化）。
- **阈值闸门** —— `similarity` 元数据是**真实余弦**，跨 Pg / InMemory 口径一致；RRF 排名分另存 `rrfScore`，只用于排序、不参与阈值判断（否则第一名恒为 1.0、第 50 名还有 0.55，闸门永远关不上）。
- **重排与去冗** —— Reranker 精排后按 MMR（相关性 − 冗余度）挑 Top-N，避免注入块被同章节碎片占满。

## 配置

```yaml
review:
  rag:
    retrieve-k: 50        # 混合检索预重排候选数（业界 Top-50~200 再精排）
    inject-top-n: 5       # 重排 + MMR 后最终注入条数
    max-age-days: 0       # freshness：0 = 不过滤，N = 只召回 N 天内入库的知识
    min-similarity: 0.3   # 选择性 abstain：低于该余弦值的候选不注入
    eval-enabled: true    # golden 指标（precision/recall/hit@k/MRR）
    rerank:
      enabled: true       # Cross-Encoder 重排，无 API Key 时自动降级启发式
    query-rewrite:
      enabled: false      # LLM 查询改写（默认关，失败自动降级恒等）
    mmr:
      enabled: true       # 重排后 MMR 多样性挑选
    parent-context:
      enabled: true       # 命中叶子块回填父章节上下文
pgvector:
  index-type: hnsw        # ANN 索引：hnsw（默认）| ivfflat（老版本兼容）
  vector-dim: 1024        # 必须与嵌入模型维度一致，否则启动会重建向量列（存量向量作废）
```

> ⚠️ `vector-dim` 必须与 `review.llm.embedding.dim` 保持一致。代码默认值、yml 默认值、实际模型三者若不一致，缺 yml 覆盖的启动会触发向量列重建。

## 改善什么

- 安全审查可以引用团队自己的安全规范
- 架构审查可以引用分层约定文档
- 相似的历史发现成为新审查的可检索上下文

## 数据存储

向量嵌入存 PostgreSQL + pgvector（默认 HNSW 索引）。若嵌入维度与既有列不匹配，向量库会**备份旧表后重建向量列**（存量向量作废），因此 dev / prod 必须保持同一维度口径。
