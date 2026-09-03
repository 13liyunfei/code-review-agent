# code-review-agent 接入公司级 Token 工厂

> 对应开源仓库：[token-factory](https://github.com/13liyunfei/token-factory)（公司级 LLM Token 网关，三模块：core / client / server）
>
> 本文说明「本系统（代码审查产品）如何接入一个公司级通用服务」，以及这套接入方式为什么是通用的。

## 1. 为什么要接

`code-review-agent` 原本自己管 LLM 密钥：配置里放 `tokenhub.api-key`，代码直连厂商。这在单系统阶段没问题，一旦公司里有多个系统都要调模型，就会暴露四个结构性问题：

| 问题 | 直连模式的代价 | 接入工厂后 |
| --- | --- | --- |
| 密钥分散 | 每个系统都存一份厂商 Key，轮换要改 N 处，泄露范围不可控 | 厂商 Key 只在工厂侧，业务方只持有工厂签发的 AK |
| 计量分散 | 各系统各算各的账，公司层面拿不到「谁花了多少钱」 | 统一计价 + 统一落库，按租户 / 应用 / 供应商拆分 |
| 额度失控 | 出事之前没人知道某个系统的调用量已经失控 | 工厂侧下发日 / 月额度，超限由工厂拦或告警 |
| 韧性重复建设 | 每个系统都要自己写一遍熔断 / 重试 / failover | 工厂统一提供，业务方只保留轻量降级 |

**代价**：多一跳网络，以及工厂挂了要有退路。下面第 3 节专门处理这个。

## 2. 接入后的调用拓扑

```
                 ┌──────────────────────────────────────┐
  SecurityAgent ─┤                                      │
  LogicAgent   ─┤        ModelGateway（本系统）          │
  PerfAgent    ─┤   路由 / 配额 / 熔断 / 退避重试         │
  ArchAgent    ─┤                                      │
  StyleAgent   ─┤                                      │
                 └───────────────┬──────────────────────┘
                                 │
             ┌───────────────────┴───────────────────┐
             │ 候选供应商（按 priority 决定顺序）        │
             └───────┬───────────────────────┬───────┘
                     │                       │
        ┌────────────▼──────────┐   ┌────────▼─────────────────────┐
        │ TokenFactoryChat-     │   │ UsageReportingProvider(       │
        │ Provider              │   │   LangChain4jChatProvider)    │
        │ → 工厂 /v1/chat/      │   │ → 直连 tokenhub 上游           │
        │   completions         │   │ → 事后补报用量到工厂            │
        └────────────┬──────────┘   └────────┬─────────────────────┘
                     │                       │
              ┌──────▼───────┐        ┌──────▼───────┐
              │ token-factory│        │ 真实厂商 API  │
              │  :8090       │        │ (TokenHub)   │
              └──────────────┘        └──────────────┘
```

关键点：**工厂供应商与直连供应商在同一个候选列表里**。这是刻意的——降级不需要另写一套逻辑，直接复用 `ModelGateway` 已有的 failover。

## 3. 降级与补报：工厂挂了怎么办

三句话概括设计：

1. **工厂失败就抛异常，不吞。** 工厂是主路径，不是兜底。它在 `ModelGateway` 里就是一个普通供应商，失败后由网关切到下一个。
2. **切到直连后花的钱，事后补报回工厂。** 否则「用量下降」只是统计缺口，不是真的省了钱。
3. **补报失败绝不影响业务。** 工厂已经不可用了，再让补报把审查链路打断，就是把「一个服务挂了」放大成「两个服务都挂了」。

对应实现：

| 类 | 职责 |
| --- | --- |
| `core/tokenfactory/TokenFactoryChatProvider` | 走工厂的供应商；读取响应里的真实用量与实际命中的厂商 |
| `core/tokenfactory/UsageReportingProvider` | 包在直连供应商外面，调用成功后补报用量 |
| `core/tokenfactory/TokenFactoryUsageReporter` | 补报出口；异常一律降级成日志，连续失败后日志从 WARN 降到 DEBUG |
| `core/tokenfactory/UsageReporter` | 接口 + `NO_OP`；工厂未启用时装配层拿到的就是空实现，调用方无需判空 |
| `core/tokenfactory/TokenFactoryClientHolder` | 客户端惰性持有者；工厂关闭时不建连接池 |

### 关于「估算值」

上游没返回 `usage` 时（部分模型/网关不返回），按字符数粗估并打上 `estimated=true`：

- 宁可给一个带标记的估算值，也不要账上记 0 —— 0 会被当成「没花钱」；
- 带标记的数据在对账时能被识别出来单独处理。

## 4. 配置

`application.yml`（全部走环境变量，默认关闭）：

```yaml
token-factory:
  enabled: ${TOKEN_FACTORY_ENABLED:false}
  base-url: ${TOKEN_FACTORY_BASE_URL:http://localhost:8090}
  access-key: ${TOKEN_FACTORY_KEY:}
  app-id: code-review-agent
  alias: ${TOKEN_FACTORY_ALIAS:default}
  priority: ${TOKEN_FACTORY_PRIORITY:true}       # true=工厂优先
  report-direct-usage: ${TOKEN_FACTORY_REPORT_USAGE:true}
  timeout-seconds: 120
  connect-timeout-ms: 3000
```

两条硬规则：

- **默认关闭**：不配置即完全不生效，行为与接入前一致。半接不接的状态最难排查。
- **启用但没配 AK → 启动失败**。宁可起不来，也不要「以为走工厂其实一直在悄悄降级」。

## 5. 链路追踪

补报与工厂调用都带 `X-TF-Trace-Id`，取值来自本系统的 `TraceContext`：

```
审查任务 traceId ──► 工厂用量明细 ──► /v1/usage/trace/{traceId}
```

所以在工厂侧能直接用审查报告的 traceId 反查出这一趟审查花了多少 token、打到哪家厂商。

## 6. 为什么这套接入是「通用」的

接入层不依赖任何代码审查的业务概念：

- `UsageAwareModelProvider` 只描述「能返回 token 用量的模型供应商」；
- `UsageReporter` 只描述「把用量报出去」；
- 装配层（ `ReviewAgentConfig`）负责决定「谁排在前面、谁需要补报」。

任何 JVM 系统只要有自己的「模型供应商」抽象，套上这三个接口就能接入工厂，不需要复制本系统的任何业务代码。SDK（`token-factory-client`）本身零 Spring 依赖，Quarkus / 裸 main 都能用。

## 7. 验证

```bash
# 本系统集成测试（JDK HttpServer 打桩，不走真实网络）
mvn -o test -Dtest=TokenFactoryIntegrationTest

# 全量回归
mvn -o test
```

`TokenFactoryIntegrationTest` 覆盖：用量读取与实际厂商归属、空内容当失败、真实用量补报、无 usage 时的估算标记、失败补报且原异常继续抛出、补报失败不打断主流程、traceId 同源。

## 8. 本地联调

```bash
# 1) 起工厂（需要 PostgreSQL；或改 spring.datasource.* 指向测试库）
cd ../token-factory
mvn -o -pl token-factory-server spring-boot:run

# 2) 建租户与 AK（管理面）
curl -X POST localhost:8090/api/admin/tenants \
  -H "X-Admin-Key: $TF_ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"code":"codereview","name":"代码审查"}'
# 返回里带一次性明文 AK，后续只存 hash

# 3) 本系统启用工厂
TOKEN_FACTORY_ENABLED=true TOKEN_FACTORY_KEY=tf_xxx \
  ./mvnw -o spring-boot:run
```

启动时日志会打印 `已接入公司级 Token 工厂：baseUrl=... appId=... alias=...`；
随后 `已装配 LangChain4j 统一模型网关（Token 工厂优先 + TokenHub 直连兜底）：ModelGateway[token-factory:default(on),hunyuan(on),...]`。
