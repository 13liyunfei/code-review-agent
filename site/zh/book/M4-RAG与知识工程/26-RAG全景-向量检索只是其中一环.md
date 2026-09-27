# 第 26 讲 · RAG 全景：向量检索只是其中一环

> 🎯 导读问题：**"你的 RAG 为什么效果不好？"** ——这一讲给你一张"病灶定位图"，把笼统的"效果不好"拆成可定位、可测量的 7 个环节。

<img class="mermaid-svg" src="/zh/book-assets/diag-0113.svg" alt="🎯 导读问题：&quot;你的 RAG 为什么效果不好？&quot; ——这一讲给你一张&quot;病灶定位图&quot;，把笼统的&quot;效果不好&quot;拆成可定位、可测量的 7 个环节。" />

> **图 26-0**　本讲地图：RAG 不是一次向量检索，而是两条链路七个环节，写入与检索的质量相乘。第 ④ 查询构造最易漏，也最静默——无命中只打一行 INFO，审查照常进行。

## 一、痛点

我第一版 RAG 上线两周，收到的反馈是：

> "机器人提的意见，跟项目里写的规范对不上。"

我的第一反应是**知识库不够**。于是我做了最直观的事：**又灌了 300 篇文档进去。**

结果没变。

第二反应是**模型不行**。我换了一个更强的模型重跑。

结果还是没变。

第三反应是**向量库不行**。我甚至认真考虑过换一个向量数据库。

——**我连着换了三个东西，但没有一个是我应该换的。**

直到我把链路铺开、一处一处打日志，才发现真相：**规范根本没被检索出来。** 检索候选 50 条里，一条规范都没有。向量库没问题、模型没问题、文档数量没问题——**问题在"从 diff 提取查询"这一步**：我把 diff 前 500 字符直接丢给检索，里面塞满 `+`、`-`、行号、hunk 头。拿这堆噪声去撞以自然语言术语为主的规范库，语义鸿沟大得根本撞不上。

**"RAG 效果不好"从来不是一句话，它是一个有 7 个环节的漏斗。** 把漏斗的每一节都量出来，你才知道拧哪一颗螺丝。

## 二、原理

### 2.1 先把 RAG 拆成两条链路

很多人说"RAG"时，脑子里只有一件事：**向量检索**。这是最大的认知偏差。

一个生产级 RAG 是**两条独立的链路**，各自有各自的故障模式：


<img class="mermaid-svg" src="/zh/book-assets/diag-0114.svg" alt="一个生产级 RAG 是两条独立的链路，各自有各自的故障模式：" />

> **图 26-1**　RAG 的两条链路。**它们各自独立，但质量相乘**——写入侧把条款切碎了，检索侧再强也召不回完整语义；反过来也一样。所以"效果不好"必须先回答"是哪条链路、哪一环"。

**关键结论：两条链路的质量是"乘法"关系，不是"加法"。**

- 写入侧把规范切碎了（比如一个"禁止 SQL 拼接"的条款被切成两个半句），检索侧再强也召不回完整语义；
- 检索侧把噪声当查询，写入侧切得再好也白搭。

**把"乘法"翻译成衰减，你才知道有多狠。** 假设每个环节单独做到"不错"的通过率 90%，这已经是件不小的事：

> 写入链 3 环（切块·向量化·入库）：0.9 × 0.9 × 0.9 ≈ **0.729**
> 检索链 4 环（查询构造·召回·重排·注入）：0.9⁴ ≈ **0.656**
> 整条链路：0.729 × 0.656 ≈ **0.478**
>
> 每一环都做到"九成"，端到端命中率也只有 **48%**——近一半在漏斗里被乘掉了。
>
> 反推：要端到端 ≥90%，每一环至少要 0.9^(1/7) ≈ **98.5%**——**这对每一环都是"近乎不许错"的要求**。

这才是"加法 vs 乘法"的实操含义：**加法允许你慢慢补短板，乘法逼你把每一环都顶到 98%，任何一环松懈都会把另外六环的努力一起稀释。**

**工程判据：** **别问"RAG 效果怎么提升"，先问"哪一环在掉"——给每一环设可测量的通过率，并反推预算：若端到端命中率目标 ≥ 90%，每环必须 ≥ 98.5%（0.9^(1/7)）；任何一环实测低于它，就先去修那一环，而不是"再加 300 篇文档 / 换更强的模型"。** 加文档和换模型只改变 ②③ 两环，救不了 ④⑤⑥⑦ 里的任何一环。

**所以"效果不好"这个问题，必须先回答"是哪条链路、哪一环"。**

### 2.2 七个环节，各自的典型症状

| # | 环节 | 本项目实现 | 坏掉的典型症状 |
|---|---|---|---|
| ① | 切块 | `StructuredChunker` | 条款被腰斩、章节归属丢失、父子关系断 |
| ② | 向量化 | `EmbeddingClient`（`LangChain4j` / `SimpleHash`） | 维度不匹配、模型换了但向量没重算 |
| ③ | 入库与索引 | `PgVectorMemoryStore` / `PgKnowledgeStore` | 索引类型不对、`search_vector` 没按分词器重建 |
| ④ | **查询构造** | `DiffQueryExtractor` + 可选 `QueryRewriter` | **查询是噪声、截断位置不对、命中不到关键词** |
| ⑤ | 初检（召回） | `PgKnowledgeStore.hybridSearch` | 单路召回漏、融合权重失衡、**长查询零命中** |
| ⑥ | 精排与过滤 | `Reranker` + `RagEvaluator` + MMR | 阈值闸门空转、Top-N 被同章节碎片占满 |
| ⑦ | 注入格式 | `RagContextBuilder.appendKnowledgeBlocks` | 注入太短没有上下文、太长挤爆预算 |

**注意第 ④ 环**——它是最容易被忽略、也最容易致命的一环。因为它**不在"知识库"这三个字覆盖的范围内**：你检查向量库、检查文档、检查模型，都查不到它。

### 2.3 为什么这个问题难以被发现

因为**整条链路是"静默降级"的**：

- 检索没命中 → 返回空列表 → `buildContext` 返回空串 → **审查照常进行**；
- 没有异常、没有报错、报告正常输出；
- 唯一的表现是"结论质量下降"，而这**没有指标**。

`RagContextBuilder.buildContext`（`RagContextBuilder.java:200-202`）在无命中时只打一行 INFO：

```java
if (passed.isEmpty()) {
    log.info("[RAG] 无相关知识（候选 {} 条均低于阈值或为空），知识分区 abstain, 耗时 {}ms",
            candidates.size(), System.currentTimeMillis() - t0);
}
```

**"abstain"这个词很重要**——它是设计上允许的行为（没知识就不注入，比注入噪声好）。但当它**每天都发生**时，它就不再是"设计"，而是"故障"。而日志级别是 INFO，没人看。

**这就是 RAG 最大的陷阱：它的失败是一等公民（合法状态），所以从不报警。**

**为什么是 RAG：三种"给模型知识"的路线一张表看清。** 有了 7 个环节的地图，你才能回答"我到底要不要 RAG"，还是该走另外两条路：

| 维度 | RAG（检索注入） | 长上下文（全量塞） | 微调 / 重训练 |
|---|---|---|---|
| **核心思想** | 检索"只喂相关的那几条" | 把文档全塞进上下文让模型自己找 | 把知识写进模型权重 |
| **知识更新** | 加/改文档即可，秒级生效 | 改文档即变（随提示传入） | 要重训/微调，慢 |
| **本轮治理成本** | 中（7 环漏斗 + 阈值） | 低（不用配检索） | 高（数据工程 + 训练） |
| **每次调用成本** | 低（只注入 Top-K token） | 高（全文 token 全计费，显存陡增） | 幻觉少但 token 不变省 |
| **延迟** | 增一次检索调用 | 最长（长窗口推理慢） | 推理正常 |
| **可解释 / 可溯源** | 强（能指到 `source`） | 弱（模型读进权重说不清） | 最弱（常"看着像但说不出出处"） |
| **知识内容是否精确** | 能保 1:1 命中原文 | 有（但也可能被长窗口稀释） | 会"变形/泛化" |
| **适用** | 规范/手册/事实查询，要求可溯源 | 单次决策、文档短、检索不值当 | 固定风格 / 任务，不追求可溯源 |

> **本项目把 RAG 当默认，是因为审查要"依据哪条规范提的"必须可溯源（第 3.3 节 `source` 元数据）**——这恰恰是长上下文和微调最弱的一环。**选型时先问一句：这条知识要不要能指到出处？要，就 RAG。**

### 2.4 千万 DAU 的容量账：先算 QPS 与延迟预算

前面三节回答的都是"哪里错了"——那是**正确性**视角。生产环境还有第二个视角：**容量**。正确性对、容量不对，照样是事故，而且是最难查的那一种：平时好好的，一到峰值就崩。

RAG 从原论文起就是"检索 + 生成"两段串联（*Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks*，https://arxiv.org/abs/2005.11401 ）。**串联的延迟是相加的，质量是相乘的。** 上一节讲了"相乘"，这一节把"相加"的账算出来。按 RAG 综述（*Retrieval-Augmented Generation for Large Language Models: A Survey*，https://arxiv.org/abs/2312.10997 ）的三阶段划分，2.1–2.3 讲的是 Naive 阶段最容易踩的坑，2.4–2.6 开始进入 Advanced 之后才被认真对待的工程议题。

先算 QPS。两个基座差三个数量级：

> **推演**：`10,000 次 ÷ 86,400 秒 ≈ 0.12 QPS`（假设：24 小时均匀分布；基座值"每天检索 1 万次"见本讲 3.2）

> **推演**：`0.12 QPS × 10 ≈ 1.2 QPS`（假设：峰时系数 10，即峰值时段请求量是均值的 10 倍；基座值 0.12 QPS 见上一行推演）

> **推演**：`10,000,000 DAU × 2 次 ÷ 86,400 秒 ≈ 231 QPS`（假设：人均 2 次咨询、24 小时均匀分布；基座 B 为千万 DAU 参照系，本项无仓库出处）

> **推演**：`231 QPS × 10 ≈ 2,310 QPS`（假设：峰时系数同为 10；基座值 231 QPS 见上一行推演）

> **推演**：`2,310 QPS ÷ 1.2 QPS ≈ 1,925 倍`（假设：两基座峰时系数同为 10；基座值见上方四行推演）

**约 1,900 倍。** 这就是为什么 2.2 那张"七个环节"表在 A 基座可以直接用、到 B 基座必须先重算——同一个 `retrieve-k=50`（`application.yml:221`），在 1.2 QPS 下是"够宽的候选窗"，在 2,310 QPS 下是"每次请求都要付的固定成本"。

再看延迟。七个环节**不是都花在在线路径上**：

<img class="mermaid-svg" src="/zh/book-assets/diag-0115.svg" alt="再看延迟。七个环节不是都花在在线路径上：" />

> **图 26-2**　把两条链路的七个环节拉成一条延迟预算条：写入链路（①②③）走离线，不占在线预算；在线预算只由 ④⑤⑥⑦ 四段消耗，其中 ⑥ 重排是同步阻塞段——它的 `timeout` 是"上限"，不是"预算基线"。

各环节的耗时构成：

| 环节 | 触发时机 | 计入在线预算 | 成本形态 |
|---|---|---|---|
| ① 切块 | 灌库时 | 否 | 一次性 CPU |
| ② 向量化 | 灌库时 | 否 | 一次性嵌入调用（按 token 计费） |
| ③ 入库 + 索引 | 灌库 / 重建时 | 否（但建索引会与在线写入争资源） | 建索引期内存与并行度（`pgvector/README.md:291`）；生产要用 `CREATE INDEX CONCURRENTLY`（`pgvector/README.md:704`） |
| ④ 查询构造 | 每请求 | 是 | 一次改写调用（本项目默认关闭，`application.yml:230-231`） |
| ⑤ 混合召回 | 每请求 | 是 | 一次 SQL（向量 + 全文 + RRF 融合） |
| ⑥ 精排 + 过滤 | 每请求 | 是 | 一次外部重排调用（`application.yml:206-207`） |
| ⑦ 注入 | 每请求 | 是 | token 数（`inject-top-n=5`，`application.yml:223`；注入位置本身也影响效果，见 *Lost in the Middle*，https://arxiv.org/abs/2307.03172 ） |

**在线预算按 P99 定，不按均值定。** 本讲 3.2 那行日志的 87ms 是一次观测，落在均值附近；均值会让 ⑥ 看起来只花几十毫秒，而只有 P99 才会暴露它是一个同步阻塞段。另外要防两个容易踩空的地方：

**一是把 `timeout` 当预算用。** `application.yml:206-207` 给 Cohere `rerank-english-v3.0` 配了 5000ms timeout。那是"超时上限"，不是"分配给它的 5 秒"。真正的预算是从端到端 P99 目标里**倒扣**出来的余量——先把 ④⑤⑦ 的量级摸出来，剩下的才轮到 ⑥。

**二是忘了融合本身也有候选窗成本。** RRF 的两侧各自都要取候选：`langchain4j/langchain4j-pgvector/src/main/java/dev/langchain4j/store/embedding/pgvector/PgVectorEmbeddingStore.java:588` 的 `Math.max(maxResults, rrfK)`、`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/rag/content/aggregator/ReciprocalRankFuser.java:29-31` 的默认 `k = 60`，都说明"候选窗"不等于"最终条数"。本项目的 `retrieve-k=50` 还会被再放大一倍、且有 20 的下限：`PgKnowledgeStore.java:182` 的 `widen = max(topK*2, 20)`。

**所以容量账的正确顺序是：先定端到端 P99 预算 → 再给每环分 P99 目标 → 最后才调参数。** 反过来（先调 `retrieve-k`、再想延迟）等于把 P99 交给运气。

### 2.5 乘法衰减在规模下会被放大

2.1 已经算过均值口径：每环 0.9，端到端 `0.9^7 ≈ 0.478`，反推每环要 98.5%。那个结论是"每环都别松懈"。规模之下还有第二个结论：**均值达标 ≠ 尾部达标。**

道理很直接：0.478 是一个**均值**，而生产验收看的是 P99。在 P99 那一档，每一环的状态都比均值更差——缓存没命中、租户过滤更苛刻、重排更容易触到 timeout。把"尾部每环退化到 0.6"代进去：

> **推演**：`0.478 ÷ 0.028 ≈ 17 倍`（假设：尾部每环通过率退化到 0.6，则 `0.6^7 ≈ 0.028`；基座值 0.478 见本讲 2.1 的 `0.9^7`）

**同一套系统，均值口径 47.8%、尾部口径 2.8%，相差约 17 倍。** 规模会把这个比值翻译成绝对量：

> **推演**：`10,000,000 DAU × 2 次 × 1% ≈ 20 万次 / 天`（假设：尾部占 1%（P99 口径）、人均 2 次咨询；基座值 2 次/人 见 2.4 的 DAU 推演）

那 20 万次不是"1% 的数字游戏"，是每天有 20 万个用户拿到一个没有依据的回答。为什么尾部会比均值差这么多？三个机制：

1. **重排是同步阻塞段，且默认不截断。** `langchain4j/langchain4j-core/src/main/java/dev/langchain4j/rag/content/aggregator/ReRankingContentAggregator.java:171` 的 `scoreAll` 一次性把候选全送进 cross-encoder，同文件 `:89` 的 `maxResults = Integer.MAX_VALUE` 意味着默认不设上限——候选有多少，就压多少在一个同步调用上。A 基座单次 `timeout 5000ms`（`application.yml:206-207`）可以接受；B 基座在峰值 QPS 下，超时不再是特例，而是分布的一部分。
2. **多租户过滤发生在索引扫描之后。** pgvector 给出的量化后果是：过滤选择性 10%、`ef_search` 取默认 40 时，平均只剩 4 条（`pgvector/README.md:450`；默认值见 `pgvector/src/hnsw.h:60`）。租户越多，选择性越差。
3. **共享索引会跨租户互相干扰。** pgvector 官方 README 直接承认这点，并建议分区或分表（`pgvector/README.md:470-472`）。A 基座团队数少，干扰可以忽略；B 基座商家数上万，它就成了尾部的常客。

机制 2 的量化后果可以直接算出来：

> **推演**：`40 × 10% ≈ 4` 条（假设：多租户过滤的选择性为 10%；基座值 `ef_search = 40` 见 `pgvector/src/hnsw.h:60`，量化口径见 `pgvector/README.md:450`）

<img class="mermaid-svg" src="/zh/book-assets/diag-0116.svg" alt="推演：`40 × 10% ≈ 4` 条（假设：多租户过滤的选择性为 10%；基座值 `ef_search = 40` 见 `pgvector/src/hnsw.h:60`，量化口径见 `pgvector/README.md:450`）" />

> **图 26-3**　乘法衰减在规模下会被放大：均值口径每环 0.9 得到端到端 0.478，尾部口径每环 0.6 只剩 0.028，相差约 17 倍；在 B 基座，那 1% 的尾部是每天约 20 万次请求。

**工程判据：验收指标必须落在 P99 上。** 把"端到端命中率 ≥ X%"写成均值指标，等于默许尾部长期劣化——因为均值的每一次"看起来还行"，都可能是尾部在拖着。

### 2.6 一手数据与搬到你自己系统：容量先行

先把本项目可机械复算的数摆出来：

| 项 | 值 | 出处 |
|---|---|---|
| 候选窗 `retrieve-k` | 50 | `application.yml:221` |
| 注入条数 `inject-top-n` | 5 | `application.yml:223` |
| 相似度闸门 `min-similarity` | 0.3 | `application.yml:234` |
| 查询改写默认 | 关闭（`enabled: false`） | `application.yml:230-231` |
| 重排 | 开启，Cohere `rerank-english-v3.0`，timeout 5000ms | `application.yml:206-207` |
| 扩窗 | `widen = max(topK*2, 20)` | `PgKnowledgeStore.java:182` |
| 生产嵌入维度 | 1024 | `application.yml:197` |

把这张表和框架默认值放在一起，候选数差了一个数量级：

```java
// langchain4j/langchain4j-core/src/main/java/dev/langchain4j/rag/content/retriever/EmbeddingStoreContentRetriever.java:70-71
    public static final Function<Query, Integer> DEFAULT_MAX_RESULTS = (query) -> 3;
    public static final Function<Query, Double> DEFAULT_MIN_SCORE = (query) -> 0.0;
```

```java
// spring-ai/spring-ai-vector-store/src/main/java/org/springframework/ai/vectorstore/SearchRequest.java:44,49
	public static final double SIMILARITY_THRESHOLD_ACCEPT_ALL = 0.0;
	public static final int DEFAULT_TOP_K = 4;
```

**候选窗 50 vs 3 / 4。** 这不是谁对谁错，而是默认目标不同：框架给的是"能跑起来"，本项目给的是"在生产上够用"。但两个默认值要记住——`DEFAULT_MIN_SCORE = 0.0` 与常量名 `SIMILARITY_THRESHOLD_ACCEPT_ALL`：**默认不设闸门**（第 31 讲会回到这点）。

更强的证据是：**框架根本不碰决定 P99 的参数。**

```java
// spring-ai/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java:512-525（节选）
		if (this.createIndexMethod != PgIndexType.NONE) {
			this.jdbcTemplate.execute(String.format("""
					CREATE INDEX IF NOT EXISTS %s ON %s USING %s (embedding %s)
					""", this.getVectorIndexName(), this.getFullyQualifiedTableName(), this.createIndexMethod,
					this.getDistanceType().index));
		}
```

这条 `CREATE INDEX` **不带任何 `WITH (...)` 子句**——不设 `m` / `ef_construction` / `lists`；`ef_search`、`iterative_scan` 在这个模块里一次都没出现。**框架只负责把索引建出来，不负责让检索变好。** 这句话就是"容量先行"的立论依据：框架的默认值是分工边界，不是生产参数。

多租户过滤更是一条接口级判据——spring-ai 把过滤器做成**每请求懒求值**，注释里点明的动机就是租户：

```java
// spring-ai/spring-ai-rag/src/main/java/org/springframework/ai/rag/retrieval/search/VectorStoreDocumentRetriever.java:67-70
	// Supplier to allow for lazy evaluation of the filter expression,
	// which may depend on the execution content. For example, you may want to
	// filter dynamically based on the current user's identity or tenant ID.
	private final Supplier<Filter.Expression> filterExpression;
```

**多租户过滤必须是 Supplier（每请求求值），不能是构造期常量**——A 基座按 `team` 隔离、B 基座按商家隔离，两条路都逃不掉。

最后是注入成本在规模下的样子（复用本讲 3.2 的口径）：

> **推演**：`¥11/天 × (2,000 万 ÷ 1 万) = ¥22,000/天 ≈ ¥2.2 万/天`（假设：单次注入 token 数与 A 基座一致（3 块 × 350 token）；基座值"每天 1 万次 ≈ ¥11/天"见本讲 3.2）

**约 ¥66 万/月**（`¥22,000 × 30`）。这就是"逐层压缩注入预算"在 B 基座不是优化项、而是必选项的原因——也解释了为什么缓存层值得单独设计。

A/B 双基座的容量对照轴：

<img class="mermaid-svg" src="/zh/book-assets/diag-0117.svg" alt="A/B 双基座的容量对照轴：" />

> **图 26-4**　A/B 双基座的容量对照轴：A 基座峰值约 1.2 QPS，共享索引够用；B 基座峰值约 2,310 QPS，同一张共享索引会因跨租户过滤被打折（`pgvector/README.md:470-472`）——分叉点落在租户隔离、知识更新与注入压缩三处（QPS 为推演值，见 2.4）。

| 维度 | A：代码审查助手 | B：千万 DAU 客服助手 | 由此分叉的设计决策 |
|---|---|---|---|
| 峰值 QPS | 约 1.2（推演，见 2.4） | 约 2,310（推演，见 2.4） | 连接池、只读副本、分片从"可选"变成"必须"（`pgvector/README.md:760`） |
| 候选窗 | `retrieve-k=50` 够用 | 同一数值被过滤打折 | 先定 `ef_search` 与分区策略，再定候选窗 |
| 租户隔离 | 团队级，`Teams.sanitize` 字符串过滤 | 商家级，共享索引互相干扰 | 走向分区或分表（`pgvector/README.md:470-472`） |
| 知识更新 | 团队规范，低频变更 | UGC 商品 / 政策文档，随时变 | 版本化切块 + 重切触发条件 |
| 重排预算 | 单次 `timeout 5000ms` 可接受 | 同步阻塞段的 P99 不可接受 | 降级链 + 结果缓存 |
| 注入条数 | `inject-top-n=5` | 与 QPS 相乘后成为成本大头 | 逐层压缩注入预算 |
| 缓存 | 无 | 命中一次省一整条链路 | 嵌入 / 语义 / 结果三层 |
| 可观测性 | `RagTrace`（第 33 讲） | 必须按租户与 QPS 分桶 | 指标带租户维度 |

**搬到你自己系统**——照这张表问：

| 工业界 / 本项目做法 | 你自己系统里该问的问题 |
|---|---|
| A 基座按"每天 1 万次"算账（本讲 3.2） | 我的日请求量、峰值系数、人均请求数各是多少？ |
| 重排 `timeout 5000ms` 是上限（`application.yml:206-207`） | 我的端到端 P99 预算里留给重排多少？超时后是阻塞还是降级？ |
| 框架默认候选 3 / 4（`langchain4j/.../EmbeddingStoreContentRetriever.java:70`、`spring-ai/.../SearchRequest.java:49`） | 我用的候选窗是照抄默认，还是自己算出来的？ |
| pgvector 默认 `ef_search=40`（`pgvector/src/hnsw.h:60`） | 我的候选窗和 `ef_search` 谁大？过滤选择性是多少、过滤后还剩几条？ |
| 共享索引跨租户干扰（`pgvector/README.md:470-472`） | 我的多租户是共享索引，还是已经分区 / 分表？ |
| 量化必须配重排（`pgvector/README.md:734`） | 我要省内存时，重排这一环还在预算里吗？ |

落地五步：

1. 把 **日请求量 / 峰值系数 / 人均请求数** 三个数写进文档或配置，算出平均 QPS 与峰值 QPS。
2. 按 2.4 的表给 ④⑤⑥⑦ 每环写一个 **P99** 耗时目标（不是均值），加总成端到端 P99 预算。
3. 用候选数交叉检查：`ef_search × 过滤选择性` 是不是把候选打到个位数；`widen = max(topK*2, 20)` 之后实际扫了多少。
4. 给漏斗日志加两个字段：请求耗时的分位数、过滤比例（放行数 ÷ 候选数）。
5. 按 `ann-benchmarks.com`（https://ann-benchmarks.com/ ）的"召回—延迟"权衡口径，在你的数据集上选一次索引参数，而不是照抄默认值。

**判据**：**容量账必须在压测前算完** —— 先定峰值 QPS 与端到端 P99 预算，再回头调 `retrieve-k`、`ef_search`、`timeout`；顺序反了，P99 就只能靠运气。

## 三、代码

### 3.1 检索链路：7 步流水线

`RagContextBuilder.buildContext`（`RagContextBuilder.java:181-235`）把检索链路写成了一条**可读的流水线**，每一步都对应 2.2 表里的一个环节：

```java
// core/memory/RagContextBuilder.java:181-235（节选，保留结构）
public String buildContext(String teamId, String agentType, List<CodeDiff> diffs) {
    // 1. 从代码提取查询意图（diff 形态）
    String rawQuery = extractQueryFromDiffs(diffs);              // → DiffQueryExtractor.extract
    // 1b. 查询改写：diff 形态 → 规范术语形态（可选；失败降级恒等，绝不劣化）
    String query = queryRewriter.rewrite(rawQuery);              // → Identity / LlmQueryRewriter
    // 2. 混合检索（向量 + BM25 + RRF），含全局基线 + freshness 过滤
    List<MemoryEntry> candidates = knowledgeStore.searchKnowledge(query, retrieveK,
            Teams.sanitize(teamId), true, maxAge);               // ← ⑤ 初检，候选窗 50
    // 3. 阈值过滤（低于 minSimilarity 的块剔除；全低于则 abstain）
    List<MemoryEntry> passed = evaluator.filterByThreshold(candidates);  // ← ⑥ 第一道闸门
    ...
    // 4. Cross-Encoder 重排
    List<MemoryEntry> reranked = reranker.rerank(query, passed, rerankPool);   // ← ⑥ 精排
    // 5. MMR 多样性挑选
    List<MemoryEntry> picked = (mmrEnabled && reranked.size() > injectTopN)
            ? mmrSelect(reranked, query, injectTopN) : reranked;
    // 6. 注入前去重：按 content hash 去重叠切块产生的重复
    List<MemoryEntry> deduped = dedupeByContent(picked);
    // 7. 评估指标 + 格式化（含 small-to-big 父章节上下文回填）
    RagEvaluator.RagMetrics metrics = evaluator.evaluate(deduped, null);
    appendKnowledgeBlocks(sb, deduped);
    // 8. 历史经验回流（独立于知识 abstain）
    appendExperience(sb, teamId, agentType, rawQuery);
    return sb.toString().trim();
}
```

**这一讲的第一个交付物，就是把上面这段代码当"漏斗图"来用。** 每一步的输入输出数量都能打出来，于是"效果不好"变成了"在第几步掉光了"。

### 3.2 一条真实日志：漏斗长什么样

这是本项目正常工作时的一行日志（`RagContextBuilder.java:221-225` 打印）：

```
[RAG] 上下文构建：team=team-pay, agent=SECURITY, 查询=412字符,
      候选 50 → 放行 18 → 注入 Top-3 (maxSim=0.6123), 耗时 87ms
```

把它读成一个漏斗：

```
查询 412 字符
  ↓ ⑤ 混合召回（向量 + BM25，RRF 融合，候选窗 50）
候选 50 条          ← 如果这里就是 0，问题在 ④/⑤/写入侧，跟重排无关
  ↓ ⑥-a 阈值过滤（similarity ≥ 0.3）
放行 18 条          ← 如果这里是 0，闸门配错了，或者 similarity 语义坏了（见第 31 讲）
  ↓ ⑥-b 精排 + MMR（λ=0.7）+ 去重
注入 Top-3
```

**四个数字，各自指向不同的环节。** 这就是"病灶定位图"。

| 观测 | 病灶 |
|---|---|
| 候选 = 0 | 写入侧没数据 / 团队隔离错了 / 稀疏路查询语法错误（第 30 讲）/ 查询是噪声 |
| 候选有，放行 = 0 | 阈值 > 实际相似度分布；或 `similarity` 写错（第 31 讲） |
| 放行有，注入质量差 | 精排无效（第 32 讲）或 Top-N 被同源碎片占满（MMR 未生效） |
| 注入有，但模型不引用 | 注入格式问题：没有章节归属 / 没标来源 → `【headingPath】` 与 `[source]` 缺失 |

**注入是一次实打实的 token 账单。** 用上面那行真实日志的数字往后推：候选 50 → 放行 18 → 注入 Top-3。假设每块正文约 **300 token**、每条再带约 **50 token** 的格式头（`【headingPath】` + `[source]`），平均每块注入 ≈ **350 token**：

> 一次查询注入：3 块 × 350 = **1050 token**
> 输入价约 ¥1/百万 token（示意口径）：1050 × ¥1/1M ≈ **¥0.0011 / 次**
> 一台每天检索 1 万次的引擎：**约 ¥11 / 天**，≈ **¥240 / 月**
>
> 若只注入 1 块：**¥0.00036 / 次**，省近三分之二——**这就是第 23 讲"准入 + 投放"那句"只放行最相关的 Top-K"从哪来的账。**

**一个易混点别踩**：RAG 注入省的是**注入侧**的 token——召回的候选窗（50 条）**不进上下文**，只有被精排选中的 Top-K 进。真正烧钱的是"把候选窗塞给模型去人工重排"那种写法，那才是一次性把 50×350 token 全计进来。

### 3.3 写入链路：只有 30 行，但决定了另一半

写入侧在本项目里非常短——因为复杂度全被 `StructuredChunker` 吸收了：

```java
// core/memory/KnowledgeBaseInitializer.java:53-65
private void doInit() {
    String handbook = loadFile("handbook/java-coding-standard.md");
    if (handbook == null) {
        log.warn("[KB] 未找到编码规范手册，跳过预灌入");
        return;
    }
    // 结构感知切块 + 富元数据（层级 section + 重叠），由 KnowledgeStore 完成
    Map<String, String> meta = new LinkedHashMap<>();
    meta.put("source", "handbook");
    meta.put("type", "coding_standard");
    int chunkCount = knowledgeStore.saveKnowledge(Teams.GLOBAL, handbook, meta);
    log.info("[KB] 编码规范已结构化切分为 {} 段并入库", chunkCount);
}
```

三个细节值得停下来看一眼：

**① `meta.put("source", "handbook")` —— 这是注入侧唯一能显示给人的溯源信息。** 最终注入的每一行都是 `- [handbook] 【数据库访问 > ...】正文`。如果 `source` 没写，你就得到 `- [knowledge] ...`——**审查意见里看不到"这是依据哪份文档提的"**，可信度直接掉一档。

**② `Teams.GLOBAL` —— 内置规范走全局基线，而不是某个团队。** 这是第 24 讲三层租户模型的落地：规范手册是团队的**公共地表**，团队私有知识叠在其上。

**③ 整个 `init()` 被 `try/catch` 包住（`:44-51`），失败只告警不阻断启动。**

```java
// core/memory/KnowledgeBaseInitializer.java:44-51
@PostConstruct
public void init() {
    try {
        doInit();
    } catch (Exception e) {
        log.error("[KB] 知识库预灌入失败，已跳过（应用继续启动）：{}", e.getMessage(), e);
    }
}
```

这个设计**对不对？** 一半对。合理性在于：知识库是"增强项"，不该因为向量服务暂时不可达而拖死整个应用启动。**危险在于：它让"知识库是空的"变成一个可以长期存在的合法状态。** 服务起来了、健康检查过了、审查跑了——**但一条知识都没有。**

**这就是本讲要建立的判断力：一个"允许失败的增强项"，必须配一个"能看见它失败了"的信号。** 否则你的 RAG 从第一天起就是坏的，而没有任何人知道。

### 3.4 接口分层：为什么知识库不是"记忆库"

这一点在第 14 讲提过，这里给出**契约层的证据**。`KnowledgeStore.java:10-29` 的类注释明确解释了这次拆分的动因：

```java
// core/rag/KnowledgeStore.java:12-16
 * <p>设计动机（业界最佳实践 + 本项目演进）：原 {@code MemoryStore} 同时承载
 * RAG 知识（{@code agent_type=RAG}）与开发者经验反馈（{@code agent_type=EXPERIENCE}），
 * 共享同一张 {@code memory_store} 表与同一检索通道，仅靠元数据区分，边界模糊易导致
 * 互相干扰（如经验噪声污染知识检索、知识规模膨胀拖累经验召回）。
```

注意后半句 **"物理共享同一张表、逻辑上是两个接口"**（`:20-24`）：

```java
 *   <li>生产实现（{@code PgKnowledgeStore}）与记忆实现（{@code PgVectorMemoryStore}）
 *       <b>物理共享同一张 {@code memory_store} 表</b>（按 {@code agent_type} 区分读写视角），
 *       但<b>逻辑上互为独立接口</b>，各自只暴露自己该暴露的能力，符合接口隔离原则；</li>
```

**这是"物理共享、逻辑隔离"的一个正面样本**——不为了"干净"而多起一张表（多一张表就多一套迁移、索引、备份），但把**类型暴露面**收窄，避免 Spring 按类型注入时二选一（这个歧义在第 19 讲踩过）。

**代价必须说清楚**：两套实现共享一张表，就有了第 28 讲那个"维度漂移"事故的土壤——**改一处维度配置，会同时影响知识库和记忆库。**

## 四、避坑清单

- [ ] **RAG 要打"漏斗日志"，4 个数字一个不能少**：候选数、放行数、重排输出数、最终注入数。缺任何一个，"效果不好"就无法定位。
- [ ] **区分"链路断"和"链路弱"。** 候选 = 0 是断（第 ④⑤ 环或写入侧）；候选有但注入差是弱（第 ⑥⑦ 环）。两者的修法完全相反。
- [ ] **查询构造是独立环节，必须能被单独检查。** 把最终查询字符串打进日志（哪怕只打前 200 字符）——否则你永远不知道检索在拿什么去找。
- [ ] **`abstain` 是合法状态，但"长期 abstain"是故障。** 给 abstain 加计数指标，配一个"连续 N 次 abstain 告警"。
- [ ] **`@PostConstruct` 里的增强步骤，失败要留可观测的痕迹。** 只 `log.error` 不够——要有健康检查/状态查询接口能回答"知识库现在有多少条"。
- [ ] **写入侧的 `source` / `type` 元数据必须写全。** 它们决定了注入内容和溯源展示，事后补不了（向量已入库）。
- [ ] **别用"加文档"解决"检索不到"。** 检索不到是**召回**问题，加文档只会让召回空间更大、更稀释。
- [ ] **别用"换模型"解决"知识没进来"。** 先确认上下文里到底有没有那条知识，再谈模型强弱。

- [ ] **容量账要按 P99 算，不能按均值算。** 均值口径 0.478 的端到端命中率，在千万 DAU 下 1% 的尾部就是每天约 20 万次请求；均值达标不等于验收通过。
- [ ] **`timeout` 是上限，不是预算。** 把重排的 `timeout 5000ms`（`application.yml:206-207`）当延迟预算用，等于默认"最坏情况可接受"——先给每一环定 P99 目标，再回头看这个 timeout 够不够。
- [ ] **框架默认值不能当生产默认值。** langchain4j 默认候选 3（`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/rag/content/retriever/EmbeddingStoreContentRetriever.java:70`）、spring-ai 默认 topK 4（`spring-ai/spring-ai-vector-store/src/main/java/org/springframework/ai/vectorstore/SearchRequest.java:49`）、pgvector 默认 `ef_search=40`（`pgvector/src/hnsw.h:60`）——这些是"能跑起来"的值，不是"能扛住峰值"的值。

## 五、动手任务

> **任务**：把 RAG 的漏斗打出来，然后**人为制造一次"环节 ④ 故障"**，看漏斗如何响应。
>
> **仓库位置**：`code-review-agent`（`core/memory/RagContextBuilder.java`、`core/rag/DiffQueryExtractor.java`）
>
> **操作**：
> 1. 启动服务，提交一个含 SQL 字符串拼接的 PR，观察日志里的：
>    `[RAG] 上下文构建：... 候选 N → 放行 M → 注入 Top-K (maxSim=...)`。
> 2. 记录这 4 个数字。
> 3. **改造实验**：临时把查询构造退化为"diff 前 500 字符截断"（即 `DiffQueryExtractor.extract` 直接返回 `patch` 前 500 字符），重跑同一个 PR。
> 4. 再记录这 4 个数字。
> 5. **容量估算**：用你现在的日请求量、峰值系数、人均请求数算一遍平均 QPS 与峰值 QPS；再按 2.4 的表给 ④⑤⑥⑦ 每环写一个 P99 目标，加总成端到端预算，把 `timeout` 与候选窗代进去，看是否超预算。
>
> **预期结果**：
> - 正常版本：候选数明显大于 0，能注入到规范相关的块（`source=handbook`）；
> - 退化版本：**候选数下降或候选内容与 diff 无关**——这正是我第一版上线时的状态。
>
> **关键对照**：写一份"环节 → 4 个数字"的对照表（就是本文 3.2 的表），放进你的项目 README。**下一次有人问"RAG 效果不好怎么办"，你就有话可说了。**

---

## 本讲小结

1. **RAG 是两条链路、七个环节**，不是"一个向量检索"。写入链路（切块→向量化→入库）与检索链路（查询构造→召回→精排→注入）的质量是**乘法**关系。
2. **最先被忽略的是"查询构造"**——它不属于"知识库"三个字，所以查不到。本项目的解法是 `DiffQueryExtractor` 从 diff 里提炼**结构化查询**（文件/类/方法/符号），而不是裸 patch 截断。
3. **RAG 的失败是静默的**：没知识就 abstain，审查照常进行，没有异常、没有指标。这是它最难被发现的原因。
4. **把漏斗的 4 个数字打出来**，就是本讲最实用的交付物：候选 → 放行 → 重排 → 注入，每一个数字指向不同的环节。
5. **"允许失败的增强项"必须配"能看见它失败的信号"**，否则它从一开始就是坏的。

下一讲我们从漏斗的**最上游**开始修：**切块策略**——为什么"按 300 字符切"会毁掉你的知识库，以及"真层级"和"假层级"的区别。
