# 第 36 讲 · LLM 网关设计：三模块分层与"能被人引进去"的边界

> 🎯 导读问题：**"你怎么设计一个给全公司用的 LLM 网关？"** ——90% 的人会答"加鉴权、加限流、加缓存"，但真正决定这个网关能不能落地的，是**依赖方向**。

> **视角切换声明**：模块一里我们是**调用方**——怎么把一个 LLM 调对、调稳。从这一讲开始我们是**平台方**——怎么把它做成给整个团队用的基础设施。同一套 `token-factory`，两个视角。第 02–08 讲里你看到的 `TokenFactoryClient` 是它的**外表**；这一讲拆它的**骨架**。

---

<img class="mermaid-svg" src="/zh/book-assets/diag-0160.svg" alt="图 36-0　本讲地图：分层按依赖方向切，判据是没有框架能否跑。core 只依赖 slf4j-api，client 零 Spring，Spring 只在 server 外层，装配点唯一。" />

> **图 36-0**　本讲地图：分层按依赖方向切，判据是没有框架能否跑。core 只依赖 slf4j-api，client 零 Spring，Spring 只在 server 外层，装配点唯一。

## 一、痛点

第一版网关我是这么做的：一个 Spring Boot 项目，里面塞了 `ChatController`、`ModelRouter`、`CircuitBreaker`、`PricingEngine`、`RetryPolicy`……**全在一个模块里。**

上线一周，两个业务方来找我。

第一个是做 Quarkus 的团队，说："你这个 SDK 我们引不动，它拉了一个 Spring 的依赖树进来。"

第二个是算法团队，说："我们只想在自己进程里包一层熔断，不想为了这个起一个服务、连一个库。"

我当时的第一反应是**给他们写文档**——教他们怎么排除依赖、怎么配置。写了三页，两边都没跑通。

**问题不在文档，在模块边界。** 我的"网关"其实是**三件东西被一个 jar 焊死了**：

| 它其实是 | 但被焊成了 |
|---|---|
| 一段纯算法（熔断、退避、路由、计价） | 一个 Spring Boot 应用的一堆 `@Service` |
| 一个 SDK（给业务方把请求发出去） | 被 Spring 依赖污染的 jar |
| 一个独立服务（自己带 Web、DB、管理面） | ——（三者混在一起） |

第三个业务方一句话点破：

> "我不需要你的服务，我需要你的**熔断算法**。"

**网关的第一性问题是"别人怎么把它引进去"，不是"它有哪些功能"。** 功能决定它好不好用，依赖方向决定**它有没有机会被人用**。

## 二、原理

### 2.1 分层不是按"功能"分，是按"依赖方向"分

把一个系统切成"鉴权层 / 业务层 / 数据层"，是按**功能**分——分完之后每一层都依赖 Spring，谁也用不上谁。

正确的切法只有一个判据：**这个模块能不能在没有框架的情况下跑起来？**

```
token-factory-core      ← 零框架（只依赖 slf4j-api）
   ↑ 被依赖
token-factory-client    ← 零 Spring（Jackson + slf4j）
   ↑ 被依赖
token-factory-server    ← Spring Boot 壳（web + jdbc + flyway + actuator + PG）
```

依赖方向是**单向向上**：`core` 不知道 `client` 存在，`client` 不知道 `server` 存在。**内核不认识 HTTP，更不认识 Spring。**

这带来三件具体的事：

1. **内核可单独嵌入**：算法团队想要熔断，引 `core` 就行——`new CircuitBreaker("deepseek")`，不需要 Spring 上下文。
2. **SDK 不引起依赖冲突**：Quarkus / 裸 `main()` / 老项目都能引 `client`。你不会因为引了一个 LLM SDK 而被迫升级整个 Spring 版本。
3. **内核可以用纯 JUnit 测**：不需要 `@SpringBootTest`、不需要起容器。这就是为什么 `token-factory-core` 有 42 个测试却跑得飞快。

### 2.2 依赖对比：用 `pom.xml` 说话

**`token-factory-core/pom.xml`**：

```xml
<dependencies>
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>${slf4j.version}</version>
    </dependency>
</dependencies>
```

**一个依赖，`slf4j-api`。** 就是它全部。

注意这不是"很少"，是**刻意的极限**：连 `jackson` 都没进来（内核不解析 JSON，序列化是适配层的事）。

**`token-factory-client/pom.xml`**：

```xml
<dependencies>
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>${jackson.version}</version>
    </dependency>
    <!-- 时间类型的序列化模块：Jackson 默认不认识 java.time.Instant。
         仍然不是「框架依赖」——它只是 Jackson 自己的官方配套模块，且随 SDK 传递下去。 -->
    <dependency>
        <groupId>com.fasterxml.jackson.datatype</groupId>
        <artifactId>jackson-datatype-jsr310</artifactId>
        <version>${jackson.version}</version>
    </dependency>
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>${slf4j.version}</version>
    </dependency>
</dependencies>
```

**三个依赖，零 Spring。** HTTP 客户端用的是 **JDK 自带的 `java.net.http.HttpClient`**——不引 OkHttp、不引 Apache HttpClient、不引 WebClient。

那段注释值得单独读一遍：`jackson-datatype-jsr310` 的引入**被写清了理由**——"Jackson 默认不认识 `java.time.Instant`"。这是真实的踩坑记录：不加这个模块，`Instant` 字段序列化会直接抛 `InvalidDefinitionException`（就像本书第 04 讲里 `orTimeout`、第 19 讲里裸 `ObjectMapper` 那类问题一样，是"跑起来才发现"的坑）。**加依赖不丢人，不写清为什么加才丢人**——三个月后别人（包括你自己）会问"这个包是干嘛的，能删吗"。

**`token-factory-server/pom.xml`**（Spring 只在最外层）：

```xml
<dependencies>
    <dependency>
        <groupId>io.github.13liyunfei</groupId>
        <artifactId>token-factory-core</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-core</artifactId>
    </dependency>
    ...
```

**注意这里没有 `spring-boot-starter-validation`、没有 `spring-boot-starter-security`、没有 Redis。** 每一格都是"这个壳真正需要的东西"：

- `web` → 提供 HTTP 端点
- `jdbc` → 提供 `JdbcTemplate`
- `actuator` → 提供健康端点
- `flyway` → 提供迁移
- `postgresql` → 驱动

**没有 `security`** —— 鉴权是自己用 `HandlerInterceptor` 写的（第 37 讲详述）。这不是"造轮子癖"，而是：一旦引入 Spring Security，整个应用的过滤器链、`SecurityContext`、默认登录页、CSRF 策略都会跟着进来，而这些**都不是一个网关内核该拥有的东西**。网关的鉴权语义只有两句话："认 AK" 和 "认 Admin-Key"。

### 2.3 `core` 不认识 Spring，那谁来装配？

这是分层最容易被忽略的一环：**内核是纯 Java，那它怎么变成 Spring Bean？**

答案是一个**唯一的装配点**——`GatewayConfig`。它的类注释就是答案：

<img class="mermaid-svg" src="/zh/book-assets/diag-0161.svg" alt="答案是一个唯一的装配点——`GatewayConfig`。它的类注释就是答案：" />

> **图 36-1**　GatewayConfig 是唯一的 Spring 装配点：`backoffPolicy`、`breakerRegistry`、`routingEngine` 三个 `@Bean` 集中了全部 `new`，内核是纯 POJO、由 Spring 主动来认识它。

**规律很清晰：所有 `new` 都发生在这一个文件里。** `core` 里的类全部是 `final class` + 构造器注入（第 40 讲会看到 `RoutingEngine` 的 7 个构造参数），它们**不知道 Spring 的存在**，是 Spring 主动来"认识"它们。

两个细节值得注意：

**① 路由表走 `Function` 而不是快照。** `routesByAlias` 是一个 `Function<String, List<RouteTarget>>`，每次调用**实时查库**：

```java
Function<String, List<RouteTarget>> routesByAlias = alias ->
        routes.findEnabledByAlias(alias).stream()
                .map(r -> new RouteTarget(r.alias(), r.providerCode(), r.upstreamModel(),
                        r.priority(), r.weight(), r.enabled()))
                .toList();
```

类注释解释了取舍：

> 路由表走的是「每次请求实时查库」而不是启动时加载：管理端改完路由立刻生效，运维不用重启网关。代价是每次调用多一次带索引的小查询——相比「改个路由要重启全公司 AI 入口」，这点开销划算得多。

**"实时查库"不是性能疏忽，是刻意的可用性取舍。** 一个网关最怕的不是多点一次数据库，是"改一行配置要走一次发布流程"。

**② 时间和 sleep 都是注入的。**

```java
millis -> {
    if (millis > 0) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
},
System::currentTimeMillis
```

`RoutingEngine` 的构造器收 `Sleeper`（函数式接口）和 `LongSupplier nowMillis`。**后果是测试里可以注入假时钟和空 sleep**：验证"退避 500ms → 1000ms → 2000ms"不需要真的等 3.5 秒，一次断言就够。

这条经验可以推广成一句：**任何"等待"与"取当前时间"，都应该在边界处被抽象成可注入的依赖。**（这个 Sleeper 的注入在本项目里还留了一个坑，见第 40 讲。）

### 2.4 装配点唯一，但**路径挂载**必须显式

`WebConfig` 只有 30 行，但它藏着一个重要的默认值决策：

```java
@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(accessKeyInterceptor)
            .addPathPatterns("/v1/**");
    registry.addInterceptor(adminKeyInterceptor)
            .addPathPatterns("/api/admin/**");
}
```

类注释：

> 这里**不用** `addPathPatterns("/**")` 再在拦截器里 if-else 判断——显式列出各自负责的路径，新加端点时「忘了挂鉴权」会立刻暴露（默认不拦截 = 裸奔）。

**"默认不拦截" = 裸奔。** 这句话值一整节安全课。（下一讲会看到：这个项目自己也在别处违反了这条原则——“默认放行"的另一半故事。）

### 2.5 身份缺失时返回 500 而不是 401

`ChatController` 里有一段值得反复读的代码：

```java
private static CallPrincipal principal(HttpServletRequest request) {
    Object attr = request.getAttribute(CallPrincipal.ATTRIBUTE);
    if (!(attr instanceof CallPrincipal principal)) {
        // 走到这里说明拦截器没生效（路径没挂上 / 配置漏了），属于部署错误而非鉴权失败
        log.error("请求未携带调用方身份，检查 AccessKeyInterceptor 是否已注册到该路径：{}",
                request.getRequestURI());
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "调用方身份缺失，网关配置错误");
    }
    return principal;
}
```

直觉上这里该返回 **401**——"你没带身份"。但它返回 **500**。

为什么？**因为"没带身份"和"身份没被解析"是两件完全不同的事**：

| 现象 | 含义 | 谁的问题 | 该触发什么告警 |
|---|---|---|---|
| 401 | 调用方没带 / 带错 AK | 业务方 | 可以忽略（正常拒绝） |
| 500 | 拦截器没挂上，请求裸奔进了业务逻辑 | **平台方** | **立刻拉响**（安全事件） |

如果这里返回 401，运维看到的一个 500 会变成一个 401，而**401 在网关里是日常噪音**——"有人 AK 配错了"每天都有几万条。真相会被埋掉。

**状态码不是给调用方看的，是给告警系统看的。** 凡是"本该被拦住却进来了"的路径，都必须用一个**不会被淹没**的状态码。

### 2.6 数量级直觉：一个"零框架内核"到底值多少钱

分层不是洁癖，它能落到数字上。把本章两个看似"很小"的取舍算给你看，事实是它们加起来决定了网关的日常处境。

**① 实时查库路由，每次真正贵多少？**

```text
场景：路由表每次请求实时查一次（放弃启动时快照）。
- 路由查询：走索引等值查询，本机 P99 ≈ 0.3 ms
- 前提 QPS = 200：路由查询负载 ≈ 200 次/s，远小于 PG 单表小查询轻松支撑的 ~5 000/s
- 占比：0.3 ms 摊在一次 2 000 ms 的 LLM 调用里 = 0.3/2000 = 0.015%

对照：改成"每次路由变更重启"。
- 一次重启约 25 s 冷启动 + 连接池预热，且整条链路上秒级不可用
- 即使一年只改 4 次路由，那也是 4 次"全公司 AI 入口抖动"
```

> **结论**：你为省下 0.015% 的耗时而放弃毫秒级热生效，等于用"全公司 AI 入口每季度抖一次"交换"0.015% 的延迟"。**这个交换的直觉是：凡被"反正每个请求都要用一次"且代价是亚毫秒的,都不值得用"重启/发布流程"去换。**

**② 一个多余的框架依赖，静态摊到每次调用上是多少？**

```text
场景：core 从 1 个依赖（slf4j-api）膨胀到 5 个，其中一个是 5 MB 的 Spring 相关 jar。
- 5 MB 的 jar 只是"引用链变长"，真正的代价在 classpath 扫描与启动：
  反射扫描 + Bean 定义解析，启动时间从 3 s → 8 s（多 5 s，摊到每次启动）。
- 但漏掉的 jar 会让业务方"引不进来"——那不是慢 5 s，是彻底不能用。
```

**判据：依赖的代价不是"体积"，是"会不会挡住别人引用"。** 一个纯算法的内核里多一个 Spring 依赖，业务方引你的 SDK 就要连带升级整个 Spring 版本——**这就是"功能决定好不好用、依赖方向决定有没有机会被人用"的量化形态。**

**三张决策表（本讲分层结论的浓缩）：**

| 维度 | 依赖方向分层 | 功能分层（controller/service/repo） | 微服务化 |
|---|---|---|---|
| 切割判据 | 无框架能否跑 | 职责归属 | 独立部署/边界团队 |
| 代价 | 装配点必须收敛到一个类，new 全部集中 | 每层都依赖框架，谁也引不动 | 网络往返、一致性、运维成本 |
| 适用场景 | 要被外部嵌入/复用的算法、SDK | 普通单体业务 | 需独立伸缩或团队边界的部分 |
| 代表 | `token-factory-core` | 一个普通 Boot 单片 | 网关按服务拆分 |

| 取舍 | 决策 | 代价 |
|---|---|---|
| 路由表实时查库 | 改路由不重启 | 每个请求多 0.015% 耗时 |
| 内核零框架 | 能被任何人嵌入 | 装配点被迫集中到一个类 |
| Sleep/clock 可注入 | 测试可用假时钟毫秒断言 | 构造参数多两个 |

> **工程判据：判断一个内部组件该不该独立成"零框架内核"，只问一句话——"我想复用的是这段算法，还是这个服务？"** 如果别人只想要算法，那么凡是让这段算法依赖框架、依赖 Spring 上下文的东西，都不该待在这个内核里。

### 2.7 网关的两种形态：进程内 SDK 与独立服务

"网关"是一个被两个产品共用的名字。本讲把 `token-factory` 按依赖方向切成三层之后，一件此前没被点破的事就浮了出来：**同一个 `core`，能被两种外壳各装一次**——装进业务方的进程里，或者装进自己的进程里、让别人调过来。

<img class="mermaid-svg" src="/zh/book-assets/diag-0162.svg" alt="&quot;网关&quot;是一个被两个产品共用的名字。本讲把 `token-factory` 按依赖方向切成三层之后，一件此前没被点破的事就浮了出来：同一个 `core`，能被两种外壳各装一次——装进业务方的进程里，或者装进自己的进程里、让别人调过来。" />

> **图 36-2**　同一个内核的两种外壳：进程内 SDK 与独立服务。**这张图要给出的新结论是：两种形态的功能清单几乎重合，差别全在"状态归谁"。** 形态一与形态二都能熔断、退避、路由、计价——它们跑的是同一份 `core`；真正分岔的只有三样东西：**熔断器的计数、配额的计数、一次调用的可见性**。

**功能相同、状态不同，这两句话合起来说明它是一个产品定位问题，而不是一个技术选型问题。** 形态一卖的是"这段算法"，形态二卖的是"这套管控"；判据只有一句话——**你要复用的是算法，还是管控？** 这句话回头解释了本讲开头那个细节：`core` 连 `jackson` 都没有（§2.1），因为**序列化与 Web 都是形态二才需要的东西**，把它们放进内核等于把内核强行绑到形态二上。

本项目是形态二的形态，而形态一那一半的代价被类注释写得非常直白：

```text
     * 熔断状态是内存态，重启即丢失——所以这里同时给出「供应商配置状态」
```

`AdminHealthController.java:23-28`

**"重启即丢失"这四个字就是形态一的分界线。** 一个进程内的熔断器，它的失败率分母是"这个进程见过的请求"；进程重启一次，分母归零，之前攒下的失败证据一起消失。在形态一里这是**正常**的（业务方自己的进程，自己承担）；把它照搬到形态二就是事故：一个坏供应商要被摘掉，得等**每一个实例**各自攒够失败次数。

三个基座放在一起看，这条分界线就更清楚：

| 维度 | A：`code-review-agent`（单体应用） | B：千万 DAU 的多租户 SaaS 平台 | 由此分叉的设计决策 |
|---|---|---|---|
| 熔断状态归谁 | 每个应用实例一份内存态（`AdminHealthController.java:23-28` 明写"重启即丢失"） | 必须被所有调用方共享 | A 引 `core` 即可（形态一）；B 必须起 `server`（形态二） |
| 配额是什么 | 没有租户概念，资源就是自己的线程池 | 要跨实例、跨租户聚合的额度 | A 的"限流"是保护自己，B 的"限流"是分配资源 |
| 一次调用谁看得见 | 只有应用自己 | 平台方必须看得见每一次调用 | 形态一只需要"库"，形态二才有"网关"这个角色 |
| 升级粒度 | 跟应用一起发版 | 能独立于业务应用发版 | 依赖方向单向 ⇒ 才可能"只升级网关" |

> **推演**：熔断状态按实例分片时，一个坏供应商要被**全部实例**摘掉，需要累积的失败次数 = `实例数 × 阈值 = 10 × 5 = 50` 次（假设：网关水平扩到 10 个实例、每实例各持一份内存态熔断器、失败阈值取配置的 `failure-threshold: 5`；基座值见 `token-factory-server/src/main/resources/application.yml:42-45`）。**阈值没有变，变的是它作用的对象——从"系统"退化成了"实例"。** 同一份配置在单实例部署上是"5 次失败即摘除"，在 10 实例部署上变成"要 50 次失败才摘干净"；而在这 50 次之间，每个实例都在替上游放大压力。

### 2.8 工业界怎么做：控制面与数据面分离

工业界切网关的方式和本讲不一样：不按"模块"切，按**改的频率**切——改得少、被读得多的叫控制面；每个请求都要穿过的叫数据面。

<img class="mermaid-svg" src="/zh/book-assets/diag-0163.svg" alt="工业界切网关的方式和本讲不一样：不按&quot;模块&quot;切，按改的频率切——改得少、被读得多的叫控制面；每个请求都要穿过的叫数据面。" />

> **图 36-3**　控制面与数据面的分界，以及这两半在 LiteLLM 里的具体形态。**这张图的判据是"改一次、读很多次"**：控制面是**被请求路径读到的数据与构造参数**，数据面是**请求路径上的代码**。而 LiteLLM 把这两半同时做成了两种交付物——一个可被引用的 `Router`（进程内）与一个 `--port 4000` 的独立 proxy 进程。

三段代码各落在半个平面上，值得逐个读：

```text
ENTRYPOINT ["docker/prod_entrypoint.sh"]
CMD ["--port", "4000"]
```

`litellm/Dockerfile:168-169`

**数据面的交付物是一个独立进程。** 这就是形态二在工业界的写法：镜像入口本身就是"起一个监听 4000 端口的服务"。

```text
    async def async_pre_call_hook(
```

`litellm/litellm/proxy/hooks/parallel_request_limiter_v3.py:578`

**限流在数据面里不是"一个模块"，而是"一个挂载点"。** 一个 pre-call 钩子意味着它天然在**每个实例、每个请求**上执行——这正是数据面的定义：**它没法被集中到一台机器上，除非你愿意为它多一跳网络。**

```text
    auto_rotate: bool | None = False
```

`litellm/litellm/models/verification_token.py:60`

**而控制面的产物是"实体"，不是函数。** "密钥要不要自动轮转"落在一个模型字段上（第 37 讲展开它的寿命与吊销语义）。

**把三段并列看，工业界的分界线是「请求路径上的代码」对「被请求路径读到的数据」。** 本项目里这两半的对应物很清楚：数据面是 `RoutingEngine` 加 `routesByAlias`（每次请求都跑一次的小查询，§2.3）；控制面是 `tf_price_rule`、`tf_access_key` 这些表（改一次、被读很多次）。

**两条路的差别只在"控制面的传播延迟"。** LiteLLM 的 `Router` 参数是一串**关键字构造参数**（`router.py:786` 到 `:811`：`num_retries` / `max_fallbacks` / `content_policy_fallbacks` / `enable_pre_call_checks` / `cooldown_time` / `allowed_fails` / `routing_strategy`）——构造期装配意味着**控制面的变化要先变成构造参数、再变成数据面行为**。本项目的选择相反：路由表走 `Function` 每次实时查库，于是控制面改完立刻生效，代价是每个请求多一次查询。**分辨这两种设计只需要问一句：改一个值，要不要重建对象？**

### 2.9 搬到你自己系统：网关分层的判据

**先把"分层"从审美问题变回一个可数的量。** 本项目的三层，依赖数是 1 / 3 / 9：

| 模块 | `<dependencies>` 内 `artifactId` 数 | 内容 |
|---|---|---|
| `token-factory-core` | **1** | `slf4j-api` |
| `token-factory-client` | 3 | `jackson-databind` / `jackson-datatype-jsr310` / `slf4j-api` |
| `token-factory-server`（HEAD） | 9 | `token-factory-core` + `web` / `jdbc` / `actuator` / `flyway` / `flyway-database-postgresql` / `postgresql` / `test` / `h2` |

（复算：`git show HEAD:<模块>/pom.xml`，只取 `<dependencies>` 与 `</dependencies>` 之间的段落，数其中 `<artifactId>` 的出现次数。）

**这个 1 不是"少"，是"边界"**——它是"这个模块能在没有框架的情况下跑起来"这句话**唯一可机械验证的形式**。同一件事还有第二个可数的量：`core` 与 `client` 的 `src/main/java` 下含 `import org.springframework` 的文件数都是 **0**（复算：遍历两模块主源 `.java`，统计含该字符串的文件数）。依赖数说的是"没引 Spring"，导入数说的是"代码里也没依赖 Spring 的注解"；**两个数都过，分层才算真的成立。**

再补一个规模量，用来判断"内核是不是被当成了杂物间"：主源 `.java` 文件数 core **31** / client **10** / server **45**（复算：`git ls-tree -r --name-only HEAD` 过滤 `<模块>/src/main/java/` 下的 `.java`）。**内核只有 31 个文件，却是三个模块里最"重"的那个**（测试类 7 个、依赖 1 个）——它承担全部算法，外壳只承担适配。

| 工业界 / 本项目做法 | 你自己系统里该问的问题 |
|---|---|
| `core` 只有 1 个依赖（`token-factory-core/pom.xml`） | 你的"内核"模块依赖清单能不能一眼看完？看不完的那部分，是不是某个适配器需要的？ |
| 一个内核、两种外壳（图 36-2） | 你要交付的是"算法"还是"管控"？两者都要的话，它们共用同一份内核吗？ |
| 熔断状态是内存态、重启即丢（`AdminHealthController.java:23-28`） | 你的熔断器分母是"本实例见过的请求"还是"全系统见过的请求"？ |
| 控制面/数据面按"改的频率"切（图 36-3） | 你的配置改完要不要重启？要重启的那部分，是不是本该做成"每次请求读"？ |
| LiteLLM 把 proxy 打成独立进程（`litellm/Dockerfile:168-169`） | 你的网关是一个进程还是一个库？这个选择是写下来的，还是碰巧的？ |
| 限流挂在 pre-call 钩子上（`parallel_request_limiter_v3.py:578`） | 你的限流计数在哪个进程？水平扩容之后它的语义变了没有？ |

**搬到你自己系统**，四步：

1. **把"内核模块"的依赖数写下来**，只数 `<dependencies>` 段内的 `<artifactId>`。**超过 3 个就问一句："这个依赖是算法需要的，还是某个适配器需要的？"**
2. **再数一遍 `import org.springframework`。** 依赖数是"声明层"，导入数是"使用层"；两个都数，才能抓住"依赖没引、代码里却在用"这种半破状态。
3. **给每个配置项标上"改它要不要重建对象"。** 要重建的归控制面（走发布流程），不重建的归数据面（走热更新）——**这条标注本身就是控制面与数据面的实际分界线。**
4. **确认你的熔断器分母。** 把"本实例失败次数"与"全系统失败次数"两种写法各推演一遍，选一个，并把结论写进类注释——本项目就是靠类注释把这句写下来的。

**判据**：**分层成立的最小证据不是"目录好看"，而是"内核模块的依赖数能一眼看完，且它的主源里没有一行框架导入"。** 两个数都拿得出来，"可被引进去"才是承诺；拿不出来，分层就只是一组包名。

## 三、避坑清单

- [ ] **`core` 模块的依赖清单必须能一眼看完。** 如果超过 3 个，问一句："这个依赖是算法需要的，还是某个适配器需要的？"后者应该挪到 `client` / `server`。
- [ ] **"零框架"不能只看源码，要看依赖传递。** 你的 `core` 源码里没有 `@Component`，但只要它依赖了某个内部 SDK，那个 SDK 的 Spring 依赖会**透传**到调用方。（`agent-kit` 在发 Maven Central 时就踩到过同类问题——classpath 门禁抓到 `token-factory-client` 透传。）
- [ ] **HTTP 客户端优先用 JDK 自带的 `HttpClient`。** SDK 每多一个 HTTP 客户端依赖，就多一份和业务方现有依赖冲突的可能。
- [ ] **每加一个"看不出来干嘛的"依赖，必须写一行注释说明它解决什么。** 否则半年后会被当成冗余删掉。
- [ ] **所有 `new` 集中在装配点。** 如果 `core` 里出现 `ApplicationContext`、`@Autowired`、`Environment`，分层已经破了。
- [ ] **"当前时间"与"等待"必须是可注入的依赖。** 否则测试要么变慢（真 sleep），要么不公平（跳过退避导致测不到真实时序）。
- [ ] **配置变更路径必须是热生效的。** 判断标准："改这个配置需不需要重启？"需要，就要问为什么。
- [ ] **拦截/鉴权的路径挂载要显式列举，不要 `/**` + if-else。** 默认值应该是"拦截"，而不是"放行"。
- [ ] **"本该拦住却进来了"必须返回 5xx，不是 401/403。** 前者是安全事件，后者是日常拒绝。
- [ ] **别把"进程内熔断"的阈值直接搬到多实例部署。** 内存态熔断器的分母是"本实例见过的请求"，实例一扩，上限跟着放大——同一个 `failure-threshold: 5`，单实例是"5 次失败即摘除"，10 实例是"要 50 次失败才摘干净"。**阈值没变，作用对象从"系统"退化成了"实例"。**（§2.7）
- [ ] **别把"改得少、读得多"的配置硬编码成构造参数。** 判据是"改一个值要不要重建对象"：要重建的，控制面的变更就必须等一次重建才能生效（LiteLLM 的 `Router` 走这条路）；不要重建的，才能做到毫秒级热生效（本项目路由表每次实时查库）。**两种都行，但要写清楚选的是哪一种。**（§2.8）
- [ ] **别用"目录好看"证明分层成立。** 只有两个数能证明：内核模块 `<dependencies>` 段内的 `<artifactId>` 数（本项目 1），以及它主源里含 `import org.springframework` 的文件数（本项目 0）。**少任何一个，"可被引进去"就只是口号。**（§2.9）

## 四、动手任务

> **目标**：亲手验证"分层"和"不分层"的差别，并给你的网关补上真正的默认拒绝。

**步骤 1：验证 `core` 的零框架**

```bash
cd token-factory
# 看 core 到底依赖了什么
mvn -o -pl token-factory-core dependency:tree
```

**预期结果**：依赖树只有 `slf4j-api`（及其可选传递项），**没有任何 Spring 组件**。

再对比 `client`：

```bash
mvn -o -pl token-factory-client dependency:tree
```

**预期结果**：`jackson-databind`、`jackson-datatype-jsr310`、`slf4j-api`。**同样没有 Spring**。

**步骤 2：证明内核可以脱离 Spring 使用**

写一个裸 `main()`（不引任何 Spring）：

```java
public static void main(String[] args) throws Exception {
    // 纯 Java 使用内核：不起容器、不连数据库
    CircuitBreakerConfig config = new CircuitBreakerConfig(2, 300L, 1, 1);
    CircuitBreakerRegistry registry = new CircuitBreakerRegistry(config);
    CircuitBreaker breaker = registry.forProvider("deepseek");

    breaker.acquire();          // 第一次放行
    breaker.recordFailure();
    breaker.acquire();          // 第二次放行（阈值 2 未到）
    breaker.recordFailure();    // 达到阈值 2 → 打开

    try {
        breaker.acquire();      // 第三次：OPEN，抛异常
        System.out.println("未熔断 —— 与预期不符");
    } catch (CircuitOpenException e) {
        System.out.println("已熔断，剩余冷却 " + e.remainingMillis() + "ms");
    }
}
```

**预期结果**：输出 `已熔断，剩余冷却 300ms`。**全程没有 Spring、没有数据库、没有 HTTP。**

这就是"内核"的定义：**它能在最简陋的环境里单独跑通。**

**步骤 3：为你的项目补一条"默认拒绝"**

如果你手上有类似的网关/中台项目，做一个检查：

```bash
# 找出所有鉴权拦截器的路径挂载
grep -rn "addPathPatterns\|addInterceptor" --include=*.java .
```

**关键对照**：如果一个拦截器挂了 `/**`，然后内部用 `if (uri.startsWith(...))` 判断——把这个模式改掉，**改成显式列举需要鉴权的路径前缀**，并加一个测试：**遍历你所有的 `@RequestMapping` 路径，断言每一个都落到了某个鉴权规则里**（白名单显式声明）。这个测试叫"路径覆盖测试"，它能防住未来 90% 的"新端点忘挂鉴权"。

**步骤 4：把"身份缺失"和"鉴权失败"分开**

在你的项目里 grep 一下：**拦截器没生效时会发生什么？** 如果答案是"继续往下走"，那你的系统在配置漏了的时候会**静默裸奔**。

正确的做法是 `ChatController.principal()` 那段：**取不到身份 → 500 + ERROR 日志 + 明确的"配置错误"提示**。补上它，然后在 k8s 里给 5xx 配一个独立的告警规则。

---

## 本讲小结

1. **分层按依赖方向切，不按功能切。** 判据是一句话："这个模块能不能在没有框架的情况下跑起来？"`core` 只有 `slf4j-api` 一个依赖。
2. **"零依赖"要看依赖传递，不能只看源码。** 你的 jar 里没有 Spring，不代表业务方引用时不会拉进 Spring。
3. **所有 `new` 集中在唯一装配点**（`GatewayConfig`）。`core` 里的类不知道 Spring 存在——是 Spring 来认识它们。
4. **可注入的时间与等待是测试可写性的前提。** 注入 `Sleeper` 与 `LongSupplier`，让"退避 500→1000→2000"能在毫秒内断言。
5. **配置热生效是可用性取舍，不是性能疏忽。** "改路由要重启全公司 AI 入口"才是真的贵。
6. **默认值必须是"拒绝"。** 路径挂载显式列举，而不是 `/**` + if-else。
7. **状态码是给告警系统看的。** "本该拦住却进来了"要返回 5xx——401 是日常噪音，会淹没真相。

下一讲我们把网关的门锁拆开：**AK 为什么用 SHA-256 而不是 BCrypt、为什么管理密钥"没配就拒绝"、以及这个项目里一处真实存在的"默认放行"漏洞。**
