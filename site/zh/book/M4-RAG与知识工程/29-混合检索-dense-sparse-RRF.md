# 第 29 讲 · 混合检索：为什么"向量召回"会漏，以及 RRF 到底在奖励什么

> 🎯 导读问题：**"为什么用混合检索？RRF 的 K 是干什么的？"** ——这一讲把"两路融合"从"配置项"讲回"数学"。

<img class="mermaid-svg" src="/zh/book-assets/diag-0126.svg" alt="🎯 导读问题：&quot;为什么用混合检索？RRF 的 K 是干什么的？&quot; ——这一讲把&quot;两路融合&quot;从&quot;配置项&quot;讲回&quot;数学&quot;。" />

> **图 29-0**　本讲地图：混合检索的正当理由是盲区互补：稠密路漏精确术语，稀疏路漏同义不同词。分数不可加、排名才可加，RRF 用 Σw/(k+rank) 融合，k=60 压平头部，0.7 是信任比。

## 一、痛点

知识库里有这条规范：

> 禁止将外部输入直接拼接进 SQL 语句，必须使用参数化查询（PreparedStatement）或预编译语句。

一个 PR 引入了一段字符串拼接的 SQL。我的检索查询里恰好有 `PreparedStatement` 这个词（从 diff 里提取出来的符号）。**检索没命中。**

我把查询改成纯语义描述——"SQL 注入防护"。

**还是没命中。**

第三次我直接把规范原文的一个片段当查询。

**命中**了。

三次实验，三种查询，同一份知识库——**命中与否完全取决于我"碰巧用了什么词"。**

于是我把两路召回的原始结果分别打出来，看到了真相：

| 查询形态 | 稠密（向量） | 稀疏（关键词） |
|---|---|---|
| 含 `PreparedStatement` | ❌ 语义距离远 | ✅ **命中** |
| "SQL 注入防护" | ✅ **命中** | ❌ 字面不重合 |
| 规范原文片段 | ✅ 命中 | ✅ 命中 |

**两路的失败是互补的。** 这就是混合检索存在的全部理由——**不是"两个比一个好"，而是"两个的失败模式不重叠"。**

## 二、原理

### 2.1 两条路各自"结构性"的盲区

**稠密向量（语义路）的盲区：**

| 盲区 | 为什么 |
|---|---|
| **精确术语 / 缩写** | 嵌入模型把 `PreparedStatement` 和 `参数绑定` 映射到相近区域——**"相近"不等于"命中"**。它学的是分布式语义，不是术语等价表 |
| **代码标识符** | `getUserById` / `user_service` / `N+1` 这些在预训练语料里频次低、形态杂，向量表示质量差 |
| **罕见专有名词** | 同理，长尾词表示最差 |
| **否定与修饰** | "禁止字符串拼接" 和 "推荐字符串拼接" 在向量空间里可能很近 |

**稀疏关键词（BM25 路）的盲区：**

| 盲区 | 为什么 |
|---|---|
| **同义不同词** | 文档写"参数绑定"，查询说"SQL 注入防护"——**一个词都不重合，`@@` 判 false** |
| **语义泛化** | 完全不理解"PreparedStatement 能防 SQL 注入"这件事 |
| **词形变化** | `simple` 词典不做词干还原（第 30 讲） |

**把两张表并排看：稠密路的盲区恰好是稀疏路的强项，反之亦然。** 这是"混合"的**唯一正当理由**——如果你说不出两条路的盲区怎么互补，那混合只是"多花了一倍算力"。

### 2.2 融合的第一步：为什么不能直接加分数

最直觉的融合是"加权求和"：

```
final_score = 0.7 × cosine_similarity + 0.3 × bm25_score     ← 错的
```

**错在哪：两个分数不可比。**

| 分数 | 值域 | 分布特征 |
|---|---|---|
| 余弦相似度 | `[-1, 1]`（实测多在 `[0.2, 0.8]`） | 有界、相对稳定 |
| `ts_rank` | `[0, +∞)`（实测常在 `[0, 0.1]`） | 无界、随语料变化 |

`0.7 × 0.6 + 0.3 × 0.05 = 0.435`——**0.3 的权重实际上被"值域差 10 倍"吃掉了。** 你写的是 7:3，实际生效的是 700:3。

**除非做归一化（min-max / z-score），否则分数不可加。** 而归一化本身引入新问题：**归一化基准随查询变化**，于是"这条结果到底有多相关"变成了一个相对量（第 31 讲讲过这个坑的另一面）。

### 2.3 RRF：绕开"分数不可比"的经典解法

**Reciprocal Rank Fusion（倒数排名融合）** 的做法是——**不看分数，只看排名**：

```
RRF(d) = Σ_r  weight_r / (k + rank_r(d))
```

- `rank_r(d)`：文档 `d` 在第 `r` 路里的排名（从 0 或 1 开始）；
- `k`：平滑常数，业界常用 **60**；
- `weight_r`：该路权重，本项目稠密 **0.7**、稀疏 **0.3**。

**它为什么有效：**

| 问题 | RRF 的回答 |
|---|---|
| 分数不可比 | **根本不比较分数**，只比较"排第几" |
| 两路都好怎么算 | 求和，自然累加 |
| 只有一路命中怎么办 | 另一路不贡献，但**命中的那一路仍然有效** |
| 需要标定归一化吗 | 不需要——**排名天然同尺度** |

### 2.4 `k = 60` 到底在干什么

`k` 是"把头部差异压平"的旋钮。看不同 `k` 下第 1 名与第 10 名的比值：

| `k` | rank=0 | rank=9 | 比值 |
|---|---|---|---|
| 1 | `1/1 = 1.0` | `1/10 = 0.1` | **10 倍** |
| 12 |
| **60** | `1/60 = 0.0167` | `1/69 = 0.0145` | **1.15 倍** |

**`k=60` 意味着"第 1 名只比第 10 名重要 15%。"**

这个"压平"很重要：它让 RRF **不相信任何单一路径的排序精度**。因为两路各自的排序都可能不准（向量可能把不相关的排前面，`ts_rank` 可能被高频词带偏），所以**融合策略是"让多路都认可的赢"，而不是"让某一路的第 1 名赢"**。

**`k` 调小 → 更相信头部；`k` 调大 → 更平均。** 60 是一个"足够平"的默认值。

### 2.5 权重 0.7 / 0.3 实际意味着什么（值得算一遍）

这套参数有一个**反直觉的推论**。设 `k=60`：

| 情形 | RRF 分 |
|---|---|
| 只在稠密路排 **#1** | `0.7 / 61` = **0.011475** |
| 只在稀疏路排 **#1** | `0.3 / 61` = **0.004918** |
| **两路都**排 **#27** | `0.7/87 + 0.3/87` = `1/87` = **0.011494** |

**三条结论：**

1. **"稠密路独占第 1 名" ≈ "两路都进前 27 名"**（`0.011475 ≈ 0.011494`）；
2. **"稠密路第 1 名" 明显强于 "稀疏路第 1 名"**（`0.0115` vs `0.0049`，**2.33 倍**）——因为权重是 7:3；
3. 所以 **`denseWeight` 不是"融合比例"，而是"两条路的信任比"**。

**推论（工程含义）**：

- 如果你的场景**术语命中比语义相似更重要**（比如审查"必须用 `PreparedStatement`"这类硬规则），**`denseWeight` 必须调低**——否则"稀疏路排第 1 的精确术语命中"会输给"稠密路排第 1 的语义相近块"；
- 如果调 `k` 而不是 `denseWeight`，你改的是"头部压平程度"，**改不到这个信任比**。这两个旋钮作用不同，别混。

### 2.6 一个必须说清楚的命名问题：`ts_rank` 不是 BM25

本项目的类注释、字段名、SQL 别名全都写 `BM25`：

```java
// core/rag/PgKnowledgeStore.java:48-49
 *   <li><b>稀疏 BM25</b>：{@code search_vector}（tsvector）+ {@code ts_rank}，
 *       对代码标识符 / 专有名词 / 精确术语友好；</li>
```

```java
// core/rag/PgKnowledgeStore.java:214
       ts_rank(search_vector, to_tsquery('simple', ?)) AS bm25
```

**但 PostgreSQL 的 `ts_rank` 不是 BM25：**

| 特性 | BM25 | PG `ts_rank`（默认调用） |
|---|---|---|
| 词频（TF） | ✅ | ✅ |
| **IDF（逆文档频率）** | ✅ **核心** | ❌ **没有** |
| **文档长度归一化** | ✅ | ❌ 默认不启用（需显式传 normalization 参数） |
| 参数 | `k1` / `b` | 权重数组 `{D, C, B, A}` |

**`ts_rank` 缺 IDF，意味着"高频常见词不会被降权"。** 这在代码场景里很危险：一个 diff 里 `public`、`string`、`return` 出现 20 次，它们在语料里也是高频词——**BM25 会把它们的贡献压到接近 0，`ts_rank` 不会。**

**更麻烦的是跨后端不一致。** 内存后端实现的是**教科书 BM25**：

```java
// core/rag/InMemoryKnowledgeStore.java:180-182
double idf = Math.log((N - df.getOrDefault(qt, 0) + 0.5) / (df.getOrDefault(qt, 0) + 0.5) + 1.0);
double denom = n + K1 * (1 - B + B * len / avgLen);
score += idf * (n * (K1 + 1)) / denom;
```

（`K1 = 1.5`、`B = 0.75`，`:43-44`——**标准 Okapi BM25**。）

**于是：**

| 后端 | 稀疏路算法 | 有 IDF | 有长度归一 |
|---|---|---|---|
| `PgKnowledgeStore` | `ts_rank` | ❌ | ❌ |
| `InMemoryKnowledgeStore` | BM25（K1/B） | ✅ | ✅ |

**两个后端都自称"BM25"，但算法不同。** 这意味着"内存后端跑通了 → 生产后端也跑通"这个假设**在稀疏路这一环直接不成立**。

**结论不是"必须换成真 BM25"**（`ts_rank` 在 PG 里是最省事的方案，工程上完全可以接受），**而是"名字必须诚实"**：

- 字段/注释应当写"稀疏路（`ts_rank`）"，而不是"BM25"；
- **跨后端一致性只对"语义"（`similarity` = 真实余弦）做保证，不对"稀疏打分"做保证**——这一点必须写进接口注释。

**这就是本项目反复出现的那个模式："名字承诺 > 实现"**（第 11、12、19、23、27 讲已出现五次，这是第六次）。它和前五次同一个病根：**读代码的人相信了名字，于是不再看实现。**

### 2.7 `ef_search=40` 与 post-filter：候选窗再大也没用

前面讲清了 RRF 怎么融合，但没回答一个更前置的问题：**融合的输入，到底能不能拿满？**

先看 pgvector 的默认值。

`pgvector/src/hnsw.h:60`

```c
#define HNSW_DEFAULT_EF_SEARCH	40
```

它在哪注册、谁能改：

`pgvector/src/hnsw.c:95`

```c
							HNSW_DEFAULT_EF_SEARCH, HNSW_MIN_EF_SEARCH, HNSW_MAX_EF_SEARCH, PGC_USERSET, 0, NULL, NULL, NULL);
```

`PGC_USERSET` 这个词很关键：`ef_search` 是**会话级** GUC，随时可 `SET`，也可以在连接池初始化时统一设——**但默认值是 40，你不设，它就不动。** 这和本书第 26 讲的候选窗 `retrieve-k=50`（`application.yml:221`）**根本不在同一层**：一个决定"应用想要几条"，一个决定"索引里探几个节点"。**要多少，不等于探多少。**

pgvector 官方把这件事的后果写得很直白。

`pgvector/README.md:450`

> With approximate indexes, filtering is applied *after* the index is scanned. If a condition matches 10% of rows, with HNSW and the default `hnsw.ef_search` of 40, only 4 rows will match on average. For more rows, enable [iterative index scans](#iterative-index-scans), which will automatically scan more of the index when needed.

Troubleshooting 一节又重复了一次。

`pgvector/README.md:935`

> Results are limited by the size of the dynamic candidate list (`hnsw.ef_search`), which is 40 by default.

**两句话合起来，就是本讲最该带走的一条：`WHERE` 是"事后过滤"（post-filter），它不参与"探哪些节点"的决策。** 于是过滤的选择性直接乘在候选数上——**线性打折**。

<img class="mermaid-svg" src="/zh/book-assets/diag-0127.svg" alt="两句话合起来，就是本讲最该带走的一条：`WHERE` 是&quot;事后过滤&quot;（post-filter），它不参与&quot;探哪些节点&quot;的决策。 于是过滤的选择性直接乘在候选数上——线性打折。" />

> **图 29-1**　放大候选窗不会放大 `hnsw.ef_search`：SQL 要 100 条，索引层只探 40 个节点，再被租户过滤线性打折。

把这条对回本项目。第 3.1 节那两条 SQL 的 `WHERE` 里都拼进了租户过滤（`agent_type = 'RAG'` 之外还有 team 条件），而每路的窗口是：

`PgKnowledgeStore.java:182-183`

```java
// 稠密路：取 topK*2 扩大召回；BM25 路：同样 topK*2
int widen = Math.max(topK * 2, 20);
```

配合 `retrieve-k=50`，**每路 SQL 写的是 `LIMIT 100`**。看起来"我们要 100 条，挺宽裕"。但索引层的动态候选表仍是 `hnsw.ef_search` 的默认 40——**"要 100" 这件事在索引层根本不成立**：40 个候选里再被租户过滤剔掉一批，剩下的才是你能看见的。

> **推演**：`50 × 10% ≈ 5` 条（假设：多租户过滤的选择性为 10%，基座值 `retrieve-k=50` 见 `application.yml:221`；pgvector 对"过滤在索引扫描之后"的量化说明见 `pgvector/README.md:450`）

**这就是"候选窗再大也没用"的机制原因**：`retrieve-k` 和 `widen` 动的是"我要几条"，动不了"索引探几个"。后者在 `ef_search` 里，默认 40。

官方给的补救入口是 iterative index scan（0.8.0 引入，`pgvector/CHANGELOG.md:37`）：开启后索引会自动多扫，直到凑够结果或撞到熔断，机制说明见 `pgvector/README.md:480`。但它是"多扫"，不是"不设限"——多扫的边界照样要由你来定。

#### 双基座：同一个默认值，在两个系统里的代价差多远

| 维度 | A：代码审查助手 | B：千万 DAU 客服助手 | 由此分叉的设计决策 |
|---|---|---|---|
| 部署形态 | 中低 QPS，单实例共享一张 `memory_store` | 高 QPS、多副本，租户（商家）数量级按千万 DAU 推演 | B 必须把租户隔离下沉到索引 / 分区层，而不是只在 `WHERE` 里加条件 |
| 索引内候选表 | `hnsw.ef_search` 默认 40，从未调整（`pgvector/src/hnsw.h:60`） | 同样是默认 40 | 默认值在两个基座上都"不对"，但 B 上错得更贵 |
| 过滤选择性 | 团队知识库，`team_id` 过滤的命中率接近 100% | 单商家文档占全表的比例按推演很低（见下） | 选择性越低，post-filter 打折越狠：A 感觉不到，B 直接接近归零 |
| 候选窗 | `retrieve-k=50`（`application.yml:221`） | 还是 50，但要多扛一层低选择性过滤 | 窗口大小不是旋钮，"索引内候选表 + 过滤顺序"才是 |
| 知识变更频率 | 低频（团队规范文档） | 高频（UGC 商品 / 政策文档随时改） | B 还要额外解决索引更新与 stale 候选，A 可以暂时忽略 |
| 失败表现 | 偶尔漏一条规范，靠人发现 | 尾部队列大面积漏召回，且容易与"内容刚改过"混淆 | B 的可观测性要求更高：必须能区分"过滤打的折"与"新内容没进索引" |

> **推演**：`40 × 1% ≈ 0.4` 条（假设：千万 DAU 电商客服助手下单个商家的文档占全表约 1%，即租户过滤的选择性为 1%，基座值 `ef_search=40` 见 `pgvector/src/hnsw.h:60`；基座 B 无仓库可复算，故该选择性亦为推演值）

**同一个 40，在 A 那里只是"偶尔漏"，在 B 那里是"平均不到 1 条候选"。** 参数没变，变的是选择性。

### 2.8 只被一路命中的条目：补算真实余弦

RRF 的输入是两路各自的 `widen` 条（`PgKnowledgeStore.java:182-183`）。**两路窗口等宽，但两路装的东西不等价**：稠密路的每一条都自带真实余弦（SQL 里 `1 - (embedding <=> ?::vector) AS sim`），稀疏路的每一条只有 `ts_rank` 分（第 2.6 节那个"不是 BM25 的 BM25"）。

于是出现一个必然的长尾：**有些条目只在稀疏路里命中，压根没进稠密路的 `widen` 窗口——它们没有真实余弦。**

本项目在融合之后立刻补了一步。

`PgKnowledgeStore.java:286-288`

```java
        // 仅被稀疏路命中的条目没进稠密路 widen 窗口，补算一次真实余弦：
        // 保证 similarity 语义在 Pg / InMemory 两个后端完全一致（阈值闸门才可能生效）。
        fillMissingDenseSim(byId.keySet(), vectorStr, denseSim);
```

方法本身的 javadoc 把"不补算会怎样"写得很清楚。

`PgKnowledgeStore.java:309-314`

```java
    /**
     * 为「仅被稀疏路命中」的条目补算真实余弦相似度。
     *
     * <p>稠密路只取 widen 条，稀疏路命中的长尾条目可能不在其中；若不补算，这些条目会沿用
     * {@code mapRow} 写入的 bm25 分，导致 {@code similarity} 语义在同一结果集内都不统一。
     * 补算成本：一条 {@code id IN (...)} 的小查询，仅在确有缺失时执行。
     */
```

补算本身是一条小 SQL。

`PgKnowledgeStore.java:327-329`

```java
        String ph = String.join(",", java.util.Collections.nCopies(missing.size(), "?"));
        String sql = "SELECT id, 1 - (embedding <=> ?::vector) AS sim "
                + "FROM memory_store WHERE id IN (" + ph + ")";
```

<img class="mermaid-svg" src="/zh/book-assets/diag-0128.svg" alt="图 29-2　两路窗口不等宽：稀疏路命中的长尾条目不在稠密路窗口内，必须补算一次 `id IN (...)`，否则同一结果集里的 `similarity` 是两种量纲。" />

> **图 29-2**　两路窗口不等宽：稀疏路命中的长尾条目不在稠密路窗口内，必须补算一次 `id IN (...)`，否则同一结果集里的 `similarity` 是两种量纲。

**三个值得停下来的点：**

**① 不补算的后果不是"分数不准"，而是"量纲混装"。** javadoc 点名了毒默认值藏在 `mapRow` 里——稀疏路的行会沿用 `bm25` 分当 `similarity`。于是同一批候选里，一部分的 `similarity` 是余弦（`[-1, 1]`），一部分是 `ts_rank`（`[0, +∞)` 且量纲完全不同）。**闸门比的是两种东西。**

**② 它因此是第 31 讲那条铁律的** **"前置条件"** **，不是一次独立优化。** 第 31 讲的铁律是"排序分与相似度分绝不共用字段"——本讲 3.3 里 `denseRank` / `denseSim` 分开存，正是它的前身。而那条铁律要能成立，前提是**每个进入结果集的条目都真的有一个真实余弦**；否则字段是分开了，其中一个里装的仍然是两种量纲。

**③ 成本是"条件性"的。** javadoc 最后一句写着"仅在确有缺失时执行"——缺失集合为空就直接返回。所以它只在"稀疏路真的捞到了稠密路没捞到的东西"时才付一次 `id IN (...)`。**反过来说：这条补算 SQL 的执行频率，本身就是"两路互补程度"的一个在线指标。**

### 2.9 两种 RRF 落地位置：SQL 内 vs 应用侧

本项目是**应用侧融合**：两路各出 `widen` 条，`denseRank` / `sparseRank` 只存排名，再在 Java 里算 `Σ weight / (RRF_K + rank)`（`PgKnowledgeStore.java:275-285`，其中 `RRF_K = 60.0` 见 `PgKnowledgeStore.java:60`）。**langchain4j 把同一件事做了两遍——一遍在应用侧，一遍在数据库里。**

应用侧（`langchain4j`）：

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/rag/content/aggregator/ReciprocalRankFuser.java:29-31`

```java
    public static List<Content> fuse(Collection<List<Content>> listsOfContents) {
        return fuse(listsOfContents, 60);
    }
```

公式与 rank 起点：

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/rag/content/aggregator/ReciprocalRankFuser.java:56-58`

```java
                int rank = i + 1;
                double newScore = currentScore + 1.0 / (k + rank);
                scores.put(content, newScore);
```

**这里是 `rank = i + 1`，从 1 起**——与本项目 `rank++` 从 0 起（第 3.3 节）**不一样**。同一个 `k=60`，配不同的 rank 起点，`k + rank` 就不是一个数。**读别人的 RRF 实现时，第一件要看的就是 rank 从几开始。**

库内（`langchain4j-pgvector`）则把默认 `k` 与公式一起搬进了 SQL：

`langchain4j/langchain4j-pgvector/src/main/java/dev/langchain4j/store/embedding/pgvector/PgVectorEmbeddingStore.java:63`

```java
    private static final int DEFAULT_RRF_K = 60;
```

融合 SQL：

`langchain4j/langchain4j-pgvector/src/main/java/dev/langchain4j/store/embedding/pgvector/PgVectorEmbeddingStore.java:576`

```sql
                         COALESCE(1.0 / (%9$d + v.rnk), 0.0) + COALESCE(1.0 / (%9$d + k.rnk), 0.0) AS score
                       FROM vector_search v
                       FULL OUTER JOIN keyword_search k ON v.embedding_id = k.embedding_id
```

两侧候选窗（同文件 `:588`）：

```java
                    Math.max(maxResults, rrfK),
```

**`:578` 的 `FULL OUTER JOIN` 是库内融合的关键**：只被一路命中的条目也会被保留，`COALESCE(..., 0.0)` 把缺的那一路记 0——这与 RRF 的语义一致，也是"两路互补"能被保住的实现前提。而 `:588` 的 `Math.max(maxResults, rrfK)` 说明**库内融合会悄悄把你的候选窗抬到至少 `k`**：你传 `maxResults=10`，它实际按 60 取。**"我只要 10 条"在库内落地时会变成"先各取 60 条再融"。** 这是成本，不是 bug。

再看多查询：**RRF 不是跑一轮就够。** 类注释直接点明是两阶段（`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/rag/content/aggregator/DefaultContentAggregator.java:22`）：

> This implementation employs Reciprocal Rank Fusion (see {@link ReciprocalRankFuser}) in two stages

两阶段的落点（同文件 `:61` / `:64`，第二阶段用到的方法定义在同文件 `:74`）：

```java
        Map<Query, List<Content>> fused = fuse(queryToContents);
```
```java
        return ReciprocalRankFuser.fuse(fused.values());
```

第一阶段在一个查询内部跨数据源融合，第二阶段再跨查询（多个改写版本）融一次。**只做一轮是常见实现错误。** 本项目关闭了查询改写（`application.yml:230-231`），所以这一轮暂时用不上；**但一旦打开改写，RRF 必须跑两轮**，否则"哪个改写版本更好"会被悄悄揉进同一次融合里。

<img class="mermaid-svg" src="/zh/book-assets/diag-0129.svg" alt="第一阶段在一个查询内部跨数据源融合，第二阶段再跨查询（多个改写版本）融一次。只做一轮是常见实现错误。 本项目关闭了查询改写（`application.yml:230-231`），所以这一轮暂时用不上；但一旦打开改写，RRF 必须跑两轮，否则&quot;哪个改写版本更好&quot;会被悄悄揉进同一次融合里。" />

> **图 29-3**　RRF 的两种落地位置：应用侧融合把 `k` 留在代码里，库内融合把 `k` 焊进 SQL——代价是少一次网络往返，换来 `k` 改不动。

#### 应用侧 vs SQL 内：一张取舍表

| 取舍项 | 应用侧融合（本项目 / `ReciprocalRankFuser`） | 库内融合（`PgVectorEmbeddingStore`） |
|---|---|---|
| 网络往返 | 两路各一次查询，外加可能的补算（`PgKnowledgeStore.java:327-329`）→ 多 | 一条 SQL 出结果（`:576`）→ 少 |
| 融合常数 `k` 的位置 | 代码常量（`PgKnowledgeStore.java:60`）→ 可配置、可热改 | 焊进 SQL（`:576` 的 `%9$d`）→ 改它等于改语句 |
| rank 起点 | 本项目从 0 起（第 3.3 节） | 由 SQL 里的排名函数决定 |
| 候选窗 | 完全由你控制（`widen`，`PgKnowledgeStore.java:182-183`） | 被抬到 `max(maxResults, rrfK)`（`:588`） |
| 可测性 | 两路排名与融合分都能在 Java 里断点、单测 | 融合发生在库里，单测要连真库 |
| 换库成本 | 与存储解耦 | 换掉 pgvector 就要重写这段 SQL |

**两句话的结论：** 少一次网络往返，换 `k` 焊进 SQL；多一次往返，换 `k` 与 rank 起点都由你说了算。**这不是"哪个更好"，是"你更怕哪一类事故"**——怕延迟就下沉，怕改不动就上浮。

**业界对 RRF 的解释是收敛的。** Azure AI Search 有一节专门讲混合检索排序，langchain4j 的 javadoc 直接指向它（https://learn.microsoft.com/en-us/azure/search/hybrid-search-ranking）；Elasticsearch 的 RRF 文档里，`rank_constant` 的默认值同样是 60（https://www.elastic.co/docs/reference/elasticsearch/rest-apis/reciprocal-rank-fusion）。RRF 本身出自 Cormack、Clarke、Buettcher 的 SIGIR 2009 论文（DOI `10.1145/1571941.1572114`；作者主页 PDF 见 https://plg.uwaterloo.ca/~gvcormac/cormacksigir09-rrf.pdf）。

#### 搬到你自己系统

| 工业界 / 本项目的做法 | 你自己系统里该问的问题 |
|---|---|
| 本项目在应用侧融合，`RRF_K = 60.0` 是代码常量（`PgKnowledgeStore.java:60`） | 你的 `k` 是配置项还是硬编码？改它要不要发版？ |
| langchain4j 应用侧 `fuse` 默认 60、`rank` 从 1 起（`ReciprocalRankFuser.java:29-31` / `:56-58`） | 你用的是哪个实现？它的 `rank` 从 0 还是 1 起？ |
| langchain4j 库内把 `k` 写进 SQL（`PgVectorEmbeddingStore.java:576`） | 你愿意把融合下沉到数据库吗？换库时这段 SQL 谁维护？ |
| 库内两侧候选窗 = `Math.max(maxResults, rrfK)`（`:588`） | 你的两路窗口等宽吗？窄的那一路是不是先天吃亏？ |
| 多查询要两轮 RRF（`DefaultContentAggregator.java:22` / `:61` / `:64`） | 你开了查询改写吗？开了的话 RRF 跑了几轮？ |
| `hnsw.ef_search` 默认 40、会话级可改（`pgvector/src/hnsw.h:60` / `pgvector/src/hnsw.c:95`） | 谁负责调 `ef_search`？这个默认值在你的过滤选择性下还够吗？ |
| A 基座过滤选择性接近 100%，B 基座按推演低到 1% | 你 `WHERE` 里那个过滤条件的选择性是多少？算过 `ef_search × 选择性` 吗？ |

**落地步骤（五个动作）：**

1. **把"两路各自的命中数"打点出来**，而不只是"融合命中 N 条"（第 3.5 节的修法）。没有这一步，后面四步都只能靠猜。
2. **算出你的过滤选择性**：`该租户的候选条数 / 全表候选条数`，再用 `ef_search × 选择性` 估"过滤之后平均还能剩几条"。低于你的 `retrieve-k`，就说明这个窗口是假的。
3. **做一次 `ef_search` 敏感性实验**（会话级 `SET`），记录"每路命中数 + P95 延迟"，画出召回-延迟曲线；再决定是调大 `ef_search`、开 iterative scan，还是用分区 / 分表把过滤前置。
4. **决定 RRF 落在哪一层，并写进 ADR**：应用侧还是库内，选哪个就锁死，**不要两处各实现一遍**。
5. **把 `retrieve-k` / `widen` / `k` / `weight` / `ef_search` 收进同一份配置**，并在配置注释里写明它们的层关系（应用想要几条 vs 索引探几个节点）。

**判据**：**如果你的 `ef_search` 还是默认 40、而 `WHERE` 里又带着低选择性的过滤条件，那么你调大的每一个 `retrieve-k` 都只是在放大一个已经被打折的窗口——A 基座上你看不出来，B 基座上它会直接表现为"召回到了尾部队列就消失"。**

## 三、代码

### 3.1 两条 SQL：dense 与 sparse

```java
// core/rag/PgKnowledgeStore.java:201-218（节选）
String denseSql = """
        SELECT id, agent_type, team_id, content, metadata, level, created_at,
               1 - (embedding <=> ?::vector) AS sim
        FROM memory_store
        WHERE agent_type = 'RAG' AND %s %s
        ORDER BY embedding <=> ?::vector
        LIMIT ?
        """.formatted(teamFilter, freshSql);

String sparseSql = """
        SELECT id, agent_type, team_id, content, metadata, level, created_at,
               ts_rank(search_vector, to_tsquery('simple', ?)) AS bm25
        FROM memory_store
        WHERE agent_type = 'RAG' AND %s %s
          AND search_vector @@ to_tsquery('simple', ?)
        ORDER BY ts_rank(search_vector, to_tsquery('simple', ?)) DESC
        LIMIT ?
        """.formatted(teamFilter, freshSql);
```

**四处值得停下来看：**

**① `agent_type = 'RAG'` 是硬编码在 SQL 里的。** 这是"物理共享 `memory_store` 表、逻辑上按 `agent_type` 隔离"（`KnowledgeStore.java:20-24`）的落地方式。**风险是它把隔离口径写进了字符串**——如果将来 `agent_type` 常量改了，这两条 SQL 是 grep 不到的那种"隐式依赖"。

**② `1 - (embedding <=> ?)` 就是余弦相似度。** `<=>` 是 pgvector 的**余弦距离**，`1 - 距离 = 相似度`。这个换算写在一行里，**没有注释**——它是第 31 讲那个"阈值闸门"的数值源头。

**③ 稀疏 SQL 里 `to_tsquery('simple', ?)` 出现了三次**（`SELECT` 里算 `ts_rank`、`WHERE` 里判 `@@`、`ORDER BY` 里再算 `ts_rank`），所以 `setString` 要绑定三次同一个值：

```java
// core/rag/PgKnowledgeStore.java:248-259
int idx = 1;
ps.setString(idx++, sparseTsQuery);
ps.setString(idx++, t);
if (includeGlobal) {
    ps.setString(idx++, Teams.GLOBAL);
}
if (!freshSql.isEmpty()) {
    ps.setLong(idx++, maxAge.toSeconds());
}
ps.setString(idx++, sparseTsQuery);      // ← 第 2 次
ps.setString(idx++, sparseTsQuery);      // ← 第 3 次
ps.setInt(idx++, widen);
```

**`SELECT` 里那次其实是多余的**——`ts_rank` 的值只用于排序，`WHERE` 已经保证命中了。去掉它可以少绑一次参数、少算一次打分。但这不是 bug，只是**一个可以删掉的冗余计算**。

**④ 两条 SQL 的占位符顺序不同，靠人工对齐。** 对比一下：

| 位置 | denseSql | sparseSql |
|---|---|---|
| 1 | `vectorStr` | `sparseTsQuery` |
| 2 | `t` | `t` |
| 3（可选） | `Teams.GLOBAL` | `Teams.GLOBAL` |
| 4（可选） | `maxAge` | `maxAge` |
| 5 | `vectorStr` | `sparseTsQuery` |
| 6 | `widen` | `sparseTsQuery` |
| 7 | — | `widen` |

**两条 SQL 的参数表不一样，而绑定代码是两段相似的 `idx++` 序列。** 这就是本项目反复出现的 **"靠下标对齐传递身份"** 模式（第 18、20、23 讲各一次，第 27 讲的重叠坐标系一次，这是第五次）。

**代价**：往 `WHERE` 里加一个条件（比如再加一个元数据过滤），你得同时数两遍参数位置。**修法**：给每条 SQL 上方写一份参数表注释，或者改用命名参数（JDBC 不直接支持，但可以自建一个 `Map<String,Object>` → 位置 的绑定器）。

### 3.2 稀疏路查询：为什么必须用 OR

```java
// core/rag/PgKnowledgeStore.java:190-194
// 稀疏路查询串：中文 bigram + 标识符子词，用 OR 连接。
// 用 OR 而非 plainto_tsquery 的 AND：RAG 查询是整段 diff/长句，AND 要求全部词命中，
// 长查询几乎必然零命中（另一种形式的稀疏路失效）；OR 保证「任一关键词命中」即可召回，
// 由 ts_rank 负责把多词命中的文档排到前面。
String sparseTsQuery = TextTokenizer.toTsQueryOr(query, SPARSE_QUERY_MAX_TERMS);
```

**这段注释是全书最诚实的一段之一**——它把"另一种形式的稀疏路失效"直接点名了。

`to_tsquery('simple', 'a & b & c')` 要求**全部词命中**。一个 400 字符的查询会被分词成几十个词——**要求几十个词全部出现在同一条知识里，命中概率趋近于 0。**

**`OR` 把语义从"全部命中"放宽到"任一命中"**，把排序权交给 `ts_rank`。这是长查询场景的**必选项**。

**`SPARSE_QUERY_MAX_TERMS = 40`**（`:62`）是防止 `to_tsquery` 过长拖慢检索的截断。**注意这是"截断"不是"取最重要的 40 个"**——如果 `toTsQueryOr` 是按顺序截断，那排在前面的词（往往来自 `DiffQueryExtractor` 输出的文件名/类名）会被保留，而方法名/符号可能被挤掉。**抛给读者的检查项：去看 `TextTokenizer.toTsQueryOr` 的截断顺序是否与你的意图一致。**

### 3.3 RRF 融合：三行实现

```java
// core/rag/PgKnowledgeStore.java:274-284
// RRF 融合
Map<Long, Double> rrf = new HashMap<>();
for (var en : denseRank.entrySet()) {
    rrf.merge(en.getKey(), denseWeight / (RRF_K + en.getValue()), Double::sum);
}
for (var en : sparseRank.entrySet()) {
    rrf.merge(en.getKey(), (1 - denseWeight) / (RRF_K + en.getValue()), Double::sum);
}
List<Map.Entry<Long, Double>> fused = new ArrayList<>(rrf.entrySet());
fused.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
double maxRrf = fused.isEmpty() ? 1.0 : fused.get(0).getValue();
```

**注意 `denseRank` / `sparseRank` 里存的是"排名"而不是分数**（`:241`、`:265`）：

```java
// core/rag/PgKnowledgeStore.java:236-242
int rank = 0;
while (rs.next()) {
    MemoryEntry e = mapRow(rs, rs.getDouble("sim"));
    byId.put(e.id(), e);
    denseSim.put(e.id(), rs.getDouble("sim"));    // ← 分数
    denseRank.put(e.id(), (double) rank++);       // ← 排名
}
```

**这就是 RRF 落到代码里的样子：**

- `denseSim` 存**分数**（真实余弦，供阈值用）；
- `denseRank` 存**排名**（供 RRF 用）；
- 两者**分开存**——这正是第 31 讲那条"排序分与相似度分绝不共用字段"铁律在本讲的前身。

**顺带一个细节**：`rank++` 是**从 0 开始**的。所以 RRF 公式里的 `RRF_K + 0 = 60`。**如果哪天有人把它改成从 1 开始，所有分数会整体偏移，融合排名可能变化。** `k` 的语义依赖于"rank 从 0 起"这个隐含约定——**值得写进注释**。

### 3.4 候选扩窗：`widen = max(topK × 2, 20)`

```java
// core/rag/PgKnowledgeStore.java:181-182
// 稠密路：取 topK*2 扩大召回；BM25 路：同样 topK*2
int widen = Math.max(topK * 2, 20);
```

配合 `review.rag.retrieve-k=50`（`application.yml:221`），实际每路取 **100** 条，融合后最多 200 个候选，最后取前 50 交给下游（阈值 → 重排 → MMR → 注入 5 条）。

**"扩窗再精排"是标准做法**（第 26 讲的漏斗里，候选窗 50 是"召回 50、最终只注入 5"）。但**这次放大的是融合前的窗口**：

| 参数 | 作用 |
|---|---|
| `widen = topK × 2` | **每路**的召回条数（RRF 的输入规模） |
| `topK` | **融合后**返回条数（交给重排） |
| `inject-top-n` | 最终注入条数 |

**三个数字、三层漏斗，别混。** 改 `retrieve-k` 会同时改变 `widen`（因为是倍数关系）——**这是隐式耦合**：你想"多召回一点给重排"，结果**每路的召回窗口也翻倍了**，RRF 的输入池变大，融合排名可能变化。

### 3.5 稀疏路被跳过时，日志不会告诉你

```java
// core/rag/PgKnowledgeStore.java:245-246,268
// 稀疏路：查询为空（或分词后无词）时整段跳过——to_tsquery('simple','') 会抛语法错误
if (!sparseTsQuery.isBlank()) {
    ...
}
```

**这个保护是必要的**（空查询串会让 `to_tsquery` 报语法错误）。但**跳过时没有任何日志**——`hybridSearch` 只在最后打一行（`:304-305`）：

```java
log.info("[PgKnowledge] 混合检索：team={}, topK={}, 融合命中 {} 条, 耗时 {}ms",
        t, topK, result.size(), System.currentTimeMillis() - t0);
```

**这行日志只告诉你"融合命中了几条"，没告诉你"每路各命中几条、有没有哪一路被跳过"。**

**后果**：当召回质量下降时，你**无法区分**下面三种情况：

| 症状 | 真实原因 |
|---|---|
| 候选只有 3 条 | 稠密路召回少？稀疏路被跳过了？两路都少？ |
| 候选很多但都不相关 | 稠密路排序坏了？`ts_rank` 被高频词带偏？ |
| 候选中没有任何术语命中 | 稀疏路根本没执行（查询分词后为空）？还是执行了但没命中？ |

**修法（很便宜）**：

```java
// 修法示意（不是仓库现状）
log.info("[PgKnowledge] 混合检索明细：team={}, dense 路 {} 条, sparse 路 {} 条{}, 融合 {} 条",
        t, denseRank.size(), sparseRank.size(),
        sparseTsQuery.isBlank() ? "（查询分词后为空，已跳过）" : "", fused.size());
```

**这一行日志，是第 26 讲"漏斗 4 个数字"往上游延伸的第 5、6 个数字。** 有它，"召回变差"才可归因。

## 四、避坑清单

- [ ] **混合检索的理由是"盲区互补"，不是"两个比一个好"。** 说不清两路各自的盲区，就别做混合——那是双倍算力买同一份错误。
- [ ] **绝不直接加权求和"余弦 + BM25 分"。** 值域差一个数量级，你的 7:3 实际生效的是 700:3。
- [ ] **RRF 只看排名，是这个方案的精华。** 如果哪天有人给你"把 rank 换成归一化分数会更好"，先问"那你用什么做跨查询可比的归一化基准"。
- [ ] **`k` 和 `weight` 是两个不同的旋钮。** `k` 控制"头部压平程度"，`weight` 控制"两路信任比"。想提升术语命中的话语权，要调的是 **weight**，不是 `k`。
- [ ] **`rank` 是从 0 还是从 1 开始，必须写进注释。** 它直接进 `k + rank`，改起点会改变融合结果。
- [ ] **`ts_rank` 不是 BM25，名字要改。** 注释、字段名、SQL 别名写 `BM25` 会让人误以为有 IDF 和长度归一化——PG 的 `ts_rank` 默认两样都没有。
- [ ] **跨后端一致性只保证你真正统一的那部分。** 本项目 `similarity`（真实余弦）跨 Pg / InMemory 是一致的；**稀疏打分不是**（`ts_rank` vs 真 BM25）。接口注释要显式声明"哪部分一致、哪部分不一致"。
- [ ] **长查询的稀疏路必须用 OR。** `AND` 在几十个词的查询上"几乎必然零命中"——这是稀疏路失效的第二种形态。
- [ ] **每一路的命中数都要能观测。** "融合命中 N 条"不够——要能看出"哪一路被跳过、哪一路为 0"。
- [ ] **参数化 SQL 的占位符顺序要么加注释表，要么用统一绑定器。** 两条结构相似的 SQL 各自绑定参数，是"下标对齐"事故的温床。
- [ ] **`retrieve-k` 与 `widen` 隐式耦合**（`widen = topK × 2`）。调一个等于调两个，配置注释要说清楚。
- [ ] **权重常量不要在两处各写一份。** `PgKnowledgeStore` 用 `denseWeight` 字段（默认 0.7）、`InMemoryKnowledgeStore` 用字面量 `0.7 / 0.3`——**没有共享常量、也没有配置项**。改一处就会造成跨后端行为分叉。
- [ ] **候选窗不是旋钮，索引内的动态候选表才是。** `hnsw.ef_search` 默认 40 且是会话级 GUC（`pgvector/src/hnsw.h:60` / `pgvector/src/hnsw.c:95`）——`retrieve-k`、`widen` 调多大都不改变它。调窗口之前先把 `ef_search` 设对。
- [ ] **过滤选择性低时，先算 `ef_search × 选择性`，再决定要不要把过滤前置。** pgvector 官方给的量化例子就是 `40 × 10% = 4`（`pgvector/README.md:450`）。多租户共享一张表时，这个乘积就是你的真实召回上限。
- [ ] **融合进来的长尾条目必须先补算真实余弦，再进同一个结果集。** 否则一批候选里 `similarity` 会同时是余弦与 `ts_rank` 两种量纲，第 31 讲的阈值闸门实际上在同时比两种东西（`PgKnowledgeStore.java:286-288` / `:308-313`）。

## 五、动手任务

> **任务**：用"受控语料"对比三条检索路径的召回能力，并实测 RRF 的"权重即信任比"。
>
> **仓库位置**：`code-review-agent`（`core/rag/PgKnowledgeStore.java`；参考 `src/test/java/com/codereview/agent/e2e/PgRagSemanticsE2eTest.java`）
>
> **准备语料**（3 条，覆盖三种命中形态）：
> | # | 内容 | 命中的路 |
> |---|---|---|
> | A | "禁止将外部输入直接拼接进 SQL，必须使用 PreparedStatement 参数化查询" | 稀疏（含 `PreparedStatement`） |
> | B | "所有用户输入在进入数据库前必须做转义与校验，防止注入" | 稠密（语义近、字面不重合） |
> | C | "数据库查询必须显式列出字段，禁止 SELECT *" | 都不该命中（对照组） |
>
> **操作**：
> 1. 用查询 `PreparedStatement` 检索：观察 A 是否命中、B 是否命中。
> 2. 用查询 `SQL 注入防护` 检索：观察 B 是否命中、A 是否命中。
> 3. **分别打印两路的原始排名**（临时把 `denseRank` / `sparseRank` 打出来）。
> 4. **改权重实验**：把 `denseWeight` 分别设为 `0.7` 和 `0.3`，重跑第 2 步。
> 5. **量一次 `ef_search` 的敏感性**：用会话级 `SET hnsw.ef_search = 100` 重跑第 1、2 步（默认 40 见 `pgvector/src/hnsw.h:60`，会话级可改见 `pgvector/src/hnsw.c:95`），记录"每路命中数 + P95 延迟"；再算出这份语料的过滤选择性（`该 team 的 RAG 块数 / RAG 块总数`），用 `ef_search × 选择性` 估"过滤后还剩几条"。**如果你把 `ef_search` 从 40 调到 400 而召回几乎没动，说明瓶颈不在索引层，而在过滤顺序。**
>
> **预期结果**：
> - 查询 1：**A 在稀疏路排前、B 在稠密路排前**——两路互补可见；
> - 查询 2：B 应稳定召回；A 的召回应**依赖权重**——`denseWeight` 调低后 A 的排名上升；
> - C **不应出现在 Top-K**（无论是哪一路）；
> - 对比表应能验证 2.5 的推论：**"稠密路 #1" ≈ "两路都 #27"**，而 "稀疏路 #1" 明显弱于 "稠密路 #1"。
>
> **关键对照**：把"两路各自排名"写成一个持久化的实验日志（不是一次性打印）。下一次有人问"为什么这条规范没被检索到"，你能直接回答"它只在稀疏路排第 3，而 denseWeight=0.7 把它压到了第 6 之后"。

---

## 本讲小结

1. **混合检索的正当理由是盲区互补**：稠密路漏精确术语/标识符/长尾词，稀疏路漏同义不同词。两路失败模式不重叠，融合才有意义。
2. **分数不可加，排名才可加。** RRF（`Σ weight/(k+rank)`）绕开了"余弦有界、`ts_rank` 无界"的不可比问题，代价是丢掉分数的绝对信息——**这正是本项目把排名分与相似度分分开存的原因**（`denseRank` vs `denseSim`）。
3. **`k=60` 是"头部压平"旋钮**：`k=60` 时第 1 名只比第 10 名重要 15%——融合策略是"多路都认可的赢"。
4. **`denseWeight=0.7` 是"信任比"而不是"融合比例"**：算一遍就知道，"稠密路 #1"（0.011475）≈ "两路都 #27"（0.011494），而"稀疏路 #1"只有 0.004918。**要让术语命中说话，调的是 weight，不是 k。**
5. **`ts_rank` 不是 BM25**（无 IDF、默认无长度归一），而内存后端实现的是真 BM25——**两个后端都叫 BM25，算法不同**。名字必须诚实，跨后端一致性边界必须显式声明。
6. **每一路的命中数必须可观测。** 现在只有"融合命中 N 条"，无法区分"稀疏路被跳过"和"稀疏路没命中"。

下一讲（第 30 讲）我们钻到稀疏路的最底层：**为什么 PG 的 `simple` 词典对中文"一个字都切不出来"**——那是一条 SQL 就能验证、却能让整条稀疏路彻底空转的坑。
