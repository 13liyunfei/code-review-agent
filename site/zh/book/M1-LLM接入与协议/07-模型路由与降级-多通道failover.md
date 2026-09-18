# 第 07 讲 · 模型路由与降级：多通道 failover

> 🎯 导读问题：**"多模型/多通道怎么选？全都挂了怎么办？"**

<img class="mermaid-svg" src="/zh/book-assets/diag-0018.svg" alt="🎯 导读问题："多模型/多通道怎么选？全都挂了怎么办？"" />


> **图 7-0**　本讲地图：「路由」其实是三件事（档位 / 通道 / 故障转移），因为它们的变化频率差三个数量级；全失败必须抛异常，**半接不接的配置必须启动失败**。

## 一、痛点

你的网关跑了两个月，运行良好。然后成本开始涨。

排查结果让人意外：**大部分请求根本没走公司统一的 Token 工厂**，而是走了直连。

日志里一切正常——`[模型网关] LLM 调用成功`，代码是绿的。问题是这条成功消息**不区分走的是哪条通道**，所以你从来没注意过。

再往下查，找到了根因：工厂的 `priority` 配错了。它被排在了候选列表的**最后一位**。

于是每一次调用都变成了这样：

```
请求 → 直连通道A（成功）→ 返回
```

而工厂通道永远排在最后，**永远不会被选中**——因为直连通道从来不会失败。

**你以为你接好了公司网关，实际上你一次都没用过它。**

而作者以为的"降级设计"，在这里变成了主路径：**兜底通道变成了默认通道**，成本按直连价走，账也没进工厂的报表。

顺藤摸瓜还有第二层问题：

> 有几天 `token-factory.enabled=true` 但环境变量 `TOKEN_FACTORY_KEY` 没配。服务正常启动了，所有调用都在"降级"，日志也正常。**没人发现。**

这一讲处理的就是这两个问题：**请求该走哪一条（顺序即策略）、以及"配置半接不接"为什么必须启动失败。**

## 二、原理

### 2.1 "路由"其实包含三件不同的事

把它们混在一起，是绝大多数路由设计失控的起点：

| 层次 | 回答的问题 | 决策依据 | 变化频率 |
|---|---|---|---|
| **档位路由** | 这个任务要多强的模型 | 任务难度 / 成本预算 | 每天调 |
| **通道路由** | 走哪家厂商 / 哪个网关 | 合规、成本、配额 | 每周调 |
| **故障转移** | 这家挂了换谁 | 实时健康状态 | 每秒变 |

三者的**变化频率差了三个数量级**。所以它们必须能被**分别调整**：

- 档位路由应该是**配置**（改 yml 就生效）；
- 通道路由应该是**候选列表的顺序**（装配时决定）；
- 故障转移必须是**运行时的自动行为**（不需要人干预）。

这一讲主要讲后两个。档位路由在第 32 讲的成本计量里会再遇到一次。

### 2.2 候选列表的顺序，就是通道策略本身

看装配代码里决定顺序的那几行：

```java
// ReviewAgentConfig.java:158-169
List<ModelProvider> providers = new ArrayList<>();
TokenFactoryChatProvider factoryProvider = factoryProvider(factoryProps, factoryClient, usageRecorder);
boolean factoryFirst = factoryProvider != null && factoryProps.isPriority();
if (factoryFirst) {
    providers.add(wrapWithBreakerIfEnabled(gateway, factoryProvider));
}
for (ModelProvider p : direct) {
    providers.add(wrapWithBreakerIfEnabled(gateway, p));
}
if (factoryProvider != null && !factoryFirst) {
    providers.add(wrapWithBreakerIfEnabled(gateway, factoryProvider));
}
```

**这段代码的结构本身就是"顺序即策略"的实现**：同一个 `factoryProvider`，插在 `direct` 前面还是后面，决定了它是主路径还是兜底。

而这个"主/兜"的选择被暴露成了一个配置项（`factoryProps.isPriority()`）。**为什么它必须是配置而不是写死的？**

因为**两种选择在现实中都有正确的场景**：

| 场景 | 正确顺序 | 理由 |
|---|---|---|
| 工厂已经成熟、是公司统一入口 | **工厂优先** | 统一计费、统一配额、统一审计 |
| 工厂是过渡期方案，稳定性不如直连 | **直连优先** | 先保证服务可用，账后面再对 |

**如果写死成"工厂优先"，就等于把公司网关的稳定性绑架到了你的服务上。** 而装配层的注释也说明了这一选择带来的一个额外好处：

```java
// ReviewAgentConfig.java:133-138
* <p><b>公司级 Token 工厂接入（2026-09-03）</b>：当 {@code token-factory.enabled=true}
* 时，候选列表里会多出一个 {@link TokenFactoryChatProvider}（按 {@code priority}
* 决定排第一还是最后），其余直连供应商包一层 {@link UsageReportingProvider}
* 把用量补报回工厂。降级路径因此是<b>天然的</b>——工厂失败就由网关 failover 到直连，
* 不需要另写一套降级逻辑；代价只是「直连期间的用量得事后补报」，而这正是
* {@link UsageReporter} 的职责。
```

**"降级路径是天然的，不需要另写一套降级逻辑"——这是这一讲最值钱的一句话。**

很多项目会为"主通道挂了怎么办"写一套专门的 `if-else` 降级逻辑。而这里的做法是：**把兜底通道当成候选列表里的一项**，于是网关原有的 failover 机制自动覆盖了它。

> **不要去写第二条路径。把一个新选项加进已有的候选列表，让既有的失败机制去处理它。**

还有一个容易配错的细节：

```java
// ReviewAgentConfig.java:154-155
// 工厂供应商自己会计量，只有直连才需要补报
direct.add(new UsageReportingProvider(base, reporter, factoryProps.getAlias(), spec.getModel()));
```

**只有直连的供应商被包了补报装饰器。** 因为工厂通道的调用天然就在工厂的账上——再补报一次就是**重复计费**。

**这就是"顺序"之外的第二个陷阱**：如果无差别地给所有供应商都包上补报，走工厂时用量会被记两遍，账单翻倍。

### 2.3 熔断器是"软跳过"，不进入重试路径

`CircuitBreakerProvider` 的类注释把这一条讲得很清楚：

```java
// CircuitBreakerProvider.java
/**
 * <p><b>三态机</b>：见 {@link CircuitBreakerState}。OPEN 期间所有请求立即抛
 * {@link CircuitOpenException}，由 {@link ModelGateway} 视为「跳过本供应商」，
 * <b>不进入</b>{@link RetryClassifier} 重试路径——熔断本身就说明重试无意义。
 */
```

回到 `ModelGateway` 的循环，能看到这个"不进入重试"是怎么落地的——**`CircuitOpenException` 是单独一个 catch，而且 `break` 立刻跳出内层重试循环**：

```java
// ModelGateway.java:143-147
} catch (CircuitOpenException coe) {
    // 熔断器 OPEN：跳过本候选，移到下一家（不计入 retry）
    log.warn("[模型网关] 供应商[{}] 触发熔断（OPEN），跳过：{}", p.name(), coe.getMessage());
    lastFailure = coe;
    break;
}
```

对比一下紧邻的另一个 catch：

```java
// ModelGateway.java:148-161
} catch (Exception e) {
    lastFailure = e;
    boolean retryable = retryClassifier.isRetryable(e);
    log.warn("[模型网关] 供应商[{}] 调用失败（{}/{}）：{}（retryable={}）",
            p.name(), attemptsOnThisProvider, maxForThis, e.getMessage(), retryable);
    if (!retryable || attemptsOnThisProvider >= maxForThis) {
        // 永久错误 或 已达上限：换下一供应商
        break;
    }
    // 临时错误：本供应商上退避重试
    long sleepMs = backoffPolicy.backoffMs(attemptsOnThisProvider);
    log.info("[模型网关] 临时错误退避 {}ms 后重试（供应商 {}）", sleepMs, p.name());
    backoffPolicy.sleep(sleepMs);
}
```

**两个 catch 的分工是这一讲的结构核心：**

- `CircuitOpenException` → **无条件跳出内层循环**（连 `retryable` 都不问）；
- 其它异常 → 问分类器：可重试就退避重试，不可重试就跳出。

**为什么要分开？** 因为"熔断器 OPEN"这个状态的语义是：**这个供应商在冷却期内已经被判定为不可用，再试一次没有意义。**

如果不分开处理（比如把 `CircuitOpenException` 也交给分类器）——分类器看它是一个 `RuntimeException`，很可能判成"可重试"，于是对一个已熔断的供应商连续退避重试 3 次。**冷却期本来只要 30 秒，你的重试却让它多等了好几秒，还平白多打了 3 次请求。**

### 2.4 路由策略只是"建议"，网关保留二次校验

这是一个很值得学的**职责边界设计**：

```java
// RouteStrategy.java
/**
 * <p><b>协作关系</b>：
 * <ul>
 *   <li>{@link CircuitBreakerProvider#available()} 提供「该供应商是否值得下发」的瞬时判定；</li>
 *   <li>本接口返回的供应商仍会被 {@link ModelGateway} 二次校验
 *       （配额是否耗尽、自身可用），契约是「策略只做『该先试谁』的建议」。</li>
 * </ul>
 */
```

也就是：

- **策略可以随便写**（自己实现一个按成本选、按延迟选、按权重轮询的策略都行）；
- **但策略不能绕过安全约束**——配额和可用性由网关自己再校验一遍。

这在 `ModelGateway` 里对应这段：

```java
// ModelGateway.java:118-122
// 配额二次校验（collectCandidates 已过滤；这里防御 available() 状态变化）
if (quotaExceeded(p.name())) {
    log.debug("[模型网关] 供应商 {} 配额耗尽，跳过", p.name());
    continue;
}
```

**注释里的"防御 available() 状态变化"是关键**：候选列表是循环开始前收集的，而循环过程中供应商的健康状态**可能已经变了**。再校验一次是**同一个决策点上的时间差防御**。

> **"策略可插拔"必须建立在"安全约束不可插拔"之上。** 否则任何第三方策略都能捅穿配额——而配额是防成本失控的最后一道闸。

### 2.5 全失败必须抛异常：三种状态，调用方必须能区分

```java
// ModelUnavailableException.java
/**
 * <p><b>为什么必须抛而不是返回空串</b>：调用方无法区分「模型认为没有可返回的结论」与
 * 「模型根本没被调到」。返回空串会让上层解析出 0 条发现，最终产出一份看起来完全通过的
 * 审查报告——静默失败比显式报错危险得多。抛出该异常后，协调器将该 Agent 标记为降级，
 * 报告中如实标注「本次未产出可信结论」。
 */
```

**这段注释把"空串"这个选项彻底钉死了。** 顺着它推一遍就能看到空串为什么致命——**它把三种语义完全不同的状态压成了同一个值**：

| 状态 | 正确的语义 | 返回空串时的语义 |
|---|---|---|
| 模型正常回复"这段代码没问题" | 审查通过 ✅ | 审查通过 ✅ |
| 所有供应商都挂了 | **审查未完成** ⚠️ | 审查通过 ✅ |
| 模型返回了空内容 | **审查未完成** ⚠️ | 审查通过 ✅ |

**三种状态里，只有第一种是真的通过。而空串把另外两种也伪装成了"通过"。**

这就是第 06 讲那个痛点的**上游版本**：第 06 讲是"解析器把失败伪装成默认值"，这一讲是"网关把失败伪装成空结果"——**同一个病，在链路的两端。**

异常里还带着两个字段，用途很明确：

```java
/** 实际发起过的调用次数（受 available / 配额过滤影响，可能小于供应商总数）。 */
public int attempts() { return attempts; }

/** 配置的供应商总数。 */
public int providerCount() { return providerCount; }
```

**`attempts() < providerCount()` 本身就说明了一件事**：有些供应商连试都没试（不可用 / 配额耗尽 / 被熔断）。这个差值在排障时直接指向"是配置问题还是上游问题"：

- `attempts == 0` → 没有可用供应商（**配置问题**，Key 没配 / 全被熔断）；
- `attempts == providerCount` 且全失败 → 上游集体故障（**外部问题**）；
- `0 < attempts < providerCount` → 部分不可用（**配额或熔断**）。

**把"试了几次"写进异常，等于在错误里带上了诊断路径。**

### 2.6 半接不接的配置，是最难排查的状态

```java
// ReviewAgentConfig.java:191-197
if (!props.usable()) {
    // 开了开关却没配 AK：直接启动失败。半接不接的状态最难排查，
    // 而且会让人误以为「已经走工厂了」——实际上每次都在悄悄降级
    throw new IllegalStateException(
            "token-factory.enabled=true 但未配置 token-factory.access-key："
                    + "请通过环境变量 TOKEN_FACTORY_KEY 注入，或把 token-factory.enabled 设为 false");
}
```

**注释里"半接不接的状态最难排查"这七个字，道出了一整类生产事故的形态。**

分析一下这个状态为什么特别毒：

| 表现 | 人的解读 |
|---|---|
| 服务正常启动 | ✅ 部署成功 |
| 所有请求都成功返回 | ✅ 功能正常 |
| 日志无异常 | ✅ 无故障 |
| **实际一次都没走工厂** | ❌ **没人知道** |

**"看起来全绿"是这个状态的核心特征。** 而它会带来两个后果：

1. **你以为接好了，于是没去查工厂侧的配置**（配额、限流、模型别名）；
2. **直到某天你依赖工厂做审计/计费时，才发现历史数据全是空的**。

**所以正确的做法是启动即失败（fail-fast）**，让这个状态**根本不可能存在**。

同一条原则在 `primaryChatModel` 上又出现了一次：

```java
// ReviewAgentConfig.java:212-227
/**
 * <p><b>无 Key / 无模型即启动失败（fail-fast）</b>——不再提供 NoOp 占位：
 * 结构化审查路径没有「静默跳过」的余地，配置缺失应当立刻暴露而不是产出一份
 * 看起来正常的假报告。
 */
@Bean
public ChatModel primaryChatModel(...) {
    if (!tokenHub.hasKey()) {
        throw new IllegalStateException("未配置 tokenhub.api-key（已移除 Mock 兜底）："
                + "请通过环境变量 TOKENHUB_API_KEY 注入真实 Key，服务拒绝在无模型配置下启动");
    }
    if (tokenHub.getModels().isEmpty()) {
        throw new IllegalStateException("tokenhub.models 为空：请至少声明一个模型（见 application.yml tokenhub.models）");
    }
    ...
}
```

**"不再提供 NoOp 占位"**——这句话说明历史上曾经有一个"占位模型"。而占位模型的后果写在同一个注释里：**产出看起来正常的假报告**。

> **一个 fail-fast 的启动检查，价值等于避免一整类"运行三个月后才被发现"的故障。** 这是极少数"投入几分钟、回报以月计"的工程投入。

## 三、代码

### 3.1 `ModelGateway.chat()`：外层 failover，内层退避重试

把两个循环放在一起看一遍，这是模块一里最完整的一段控制流：

```java
// ModelGateway.java:111-163（结构摘要）
int strategyCursor = 0;
while (strategyCursor < candidates.size()) {              // ← 外层：failover
    ModelProvider p = routeStrategy.next(rotate(candidates, strategyCursor));
    strategyCursor++;
    if (p == null) continue;
    if (quotaExceeded(p.name())) continue;                 // ← 二次校验

    int attemptsOnThisProvider = 0;
    int maxForThis = retryEnabled ? retryMaxAttempts : 1;
    while (attemptsOnThisProvider < maxForThis) {          // ← 内层：退避重试
        attemptsOnThisProvider++;
        totalAttempts++;
        try {
            String result = p.chat(prompt);
            incQuota(p.name());
            return result;
        } catch (CircuitOpenException coe) {
            break;                                          // ← 熔断：直接换下一家
        } catch (Exception e) {
            if (!retryable || attemptsOnThisProvider >= maxForThis) break;
            backoffPolicy.sleep(sleepMs);                   // ← 临时错误：本家重试
        }
    }
}
```

**外层 + 内层的嵌套顺序，和第 04 讲 `RoutingEngine` 的结论完全一致**：

> **同一家内层重试，全部失败后外层切换。**

两个独立实现（一个在网关内核、一个在应用侧）得出同一个结论——**因为这是唯一正确的顺序**。

还有一个精妙的辅助方法：

```java
// ModelGateway.java:194-203
/** 从 cursor 起旋转移位（让路由策略从 cursor 开始选择）。 */
private static List<ModelProvider> rotate(List<ModelProvider> in, int cursor) {
    if (cursor <= 0 || cursor >= in.size()) {
        return in;
    }
    List<ModelProvider> out = new ArrayList<>(in.size());
    out.addAll(in.subList(cursor, in.size()));
    out.addAll(in.subList(0, cursor));
    return out;
}
```

**为什么需要 `rotate`？** 因为 `routeStrategy.next()` 的契约是"从候选列表里选第一个可用的"。如果每次都传同一个列表，那就**永远选第一个**——外层 `while` 会陷入"每次都在试同一个供应商"的死循环（准确说是空转 N 次，每次都被同一个失败的供应商挡住）。

`rotate` 用**旋转**的方式把"已经试过的"挪到后面，让策略自然地推进到下一个。**这是一个"用数据结构适配接口契约"的典型手法**——不动策略，动输入。

### 3.2 装配处的日志：把"当前生效的通道策略"打出来

```java
// ReviewAgentConfig.java:178-180
log.info("已装配 LangChain4j 统一模型网关（{} + 熔断 + 退避重试）：{}",
        factoryProvider == null ? "TokenHub 多模型直连" : "Token 工厂优先 + TokenHub 直连兜底",
        gatewayBean.describe());
```

而 `describe()` 也不只是名字：

```java
// ModelGateway.java:208-214
public String describe() {
    StringBuilder sb = new StringBuilder("ModelGateway[");
    for (ModelProvider p : providers) {
        sb.append(p.name()).append(p.available() ? "(on)" : "(off)").append(",");
    }
    return sb.append("]").toString();
}
```

**启动日志里会明确写出"是工厂优先还是直连兜底"，以及每个供应商当前是 `on` 还是 `off`。**

这一条直接对应本章开头的痛点：**如果当初这条日志存在，那个"priority 配错"的问题在第一次部署时就会被看见。**

> **把"当前生效的策略"打进入口日志**，是成本最低的一个防错手段。它不解决 bug，但它让"策略配错却无人察觉"变得不可能。

### 3.3 健康快照：把降级状态暴露成一个端点

```java
// ModelGateway.java:232-248
/** 全链路快照：SLA 端点暴露。 */
public LlmGatewaySnapshot snapshot() {
    List<CircuitBreakerProvider.CircuitSnapshot> providerSnaps = new ArrayList<>();
    for (ModelProvider p : providers) {
        if (p instanceof CircuitBreakerProvider cb) {
            providerSnaps.add(cb.snapshot());
        } else {
            // 未包熔断器：构造一个始终 CLOSED 的快照（便于上层无需特判）
            providerSnaps.add(new CircuitBreakerProvider.CircuitSnapshot(
                    p.name(), CircuitBreakerState.CLOSED, 0, 0, null, 0, 0, 0));
        }
    }
    List<TokenUsageRecorder.ProviderAggregate> agg = usageRecorder == null
            ? List.of() : usageRecorder.aggregatesSnapshot();
    return new LlmGatewaySnapshot(totalFailures.get(), providerSnaps, agg);
}
```

**注意 `else` 分支**——没包熔断器的供应商也要产出一个快照，而不是返回 `null`：

> 未包熔断器：构造一个始终 CLOSED 的快照（**便于上层无需特判**）

**这是一个很小的设计动作，但它决定了消费方的代码质量。** 如果这里返回 `null`，那么每个读快照的地方都要写 `if (snap != null)`——于是"输出侧省一行，消费侧多十行"。

**数据结构的完整性和一致性，比"少造一个对象"重要得多。**

### 3.4 熔断器装饰后的线程安全说明

```java
// CircuitBreakerProvider.java
/**
 * <p><b>线程安全</b>：状态转换走 {@code synchronized}；HALF_OPEN 试验并发限制走
 * {@link AtomicInteger}。{@link #available()} 不加锁，仅读 volatile 字段快照，
 * 接受轻微不一致（路由层是「软跳过」，不会因此打挂供应商）。
 */
```

**"接受轻微不一致"这一段是成熟的工程判断，而不是妥协。**

因为 `available()` 的消费者是**路由层**，而路由层本来就是"软跳过"——它只是决定"先试谁"，试失败了还有下一家兜着。**在这里加锁的收益是零，成本是每次路由都要抢锁。**

> 判断一个地方该不该加锁，标准不是"有没有数据竞争"，而是"**如果不一致，后果有多严重**"。后果是"少跳过一家供应商"→ 不加锁。

## 四、避坑清单

- [ ] **把档位路由 / 通道路由 / 故障转移分开**。三者变化频率差三个数量级，混在一起必然失控。
- [ ] **候选列表的顺序就是通道策略**，必须可配置。写死顺序 = 把某个通道的稳定性绑架到你的服务上。
- [ ] **兜底通道要作为候选列表里的一项**，而不是另写一套降级逻辑。让既有 failover 机制自动覆盖它。
- [ ] **只给需要补报的通道加补报装饰器。** 无差别地包所有供应商 = 用量被记两遍、账单翻倍。
- [ ] **熔断 OPEN 必须跳过重试路径**，单独一个 catch 直接换下一家。
- [ ] **供应商未包熔断器时，也要产出完整快照**，不要让消费方到处判空。
- [ ] **路由策略只是建议，配额与可用性必须由网关二次校验。** 安全约束不可插拔。
- [ ] **候选列表是循环开始时收集的，循环中状态会变**，所以决策点上要再校验一次。
- [ ] **全失败必须抛异常，绝不返回空串。** 空串会把"未完成审查"伪装成"审查通过"。
- [ ] **异常里要带 `attempts` 和 `providerCount`**——它们的差值直接指出是配置问题还是上游问题。
- [ ] **配置半接不接（开关开着但参数没配）必须启动失败。** 这个状态"看起来全绿"，是最难排查的一类故障。
- [ ] **移除 NoOp / Mock 占位。** 占位模型会产出"看起来正常的假报告"。
- [ ] **启动日志要打出"当前生效的通道策略"和每个供应商的 on/off。** 一行日志，消灭一整类"配错却无人察觉"。

## 五、动手任务

> **任务一：复现"兜底通道变成主通道"这个配错事故。**
>
> **仓库位置**：`code-review-agent`
>
> **操作**：
> 1. 读 `ReviewAgentConfig.llmClient(...)`（第 140–182 行），找到 `factoryFirst` 那一行。
> 2. 把 `factoryProps.isPriority()` 反过来（相当于把它配到列表最后）。
> 3. 用假的 `TokenFactoryClientHolder` 起一次上下文，读启动日志里 `ModelGateway[...]` 那一行。
>
> **预期结果**：
> - 日志里的 `describe()` 会打印**实际顺序**，你能直接看出工厂被排在最后。
> - **此时若第一项直连供应商始终成功，工厂永远不会被选中**——在 `TokenFactoryChatProvider.chat(...)` 里加一行日志/计数器验证：**它是 0 次**。
>
> **思考题**：为什么这个 bug **不会被任何测试发现**？因为"功能正常"——所有请求都成功返回了。**唯一能发现它的方式是看"哪个通道被用了"，而不是"请求成不成功"。** 这就是为什么 3.2 节那条启动日志必须存在。
>
> **任务二：验证"熔断 OPEN 不进入重试"。**
> 1. 构造一个**永远抛异常**的假供应商，包上 `CircuitBreakerProvider`（阈值 2）。
> 2. 把它放在候选列表第一位，第二位放一个正常供应商。
> 3. 连续调 `ModelGateway.chat()` 5 次，打印每次走的供应商名称。
>
> **预期结果**：
> - 前 2 次：第一位尝试 2 次（阈值）→ 熔断 → 切到第二位成功。
> - 第 3 次起：**第一位被直接跳过**（`CircuitOpenException`），日志里出现 `触发熔断（OPEN），跳过`——**且没有退避 sleep**。
> - **关键对照**：把 `CircuitOpenException` 的 catch 删掉（让它落到 `catch (Exception e)`），观察退避 sleep 的次数变化。**你会看到它对一个已熔断的供应商白白重试。**
>
> **任务三：验证 fail-fast。**
> 把 `token-factory.enabled` 设为 `true`，但**不设** `TOKEN_FACTORY_KEY` 环境变量，启动服务。
>
> **预期结果**：**启动失败**，异常消息就是 `token-factory.enabled=true 但未配置 token-factory.access-key：...`。
> **对比**：把 `factoryProvider(...)` 里那个 `throw` 改成 `return null`，服务会**正常启动**，所有请求走直连，日志全绿——**这就是"半接不接"，也是这一讲最希望你亲手见一次的状态。**

---

## 本讲小结

1. **"路由"是三件事**：档位（能力）、通道（厂商）、故障转移（健康）。分开设计，因为变化频率差三个数量级。
2. **候选列表的顺序就是通道策略，必须可配置。** 而"主/兜"的两种排法在现实中都有正确场景。
3. **降级路径应该是"天然的"**——把兜底通道放进候选列表，别另写一套逻辑。同时注意**只给需要补报的通道加装饰器**，否则账单翻倍。
4. **熔断 OPEN 不进入重试路径**，单独一个 catch 直接换下一家。
5. **路由策略只是建议，配额与可用性由网关二次校验**——策略可插拔，安全约束不可插拔。
6. **全失败必须抛异常，绝不返回空串。** 空串把"未完成"伪装成"通过"。异常里带上 `attempts / providerCount`，让错误自带诊断路径。
7. **半接不接的配置必须启动失败。** 它"看起来全绿"，是最难排查的一类故障；一行 fail-fast 检查，回报以月计。

模块一到此结束。你已经能把一个 LLM 调对、调稳、调得可观测。

**从第 08 讲开始，我们把视角从"调模型"切到"造 Agent"**——先看一个 Agent 的最小骨架到底是什么形状，再往上叠工具、记忆、提示词、上下文与可靠性。
