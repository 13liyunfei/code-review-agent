# 第 09 讲 · 千万 DAU 的配额与容量：限流、预扣与优先级

> 🎯 导读问题：**"日额度配了 10 万次，为什么一个租户还是能把整个平台打满？"**

<img class="mermaid-svg" src="/zh/book-assets/diag-0037.svg" alt="🎯 导读问题：&quot;日额度配了 10 万次，为什么一个租户还是能把整个平台打满？&quot;" />

> **图 09-0**　本讲地图：把"一个租户打满整个平台"拆成三层——**额度**（这一周期花多少）、**限流**（每秒放行多少）、**隔离**（谁也不能拖垮谁）。A 基座只做了第一层，而且只做了它的一半。三层的失效模式不同源，所以判据也不同源：**缺哪一层，就有一种明确的洪水挡不住。**

## 一、痛点

从第 08 讲走出来的时候，"选哪条路"这件事已经闭环了：档位、通道、故障转移三层分开，配额与可用性由网关二次校验。

但"二次校验"这四个字里藏着一整个没讲的东西——**校验什么、用什么尺度校验、校验不过牺牲谁。**

一个很常见的上线事故长这样：

> 网关全线 429。不是攻击。监控显示：**一个租户**在 3 分钟内打了 40 万次请求。
>
> 而它的额度是 **"日 10 万次"**——它明明已经超标了，为什么还能打满平台？

答案有三层，全都反直觉。

**第一层：日额度拦不住"速率"。** 10 万次平摊到一天，均值是 `100000 / 86400 ≈ 1.16` 次每秒；集中在前 3 分钟打完，峰值是 `100000 / 180 ≈ 556` 次每秒——**峰值是均值的约 480 倍**。配额只约束"这一周期总共用多少"，对"一口气用多快"没有任何约束力。集群按均值配的容量，会被一个**完全守规矩**的租户（它确实没超过日额度）打穿。

**第二层：额度判定在并发下会集体放行。** 额度的判定是三步：读已用量、加上本次、比上限。十个请求同时进来，它们读到的是**同一个**已用量。于是十个都算出"加上我还没超"，十个全放行。**超发量最大可达并发数减一**，而每一步单独看都是对的。

**第三层更硬**，而且是 A 基座自己在注释里写下来的：token 与费用维度的额度，**只能在超限后的下一次调用被拦住**。因为"这次调用会生成多少 token"在调用前不可知。这不是实现偷懒，这是**能力边界**——本讲后面会看到它落在哪一行代码上。

动手之前先说清一件事，免得与第 40 讲混淆：

> **第 40 讲讲的是"被上游限流"**——收到 429 与 `Retry-After` 之后怎么退避、什么时候熔断，那是**防御**。
> **本讲讲的是"我方限流"**——我拿什么尺度决定放行谁、拒绝谁、先牺牲谁，那是**分配**。

这一讲解决四件事：**配额到底有几种粒度、本地限流为什么会按实例数超发、预扣掉的额度怎么退、以及超限时牺牲谁。**

## 二、原理

### 2.1 三种粒度，以及 A 基座缺的两条边

"配额"不是一个数，是三个互相不可替代的维度：

| 粒度 | 它约束的量 | 拦得住什么 | 拦不住什么 |
|---|---|---|---|
| **RPM**（每分钟请求数） | 请求的**频率** | 短请求高频刷量（洪水） | 一个超长请求占满并发槽；一次带 10 万 token 的大包 |
| **TPM**（每分钟 token） | 请求的**体量** | 大 prompt、大输出灌爆上游额度 | 短请求的高频洪水；而且**只能滞后一次调用**生效 |
| **并发**（在飞请求数） | 请求的**同时存在数** | 慢请求占满资源（长尾） | 单次超大 token；短请求高速轮转 |

> **判据**：**三者不是"三选一"，是三条互补的边。** 只配一条，就有一种明确的洪水挡不住——只配 RPM ⇒ 大包漏过；只配 TPM ⇒ 洪水漏过；只配并发 ⇒ 大包漏过。

A 基座有三个计量维度、两个周期，看起来挺全：

```text
public record QuotaPolicy(String id,
                          Scope scope,
                          Period period,
                          long limit,
                          Action action) {

    /** 计量维度。 */
    public enum Scope {
        /** 总 token 数（输入 + 输出）。 */
        TOKENS,
        /** 请求次数。 */
        REQUESTS,
        /** 累计费用（微元）。 */
        COST_MICROS
    }

    /** 统计周期。 */
    public enum Period {
        DAY,
        MONTH
    }
```

`token-factory/token-factory-core/src/main/java/io/tokenfactory/core/metering/QuotaPolicy.java:12-32`

**把它按上表对一遍，缺口立刻显形：**

| A 基座的维度 | 实际对应的粒度 | 周期 |
|---|---|---|
| `REQUESTS` | **RPD**（每日请求数） | DAY / MONTH |
| `TOKENS` | **TPD**（每日 token 数） | DAY / MONTH |
| `COST_MICROS` | 每日费用 | DAY / MONTH |

**三个维度全是"总量维"，一个速率维都没有，一个并发维都没有。** 这不是省略，是两条边真的不在。

而最有意思的证据不是"关键词命中 0"，是**数据模型里早就有位置**：

```text
    rate_limit_rpm  integer      not null default 600,
```

`token-factory/token-factory-server/src/main/resources/db/migration/V1__init.sql:28`

```text
                              int rateLimitRpm,
```

`token-factory/token-factory-server/src/main/java/io/tokenfactory/server/domain/AccessKeyRecord.java:18`

```text
    private static final RowMapper<AccessKeyRecord> MAPPER = (rs, rowNum) -> new AccessKeyRecord(
            rs.getLong("id"),
            rs.getString("key_id"),
            rs.getString("key_hash"),
            rs.getLong("tenant_id"),
            rs.getString("tenant_code"),
            rs.getString("name"),
            rs.getString("status"),
            rs.getInt("rate_limit_rpm"),
            TenantRepository.toInstant(rs.getTimestamp("last_used_at")),
            TenantRepository.toInstant(rs.getTimestamp("created_at")));
```

`token-factory/token-factory-server/src/main/java/io/tokenfactory/server/repository/AccessKeyRepository.java:20-30`

```text
                    "insert into tf_access_key (key_id, key_hash, tenant_id, name, status) "
```

`token-factory/token-factory-server/src/main/java/io/tokenfactory/server/repository/AccessKeyRepository.java:42`

**把四段连起来读**：建表里有 `rate_limit_rpm`（默认 600）、实体里有 `rateLimitRpm`、RowMapper 确实把它从结果集里读了出来——**然后全仓再没有一行代码读过它**。创建 Key 的 `INSERT` 列里甚至没有这一列，只能吃建表默认值。

> **判据**：**"数据模型留了位置"与"有代码用它"是两件事。** 排查这类缺口不能靠通读代码，要靠**数引用**：一个字段如果只有声明与赋值、没有任何读取，那它就是"设计了一半"。它的危险在于**看起来像做过了**——评审时看到列和字段都在，就会跳过。

业界怎么做"三条边都要"？看 LiteLLM 的 Key 模型，四个维度各占一行：

```text
    max_parallel_requests: int | None = None
```

`litellm/litellm/models/verification_token.py:31`

```text
    tpm_limit: int | None = None
    rpm_limit: int | None = None
    tpd_limit: int | None = None
```

`litellm/litellm/models/verification_token.py:33-35`

注意 **`rpm_limit` 与 `tpd_limit` 并存**：一个是速率维、一个是周期维，两者不是二选一——**总量要管、速率也要管**，因为它们对应两种完全不同的浪费方式。而 `max_parallel_requests` 是第三个字段、第三种语义。

限流器的运行时类型也照这三个词命名：

```text
    rate_limit_type: Literal["requests", "tokens", "max_parallel_requests"]
```

`litellm/litellm/proxy/hooks/parallel_request_limiter_v3.py:516`

<img class="mermaid-svg" src="/zh/book-assets/diag-0038.svg" alt="`litellm/litellm/proxy/hooks/parallel_request_limiter_v3.py:516`" />

> **图 09-1**　三种粒度的失效模式。**每一条边漏掉的东西，恰好是另一条边擅长的**——这正是它们不能互相替代的原因。注意"只配 TPM"那一格有个额外的坑：TPM 是**用量维**，而用量在调用前不可知，所以它天生滞后一次调用（见 2.3）。

### 2.2 本地桶与中心预扣：超发的上界是「实例数 × 桶容量」

确定了粒度，下一个问题是**在哪一层判定**。

标准答案叫令牌桶，三个参数必须分开设：

| 参数 | 含义 | 它决定 |
|---|---|---|
| **容量** `capacity` | 桶里最多攒多少令牌 | 一次能**突发**多少（开头能接住多大的一波） |
| **回填速率** `refill` | 每秒补回多少令牌 | **长期**每秒能通过多少 |
| **当前令牌数** `tokens` | 桶里此刻剩多少 | 这一瞬间放不放行 |

> **判据**：**容量与速率不能混为一谈。** "设了 100 QPS"这句话没有信息量——它可能是"每秒回填 100"，也可能是"桶里放 100 个令牌"，后者的长期通过量与速率无关。两者混设，就会出现"100 个请求同时进"。

接着是**放在哪**。极端选择有两个，都不好：

- **纯本地桶**：零网络往返，但每个实例各攒各的令牌。
- **纯中心桶**：精确，但每个请求多一次往返；在千万 DAU 下这次往返本身就是瓶颈与单点。

所以生产做法是**两级**：本地桶做粗筛（把绝大多数洪水挡在本地、零 RTT），只对"本地桶放行"的请求再做一次中心原子预扣。

**本地桶的代价是一个可以算出来的数**：`允许的瞬时超发量 ≤ 实例数 × 每实例桶容量`，**与回填速率无关**。

算例（可复算）：8 个实例，每实例桶容量 100 ⇒ 某一瞬间最多可以有 **800 个请求**同时被放行，是"每实例 100"的 8 倍。而配置的人脑子里想的是"我设了 100 的突发"。

⇒ 配置口径必须反过来写：**每实例桶容量 = 总能容忍的突发量 ÷ 实例数**。若总容忍突发是 100、实例 8 个，每实例桶容量该配 **12**，不是 100。**扩容会让超发量线性放大**——这是本地桶最反直觉的地方：加机器在保护能力上是"负向"的。

中心那一级，看 LiteLLM 怎么保证原子：

```text
-- Atomic check-and-increment-by-N across one or more descriptors.
-- All-or-nothing: if any descriptor would exceed its limit, no counter is
-- modified.
--
-- Uses Redis server time (`redis.call('TIME')`) instead of a client-supplied
-- timestamp so that window resets are deterministic across replicas with
-- skewed wall-clocks. This prevents a clock-skew-induced reopening of the
-- TOCTOU window across multi-replica deployments.
```

`litellm/litellm/proxy/hooks/parallel_request_limiter_v3.py:203-210`

这两条注释值得逐字读，因为它们是**两个不同层面的必要设计**：

1. **`All-or-nothing`**：一个请求同时命中多个描述符（Key、Team、Model 各一条）。要么全扣、要么全不扣——**不允许"扣了一半"**。扣一半会造成"额度被吃掉了但请求被拒"，用户看到的是拒绝，账上却少了量。
2. **`redis.call('TIME')`**：窗口重置的基准时间取 **Redis 服务器时间**，不取调用方传进来的时间。原因写在注释里：多副本的墙上时钟有偏斜，用客户端时间会让 TOCTOU 窗口**重新打开**——两个副本各自认为"窗口已过期"，于是都把计数清零。

限流挂在 `pre-call` 钩子上（`litellm/litellm/proxy/hooks/parallel_request_limiter_v3.py:578`），语义就是"在真正花钱之前"。

<img class="mermaid-svg" src="/zh/book-assets/diag-0039.svg" alt="限流挂在 `pre-call` 钩子上（`litellm/litellm/proxy/hooks/parallel_request_limiter_v3.py:578`），语义就是&quot;在真正花钱之前&quot;。" />

> **图 09-2**　两级闸门。**第一级买的是"零往返"，第二级买的是"准"**——代价分别写在两条边上：本地桶的超发上界由实例数决定，中心预扣的压力由本地桶的粗细决定。这两条边**互相拉扯**，所以没有"都对"的配置，只有"你更怕哪一个"的选择。

### 2.3 预扣与对账：难点不在扣，在怎么退

先分清两个容易混的词：

| 做法 | 判定方式 | 并发下会怎样 |
|---|---|---|
| **前判** | 读已用量 → 加上本次 → 比上限 | **N 个并发请求读到同一个已用量，集体放行** |
| **预扣** | 原子自增/预占 → 拿返回的新值比上限 | 只有拿到额度的那几个放行 |

A 基座走的是**前判**，算式只有两行：

```text
        for (QuotaPolicy policy : policies) {
            long used = base.valueOf(policy.scope());
            long projected = used + inc.valueOf(policy.scope());
            boolean exceeded = projected > policy.limit();
            boolean reject = exceeded && policy.action() == QuotaPolicy.Action.REJECT;
            decisions.add(new QuotaDecision(policy, used, projected, !reject,
                    exceeded && !reject, describe(policy, used, projected, exceeded)));
        }
```

`token-factory/token-factory-core/src/main/java/io/tokenfactory/core/metering/QuotaEvaluator.java:35-42`

逐字值得记两处：

- **`boolean exceeded = projected > policy.limit();` 用的是 `>` 而不是 `>=`。** 语义上很清楚："等于上限不算超"。这条在边界上必须一次说清，否则"上限 1000、已用到 1000"到底拦不拦就成了两种实现各说各话。
- **`long used = base.valueOf(policy.scope());` 里那个 `base` 是入参。** 它没有版本号、没有时间戳、没有并发控制——**所以它天然是"读到的那一瞬"的值**。

把第二点推到极端，就是那个"集体放行"的算例（可复算）：

> 上限 1000，当前已用 999。**10 个请求同时进入** `evaluate`。
> 每个都读到 `used = 999`，各自算 `projected = 999 + 1 = 1000`。
> `1000 > 1000` 为 `false` ⇒ **10 个全部放行**。实际用量走到 **1009**。
> **超发 9 次 = 并发数 − 1。**

一般式：**前判在最坏情况下的超发量 ≤ 并发数 − 1**，而"并发数"是你可以直接乘的那个数。

修法有两条，代价不同：

| 修法 | 做法 | 代价 |
|---|---|---|
| **把判定搬进原子操作** | 用 `INCR` 拿新值再比上限；超了就撤回本次自增（或直接不撤回，记为拒绝量） | 每次判定一次 Redis 往返；撤回本身也要原子 |
| **中心侧做成全有或全无** | 如 LiteLLM 的 Lua：一次判定多个描述符，任一超限则**一个计数器都不改** | 需要 Lua/事务；逻辑集中在脚本里，调试成本上移 |

**"扣"只是上半场，下半场是"退"。** 任何一个"先占后算"的系统，都必须先回答"差额往哪退"。这不是边角问题——LiteLLM 为此专门写了一段注释：

```text
        async_log_failure_event is a litellm completion-level callback and
        never fires for proxy-side rejections, so a leaked slot would occupy
        the gauge for the full PARALLEL_REQUEST_SLOT_TTL_SECONDS.

        Idempotent: the slot release clears the stashed acquisition (and slot
        removal is a no-op ZREM on a second run), and the TPM/ITPM/OTPM
        refund is guarded by the stash's ``reservation_released`` flag — if
        both this hook and async_log_failure_event end up running in the same
        flow, only the first release/refund applies. A mid-stream failure
        relayed here with recovered partial usage settles the reservation at
        that usage instead of refunding it.
```

`litellm/litellm/proxy/hooks/parallel_request_limiter_v3.py:5086-5096`

这段话里有**三个可以直接搬走的结论**：

1. **"释放"必须自己负责，不能指望那个天然的回调。** `async_log_failure_event` 是 completion 级回调，**代理侧自己拒绝的请求根本不会触发它**。于是被拒绝的那次预扣会一直占着额度，直到超时（`PARALLEL_REQUEST_SLOT_TTL_SECONDS`）。
2. **释放必须是幂等的。** 断连钩子与失败回调**可能都会跑**；`reservation_released` 标志保证"谁先退谁生效，后面的看到已释放就退出"。没有这个标志，同一笔预扣会被退两次——账目上凭空多出额度。
3. **中途失败不等于全额退，而是"按已发生的部分用量结算"。** 注释原文是 `settles the reservation at that usage instead of refunding it`。这条最容易漏：一个生成了 300 token 之后失败的请求，全额退就等于那 300 token 白送。

把"退"的各种情形列全，是这样一张表：

| 情形 | 预扣 | 实际 | 结算动作 | 真正的风险 |
|---|---|---|---|---|
| 正常 | 按上限预扣（如 `max_tokens` 给出的 1000） | 700 | 退 300 | 无 |
| 超预扣 | 1000 | 1500 | 补扣 500 | **补扣可能失败**（额度已被别人花掉）⇒ 要么允许"欠账"，要么把预扣设得足够保守 |
| 请求失败 | 1000 | 0 | 全额退 | **漏退 = 静默漏账**：额度少了但没有任何告警 |
| 中途失败 | 1000 | 300（已生成） | **按 300 结算** | 需要"已生成的部分用量"这个数，而它往往只在流式末帧里 |
| 客户端断开 | 1000 | 未知 | 按能拿到的用量结算 | 回到第 05 讲：断开之后上游是否真的停了 |

<img class="mermaid-svg" src="/zh/book-assets/diag-0040.svg" alt="| 客户端断开 | 1000 | 未知 | 按能拿到的用量结算 | 回到第 05 讲：断开之后上游是否真的停了 |" />

> **图 09-3**　预扣的生命周期。**四条出口里只有一条是"无风险"的**（实际小于预扣、退差额），另外三条各自对应一种对账缺口：补扣可能失败、中途失败要按部分量结算、取不到 usage 时要选一个"诚实但难看"的记法。**先把这四条出口写下来，再决定要不要做预扣**——只有第一种出口的预扣，是没算完的预扣。

现在回到 A 基座，看它的"诚实边界"落在哪一行：

```text
        QuotaUsage current = currentUsage(tenantCode, policies);
        List<QuotaDecision> decisions =
                QuotaEvaluator.evaluate(policies, current, QuotaUsage.of(1, 0, 0));
```

`token-factory/token-factory-server/src/main/java/io/tokenfactory/server/service/QuotaService.java:50-52`

这一行里，**`QuotaUsage.of(1, 0, 0)`** 的三个位置分别是 `requests / tokens / costMicros`。它只说"请求数 +1"，token 与费用一律填 **0**。为什么？因为**调用前不知道这次会生成多少**。

这段判断就写在类注释里：

```text
 * <p><b>精度说明（重要）</b>：请求数维度是精确的前置拦截（+1 后超限即拒绝）；
 * token 与费用维度只能基于「已用量」判定，因为输出长度无法预知——
 * 因此这两类额度是在<b>超限后的下一次调用</b>被拦住的，不是实时熔断。
 * 想要更严格的控制，把限额设保守一点或改用 REQUESTS 维度。
```

`token-factory/token-factory-server/src/main/java/io/tokenfactory/server/service/QuotaService.java:25-28`

这就是痛点里"第三层"的代码级证据。而它的**代价可以量化**：

> 滞后一次调用 ⇒ 超限幅度上界 = **单次调用可能生成的最大 token 数**。
> 若一个租户的额度是 **100 万 token**，最后一次放它进来可能让它跑到 `100 万 + 单次上限`——相对超发 < 1%。
> 若额度是 **1000 token**，同样一次调用就能让它冲到 9000 上下——**相对超发 800%**。
>
> ⇒ **额度设得越小，"滞后一次"的相对误差越大。** 这条给出了一个很实用的配置口径：**用小额度做细粒度管控时，别用 token 维度，改用 `REQUESTS` 维度**——这也正是注释最后那句建议。

### 2.4 优先级与降级次序：超限时牺牲谁

A 基座的动作只有两态：

```text
     *
     * <p>{@link #WARN} 存在的理由：一刀切 REJECT 会让「正在跑的批处理任务」中途全失败。
     * 先用 WARN 观察真实水位，再按租户切换到 REJECT，是更稳妥的落地路径。
     */
    public enum Action {
        /** 超限后拒绝请求。 */
        REJECT,
        /** 超限后放行但在响应与日志中告警。 */
        WARN
    }
```

`token-factory/token-factory-core/src/main/java/io/tokenfactory/core/metering/QuotaPolicy.java:36-45`

**`WARN` 那一栏的设计理由值得单独记一笔**，因为它是一类通用手法：**新上一个闸门时，第一次上线不要用 `REJECT`。** 先 `WARN` 把真实水位打出来，确认"以这个阈值计，会被拦住的量是不是我能承受的"，再按租户逐个切成 `REJECT`。一刀切 `REJECT` 的后果是"正在跑的批处理任务中途全失败"——那些任务再也跑不完了。

但**两态不够用**。千万 DAU 的场景里，"超限"不是一个二值判断，而是一道排序题：**同时超限的请求里，先牺牲谁？** 三种可用的次序，各有代价：

| 次序 | 先拒绝谁 | 前提 | 代价 |
|---|---|---|---|
| **按租户等级** | 先拒免费 / 试用租户 | 准入层必须**已经知道**租户等级 | 等级得是配置而不是从请求内容里猜 |
| **按请求类型** | 先拒批处理、保交互？或反之 | 要能可靠地判定"类型" | 类型通常是**调用方自己声明的**，可以被伪造 |
| **按已用量** | 先拒本周期已用得最多的 | 已用量在准入层可读 | 对"大客户"不公平，但换来了整体的公平性 |

> **判据**：**降级次序必须在准入层就能判出来。** 任何需要"先跑一半再决定牺牲谁"的次序，都不是降级策略，是失败路径。

配额能"分级"的前提是**策略可以继承**，否则每加一个租户就要把全部维度重配一遍。LiteLLM 做的是"继承 + 覆盖"：

```text
    allowed_models: list[str] | None = None  # per-member model scope; empty = inherit team models
```

`litellm/litellm/models/budget.py:33`

```text
class LiteLLM_TeamMemberTable(LiteLLM_BudgetTable):
```

`litellm/litellm/models/budget.py:62`

```text
    budget_id: str | None = None
```

`litellm/litellm/models/verification_token.py:48`

三段合起来是一条链：**Key 通过 `budget_id` 外键挂到一个 Budget 实体上**；**成员的预算类直接继承团队的预算类**（`LiteLLM_TeamMemberTable(LiteLLM_BudgetTable)`）；而团队又挂在组织下（`litellm/litellm/models/team.py:66` 的 `organization_id`）。这个设计给出的判据是：

> **配额要能"继承 + 覆盖"，而不是"逐个配全"。** 一个 `None` 表示"继承上一级"，一个显式值表示"覆盖"。这样加一个租户的默认成本是 **0 次配置**——只有需要例外的租户才要动。

配额重置也同理，是**两个字段一对**：

```text
    budget_duration: str | None = None
    budget_reset_at: datetime | None = None
```

`litellm/litellm/models/verification_token.py:36-37`

一个记"周期多长"，一个记"下次什么时候重置"。分开的理由很实际：**周期长度是策略，重置时刻是状态**——前者随策略变、后者随执行推进，混成一个字段就没法回答"我现在这个周期的起点是几点"。

### 2.5 突发：桶容量决定"开头接住多少"，回填速率决定"长期通过多少"

大促 / 秒杀这类场景，所有人都会本能地"把桶开大一点"。这个直觉是错的，而且错得很干净——**因为它只影响开头那一下。**

算例（可复算）：日额度 10 万次，桶容量 1000，回填速率 100/s。大促期间 180 秒内涌入 **18 万次**请求。

| 项 | 计算 | 结果 |
|---|---|---|
| 开头靠桶容量能接住 | 桶容量 | **1,000** |
| 180 秒内靠回填能通过 | `100 × 180` | **18,000** |
| 合计放行 | `1000 + 18000` | **19,000** |
| 必须拒掉 | `180000 − 19000` | **161,000** |
| 若改成排队：消化退回量要多久 | `161000 / 100` | **1,610 秒 ≈ 27 分钟** |

把桶容量从 1000 提到 10000 会怎样？

| 项 | 结果 |
|---|---|
| 合计放行 | `10000 + 18000` = **28,000** |
| 比原来多放 | **9,000 次**，占需求的 **5%** |

⇒ **加大桶容量解决不了总量问题。** 长期的通过量只由**回填速率**决定；桶容量只在"最开始那一瞬"起作用。而 27 分钟的排队意味着客户端在等到令牌之前就已经超时了——**队列里的请求全部变成"白占资源"**。

三种处置要选一个，且必须写下来：

| 处置 | 做法 | 代价 |
|---|---|---|
| **放行到桶空** | 桶空了就直接拒绝（`429` + `Retry-After`） | 用户看到错误，但服务端零成本 |
| **排队等令牌** | 请求挂在有界队列里等 | 每条排队请求占一条连接与一份内存；**队列必须是有界的**，否则"排队"等于"把雪崩推迟到超时那一刻" |
| **降级** | 排队的同时降级到更小/更便宜的模型 | 复杂度最高，但唯一能"既不全拒也不全等"的路 |

> **判据**：**大促的正确做法不是"加大桶容量"，而是"把排队改成有界 + 快速失败"。** 无界队列不会让任何请求成功，它只会让失败来得更晚——并且在那之前吃掉所有内存。队列与舱壁（第 25 讲）是同一件事的两面：**准入侧排的队，最终要落到执行侧的池子上。**

顺带一条容易忽略的：**拒绝也要带上"什么时候可以再来"的信息。** LiteLLM 在把各厂商的响应头归一化：

```text
    if "x-ratelimit-limit-requests" in _response_headers:
        openai_headers["x-ratelimit-limit-requests"] = _response_headers["x-ratelimit-limit-requests"]
    if "x-ratelimit-remaining-requests" in _response_headers:
        openai_headers["x-ratelimit-remaining-requests"] = _response_headers["x-ratelimit-remaining-requests"]
```

`litellm/litellm/litellm_core_utils/llm_response_utils/get_headers.py:23-26`

```text
        openai_headers["x-ratelimit-limit-requests"] = headers["anthropic-ratelimit-requests-limit"]
    if "anthropic-ratelimit-requests-remaining" in headers:
        openai_headers["x-ratelimit-remaining-requests"] = headers["anthropic-ratelimit-requests-remaining"]
```

`litellm/litellm/llms/anthropic/common_utils.py:1725-1727`

这两段说的是同一件事：**不同厂商的限流头名字不一样，但要有一套内部统一的名字**。`x-ratelimit-*` 是 OpenAI 的形态（可直链的官方说明见 <https://platform.openai.com/docs/guides/rate-limits>），Anthropic 用 `anthropic-ratelimit-*`，LiteLLM 在适配层把后者翻成前者。

判据：**"上限 / 剩余 / 重置时刻"这三个信息必须在同一套名字下可读**——因为客户端退避逻辑（第 40 讲）只应该认一套。否则每接一家上游，退避策略就得改一次；而**你永远不会记得改**。

### 2.6 搬到你自己系统：把闸门写成可复算的数

先把 A 基座的现状摆成可复算的数：

| 项 | 值 | 复算方式（在 `token-factory` 仓，全部按 HEAD） |
|---|---|---|
| 配额相关文件行数 | `QuotaPolicy` **55** / `QuotaEvaluator` **69** / `QuotaUsage` **41** / `QuotaDecision` **22** / `QuotaService` **111** | `git show HEAD:<path> \| wc -l` |
| 全仓 `rateLimitRpm` 出现次数 | **1**（只有字段声明） | `git grep -n "rateLimitRpm" HEAD` |
| 全仓 `RateLimiter` / `TokenBucket` / `Semaphore` 命中 | **0 / 0 / 0** | `git grep -c "<关键词>" HEAD -- '*.java'` |
| 全仓 `max_parallel` 命中 | **0** | 同上 |
| `Scope` 枚举的维度数 | **3**（全是总量维） | 数 `QuotaPolicy.java:19-26` |

**这五个数字合起来说明一件事**：A 基座有一套**能用的总量配额**（三个维度、两个周期、两种动作、紧凑构造器兜底），但它**一个速率维、一个并发维都没有**；而且数据模型里的 `rate_limit_rpm` 是"留了位置没接上"，不是"没想到"。

**双基座对照**——同一件事，两个完全不同的难度：

| 维度 | A：`code-review-agent`（及其网关 `token-factory`） | B：千万 DAU 的大模型 API 网关 | 由此分叉的设计决策 |
|---|---|---|---|
| 租户数 | 内部几个团队 | 数十万 API Key、多层（org / team / member） | A 可以逐个配；**B 必须能继承 + 覆盖** |
| 额度的粒度需求 | 日 / 月总量足够（审查任务是批处理，天然不突发） | 必须同时有 RPM / TPM / 并发（交互式流量天然突发） | **B 必须有速率维与并发维** |
| 判定精度要求 | 差几次调用没人发现 | 单租户打满平台 = 全站不可用 | A 前判即可；**B 必须原子预扣** |
| 判定的并发度 | 常驻几十个并发 | 峰值数千并发同时判定 | **B 的前判会集体放行，必须换机制** |
| 超限后的动作 | `WARN` 观察 + `REJECT` 拦住 | 还要能"按租户等级排队 / 降级" | A 两态够用；**B 需要优先级** |
| 对账要求 | 内部账，差几块钱没人发现 | 按调用计费，账单要与上游逐分对齐 | **B 必须把"退"的四种出口全部实现** |

**搬到你自己系统，五步：**

1. **先数边，再配数。** 把"RPM / TPM / 并发"三条边对照你系统的真实洪水逐条问一遍：**这条边拦得住什么？拦不住什么？** 任何一条答不上来的，就先别配额度的数值——因为你还不知道要防什么。
2. **动手前先数一遍"字段有没有人用"。** 用 `grep`（本机 shell 的 `grep` 会漏匹配，用带 `glob` 的检索工具）数你数据模型里所有"看起来像配额"的字段，看它们是否**只有声明没有读取**。2.1 那个 `rate_limit_rpm` 就是这么被找出来的——**它是"看起来做过了"的典型形态**。
3. **把本地桶的容量按实例数折算。** 公式：`每实例桶容量 = 总能容忍的突发 ÷ 实例数`。然后加一条断言：**扩容后重新算一遍**。这一步不做，扩容会自动放大超发量，而所有监控都是绿的。
4. **要么不做预扣，要么把四条出口想全。** 参照 2.3 那张表，逐条写出"实际小于/大于预扣、中途失败、取不到 usage"时的动作。**取不到 `usage` 那一格必须显式选一个**：算准、明说不计费、明确不支持——三者选一，"估算一下"是第四种，也是最坏的一种。
5. **对账的口径要"统一到一套名字"。** 把上游返回的限流/用量头归一到你自己的一套键名（如 2.5 的 `x-ratelimit-*`），退避与告警只认这一套。**新增一家上游时，适配层是唯一要改的地方**——这是判断"归一化做没做对"的标准。

> **判据**：**"我们做了配额"这句话，只有在你能同时说出"三条边各配了多少"和"超限时先牺牲谁"时才算成立。** 说不出第二个的，是把闸门装上了，但没有装开关。

## 三、代码

这一节把 A 基座这套配额的**完整形状**读一遍，再看它离 B 基座还差哪几块。全部按 `token-factory` 的 HEAD，行号可用 `git show HEAD:<path>` 逐行复核。

### 3.1 三个维度、两个周期、两种动作

```text
    /** 统计周期。 */
    public enum Period {
        DAY,
        MONTH
    }
```

`token-factory/token-factory-core/src/main/java/io/tokenfactory/core/metering/QuotaPolicy.java:28-32`

这是 `Period` 的两个取值。它与 `Scope` 是**正交**的：任意维度 × 任意周期都合法，所以"日 token 额度"和"月请求额度"可以同时存在——这正是同一份策略列表里会混着不同周期策略的原因。

紧凑构造器做了一个容易被忽略的兜底：

```text
    public QuotaPolicy {
        limit = Math.max(0L, limit);
        action = action == null ? Action.WARN : action;
    }
```

`token-factory/token-factory-core/src/main/java/io/tokenfactory/core/metering/QuotaPolicy.java:47-50`

两个默认值的方向**不一致，而且不一致是对的**：

- `limit` 非法（负数）⇒ **收敛为 0**，即"最严格"。因为一个负的上限在语义上无意义，取 0 至少是安全的（全拒）。
- `action` 缺失 ⇒ **收敛为 `WARN`**，即"最宽松"。因为没写明处置方式时，直接开始拒绝线上流量是最危险的选择。

> **判据**：**默认值的方向应该指向"最不容易造成不可逆后果"的那一侧。** 数值类字段往安全侧收，动作类字段往观察侧收。如果两个方向都往"严格"收，第一次上线就会全站 429。

### 3.2 维度的取值方式，以及它为什么必须带 `period`

```text
    /** 取该策略关注维度的当前值。 */
    public long valueOf(QuotaPolicy.Scope scope) {
        return switch (scope) {
            case REQUESTS -> requests;
            case TOKENS -> tokens;
            case COST_MICROS -> costMicros;
        };
    }
```

`token-factory/token-factory-core/src/main/java/io/tokenfactory/core/metering/QuotaUsage.java:33-39`

这七行是"维度可插拔"的落点：策略带 `scope`，基线按 `scope` 取值。**但请注意入参只有一个 `scope`，没有 `period`。**

后果不是"少一个参数"，而是：**同一份基线数据被拿去判定全部策略**。而 `QuotaUsage` 只有三个字段、**没有周期**——所以"这份 requests 是从哪个起点统计的"这个信息，在数据里根本不存在。

A 基座有两条取基线的路径，口径不同：`snapshot`（查询用）按周期分组、分别统计；`evaluate`（拦截用）统一取"所有相关周期里最早的起点"。同一份配额数据**被两条路径以两种口径读取**——这是"闸门"与"账本"没有分家留下的后患，具体的错法在第 38 讲逐字分析。

```text
     * <p>按<b>周期分别统计</b>再判定：日额度与月额度的统计起点不同，
```

`token-factory/token-factory-server/src/main/java/io/tokenfactory/server/service/QuotaService.java:66`

> **判据**：**凡"策略带一个维度，基线却不带这个维度"的判定，都必然错。** 拦截路径与查询路径必须共用同一套取数口径——它们可以有不同的性能取舍，但不该有不同的**语义**。两条路径走两套口径，等于同一件事有两个答案。

### 3.3 缺的三块，以及业界怎么补

| 缺的东西 | A 基座现状（HEAD） | 补法 | 补它的代价 |
|---|---|---|---|
| **速率维** | 只有 `REQUESTS/DAY` 这类总量维；`rate_limit_rpm` 列存在但无人读 | 加"每分钟"窗口的计数（滑动窗口 / 令牌桶） | 需要窗口状态存储（Redis）；多实例下要原子 |
| **并发维** | 全仓 `max_parallel` 命中 **0** | 在飞计数器，准入时 +1、结束时 −1 | **必须解决"泄漏"**：拒绝路径与崩溃路径都不会自动 −1 |
| **预扣与对账** | `QuotaUsage.of(1, 0, 0)`：token 与费用按 0 计，只能前判 | 原子预扣 + 按实际 usage 结算（四条出口） | 需要"已生成的部分用量"这个数；`usage` 拿不到时要显式选口径 |

并发维为什么单独列出来，看 LiteLLM 的做法就清楚了——**它用的不是计数器**：

```text
-- Atomic check-and-acquire for the max_parallel_requests concurrency gauge.
-- Each gauge key is a sorted set of per-request slot ids scored by acquire
-- time (Redis server clock). In-flight requests are counted by ZCARD after
-- pruning slots older than the slot TTL, so unlike the windowed RPM/TPM
-- counters the gauge is never reset while requests are in flight, a
-- rejected request never occupies a slot, and a slot leaked by a crashed
-- worker self-heals after the slot TTL even under continuous traffic.
```

`litellm/litellm/proxy/hooks/parallel_request_limiter_v3.py:331-337`

这七行注释把一个"计数器"换成"带时间的集合"，一次性解掉了三个问题：

| 计数器的三个坑 | 排序集合为什么没有这个坑 |
|---|---|
| 窗口到期会把计数**清零**，而在飞的请求还没结束 | `ZCARD` 数的是"还没过期的槽"，**in-flight 期间永远不会重置** |
| 被拒绝的请求也可能把计数 +1（先加后判） | 拒绝时**根本不写入槽**，所以不占位 |
| 某个 worker 崩了，它的槽永远 − 不回去 | 槽有 TTL，**过期自动消失**；注释原话是 `self-heals` |

> **判据**：**并发槽位不能用计数器。** 计数器只能表达"发生了多少次"，表达不了"此刻还有几个没结束"——而后者才是并发的定义。用计数器的实现会出现一种最难查的故障：**站点的并发上限随时间缓慢下降**，重启后恢复正常，然后再次下降。

## 四、避坑清单

- [ ] **别把"额度"当"限流"。** 额度管总量（这一周期花多少），限流管速率（每秒放行多少），两者的失效模式不同源。**日额度 10 万次挡不住 3 分钟打完它。**
- [ ] **三条边要一起配：RPM / TPM / 并发。** 每一条漏掉的东西恰好是另一条擅长的；只配一条就有一种明确的洪水挡不住。（§2.1）
- [ ] **"数据模型留了位置"不等于"功能做了"。** 用时序计数法查一遍：一个字段是否只有声明与赋值、没有任何读取？`rate_limit_rpm` 就是这么被翻出来的。（§2.1）
- [ ] **本地桶的容量必须按实例数折算。** `每实例容量 = 总容忍突发 ÷ 实例数`；不折算就等于把超发上界放大到实例数倍，而且**扩容会把它继续放大**。（§2.2）
- [ ] **容量与速率不能混设。** "设了 100 QPS"可能指回填速率、也可能指桶容量，两者的长期通过量完全不同。
- [ ] **多描述符的预扣必须 all-or-nothing。** 扣一半造成的后果是"请求被拒了、额度却被吃掉"，用户与账本看到的是两件事。（§2.2）
- [ ] **窗口基准时间必须取服务端时间，不能取调用方传的时间。** 多副本时钟偏斜会让 TOCTOU 窗口重新打开——两个副本各自认为窗口过期，于是都把计数清零。（§2.2）
- [ ] **前判在并发下会集体放行，超发上界 = 并发数 − 1。** 需要精确控制时，把判定搬进一次原子操作，或做成中心侧的全有或全无。（§2.3）
- [ ] **判定用的基线必须与策略同维度**：策略带 `period`，基线就必须带 `period`。拦截路径与查询路径也必须共用同一套语义。（§3.2）
- [ ] **先决定"怎么退"，再决定"要不要预扣"。** 四条出口（退差额 / 补扣 / 按部分用量结算 / 取不到 usage）想不全，预扣就是自找的对账缺口。（§2.3）
- [ ] **"释放预扣"必须自己负责，且必须幂等。** 代理侧自己拒绝的请求不会触发 completion 级失败回调；断连钩子与失败回调可能都跑，没有幂等标志就会退两次。（§2.3）
- [ ] **中途失败要按已发生的部分用量结算，不是全额退。** 生成了 300 token 之后失败，全额退等于那 300 token 白送。
- [ ] **新上闸门的第一版动作用 `WARN`，不要用 `REJECT`。** 先把真实水位打出来，再按租户逐个切成 `REJECT`——一刀切 `REJECT` 会让正在跑的批处理任务全部中途失败。（§2.4）
- [ ] **降级次序必须在准入层可判。** 任何"跑一半再决定牺牲谁"的次序都不是降级策略，是失败路径。（§2.4）
- [ ] **配额要能"继承 + 覆盖"，不要"逐个配全"。** `None = 继承上一级`、显式值 = 覆盖；这样新增租户的默认配置成本是 0。（§2.4）
- [ ] **大促不要靠加大桶容量。** 长期通过量只由回填速率决定；桶容量只影响开头那一下（算例里提到 10 倍容量只多放 5%）。真正的动作是"排队改成有界 + 快速失败"。（§2.5）
- [ ] **无界队列不是缓冲，是延迟爆炸。** 它不会让任何请求成功，只会让失败来得更晚，并在那之前吃掉内存。
- [ ] **限流/用量头要归一到一套内部名字。** 退避与告警只认这一套，新增上游只改适配层；否则每接一家就要改一次退避策略，而你永远不会记得改。（§2.5）
- [ ] **并发槽位不要用计数器。** 用带 TTL 的槽集合：in-flight 期间不被重置、被拒请求不占位、崩溃泄漏的槽自动消失。（§3.3）

## 五、动手任务

> **任务一：亲手复现"前判在并发下集体放行"。**
>
> **操作**：
> 1. 为同一个租户配一条 `REQUESTS/DAY` 额度，上限设 **1000**（用 `tf_quota` 表插一条，或走管理接口）。
> 2. 先把已用量推到 **999**（打 999 次请求，或直接改 `tf_usage` 的聚合结果）。
> 3. 用 **10 个并发**同时发请求（`xargs -P 10` 或任意并发工具）。
>
> **预期结果**：**10 个请求全部返回 200**，而不是"只放行 1 个"。
> 之后查用量：**总数 = 1009**——比上限多 9，正好等于"并发数 − 1"。
>
> **为什么这条实验值得做**：它是**唯一一条"每一步都正确、整体却错了"的故障**。单线程能跑通，压测才暴露；而在单线程复现里你永远看不到它。

> **任务二：验证"token 额度滞后一次调用"。**
>
> **操作**：
> 1. 配一条 `TOKENS/DAY` 额度，上限设成一个**明显小于单次调用量级**的数（例如 1000）。
> 2. 用同一个租户连续调 3 次（每次请求的 `max_tokens` 设成 2000 或更大）。
> 3. 每次记录返回的状态码与 `usage`。
>
> **预期结果**：
> - 第 1 次：**放行**（判定时读到的已用量还是 0）。
> - 第 2 次：**放行**（判定时读到的已用量是"上一次之前的累计"，不含上一次）。
> - 第 3 次起：**429**。
> - 关键在于**最后一次被放行的调用把它推过了上限**，超出幅度可以远大于上限本身。
>
> **对照实验**：把维度从 `TOKENS` 换成 `REQUESTS`，同样的步骤——**第 2 次就会被拦住**。这个对照说明"滞后一次"是 `TOKENS` 维度独有的性质，而不是整个配额系统的性质。

> **任务三：把你自己的数据模型"数一遍"。**
>
> **操作**：
> 1. 在你的项目里，列出所有名字里带 `limit` / `quota` / `rate` / `rpm` / `tpm` / `concurrent` 的字段与列（SQL 建表 + 实体类 + 配置文件）。
> 2. 对每一个，检索它的**全部引用**，分成三类：① 有读取并参与判定；② 只有声明与赋值；③ 有读取但结果被丢弃（例如取出来的 `props` 之后没用）。
>
> **预期结果**：你大概率会找到至少一个第 ② 类。**找到它之后不要立刻修**——先问"当初为什么留了它"，答案通常是一条被砍掉的需求，或者一个没接上的上游字段。**知道它为什么在那儿，比补上判定更重要。**

---

## 本讲小结

1. **配额是三条边，不是三个数。** RPM（频率）、TPM（体量）、并发（同时存在数）各自漏掉的东西恰好是另一条擅长的；A 基座的三个维度**全是总量维**，速率与并发两条边真的不在。
2. **"设计了一半"比"没设计"更危险。** `rate_limit_rpm` 列存在、实体字段存在、RowMapper 把它读了出来——然后全仓没有一行代码用它拦人。判据是**数引用**，不是读代码。
3. **本地限流买的是零往返，付的是超发。** 上界是 `实例数 × 每实例桶容量`，**且扩容会线性放大它**。配置口径必须写成"每实例容量 = 总容忍突发 ÷ 实例数"。中心那一级需要原子性与 all-or-nothing，窗口基准时间必须取服务端时间。
4. **前判在并发下会集体放行，超发上界 = 并发数 − 1。** 这是唯一一类"每一行都正确、整体却错了"的故障：单线程复现不出来，压测才暴露。
5. **预扣的难点不在扣，在退。** 四条出口必须全部想清：退差额、补扣（可能失败）、按已发生的部分用量结算、取不到 `usage` 时选一个诚实口径。而且"释放"必须自己负责、必须幂等——**代理侧自己拒绝的请求不会触发那个天然的回调**。
6. **大促不靠加大桶容量。** 长期通过量只由回填速率决定；桶容量只影响开头那一下。真正的动作是"排队改成有界 + 快速失败"——无界队列不会让请求成功，只会让失败更晚、更贵。
7. **超限时牺牲谁，是一道必须先答的排序题。** 降级次序必须能在准入层判出来；而配额要能"继承 + 覆盖"，否则每加一个租户就把全部维度重配一遍。

模块一到此结束。你已经能把一个 LLM **调对**（协议、超时、路由）、**调稳**（熔断、退避、配额）、**调得可观测**（成本、用量、健康），并且知道在千万 DAU 的量级上，这三件事各自的闸门该装在哪一层。

**从第 10 讲开始，我们把视角从"调模型"切到"造 Agent"**——先看一个 Agent 的最小骨架到底是什么形状，再往上叠工具、记忆、提示词、上下文与可靠性。
