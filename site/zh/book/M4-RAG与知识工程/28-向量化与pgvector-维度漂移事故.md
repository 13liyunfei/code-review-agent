# 第 28 讲 · 向量化与 pgvector：一个默认值不一致，如何作废整张表

> 🎯 导读问题：**"你们的向量库怎么建的？换嵌入模型怎么办？"** ——这一讲给你一个能讲 5 分钟的事故复盘。
> 🔬 本讲涉及的 pgvector 行为在 **PostgreSQL 17.11 + pgvector 0.8.6** 上验证。

<img class="mermaid-svg" src="/zh/book-assets/diag-0122.svg" alt="🔬 本讲涉及的 pgvector 行为在 PostgreSQL 17.11 + pgvector 0.8.6 上验证。" />

> **图 28-0**　本讲地图：向量维度是模型、列、配置项的三方契约，必须有单一事实来源。两个默认值 1024 与 256 不一致，改配置就触发自动迁移，存量向量全部作废，日志却只有一行 WARN。

## 一、痛点

为了做离线调试，我在配置里关掉了一个开关：

```yaml
review:
  llm:
    embedding:
      enabled: false     # 关掉真实嵌入，用离线哈希兜底
```

理由很正当：**离线环境没有外网，每次都要等嵌入 API 超时太慢。**

应用启动了。日志里有一行 WARN，我没细看。审查照常跑。

第二天我提交了一个 PR，想验证知识库检索。**一条知识都没命中。**

我打开数据库看：

```sql
SELECT count(*) FROM memory_store WHERE agent_type = 'RAG';
```

```
 count
-------
     0
```

**知识库是空的。** 不是"检索不准"，是**一条都没有**。

我往上翻启动日志，找到了那行被我忽略的 WARN：

```
[KB] 知识库预灌入失败，已跳过（应用继续启动）：...
```

**"已跳过"三个字，把一个致命故障降级成了一条 WARN。** 而真正的报错被它包在里面——`ERROR: expected 1024 dimensions, not 256`。

**这就是本讲的核心：向量维度是一个三方契约，而三方各自都能独立变化。**

## 二、原理

### 2.1 向量维度：一个必须有"单一事实来源"的三方契约

一个 float 向量要成功落库，需要三个地方**对同一个数字达成一致**：

| 来源 | 在哪 | 谁改它 |
|---|---|---|
| **① 嵌入模型的输出维度** | 外部服务（或 `SimpleHashEmbeddingClient` 的常量） | 换模型 / 换开关 |
| **② 列的声明维度** | DDL：`embedding vector(N)` | 首次建表；之后只能重建 |
| **③ 配置项** | `pgvector.vector-dim` | 改配置文件 |

**三者关系是"配置驱动 ②，模型决定 ①，而 ① 必须等于 ②"。**

它之所以容易出事，是因为**三个来源之间没有任何自动校验**：

- ① 是外部服务的返回值——**代码里没人检查它的长度**；
- ② 是数据库里的既定事实——**改了就得重建列**；
- ③ 是配置——**改起来最便宜，所以最容易被随手改**。

**最便宜的那个，就成了事故的起点。**

### 2.2 两个失败方向，一个比一个疼

```
方向 A：配置说 1024，模型给 256
  → INSERT ... ?::vector 报错：expected 1024 dimensions, not 256
  → 写入路径抛异常 → 启动期灌库被 try/catch 吞掉 → 知识库为空

方向 B：配置从 256 改成 1024（或反之），而列已经是旧维度
  → 启动时检测到 curDim != vectorDim
  → 备份旧表 → DROP COLUMN embedding → ADD COLUMN embedding vector(新维度)
  → 存量向量全部作废
```

**方向 B 是"数据事故"，方向 A 是"功能事故"。** 但真正致命的是**它们的表现形态**：

- A 的表现是"知识库空的"，但**应用健康检查通过**；
- B 的表现是"经验/知识检索变差"，但**应用正常启动、日志只有一行 WARN**。

### 2.3 为什么"自动迁移"是个陷阱

很多实现（包括本项目）会选择"**检测到维度不一致就自动重建**"。这个设计的动机是好的：**让运维不必手工改 DDL。**

但它的代价是：**把一个"需要人来决策"的事，变成了"机器静默执行"。**

`PgVectorMemoryStore.migrate` 的日志写得很清楚（`:381-382`）：

```java
log.warn("[PgVector] 迁移：embedding 维度 {} != 期望 {}，已备份旧数据至 {}，" +
        "重建向量列（旧向量与新嵌入模型不兼容，作废）", curDim, vectorDim, bak);
```

**"作废"这个词是作者自己写的。** 一个 WARN 级别的日志，带着"数据作废"的后果——**这个级别是不匹配的。**

### 2.4 为什么"哈希嵌入"是维度的隐形地雷

本项目有一个离线兜底嵌入实现，它的维度是**硬编码常量**：

```java
// core/llm/SimpleHashEmbeddingClient.java:18
private static final int DIM = 256;
```

而真实模型的维度是 **1024**（`application.yml:197` 的 `dim: 1024`）。

**两个维度并存，靠一个开关切换：**

```java
// config/ReviewAgentConfig.java:275-296
public EmbeddingClient embeddingClient(@Value("${review.llm.embedding.enabled:true}") boolean enabled, ...) {
    ...
    return new LangChain4jEmbeddingClient(em);       // 1024 维（由模型决定）
    ...
    log.warn("未启用 LangChain4j 向量化，回退 SimpleHashEmbeddingClient（离线哈希嵌入，dim=256）");
    return new SimpleHashEmbeddingClient();          // 256 维（硬编码）
}
```

**注意这段日志本身是诚实的**——它明确写了 `dim=256`。问题在于：**它只说了一半。** 它没有告诉你"而你数据库里的列是 1024"。**两个数字在两条日志里，需要人在脑子里做减法。**

### 2.5 索引：类型变了也要重建

pgvector 的 ANN 索引有两种，本项目的选择是：

| 索引 | 特点 | 本项目 |
|---|---|---|
| **HNSW** | pgvector ≥ 0.5；召回精度与构建速度更优；**无维度上限** | **默认** |
| **ivfflat** | 老版本兼容；**有 2000 维上限**；需要训练 | 回退路径 |

**关键点：索引与列绑定，列重建了索引也必须重建。** 而且**索引类型切换也要重建**（`ivfflat → hnsw` 需要 `DROP` 再 `CREATE`）。

`application.yml:169-170` 注释里提到 ivfflat 的 2000 维上限：

```yaml
  # 默认用 0.6b：成本/延迟优先（hnsw 无 ivfflat 的 2000 维上限，4b=2560 维亦可建 hnsw 索引，
```

**这句话意味着：如果你为了"兼容老 pgvector"切到 ivfflat，那 2560 维的模型就用不了了。** 索引类型的选型**不是性能偏好，它反过来约束了你能选哪个嵌入模型**。

### 2.6 内存墙：1024 维到底吃多少内存

前面几节只关心"维度对不对"，没算过"维度要花多少钱"。先把账列出来。一行向量的开销不是一份，是三份：

<img class="mermaid-svg" src="/zh/book-assets/diag-0123.svg" alt="前面几节只关心&quot;维度对不对&quot;，没算过&quot;维度要花多少钱&quot;。先把账列出来。一行向量的开销不是一份，是三份：" />

> **图 28-1**　一行向量的内存由三部分构成：向量本体（维度 × 4 字节）、HNSW 图的 m 个邻居引用、以及元数据与 tsvector 列。

**向量本体是一道乘法**：`条数 × 维度 × 字节数`。`vector` 是单精度浮点，每维 4 字节，所以 1024 维的一条 = `1024 × 4 = 4096` 字节 = **4 KB**。

**但 HNSW 不只存向量。** 图里每个节点还要存 `m` 个邻居引用。`m` 的默认值与边界都写死在源码里：

```c
// pgvector/src/hnsw.h:54
#define HNSW_DEFAULT_M	16
```

```c
// pgvector/src/hnsw.h:55-56
#define HNSW_MIN_M	2
#define HNSW_MAX_M		100
```

**这三行合起来意味着什么**：`m` 是一个 **2 到 100 之间的整数档位**，不是一个"越大越好"的连续旋钮。它与两个 `ef` 参数一样有硬上限：

```c
// pgvector/src/hnsw.h:59 / :62
#define HNSW_MAX_EF_CONSTRUCTION		1000
#define HNSW_MAX_EF_SEARCH		1000
```

`ef_construction` 默认 64（`pgvector/src/hnsw.h:57`），封顶 1000；`ef_search` 同样封顶 1000。**建索引前就该知道你要的值在不在区间内**——超出区间的"调参"根本不会生效。

本项目用的维度是 **1024**（`application.yml:197` 的 `dim: 1024`）。把它代进乘法：

| 项 | 算式 | 结果 |
|---|---|---|
| 向量本体 | `条数 × 1024 × 4` 字节 | 每条 4 KB |
| HNSW 邻居引用 | `条数 × m × 4` 字节 | 每条 64 B（`m=16`） |
| 元数据与 `tsvector` | — | 与向量列分开，各自有索引 |

**所以内存墙的分叉点不在"用不用向量库"，而在知识库规模。** 把两个基座放进同一张表：

| 维度 | A：代码审查助手 | B：千万 DAU 客服助手 | 由此分叉的设计决策 |
|---|---|---|---|
| 知识库条数 | 手册 24 行切出 5 个 chunk | 推演 10^7 条 chunk | 数量级决定"能不能全靠内存" |
| 向量本体 | `5 × 1024 × 4 B` ≈ 20 KB | `10^7 × 1024 × 4 B` ≈ 40.96 GB | A 不用管；B 必须量化或分片 |
| HNSW 邻居引用 | `5 × 16 × 4 B` = 320 B | `10^7 × 16 × 4 B` ≈ 640 MB | `m` 在 A 无感，在 B 是内存变量 |
| 内容是否变化 | 低频（规范变更） | 高频（商品/政策随时改） | B 的重切与重算成本按天计 |
| P99 敏感度 | 近乎无感 | 卡在单机内存这条线上 | A 关注正确性，B 关注成本 |

> **推演**：`10^7 × 1024 × 4 = 4.096 × 10^10` 字节 ≈ 40.96 GB（假设 B 基座商品与政策文档切块后约 10^7 条、每条 1024 维；基座值 1024 维见 `application.yml:197`）

> **推演**：`10^7 × 16 × 4 = 6.4 × 10^8` 字节 ≈ 640 MB（假设 HNSW 每条存 `m=16` 个邻居引用、每个引用 4 字节；基座值 `m=16` 见 `pgvector/src/hnsw.h:54`）

**两个基座的知识库条数相差约 6 个数量级（5 → 10^7）**——`retrieve-k`、`m`、`ef_search` 这类参数在 A 基座怎么调都无所谓，在 B 基座每一个都是成本项。

### 2.7 三种降内存手段的取舍

1024 维的 4 KB/条是"原价"。要降，pgvector 自己把选择顺序写在 README 里：

```text
// pgvector/README.md:755-756
1. Use the `halfvec` type instead of `vector` for tables
2. Use binary quantization for indexes (with re-ranking for search)
```

第二条的括号不是修饰语，是**条件**。检索侧那条更直白：

```text
// pgvector/README.md:734
Use binary quantization with re-ranking to keep indexes in-memory at scale.
```

**"with re-ranking"是连在一起的**——量化省下的是内存，赔出去的是召回精度，而重排是把精度买回来的手段。这就把本讲的"维度"和第 32 讲的"重排"接上了： **"没有重排，就不要做量化。"**

第三条路不在 pgvector 里，而在嵌入模型侧：**把维度直接裁掉**。

<img class="mermaid-svg" src="/zh/book-assets/diag-0124.svg" alt="第三条路不在 pgvector 里，而在嵌入模型侧：把维度直接裁掉。" />

> **图 28-2**　降内存的三条路：halfvec 把每维压到 2 字节、二值量化压到 1 bit、Matryoshka 直接裁维度——前两条要付重排成本，第三条要换模型。

`halfvec` 是 0.7.0 才有的类型：

```text
// pgvector/CHANGELOG.md:68 / :70
## 0.7.0 (2024-04-29)

- Added `halfvec` type
```

三条路的账：

| 手段 | 每维 | 1024 维每条 | 类型维度上限 | 主要代价 |
|---|---|---|---|---|
| `vector`（现状） | 4 字节 | 4 KB | 2000 | — |
| `halfvec` | 2 字节 | 2 KB | 4000 | 精度损失，需实测召回 |
| 二值量化（`bit`） | 1 bit | 128 B | 64000 | **必须配重排** |
| `sparsevec` | — | — | 1000 个非零 | 只适合稀疏数据 |

上限一栏逐字来自 `pgvector/README.md:252-255`：`vector` 2000 维、`halfvec` 4000 维、`bit` 64000 维、`sparsevec` 1000 个非零元素。源码侧与它对得上——HNSW 的维度上限是多档的：

```c
// pgvector/src/hnsw.h:33-34
#define HNSW_MAX_DIM 2000
#define HNSW_MAX_NNZ 1000
```

**这里藏着一个会直接锁死模型选型的事实**：`HNSW_MAX_DIM 2000` 意味着 **`vector` 类型的 HNSW 索引上限是 2000 维**。本项目 1024 维安全；但换成 3072 维的模型（OpenAI `text-embedding-3-large`）就**建不出 HNSW 索引**，必须转 `halfvec`（上限 4000 维）。**维度不是一个可以随手改的配置项，它决定你能不能用索引。**

第三条路（裁维度）的学术来源是 Matryoshka Representation Learning：训练出的向量可以截断成前 N 维近似使用，于是**同一份向量能裁成多档**（256 / 512 / 1024 共用一个模型）——https://arxiv.org/abs/2205.13147。代价是必须换模型，现有的 1024 维模型不支持这种截断。

量化的学术来源是各向异性量化（Anisotropic Vector Quantization，ScaNN，https://arxiv.org/abs/1908.10396）；二值量化 + 重排的工程实测见 https://qdrant.tech/articles/binary-quantization/ —— 与 `pgvector/README.md:734` 的 **"量化必须配重排"** 互证。

**对 A 基座来说，这段整节可以跳过**（5 条 = 20 KB，问题不存在）；**对 B 基座来说，这三条路是必答题**——40.96 GB 的向量本体装不进单机内存，要么压精度、要么裁维度、要么分片。选哪条，取决于你的重排层值不值那个钱。

### 2.8 一手数据与搬到你自己系统：维度契约的三处校验

2.1 把维度问题定义成"三方契约"（模型输出 / 列声明 / 配置项）。**契约要能执行，必须在每一方各设一个校验点**——只有一处检查，等于另外两方裸奔：

| 校验点 | 在哪一层 | 查什么 | 本项目位置 |
|---|---|---|---|
| ① 写入前断言 | 代码 | 嵌入返回值长度 = 期望维度 | 本讲避坑清单第 2 条 |
| ② 启动期核对 | SQL | 列声明的维度 = 配置维度 | `PgVectorMemoryStore.java:340-352`（读 `atttypmod`） |
| ③ 配置层收敛 | 配置 | 同一属性只有一个默认值 | 本讲 §3.1 发现的两个默认值（1024 / 256） |

第二处校验（读 `atttypmod`）是本书的做法。spring-ai 对同一个问题给的是**另一种解法**：

```java
// spring-ai/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorStore.java:530-534
private void validateTableSchemaIfEnabled() {
    if (this.schemaValidation) {
        this.schemaValidator.validateTableSchema(this.getSchemaName(), this.getVectorTableName(), this.dimensions);
    }
}
```

它的校验基准不是"从系统目录里读出实际维度"，而是**一份期望的 DDL / 索引文本**（`spring-ai/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorSchemaValidator.java:175` 期望 DDL、`:181` 期望索引），拿它去与实际 schema 比对。

<img class="mermaid-svg" src="/zh/book-assets/diag-0125.svg" alt="它的校验基准不是&quot;从系统目录里读出实际维度&quot;，而是一份期望的 DDL / 索引文本（`spring-ai/vector-stores/spring-ai-pgvector-store/src/main/java/org/springframework/ai/vectorstore/pgvector/PgVectorSchemaValidator.java:175` 期望 DDL、`:181` 期望索引），拿它去与实际 schema 比对。" />

> **图 28-3**　三方契约要在三处各设一个校验点：写入前断言向量长度、启动期读 atttypmod 核对列声明、配置层保证默认值唯一——三处都过才允许启动。

**同题不同解**：

| | 本书（code-review-agent） | spring-ai |
|---|---|---|
| 校验时机 | 启动迁移前 | 启动期（`validateTableSchemaIfEnabled`） |
| 比对基准 | **实际值**：`atttypmod` 读出的列维度 | **期望值**：拼好的期望 DDL / 索引文本 |
| 对"人工改过表"的态度 | 事后发现差异，再决策 | 事前就假定表应等于期望文本 |
| 触发开关 | 无（每次都检测） | `schemaValidation` 布尔开关 |

两者的区别一句话概括：**本项目问"表现在是什么"，spring-ai 问"表应该是什么"。** 前者容忍历史遗留 schema，后者对"有人手工 `ALTER` 过"零容忍。选哪种取决于你的表是"代码建的"还是"人工建的"——A 基座单团队单库、表由代码建，事后检测够用；B 基座多租户分片、运维随时可能手工改表，就需要一份事前基准。

| 工业界/本项目做法 | 出处 | 你自己系统该问的问题 |
|---|---|---|
| 读系统目录核对列维度 | `PgVectorMemoryStore.java:340-352` | 你的表维度是"代码建的"还是"人工 `ALTER` 过"？ |
| 启动期拿期望 DDL 比对 | `PgVectorStore.java:530-534`、`PgVectorSchemaValidator.java:175` / `:181` | 你有没有一份可作为基准的"期望 schema"？ |
| 类型维度上限 2000 维 | `pgvector/src/hnsw.h:33` | 你选的模型维度离这条线还有多远？ |
| 量化前先接重排 | `pgvector/README.md:734`、`:755-756` | 你有没有重排这一层，够不够接住量化？ |
| 向量本体 + 邻居引用的内存账 | `pgvector/src/hnsw.h:54` | 你算过 `条数 × 维度 × 4` 吗？ |

**搬到你自己系统的五步：**

1. **把 `@Value` 的默认值收敛。** 全仓 `grep` 读取 `pgvector.vector-dim` 的每一处，只留一个默认值；其余改为无默认值（缺失即启动失败）。
2. **写入前加断言。** 嵌入客户端返回后立刻 `if (embedding.length != dim) throw ...`，并删掉零向量兜底（见 §3.6）。
3. **把"检测"与"重建"拆开。** `atttypmod` 检测照旧，但检测到不一致时默认**拒绝启动**，重建要显式开关。
4. **补一条 CI 断言。** 扫描 DDL 里的 `vector(N)` 与配置里的维度，不相等就 fail——这是唯一能在合并前拦住漂移的地方。
5. **要量化就先接重排。** 若内存账超了，先落重排（第 32 讲），再考虑 `halfvec` / 二值量化。

**★ 判据：三处校验里只要有一处是"只能事后从日志里发现"，这一处就等于不存在——维度契约的验收标准是"不一致时启动失败"，不是"日志里多一行 WARN"。**

## 三、代码

### 3.1 同一个属性，两个默认值（本讲发现的真实不一致）

这是本讲最"干净"的一个缺陷，因为它只需要对比两行代码。

**向量存储（写入方）的 Bean**：

```java
// config/InfrastructureConfig.java:41-53
@Bean
@ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
public MemoryStore pgVectorMemoryStore(EmbeddingClient embeddingClient,
                                       @Value("${pgvector.host:localhost}") String host,
                                       ...
                                       @Value("${pgvector.vector-dim:1024}") int vectorDim,   // ← 默认 1024
                                       @Value("${pgvector.index-type:hnsw}") String indexType) {
    log.info("已启用 PgVector 记忆存储（{}:{}/{}, dim={}, ANN 索引={}）", host, port, database, vectorDim, indexType);
    return new PgVectorMemoryStore(embeddingClient, host, port, database, username, password, vectorDim, indexType);
}
```

**知识库（检索方）的 Bean**：

```java
// config/InfrastructureConfig.java:76-89
@Bean
@ConditionalOnProperty(name = "pgvector.enabled", havingValue = "true")
public KnowledgeStore pgKnowledgeStore(EmbeddingClient embeddingClient,
                                       MemoryStore memoryStore,
                                       ...
                                       @Value("${pgvector.vector-dim:256}") int vectorDim) {   // ← 默认 256
    log.info("已启用 PgKnowledgeStore（混合检索：向量+BM25+RRF，{}:{}/{}, dim={}）",
            host, port, database, vectorDim);
    return new PgKnowledgeStore(embeddingClient, memoryStore, host, port, database, username, password, vectorDim);
}
```

**同一个属性 `pgvector.vector-dim`，一个默认 `1024`，一个默认 `256`。**

这在 `application.yml` 里定义了该属性的情况下不会暴露（`:105` 明确写了 `1024`）。但**一旦配置缺失**（新 profile、精简配置、测试上下文），两个 Bean 就会对同一个数字产生**不同理解**——而它们**物理共享同一张 `memory_store` 表**（`KnowledgeStore.java:20-24`）。

**更麻烦的是：`PgKnowledgeStore` 的 `vectorDim` 参数其实没有任何作用。**

```java
// core/rag/PgKnowledgeStore.java:75-87
public PgKnowledgeStore(EmbeddingClient embeddingClient, MemoryStore writer,
                        String host, int port, String database,
                        String username, String password, int vectorDim) {
    this(embeddingClient, writer, host, port, database, username, password, vectorDim, 0.7);
}

public PgKnowledgeStore(EmbeddingClient embeddingClient, MemoryStore writer,
                        String host, int port, String database,
                        String username, String password, int vectorDim,
                        double denseWeight) {
    this.embeddingClient = embeddingClient;
    this.writer = writer;
    this.denseWeight = denseWeight;
    // ← vectorDim 在此之后不再出现：没有 this.vectorDim = vectorDim
    ...
}
```

**它被接住了，然后被扔掉了。** 类注释（`:37-38`）解释了为什么：

```java
 * <p><b>写入复用</b>：通过构造注入的 {@link MemoryStore}（即 {@code PgVectorMemoryStore} 实例）
 * 完成落库——共享其连接池、建表迁移与 {@code search_vector} 维护，<b>不另起写入连接</b>。
```

**表与向量列都由写入器负责**——所以检索方不需要知道维度。这个架构决策是对的（单一写入方 = 单一 DDL 事实来源）。

**但后果是：那行 `dim={}` 日志是假信息。**

```java
log.info("已启用 PgKnowledgeStore（混合检索：向量+BM25+RRF，{}:{}/{}, dim={}）",
        host, port, database, vectorDim);     // ← 打印一个从未被使用的参数
```

**这就是"死参数"的危害形态**：它没有功能影响，但它**在生产日志里输出一个错误的事实**。运维看到 `dim=256`，就会去把嵌入模型配成 256 维——然后撞上方向 A 的报错。

**修法**（两条都不复杂）：

```java
// 修法一：删掉死参数（推荐——表归写入方管，检索方不该有这个参数）
public PgKnowledgeStore(EmbeddingClient embeddingClient, MemoryStore writer, ...) { ... }

// 修法二：如果确实要保留用于日志/断言，就从写入方取，而不是自己再读一次配置
// 让 PgVectorMemoryStore 暴露一个 `int dim()`，检索方读它——单一事实来源
```

**判断标准很简单：一个参数，要么被使用，要么被删除。** 不要让它只是"被接受"。

### 3.2 建表：维度和 tsvector 一起定义

```java
// core/memory/PgVectorMemoryStore.java:354-368
private void createTable(Statement stmt) throws SQLException {
    stmt.execute("""
            CREATE TABLE IF NOT EXISTS memory_store (
                id        BIGSERIAL PRIMARY KEY,
                agent_type VARCHAR(100),
                team_id    VARCHAR(100) NOT NULL DEFAULT '__global__',
                content   TEXT NOT NULL,
                metadata  JSONB DEFAULT '{}',
                level     VARCHAR(20) NOT NULL,
                created_at TIMESTAMPTZ DEFAULT now(),
                embedding vector(%d),
                search_vector tsvector
            )
            """.formatted(vectorDim));
    log.info("[PgVector] 已创建 memory_store 表（vector({}) + tsvector）", vectorDim);
}
```

两个设计值得注意：

**① `vector(%d)` 用 `formatted` 拼进去——这是 DDL，维度不能参数化。** 所以 `vectorDim` 必须是**启动期常量**，改它 = 改 schema。

**② 新表一次性带上了 `search_vector tsvector`。** 而存量表在 `init()` 里通过 `ALTER TABLE ... ADD COLUMN` 补（`:159-162`）——**同一个 schema 有"新建"和"迁移"两条路径**，这在后面会变成"两种表结构可能是不同的"这类事故的温床。

**③ `team_id` 有 `NOT NULL DEFAULT '__global__'`。** 第 24 讲讲过的三层租户模型，在 DDL 层就被固化了——**连"忘记传 team"都会落到全局基线，而不是 NULL**（后面会讲这个默认值的风险）。

### 3.3 维度漂移检测：读 `atttypmod`

```java
// core/memory/PgVectorMemoryStore.java:340-352
private int embeddingDim(Connection conn) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("""
            SELECT a.atttypmod
            FROM pg_attribute a
            JOIN pg_class c ON c.oid = a.attrelid
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relname = 'memory_store' AND n.nspname = 'public' AND a.attname = 'embedding'
            """)) {
        try (ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }
}
```

**这是本讲最值得学的一段代码**——它用 PostgreSQL 的系统目录直接问出了"这一列声明的维度是多少"：

- `pg_attribute.atttypmod` 对 `vector(N)` 存的就是 **N**；
- 列不存在 → 查询无结果 → 返回 **`-1`**（三态：`-1` = 列不存在，`>0` = 当前维度）。

**为什么返回 `-1` 而不是 0**：因为 0 是一个合法的"无意义值"，用它区分"列不存在"会含糊。**三态表意清晰，是这段代码最讲究的地方。**

### 3.4 自动迁移：备份、丢弃、重建

```java
// core/memory/PgVectorMemoryStore.java:371-390
private void migrate(Connection conn, Statement stmt) throws SQLException {
    if (!columnExists(conn, "team_id")) {
        stmt.execute("ALTER TABLE memory_store ADD COLUMN team_id VARCHAR(100) NOT NULL DEFAULT '__global__'");
        log.info("[PgVector] 迁移：已为 memory_store 补充 team_id 列（多租户兼容）");
    }
    int curDim = embeddingDim(conn);
    if (curDim > 0 && curDim != vectorDim) {
        String bak = "memory_store_bak_" + Instant.now().getEpochSecond();
        stmt.execute("CREATE TABLE " + bak + " AS SELECT * FROM memory_store");
        log.warn("[PgVector] 迁移：embedding 维度 {} != 期望 {}，已备份旧数据至 {}，" +
                "重建向量列（旧向量与新嵌入模型不兼容，作废）", curDim, vectorDim, bak);
        stmt.execute("DROP INDEX IF EXISTS idx_memory_embedding");
        stmt.execute("ALTER TABLE memory_store DROP COLUMN IF EXISTS embedding");
        stmt.execute("ALTER TABLE memory_store ADD COLUMN embedding vector(" + vectorDim + ")");
    } else if (curDim < 0) {
        stmt.execute("ALTER TABLE memory_store ADD COLUMN embedding vector(" + vectorDim + ")");
        log.info("[PgVector] 迁移：已为 memory_store 补充 embedding 列（vector({})）", vectorDim);
    }
}
```

**逐句读这个"作废"过程：**

| 步骤 | 效果 |
|---|---|
| `CREATE TABLE bak AS SELECT *` | 全表快照（**包含向量，但新表里向量是旧维度、无法用于检索**） |
| `DROP INDEX IF EXISTS idx_memory_embedding` | 索引先删（`DROP COLUMN` 会连带删，这里是显式） |
| `ALTER TABLE ... DROP COLUMN embedding` | **向量列消失，全部向量数据丢失** |
| `ALTER TABLE ... ADD COLUMN embedding vector(N)` | 新列，全 NULL |

**这不是"迁移"，这是"重建 + 归档"。** 名字叫 `migrate` 会让人误以为"数据还在"。

**三个必须知道的事实：**

1. **文本数据仍在**——`content` / `metadata` / `team_id` 没动。但**没有向量就检索不到**（`ORDER BY embedding <=> ?` 里 NULL 排序行为未定义），等于知识失效。
2. **备份表永不清理**。表名是 `memory_store_bak_<epoch秒>`——**每次维度变化都生成一张新表**。反复切换 dev/prod 配置会攒出一堆僵尸表。**没有任何代码会删它们。**
3. **`team_id` 的默认值会掩盖"忘记传团队"**。存量迁移用 `DEFAULT '__global__'` 补列——所有历史行的团队归属**全部变成全局基线**。这在多租户场景下是**数据泄露方向**的问题（第 24 讲讲过的隔离模型被 DDL 默认值抹平）。

### 3.5 索引维护：类型不一致也要重建

```java
// core/memory/PgVectorMemoryStore.java:177-187（注释与节选）
/**
 * 确保向量 ANN 索引存在且为期望类型（hnsw / ivfflat），幂等。
 * ...
 *   <li>索引不存在 → 直接按期望类型创建（hnsw 失败则 WARN 回退 ivfflat，老 pgvector 无 hnsw）；</li>
 *   <li>存量库为 ivfflat 而期望 hnsw → DROP 后重建迁移（启动时一次性，数据量小时可接受；
 *       数据量大时仍会执行——hnsw 在 pgvector 0.5+ 均可用，迁移收益明确）；</li>
 * ...
 */
```

**注意那句自我坦白**：

> "**数据量大时仍会执行**"

——即**没有"数据量大就跳过"的保护**。对比一下同一个类里 `migrateTsvectorTokens` 的做法（`:240-297`）：它有一个 `MAX_MIGRATE_ROWS = 100_000` 的上限，超过就 WARN 跳过。

**同一个类里，两种迁移策略：**

| 迁移 | 数据量大时 |
|---|---|
| `search_vector` 分词重建 | **跳过**（有行数上限），提示手工分批 |
| `ivfflat → hnsw` 索引重建 | **照做**（无上限） |

**索引重建要锁表 + 全量扫描**，在大表上可能把启动拖到超时。**同一个文件里两种态度，说明"要不要为大表做保护"这个问题没有统一结论**——这是评审时应该问出口的问题。

### 3.6 一个"写入即死"的兜底：零向量

```java
// core/memory/PgVectorMemoryStore.java:399-406
float[] embedding = entry.embedding();
if (embedding == null || embedding.length == 0) {
    embedding = embeddingClient.embed(entry.content());
}
if (embedding == null || embedding.length == 0) {
    embedding = zeros();                       // ← new float[vectorDim]，全 0
}
```

兜底的用意是好的（"`[]::vector` 会报语法错，用零向量避免崩溃"），但**后果是写入一行永远不可能被召回的记录**：

- 零向量与任何查询向量的余弦 = 未定义（分母为 0）；
- `similarity` 解析出来是 **`NaN`**；
- `RagEvaluator.filterByThreshold` 的判据是 `sim >= minSimilarity` —— **`NaN >= 0.3` 恒为 `false`**；
- 于是这行**永远被拦截**，`rejected++`。

**所以"兜底不崩"换来的是"静默存了一条死数据"。** 更好的做法是**直接拒绝写入并告警**——因为向量化失败本身就是需要人知道的事件（第 26 讲：一个增强项失败，必须留下"能看见它失败了"的信号）。

**顺带说明一个"看起来能救命、实际不能"的点**：`zeros()` 返回的是 `new float[vectorDim]`——**配置里的维度，不是模型的实际输出维度**。所以如果模型返回了 `[]`，兜底给的是 1024 长度的全零向量，**它长度是"对"的，所以 PG 不报错**，于是这行"合法地"进了库。**维度校验被兜底绕过了。**

## 四、避坑清单

- [ ] **向量维度必须有单一事实来源。** 三个来源（模型输出 / 列声明 / 配置项）里选一个作为权威，另外两个由它推导或校验它——**不要三个都独立可改**。
- [ ] **写入前校验 `embedding.length == 期望维度`，不匹配就抛异常。** 别把校验完全交给数据库——数据库报错会发生在**启动期灌库的 `try/catch` 里**，最终表现是"知识库为空"。
- [ ] **同一属性禁止有两个默认值。** `InfrastructureConfig:49` 是 `1024`、`:85` 是 `256`——`grep` 一遍所有读这个属性的 `@Value`，默认值不一致就是隐患。
- [ ] **"接了但没用的参数"要么用、要么删。** 死参数的最大危害不是浪费，是**在日志里输出一个错误的事实**（`dim={}` 打了一个从未被使用的值）。
- [ ] **维度自动重建必须升级日志级别并需要显式开关。** "数据作废"不该是一条启动期 WARN；至少 `ERROR` + 一个 `pgvector.auto-migrate-dim=false` 的默认关闭开关（宁可启动失败，不要静默作废）。
- [ ] **备份表要有清理策略。** `memory_store_bak_<epoch>` 会随着每次维度变化累积；写下"保留最近 N 个 / 超过 X 天删除"的规则，并真的实现它。
- [ ] **DDL 默认值会制造"隐形的数据归属"。** `team_id NOT NULL DEFAULT '__global__'` 会让所有忘记传团队的数据落进全局基线——这在多租户下是**数据可见性**问题，不只是完整性问题。
- [ ] **对"大表重建"要给上限或跳过开关。** 同一个类里 `search_vector` 重建有 `MAX_MIGRATE_ROWS = 100_000` 保护、而索引类型迁移没有——这种不一致要显式决策，别默认"反正能跑"。
- [ ] **零向量兜底等于写入死数据。** 向量化失败应当**拒绝写入 + 告警**，而不是用全零向量"骗过"数据库的维度校验。
- [ ] **索引类型会反向约束模型选型。** ivfflat 有 2000 维上限——选它就意味着 2560 维的模型不能用。这个约束要写进配置注释。
- [ ] **换嵌入模型是一次"数据迁移事件"，不是一次配置变更。** 必须走：停写 → 全员重算向量 → 校验 → 切流量。没有"自动完成"这回事。
- [ ] **先把内存账算出来再选索引参数。** 向量本体是 `条数 × 维度 × 4` 字节，HNSW 还要额外存 `m` 个邻居引用/节点（`pgvector/src/hnsw.h:54` 默认 `m=16`）——1024 维下 10^7 条约 41 GB，而 5 条只需 20 KB。同一套参数在 A 基座无感、在 B 基座就是成本。
- [ ] **`m` 是档位不是旋钮，两个 `ef` 都封顶 1000。** `pgvector/src/hnsw.h:55` / `:56` 给 `m` 的硬边界 2 与 100，`:59` / `:62` 给 `ef_construction` 与 `ef_search` 的硬上限 1000——调参前先确认目标值在区间内。
- [ ] **量化必须配重排。** `pgvector/README.md:755-756` 的两步建议里，"二值量化"与 `with re-ranking for search` 是连在一起的（`:734` 同）；只量化不接重排，等于用召回率换内存。

## 五、动手任务

> **任务**：在本地真库上**故意制造一次维度漂移**，完整观察两个失败方向和一次数据作废。
>
> **前置**：本机 PostgreSQL 17.x + pgvector 0.8.x（`CREATE EXTENSION vector` 可用）。
>
> **仓库位置**：`code-review-agent`（`config/InfrastructureConfig.java`、`core/memory/PgVectorMemoryStore.java`）
>
> **实验 A：写入方向失败（表现为"知识库为空"）**
> 1. 保持 `pgvector.vector-dim=1024`（默认），但把 `review.llm.embedding.enabled` 设为 `false`（切到 `SimpleHashEmbeddingClient`，256 维）。
> 2. 启动应用。
> 3. 观察日志。
>
> **预期结果**：
> - 出现 `[PgVector] 保存失败: ERROR: expected 1024 dimensions, not 256`；
> - 紧接着 `[KB] 知识库预灌入失败，已跳过（应用继续启动）`；
> - **应用启动成功**；`SELECT count(*) FROM memory_store WHERE agent_type='RAG'` → **0**。
> - **关键观察**：`log.error` 里写的是"已跳过"，而故障本身是致命的——**这是级别与后果不匹配**。
>
> **实验 B：数据作废（表现为"向量全没了"）**
> 1. 恢复 `review.llm.embedding.enabled=true`（1024 维），确认知识库有数据、`embedding` 非空。
> 2. 把 `pgvector.vector-dim` 改成 `256`，重启。
> 3. 再改回 `1024`，重启。
>
> **预期结果**：
> - 每次启动打印 `迁移：embedding 维度 N != 期望 M，已备份旧数据至 memory_store_bak_<epoch>`；
> - `SELECT count(embedding) FROM memory_store` 在每次重启后**先清零**（`ADD COLUMN` 后全 NULL）；
> - `SELECT tablename FROM pg_tables WHERE tablename LIKE 'memory_store_bak_%'` → **每次漂移多一张表，且永不清理**。
>
> **收尾（务必做）**：
> ```sql
> DROP TABLE IF EXISTS memory_store_bak_1940000000;  -- 按实际表名
> ```
> 并把这个复原动作写进你的 runbook——**因为代码里没有。**
>
> **追加一步：算一遍你自己的内存账**
> 1. 数出你库里 `agent_type = 'RAG'` 的行数 `N`；
> 2. 向量本体 = `N × 1024 × 4` 字节（维度取 `application.yml:197` 的 `dim: 1024`）；
> 3. HNSW 邻居引用 = `N × m × 4` 字节，`m` 取默认 16（`pgvector/src/hnsw.h:54`）；
> 4. 两项相加，与你的实例可用内存对比：超过一半就该考虑 `halfvec` 或二值量化（`pgvector/README.md:755-756`），而量化前先确认重排已接上（`pgvector/README.md:734`）。
>
> **判据**：内存账算不出来，就先别调 `m`——调参解决的是召回，不是容量。

---

## 本讲小结

1. **向量维度是一个三方契约**（模型输出 / 列声明 / 配置项），三者必须有一个权威来源。三个都能独立改，就等于没有契约。
2. **两个失败方向**：写入期维度不匹配 → **抛异常（但会被启动期 try/catch 吞成"知识库为空"）**；列维度漂移 → **备份表 + 丢弃向量列 + 重建**（数据作废）。
3. **本讲发现的真实不一致**：`InfrastructureConfig` 里同一个属性 `pgvector.vector-dim` 两个 Bean 的默认值分别是 **1024** 和 **256**；而 `PgKnowledgeStore.vectorDim` 是一个**从不被使用的死参数**——它不造成功能故障，但让日志输出一个错误的事实。
4. **"自动迁移"的代价是把人的决策变成机器的静默执行。** "数据作废"配一条 WARN 是不匹配的：应该默认拒绝启动，让人显式确认。
5. **零向量兜底会绕过维度校验并写入死数据。** 向量化失败应该拒绝写入 + 告警。
6. **索引类型反向约束模型选型**（ivfflat 2000 维上限），且"大表重建"在本项目里**两种迁移两种态度**——一个有限额、一个没有。

第 26 讲我们看了漏斗全景，第 27 讲修了写入侧的第一环，这一讲把向量化落地——**下一讲回到检索链路的核心：混合检索**。为什么单靠向量召回会漏，为什么"向量 0.7 + BM25 0.3"是一个需要论证的配比。
