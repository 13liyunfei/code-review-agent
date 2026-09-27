# 第 35 讲 · 成本、缓存与在线闭环：把 RAG 当生产系统运营

> 🎯 导读问题：**"检索质量调好了，为什么账单和事故还没变好？"** ——这一讲把 RAG 从"效果工程"推进到"运营工程"：算清五个环节的成本量级、给三层缓存定死隔离键、把在线信号接成人审闭环，并认清三类变更的回滚代价完全不同。

<img class="mermaid-svg" src="/zh/book-assets/diag-0155.svg" alt="🎯 导读问题：&quot;检索质量调好了，为什么账单和事故还没变好？&quot; ——这一讲把 RAG 从&quot;效果工程&quot;推进到&quot;运营工程&quot;：算清五个环节的成本量级、给三层缓存定死隔离键、把在线信号接成人审闭环，并认清三类变更的回滚代价完全不同。" />

> **图 35-0**　本讲地图：成本 → 缓存 → 在线闭环 → 灰度回滚，四件事串成一条运营链。成本决定缓存值不值得做，缓存与在线信号决定灰度能不能测，而回滚代价决定灰度该放多小。

## 一、痛点

第 33 讲结束时，我以为 RAG 这件事做完了：基线有了、指标有了、验收流程有了。

然后我试着回答三个运营问题，一个都答不上来。

**问题一：一次审查到底花了多少钱？**

我能背出 `retrieve-k=50`（`application.yml:221`）、`inject-top-n=5`（`application.yml:223`），也能背出重排超时是 5000ms（`application.yml:206-207`）。但我说不出：

- 这 5 条注入进 prompt 一共是多少 token；
- 一次审查里，重排 API 被打了几次分；
- 如果 QPS 放大一百倍，先撑不住的是数据库、是重排额度、还是 token 预算。

**"知道配置"和"知道成本"是两件事。** 前者是读 yml，后者是把 yml 变成一条算式。

**问题二：如果给 RAG 加一层缓存，租户之间会不会串味？**

本项目是多租户的——`KnowledgeStore.java:12-16` 写得很清楚："物理共享同一张表、逻辑上互为独立接口"。我第一反应是"缓存 key 用查询文本就行了"，然后立刻意识到一个反例：

**如果两个团队问了字面完全相同的问题呢？**

同一个 query 文本，team A 和 team B 的正确答案是不同的（各自的规范不同）。如果缓存只认文本，它会把 team A 的答案返给 team B。

**这个问题的答案不在"要不要缓存"，而在"缓存 key 里有没有隔离键"。**

**问题三：如果我要换嵌入模型，多久能回滚？**

我一开始没觉得这是问题——直到想起第 28 讲的"维度契约"：换模型常常顺带换维度，而 **维度一变，存量的向量全部作废**。

那就不是"回滚"，那是"重算"。

**这三个问题的共同点是：它们都不是"检索质量"问题，而是"运营"问题。** 第 26–35 讲把知识这条链路的质量做通了；这一讲办的是另一件事——**把它当成一个要长时间跑、要花钱、要出事、要回退的生产系统来运营。**

## 二、原理

### 2.1 成本结构：五个环节各自量级

先把钱花在哪说清楚。一次 RAG 请求的完整成本，可以拆成五个环节：

| 环节 | 触发时机 | 量级随什么涨 |
|---|---|---|
| **① 嵌入（写入侧）** | 摄取文档时，每块一次 | 语料块数（一次性） |
| **① 嵌入（查询侧）** | 每次请求一次 | 查询数 × 1 |
| **② 存储** | 常驻 | 语料块数 × 维度（与流量无关） |
| **③ 检索** | 每次请求 | 查询数 ×（1 次 ANN + 1 次稀疏路） |
| **④ 重排** | 每次请求 | 查询数 × **送进重排的候选数** |
| **⑤ 注入 token** | 每次请求 | 查询数 × 注入条数 × 每块 token × 单价 |

<img class="mermaid-svg" src="/zh/book-assets/diag-0156.svg" alt="| ⑤ 注入 token | 每次请求 | 查询数 × 注入条数 × 每块 token × 单价 |" />

> **图 35-1**　RAG 的成本结构：写入侧是一次性的嵌入 + 常驻的存储，查询侧是"嵌入 → 检索 → 重排 → 注入 token"四段串联；前三段随流量线性增长，重排多一个候选数乘数，注入 token 多一个长度乘数。

**这张图最重要的读法是"哪些环节与流量解耦"。**

- **② 存储**是唯一与流量无关的成本——它只随语料量涨。所以"知识库变大"和"QPS 变大"是两个独立的成本轴，别混着算。
- **③ 检索**随流量线性涨，但它花的是**你自己的** CPU 与内存（`pgvector` 的 `ef_search` 默认 40，见 `pgvector/src/hnsw.h:60`）。
- **④ 重排**是唯一一个"流量 × 候选数"双乘数环节——**它花的是外部的钱**（本项目用 Cohere 的 `rerank-english-v3.0`，超时 5000ms 见 `application.yml:206-207`）。
- **⑤ 注入 token** 是"流量 × 条数 × 长度"三乘数环节，也是**唯一被 `inject-top-n` 直接控制的一项**。

成本算式，逐条写出来：

```
查询侧嵌入成本   = 查询数 × 1 次调用
检索成本         = 查询数 × (1 次 ANN + 1 次稀疏路)
重排成本         = 查询数 × 送进重排的候选数
注入 token 成本  = 查询数 × 注入条数 × 每块平均 token × 单价
存储成本         = 语料块数 × 维度 × 每维字节
```

**本项目的一手参数**（这些都能机械复算）：候选窗 `retrieve-k = 50`（`application.yml:221`）、注入条数 `inject-top-n = 5`（`application.yml:223`）、重排超时 5000ms（`application.yml:206-207`）、嵌入维度 `dim = 1024`（`application.yml:197`）。

把它们代进基座 B 的语境：

> **推演**：`1,000 万 × 2 / 86,400 ≈ 231` 次/秒（假设 DAU 1000 万、人均每日 2 次知识问答；基座值见 `application.yml:221` 的 `retrieve-k=50`）

> **推演**：`1,000 万 × 2 × 5 = 1 亿` 个注入块/天（假设 DAU 1000 万、人均每日 2 次问答、每次注入 5 条；基座值见 `application.yml:223` 的 `inject-top-n=5`）

> **推演**：`1,000 万 × 2 × 15 = 3 亿` 次重排打分/天（假设 DAU 1000 万、人均每日 2 次问答、每次送 `rerankPool = 3 × topN = 15` 条进重排；基座值见 `inject-top-n=5`（`application.yml:223`）与 `RagContextBuilder.java:204-217` 的候选池算式）

> **推演**：`1,000 万 × 1024 = 102.4 亿` 个浮点数常驻（假设知识库 1000 万块、每块一个向量；基座值见 `application.yml:197` 的 `dim: 1024`）

**读这四条推演要读出的不是数字，而是乘数结构。** 重排那 3 亿次里，`15` 不是"召回需要 15 条"——它是给 MMR 留的挑选余地（第 32 讲的 `rerankPool`）。**一个为"多样性"设的参数，在基座 B 的规模下变成了重排账单上的主要乘数。**

**双基座在这个维度上的差别是量级级的：**

| 维度 | A：代码审查助手 | B：千万 DAU 客服助手 | 由此分叉的设计决策 |
|---|---|---|---|
| 查询量级 | 团队内部触发，中低 QPS | ≈ 231 次/秒（推演，见上） | A 的成本核算可以按天；B 必须按秒看 P99 成本 |
| 重排乘数 | 同样 `rerankPool = 3 × topN`（`RagContextBuilder.java:204-217`） | 同一乘数，但乘在 231 QPS 上（推演，见上） | 候选池不只影响质量，它是**账单的乘数** |
| 存储与流量的关系 | 规范低频变更，存储成本几乎不变 | UGC 高频变更，存储与重算成本都在动 | B 的"重算"是常态，A 的"重算"是事件 |

### 2.2 缓存三层：嵌入、语义、结果，各自会怎么失效

缓存是降本的第一手段，但"缓存"不是一个东西，是**三个不同层次、失效条件完全不同**的东西。

| 层 | 缓存的 key | 缓存的 value | 命中条件 | 失效条件 |
|---|---|---|---|---|
| **① 嵌入缓存** | 模型 id + 规范化文本 | 向量 | 文本完全相同 | **换嵌入模型** |
| **② 语义缓存** | 查询文本的向量 | 模型答案 | 向量距离 ≤ 阈值 | TTL 到期 / 阈值变化 / 上下文变 |
| **③ 结果缓存** | 查询 hash（含租户与参数） | 最终结果（注入块或答案） | 查询与参数完全一致 | 语料更新 / 参数变更 |

<img class="mermaid-svg" src="/zh/book-assets/diag-0157.svg" alt="| ③ 结果缓存 | 查询 hash（含租户与参数） | 最终结果（注入块或答案） | 查询与参数完全一致 | 语料更新 / 参数变更 |" />

> **图 35-2**　三层缓存与各自的失效条件：越往下走（嵌入 → 语义 → 结果），缓存的东西越"终态"，失效条件也越多；而"隔离键缺失"是唯一一个会让正确答案变成错误答案的失效。

**三层里最便宜、收益最确定的是 ① 嵌入缓存。** 它的 key 是确定的（文本 + 模型 id），不需要任何阈值和近似—— **"同文本必同向量"是嵌入的数学事实，不是经验判断**。但它只省写入侧和重复查询的钱，省不了检索和重排。

**最值钱、也最危险的是 ② 语义缓存。** 它用一个相似度阈值来换命中率，命中即绕过整条链路（连 LLM 调用都省了）。spring-ai 在 `spring-ai/vector-stores/spring-ai-redis-semantic-cache/src/main/java/org/springframework/ai/vectorstore/redis/cache/semantic/DefaultSemanticCache.java:83` 给了它的默认阈值：

```java
private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.8;
```

**这个 0.8 是"相似度"口径，但后端可能是距离口径**，所以同一文件 `:234-241` 做了一次显式换算：

```java
double effectiveThreshold = this.similarityThreshold;
...
    effectiveThreshold = 1 - (this.similarityThreshold / 2);
...
        logger.debug("Converting distance threshold " + this.similarityThreshold + " to similarity threshold "
```

**`1 - (阈值 / 2)` 正是第 31 讲那条"余弦距离 → 相似度"的换算公式。** 语义缓存把第 31 讲的"similarity 语义铁律"从检索层搬到了缓存层——**缓存命中判断是一个阈值判断，于是它继承了阈值的全部口径问题**：阈值调松一点，命中率上去、误命中率也上去；调紧一点，缓存白建。

TTL 是它的第二个必答参数，`DefaultSemanticCache.java:223`：

```java
redisStore.getJedisClient().expire(key, ttl.getSeconds());
```

**TTL 是"语义缓存与新鲜度的唯一妥协点"**：知识会变，缓存不会自己知道。本项目 freshness 的 `max-age-days=0`（`application.yml:226`）意味着知识要求"当天有效"——**在这种新鲜度要求下，语义缓存的 TTL 必须跟着这个口径走，否则缓存会退回过期知识。**

**第三个必答参数是隔离键**，也是三层缓存里唯一会导致"答案从对变错"的参数。见 3.3。

语义缓存的服务侧视角在 SCALM（`2406.00025`，https://arxiv.org/abs/2406.00025）里有专门讨论——**命中率、阈值、误命中代价，三者是一起被调的两个方向**。工程载体侧可以看 Redis for AI（https://redis.io/docs/latest/develop/ai/）。

**双基座在缓存上的分叉最大：**

| 维度 | A：代码审查助手 | B：千万 DAU 客服助手 | 由此分叉的设计决策 |
|---|---|---|---|
| 语料变更频率 | 团队规范，低频变更 | UGC 商品 / 政策，**高频变更** | A 的 TTL 可以按天；B 的 TTL 必须按语料变更周期设 |
| 缓存命中率的价值 | 命中即省一次重排与 LLM 调用 | 命中率是**核心指标**，直接决定单位成本 | B 必须把"命中率"当一级指标看，A 当优化项看 |
| 多租户隔离 | 多租户（team），租户数少 | 多租户（商家），租户数多且共享热 query | B 的隔离键必须进 key，A 也不能例外 |
| 缓存串味的代价 | 一个团队看到另一个团队的规范 | 一个商家看到另一个商家的政策，**合规风险** | 隔离键不是优化，是**正确性前提** |

**"命中率"在基座 B 里直接换算成钱**——这就是缓存从"可选优化"变成"必须做对"的原因。

### 2.3 在线闭环：隐式信号 → A/B → 人审

第 33 讲（2.6 与 2.7）已经立好第一根柱子：**生产流量里没有 ground truth，所以只能在线的"观察"代替离线的"标注"。** 这一节把闭环的三段补齐，并且钉死每一段的边界。

**第一段：隐式信号（implicit signal）只能当弱标签。**

| 信号 | 怎么采 | 只回答什么 |
|---|---|---|
| 命中块是否被引用 | 输出里的引用标记 / 块 id 回指 | 注入的块有没有真的进到答案 |
| 审查意见是否被采纳 | 意见被接受 / 客服回复被直接用 | 检索到的块有没有帮上忙 |
| 是否被标  **"误报"** | 显式负反馈入口 | 检索到的块有没有帮倒忙 |

**三个信号加起来仍然只是弱标签**，因为它有一个结构性缺陷第 33 讲已经点破：**曝光偏差**——你只能拿到"被检索出来的块"的反馈，**召回漏掉的那些块，永远不会有反馈**。所以"隐式信号算出来的召回率"在数学上就不是召回率。

**第二段：A/B 的分流单位必须是查询或会话，不能是用户。**

这一条在第 33 讲已经给出，这里要把"为什么"补完整：

- RAG 是**带回溯上下文**的（多轮、有会话历史、有检索缓存）。
- 按 `userId` 分流时，同一个人的同一段会话可能在第二轮落到另一个版本。
- 于是你测到的是**版本切换本身造成的噪声**，不是版本差异。

**分流单位是"可归因性"的物理前提，不是实现细节。**

**第三段：人审是最终裁判。**

弱标签给方向，A/B 给统计显著性，**但"这条答案到底对不对"只有人能给**。人审的位置必须在**回流到 golden set 之前**——它既是裁判，也是评测集的产能。

样本量必须**在动手前算出来**，而不是"跑一周看看"：

> **推演**：`16 × 0.5 × 0.5 / 0.01² = 40,000`（假设：基线采纳率 50%、要检测的绝对提升是 1 个百分点、95% 置信 + 80% 功效；基座值见 `application.yml:237` 的 `eval-enabled: true`——本项目该开关已打开，但生产 ground truth 恒为 `null`，所以这个样本量目前无处落地）

**人审队列的产能也必须先算**，否则闭环会堵在最后一段：

> **推演**：`1,000 万 × 2 × 1% = 20 万` 条/天（假设 DAU 1000 万、人均每日 2 次问答、隐式信号回流采样率 1%；基座值见 `application.yml:221` 的 `retrieve-k=50`）

> **推演**：`20 万 × 0.1% = 200` 条/天（假设：回流 20 万条/天、人工抽检率 0.1%；基座值见 `application.yml:237` 的 `eval-enabled: true`）

**如果抽检率定成 1%，人审队列就是 2000 条/天——那是要招人的。** 抽检率是成本决策，不是实验设计的一个小尾巴。

<img class="mermaid-svg" src="/zh/book-assets/diag-0158.svg" alt="如果抽检率定成 1%，人审队列就是 2000 条/天——那是要招人的。 抽检率是成本决策，不是实验设计的一个小尾巴。" />

> **图 35-3**　在线闭环的三段：隐式信号（弱标签）→ A/B（按查询 / 会话分流）→ 人审（最终裁判）→ 回流成下一轮 golden set；三段各有自己的失效方式，任何一段缺位，闭环就退化成"我觉得这版更好"。

**闭环的评测口径有两个可复现的业界出处**：ARES（`2311.09476`，https://arxiv.org/abs/2311.09476）用**小模型 + 少量标注**做评测，**不依赖 GPT-4 打分**——正对"评测跑不动"的根因；Ragas（`2309.15217`，https://arxiv.org/abs/2309.15217）给出 faithfulness / answer relevance / context precision 这一族指标。

**闭环真正要接的下一个动作是"降级"，而不是"报警"。** CRAG（`2401.15884`，https://arxiv.org/abs/2401.15884）给的就是这个范式的学术版本：**检索质量差时触发纠正动作**（改写查询、降级到另一条检索路）。这条思路的工程落点就是 2.5 的降级链。

### 2.4 灰度与回滚：三类变更，三种回滚代价

**灰度发布的前提是"回滚得起"。** 而 RAG 的三类变更，回滚代价根本不在一个量级上。

<img class="mermaid-svg" src="/zh/book-assets/diag-0159.svg" alt="灰度发布的前提是&quot;回滚得起&quot;。 而 RAG 的三类变更，回滚代价根本不在一个量级上。" />

> **图 35-4**　三类变更的回滚代价阶梯：参数类改配置即回滚（秒级），索引类要再建一次索引（随数据量线性增长），模型类改的是"存量数据本身"，**回滚的代价就是重算，等于不可回滚**。

**① 参数类变更：可秒级回滚。**

`ef_search`（`pgvector/src/hnsw.h:60` 默认 40）、`min-similarity`（`application.yml:234` 的 0.3）、`retrieve-k`（`application.yml:221` 的 50）——这三个都只是"查询时的行为参数"，改回去就回滚了。

`ef_search` 尤其干净：它是一个**会话级 GUC**（`PGC_USERSET`，注册处见 `pgvector/src/hnsw.c:95`），也就是说 **`SET hnsw.ef_search = ...` 立刻生效、不必重建索引、可以只对一部分连接生效**。**这是三类变更里唯一能做到"按连接灰度"的。**

**② 索引类变更：要再建一次索引。**

`m` / `ef_construction`（HNSW 建索引参数，默认 16 / 64，见 `pgvector/src/hnsw.h:54` / `:57`）、IVFFlat 的 `lists` / `probes`（IVFFlat 的经验法则见 `pgvector/README.md:340-342`；默认 `lists=100` / `probes=1` 见 `pgvector/src/ivfflat.h:55` / `:58`）——这些参数**写进索引本身**，改了必须重建。

重建的成本是**可见的**，pgvector 会主动告诉你：

`pgvector/README.md:291`：

> Indexes build significantly faster when the graph fits into `maintenance_work_mem`

并行度默认只有 2（`pgvector/README.md:309`）：

> You can also speed up index creation by increasing the number of parallel workers (2 by default)

而生产环境建索引不能锁写，`pgvector/README.md:704`：

> In production environments, create indexes concurrently to avoid blocking writes.

`CREATE INDEX CONCURRENTLY` 的代价是**更慢**（它要跑两遍表扫描）——**所以"索引类变更的回滚"不是"再跑一次 SQL"，而是"再跑一次一个更慢的 SQL，并且这段时间系统压力更大"。** 代价随数据量线性增长，这是它和参数类最本质的区别。

**还有一条更隐蔽的**：换索引类型也不是无代价回滚，因为**跨索引类型的能力不对等**。HNSW 的 iterative scan 有三档（`pgvector/src/hnsw.h:163-165`）：

```c
	HNSW_ITERATIVE_SCAN_OFF,
	HNSW_ITERATIVE_SCAN_RELAXED,
	HNSW_ITERATIVE_SCAN_STRICT
```

而 IVFFlat 只有两档，没有 strict（`pgvector/src/ivfflat.h:116-117`）：

```c
	IVFFLAT_ITERATIVE_SCAN_OFF,
	IVFFLAT_ITERATIVE_SCAN_RELAXED
```

iterative scan 本身是 0.8.0 才引入的（`pgvector/CHANGELOG.md:37` / `:39`）。**所以"从 HNSW 换到 IVFFlat 再换回来"，不只是重建两次索引——中间那段时间你的过滤准确性静默降级了（第 34 讲的 post-filter 问题）。**

**③ 模型类变更：不可回滚。**

这是唯一一个**改完之后"旧世界"就消失了**的变更。

换嵌入模型 → 新旧向量不在同一个空间 → 存量向量全部作废 → **必须全量重算**。

> **推演**：`1,000 万块 × 1 次嵌入调用 = 1,000 万` 次嵌入调用（假设知识库 1000 万块、换模型后每块重算一次；基座值见 `application.yml:197` 的 `dim: 1024`）

维度约束还直接卡死选型。`pgvector/src/hnsw.h:33`：

```c
#define HNSW_MAX_DIM 2000
```

**`vector` 类型的 HNSW 索引上限 2000 维。** 本项目用 1024 维（`application.yml:197`）是安全的；**但如果新模型是 3072 维（比如 OpenAI `text-embedding-3-large`），你连 HNSW 索引都建不出来。**

绕开它的路是 pgvector 0.7.0 引入的 `halfvec`（`pgvector/CHANGELOG.md:68` / `:70`），它把维度上限提到 4000。而 pgvector README 给的建议是把三件事**绑在一起**用（`pgvector/README.md:755-756`）：

> 1. Use the `halfvec` type instead of `vector` for tables
> 2. Use [binary quantization](#binary-quantization) for indexes (with re-ranking for search)

**注意括号里那句 "with re-ranking"**——`pgvector/README.md:734` 说得更直白：

> Use [binary quantization](#binary-quantization) with re-ranking to keep indexes in-memory at scale.

**"量化必须配重排"是连在一起的建议。** 换成 halfvec、或者再加二值量化，召回率必然下降，**这个下降必须由重排（第 32 讲）来偿还**。于是：

**一次"模型类变更"会同时打穿三处：存量向量（重算）、索引类型（可能要换 halfvec）、以及重排成本（量化后要加重排来补召回）。** 这就是它"不可回滚"的真正含义——**它不是一次变更，是一次架构迁移。**

所以灰度策略应该由"回滚代价"反推：

| 变更类 | 灰度单位 | 观察期 | 决定因素 |
|---|---|---|---|
| 参数类 | 连接 / 会话（`SET` 级） | 分钟级 | 指标立刻可见 |
| 索引类 | 蓝绿索引 + 少量表 | 小时级 | 重建耗时决定灰度节奏 |
| 模型类 | 新表 + 影子流量 | 天级 | **必须有并行的新旧两套向量**，否则连比较对象都没有 |

**最后一行是这一节的判据：模型类变更的灰度，必须先解决"新旧两套向量并存"的问题——否则你不是在灰度，你是在赌。**

### 2.5 降级链与一手运营账：搬到你自己系统

**降级链是这一讲的收口动作。** 前面四节讲了成本、缓存、闭环、回滚；但真正让一个系统"跑得久"的，是**每一个非核心依赖都有一条可退的路**。

| 挂了的东西 | 退到哪 | 代价 |
|---|---|---|
| **嵌入服务** | 退到稀疏路（BM25 风格的关键词检索） | 召回质量降，但链路不断 |
| **重排服务** | 退到启发式重排（第 32 讲的三级降级链） | 排序质量降，但结果仍出 |
| **缓存** | 直连后端（缓存是加速器，不是依赖） | 成本回升、延迟回升，但正确性不变 |

**第三条最关键也最容易被写错**：**缓存必须是"旁路"而不是"必经"**。如果缓存挂了整条链路就挂，那它就不是缓存，是单点。

同族的工业范式在 spring-ai 里：**校验失败就打 WARN 降级，而不是抛异常**（`spring-ai/spring-ai-rag/src/main/java/org/springframework/ai/rag/preretrieval/query/expansion/MultiQueryExpander.java:118-131`）：

```java
if (CollectionUtils.isEmpty(queryVariants) || this.numberOfQueries != queryVariants.size()) {
```

**注意它的立场：降级不是异常路径，是被设计出来的一条正常路径。** 这与本项目的风格一致——第 32 讲数过 `ApiReranker` 的 4 条降级路径，也是"降级而非抛错"。

**降级链有一个隐藏成本，必须显式记账：降级会污染成本与质量两类指标。** 如果监控上"缓存命中"和"缓存挂了直连"打在同一个指标里，你会看到成本上升却查不出原因。**每一级降级都要有独立可观测标记**（第 32 讲的判据在这里复用）。

**把本讲能机械复算的数摆齐：**

| 项 | 值 | 出处 |
|---|---|---|
| 候选窗 `retrieve-k` | 50 | `application.yml:221` |
| 注入条数 `inject-top-n` | 5 | `application.yml:223` |
| 相似度闸门 `min-similarity` | 0.3 | `application.yml:234` |
| freshness `max-age-days` | 0 | `application.yml:226` |
| 嵌入维度 `dim` | 1024 | `application.yml:197` |
| 重排超时 | 5000ms | `application.yml:206-207` |
| 评测开关 `eval-enabled` | true | `application.yml:237` |
| MMR 相关性权重 λ | 0.7 | `application.yml:214-215` + `RagContextBuilder.java:78-79` |
| RRF 常数 | `RRF_K = 60.0` | `PgKnowledgeStore.java:60` |
| 稀疏路扩窗 | `widen = max(topK*2, 20)` | `PgKnowledgeStore.java:182` |

**这十个数是运营账的全部锚点。** 缺任何一个，你都算不出 2.1 那张算式里的某一项。

**双基座的三元对照：**

| 维度 | A：代码审查助手 | B：千万 DAU 客服助手 | 由此分叉的设计决策 |
|---|---|---|---|
| 核心指标 | 检索质量（hit@k / MRR，第 33 讲） | 质量 **＋ 成本 ＋ 缓存命中率** | B 的看板必须同时挂质量与成本两条线 |
| 成本压力 | 中低 QPS，成本是"优化项" | ≈ 231 次/秒（推演，见 2.1） | B 的成本核算必须先于功能迭代 |
| 语料变更 | 团队规范，低频 | UGC 商品 / 政策，高频 | B 的缓存 TTL、重算频率都要跟着语料周期走 |
| 变更灰度 | 可以"整个团队一起切" | 必须按商家 / 流量分层灰度 | B 的灰度单位是"层"，A 的是"次" |
| 回滚能力 | 索引类变更可在维护窗口做 | 任何变更都要能在**线上**回滚 | B 必须预留"新旧并存"的容量 |

**「搬到你自己系统」的对照表：**

| 工业界 / 本项目的做法 | 你自己系统里该问的问题 |
|---|---|
| spring-ai 语义缓存默认阈值 0.8（`DefaultSemanticCache.java:83`）+ 距离换算（`:234-241`） | 我的缓存阈值是"相似度"还是"距离"口径？换算写在哪一行？ |
| 语义缓存 TTL 显式设置（`DefaultSemanticCache.java:223`） | 我的 TTL 跟语料变更周期对得上吗？还是拍了个"一小时"？ |
| `contextHash` 用 system prompt 的 hash 做隔离键（`SemanticCacheAdvisor.java:122`） | 我的缓存 key 里有没有租户 / 上下文这一维？ |
| 本项目多租户过滤是字符串条件（`KnowledgeStore.java:12-16`） | 我的租户 id 进了缓存 key 吗，还是只在 SQL 里？ |
| `ef_search` 是会话级 GUC（`pgvector/src/hnsw.c:95`） | 我的参数变更能不能只对一部分连接生效？ |
| `CREATE INDEX CONCURRENTLY` 不锁写但更慢（`pgvector/README.md:704`） | 我的索引重建有没有排期和容量预留？ |
| `HNSW_MAX_DIM 2000`（`pgvector/src/hnsw.h:33`） | 我如果换 3072 维模型，索引还建得出来吗？ |
| 量化必须配重排（`pgvector/README.md:734`） | 我为了省内存做的量化，重排预算加了没有？ |
| 校验失败打 WARN 降级（`MultiQueryExpander.java:118-131`） | 我的降级是独立可观测状态，还是一行日志？ |

**落地步骤（5 步）：**

1. **把成本算式写进监控**：按 2.1 的五环节，为"查询侧嵌入次数 / 检索次数 / 重排打分数 / 注入块数 / 注入 token 数"各打一个计数器（拒绝用日志行数当指标）。
2. **给缓存定三个参数并写进配置**：阈值、TTL、**隔离键**；隔离键至少包含租户 id，对照 `SemanticCacheAdvisor.java:122` 的 `contextHash` 做法。
3. **把降级链的每一级做成独立状态**（`cache.mode = hit / miss / bypass`、`rerank.mode = api / heuristic / none`），并断言"缓存 bypass 时结果与无缓存一致"。
4. **给三类变更各写一份灰度与回滚预案**：参数类走 `SET` 级灰度；索引类走蓝绿索引 + 预留重建窗口；**模型类必须先做"新旧向量并存"，再谈灰度**。
5. **算一次模型类变更的全量重算账**（2.4 的推演），并对照 `HNSW_MAX_DIM 2000`（`pgvector/src/hnsw.h:33`）确认新模型维度可建索引。

**★ 判据：如果一次变更你答不出"回滚需要多久、回滚时数据和索引是什么状态"，那这次变更就没有灰度资格——它只有"赌"和"不赌"两个选项。**

## 三、代码

### 3.1 从配置项读出成本算式

成本算式的输入全在配置里，直接把能复算的抄出来。`application.yml:221`：

```yaml
retrieve-k: ${RAG_RETRIEVE_K:50}
```

`application.yml:197`：

```yaml
dim: 1024
```

**这两个参数分别落在公式的不同位置，别混：**

| 配置项 | 值 | 落在算式的哪一项 | 角色 |
|---|---|---|---|
| `retrieve-k` | 50 | 检索的候选窗 | 决定**检索与补算**的规模，不直接决定成本大头 |
| `inject-top-n` | 5 | 注入 token 的条数 | 决定**注入 token** 的条数乘数 |
| 重排候选池 | `3 × inject-top-n = 15` | 重排的打分次数 | 决定**重排**的乘数（`RagContextBuilder.java:204-217`） |
| `dim` | 1024 | 存储的维度 | 决定**存储**与**写入侧嵌入**的规模 |

**这里有一个反直觉的结论：`retrieve-k` 从 50 调到 200，重排的打分次数不会跟着涨到 4 倍。**

因为送进重排的不是 `retrieve-k`，而是 `rerankPool`（`RagContextBuilder.java:204-217` 的 `min(passed.size(), 3 × injectTopN)`）。**`retrieve-k` 影响的是"有多少候选能进来"，`inject-top-n` 影响的是"进来之后花多少钱"。** 前者调大主要吃的是你自己的 DB，后者调大才吃外部 API。

**所以"省钱"的第一杠杆是 `inject-top-n`，第二杠杆是重排候选池的乘数，第三才是 `retrieve-k`。** 把它们当成三个独立旋钮，而不是一个"质量-成本"总旋钮。

评测开关也要在这里提一句，`application.yml:235-237`：

```yaml
    # 是否记录 ground-truth 召回评估指标（需候选携带 expectedId 元数据）；
    # true 时 RagEvaluator 在 evalEnabled 下计算 precision/recall，配合回归基线。
    eval-enabled: ${RAG_EVAL_ENABLED:true}
```

**这个开关与本讲的在线闭环是同一件事的两端**：第 33 讲指出它"打开着但算不出结果"（生产调用点传 `null`）；而在线闭环（2.3）就是给它喂数据的那条管道。**成本账与质量账共用同一批埋点——埋点是成本，也是资产。**

### 3.2 语义缓存的三个必答参数

`DefaultSemanticCache` 是 spring-ai 里的语义缓存实现，它把三个参数直接写成了构造期常量与运行期配置。逐个看。

**参数一：阈值**（`spring-ai/vector-stores/spring-ai-redis-semantic-cache/src/main/java/org/springframework/ai/vectorstore/redis/cache/semantic/DefaultSemanticCache.java:83`）：

```java
private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.8;
```

**参数一的第二个面：距离型后端的换算**（同文件 `:234-241`）：

```java
double effectiveThreshold = this.similarityThreshold;
...
    effectiveThreshold = 1 - (this.similarityThreshold / 2);
...
        logger.debug("Converting distance threshold " + this.similarityThreshold + " to similarity threshold "
```

**同一个阈值常量，在不同后端下算出不同的比较值。** 这就是"缓存命中"这件事在第 31 讲铁律下的样子：**如果用户配的阈值和实际比较的阈值口径不同，缓存会静默地"该命中不命中"或"不该命中却命中"。**

**参数二：TTL**（同文件 `:223`）：

```java
redisStore.getJedisClient().expire(key, ttl.getSeconds());
```

TTL 是缓存与新鲜度之间唯一的耦合点。**语义缓存的 TTL 必须显式设置，且必须与知识的新鲜度口径一致**——本项目的 `max-age-days=0`（`application.yml:226`）要求知识"当天有效"，这时 TTL 设成"一周"就是在缓存里放假知识。

**参数三：隔离键**——见 3.3。

**这三个参数没有默认值可用。** 阈值 0.8 是库的默认，不是你的业务默认；TTL 与隔离键在样例里可以省，在你这里省不了。

### 3.3 隔离键：把"缓存串味"挡在 key 上

这是本讲唯一一个"做错了就是正确性事故"的地方。

spring-ai 的做法在 `spring-ai/vector-stores/spring-ai-redis-semantic-cache/src/main/java/org/springframework/ai/chat/cache/semantic/SemanticCacheAdvisor.java:122`：

```java
String contextHash = extractContextHash(request);
```

命中查询在 `:125`：

```java
// Check cache first (with context filtering)
Optional<ChatResponse> cached = this.cache.get(userText, contextHash);
```

回填在 `:137`：

```java
this.cache.set(userText, response.chatResponse(), contextHash);
```

**三次调用用的是同一个 `contextHash`，而它来自 system prompt 的 hash。**

**这就是"隔离键"的工业解法：把"答案所依赖的那个不可见上下文"折成一个 hash，塞进缓存 key。** 于是"同一句问话、不同 system prompt"会落到两个不同的缓存条目——**不再是同一个 key 的不同值，而是不同 key。**

**为什么用 system prompt 的 hash，而不是 tenant id？** 因为 system prompt 是"真正决定答案会怎么写"的东西：租户 id 只是它的一种来源。**如果两家租户恰好配了完全相同的 system prompt，那它们的答案本来就可以共享——用 hash 做 key，这个"可以共享"就被自动识别出来了；用 tenant id 做 key，就共享不了。**

**映射到本项目**：上下文构建入口是 `RagContextBuilder.java:181-235`：

```java
public String buildContext(String teamId, String agentType, List<CodeDiff> diffs) {
```

**`teamId` 和 `agentType` 都是它的入参——也就是说，它们都是"答案所依赖的上下文"。** 那么本项目的缓存 key 至少必须包含 `teamId` 与 `agentType`，**而且不能只把它们拼进 SQL 的 WHERE 里**（那是 post-filter，第 34 讲的主题），必须**进缓存 key**。

**这两件事的区别是致命的：**

| 做法 | 隔离点 | 失效方式 |
|---|---|---|
| 只把 `teamId` 拼进检索条件 | 检索层 | 缓存命中会**完全绕过检索层**，隔离失效 |
| 把 `teamId` 放进缓存 key | 缓存层 | 不同 team 落到不同 key，隔离生效 |

**"缓存命中绕过以下所有层"这件事，是缓存隔离必须单独做一遍的原因。** 检索层做得再对，只要缓存 key 里没有租户维，租户隔离就在缓存这一层被旁路了。

**本项目当前没有缓存层**——所以这个坑还没踩上，但也意味着**如果哪天真加缓存，它会是第一个必须被写进 key 的字段**。这也正是把"隔离键"写成本讲独立一节的理由：**它是加缓存这件好事的前置条件，不是加完之后的优化项。**

## 四、避坑清单

- [ ] **成本要按环节拆，不要算一个总数。** 查询侧嵌入 / 检索 / 重排 / 注入 token 各自随不同的乘数涨；算总数只能告诉你"贵了"，算分项才能告诉你"哪一项贵了"。
- [ ] **`retrieve-k` 不是成本总开关。** 真正决定外部 API 花销的是 `inject-top-n`（`application.yml:223`）与重排候选池（`RagContextBuilder.java:204-217`）；`retrieve-k` 主要吃自己的 DB。
- [ ] **缓存不是一个东西，是三层。** 嵌入缓存的 key 是确定的（文本 + 模型 id），语义缓存的 key 是一个阈值判断，结果缓存的 key 是查询 + 参数的 hash——三层的失效条件完全不同，别用一个"清缓存"按钮糊过去。
- [ ] **语义缓存的阈值有口径问题。** 阈值是"相似度"还是"距离"，决定了 `DefaultSemanticCache.java:234-241` 那个 `1 - (阈值 / 2)` 换算是必须的还是多余的。**不确认口径就调阈值，等于蒙着眼睛调。**
- [ ] **TTL 必须与知识新鲜度口径绑定。** 本项目 `max-age-days=0`（`application.yml:226`）要求知识当天有效——此时语义缓存的 TTL 设成"一周"，就是在缓存里过期知识。
- [ ] **缓存 key 里必须有租户维（隔离键）。** 只把 `teamId` 拼进检索 WHERE 是不够的——**缓存命中会绕过整个检索层。** 对照 `SemanticCacheAdvisor.java:122` 的 `contextHash` 做法。
- [ ] **缓存必须是旁路，不能是必经。** 缓存挂了要能直连后端；如果缓存挂了整条链路就挂，它就已经不是缓存，是单点。
- [ ] **隐式信号只能当弱标签。** 曝光偏差决定了"漏召回的块永远没有反馈"，所以隐式信号算出的不是召回率，是"被检索出来的那部分的采纳率"。它的正确去向是下一轮 golden set 的候选。
- [ ] **A/B 的分流单位必须是查询或会话，且样本量要在动手前算出来。** RAG 是带回溯上下文的，按 `userId` 分流会让同一段会话看到两个版本——你测到的是版本切换的噪声；"跑一周看看"不是实验设计，同时还要算人审队列的产能（抽检率是成本决策）。
- [ ] **三类变更的回滚代价不在一个量级。** 参数类是"改回来"（`ef_search` 甚至能按连接灰度，见 `pgvector/src/hnsw.c:95`），索引类是"再建一次"（`CREATE INDEX CONCURRENTLY` 更慢，见 `pgvector/README.md:704`），模型类是"重算一遍"。
- [ ] **模型类变更不可回滚，且量化必须配重排。** 换嵌入模型会作废存量向量，还可能撞上 `HNSW_MAX_DIM 2000`（`pgvector/src/hnsw.h:33`）的硬上限——它必须先解决"新旧向量并存"，再谈灰度；而走 `halfvec` / 二值量化省内存时，`pgvector/README.md:734` 与 `:755-756` 都把 "with re-ranking" 写在一起——**省内存的代价是召回，召回要靠重排还**。
- [ ] **降级的每一级都要有独立可观测标记。** 否则"缓存命中"与"缓存挂了直连"在监控上不可区分，成本上升却查不出原因。

## 五、动手任务

> **任务**：为 `code-review-agent` 写一份 RAG 运营账，覆盖"成本 / 缓存 / 灰度"三件事，并回答本讲开头的三个问题。
>
> **仓库位置**：`code-review-agent`（`src/main/resources/application.yml`、`core/memory/RagContextBuilder.java`、`core/rag/RagEvaluator.java`）
>
> **第一部分：算清五个环节的量级**
> 1. 从 `application.yml` 抄出 `retrieve-k`（`:221`）、`inject-top-n`（`:223`）、重排超时（`:206-207`）、`dim`（`:197`），填入 2.5 那张锚点表。
> 2. 按 2.1 的五条算式，把基座 B 的四条推演**自己复算一遍**，确认每条的乘数来自哪一行配置。
> 3. 找出**唯一与流量无关**的那一项（提示：`dim` 出现的算式）。
>
> **预期结果**：
> - 你能说出"QPS 放大 100 倍时，哪一个成本项先失控"；
> - 你能指出 `retrieve-k` 与 `inject-top-n` 分别落在哪一条算式里。
>
> **第二部分：给缓存定三个参数（纸面设计，不要求落地）**
> 4. 假设要加一层语义缓存，写出它的三个参数：阈值、TTL、隔离键；隔离键至少包含 `teamId` 与 `agentType`（`RagContextBuilder.java:181-235` 的两个入参）。
> 5. 对照 `DefaultSemanticCache.java:83`（阈值 0.8）与 `:234-241`（距离换算），说明你选的阈值是**相似度口径还是距离口径**。
>
> **预期结果**：
> - 你能说清"缓存命中会绕过检索层，所以租户隔离必须在缓存 key 里再做一遍"；
> - 你能指出本项目当前的租户过滤（`KnowledgeStore.java:12-16`）为什么不能直接当缓存隔离用。
>
> **第三部分：为三类变更各写一行回滚预案**
> 6. 参数类（`ef_search` / `min-similarity` / `retrieve-k`）：写出回滚动作与预期耗时量级。
> 7. 索引类（HNSW 参数 / `lists` / `probes`）：写出重建动作，并对照 `pgvector/README.md:291` / `:309` / `:704` 说明为什么它比参数类慢。
> 8. 模型类：对照 `pgvector/src/hnsw.h:33`（`HNSW_MAX_DIM 2000`）与 `pgvector/CHANGELOG.md:68` / `:70`（0.7.0 的 `halfvec`），说明"换 3072 维模型"时索引会怎样。
>
> **预期结果**：三行预案的"回滚耗时"应当呈**阶梯式放大**——参数类秒级、索引类到小时级、模型类到天级；如果三行写成差不多的耗时，说明你还没把"改配置 / 重建索引 / 重算向量"这三件事分开。

---

## 本讲小结

1. **RAG 的成本有五个环节，但只有四个跟流量走**：查询侧嵌入、检索、重排、注入 token。存储是唯一与流量解耦的一项，它只随语料量涨。**算总数只能知道"贵了"，算分项才知道"哪里贵了"。**
2. **省钱的杠杆顺序是 `inject-top-n` → 重排候选池 → `retrieve-k`。** 前两个直接进外部 API 的调用量，第三个主要吃自己的 DB。
3. **缓存是三层，不是一个东西。** 嵌入缓存的 key 是确定的；语义缓存的 key 是一个阈值判断（默认 0.8，见 `DefaultSemanticCache.java:83`），于是它继承了第 31 讲的 similarity 口径问题；结果缓存的 key 是查询 + 参数的 hash。
4. **隔离键是加缓存的前置条件，不是优化项。** spring-ai 用 system prompt 的 hash 做 `contextHash`（`SemanticCacheAdvisor.java:122`）。**缓存命中会绕过整个检索层——所以租户隔离必须在缓存 key 里再做一遍，否则检索层做得再对也会在这里被旁路。**
5. **在线闭环的三段各有边界**：隐式信号只能当弱标签（曝光偏差）、A/B 必须按查询 / 会话分流、人审是最终裁判。样本量与抽检率都要**动手前算出来**。
6. **三类变更的回滚代价是阶梯，不是同一件事。** 参数类改配置即回（`ef_search` 甚至能按连接灰度）；索引类要再建一次（`CREATE INDEX CONCURRENTLY` 更慢）；**模型类不可回滚**——它作废存量向量，还可能撞上 `HNSW_MAX_DIM 2000`，是架构迁移而非一次发布。

---

## 模块四小结：知识这条链路

模块四到这里结束。我们从"RAG 全景"一路走到"把 RAG 当生产系统运营"，把知识那条链路完整铺开了：

| 讲 | 环节 | 核心结论 |
|---|---|---|
| 26 |
| 27 |
| 28 |
| 29 |
| 30 |
| 31 |
| 32 |
| 33 |
| 34 |
| 35 |

**如果你只能从模块四带走三件事：**

1. **RAG 的失败是静默的**——它不报错，只是"效果差"。所以**每一个环节都必须留下能看见它失败的信号**（漏斗日志、`parentExcerpt` 命中率、每路命中数、`NaN` 与 `0` 的区分、索引空洞与并发删除的计数）。
2. **"名字承诺 > 实现"是本书出现频率最高的缺陷模式**（模块四就抓到 4 次：`BM25` 实为 `ts_rank`、`Jaccard` 用了 `max` 分母、`hitCount` 实为放行数、`RagTrace` 从未接线）。**读代码时，凡遇到一个"名字很自信"的字段/注释/类名，就去核对它的实现。**
3. **很多 RAG 问题的根因在语料，不在代码**——手册 H1 下面没有正文，就让 `small-to-big` 全程空转；同理，语料的租户分布决定了 post-filter 会打几折。

模块四到这里真正收口：**第 26–35 讲把知识这条链路的质量做通了，最后两讲把它变成了一门可运营的生意——算得清成本、兜得住租户、测得准上线、退得回来。**

**从第 36 讲开始，我们进入模块五：工程化。** 到这里，`code-review-agent` 已经是一个"功能完整"的系统了——多 Agent 协作、RAG、评测，一样不缺。

**但它还不能交给团队用。** 因为还缺四样东西：**把 LLM 变成团队基建的网关、成本与配额的可控、提示注入的防御、以及"出事时能查"的可观测性。**

那就是模块五。
