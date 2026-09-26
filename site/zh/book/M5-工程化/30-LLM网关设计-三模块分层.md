# 第 30 讲 · LLM 网关设计：三模块分层与"能被人引进去"的边界

> 🎯 导读问题：**"你怎么设计一个给全公司用的 LLM 网关？"** ——90% 的人会答"加鉴权、加限流、加缓存"，但真正决定这个网关能不能落地的，是**依赖方向**。

> **视角切换声明**：模块一里我们是**调用方**——怎么把一个 LLM 调对、调稳。从这一讲开始我们是**平台方**——怎么把它做成给整个团队用的基础设施。同一套 `token-factory`，两个视角。第 02–07 讲里你看到的 `TokenFactoryClient` 是它的**外表**；这一讲拆它的**骨架**。

---

<img class="mermaid-svg" src="/zh/book-assets/diag-0068.svg" alt="图 30-0　本讲地图：分层按依赖方向切，判据是没有框架能否跑。core 只依赖 slf4j-api，client 零 Spring，Spring 只在 server 外层，装配点唯一。" />

> **图 30-0**　本讲地图：分层按依赖方向切，判据是没有框架能否跑。core 只依赖 slf4j-api，client 零 Spring，Spring 只在 server 外层，装配点唯一。

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

那段注释值得单独读一遍：`jackson-datatype-jsr310` 的引入**被写清了理由**——"Jackson 默认不认识 `java.time.Instant`"。这是真实的踩坑记录：不加这个模块，`Instant` 字段序列化会直接抛 `InvalidDefinitionException`（就像本书第 04 讲里 `orTimeout`、第 16 讲里裸 `ObjectMapper` 那类问题一样，是"跑起来才发现"的坑）。**加依赖不丢人，不写清为什么加才丢人**——三个月后别人（包括你自己）会问"这个包是干嘛的，能删吗"。

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

**没有 `security`** —— 鉴权是自己用 `HandlerInterceptor` 写的（第 31 讲详述）。这不是"造轮子癖"，而是：一旦引入 Spring Security，整个应用的过滤器链、`SecurityContext`、默认登录页、CSRF 策略都会跟着进来，而这些**都不是一个网关内核该拥有的东西**。网关的鉴权语义只有两句话："认 AK" 和 "认 Admin-Key"。

### 2.3 `core` 不认识 Spring，那谁来装配？

这是分层最容易被忽略的一环：**内核是纯 Java，那它怎么变成 Spring Bean？**

答案是一个**唯一的装配点**——`GatewayConfig`。它的类注释就是答案：

<img class="mermaid-svg" src="/zh/book-assets/diag-0069.svg" alt="答案是一个唯一的装配点——`GatewayConfig`。它的类注释就是答案：" />

**规律很清晰：所有 `new` 都发生在这一个文件里。** `core` 里的类全部是 `final class` + 构造器注入（第 34 讲会看到 `RoutingEngine` 的 7 个构造参数），它们**不知道 Spring 的存在**，是 Spring 主动来"认识"它们。

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

这条经验可以推广成一句：**任何"等待"与"取当前时间"，都应该在边界处被抽象成可注入的依赖。**（这个 Sleeper 的注入在本项目里还留了一个坑，见第 34 讲。）

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
