# 第 16 讲 · 会话状态外置与水平扩展：Agent 进程为什么不能有本地状态

> 🎯 导读问题：**"把副本从 1 台扩到 3 台，为什么用户的对话历史会'时有时无'？"**

<img class="mermaid-svg" src="/zh/book-assets/diag-0070.svg" alt="🎯 导读问题：&quot;把副本从 1 台扩到 3 台，为什么用户的对话历史会'时有时无'？&quot;" />

> **图 16-0**　本讲地图：**"进程不能有本地状态"不是一个说法，是一条可验证的判据**——它由五个具体问题合成：状态分几个槽位、后端怎么选、粘性会话为什么不是答案、同一会话并发写会丢什么、滚动发布时在途请求怎么收尾。五个问题里有四个的正确答案都反直觉，因为**它们都不报错**。

## 一、痛点

先看一个几乎每个团队都会走一遍的过程。

第一版系统部署在一台机器上。状态存在本地：断点是一个 JSON 文件，经验库是一个目录，团队配置又是另一个目录。写得很顺手——**读一个文件、改一个字段、写回去**，没有任何依赖，本地跑得飞快。

然后产品说：要扩容。于是副本数从 1 改成 3。

三个故障开始出现，而且**都不会让任何请求失败**：

**故障一：同一个 PR 重推一次，两次审查结果不一样。** 第一次审查沉淀了几条经验，第二次审查却检索不到——因为第一次的请求落在 A 机、写进了 A 机的目录，第二次落在 B 机，B 机的目录里什么都没有。于是同一个输入、同一个模型、同一个提示词，**输出质量随负载均衡的调度结果而变**。

**故障二：断点续跑"失效"了。** A 机崩溃，客户端按原 `runId` 重试——请求落到 B 机。B 机查自己的本地存储，查不到断点，于是**从头重跑**。所有已经花掉的 token 全部白烧。断点续跑这件事"实现了"，却只在单机下有效。

**故障三：残留状态永远不回收。** 崩溃留下的断点是靠一个定时任务清理的。这个任务跑在 C 机上，只清 C 机的本地文件。集群越大，**清理覆盖率越低**——每个实例只清自己那一片。

把三个故障放在一起看，会发现它们**有同一个根因**：状态属于"某一次会话/某一次任务"，而处理这次会话的进程**是一次性的**。两者的生命周期不一致——状态想活几个月，进程可能只活几十分钟（滚动发布、扩缩容、崩溃重启都会换掉它）。**把生命周期长的东西存在生命周期短的东西里，必然产生上面三种症状。**

那为什么一开始会这么写？因为**单机上它是对的**。本地文件的读写延迟比任何网络存储都低，而且零依赖。这个选择的错误不在实现，在于它把"进程是一次性的"这件事**当成了一个可以推迟处理的假设**——直到你决定扩容的那一天。

`code-review-agent` 的仓库里留着这条改造的完整痕迹，就写在接口的类注释里：

```text
 * <p>替代改造前的 {@code FileResumeStore}：本地 JSON 文件在多机部署下 A 机写的断点 B 机读不到，
 * 且无跨进程并发控制；PG 行级原子写入天然解决两者。
```

`code-review-agent/src/main/java/com/codereview/agent/core/resume/ResumeStore.java:12-13`

这两行值得逐字读三遍，因为它把"本地状态"的**两个病**分得很清楚：

1. **不可见**——A 机写的东西 B 机读不到（这是 **可见性** 问题）；
2. **无跨进程并发控制**——两个进程同时做"全量读-改-写"，会互相覆盖（这是 **原子性** 问题）。

这两个病是**独立的**。你解决了第一个（比如把文件放到共享 NFS 上），第二个一点都没好——NFS 上的全量读改写照样互踩。所以选后端时不能只说"要共享"，必须同时回答"并发写怎么保证"。

这一讲要回答四个问题：

1. **一个 Agent 进程里到底有哪些状态？** 哪些必须外置、哪些可以留在本地？
2. **外置到 Redis、PG 还是混合？** 它们的差异不在性能，在"谁来管生命周期"。
3. **粘性会话能不能替代状态外置？** 不能，但要说清它到底掩盖了什么。
4. **同一个会话被两个请求同时写会怎样？** 以及滚动发布时在途请求怎么办？

> **判据**：**"我们的状态已经外置了"这句话，只有在你能同时说出"杀掉任意一个实例、把它的流量立刻切到另一个实例，用户的会话有没有任何变化"时，才算成立。** 说不出来，就是把状态放进了一个进程里，而不是放进了一个系统里。

## 二、原理

### 2.1 会话状态的四个槽位与生命周期

"会话状态"是四个不同的东西，它们的可丢性完全不同：

<img class="mermaid-svg" src="/zh/book-assets/diag-0071.svg" alt="&quot;会话状态&quot;是四个不同的东西，它们的可丢性完全不同：" />

> **图 16-1**　四个槽位与它们的可丢性。**判据不是"这个状态重不重要"，而是"丢了以后谁来付代价"**：槽位一由用户付（他看得见历史没了）、槽位二由账单付（重跑要重新烧 token）、槽位三由效果付（不会报错，只是变笨）、槽位四由数据付（双写产生两条互相覆盖的记录）。只有"付代价的人是谁"能决定它必须存在哪。

逐个槽位看：

| 槽位 | 内容 | 生命周期事件 | 写者 | 丢了谁付代价 | 存在哪 |
|---|---|---|---|---|---|
| **① 对话历史** | 用户 / 助手 / 工具消息 | 创建 → 追加 → 归档 → 过期 | 每个节点都写 | 用户 | **必须外置**（或可完整重建） |
| **② 运行中间态** | 已完成步骤、待办步骤、当前输入输出 | 任务开始 → 每步覆盖 → 完成 / 超时 | 只有执行者写 | 账单 | **必须外置**（否则续跑只能在同机） |
| **③ 长期记忆** | 跨会话沉淀的经验 / 知识 | 沉淀 → 失效 → 物理删除 | 异步沉淀 | 效果 | 可外置，也可"接受变笨" |
| **④ 会话租约** | 谁在占用这个会话 | 获取 → 续期 → 释放 / TTL 过期 | 抢锁者写 | 数据 | 多副本才需要 |

四个槽位里，**只有槽位四是因为"多副本"才存在的**。这是个常被忽略的顺序问题：如果你先上了多副本才发现需要租约，说明你把"状态放哪"和"谁来写"这两个问题分开做了——而它们本来就该一起设计。

工业界的框架把**槽位一**抽得最干净。`langchain4j` 的 `ChatMemoryStore` 是一个只有四个方法的接口：

```text
public interface ChatMemoryStore {
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/ChatMemoryStore.java:27`

```text
    List<ChatMessage> getMessages(Object memoryId);
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/ChatMemoryStore.java:35`

```text
    void updateMessages(Object memoryId, List<ChatMessage> messages);
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/ChatMemoryStore.java:44`

```text
    void deleteMessages(Object memoryId);
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/ChatMemoryStore.java:51`

**这三个方法就是槽位一的最小面**：读、写、删。请特别注意第二个方法的签名——它接收的是 `List<ChatMessage>`，也就是**整个会话的全部消息**。它的语义是"用这一整段替换掉存储里的那一段"，**不是"追加一条"**。

这个区别决定了后面 2.4 的全部麻烦：**整段覆盖的写，天然没有"谁先谁后"的概念**，两个并发请求各自算出一整段、各自写一次，后写的那次把前一次的整段结果抹掉。而如果接口是"追加一条消息"，冲突面就只剩"这一条"。

`spring-ai` 的 `ChatMemoryRepository` 是同一层，但多了一个方法，注释里也把语义写明了：

```text
public interface ChatMemoryRepository {
```

`spring-ai/spring-ai-model/src/main/java/org/springframework/ai/chat/memory/ChatMemoryRepository.java:29`

```text
	List<String> findConversationIds();
```

`spring-ai/spring-ai-model/src/main/java/org/springframework/ai/chat/memory/ChatMemoryRepository.java:31`

```text
	List<Message> findByConversationId(String conversationId);
```

`spring-ai/spring-ai-model/src/main/java/org/springframework/ai/chat/memory/ChatMemoryRepository.java:33`

```text
	 * Replaces all the existing messages for the given conversation ID with the provided
	 * messages.
```

`spring-ai/spring-ai-model/src/main/java/org/springframework/ai/chat/memory/ChatMemoryRepository.java:36-37`

```text
	void saveAll(String conversationId, List<Message> messages);
```

`spring-ai/spring-ai-model/src/main/java/org/springframework/ai/chat/memory/ChatMemoryRepository.java:39`

```text
	void deleteByConversationId(String conversationId);
```

`spring-ai/spring-ai-model/src/main/java/org/springframework/ai/chat/memory/ChatMemoryRepository.java:41`

`Replaces all the existing messages` 这句话是**逐字**的：替换，不是追加。而且 `saveAll` 这个方法名本身也承认了这件事——它一次写整个会话，粒度是会话，不是消息。

**多出来的 `findConversationIds()` 是运维与合规需要的**，不是业务逻辑需要的：要批量导出（用户申诉）、要批量删除（删除权）、要盘点（多少会话还活着）。如果接口里没有它，你就只能在存储层直接写 SQL——而那一刻，"后端可替换"这件事就失效了。

**生命周期有四个事件，不是两个**：

| 事件 | 谁触发 | 漏了会怎样 |
|---|---|---|
| 创建 | 第一次写 | — |
| 追加 / 覆盖 | 每次对话 | — |
| **完成**（主动清理） | 业务逻辑判定任务结束 | — |
| **过期**（兜底回收） | 定时任务 + TTL | **永远漏不了，但也永远清不掉**（见 3.3） |

最后一行是本讲最容易出事的地方：**"完成"和"过期"是两条独立的线，只做前者必然泄漏**。原因很朴素——"正常完成"是一条正常的路径，而崩溃、被强杀、用户换了个 commit 让老 `runId` 再也不被触发，全都不走正常路径。

`code-review-agent` 把这条教训逐字写在了清理任务的注释里：

```text
 * <p>{@link ResumeStore#complete} 只在审查<b>正常完成</b>时被调用。进程被杀、机器重启之后，
 * 若那个 runId 再也不被触发（PR 被关闭 / 合并 / 换了新的 commit），断点就会永远残留在存储中。
 * 文件存储最容易被忽略的运维问题不是性能、不是一致性，而是这种<b>缓慢的泄漏</b>：
 * 每次崩溃漏一个，攒几个月就攒出一堆垃圾（PG 行版同样需要兜底回收）。
```

`code-review-agent/src/main/java/com/codereview/agent/core/resume/ResumeJanitor.java:14-17`

**"缓慢的泄漏"这个词组选得很准**：它不是一次事故，是每次崩溃漏一个。攒三个月以后，你在监控上看不出任何异常——**因为泄漏从来不报警，它只是让你的表和磁盘慢慢变胖**。

而"为什么过期回收不会误杀正在进行的任务"这件事，判据也只有一句：

```text
 * <p>为什么不会误删正在进行的审查：判据是断点最后更新时间，只要审查还在推进就会不断
 * {@link ResumeStore#save} 覆盖刷新，超过 TTL 没有任何写入就等价于「这次审查已经死了」。
```

`code-review-agent/src/main/java/com/codereview/agent/core/resume/ResumeJanitor.java:23-24`

**这条注释里藏着一个必须先成立的前提**：`save` 要**在每一步都调用**。如果只在任务开始时存一次、结束时删掉，那么"最后更新时间"就永远停在开始那一刻——TTL 一到，正在跑的任务会被当成残留**删掉**。所以"兜底回收"不是加一个定时任务就完事的，它要求**写入频率**与**TTL**之间有一个明确的关系：

> **判据**：`TTL > 单步最长耗时 × 安全系数`。TTL 必须大于"正常推进时两次写入之间的最长间隔"，否则定时任务会开始吃活的任务。这条关系必须写进配置注释，因为它是两个看起来无关的参数之间的隐形约束。

### 2.2 状态外置的三种后端：差异不在性能，在"谁管生命周期"

选后端时最容易犯的错，是拿压测数据比延迟。**会话状态的读写量通常不是瓶颈**（一次对话几 KB、几百 QPS 级别），真正的差异在**生命周期由谁管理**。

<img class="mermaid-svg" src="/zh/book-assets/diag-0072.svg" alt="选后端时最容易犯的错，是拿压测数据比延迟。会话状态的读写量通常不是瓶颈（一次对话几 KB、几百 QPS 级别），真正的差异在生命周期由谁管理。" />

> **图 16-2**　三种后端的选择与各自要付的代价。**注意三张"代价"卡片都不是性能问题**：Redis 的代价是"你不配就永不删除"、PG 的代价是"写放大与顺序要自己管"、混合的代价是"两层之间必然有一段不一致窗口"。**选型的问题是"你愿意付哪一张账单"，不是"哪个更快"。**

逐个看：

| 判据 | Redis | PG | 混合 |
|---|---|---|---|
| 单会话全量读写 | 快（单键 JSON） | 一般（先删后插） | 最快 |
| 按内容查询（"找出提到 X 的会话"） | 需要 JSON 查询引擎 | SQL 直接做 | 冷层能做 |
| 跨会话事务 | **没有** | 有 | 冷层有 |
| 留存期 / 过期 | TTL 天然，**但默认永不过期** | 必须自己写清理 | 两层各管 |
| 顺序保证 | 要靠原子序列号 | 显式顺序列 | 同左 |
| 运维成本 | 低（但多了一个有状态组件） | 低（多数系统已有） | **高**（两层一致性） |

`spring-ai` 把这件事做得很彻底——同一个接口，仓内**有 5 套后端实现**（JDBC / Redis / Cassandra / MongoDB / Neo4j），业务代码一行不改。这不是"框架爱炫技"，这是**接口先行**的直接回报：只要接口定得足够窄，后端就真能换。

三套后端各自最值得学的一行，逐字如下。

**① Redis 的 TTL 默认值是 `-1`（永不过期）：**

```text
		private Integer timeToLiveSeconds = -1;
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-redis/src/main/java/org/springframework/ai/chat/memory/repository/redis/RedisChatMemoryConfig.java:156`

**框架不会替你决定留存期。** 这一行的工程含义是：**不配 TTL 就等于选择永久保留**。而在千万 DAU 场景下，"永久保留所有用户对话"是一个合规问题，不是一个存储成本问题（见第 44 讲）。所以这条配置必须**显式填**，且写进上线检查表。

**② Redis 侧一个会话要占两把键，不只是键前缀不同：**

```text
		String sequenceKey = String.format("%scounter:%s", this.config.getKeyPrefix(), escapeKey(conversationId));
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-redis/src/main/java/org/springframework/ai/chat/memory/repository/redis/RedisChatMemoryRepository.java:197`

消息存在 `chat-memory:<会话>` 之下，而**顺序号单独存在 `chat-memory:counter:<会话>`**。两把键都必须管：内容过期了、计数没过期，下一个写入就拿到一个跳到未来的序号；反之计数过期了、内容还在，序号会从头开始，**同一个会话里出现两组同序号的消息**。**"外置"永远不是"挪一个变量过去"，而是"这一组键要一起管"。**

**③ PG 侧的顺序是一个显式的列，且 `NOT NULL`：**

```text
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-jdbc/src/main/resources/org/springframework/ai/chat/memory/repository/jdbc/schema-postgresql.sql:1`

```text
    "timestamp" TIMESTAMP NOT NULL,
    sequence_id BIGINT NOT NULL
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-jdbc/src/main/resources/org/springframework/ai/chat/memory/repository/jdbc/schema-postgresql.sql:5-6`

如果只存时间戳，同一毫秒内写入的两条消息**顺序就不确定了**——而一次对话里"用户说了什么、助手回了什么"的顺序错了，整个上下文就废了。所以顺序必须是一个**独立的、单调的**列。注意 `timestamp` 还加了引号，因为它是 SQL 保留字——**外置到 SQL 之后，"字段叫什么名字"这件事第一次需要认真对待**。

那"混合"什么时候值得？只有一种情况：

> **热路径的读放大与冷路径的查询需求同时存在。** 例如"每次开新对话都要读最近 20 轮"（热，高频）加上"客服按关键词检索全部历史"（冷，低频、要索引）。如果两个需求只有一个，混合只会给你增加一个一致性窗口，没有任何收益。

`code-review-agent` 的选择是**只用 PG**，而且把"多后端"这件事做成了**一个开关**：

```text
 * 状态存储装配（多机集群化的核心开关）。
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/StateStoreConfig.java:26`

```text
 * <p>把改造前散落在 {@code data-dir} 本地 JSON 文件的状态（断点/经验/反馈/历史/校准/
 * 团队配置/轨迹/知识元数据）统一收敛到「可插拔后端」：
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/StateStoreConfig.java:28-29`

```text
 *   <li>{@code pgvector.enabled=true}（集群前提：PG + pgvector）→ 全部走 PostgreSQL 实现，
 *       多实例读写同一份状态，A 机沉淀 B 机可见；</li>
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/StateStoreConfig.java:31-32`

```text
 *   <li>否则 → 全部回退内存实现并告警（单机/测试，重启丢失）。</li>
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/StateStoreConfig.java:33`

```text
 * 由 {@code ReviewAgentConfig} 组装。该结构使「无本地文件」成为系统默认——审查进程无本地状态。
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/StateStoreConfig.java:37`

**"把 8 类状态收敛到一个开关"** 是这份代码里最值得抄的一个结构决策。它的价值不在于省事，而在于**它把"状态外置"从一个散布在十几个类里的实现细节，变成了一个可以被审计的系统属性**：只要看一个配置项，就知道当前进程是不是无状态的。

注意最后一句的措辞——`「无本地文件」成为系统默认`。**默认值的方向决定了故障的方向**：默认无状态，那么配错只会在单机测试时暴露（回退内存）；如果反过来默认有状态、集群时才切成外置，那么配错就是在生产上以"多实例各看各的"的形式暴露。

回退分支必须**告警**，而不是静默：

```text
        log.warn("pgvector 未启用：断点存储回退内存（单机/测试，多实例部署必须启用 PostgreSQL）");
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/StateStoreConfig.java:99`

**告警文案把后果说清了**（"多实例部署必须启用 PostgreSQL"）。如果只写"回退到内存实现"，运维看到这条日志不会知道它意味着什么。

> **判据**：**任何"回退到内存"的分支都必须回答"多实例下会发生什么"，并把这句答案写进日志。** 因为回退路径恰恰是**最容易被忽略的那条**——单机测试时它完全正常。

### 2.3 粘性会话是陷阱，不是方案

有一个很诱人的中间方案：**让负载均衡把同一个会话的请求永远打到同一个实例**（sticky session / 会话保持）。这样状态留在进程内也能工作，"扩容"这件事看起来就完成了。

它确实是能工作的——**在正常情况下**。

```text
 * Currently, the only implementation available is {@link InMemoryChatMemoryStore}.
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/ChatMemoryStore.java:20`

```text
 * This storage mechanism is transient and does not persist data across application restarts.
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/InMemoryChatMemoryStore.java:14`

这两行合起来说明：**框架的默认形态就是"进程内 + 不跨重启"**。默认形态之所以能被接受，恰恰是因为在开发机上**没有第二个实例**，也没有人会重启你的进程。所以"粘性会话"在开发环境里永远看不出问题——它要等下面这几件事发生：

| 触发事件 | 粘性会话下会发生什么 |
|---|---|
| **扩缩容** | 新实例上没有老会话；缩容时被摘掉的实例上的会话**直接消失** |
| **滚动发布** | 新版本实例的空会话表；老实例被下线时，正在进行的会话被中断 |
| **实例崩溃 / 重启** | 该实例上所有活跃会话一次性丢失 |
| **连接重建** | 移动网络从 Wi-Fi 切到 4G，连接重建后落到别的实例（IP 变了） |
| **LB 权重调整 / 后端摘除** | 调度结果改变，用户"莫名其妙回到了空白页" |

关键在于：**这些事件都不是异常，它们是常态。** 滚动发布是你自己发起的，扩缩容是自动伸缩策略发起的，切网络是用户干的。

所以粘性会话的真实性质是：**它把"状态必须外置"这个必须在架构上解决的问题，降级成了一个运行时的概率问题**——正常情况下不出事，出事时没有任何预兆，而且**症状落在用户身上**（他的历史没了），日志上什么都看不到。

这带来一个很好用的验收判据：

> **判据一（功能判据）**：**把粘性会话说成"关闭"，功能还完整吗？** 如果答案是不完整，那你的系统是"能跑"，不是"能扩"。
>
> **判据二（压测判据）**：**同一个会话标识交替打两个不同的实例，回应必须一致。** 这个测试不需要真的上负载均衡——把 base-url 指向 A 机发一轮、指向 B 机发下一轮即可。这个动作的成本是十分钟，而它能暴露的问题，靠"看着好像是好的"永远发现不了。

> **推演**：3 个副本 + 粘性会话，若某次滚动发布让每个实例的平均存活期为 6 小时，则一个持续 24 小时的会话**必然至少被迁移一次**（24 / 6 = 4 轮发布窗口），并被迁移到"看不到历史"的新实例上。这类会话的用户会认为"系统丢了我的东西"（假设：发布期间会话不主动迁移、且新实例没有历史缓存；基座值——`server.shutdown` 与发布窗口的配置见 `application.yml:5`，本仓的实例数由部署决定，代码里不体现）。

### 2.4 同会话并发：三档控制与它们各自的失效面

状态外置以后，多副本第一次**真的**会同时写同一个会话。这件事在单机上很少发生，上了集群就变成常态——用户双击发送、客户端自动重试、多端同时在线，都会产生"同一会话两个请求同时在跑"。

<img class="mermaid-svg" src="/zh/book-assets/diag-0073.svg" alt="状态外置以后，多副本第一次真的会同时写同一个会话。这件事在单机上很少发生，上了集群就变成常态——用户双击发送、客户端自动重试、多端同时在线，都会产生&quot;同一会话两个请求同时在跑&quot;。" />

> **图 16-3**　三档并发控制与各自的后果。**判据不是"要不要加锁"，而是"冲突发生时，你希望系统做什么"**：静默丢（最坏，因为没人知道）、原子预留（不丢，但顺序由机制决定）、版本号（不丢，但要有人重试）。**档一不是"没做控制"，它是一个明确的、有后果的选择**——问题在于很少有人在写它的时候意识到自己做了选择。

**档一：不加控制（整段覆盖）。** 这是框架默认实现的行为：

```text
        messagesByMemoryId.put(memoryId, messages);
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/InMemoryChatMemoryStore.java:32`

```text
    private final Map<Object, List<ChatMessage>> messagesByMemoryId = new ConcurrentHashMap<>();
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/InMemoryChatMemoryStore.java:18`

**这里有一个非常容易被读错的细节**：`ConcurrentHashMap` 保证的是**容器本身**在多线程下不会损坏，它**完全不保证**"读-改-写"这一整段是原子的。看第 32 行的语义——`put` 是**整段替换**。于是两个并发请求的时序可以是：

1. 请求甲读出 `[m1, m2]`；
2. 请求乙读出 `[m1, m2]`；
3. 甲算出新的一段 `[m1, m2, m3]`，`put` 回去；
4. 乙算出新的一段 `[m1, m2, m4]`，`put` 回去。

最终存储里是 `[m1, m2, m4]`——**`m3` 永久消失**。没有异常、没有日志、没有指标变化，只有那个用户的聊天记录里少了一条。而且这个 bug **在单机并发测试里也会出现**，只要你真的同时发两个请求。

> **推演**：若某会话的并发写概率为 1%，日均 200 万条消息，则每天约 `2000000 × 1% = 20000` 条消息落在并发窗口内；按"两条并发丢一条"的下界估算，**每天至少丢 10000 条消息，且全部无法归因**（假设：并发窗口内两条消息互相覆盖、且窗口宽度不随负载变化；基座值——`put` 的整段覆盖语义见 `InMemoryChatMemoryStore.java:32`，该行为与实例数无关）。

**档二：单写者（原子段预留）。** `spring-ai` 的 Redis 实现走的是这条路——**序列号的分配必须在服务端一次完成**：

```text
			// always reserve disjoint blocks of unique and increasing timestamps
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-redis/src/main/java/org/springframework/ai/chat/memory/repository/redis/RedisChatMemoryRepository.java:203`

```text
					+ "return redis.call('INCRBY', KEYS[1], ARGV[2]) - ARGV[2] + 1";
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-redis/src/main/java/org/springframework/ai/chat/memory/repository/redis/RedisChatMemoryRepository.java:206`

**`disjoint blocks`（互不重叠的段）这个词是整个方案的验收目标**：多个实例同时往同一会话追加，每个实例拿到的序号段**不允许有任何重叠**。做法是把"读当前值 → 加一 → 写回"这三步压进一个 Lua 脚本，在 Redis 服务端原子执行。如果分成三次往返，两个实例会读到同一个基数、各加一、各写回——**撞号**。

注意它预留的是**一段**（`ARGV[2]` 可以是多条消息的数量），不是单个序号。这样做的好处是"一次往返预留 N 个位置"，坏处是"预留了但没写进去"的那部分序号**会永久空出来**——序号有空隙是可以接受的（顺序仍然正确），这也是这个方案比"严格连续"更实用的原因。

**档三：版本号（乐观锁）。** 这是唯一能区分"我基于哪一版改的"的方案：写的时候带上读到的版本，版本没变才允许写，变了就拒绝并让调用方重试。

**而这一档，本书的两个基座都没有做。** `code-review-agent` 的断点存储用的是行级 UPSERT：

```text
                            ON CONFLICT (run_id) DO UPDATE SET
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/PgResumeStore.java:47`

```text
 *   <li>同一 runId 并发 save 不会互相破坏（文件存储下两进程同时全量读改写会互踩）；</li>
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/PgResumeStore.java:22`

**这句话是准确的，但它的边界要说清**：UPSERT 保证的是**行**不会被写坏（不会出现半截 JSON、不会两进程互踩），它**不保证**语义上的"两次更新都生效"——并发时仍然是**最后写的那次赢**。

那为什么这里可以接受？因为这个槽位（运行中间态）**天然单写者**：一次审查的断点只有执行它的那个节点在写。**"并发写不冲突"不是靠机制保证的，是靠"没有人会同时写"这个业务事实保证的。** 一旦这个事实变了（比如加了"用户手动中止"接口、或者节点重试与超时同时发生），档一的问题就会原样出现在档二/档二点五的实现上。

> **判据**：**"我们的存储是并发安全的"这句话必须补全为"针对哪种并发"**：① 容器级（多线程不损坏）；② 行级（同一行不会被写成半截）；③ 语义级（两次更新都不丢）。前两个是存储给的，**第三个只能由业务自己设计**（版本号、租约、或者"保证只有一个写者"）。

### 2.5 滚动发布下的 in-flight 请求：优雅停机与 drain

状态外置解决的是"新实例能不能接上"，还有一件事它没解决：**老实例下线时，正在它身上跑的请求怎么办**。

`code-review-agent` 这一项**已经配好了**：

```text
  # 优雅停机：SIGTERM 后等待在途审查任务完成，避免任务丢失（配合 spring.lifecycle 超时）
  shutdown: graceful
```

`code-review-agent/src/main/resources/application.yml:4-5`

```text
    timeout-per-shutdown-phase: 30s
```

`code-review-agent/src/main/resources/application.yml:303`

现在做一个必须做的对账：**30 秒的停机窗口，够不够等在途请求跑完？** 第 05 讲的双基座对照表里给过这个基座的量级——**单次审查 30–300 秒**。300 秒的请求，30 秒的窗口，显然等不完。

那这是配置错了吗？**不是。** 这里是本讲最值得记住的一个组合结论：

> **优雅停机不需要等待最长的请求，前提是那个请求可以从断点续跑。**

把两件事连起来看：

- 光有优雅停机（无外置）→ 等不完就被强杀，那个任务的**全部进度丢失**，重跑要重花全部 token；
- 光有外置（无优雅停机）→ 强杀得更早，但断点在存储里，**重跑只需要从断点之后开始**；
- **两者都有** → 短窗口是划算的：用"重跑最后一段"换"停机更快、发布更短"。

所以停机窗口的判据不是"≥ 最长请求时长"，而是一串有条件的判断：

| 问 | 答 | 结论 |
|---|---|---|
| 在途请求会被强杀吗？ | `停机窗口 < 请求 P99` ⇒ 会 | 必须承认有一部分会被打断 |
| 打断以后能续吗？ | 断点已外置 + 单步粒度 ⇒ 能 | **短窗口可接受** |
| 续跑的代价是什么？ | 重跑"最后一段未完成的部分" | 与"整次重跑"差一个数量级 |
| 如果续跑不可行呢？ | 必须把窗口提到 ≥ P99 | 或者改成异步任务（先入队、后处理） |

> **推演**：若单次审查时长在 0–300 秒间均匀分布，停机窗口 30 秒，则在停机瞬间处于"第 30 秒之后"的在途请求占比为 `1 − 30/300 = 90%`——它们会被强杀。**但因为断点是增量保存的，这 90% 里浪费的只是"最后一次保存之后的工作"**：若断点每步保存一次、单步约 10 秒，则平均浪费 `10 / 2 = 5` 秒的工作量，占单次审查的 `5/150 ≈ 3.3%`（假设：时长均匀分布、断点按步保存、单步 10 秒；基座值——`timeout-per-shutdown-phase: 30s` 见 `application.yml:303`，单次审查 30–300 秒见第 05 讲双基座对照表，断点保存粒度见 `ResumeStore.java:17`）。

**"用增量保存换一个更短的停机窗口"，这就是外置带来的、最容易被忽略的一笔收益**——它不体现在正确性上，而是体现在**发布速度**上。

还有两件 drain 相关的工程细节，顺序错了优雅停机就是不生效：

1. **先摘流量，再发 SIGTERM。** 如果先 SIGTERM、负载均衡还在往这个实例发新请求，那些请求会在"已经开始停机"的进程里排队，最终以连接错误告终。正确顺序是：从 LB 摘除 → 等在注册中心的心跳过期（或主动下线）→ 发 SIGTERM。
2. **流式响应下 drain 的单位是"连接"不是"请求"。** 第 05 讲讲过，一个 SSE 连接可以活几分钟。优雅停机的窗口必须按**连接存活时长**算，而不是按"一个请求的平均处理时间"算——否则你会一边"优雅等待"，一边让几十条长连接被硬切。

> **判据**：**滚动发布的验收不是"新版本起来了"，而是"发布期间零个会话状态丢失、零个已完成的步骤被重跑"。** 前者看连接数就够了，后者必须看**断点表在发布窗口内的读写记录**——这是唯一能证明"在途请求确实被正确接管或正确续跑"的证据。

### 2.6 一手数据与搬到你自己系统

先把本项目可机械复算的现状摆出来。

**一手数据（A 基座的状态外置现状）**：

| 项 | 值 | 复算方式（在 `code-review-agent` 仓） |
|---|---|---|
| `InMemory*` 实现数 | **10** | `find src/main/java -name 'InMemory*.java' \| wc -l` |
| `Pg*` 实现数 | **11** | `find src/main/java -name 'Pg*.java' \| wc -l`（其中 1 个是共享连接底座 `PgDb`） |
| 成对的后端实现 | **10 对** | 上两数相减：11 − 1（`PgDb` 不是存储实现）= 10，与 `InMemory*` 的 10 一一对应 |
| `StateStoreConfig` 里收敛的状态类别 | **8 类** | 该文件里带 `pgvector.enabled` 条件的 `@Bean` 方法数：PG 分支 8 个、内存分支 8 个 |
| 由 `StateStoreConfig` 之外装配的那 2 对 | 向量存储、知识库 | 在 `InfrastructureConfig` 里，同样按 `pgvector.enabled` 二选一 |
| `ResumeStore` 接口方法数 | **5** | `save` / `load` / `complete` / `purgeExpired` + 注释（数接口内的方法签名） |
| 断点残留 TTL 默认值 | **24h** | `application.yml` 之外的默认值写在 `@Value("${review.resume.ttl:24h}")` |

**"10 对实现 + 1 个共享底座"** 这个结构本身就是一条可迁移的经验：**外置不是写一个 PG 版本，是把每一个本地实现都配一个后端实现，并且共用同一个连接底座**。`PgDb` 的类注释把这件事的动机写得很清楚：

```text
 * {@code data-dir} 本地文件，多实例部署时 A 机沉淀、B 机不可见，必然分叉。
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/PgDb.java:21`

```text
 * 使每个实例读写同一份 PG 状态——审查进程无本地状态，A/B 双机共享同一视图。
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/PgDb.java:23`

**"必然分叉"这四个字是有重量的**：它不是"可能会不一致"，而是"两个进程独立演进同一份数据，结果一定是分叉"。这给出一条比"性能对比"更硬的选型理由——**本地存储在多实例下不是"慢一点"，是"错的"。**

**双基座对照**——同一个问题，两个基座的答案完全不同：

| 维度 | A：`code-review-agent` | B：千万 DAU 的对话应用 | 由此分叉的设计决策 |
|---|---|---|---|
| 状态种类 | **8 类任务态**（断点/经验/反馈/历史/校准/团队配置/轨迹/知识元数据），**没有多轮会话** | 槽位一（对话历史）+ 流式 in-flight + 用户长期记忆 + 会话租约 | B 必须先做**会话键设计**（分片键 = 会话 ID），A 只需要任务键 |
| 单次交互时长 | 一次审查 30–300 秒，单轮，完成后即结束 | 一个会话可跨天、跨设备、任意轮 | A 的 TTL 按"任务超时"定；B 的 TTL 必须按**留存政策**定 |
| 并发写同一键 | 罕见（同一 PR 通常串行触发）；实现上是**行级 UPSERT**，语义为"最后写赢" | 高频（多端、双击、重试）⇒ **语义级并发控制是必需的** | B 需要版本号或租约；A 可以用"业务上只有一个写者"来替代 |
| 顺序保证 | 不需要（一次性快照） | **必须**（消息顺序错了上下文就废了） | B 必须引入显式的单调顺序键（见 §2.2 的 `sequence_id`） |
| 停机窗口 | 30s 优雅停机 + **断点可续跑** ⇒ 短窗口可接受 | 长连接流式为主，drain 单位是**连接** | B 的窗口要按连接存活时长算，且要先摘流量 |
| 存储选型 | 单一 PG（够用） | Redis 热 + PG 冷（混合） | B 要额外承担两层一致性窗口的成本 |
| 存量迁移 | 从本地 JSON 目录迁到 PG（已在代码里留痕） | 从"无状态"起步也要预留 delete-by-conversation | B 的接口里必须有 `findConversationIds` 这类运维口 |

**搬到你自己系统**，五步：

1. **先给状态分槽位，再给它选存储。** 按 2.1 的表把每一项填成"槽位几 / 丢了谁付代价"。填不出"丢了谁付代价"的，说明这个状态**目前没有人在用**，可以先不做（但要在文档里写下"我们故意不做"，而不是让它处于不明的状态）。
2. **把"回退到内存"的分支改成一条会报警的路径。** 参照 `StateStoreConfig.java:99` 的写法：日志里必须出现"多实例部署会怎样"。然后加一条验收动作：**故意把外置开关关掉，跑一次多实例验证，确认能观测到这条告警**。
3. **亲手做一次"关掉粘性会话"的压测。** 同一会话标识交替打两个实例（不需要真上 LB，改 base-url 即可），断言回应一致。这一步不能用"看起来没问题"代替。
4. **给"完成"与"过期"各写一条测试。** ① 正常完成 → 记录被清理；② 模拟崩溃（不调用完成路径）→ 记录**在 TTL 之后**被回收。第二条很容易漏测，因为它在正常流程里从不执行——**而它才是泄漏的唯一防线**。
5. **对账停机窗口与请求最长时长，并把结论写下来。** 写成二选一：(a) 窗口 ≥ P99；(b) 窗口 < P99，但断点已外置、单步粒度 ≤ N 秒，因此可接受。**没有第三种写法。** 写不出结论的，就是还没想清楚。

**判据**：**"我们能水平扩展"这句话，只有在你能同时说出三件事时才算成立——③ 杀掉任意实例、会话不变；② 同一会话交替打两个实例、结果一致；③ 发布窗口内零状态丢失。** 三件事都是可复现的动作，不是架构图上的一个框。

## 三、代码

### 3.1 `langchain4j`：最小面是三个方法，其中一个是"整段覆盖"

```text
public interface ChatMemoryStore {

    List<ChatMessage> getMessages(Object memoryId);

    void updateMessages(Object memoryId, List<ChatMessage> messages);

    void deleteMessages(Object memoryId);
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/ChatMemoryStore.java:27-51`

这三行（去掉注释与 Javadoc）就是"会话状态存哪"的最小接口面。值得学的有两点：

1. **它是接口，而默认实现是内存**（`ChatMemoryStore.java:20` 逐字写了"唯一的开箱实现是 `InMemoryChatMemoryStore`"）。**先把接口定下来、默认实现放内存**，这个顺序是对的：接口先行才有"换后端"的可能；默认内存则保证了"零配置也能跑"，代价是**开发者会把内存实现的行为误当成接口的语义**。
2. **同步接口上又加了一套异步的 `default` 方法**，而且默认实现**故意返回失败**：

```text
     * The default implementation returns a failed future carrying {@link AsyncNotSupportedException}: a store backed by blocking I/O is
     * <b>not</b> silently offloaded to a worker thread, because that would hide the fact that it is not truly
     * non-blocking.
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/ChatMemoryStore.java:58-60`

```text
    default CompletableFuture<List<ChatMessage>> getMessagesAsync(Object memoryId) {
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/store/memory/chat/ChatMemoryStore.java:68`

**这是"宁可失败，也不假装"的一个标准样本。** 一个阻塞 IO 的存储被放到异步 API 后面时，最省事的做法是"悄悄甩到线程池里"，调用方看起来拿到了非阻塞接口——但线程池的线程数会变成新的隐性瓶颈，而且**这个问题在压测里才会出现**。框架选择**直接返回失败**，把"我不支持真非阻塞"这个事实暴露在编译期之后、运行期之前的调用点上。

这条设计选择可以原样搬到你自己的接口上：**当你无法真正实现一个能力的语义时，返回明确的失败比返回一个"看起来能工作"的降级实现要安全**——因为降级实现的性能特征与原语义不同，而调用方是照原语义做容量规划的。

### 3.2 `spring-ai`：同一个接口，两个后端，两种并发语义

**JDBC 侧**写一次会话的方式是"删掉整段、重插整段"，而且包在一个事务里：

```text
		this.transactionTemplate.executeWithoutResult(status -> {
			deleteByConversationId(conversationId);
			this.jdbcTemplate.batchUpdate(this.dialect.getInsertMessageSql(),
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-jdbc/src/main/java/org/springframework/ai/chat/memory/repository/jdbc/JdbcChatMemoryRepository.java:122-124`

```text
		return "DELETE FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?";
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-jdbc/src/main/java/org/springframework/ai/chat/memory/repository/jdbc/JdbcChatMemoryRepositoryDialect.java:60`

```text
		return "INSERT INTO SPRING_AI_CHAT_MEMORY (conversation_id, content, type, timestamp, sequence_id) VALUES (?, ?, ?, ?, ?)";
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-jdbc/src/main/java/org/springframework/ai/chat/memory/repository/jdbc/JdbcChatMemoryRepositoryDialect.java:46`

**"删了再插"是一个明确的取舍：写放大换实现简单。** 好处是不需要处理"哪条是新的、哪条要改"，每次都是一个完整快照；坏处是①写入量随会话长度线性增长（一个 200 轮的会话，每次保存都重写 400 行）；②**它要求调用方提供完整的一段**，所以并发写必然互相覆盖——事务只保证"要么全删全插、要么不变"，**不保证两次保存的结果都留下**。

顺序键的来源也很值得注意：

```text
			// the batch index is a stable, database-portable ordering key.
			ps.setLong(5, i);
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-jdbc/src/main/java/org/springframework/ai/chat/memory/repository/jdbc/JdbcChatMemoryRepository.java:157-158`

```text
		return "SELECT content, type, timestamp FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ? ORDER BY sequence_id";
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-jdbc/src/main/java/org/springframework/ai/chat/memory/repository/jdbc/JdbcChatMemoryRepositoryDialect.java:39`

**顺序取自"批内下标"，注释给的理由是"stable, database-portable"。** 这句话点出了一个很实际的坑：如果用时间戳当顺序键，同一毫秒内的两条消息顺序就由数据库的物理顺序决定——**不同数据库、不同版本可能给出不同结果**。用下标则完全确定，而且不依赖时钟。

顺带看一个"有损落库必须留痕"的样本：

```text
					"JdbcChatMemoryRepository does not support tool call messages. Some messages were filtered out for conversation: "
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-jdbc/src/main/java/org/springframework/ai/chat/memory/repository/jdbc/JdbcChatMemoryRepository.java:118`

它**不支持工具调用消息**，落库前过滤掉，并且**打一条 warn**。这就是处理"存储能力 < 业务数据形状"时的正确姿势：不静默丢——**读者从日志里能看到"这次会话少了几条，且少的是哪一类"。**

**Redis 侧**则是另一套完全不同的并发语义——序号靠 Lua 脚本在服务端一次性预留：

```text
			// always reserve disjoint blocks of unique and increasing timestamps
			String script = "local exists = redis.call('EXISTS', KEYS[1]) " + "if exists == 0 then "
					+ "  redis.call('SET', KEYS[1], ARGV[1] + ARGV[2] - 1) " + "  return ARGV[1] " + "end "
					+ "return redis.call('INCRBY', KEYS[1], ARGV[2]) - ARGV[2] + 1";
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-redis/src/main/java/org/springframework/ai/chat/memory/repository/redis/RedisChatMemoryRepository.java:203-206`

**把同一件事放在两个后端上看，差异一目了然**：JDBC 靠"一个事务 + 整段替换"保证一致性，语义是"后写赢"；Redis 靠"一次 Lua 脚本 + 互不重叠的段"保证追加不撞号，语义是"都留下"。**同一个接口，两种并发语义**——这正是为什么 2.4 那条判据要把"并发安全"拆成三层：**接口层给不出这一层保证，它必须由实现来承诺，而你必须知道你用的是哪一个实现。**

Redis 侧的最后一个事实，是它的命名空间约定：

```text
	public static final String DEFAULT_KEY_PREFIX = "chat-memory:";
```

`spring-ai/memory-repositories/spring-ai-model-chat-memory-repository-redis/src/main/java/org/springframework/ai/chat/memory/repository/redis/RedisChatMemoryConfig.java:39`

**键前缀是一套系统在共享 Redis 上的第一道分隔**——更准确地说，它是"多套系统共用一个 Redis 时唯一的隔离"。如果你按租户隔离，这个前缀就是租户隔离的落点；如果你不隔离，那么一次 `FLUSHDB`（很多人排障时的手感动作）会清掉所有租户的会话。

### 3.3 本项目：已经外置的那一类，以及仍然留在本地的那一处

**已外置的样本**是断点续跑。它的存储表结构只有四列，但每一列都有理由：

```text
                            INSERT INTO resume_state (run_id, team_id, payload, updated_at)
                            VALUES (?, ?, ?::jsonb, now())
                            ON CONFLICT (run_id) DO UPDATE SET
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/PgResumeStore.java:45-47`

四列分别是：**身份**（`run_id`）、**隔离键**（`team_id`）、**载荷**（`payload`，整个断点序列化成一个 jsonb）、**存活判据**（`updated_at`）。这个四列结构可以直接复用到会话状态上——把 `run_id` 换成 `conversation_id`、`payload` 换成消息数组，其余三列的**职责**完全不变。

而"隔离键必须进每一条查询"这件事，在读取语句里体现得最清楚：

```text
                            "SELECT payload FROM resume_state WHERE run_id = ? AND team_id = ?",
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/PgResumeStore.java:64`

**单机时 `team_id` 是可选的（本地目录天然按团队分开了）；外置之后它变成了必需项**——因为所有团队的数据现在住在同一张表里。这条变化是外置带来的一次**隐式收紧**：原来靠"文件系统目录结构"实现的隔离，现在必须**写进每一条 SQL**。漏写一条，就是一次跨租户读到别人的数据。

外置的收益在命中日志里写得最直白：

```text
                        log.info("[Resume] 命中断点（跨实例共享）：runId={}, teamId={}", runId, teamId);
```

`code-review-agent/src/main/java/com/codereview/agent/core/store/PgResumeStore.java:68`

**"跨实例共享"这四个字被写进了 info 级日志**——这是一个正确的可观测性选择：当你想验证"外置到底有没有生效"时，这条日志是最直接的证据。

**仍然留在本地的那一处**，是一个诚实的缺口样本。在可选的工具循环里，文件读取工具的根目录来自本机磁盘：

```text
            registry.register(new com.codereview.kit.toolcalling.BuiltinTools.FileReadTool(
                    java.nio.file.Path.of(env.getProperty("review.data-dir", "./data"))));
```

`code-review-agent/src/main/java/com/codereview/agent/config/ReviewAgentConfig.java:581-582`

**为什么这算一个缺口**：同一个审查请求落到实例 A 还是实例 B，工具能看到的文件**不同**——因为它读的是**本机磁盘**上的 `./data`。这与 2.1 的槽位分析不冲突（它不是会话状态），但它是一个更普遍的规律：

> **"进程无状态"是一个需要逐个入口检查的属性，而不是一个靠一个开关就能保证的属性。** 存储层外置了，但**任何一个用本地路径做根的计算或读取，都会把本地状态重新引回系统**。

要把这个缺口说完整，还必须有边界条件（这决定了它是"理论问题"还是"现在要修"）：

```text
        if (Boolean.parseBoolean(env.getProperty("review.tools.agent-loop.enabled", "false"))
```

`code-review-agent/src/main/java/com/codereview/agent/config/ReviewAgentConfig.java:576`

**默认 `false`**：也就是说默认配置下这条路径不会被走到。所以准确的结论是"**这是一个在打开工具循环后才会显形的本地状态依赖**"——而不是"这个系统有本地状态"。**说清边界，比说了一个更吓人的结论更重要**：前者能让别人判断什么时候必须修，后者只会让人把这条结论归到"危言耸听"里。

## 四、避坑清单

- [ ] **别把"状态外置"当成一次重构，它是一次架构属性的变更。** 判据不是"代码改了"，而是"杀掉任意实例、会话不变"。
- [ ] **本地状态的病有两个，且互相独立**：① 跨实例不可见；② 无跨进程并发控制。把文件挪到共享存储只治了第一个。
- [ ] **先分槽位，再选存储。** 四个槽位（对话历史 / 运行中间态 / 长期记忆 / 会话租约）的可丢性完全不同，混在一起设计必然要么过度（全都持久化）要么不足（该持久化的留在了内存）。（§2.1）
- [ ] **"完成"和"过期"是两条独立的线。** 只做前者，一次崩溃就留一条永不回收的记录——**泄漏按次累积，不按比例**。（§2.1）
- [ ] **TTL 必须大于"正常推进时两次写入之间的最长间隔"。** 否则定时清理任务会开始吃正在跑的任务。（§2.1）
- [ ] **别拿性能当后端选型的首要判据。** 会话状态的差异在"谁管生命周期"：Redis 的 TTL 默认**永不过期**、PG 是写放大 + 顺序要自己管、混合多一个一致性窗口。（§2.2）
- [ ] **Redis 上一个会话要占多把键**（消息 + 序号计数器 + 可能的索引），它们必须**一起设置过期**，否则会出现"序号跳到未来"或"同一个会话两组同序号消息"。（§2.2）
- [ ] **不配 TTL 就等于选择永久保留。** `timeToLiveSeconds = -1` 是默认值——这在千万 DAU 下是一个合规问题，不是存储成本问题。（§2.2）
- [ ] **回退到内存的分支必须告警，且日志里要写出"多实例下会发生什么"。** 回退路径恰是最容易被忽略的一条，因为单机测试时它完全正常。
- [ ] **把"无本地文件"做成默认值，而不是可选优化。** 默认方向决定故障方向：默认无状态，配错在单机就暴露。（§2.2）
- [ ] **粘性会话不是方案，是概率。** 扩缩容、滚动发布、实例崩溃、切网络、LB 调权重，这五件事都是常态。（§2.3）
- [ ] **验证"真的能扩"的动作是：关掉粘性会话说，功能仍然完整。** 再加上"同一会话交替打两个实例，结果一致"。（§2.3）
- [ ] **`ConcurrentHashMap` 不解决任何跨进程问题，也不解决"读-改-写"的原子性。** 它只保证容器不损坏。（§2.4）
- [ ] **"整段覆盖"的写接口（`updateMessages` / `saveAll`）天然没有先后概念。** 并发写必然丢内容，且**静默**——没有异常、没有日志。
- [ ] **"并发安全"必须补全为"针对哪一层"**：容器级 / 行级 / 语义级。前两层存储给，第三层**只能业务自己设计**（版本号、租约、或者"保证只有一个写者"）。（§2.4）
- [ ] **行级 UPSERT 不等于语义级并发安全。** 它保证行不被写坏，但并发时仍然是"最后写赢"。用它之前先确认"业务上确实只有一个写者"。
- [ ] **优雅停机的窗口不必 ≥ 最长请求时长——前提是那个请求能从断点续跑。** 短窗口买到的是更快的发布；没有续跑能力时，短窗口等于丢进度。（§2.5）
- [ ] **drain 的顺序是：先摘流量，再发 SIGTERM。** 反了就变成"一边停机一边还在收新请求"。（§2.5）
- [ ] **有流式响应时，drain 的单位是"连接"不是"请求"。** 按平均请求时长算窗口，会切掉一批刚建立的长连接。
- [ ] **外置之后隔离键必须进每一条查询。** 单机靠目录结构实现的隔离，搬到一张表里就只剩 SQL 里的 `WHERE` 了。（§3.3）
- [ ] **"进程无状态"要逐个入口检查，不能只查存储层。** 任何一个用本地路径当根的计算或读取，都会把本地状态引回来。（§3.3）

## 五、动手任务

> **任务一：亲手复现"并发写丢一条"，并确认它是静默的。**
>
> **操作**：
> 1. 用 `InMemoryChatMemoryStore` 起一个最小程序，让它为一个固定的 `memoryId` 提供"读整段 → 追加一条 → 写回"的能力。
> 2. 用两个线程**同时**调用这个能力，各自追加一条不同的消息。
> 3. 打印最终读回的整段，并统计总共写入了多少条、实际留下多少条。
>
> **预期结果**：最终的消息条数**小于**写入次数（典型情况是 2 次写入只留下 1 条）。**关键在于同时确认：整个过程没有任何异常、没有任何 warn 及以上日志。** 这一条比"丢了几条"重要——它解释了为什么这类缺陷能活到生产环境。
>
> **任务二：验证"停掉外置开关"时你能不能观测到。**
>
> **操作**：
> 1. 在 `code-review-agent` 的配置里把 `pgvector.enabled` 置为 `false`。
> 2. 启动应用，检索启动日志中是否出现"回退内存"的告警。
> 3. 再把开关打开，重复一次，确认告警消失。
> 4. 最后做一次对照：**只在文件里改回退分支的实现，把那条 `log.warn` 删掉**，再重复第 1 步——确认此时**日志上完全看不出区别**。
>
> **预期结果**：第 2 步能看到明确的告警（`StateStoreConfig.java:99` 那条）；第 4 步看不到任何痕迹。**第 4 步是整个任务的重点**：它证明"回退路径的可观测性完全依赖那一行日志"，而不是依赖任何别的东西。
>
> **任务三：算清你系统的停机窗口该配多少。**
>
> **操作**：
> 1. 从监控里取单次请求处理时长的 P50 / P99（不是平均值）。
> 2. 取"断点（或等价的可续跑状态）的保存粒度"——即两次保存之间最长会丢多少工作。
> 3. 填入下表，得出二选一的结论：
>
> | 停机窗口 | 与 P99 的关系 | 单次被强杀会丢多少 | 结论 |
> |---|---|---|---|
> | ？ | ≥ P99 | 0 | 可以不依赖续跑 |
> | ？ | < P99 | 最多"一次保存粒度" | 必须依赖续跑，且要写下来 |
>
> **预期结果**：你会得到一句可交付的结论，形如"窗口 30 秒 < P99 300 秒，但断点按步保存（单步约 10 秒），因此最强杀损失约 5 秒工作量，占单次 3.3%，可接受"。**写不出这句话，说明这个参数是拍脑袋定的。**

---

## 本讲小结

1. **"进程不能有本地状态"的根因是生命周期不匹配**：状态想活几个月，进程可能只活几十分钟。把生命周期长的东西存在生命周期短的东西里，必然产生"结果随机、续跑失效、残留不清"这三种症状，而**它们都不报错**。
2. **本地状态有两个独立的病**：跨实例不可见、无跨进程并发控制。把文件挪到共享存储只治前一个——所以后端选型必须同时回答"并发写怎么保证"。
3. **会话状态有四个槽位**（对话历史 / 运行中间态 / 长期记忆 / 会话租约），判据不是"重不重要"而是"**丢了谁付代价**"。其中只有第四个是"多副本"才带来的。
4. **生命周期有四个事件，"完成"与"过期"必须各有一条线。** 只做"完成"，一次崩溃就留下一条永不回收的记录——**泄漏按次累积**。
5. **三种后端的差异不在性能，在"谁管生命周期"**：Redis 的 TTL 默认永不过期（还要多管一把计数键）、PG 是写放大 + 顺序要显式列、混合多一个一致性窗口。**选型是选你愿意付哪张账单。**
6. **粘性会话不是方案，是把架构问题降级成概率问题**——它在滚动发布、扩缩容、崩溃、切网络时都会失效，而失效的症状落在用户身上。验收判据是"关掉它，功能仍然完整"。
7. **"并发安全"必须分三层说**：容器级、行级、语义级。前两层存储给你，**第三层只能业务自己设计**。`ConcurrentHashMap` 与行级 UPSERT 都到不了第三层。
8. **短停机窗口是可以的——只要在途请求能从断点续跑。** "增量保存"换来的不只是正确性，还有**更快的发布**。写不出"窗口 / P99 / 每次丢多少"这三个数的，说明这个参数没算过。

**模块二到这里就结束了**：你现在有了一个完整的单 Agent——循环、工具、记忆、提示词、上下文、可靠性，以及**能让它横向扩展的状态外置**。

下一讲（第 17 讲）我们把一个 Agent 变成五个，并处理随之而来的新问题：**谁说了算、怎么并行、结果怎么合。**

「模块三 · 多 Agent 协作」见。
