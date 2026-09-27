# 第 02 讲 · 十分钟跑通第一次 LLM 调用


> 本讲是全书**唯一**一讲"以跑通为目标"的课。先拿到正反馈，再谈架构。

<img class="mermaid-svg" src="/zh/book-assets/diag-0006.svg" alt="本讲是全书唯一一讲&quot;以跑通为目标&quot;的课。先拿到正反馈，再谈架构。" />

> **图 02-0**　本讲地图：所有大模型 HTTP API 长一个样，所以正确的位置是**网关**而不是业务代码；本讲先让你 10 分钟跑通，再谈理解。

## 一、痛点

第一次接大模型，你会被一堆 SDK 拦住：

- 厂商官方 SDK（一家一个样）
- `LangChain4j`、`Spring AI`（要先学 Client / Model / Chain / Memory 一堆抽象）
- 直接拼 HTTP（最省事，但你得自己处理鉴权、超时、重试）

**但真正的问题不是"选哪个 SDK"，而是"业务代码该不该直接依赖某个厂商 SDK"。**

答案很明确：**不该。**

因为半年后你大概率会换模型，或者遇到"主通道挂了要切备用通道"。如果你的业务代码里到处是 `new DeepSeekClient(...)`，那一天你会改到怀疑人生。

所以在写第一行调用代码之前，我们要先把**位置**摆对。

## 二、原理

### 2.1 所有大模型 HTTP API 其实长得一样

这不是巧合。OpenAI 的 `/v1/chat/completions` 已经成为事实标准，几乎所有厂商都在向它对齐。它的请求体只有五个关键字段：

```json
{
  "model": "default",
  "messages": [
    { "role": "system",    "content": "你是一个资深代码审查专家" },
    { "role": "user",      "content": "审查这段 diff ..." }
  ],
  "temperature": 0.2,
  "max_tokens": 2048,
  "stream": false
}
```

逐个解释（这几个字段后面每一讲都会用到）：

| 字段 | 作用 | 生产环境的注意点 |
|---|---|---|
| `model` | 要哪个模型 | **用别名，不用厂商真实模型名** |
| `messages` | 对话历史 | `role` 只有三种：`system` / `user` / `assistant` |
| `temperature` | 随机性 | 审查类任务要低（0.2），创作类才高 |
| `max_tokens` | 输出上限 | **不设就是敞口**，一次跑飞可能很贵 |
| `stream` | 是否流式 | 流式的计量口径不同，见下 |

**只要这个形状对了，接口就能互换。** 这就是为什么第 02 讲我们用的是"把 `baseUrl` 指过去"的方案——因为 OpenAI 兼容端点意味着**你甚至可以不加任何 SDK，直接用 `curl`**。

### 2.2 所以，正确的位置是"网关"，不是"业务代码"

本书的主线项目 `code-review-agent` 不会直接调用任何厂商。它调用的是 `token-factory` 网关，只认一个模型别名 `default`：

```
code-review-agent  ──►  token-factory  ──►  deepseek / 混元 / GLM / OpenAI ...
   （认别名 default）        （路由表决定去哪个厂商）
```

换厂商时，改的是**网关侧的一张路由表**，业务代码一行不动。

这个设计不是我们发明的，但它是一个 AI 应用能不能长期活下去的分水岭。第 36 讲会完整讲网关的内部设计；**这一讲你只需要把它当成"一个地址 + 一个密钥"。**

### 2.3 先跑通，再理解

我见过太多人卡在"环境没配好"这一步上然后放弃。所以这一讲刻意**不讲任何原理**（原理在 04–08 讲），只做一件事：**让一个真实的 LLM 调用返回一句话。**

## 三、代码

### 3.1 起服务

`token-factory` 需要 PostgreSQL。连接串全部走环境变量，不写进配置文件：

```bash
export TF_DATASOURCE_URL=jdbc:postgresql://localhost:5432/token_factory
export TF_DATASOURCE_USERNAME=postgres
export TF_DATASOURCE_PASSWORD=...
export TF_ADMIN_KEY=$(openssl rand -base64 32)

mvn -o -pl token-factory-server spring-boot:run   # 默认端口 8090
```

或者用 Docker：

```bash
docker compose up -d
```

> 注意默认端口是 **8090**，不是 8080。后面的 `curl` 和客户端都按 8090。

### 3.2 建租户、发 AK、配路由

三个 `curl`，管理面用 `X-Admin-Key` 认证：

```bash
# 1) 建租户
curl -X POST localhost:8090/api/admin/tenants \
  -H "X-Admin-Key: $TF_ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"code":"acme","name":"Acme 研发"}'

# 2) 发 AK（明文只在这一次响应里出现，库里只存 SHA-256）
curl -X POST localhost:8090/api/admin/keys \
  -H "X-Admin-Key: $TF_ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"name":"acme-prod","tenantCode":"acme"}'
# → {"accessKey":"tf_xxxx...","keyId":"..."}

# 3) 登记供应商（apiKeyEnv 只存环境变量名，绝不落库明文）
curl -X POST localhost:8090/api/admin/providers \
  -H "X-Admin-Key: $TF_ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"code":"deepseek","baseUrl":"https://api.deepseek.com/v1",
       "apiKeyEnv":"DEEPSEEK_API_KEY","protocol":"OPENAI_COMPAT","weight":100}'

# 4) 配模型别名路由（业务方只认别名 default，换厂商不用改业务代码）
curl -X POST localhost:8090/api/admin/routes \
  -H "X-Admin-Key: $TF_ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"alias":"default","providerCode":"deepseek","upstreamModel":"deepseek-chat","priority":1}'
```

这里有两处设计**现在就值得记住**，第 37 讲会展开：

- **AK 明文只在创建响应里出现一次**，库里只有 SHA-256。这意味着 AK 丢了只能重新签发，找不回来——这是刻意的。
- **厂商密钥只存"环境变量名"**（`apiKeyEnv`）。库被拖走也拿不到任何一把真实 Key。

### 3.3 业务方调用（Java）

最省事的验证方式是 `curl`：

```bash
curl localhost:8090/v1/chat/completions \
  -H "Authorization: Bearer $TOKEN_FACTORY_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"default","messages":[{"role":"user","content":"hi"}]}'
```

但在 Java 项目里，用 SDK 更顺手：

```java
TokenFactoryClient client = TokenFactoryClient.builder()
        .baseUrl("http://localhost:8090")
        .accessKey(System.getenv("TOKEN_FACTORY_KEY"))
        .appId("my-service")
        .build();

ChatCompletionResponse res = client.chat(
        ChatCompletionRequest.builder("default")          // 工厂侧的模型别名
                .system("你是一个资深代码审查专家")
                .user("审查这段 diff ...")
                .temperature(0.2)
                .build());

System.out.println(res.content());      // 模型输出
System.out.println(res.traceId());      // 链路 ID，可去工厂查全链路
System.out.println(res.costMicros());   // 本次费用（微元）
System.out.println(res.provider());     // 实际命中的厂商
```

四个 `println` 里，**后三个才是这本书的关注点**：

- `res.traceId()` —— 拿它调 `GET /v1/usage/trace/{traceId}` 能看到这次调用经过了哪个路由、哪个上游、耗时多少。**可观测性从第一行代码就要有。**
- `res.costMicros()` —— 费用单位是**微元**，`1 元 = 1_000_000 微元`。所以 `costMicros() = 12000` 表示这次调用花了 **0.012 元**。为什么不用 `double` 记钱？第 38 讲会给你看账目对不上的现场。
- `res.provider()` —— 实际命中的厂商。你可能配的是 A，但因为熔断切到了 B，**这个字段会告诉你真相**。

### 3.4 跑通之后：这笔调用的账与时钟

`hi` 那一次的 `cost_micros ≈ 0.012 元` 只剩练手感的价值，别拿它当成本基准。跑通后你要会**估算一次真实审查调用**的 token 预算和期望时长——这正是后面流式与超时（第 04 讲）、成本计量（第 38 讲）的起算点。用示意价（输入约 1 元/百万 token、输出约 3 元/百万 token，仅作量级参考）：

> **数量级直觉：一个 PR 的调用，账和时钟怎么算？**
>
> - **token 预算**：一个 PR 拆成 N 个 diff 文件，每个文件平均 M token，整段 diff 塞一个上下文，输入 ≈ N×M + system。例如 N=20 文件 × M=500 token → 输入 10k；输出按这次审查的量约 2k。
>   - 成本：输入 `10k/1M×1元=0.01元`，输出 `2k/1M×3元=0.006元` → **≈ 0.016 元／次**；
>   - 这次调用 `cost_micros` 应该落在 `16000` 微元附近——而刚才的 `hi` 只有 12000 微元。**一次真实审查和一次 ping 的差距远没你想的大，差的量级主要来自"多少个 diff、多长的上下文"，而不是单 token 单价。**
> - **期望时钟**：流式生成按约 60 token/s（示意），输出 2k token → 生成 ≈ 33s，这是**没有重试**的乐观时长。若需要默认 2 次重试、每次叠加前一次的整段往返，期望时长 ≈ `33 × 3 ≈ 100s`。
>   - **这一算就暴露了 04 讲的坑**：若网关把超时随便设成 60s，第一次调用就会在满负荷 PR 上超时，然后吃掉重试预算——**你以为在"设超时"，其实在"漏掉成功路径"。**
>
> **工程判据：** 给任何一次"审查级"调用设超时前，先算这条期望时长（输出 token ÷ 生成速度 × (1+重试次数)），再把超时设在它的 1.5～2 倍——**别拿 ping 的毫秒当审查的分**。

### 2.4 网关的两种交付形态：进程内 SDK 与独立服务

你现在跑通的这一次调用，走的是 `token-factory` 的**独立服务**形态。但工业界两种形态都存在，而且**它们的失败面完全不同**。

先看两条路径：

<img class="mermaid-svg" src="/zh/book-assets/diag-0007.svg" alt="先看两条路径：" />

> **图 02-1**　两种交付形态的失败面差异：**SDK 形态把"多一跳"省掉，但把配额、密钥、重试全部散进业务进程**；服务形态多一次 RTT 与一个可故障组件，换来的是"所有调用可数、可限、可查"。判据是**你要不要跨进程治理**——如果只需要把密钥藏起来，SDK 就够；如果还要按租户配额、要统一降级、要一份全局账单，就必须是服务。

**这里有一处很多人会漏掉的细节**：SDK 形态其实没有被"淘汰"，它活在**服务形态的内部**。LiteLLM 的网关进程里，真正发 HTTP 请求的也是 SDK；本书主线的 `ModelGateway`（`ModelGateway.java`），在同一个 JVM 里做的又是"SDK 形态"的一件事。所以正确的问法不是"选 SDK 还是选服务"，而是 **"治理边界画在哪一跳上"**。

`openai-java` 里有一个字段专门为这件事存在：

```kotlin
    private val baseUrl: String?,
```

`openai-java/openai-java-core/src/main/kotlin/com/openai/core/ClientOptions.kt:89`

**`baseUrl` 可覆盖，是"进程内 SDK 也能接网关"的唯一开关**——不改任何业务代码，把地址从官方域名改成自己的网关，就从"直连"变成了"受治理"。这也解释了为什么本书的 `ChatCompletionRequest` 要逐字对齐 OpenAI 协议（第 03 讲）：**协议一致是这条替换能成立的前提。**

### 2.5 工业界怎么做：一个网关的配置里有什么

一个生产级网关的配置，说到底是四段：**Key → 模型清单 → 路由 → 上游**。LiteLLM 把它落在代码仓里，而不是运行时查数据库：

| 段 | 内容 | 可回源索引 |
|---|---|---|
| **Key** | 一个虚拟 Key 上并行挂六类配额维度：钱、并发、速率、重置周期、模型分档、生命周期 | `litellm/litellm/models/verification_token.py:16-69` |
| **模型清单** | 价目表随代码仓发版，**4378 个顶层模型条目** | `litellm/model_prices_and_context_window.json` |
| **路由** | 五种策略可切换 + 冷却与分类回退 | `litellm/litellm/router.py:811`/`:857` |
| **上游** | 每供应商一份超时预算（connect / read / write / request） | `openai-java/openai-java-core/src/main/kotlin/com/openai/core/Timeout.kt:11-58` |

<img class="mermaid-svg" src="/zh/book-assets/diag-0008.svg" alt="| 上游 | 每供应商一份超时预算（connect / read / write / request） | `openai-java/openai-java-core/src/main/kotlin/com/openai/core/Timeout.kt:11-58` |" />

> **图 02-2**　生产级网关配置的四段：**Key 管"给谁多少"，模型清单管"单价与窗口"，路由管"选哪条路"，上游管"等多久"**。四段的**变化频率差三个数量级**——价目表随发版、路由按事故调、Key 按客户开、超时按供应商配。混在一个配置文件里，就是"改一个数要重启全部"（新 43 讲）。

三个可回源的事实，值得你逐字看一眼，因为它们回答了"配置该长什么样"：

```text
    max_budget: float | None = None
    max_parallel_requests: int | None = None
    tpm_limit: int | None = None
    rpm_limit: int | None = None
```

`litellm/litellm/models/verification_token.py:22`、`:31`、`:33`、`:34`

```text
        routing_strategy: RoutingStrategyName = "simple-shuffle",
```

`litellm/litellm/router.py:811`

```kotlin
    fun connect(): Duration = connect ?: Duration.ofMinutes(1)
```

`openai-java/openai-java-core/src/main/kotlin/com/openai/core/Timeout.kt:24`

> **一手数据**：LiteLLM 的价目表文件 `model_prices_and_context_window.json` 是 **2943129 字节 / 77607 行**、**4378 个顶层模型条目**；Key 模型 `verification_token.py` 是 **2718 字节 / 80 行**。复算方式：`python3 -c "import json;d=json.load(open('litellm/model_prices_and_context_window.json'));print(len(d))"` 与 `wc -c -l <file>`。
> 这两个数字合起来说明一件事：**价目表是数据、会一直长；Key 模型是结构、基本不变。** 把它们放进同一个"配置文件"里管理，是体积与频率的双重错配。

### 2.6 一手数据与搬到你自己系统：第一次调用要固定下来的四个量

跑通一次调用之后，**先别急着写业务**。把下面四个量固定下来，否则后面每一讲你都会重新讨论一遍：

| 量 | 为什么是它 | 本项目取值 / 出处 |
|---|---|---|
| **协议基线** | 决定以后能不能换供应商、能不能插网关 | OpenAI 兼容（`baseUrl` 可覆盖） |
| **超时预算** | 决定"慢"是不是等于"失败" | `openai-java` 默认 `connect` 60s / `request` 600s（`Timeout.kt:24`、`:54`） |
| **重试口径** | 决定一次失败会放大成几次账单 | `request` 的默认值**不含重试**（`Timeout.kt:45` 注释原文） |
| **计量粒度** | 决定账单能不能拆到租户 / 供应商 | 本书 `token-factory` 按调用记 spend（第 38 讲详述） |

| 工业界 / 本项目做法 | 你自己系统里该问的问题 |
|---|---|
| 价目表随代码发版（LiteLLM 4378 条） | 你的单价从哪来？谁负责更新？变了怎么回滚？ |
| 超时是四层而不是一个数 | 你的 `read` 超时和总时长分开了吗？`read` 是"两包之间"还是"整段"？ |
| 虚拟 Key 上挂六类配额 | 你现在的"一个 API Key"能表达几种限额？ |
| 路由策略可切换且默认 `simple-shuffle` | 你的路由是可配的，还是写死在 `if/else` 里？ |

**搬到你自己系统**，四步：

1. **写死协议基线**：把你的请求 / 响应对齐到一个公开协议（OpenAI 兼容最省事），并让 `baseUrl` 可覆盖。
2. **把超时拆开**：至少区分"连接""首字节""总时长"三个量，并明确三者是否包含重试。
3. **把单价数据化**：单价不要写进代码逻辑，放成一份可版本化的数据文件。
4. **给每个 Key 加两个数**：并发上限与速率上限。**先加上去，哪怕暂时都是 `-1`**——没有这两个字段，第 09 讲的配额就没有落点。

**判据**：如果你现在无法回答"这一次调用，最坏情况下会花多少钱、最多会花多久"，那么你的接入层还没有完成——**不是因为功能缺失，是因为边界没有固定。**

## 四、避坑清单

- [ ] **`max_tokens` 一定要设。** 不设等于给模型一张无限额度的卡。
- [ ] **审查 / 抽取类任务 `temperature` 给低**（0.1–0.3）。温度高会让同一个 diff 每次审出不同结果，评测根本没法做。
- [ ] **不要把 API Key 写进代码或配置文件。** 走环境变量，或走网关的 AK。
- [ ] **不要让业务代码直接 `new` 厂商 SDK 的 Client。** 半年后换模型时你会哭——这是本书坚持"网关前置"的根本原因。
- [ ] **注意默认端口是 8090**，别照抄成 8080。
- [ ] **`stream=true` 会返回 501（暂不支持）**，这是**刻意设计**，不是 bug。原因：流式的中断计量口径不同（用户点了停止，这次算多少钱？），网关宁愿明确拒绝，也不给你一个算错的数。（第 04 讲展开）
- [ ] **别把网关当成"多一跳的损耗"。** 这一跳换来的是配额、密钥、账单、降级四件事的落点；如果你只把它当代理用，那它确实只剩损耗——**损耗是你用法的结果，不是它的属性**。（§2.4）
- [ ] **别在一个配置文件里混装"四段配置"。** Key、模型清单、路由、上游的变化频率差三个数量级；混装的下场是"改一个数重启全集群"（新 43 讲）。（§2.5）
- [ ] **别用"单次单价 × 次数"估成本。** 先把协议基线、超时预算、重试口径、计量粒度这四个量固定下来，否则第 38 讲的账你一定算不对。（§2.6）

## 五、动手任务

> **任务**：让一次真实的 LLM 调用返回内容，并把它的成本与链路查出来。
>
> **仓库位置**：`token-factory`（`token-factory-server` + `token-factory-client`）
>
> **操作**：
> 1. 按 3.1 起服务（Docker 或 `mvn spring-boot:run`）；
> 2. 按 3.2 建租户 / 发 AK / 配供应商与路由；
> 3. 用 3.3 的 `curl` 或 Java 客户端发起一次调用；
> 4. 记下返回的 `trace_id`，然后：
>    ```bash
>    curl localhost:8090/v1/usage/trace/<traceId> \
>      -H "Authorization: Bearer $TOKEN_FACTORY_KEY"
>    ```
>
> **预期结果**：
> - 第 3 步返回一句话；
> - 第 4 步能看到这次调用的完整链路（走了哪个路由、命中了哪个上游、耗时、费用）；
> - `cost_micros` 是一个整数。把它除以 **1,000,000**，就是你这次调用花掉的人民币。

---

## 本讲小结

1. 所有大模型 HTTP API 都长得像 `/v1/chat/completions`——`model` 用**别名**，不要用厂商真实模型名。
2. **业务代码不该直接依赖厂商 SDK**，网关前置是长期能活下去的前提。
3. 从第一行代码就要有 `traceId` / `costMicros` / `provider` 这三个"可观测锚点"。
4. `max_tokens` 必设，`temperature` 审查场景要低，`stream=true` 是刻意不支持。

下一讲我们看第一个"静默失败"的坑——它不报错、不影响运行，**只是悄悄地让你的账少一块钱**。
