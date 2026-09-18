# 第 02 讲 · 十分钟跑通第一次 LLM 调用

> 本讲是全书**唯一**一讲"以跑通为目标"的课。先拿到正反馈，再谈架构。

<img class="mermaid-svg" src="/zh/book-assets/diag-0024.svg" alt="本讲是全书唯一一讲"以跑通为目标"的课。先拿到正反馈，再谈架构。" />


> **图 2-0**　本讲地图：所有大模型 HTTP API 长一个样，所以正确的位置是**网关**而不是业务代码；本讲先让你 10 分钟跑通，再谈理解。

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

这个设计不是我们发明的，但它是一个 AI 应用能不能长期活下去的分水岭。第 30 讲会完整讲网关的内部设计；**这一讲你只需要把它当成"一个地址 + 一个密钥"。**

### 2.3 先跑通，再理解

我见过太多人卡在"环境没配好"这一步上然后放弃。所以这一讲刻意**不讲任何原理**（原理在 04–07 讲），只做一件事：**让一个真实的 LLM 调用返回一句话。**

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

这里有两处设计**现在就值得记住**，第 31 讲会展开：

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
- `res.costMicros()` —— 费用单位是**微元**，`1 元 = 1_000_000 微元`。所以 `costMicros() = 12000` 表示这次调用花了 **0.012 元**。为什么不用 `double` 记钱？第 32 讲会给你看账目对不上的现场。
- `res.provider()` —— 实际命中的厂商。你可能配的是 A，但因为熔断切到了 B，**这个字段会告诉你真相**。

### 3.4 跑通之后：这笔调用的账与时钟

`hi` 那一次的 `cost_micros ≈ 0.012 元` 只剩练手感的价值，别拿它当成本基准。跑通后你要会**估算一次真实审查调用**的 token 预算和期望时长——这正是后面流式与超时（第 04 讲）、成本计量（第 32 讲）的起算点。用示意价（输入约 1 元/百万 token、输出约 3 元/百万 token，仅作量级参考）：

> **数量级直觉：一个 PR 的调用，账和时钟怎么算？**
>
> - **token 预算**：一个 PR 拆成 N 个 diff 文件，每个文件平均 M token，整段 diff 塞一个上下文，输入 ≈ N×M + system。例如 N=20 文件 × M=500 token → 输入 10k；输出按这次审查的量约 2k。
>   - 成本：输入 `10k/1M×1元=0.01元`，输出 `2k/1M×3元=0.006元` → **≈ 0.016 元／次**；
>   - 这次调用 `cost_micros` 应该落在 `16000` 微元附近——而刚才的 `hi` 只有 12000 微元。**一次真实审查和一次 ping 的差距远没你想的大，差的量级主要来自"多少个 diff、多长的上下文"，而不是单 token 单价。**
> - **期望时钟**：流式生成按约 60 token/s（示意），输出 2k token → 生成 ≈ 33s，这是**没有重试**的乐观时长。若需要默认 2 次重试、每次叠加前一次的整段往返，期望时长 ≈ `33 × 3 ≈ 100s`。
>   - **这一算就暴露了 04 讲的坑**：若网关把超时随便设成 60s，第一次调用就会在满负荷 PR 上超时，然后吃掉重试预算——**你以为在"设超时"，其实在"漏掉成功路径"。**
>
> **工程判据：** 给任何一次"审查级"调用设超时前，先算这条期望时长（输出 token ÷ 生成速度 × (1+重试次数)），再把超时设在它的 1.5～2 倍——**别拿 ping 的毫秒当审查的分**。

## 四、避坑清单

- [ ] **`max_tokens` 一定要设。** 不设等于给模型一张无限额度的卡。
- [ ] **审查 / 抽取类任务 `temperature` 给低**（0.1–0.3）。温度高会让同一个 diff 每次审出不同结果，评测根本没法做。
- [ ] **不要把 API Key 写进代码或配置文件。** 走环境变量，或走网关的 AK。
- [ ] **不要让业务代码直接 `new` 厂商 SDK 的 Client。** 半年后换模型时你会哭——这是本书坚持"网关前置"的根本原因。
- [ ] **注意默认端口是 8090**，别照抄成 8080。
- [ ] **`stream=true` 会返回 501（暂不支持）**，这是**刻意设计**，不是 bug。原因：流式的中断计量口径不同（用户点了停止，这次算多少钱？），网关宁愿明确拒绝，也不给你一个算错的数。（第 04 讲展开）

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
