# 第 25 讲 · 混合检索：为什么"向量召回"会漏，以及 RRF 到底在奖励什么

> 🎯 导读问题：**"为什么用混合检索？RRF 的 K 是干什么的？"** ——这一讲把"两路融合"从"配置项"讲回"数学"。

<img class="mermaid-svg" src="/zh/book-assets/diag-0040.svg" alt="🎯 导读问题："为什么用混合检索？RRF 的 K 是干什么的？" ——这一讲把"两路融合"从"配置项"讲回"数学"。" />


> **图 25-0**　本讲地图：混合检索的正当理由是盲区互补：稠密路漏精确术语，稀疏路漏同义不同词。分数不可加、排名才可加，RRF 用 Σw/(k+rank) 融合，k=60 压平头部，0.7 是信任比。

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
| **词形变化** | `simple` 词典不做词干还原（第 26 讲） |

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

**除非做归一化（min-max / z-score），否则分数不可加。** 而归一化本身引入新问题：**归一化基准随查询变化**，于是"这条结果到底有多相关"变成了一个相对量（第 27 讲讲过这个坑的另一面）。

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
| 10 | `1/10 = 0.1` | `1/19 = 0.053` | 1.9 倍 |
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

**这就是本项目反复出现的那个模式："名字承诺 > 实现"**（第 09、10、16、20、23 讲已出现五次，这是第六次）。它和前五次同一个病根：**读代码的人相信了名字，于是不再看实现。**

## 三、代码

### 3.1 两条 SQL：dense 与 sparse

```java
// core/rag/PgKnowledgeStore.java:201-220
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

**② `1 - (embedding <=> ?)` 就是余弦相似度。** `<=>` 是 pgvector 的**余弦距离**，`1 - 距离 = 相似度`。这个换算写在一行里，**没有注释**——它是第 27 讲那个"阈值闸门"的数值源头。

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

**两条 SQL 的参数表不一样，而绑定代码是两段相似的 `idx++` 序列。** 这就是本项目反复出现的 **"靠下标对齐传递身份"** 模式（第 15、17、20 讲各一次，第 23 讲的重叠坐标系一次，这是第五次）。

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
- 两者**分开存**——这正是第 27 讲那条"排序分与相似度分绝不共用字段"铁律在本讲的前身。

**顺带一个细节**：`rank++` 是**从 0 开始**的。所以 RRF 公式里的 `RRF_K + 0 = 60`。**如果哪天有人把它改成从 1 开始，所有分数会整体偏移，融合排名可能变化。** `k` 的语义依赖于"rank 从 0 起"这个隐含约定——**值得写进注释**。

### 3.4 候选扩窗：`widen = max(topK × 2, 20)`

```java
// core/rag/PgKnowledgeStore.java:181-182
// 稠密路：取 topK*2 扩大召回；BM25 路：同样 topK*2
int widen = Math.max(topK * 2, 20);
```

配合 `review.rag.retrieve-k=50`（`application.yml:221`），实际每路取 **100** 条，融合后最多 200 个候选，最后取前 50 交给下游（阈值 → 重排 → MMR → 注入 5 条）。

**"扩窗再精排"是标准做法**（第 22 讲的漏斗里，候选窗 50 是"召回 50、最终只注入 5"）。但**这次放大的是融合前的窗口**：

| 参数 | 作用 |
|---|---|
| `widen = topK × 2` | **每路**的召回条数（RRF 的输入规模） |
| `topK` | **融合后**返回条数（交给重排） |
| `inject-top-n` | 最终注入条数 |

**三个数字、三层漏斗，别混。** 改 `retrieve-k` 会同时改变 `widen`（因为是倍数关系）——**这是隐式耦合**：你想"多召回一点给重排"，结果**每路的召回窗口也翻倍了**，RRF 的输入池变大，融合排名可能变化。

### 3.5 稀疏路被跳过时，日志不会告诉你

```java
// core/rag/PgKnowledgeStore.java:245-246
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

**这一行日志，是第 22 讲"漏斗 4 个数字"往上游延伸的第 5、6 个数字。** 有它，"召回变差"才可归因。

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

下一讲（第 26 讲）我们钻到稀疏路的最底层：**为什么 PG 的 `simple` 词典对中文"一个字都切不出来"**——那是一条 SQL 就能验证、却能让整条稀疏路彻底空转的坑。
