# 第 03 讲 · 协议的暗礁：snake_case 与 camelCase

> 🎯 导读问题：**"你怎么保证接口契约不被静默破坏？"**

<img class="mermaid-svg" src="/zh/book-assets/diag-0009.svg" alt="🎯 导读问题：&quot;你怎么保证接口契约不被静默破坏？&quot;" />

> **图 03-0**　本讲地图：同一个 JVM 里两套命名并存，在全局 `ObjectMapper` 上设策略会污染自有端点；判据是**让命名策略跟着「是否在模拟外部协议」走**，由每个 DTO 自己声明。

## 一、痛点

上线第 12 天，财务发来一条消息：

> "成本报表里，9 月 3 日之后就没有数据了，是你们那边停止上报了吗？"

你打开监控，一切正常。查日志，**HTTP 200，零异常**。查数据库，补报记录确实存在——但点开一看：

| trace_id | prompt_tokens | completion_tokens | cost |
|---|---|---|---|
| *(null)* | `0` | `0` | `0` |

**记录写进去了，字段全是空的。**

你的第一反应可能是"服务端有 bug"。但服务端没有问题——它收到了一份**字段名完全对不上**的 JSON，然后**安安静静地**造了一个对象出来，所有字段走了默认值。

这就是这一讲的主题：**最危险的不是报错，是 HTTP 200 + 字段静默变 null。**

## 二、原理

### 2.1 两个世界在同一个 JSON 库里相撞

Java 的命名惯例是 `camelCase`：

```java
String traceId;  long promptTokens;  String providerCode;
```

而 OpenAI 协议（现在的事实标准）是 `snake_case`：

```json
{ "trace_id": "...", "prompt_tokens": 100, "provider_code": "deepseek" }
```

一个项目里同时存在这两种风格，是**必然的**——因为你既要模拟 OpenAI，又有自己的管理端点。

### 2.2 "聪明的省事"：在 ObjectMapper 上设全局命名策略

面对这个矛盾，最常见的"聪明做法"是：既然 OpenAI 端点是主线，那就在**全局**对象映射器上设一次：

```java
// ❌ 不要这样写
ObjectMapper mapper = new ObjectMapper();
mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
```

这样 OpenAI 兼容端点确实全对了。**但自有端点也一起被转成了 snake_case。**

于是：

```java
// 补报请求（自有端点），Java 里长这样
new UsageReportRequest(traceId, alias, providerCode, upstreamModel,
                       promptTokens, completionTokens, latencyMs, ...)
```

被全局策略**静默改写成**：

```json
{
  "trace_id": "...", "provider_code": "deepseek",
  "prompt_tokens": 100, "completion_tokens": 50, "latency_ms": 830
}
```

而服务端那份 DTO 是按 `camelCase` 声明的（它期望 `traceId` / `promptTokens`）。**字段名对不上，一个都读不进来。**

### 2.3 为什么这个问题极难被发现

三个条件叠加，把它变成了"看不见的故障"：

**第一，Jackson 对未知字段的默认行为被关掉了。**
Jackson 默认遇到 JSON 里存在、但对象里没有的字段会抛 `UnrecognizedPropertyException`。但这在生产里几乎人人都会关掉——因为你不希望上游加个字段就把你搞崩：

```java
mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
```

一旦关掉，**多出来的字段被静静丢弃，不报错，不留痕。**

**第二，反序列化能"成功"造出一个空对象。**
`long` 型字段拿不到值 → 变成 `0`；`String` 型 → 变成 `null`。对象构造成功，方法正常返回。

**第三，故障表现在"缺数据"，而不是"报错"。**
报表里少一行、某个字段是 0 —— 这类现象**没有人会第一时间去找研发**。它可能安静地存在几个月。

> **数量级直觉：一个字段变 null，账上到底丢多少？** 单看一笔，微不足道——一条补报记录（`cost=0`、`trace_id=null`），用示意价（输入约 1 元/百万 token、输出约 3 元/百万 token）算，输入 500 + 输出 20 的真实调用约 0.0005 元，**这条废数据的单笔损失约 0.0006 元**，一点不痛。但账不是这么算的：
>
> - 一个网关每天转 2000 次真实调用，峰值期更高频。若这类静默故障叠在一次新模型上量（你开大流量应对新供应商），**它让这一整天的成本曲线凭空缺掉一块**——不是 0.0006 元，而是"今天的总账单对不上、且**查不到是哪一笔**"。
> - 更危险的是语义：它抹掉的**不是任意一笔，而是排障锚点 `trace_id` 本身**。以后你怀疑某段流量异常，追踪链在这笔处断裂，只能整库扫。
>
> 这就是"HTTP 200 + 字段为 null + 无异常日志"被称为最危险组合的原因：**最小单笔损失微不足道，但它抹掉的是统计与排障上最关键的那一行，等于毁掉整条链的可审计性。**
>
> **工程判据：** 对这类"费用 / trace"关键字段，上报端另开一条**运行期非空校验**（`traceId` 非空、`cost > 0`，命中即告警），而不是等财务对不上账才发现——校验的成本是 0，防的是整月报表失真。

### 2.4 一句话判据

这个坑的解法不需要复杂设计，只需要一条**明确的判据**：

> **命名策略跟着"这个端点是不是在模拟 OpenAI"走，不跟着"项目的编码风格"走。**

- 模拟 OpenAI 的端点（`/v1/chat/completions`、`/v1/models`）→ **snake_case**，因为它是**协议**。
- 自有端点（`/v1/usage/report`、`/api/admin/*`）→ **camelCase**，因为它是**我们自己的接口**。

判据清楚之后，实现就顺理成章：**不在全局设策略，让每个 DTO 自己声明。**

**命名策略三选一之深度对比表**（选型速查，新增列把"判据"展开成决策信息）：

| 维度 | 全局 `SNAKE_CASE` | 全局 `camelCase` | 按 DTO `@JsonNaming` 声明 |
|---|---|---|---|
| **核心思想** | 一套命名策略吃所有端点 | 一套命名策略吃所有端点 | 判据跟着"是否模拟外部协议"走，每个 DTO 自声明 |
| **对 OpenAI 兼容端点** | 恰好正确 | 变 camelCase，协议不兼容 | 要 snake_case 的 DTO 显式声明 |
| **对自有端点** | 全转 snake_case，服务端读 null | 恰好正确 | camelCase 天然正确，**不动就是对的** |
| **故障表现** | HTTP 200 + 条约变 null（隐身） | 协议端直接 4xx（可见） | 无 |
| **隐患等级** | 最高——隐身、影响"费用/trace"关键字段 | 低——每次显性失败，好排查 | 无 |
| **维护成本** | 一次设置，最低 | 一次设置，最低 | 略高：每个对外协议 DTO 加注解 |
| **适用场景** | **绝不**（网关/产品层） | 纯内部系统、确定不模拟任何外部协议 | **网关与一切"模拟 OpenAI + 自有端点并存"的产品** |

**这张表的决策含义**：全局统一命名只有在"你 100% 确定永远不模拟外部协议"时才成立——而网关这类产品从出生就要模拟 OpenAI，所以它注定落不进前两列。**第三列多花的几条 `@JsonNaming` 注解，买的是"字段永不静默错位"。**

## 三、代码

### 3.1 `Json.java`：全局策略"故意不设"

`token-factory-client` 里那个共用的 `ObjectMapper`，注释写得比代码长——因为它记录的就是这节课的教训：

```java
// token-factory-client/src/main/java/io/tokenfactory/client/Json.java:12-29
/**
 * SDK 内部共用的 ObjectMapper。
 *
 * <p><b>不设全局命名策略</b>：命名规则由各个 DTO 自己用 {@code @JsonNaming} 声明。
 * 全局设成 snake_case 时，OpenAI 兼容端点确实对了，但自有端点（用量补报、额度查询）
 * 会被一起转成 snake_case——而服务端收的是 camelCase，结果是字段静默变成 null，
 * <b>不报错、只是账上少一笔钱</b>。这类问题只能靠联调撞见，所以从源头分开。
 */
static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
```

注意最后一行：`FAIL_ON_UNKNOWN_PROPERTIES` **也是关掉的**。这是对的（为了向前兼容），但正因为它关着，上面那个错误才不会被发现——**两个决策单独看都没错，放在一起就成了隐身衣**。

### 3.2 走 snake_case 的：模拟 OpenAI 的那些 DTO

<img class="mermaid-svg" src="/zh/book-assets/diag-0010.svg" alt="### 3.2 走 snake_case 的：模拟 OpenAI 的那些 DTO" />

> **图 03-1**　对外请求 DTO 的命名策略：`@JsonNaming(SnakeCaseStrategy)` 是**类级**注解，把同一个 record 的全部字段一次性转到 OpenAI 协议的 snake_case。**它是协议适配，不是编码风格**——所以只在跨协议的那一侧声明，绝不上提到全局 `ObjectMapper`。

它的类注释点明了理由：

```java
// token-factory/token-factory-client/src/main/java/io/tokenfactory/client/dto/ChatCompletionRequest.java:13-15
// 字段命名与 OpenAI /v1/chat/completions 完全一致，因此把 baseUrl 直接指向
// Token Factory 就能接入，无需改造任何调用代码——这也是这里显式声明 snake_case 的原因：
// 它是<b>协议</b>，不是本项目的编码风格。
```

响应侧同理，而且多了一层设计——**厂商扩展字段放在顶层**：

<img class="mermaid-svg" src="/zh/book-assets/diag-0011.svg" alt="响应侧同理，而且多了一层设计——厂商扩展字段放在顶层：" />

> **图 03-2**　对外响应 DTO 的两段式字段布局：**标准字段**（`id` / `object` / `created` / `model` / `choices` / `usage`）留在 OpenAI 兼容区；**厂商扩展字段**（`provider` / `upstream_model` / `cost_micros` / `trace_id` / `latency_ms`）放顶层，配合 `@JsonIgnoreProperties(ignoreUnknown = true)`。**扩展不塞进 `choices`**——老客户端不认识新字段是常态，"被新字段打崩"才是事故。

类注释说得很清楚：**前五个字段与 OpenAI 完全兼容**（标准 OpenAI SDK 能直接消费），后五个是扩展字段——**放在顶层而非塞进 `choices` 里**，这样标准客户端会自动忽略它们，兼容性不被破坏。

**这一段是面试的好素材**：它展示了一个高级设计原则——*扩展协议时，只增不改，且让不认识的客户端能安全忽略。*

还有个细节值得学：`content()` 方法在 `choices` 为空时返回**空串而不是 `null`**，注释写着"避免调用方到处判空"。**这就是"把防御做在库里面"的写法。**

### 3.3 走 camelCase 的：自有端点

对照一下同一个 SDK 里的补报请求——**一个命名注解都没有**：

<img class="mermaid-svg" src="/zh/book-assets/diag-0012.svg" alt="对照一下同一个 SDK 里的补报请求——一个命名注解都没有：" />

> **图 03-3**　自有端点 DTO 与它的"定时炸弹"：`UsageReportRequest` 全 camelCase、**一条命名注解都没有**，靠的是"全局 `ObjectMapper` 不设策略"这个**隐性契约**。一旦有人在全局设了 `SNAKE_CASE`，`traceId` 会静默变成 `trace_id`，上行接口字段名不匹配——**这就是第 03 讲 §2.2 那个"聪明的省事"的代价**。

**这就是"没有全局策略"的价值**：这个类什么都不用做，天然就是对的。而如果当初在全局设了 `SNAKE_CASE`，这个类会变成一颗定时炸弹。

同样的对比也出现在 `TokenFactoryClient.java` 的内部 DTO 里：

```java
// TokenFactoryClient.java:282-298
/** 错误响应信封（OpenAI 口径：{"error":{"code":..,"message":..}}）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ErrorEnvelope(ErrorBody error) {            // ← 自有信封，camelCase

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String code, String message) { }
}

/** /v1/models 响应，字段沿用 OpenAI 的 snake_case。 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
record ModelsResponse(List<ModelInfo> data) {      // ← 模拟 OpenAI，snake_case
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record ModelInfo(String id, String object, Long created, String ownedBy) { }
}
```

**同一个文件里，两个 DTO 两种策略**——而判据完全一致：`/v1/models` 在模拟 OpenAI，所以 snake_case；错误信封是我们自己的口径，所以 camelCase。

### 2.5 命名策略的工业界口径：三家 SDK 的默认行为对照

同一件"字段名谁说了算"的事，三家 Java/Kotlin 侧的大模型 SDK 给出了三种完全不同的答案。这不是风格差异，**它们各自用不同的机制解决了同一组风险**：

<img class="mermaid-svg" src="/zh/book-assets/diag-0013.svg" alt="同一件&quot;字段名谁说了算&quot;的事，三家 Java/Kotlin 侧的大模型 SDK 给出了三种完全不同的答案。这不是风格差异，它们各自用不同的机制解决了同一组风险：" />

> **图 03-4**　三家 SDK 的 JSON 命名口径：`openai-java` 走"**逐字段显式注解**"，`langchain4j` 走"**实现可替换的 SPI**"，`spring-ai` 走"**默认策略 + 不干预**"。三条路的共同点是**都没有用"在全局 `ObjectMapper` 上设 `SNAKE_CASE`"**——**这正是本讲的核心结论：全局命名策略是三家一致避开的选择。**

逐条看它们的原文，三种取舍的代价一目了然。

**第一条：`openai-java` 要求每个字段都标注解**，所以它自己的 SDK 反而要另建一个 mapper 绕开：

```text
// The SDK `ObjectMappers.jsonMapper()` requires that all fields of classes be marked with
// `@JsonProperty`, which is not desirable in this context, as it impedes usability. Therefore, a
// custom JSON mapper configuration is required.
```

`openai-java/openai-java-core/src/main/kotlin/com/openai/core/StructuredOutputs.kt:26-28`

```text
    @JsonProperty("access_token") val accessToken: String,
```

`openai-java/openai-java-core/src/main/kotlin/com/openai/auth/TokenExchangeResponse.kt:6`

它的收益体现在**连"前缀"这种细节都被测住了**：

```text
    @JsonProperty("is_active") @ExcludeMissing fun _isActive() = isActive
```

`openai-java/openai-java-core/src/test/kotlin/com/openai/core/ObjectMappersTest.kt:22`（对应的测试名是 `write_whenFieldPrefixedWithIs_keepsPrefix()`，第 `:26` 行）

**第二条：`langchain4j` 把 JSON 实现做成 SPI**，调用方根本不认识底层用的是哪个库：

```text
     * lets one implementation stand in for another without callers noticing which library is
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/internal/Json.java:28`

```text
        for (JsonCodecFactory factory : loadFactories(JsonCodecFactory.class)) {
```

`langchain4j/langchain4j-core/src/main/java/dev/langchain4j/internal/Json.java:80`

**第三条：`spring-ai` 在自己的 OpenAI 路径上用的是一个"什么都不设"的 mapper**：

```text
    private static final ObjectMapper objectMapper = new ObjectMapper();
```

`spring-ai/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java:142`

而在它更新的 Responses 路径上，又直接复用了 `openai-java` 那个"要求全字段注解"的 mapper：

```text
    private static final JsonMapper jsonMapper = ObjectMappers.jsonMapper();
```

`spring-ai/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/responses/ResponsesRequestBuilder.java:80`

> **这三条放在一起，得出一个比"别设全局策略"更硬的结论**：命名口径必须**跟着边界走**，而不是跟着项目走。同一个进程里可以并存三种命名风格——只要你知道**每一侧归谁管**。`spring-ai` 自己就在一个仓里同时用了两种（第 `:142` 行与第 `:80` 行），它们并不冲突，因为它们管的是不同的边。

### 2.6 一手数据：本项目 DTO 的命名风格分布与复算方式

上面三条是"别人怎么做"。现在看你手上这套系统**自己的分布**——它同时也是判断"有没有把策略设歪"的最快办法。

> **一手数据（可复算）**：`token-factory` 仓共 **100 个 Java 文件**，其中**只有 8 个**出现 `@JsonNaming`，**只有 2 个**出现 `@JsonProperty`。这 8 个里有 6 个是 DTO：
> - client 侧：`token-factory-client/src/main/java/io/tokenfactory/client/dto/ChatCompletionRequest.java`、`ChatCompletionResponse.java`
> - server 侧：`token-factory-server/src/main/java/io/tokenfactory/server/api/dto/ChatCompletionRequest.java`、`ChatCompletionResponse.java`、`ChatCompletionChunk.java`
> - 另外 2 个不是 DTO：`token-factory-client/src/main/java/io/tokenfactory/client/Json.java`（建 mapper 的地方）与 `TokenFactoryClient.java`
>
> 复算方式：`grep -rl "@JsonNaming" --include='*.java' .`，再对每个文件看它是不是 DTO。

这个分布本身就是判据：**命名注解的"面积"应该只覆盖跨协议的那条边，而不是整个工程**。8/100 是一个健康的比例；如果你的工程是 80/100，说明你把协议适配当成编码风格了。

一次真实事故的两个数字，能说明另一件事：

| 事实 | 数字 | 出处 |
|---|---|---|
| `openai-java` 里显式 `@JsonProperty` 的出现次数 | **21177** | 全仓 `grep -o '@JsonProperty' | wc -l` |
| 它测试里专门盯"`is` 前缀不被吃"的用例 | 1 个（`ObjectMappersTest.kt:26`） | 同上 |

**21177 处显式注解换来的，是"字段名永不漂移"**；而 1 个用例说明**连 `getIsActive()` 会被 Jackson 读成 `active` 这种冷门行为，都有人替你踩过并测住了**。这就是"用模板代码换确定性"的价格与收益——**如果你的系统要靠字段名对齐上游，这个价格是值得付的。**

**搬到你自己系统**，三步复算：

1. 数出你的"命名注解面积"：`grep -rl "@JsonNaming\|@JsonProperty" --include='*.java' . | wc -l`，再除以总文件数。
2. 在全局 `Json` / `ObjectMapper` 配置类里搜三样东西：`SNAKE_CASE`、`PropertyNamingStrategies`、`setPropertyNamingStrategy`。**只要有一处，就是一个潜在的跨协议炸弹。**
3. 把注解所在的文件**按目录分组**。如果它们不集中在"对接外部协议"的那一侧，说明口径已经渗到自有端点了。

**判据**：命名注解应当**只出现在跨协议边的一侧，并且能被一条命令列全**。列不全，就是已经漏了。

### 2.7 搬到你自己系统：跨协议边的契约测试怎么写

命名风格问题最难的地方是**它极难被发现**（本讲 §2.3 已经讲过为什么）。所以它的防线不能是"小心一点"，只能是**机械化的契约测试**。

<img class="mermaid-svg" src="/zh/book-assets/diag-0014.svg" alt="命名风格问题最难的地方是它极难被发现（本讲 §2.3 已经讲过为什么）。所以它的防线不能是&quot;小心一点&quot;，只能是机械化的契约测试。" />

> **图 03-5**　跨协议边的四道契约测试：**①②管"对外那一侧"，③管"往返一致性"，④管"自家端点没被污染"**。第 ④ 条是最容易被忽略、也最值钱的一条——因为全局策略这类错误**只会从自有端点这一侧暴露出来**（对外那一侧被注解保护着，看不出问题）。

| 工业界 / 本项目做法 | 你自己系统里该问的问题 |
|---|---|
| 每条边各自声明命名口径（`openai-java` 全注解 / `spring-ai` 两条路两种 mapper） | 你有几条跨协议边？每条边归谁定义字段名？ |
| `langchain4j` 用 SPI 让"换 JSON 库"不动调用方 | 你换掉 Jackson 需要改几个文件？ |
| 本仓 `Json.java` 里**故意不设**全局策略，并把理由写进类头 | 你的全局配置里，有没有一处"为了省事"设的策略？ |
| `openai-java` 为 `is` 前缀专门写了用例 | 你有没有为"自己的字段名"写过一条断言？ |

**搬到你自己系统**，四步：

1. **给每条跨协议边建一个测试类**，名字里带上边名（如 `OpenAiWireFormatTest`）。
2. **写一条金样本**：把一份真实的请求 JSON 逐字贴进测试，断言"序列化结果与它逐字相等"。**逐字，不是等价**——字段顺序可以放宽，**字段名一个字符都不能放宽**。
3. **补一条自有端点的负向断言**：拿你的内网 DTO 序列化一次，断言输出里**不含下划线**。这条专门用来抓"全局策略被改坏"。
4. **把这三条挂到 CI 的必过门禁上**，并在注释里写明"为什么不能改成等价比较"。

**判据**：**跨协议边的正确性只能靠"逐字比对"证明，不能靠"看起来对"**。任何一条依赖"我记得当时是对的"的边，都还没有验收。

## 四、避坑清单

- [ ] **绝不 `setPropertyNamingStrategy` 到全局。** 让每个 DTO 用 `@JsonNaming` 自己声明。
- [ ] **命名策略的判据是"是否在模拟外部协议"**，不是"项目风格"。外部协议用它们的，自有端点用你自己的。
- [ ] **`FAIL_ON_UNKNOWN_PROPERTIES` 关掉时要留痕**：至少在服务端日志里统计未知字段名，否则"字段对不上"会永远隐身。
- [ ] **契约测试要断言 JSON 的 key 名**，不能只断言对象序列化成功。`assertThat(json).contains("\"max_tokens\"")` 比 `assertEquals(expected, actual)`（对象比较）有用得多。
- [ ] **给扩展字段显式加 `@JsonProperty`。** 依赖隐式命名推导，等于把契约交给框架的默认值。
- [ ] **记住这个组合最危险**：`HTTP 200` + `字段为 null` + `无异常日志`。见到它就往"契约对不上"上查。
- [ ] **验收时要看数据，不只看接口**。若当初财务没发现，这个 bug 能活一年。
- [ ] **别把跨协议边的"逐字比对"降级成"等价比较"。** 字段顺序可以放宽，**字段名一个字符都不能放宽**——命名漂移恰恰是"看起来等价"的那种错。（§2.7）
- [ ] **给自有端点专门写一条"输出里不含下划线"的负向断言。** 全局策略被改坏时，对外那一侧因为有注解保护而看不出问题，**只有自有端点这一侧会暴露**。（§2.7）
- [ ] **命名注解要能一条命令列全，并且只出现在跨协议边的一侧。** 列不全说明口径已经渗到自家端点；本书参考仓的实测比例是 **8 / 100 个 Java 文件**。（§2.6）

## 五、动手任务

> **任务**：亲手把这个 bug 造出来，再看着测试把它抓住。
>
> **仓库位置**：`token-factory / token-factory-client`
>
> **操作**：
> 1. 打开 `Json.java`，给 `MAPPER` 加上一行"聪明"的全局策略：
>    ```java
>    .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
>    ```
> 2. 跑 `token-factory-client` 的测试：
>    ```bash
>    mvn -o -pl token-factory-client test
>    ```
> 3. 观察哪些测试变红，以及**红在哪一行断言上**。
> 4. 撤销这行改动，确认测试恢复全绿。
>
> **预期结果**：
> - 与 `/v1/chat/completions` 相关的测试**仍然是绿的**（例如 `assertTrue(call.body().contains("\"model\":\"default\""))`——`model` 是单个词，转不转 snake_case 都一样）。**这解释了为什么这个 bug 这么容易漏出去。**
> - **`usage_report_sends_trace_and_token_counts`**（`TokenFactoryClientTest.java:157`）**变红**。它有两行断言正对着这个坑：
>   ```java
>   assertTrue(call.body().contains("\"traceId\":\"trace-999\""), "补报必须带上 traceId，否则无法溯源");
>   assertFalse(call.body().contains("trace_id"), "自有端点用 camelCase，snake_case 会让服务端读到 null");
>   ```
> - 打开这个测试方法的注释（第 153–155 行），读一遍——**它就是这一讲的故事，写在一个真实仓库里**：
>   > "这里曾是 snake_case：SDK 全局 ObjectMapper 设了 snake_case 命名策略，于是 traceId 被序列化成 trace_id，服务端（收 camelCase）反序列化成 null，**不报错，只是补报记录变成一条没有 traceId、费用为 0 的废数据**。"
>
> - 如果你发现**没有任何测试变红**，那说明你的契约测试存在缺口——请补一个断言 JSON key 名的测试。这个发现比任务本身更有价值。

---

## 本讲小结

1. **HTTP 200 + 字段静默 null 是最危险的一类故障**，因为它不报错、不留痕，只让数据悄悄缺失。
2. 根因是**在全局对象映射器上设了命名策略**，污染了本来正确的自有端点。
3. 解法是一条判据：**命名策略跟着"是否在模拟外部协议"走，由每个 DTO 自己声明。**
4. 还顺手学到一个高阶设计：**扩展协议时只增不改，并让不认识的客户端能安全忽略**——厂商扩展字段放顶层，而不是塞进 `choices` 里。

下一讲我们进入流式与超时的世界——**在那里你会看到另一类"看起来配了、其实没生效"的坑**。
