# 第 31 讲 · similarity 语义铁律：为什么我的 0.3 阈值等于没配

> 🎯 导读问题：**"你怎么防止不相关的知识被塞进上下文？"** ——这一讲教你怎么让"闸门"真的关上。

<img class="mermaid-svg" src="/zh/book-assets/diag-0134.svg" alt="🎯 导读问题：&quot;你怎么防止不相关的知识被塞进上下文？&quot; ——这一讲教你怎么让&quot;闸门&quot;真的关上。" />

> **图 31-0**　本讲地图：融合后每个文档有两个数：RRF 排名分与真实余弦。把排名分写进 similarity，闸门永不关闭——跌破 0.3 需 rank 大于 140，候选只有 50 条，两者各归其位。

## 一、痛点

我给 RAG 配了一个参数：

```yaml
review:
  rag:
    min-similarity: 0.3
```

意思很直白：**余弦相似度低于 0.3 的知识块，别塞给模型。**

理由也充分——不相关的知识进上下文，比没知识更糟：它会误导模型，让审查结论跑偏。

上线后我去看日志：

```
[RAG-Eval] 候选 50 条，放行 50 条，拦截 0 条
```

**50 条全部放行。**

我第一反应是"知识库质量好，全都相关"。但连续几天，无论输入什么 diff，都是"放行 50、拦截 0"。

一个正常的检索系统不可能每次都全过。**唯一合理的解释是：这道闸门根本没关上。**

## 二、原理

### 2.1 混合检索融合后，会得到两个完全不同的"分"

第 29 讲讲过，混合检索用 RRF 把两条路融合起来。融合之后，每个文档身上其实有**两个数**：

| 名称 | 定义 | 量纲 |
|---|---|---|
| **RRF 融合分** | `Σ weight / (k + rank)` | **排名分**——只跟"排第几"有关 |
| **真实余弦** | `1 - (embedding <=> query)` | **相似度**——跟向量夹角有关 |

它们是两种**完全不同性质**的数。而历史上，Pg 后端把前者写进了 `similarity` 字段——于是阈值判断就变成了拿"排名分"去比"余弦阈值"。

### 2.2 归一化排名分的"形状"：几乎恒大于 0.3

先看 RRF 的融合方式：

```java
// core/rag/PgKnowledgeStore.java:274-281
// RRF 融合
Map<Long, Double> rrf = new HashMap<>();
for (var en : denseRank.entrySet()) {
    rrf.merge(en.getKey(), denseWeight / (RRF_K + en.getValue()), Double::sum);
}
for (var en : sparseRank.entrySet()) {
    rrf.merge(en.getKey(), (1 - denseWeight) / (RRF_K + en.getValue()), Double::sum);
}
```

其中 `RRF_K = 60.0`（`PgKnowledgeStore.java:60`），`denseWeight` 的默认值是 **0.7**——它由便捷构造器委托给完整构造器时给定：

```java
// core/rag/PgKnowledgeStore.java:77
this(embeddingClient, writer, host, port, database, username, password, vectorDim, 0.7);
```

对"只被稠密路命中"的文档，`rrf = 0.7 / (60 + rank)`。再按最大值归一化：

```java
// core/rag/PgKnowledgeStore.java:284
double maxRrf = fused.isEmpty() ? 1.0 : fused.get(0).getValue();
```

```java
// core/rag/PgKnowledgeStore.java:293
double norm = maxRrf > 0 ? fused.get(i).getValue() / maxRrf : 0.0;
```

把两式相除，归一化排名分其实是一个**极简的函数**：

```
norm(rank) = (0.7 / (60 + rank)) / (0.7 / 60) = 60 / (60 + rank)
```

代几个数进去，你会立刻明白问题出在哪：

| rank | norm = 60/(60+rank) |
|---|---|
| 0 | **1.000** |
| 12 |
| 34 |
| **50** | **0.545** |
| 100 | 0.375 |
| **140** | **0.300** |
| > 140 | < 0.3 |

**要让归一化排名分跌破 0.3，需要 `rank > 140`。**

而我把 `retrieve-k` 设的是 50——**候选集里一共只有 50 条，rank 最大就是 49**。

所以：

> 阈值 0.3 配上候选集 50，等价于**闸门永远打开**。

### 2.3 更糟的是：两个后端"声称一致，实则不一致"

`similarity` 这个字段，在项目的两个存储后端里行为不同：

| 后端 | `similarity` 写的是什么 |
|---|---|
| `InMemoryKnowledgeStore` | **真实余弦**（正确） |
| `PgKnowledgeStore`（修复前） | **RRF 归一化排名分**（错误） |

这意味着：**你用内存后端跑测试，阈值工作正常；一上 PG 生产，阈值就失效了。**

这类 bug 最难查——因为"测试全绿"，你会一直以为问题在数据或 prompt 上。

### 2.4 为什么这个 bug 能活这么久

三个原因叠加：

**第一，它不报错。** 只是"召回的东西质量一般"，这本来就在 RAG 的正常波动范围内。

**第二，我后来把 `retrieve-k` 从 10 提到了 50** —— 本意是"扩大候选窗口、提高召回"，结果**把闸门焊得更死了**（候选越多，最后一名越安全地待在 0.545 附近）。

**第三，我配了 `min-similarity` 就以为"防污染"这件事已经做完了。** 配置项存在 ≠ 配置项生效。这是所有"看起来配了"的坑的通用形态。

**把 0.3 翻译回几何，你才有"闸门到底多松"的直觉。** 余弦阈值 θ 对应的夹角 = arccos(θ)：

> - 0.9 → ≈25.8°
> - 0.7 → ≈45.6°
> - **0.3 → ≈72.5°**
> - 0.0 → 90°
> - −1  → 180°
>
> 所以 `min-similarity=0.3` 的真实含义是 **「知识块与查询的夹角 ≤72.5° 就放行」**——72° 已经是明显的"半相关"，这条闸门**只拦反方向/近乎正交的垃圾，拦不住"有点相关但跑偏"的内容**。
>
> 反推：要真正收紧到"只放行强相关"，把阈值顶到 **0.6~0.7**（夹角 45°~53° 以内）才谈得上。**看别人报告时先看他设的阈值：低于 0.5，弦外之音就是"这是条宽门"。**

**为什么铁律要求"必须是余弦"：三种相似度度量一张表看清。** 余弦不是唯一可比的量，但它是唯一在 RAG 闸门这件事上站得住的：

| 度量 | 定义 | 取值域 | 与"长短"耦合 | 受向量模长影响 | 适作 RAG 闸门 | 说明 |
|---|---|---|---|---|---|---|
| **余弦 cosine** | 1 − `<=>` 距离 | [−1, 1] | 归一化，不耦合 | 否（只看夹角） | **是（本书铁律）** | 只关心方向，最稳 |
| **点积 dot** | Σ a·b | (−∞, +∞) | 强耦合（越长越大） | 是 | 否 | 未归一，不可跨样本比 |
| **欧氏距离 L2** | √Σ(a−b)² | [0, +∞) | 强耦合 | 是 | 否 | 数值随维度膨胀，阈值难设 |

> **一句话：用余弦是因为它把"长度"归一掉了，只剩"方向"——而"知识块跟查询同不同向"正是 RAG 想判断的事。**

### 2.5 同一个库里就有两种 score 公式

读到这里的你可能以为， "把真实余弦写进 `similarity`" 只是本项目自己的实现选择。**不是。** 横向看三个生产级实现，会发现 `score` 这个字段的定义从来没统一过——**同一个库内部都不止一种写法。**

先看 langchain4j 的 pgvector 存储层：它在 SQL 里把余弦距离换算成一个"分"。

```java
// langchain4j/langchain4j-pgvector/src/main/java/dev/langchain4j/store/embedding/pgvector/PgVectorEmbeddingStore.java:481
"SELECT (2 - (embedding <=> '%s')) / 2 AS score, embedding_id, embedding, text, %s FROM %s "
```

即 `score = 1 - d/2`（余弦距离 `d ∈ [0, 2]` → `score ∈ [1, 0]`）。同一段 SQL 的阈值过滤，是按**同一个公式反推**出来的：

```java
// 同文件 :482
"WHERE round(cast(float8 (embedding <=> '%s') as numeric), 8) <= round(2 - 2 * %s, 8) %s "
```

但同一个仓库的**工具层**，入口换成了"余弦"而不是"距离"：

```java
// langchain4j/langchain4j-core/src/main/java/dev/langchain4j/store/embedding/RelevanceScore.java:15-17
public static double fromCosineSimilarity(double cosineSimilarity) {
    return (cosineSimilarity + 1) / 2;
}
```

`(cos+1)/2` 把 `[-1, 1]` 平移成 `[0, 1]`——存储层与工具层，就是同一个库里那"两种写法"。再看 spring-ai 的 pgvector 实现：它**干脆不换算**，三个距离模板直接把 `distance` 当分用。

```java
// spring-ai/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java:677-688（节选）
EUCLIDEAN_DISTANCE("<->", "vector_l2_ops",
        "SELECT *, embedding <-> ? AS distance FROM %s WHERE embedding <-> ? < ? %s ORDER BY distance LIMIT ? "),
NEGATIVE_INNER_PRODUCT("<#>", "vector_ip_ops",
        "SELECT *, (1 + (embedding <#> ?)) AS distance FROM %s WHERE (1 + (embedding <#> ?)) < ? %s ORDER BY distance LIMIT ? "),
COSINE_DISTANCE("<=>", "vector_cosine_ops",
        "SELECT *, embedding <=> ? AS distance FROM %s WHERE embedding <=> ? < ? %s ORDER BY distance LIMIT ? ");
```

注意这段 SQL 的形状：`WHERE ... < 阈值` 与 `ORDER BY distance LIMIT ?` 在**同一条语句**里，而且阈值方向与前两者**相反**——`distance` 越小越相似。

把四个口径摆到同一对向量上，差异一眼可见：

| 同一对向量（取 `d = 0.6`，即 `cos = 0.4`） | 公式 | score |
|---|---|---|
| 本书契约 | `1 - d` | `0.4` |
| langchain4j 存储层 | `1 - d/2` | `0.7` |
| langchain4j 工具层 | `(cos+1)/2` | `0.7` |
| spring-ai PgVector | 直接返回 `distance` | `0.6`（越小越近） |

> **推演**：取 `d = 0.6`（`cos = 0.4`）代入各公式 —— 本书 `1 − 0.6 = 0.4`；langchain4j 存储层 `1 − 0.6/2 = 0.7`；langchain4j 工具层 `(0.4 + 1)/2 = 0.7`；spring-ai 返回 `distance = 0.6`（假设：`<=>` 为余弦距离且 `d = 1 − cos`；基座值见 `PgVectorEmbeddingStore.java:481`、`RelevanceScore.java:15-17`、`PgVectorStore.java:677-687`）

<img class="mermaid-svg" src="/zh/book-assets/diag-0135.svg" alt="推演：取 `d = 0.6`（`cos = 0.4`）代入各公式 —— 本书 `1 − 0.6 = 0.4`；langchain4j 存储层 `1 − 0.6/2 = 0.7`；langchain4j 工具层 `(0.4 + 1)/2 = 0.7`；spring-ai 返回 `distance = 0.6`（假设：`&lt;=&gt;` 为余弦距离且 `d = 1 − cos`；基座值见 `PgVectorEmbeddingStore.java:481`、`RelevanceScore.java:15-17`、`PgVectorStore.java:677-687`）" />

> **图 31-1**　同一个查询、同一个余弦距离，三个实现给出三个不同的 `score`，spring-ai 连方向都是反的——想让 `minScore` 当绝对质量线，先得钉死自己的口径。

**所以结论要写清楚：不是本项目实现得差，而是跨实现、甚至跨同一个库的不同文件，`score` 的定义本来就不同。** 任何把 `minScore` 当 **"绝对质量线"** 的用法，都必须先钉死口径：这个 `score` 到底是 `1-d`、`1-d/2`，还是 `distance` 本身。

顺带把默认值也钉死。langchain4j 的默认检索器：

```java
// langchain4j/langchain4j-core/src/main/java/dev/langchain4j/rag/content/retriever/EmbeddingStoreContentRetriever.java:70-71
public static final Function<Query, Integer> DEFAULT_MAX_RESULTS = (query) -> 3;
public static final Function<Query, Double> DEFAULT_MIN_SCORE = (query) -> 0.0;
```

`0.0` 就是"不设闸门"。这不是 langchain4j 一家的立场——下一节把三个框架的默认值排在一起看。

### 2.6 minScore 的绝对线为什么不可移植

先给结论：**主流框架的默认值一律是"不设闸门"。** 你如果只是"没配"，那你根本没有闸门。

spring-ai 把这件事直接写进了常量名：

```java
// spring-ai/spring-ai-vector-store/src/main/java/org/springframework/ai/vectorstore/SearchRequest.java:44
public static final double SIMILARITY_THRESHOLD_ACCEPT_ALL = 0.0;
```

```java
// 同文件 :49
public static final int DEFAULT_TOP_K = 4;
```

```java
// 同文件 :58
private double similarityThreshold = SIMILARITY_THRESHOLD_ACCEPT_ALL;
```

`ACCEPT_ALL`——"全收"。检索器的构造器把这个默认值原样接下来：

```java
// spring-ai/spring-ai-rag/src/main/java/org/springframework/ai/rag/retrieval/search/VectorStoreDocumentRetriever.java:79-82
this.similarityThreshold = similarityThreshold != null ? similarityThreshold
        : SearchRequest.SIMILARITY_THRESHOLD_ACCEPT_ALL;
this.topK = topK != null ? topK : SearchRequest.DEFAULT_TOP_K;
this.filterExpression = filterExpression != null ? filterExpression : () -> null;
```

把三个框架摆在一起，默认值的立场完全一致：

| 维度 | langchain4j | spring-ai | 本书 dev |
|---|---|---|---|
| 默认相似度阈值 | `minScore = 0.0`（`EmbeddingStoreContentRetriever.java:70-71`） | `ACCEPT_ALL = 0.0`（`SearchRequest.java:44`） | `min-similarity = 0.3`（`application.yml:234`） |
| 默认候选数 | `maxResults = 3`（同上） | `topK = 4`（`SearchRequest.java:49`） | `retrieve-k = 50`（`application.yml:221`） |
| 空结果怎么办 | 不设闸门 | 默认不放行 + 专用空上下文提示词 | abstain + INFO 日志 |

**默认值一律是"不设闸门"**——想有闸门，就得自己显式配置，并盯着它是否真的拦过东西。

<img class="mermaid-svg" src="/zh/book-assets/diag-0136.svg" alt="默认值一律是&quot;不设闸门&quot;——想有闸门，就得自己显式配置，并盯着它是否真的拦过东西。" />

> **图 31-2**　三个框架的默认阈值都是 0.0（不设闸门），本书的 0.3 是显式加上去的；spring-ai 在"拦住之后说什么"上多走了一步。

**同一道题，spring-ai 的解法比本书更往前一步。** 本书是"闸门拦住 + abstain INFO 日志"——拦住了，但用户拿到的是一个空回复；spring-ai 默认**不放行空上下文**，并且**准备了专门的空上下文提示词**：

```java
// spring-ai/spring-ai-rag/src/main/java/org/springframework/ai/rag/generation/augmentation/ContextualQueryAugmenter.java:77
private static final boolean DEFAULT_ALLOW_EMPTY_CONTEXT = false;
```

```java
// 同文件 :127
if (this.allowEmptyContext) {
```

同文件还有两个模板常量：`:53` 的正常提示词模板与 `:72` 的**空上下文提示词模板**。也就是说，spring-ai 不只是"拦住"，它把"拦住之后该说什么"也做成了默认资产——本书的 `abstain` 只做到前半步。

这个"给正确回复"的思路与按需检索同源：Self-RAG 让模型自己决定要不要检索、检到的够不够（`2310.11511`，https://arxiv.org/abs/2310.11511）。但它的代价是接入模型自评，而 spring-ai 的代价只是**一段默认提示词**——工程上划算得多。

**现在把闸门参数放到双基座上看。** A 基座是一套固定配置，B 基座则根本承受不住"一个绝对值走天下"：

| 维度 | A：代码审查助手 | B：千万 DAU 客服助手 | 由此分叉的设计决策 |
|---|---|---|---|
| 闸门来源 | 单个配置项 `min-similarity=0.3`（`application.yml:234`） | 单一阈值覆盖不了商家 / 类目的分布差异 | 阈值从"一个配置项"变成"按租户标定的标量表" |
| 候选窗 | `retrieve-k=50`（`application.yml:221`） | 共享索引下，租户过滤后候选被线性打折 | 必须把 ACL / 商家过滤**前置**，或按商家分区 |
| 与框架默认的关系 | 框架默认 0.0，本书显式设 0.3 | 同上，但 B 的误放行代价更高 | 默认不设闸门 ⇒ 必须显式配置 + **监控拦截数** |
| 知识变更频率 | 团队规范，低频 | UGC 商品 / 政策，高频 | 阈值标定要**可重跑、可回滚**，不能一次配死 |

> **推演**：`40 × 10% ≈ 4` 条（假设：多租户过滤的选择性为 10%，基座值为 pgvector 默认 `ef_search=40`，见 `pgvector/README.md:450`）

> **推演**：`1000 万 DAU × 1.5 次/日 ÷ 86400 s ≈ 174 次/秒`（均值）；`× 峰值系数 5 ≈ 870 次/秒`（假设：每用户日均 1.5 次咨询、峰值系数 5；基座值取本书候选窗 `retrieve-k=50`，见 `application.yml:221`）

> **推演**：`50 × 0.1% = 0.05` 条（假设：单商家知识约占全库 0.1%，候选窗 50 条见 `application.yml:221`；pgvector 对"过滤在索引扫描之后"的量化说明见 `pgvector/README.md:450`）

第二条推演说明一件比第 29 讲更糟的事：A 基座里过滤选择性尚可（按 `pgvector/README.md:450` 的 `40 × 10%` 算过），而 B 基座的选择性低到 **0.1%**，候选集会**被打折到 1 条以下**——这时候"阈值设多少"已经不是主要矛盾了。

### 2.7 把绝对阈值翻译成业务判据

绝对阈值不可移植，原因有三层，且各自独立：

1. **口径不同。** 同一对向量，`1-d` 与 `1-d/2` 给出两个不同的数（见 2.5）；
2. **嵌入模型不同。** 换一个嵌入模型，同一份语料的相似度分布会整体平移（嵌入模型的横评口径见 MTEB，https://arxiv.org/abs/2210.07316）；
3. **语料分布不同。** 团队规范文本与 UGC 商品描述，余弦分布的**形状**就不一样（检索评测基准的 nDCG / recall 口径见 BEIR，https://arxiv.org/abs/2104.08663）。

所以能移植的从来不是数值，而是 **"业务判据"**。**例如把"相似度 ≥ 0.3"改写成"Top-1 必须高于该查询在验证集上的第 90 百分位"——后者换了模型、换了语料，只要重跑一遍验证集就仍然成立。**

**三步翻译法：**

1. **第一步，钉死口径。** 在配置与注释里写清这个 `score` 是余弦（`1-d`）、是"分"（`1-d/2`），还是距离（`distance`）。这一步做完，2.5 的坑就关掉了。
2. **第二步，取分布。** 在同一嵌入模型 + 同一份语料上，对验证集跑一遍全量 Top-1 的 `similarity`，记录分布——取第 X 百分位（X 由业务定，比如 90）作为候选阈值。
3. **第三步，定判据。** 把配置从 `min-similarity: 0.3` 改写成"Top-1 ≥ 该查询分布的第 90 百分位，否则 abstain"，并把**拦截数**当成上线后的监控指标。

<img class="mermaid-svg" src="/zh/book-assets/diag-0137.svg" alt="3. 第三步，定判据。 把配置从 `min-similarity: 0.3` 改写成&quot;Top-1 ≥ 该查询分布的第 90 百分位，否则 abstain&quot;，并把拦截数当成上线后的监控指标。" />

> **图 31-3**　把绝对阈值翻译成业务判据的三步：钉死口径、在验证集上取分布、定成可复算的判据。

**搬到你自己系统**，对照表如下：

| 工业界 / 本书的做法 | 你自己系统里该问的问题 |
|---|---|
| `score = 1-d` 是本书契约（2.5） | 你读到的 `score` 到底是哪一种口径？在哪一行代码里换算的？ |
| langchain4j 默认 `minScore=0.0`、spring-ai 默认 `ACCEPT_ALL=0.0`（2.6） | 你的阈值是"没配"还是"配了"？拦截数是不是恒为 0？ |
| spring-ai 给空上下文准备了专用提示词（2.6） | 拦住之后，用户看到的是什么？是空白，还是一句正确的"我没找到"？ |
| 阈值线是单值 | 你的语料是单一分布（团队规范）还是多分布（多租户 UGC）？单值够用吗？ |
| 评测要跑固定验证集（RAGAS 指标族见 https://arxiv.org/abs/2309.15217） | 你有没有一份能反复跑的验证集？没有它，第二步就无法执行。 |

**落地步骤（3–5 步）：**

1. **口径签名。** 在检索结果的数据结构上，把 `similarity` 字段的注释改成一句可机械核对的话（例如"本字段恒为 `1 - (embedding <=> query)`"），并在跨后端一致性测试里断言这一点。
2. **建验证集。** 从线上日志里抽 100–300 条真实查询，标注"应有答案 / 应 abstain"，构成固定验证集。
3. **测分布。** 用同一嵌入模型跑全量 Top-1 的 `similarity`，输出 P50 / P90 / P99，取 P90 作为候选阈值。
4. **改写配置 + 上监控。** 把固定阈值替换为分位阈值，并把"候选 N 条、放行 X 条、拦截 Y 条"打成指标；上线后**先看 Y，再看答案质量**。
5. **标定可重跑。** 把第 3 步封装成一条可重跑的命令——换嵌入模型、换语料、加租户维度时都要能重跑一遍。

**判据：** **★ 绝对阈值不可移植，能移植的只有业务判据——把"相似度 ≥ 0.3"改写成"Top-1 ≥ 该查询在验证集上的第 90 百分位"，闸门在换模型、换语料、换租户之后才仍然成立。**

## 三、代码

### 3.1 铁律：`similarity` 恒为真实余弦

修复后的两行代码，是整个模块最重要的一条边界：

```java
// core/rag/PgKnowledgeStore.java:295-300
// similarity = 真实余弦相似度（供 RagEvaluator 的 min-similarity 闸门使用，跨后端可比）；
// rrfScore = RRF 融合归一化排名分（仅供排序与观测，绝不参与阈值判断）。
// 二者必须分开：RRF 排名分第一名恒为 1.0、第 50 名仍有 ~0.55，
// 拿它去比 0.3 的余弦阈值等于闸门永远不关（历史上正是这个 bug 让 abstain 空转）。
m.put("similarity", String.format("%.4f", denseSim.getOrDefault(e.id(), 0.0)));
m.put("rrfScore", String.format("%.4f", norm));
```

**注意这两行的分工**：

- `similarity` → 真实余弦 → **只进阈值判断**；
- `rrfScore` → 归一化排名分 → **只做排序与观测**。

它们在同一个结果集里**并存但不混用**——这是"两个语义不同的数"应该有的相处方式：**不是删掉一个，而是让每个都有自己的名字和职责。**

### 3.2 `fillMissingDenseSim`：同一结果集内语义必须统一

稠密路只取 `widen` 条，稀疏路可能命中更长尾的文档——这些文档**没进稠密路窗口，就没有余弦值**。

如果不处理，它们会沿用 `mapRow` 写进去的 BM25 分——于是**同一个结果集里，有的条目是余弦、有的是 BM25 分**。

这种"语义不统一"比"全是错的"更麻烦：你会看到一部分结果行为正常、一部分异常，根本没法归因。

所以有一趟补算：

```java
// core/rag/PgKnowledgeStore.java:309-314（注释）
/**
 * 为「仅被稀疏路命中」的条目补算真实余弦相似度。
 *
 * <p>稠密路只取 widen 条，稀疏路命中的长尾条目可能不在其中；若不补算，这些条目会沿用
 * {@code mapRow} 写入的 bm25 分，导致 {@code similarity} 语义在同一结果集内都不统一。
 * 补算成本：一条 {@code id IN (...)} 的小查询，仅在确有缺失时执行。
 */
```

实现就一条小查询：

```java
// core/rag/PgKnowledgeStore.java:327-329
String ph = String.join(",", java.util.Collections.nCopies(missing.size(), "?"));
String sql = "SELECT id, 1 - (embedding <=> ?::vector) AS sim "
        + "FROM memory_store WHERE id IN (" + ph + ")";
```

请注意 `1 - (embedding <=> ?::vector)` 这个表达式——**`<=>` 是余弦距离，`1 - 距离 = 余弦相似度`**。这个换算关系要记住，它是本书里"相似度"这个量的唯一定义。

调用点在融合之后、构造结果之前：

```java
// core/rag/PgKnowledgeStore.java:286-288
// 仅被稀疏路命中的条目没进稠密路 widen 窗口，补算一次真实余弦：
// 保证 similarity 语义在 Pg / InMemory 两个后端完全一致（阈值闸门才可能生效）。
fillMissingDenseSim(byId.keySet(), vectorStr, denseSim);
```

### 3.3 读取方要跟着对齐

一个容易被忽略的点：**改完写入方，要检查所有读取方**。

在这个项目里，阈值判断的读取方是 `RagEvaluator`——它**只读 `similarity`**，不碰 `rrfScore`。这是对的。

**但如果某个地方不小心读了 `rrfScore` 去做阈值判断，这个 bug 会以另一种形式回来。** 所以字段名要起得让人不可能搞错：一个叫 `similarity`，一个叫 `rrfScore`——**名字本身就是文档**。

> **跨后端一致性必须要有测试。** 光靠"我改对了"是不够的——第 28 讲会讲另一个同类事故（维度漂移），它们的共同点是：**两个后端声称一致，实际不一致。**

**工程判据：** **阈值闸门是否生效，不看配置，看两个可观测信号：① 拦截数是否恒为 0（恒 0 先怀疑闸门坏了，再怀疑数据好）；② 用受控向量桩在真库上验证——造一对反方向文档（余弦 = −1），若它被放行，说明 `similarity` 里装的不是余弦。** 而阈值设多少合理，用夹角直觉锚定：`min-similarity=0.3` 允许 72.5° 的半相关（宽门），想要强相关就顶到 0.6~0.7。

## 四、避坑清单

- [ ] **排序分与相似度分绝不共用一个字段。** 名字要能自证语义（`similarity` vs `rrfScore`）。
- [ ] **归一化排名分永远不是相似度。** 它的取值范围、物理含义与余弦完全不同。
- [ ] **配了阈值要验证它真的会拦。** 在日志里打出"候选 N 条、放行 X 条、拦截 Y 条"——**如果 Y 恒为 0，先怀疑闸门坏了，再怀疑数据好。**
- [ ] **扩大候选集（`retrieve-k`）会放大这个 bug。** 候选越多，低排名条目的归一化分越高。
- [ ] **跨后端行为一致性要有测试。** 内存后端跑通 ≠ 生产后端跑通。
- [ ] **阈值语义写进接口注释。** 让下一个改这段代码的人知道：这个字段只能比阈值，不能拿去排序。
- [ ] **改动 `similarity` 的写入方式后，grep 所有读取方。** 只改一半比不改更危险。
- [ ] **别把 `minScore` 当绝对质量线。** 同一个库、不同文件里 `score` 的写法都不同（存储层 `1-d/2` vs 工具层 `(cos+1)/2`），跨实现更不可移植（本书 `1-d`、spring-ai 直接用 `distance`）——先钉死口径，再谈阈值。
- [ ] **默认值一律是"不设闸门"。** langchain4j `minScore=0.0`、spring-ai `ACCEPT_ALL=0.0`——照抄框架默认，等于压根没有闸门。
- [ ] **拦住之后要给"正确的回复"。** spring-ai 默认不放行空上下文，还配了专用的空上下文提示词；只拦不说，用户拿到的还是空回答。

## 五、动手任务

> **任务**：用一个"受控向量桩"（人为指定的正交向量）在真库上验证闸门真的会关上。
>
> **为什么用受控向量**：真实 embedding 你无法预测排序，也就无法断言。自己造向量，才能让"反方向文档"的余弦恰好是 `-1`，从而精确验证拦截行为。
>
> **仓库位置**：`code-review-agent`（参考 `src/test/java/com/codereview/agent/e2e/PgRagSemanticsE2eTest.java`）
>
> **操作**：
> 1. 建一张表，插入**两条**知识：
>    - 文档 A：向量与查询向量**同方向**（余弦 ≈ 1）；
>    - 文档 B：向量与查询向量**反方向**（余弦 = **-1**）。
> 2. 用查询向量检索，阈值设为 **0.3**。
> 3. 打印 `similarity` 与放行 / 拦截计数。
>
> **预期结果**：
> - 文档 B 的 `similarity` 应当是 **`-1.0000`**，被闸门**拦截**；
> - 日志形如：`[RAG-Eval] 候选 1 条，放行 0 条，拦截 1 条`。
>
> **关键对照**：如果 `similarity` 显示的是 **`1.0000`**（这是 RRF 归一化分——第一名恒为 1.0），说明**闸门又坏了**——它会把反方向文档原样放行。这一个数字的差异，就是这一讲的全部价值。
>
> **进阶一步（可选）**：把 `min-similarity` 从固定 0.3 改写成"业务判据"——用同一批桩数据记录 Top-1 的 `similarity` 分布，取第 90 百分位作为新阈值，再跑一遍，比较"放行 / 拦截"计数的变化；然后把取分布这一步固化成可重跑命令。

---

## 本讲小结

1. **RRF 归一化排名分与真实余弦是两种数**，前者几乎恒大于 0.3（`norm = 60/(60+rank)`，rank=50 时仍有 0.545，跌破 0.3 需要 rank>140）。
2. **把排名分写进 `similarity`，等于让 `min-similarity` 闸门永不关闭。**
3. 修复方式是**让两个数各归其位**：`similarity` 只进阈值，`rrfScore` 只做排序与观测。
4. **`fillMissingDenseSim`** 保证同一结果集内语义统一——不做这一步，结果是"一半正常、一半异常"。
5. **方法论**：判断闸门是否生效，看"拦截数"是不是恒为 0；真要验证，用**受控向量桩**，因为真实 embedding 无法断言。

下一讲我们把 RAG 链路收口：**重排与多样性**——为什么"检索得准"还不够，Top-N 还得"不重复"。
