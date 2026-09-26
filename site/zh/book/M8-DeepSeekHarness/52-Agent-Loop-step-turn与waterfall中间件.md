# 第 52 讲 · Agent Loop：step、turn 与 waterfall 中间件

> 🎯 导读问题：**"agent loop 不就是个 while 循环吗？为什么它自己也能被替换？"** ——能答出"因为它的**每一步都暴露成了事件**：能进入哪一步（`pre-step`）、请求提交前能不能拦（`request`）、流式能不能包（`llm/stream`）、工具执行前后能不能插（`tools/*`）；**循环本身只是这些事件的默认消费者**"——这一句就把"写死一个 while"和"把它做成可拦截的流水线"分开了。**本讲要建立的判据是：只有"顺序能改变结论"的事件才配做成 waterfall；顺序改不了结论的，做成 serial 并把判据写在数据里。**

> **模块八 · 第四讲**：前两讲讲了"插件能做什么"（三个动作）和"最重的那份产物"（会话日志）。这一讲看**那个把所有插件串成一次运行的循环**。它的特殊性在于：`docs/architecture.zh.md:11-13` 明确说，**agent loop 本身都是插件**——所以这一讲不是"介绍一个模块"，而是"看一个核心机制如何把自己也交给事件"。
>
> **为什么单独讲它**：因为你未来要动的大多数"行为微调"，落点都在这一讲的四个事件上。**不知道拦截点在哪，就只能改循环源码——那正是这一模块要你避开的那条路。**

<img class="mermaid-svg" src="/zh/book-assets/diag-0123.svg" alt="为什么单独讲它：因为你未来要动的大多数&quot;行为微调&quot;，落点都在这一讲的四个事件上。不知道拦截点在哪，就只能改循环源码——那正是这一模块要你避开的那条路。" />

> **图 52-0**　本讲地图：一个轮次含零或多个步骤；每一步有四个拦截点（`agent/pre-step`、`agent/request`、`llm/stream`、`tools/*`），它们都是 waterfall。而**轮次收尾 `agent/turn-stopping` 偏偏是 serial、没有 `next()`**——这个反差本身就是本讲的判据。

## 一、痛点

先说清一件事：**"agent loop 是个 while 循环"这句话，在实现层面是对的，在工程层面是个陷阱。**

你按它写：

```java
while (hasWork()) {
    var resp = llm.chat(history, tools);
    if (resp.hasToolCalls()) { history.add(execute(resp.toolCalls())); }
    else break;
}
```

这段代码能跑。但当需求变成下面任意一条时，它就撑不住了：

- **"某些步骤我要先看一眼再决定放不放行"**（内容准入、安全策略）；
- **"请求发出去之前我要换一个路由"**（模型降级）；
- **"工具执行前要过一遍审批"**（权限）；
- **"我要记录每一步的耗时和成本"**（可观测性）；
- **"某些情况下我要在收尾前再塞一次输入"**（止损、追加指令）。

**这五条需求，全都要"插进循环中间"。** 而在一个写死的 while 里，"插进去"只有两个办法：**改循环源码**，或者**把循环写成模板方法 + 一堆子类**。前者让循环迅速腐烂成没人敢动的巨石，后者让你每加一条策略就多一个类。

> **问题的本质不是"循环写得好不好"，而是"循环有没有把控制流暴露成可挂载的东西"。**

`dsh` 的答案是：**把循环的每一个关键位置都做成"类型化事件"**（第 50 讲的第二个动作），然后让循环去做这些事件的**默认实现**。于是"插进去"= **注册一个监听器**，与循环源码零耦合。

而要理解这套做法，得先精确地知道它循环的**两级时间单位**。

## 二、原理

### 2.1 step 与 turn：两个必须分清的粒度

**步骤（step）** 的定义（术语表 `docs/glossary.zh.md:38` 原文）：

> **步骤**：一次模型请求，以及由模型响应引发的工具执行；一个轮次包含零个或多个步骤。

事件侧的 JSDoc 更短（`docs/subsystems/session.zh.md:44-45`）：

> *"Opens step `step` of turn `turn` — **one model call plus the tool executions it requested**."*

**轮次（turn）** 的定义（`docs/glossary.zh.md:37` 原文）：

> **轮次**：会话中一次对**已接纳输入**的排空过程，在模型及其工具停止工作或终止策略介入后结束。

**★ 判据句（本讲第一个架构判据）**：

> **"轮次含零个或多个步骤"——这个"零"是设计的重点，不是边界情况。** 它意味着一个轮次可以在**一个模型请求都没发**的情况下开合（例如输入被 `pre-step` 全数拒绝，或首次领取为空）。`docs/architecture.zh.md:96-97` 原文就写了这个分支：*"reject, or a first enter rewritten empty -> close the turn with no step"*。
>
> **为什么重要**：如果实现里假设"轮次至少一步"，那么"全部拒绝"就被迫用异常或空对象表达——**而"拒绝"是一个正常的业务结论**（第 51 讲讲过同一件事：空值必须是合法返回）。**"零步骤轮次"能不能被自然表达，是这套循环设计是否干净的一个试金石。**

### 2.2 一个轮次的完整流程

`docs/architecture.zh.md:92-111` 直接给了一段伪码。它的信息密度很高，我按"阶段 + 在这一阶段决定什么"重排一遍（顺序、事件名一字不改）：

| # | 阶段 | 关键动作 / 决定 |
|---|---|---|
| 1 | `turn/start` | 在"领取输入 / 跑 pre-step"**之前**开启轮次 |
| 2 | claim | 领取"下一步输入 + 一条排队消息" |
| 3 | assemble | 组装提示词片段与工具 schema；投影运行期上下文 |
| 4 | **`agent/pre-step`** | **waterfall**：拒绝 / 允许进入（`enter(messages, startsRequestSeries?)`） |
| 5 | — | 被拒或首次 enter 被改写为空 ⇒ **关闭一个不含步骤的轮次** |
| 6 | `step/start` | 打开步骤 |
| 7 | **`agent/request`** | **waterfall**；随后 `prepareCall`（**此时取消，system 与 user 都不提交**） |
| 8 | — | 用已准备的调用能力调和 `system/message` |
| 9 | — | 把 enter 的消息追加为 `user/message`；按需记 `request/header` 与 `request/context` |
| 10 | — | **从日志派生并冻结模型历史** |
| 11 | **`llm/stream`** | **waterfall**：流式绑定调用 |
| 12 | `agent/assistant-stream` | start / chunk* / end（**进程本地**，非持久） |
| 13 | `assistant/message` 或 `assistant/attempt` | 提交结算 |
| 14 | **`tool/call*` → `tools/pre-execute` → `tools/execute` → `tools/post-execute` → `tool/result*`** | **三个 `tools/*` 都是 waterfall** |
| 15 | `step/end` | 结束步骤 |
| 16 | — | 工具欠了下一次请求，或来了新的下一步输入 ⇒ 回到第 2 步 |
| 17 | **`agent/turn-stopping`** | **serial，无 `next()`** |
| 18 | `turn/end` | 关闭轮次 |

**这张表最值得注意的不是"有多少步"，而是第 11 与第 13 行之间那段**（`docs/architecture.zh.md:113`）：

> `agent/assistant-stream` 发布**进程本地** start、**瞬态** chunk 与 end frame。loop 会在 committed end frame 前把**完整紧凑 stream** 提交为一个 message 或仅日志 attempt；Web Session-follow adapter 是该 live event **唯一的远程消费方**。

⇒ **"给 UI 看的逐字流"和"落进日志的证据"是两样东西**：前者是进程本地的瞬态事件（可以不持久、可以丢），后者是**紧凑、完整、带时间**的持久对象。**这条分离直接解释了"为什么刷新页面后历史还在、但逐字动画不会重放"。**

### 2.3 三个事件域：选对域是"大多数改动的第一个决定"

事件不是平的。`docs/architecture.zh.md:76-80` 把扩展点分成三域，并给出判据：

| 事件域 | 形态 | 什么时候用（原文判据） |
|---|---|---|
| **会话事件** | 追加到日志、通过 `session/event` 广播的**持久事实** | "当某个事实**必须在重新加载后仍然存在**时" |
| **Agent 事件**（`agent/*`） | 携带活跃 `Agent`：inbox、步骤、状态、请求、验证、续跑 | "要**观察或拦截进行中的工作**时" |
| **能力事件** | 无需导入循环即可向某个 seam（`fs/*`、`tools/*`、`telemetry/*`）附加策略与适配器 | 给某项能力挂策略/适配器 |

**★ 判据句（本讲第二个架构判据）**：

> **"这个判断要不要活过重启？"——这是选域的第一问，也是唯一一问。** 要活过重启 ⇒ 会话事件（它会进日志，因此也要受第 51 讲"模型可见即已记录"的约束）。只是**当下这一次运行的行为**（拦截、观察、统计）⇒ `agent/*` 或能力事件。
>
> **把一个临时策略写成会话事件，等于往日志里灌噪音**；反过来，**把一个必须持久的事实只做成实时事件，重启就丢**。`dsh` 把这条判据写在文档里的方式很值得学：**它不是列一张"哪些事件属于哪域"的清单让你背，而是给你一句可以自己判的话。**

### 2.4 waterfall 与 `next()`：三个"能改行为"的拦截点

`docs/architecture.zh.md:113` 明确了哪些是 waterfall：

> `agent/pre-step`、`agent/request`、`llm/stream` 和三个 `tools/*` 事件是 **waterfall**（瀑布式事件），其监听器**必须调用 `next()` 才能委托下去**；`agent/turn-stopping` 是 **serial** 事件，**没有 `next()`**。

（另有一个 `agent/request-error` 也是 waterfall，见 `docs/subsystems/core.zh.md:987`；全仓 waterfall 事件在 `docs/event-producer-consumer.zh.md` 里逐条标注，共 17 个已声明事件，另有 2 个未进声明表。）

`next()` 的语义在第 50 讲已给（`docs/cordis-primer.zh.md:35`）：调用它才执行下游，**不调用则短路**。这里补两个**具体到 `agent/*` 的语义**：

- `agent/pre-step`（`docs/subsystems/core.zh.md:934`）：*"Reject a proposed step or replace the messages that enter it. Calling `next()` **preserves the current messages**."*
- `agent/request`（`docs/subsystems/core.zh.md:959`）：在步骤被准入之后、`step/start` 之后运行，**在提交系统提示词与已接纳用户批次之前**；"在这里或随后的 `prepareCall()` 解析期间取消，**两者都不提交**"。

### 2.5 反差点：为什么 `agent/turn-stopping` 不给 `next()`

现在讲这一讲**最想让你带走的那处设计**。

`agent/turn-stopping` 是"轮次即将关闭"的时刻：模型不欠任何回复（没有活着的工具调用、没有新的 steering）。它**在边界提交之前被 await**。而它的形态是 **serial + 无 `next()`**（`docs/subsystems/core.zh.md:1041` 原文）：

> 轮次即将关闭：模型不欠响应（没有活跃工具调用、没有新的 steering）。在边界提交之前被 await——**有异议的监听器去「steering」（`agent.steer(...)`），然后状态机重读它的 inbox**：有新的 steering 就再跑一步，没有就关闭轮次。**Data decides, so listener order cannot change the outcome.**

（签名同样没有 `next` 参数：`'agent/turn-stopping'(...): Promise<void> | void`，`docs/subsystems/core.zh.md:1061`。同页还补了一处对称的设计：**携带 `concludesTurn` 的工具结果在该步骤结束轮次**，且"结论绝不短路已提交的下一步工作"。）

**★ 判据句（本讲第三个架构判据，也是本讲的核心）**：

> **选 waterfall 还是 serial，看的不是"要不要拦截"，而是"顺序会不会改变结论"。**
>
> - 在 `agent/pre-step` 里，A 先放行、B 后拒绝，和 B 先拒绝、A 后放行——**结果不同**。所以它必须是 waterfall：**后挂的能包住先挂的**，语义由嵌套决定。
> - 在 `agent/turn-stopping` 里，监听器**不能直接改结论**，只能**投递事实**（`steer()` 往 inbox 放一条），然后由状态机**重新读数据**得出结果。**顺序就改不了结论** ⇒ 做成 serial，且**不给 `next()`**。
>
> **这条设计的深意是：把"表达异议"从控制流变成数据。** 一旦异议是数据，它就可以被记录、被回放、被测试；一旦它是"谁先返回谁说了算"，它就只能被调试。**这就是"数据决定"优于"顺序决定"的地方。**
>
> **迁移到你自己的系统**：你加"能不能结束/能不能通过"这类钩子时，**优先让它投递事实而不是返回裁决**。返裁决的钩子一多，你最终会陷入"到底谁先执行"的问题——**而那个问题没有正确答案，只有约定。**

### 2.6 前沿深挖一：取消语义——"在哪个阶段取消"决定"提交什么"

Agent 的取消不是"停下"这么简单：**在哪个时刻取消，决定了日志里留下什么。** `dsh` 的做法很精确（`docs/agent-lifecycle.zh.md:39` 时序图注释）：

> *"cancellation during either async phase commits neither system nor users"*

中文版（`docs/architecture.zh.md:117`）：

> 组装与 `step/start` 之后，`agent/request` 和 `prepareCall()` 先解析**实际路由**，再提交系统提示词与已接纳用户消息；**在任一异步阶段取消都不会提交这两者。**

**注意这里的诚实**：系统提示词与用户消息是**成对提交或成对不提交**的。为什么？因为它们是一次"请求封装"的两半——**只提交一半，日志里就会留下一个"有提示词没输入"（或反之）的畸形请求**，而第 51 讲的不变式要求它可重建。

还有一个和"路由"有关的细节（`:117`）：

> **提示词准入依据已准备调用的能力，而非先前的 `request/context`。**

这句话的意思是：**在决定"这段提示词能不能进去"时，用的是"这次实际会走哪条路由"的能力**，而不是上一次记下来的快照。⇒ **防止"上一轮能用工具 A，这一轮路由换了却还按上一轮放行"** 这一类不一致。

### 2.7 前沿深挖二：`startsRequestSeries` 与"包装监听器必须显式保留声明"

这一节讲一个**非常容易静默出错**的机制。

缓存前缀（prompt cache）要生效，模型侧需要知道"这是一段新序列的起点"还是"延续上一段"。`dsh` 把这件事表达成一个**决策字段**（`docs/architecture.zh.md:117` 原文）：

> enter 决策可设置 `startsRequestSeries`：循环记录新的 `request/header`（原因为 `series`，或在封装同时变化时为携带 `startsSeries: true` 的 `change`）。

而风险在于：**一个包装型监听器**（自己改了点东西，再把下游的结论透传出去）如果**重建**了 decision 对象，就很容易把这个字段**丢掉**。原文给的写法只有一行（同段）：

> **包装监听器通过 `{ ...decision, messages }` 保留该声明。**

`docs/agent-lifecycle.zh.md:89` 从另一角度确认了这条纪律：

> 以返回的 `agent/pre-step` 决策为准；通过包装 `next()` 的监听器会**保留下游消息与 `startsRequestSeries`**，除非有意替换。

**★ 判据句（本讲第四个架构判据）**：

> **"透传"必须写成一个展开运算符，而不是写成"我知道要带哪些字段"。** 前者的语义是"**保留我没想到的一切**"，后者是"**我列出来的才保留**"。对一个还在演进的决策对象，只有前者是安全的。
>
> **丢失的后果是静默的**：缓存前缀失效 ⇒ 成本上升、延迟上升，**但没有任何一处报错**。这类"不报错但变贵/变慢"的失效，正是第 50、51 讲反复出现的同一族——**它不会让你失败，只会让你慢慢不明白为什么变贵了。**

### 2.8 前沿深挖三：工具执行流水线——20 个节点、13 行归并里"策略"与"实现"的分工

最后补一块：工具调用那条路。`docs/tool-execution-pipeline.zh.md` 把一次工具执行拆成一条**固定顺序**的流水线（`:10-63` 是它的 mermaid 原文，`:8` 是叙述版）。**下面这张表是我按"谁能插进来"把图中 20 个节点归并成的 13 行，节点本身多于行数。** 按阶段列出来，并标注**谁有权插进去**：

| # | 阶段 | 谁能插进来 |
|---|---|---|
| 1 | `tool/call`（执行前记录） | 会话事件，只记 |
| 2 | **`tools/pre-execute`** | **hooks / permission / sandbox** |
| 3 | 已注册**单调守卫** | 注册表 |
| 4 | `ctx.approval` 一次性询问 | 审批 seam |
| 5 | **`tools/execute`** | **timeout / retry / metrics** |
| 6 | 工具自身 `execute()` 主体 | 工具实现 |
| 7 | `fs/write-intent` 或 `fs/edit-intent`（仅 tool-fs 变更） | 文件策略 |
| 8 | 工具自有的会话事件 | 工具实现 |
| 9 | `ToolDefinition.projectContent` | 工具定义 |
| 10 | **`tools/post-execute`** | **后置策略** |
| 11 | 注册表外层规范化 | 注册表 |
| 12 | `ToolDefinition.finalizeContent` | 工具定义 |
| 13 | `tools/result`（同步通知）→ `tool/result`（会话事件） | UI 卡片 / 日志 |

**这张表的分工很清楚**：`tools/pre-execute` 先跑，随后是**单调守卫**，然后 `tools/execute` 与 `tools/post-execute`；而**由定义自身控制的** `projectContent` 在"执行后策略"**之前**安装已准备内容，`finalizeContent` 与 `tools/result` 在**其后**运行（`:8`）。

还有一条关于**结果不变式**的实现细节（`:65`）：注册表会对候选结果做**无损快照**；快照失败先规范化失败，再由快照固定的 `finalizeContent` 强制执行"**同步、仅限内容**"的不变式；随后 `tools/result` 观察到的才是"不可变、可无损 JSON 表示"的结果。

**★ 判据句（本讲第五个架构判据）**：

> **"策略"与"实现"要能分别插，而且插的位置要固定。** 超时、重试、指标在**执行前**（第 5 阶段）；审批、沙箱在**更前**（第 2 阶段）；内容规范化在**执行后**（第 10–12 阶段）。**把顺序写进文档并让它可核对**，你才能回答"为什么我这里的审批没拦住那个工具"——**答案是它挂在了第 10 阶段，而工具在第 6 阶段已经跑了。**

### 2.9 先画出来：一个轮次的完整阶段流

第 2.2 节那张 18 行表信息很密，但它是一张**表**，看不出"哪个节点是拦截点、哪个是流水线内嵌的"。这里把它画成流程图，并在主线上把**四个拦截点**与**轮次收尾**标出来。

真正的轮次主循环不在 `packages/core/agent-loop/src/index.ts`（那是 `AgentLoop` 工厂，`:330 class AgentLoop extends Service`），而在 `packages/core/agent-loop/src/agent.ts` 的 `ReactLoopAgent`：`kick()` 跑 `while (await this.turn()) {}`（`agent.ts:254`），`turn()` 在 `:296-379`，`step()` 在 `:381-527`。

<img class="mermaid-svg" src="/zh/book-assets/diag-0124.svg" alt="真正的轮次主循环不在 `packages/core/agent-loop/src/index.ts`（那是 `AgentLoop` 工厂，`:330 class AgentLoop extends Service`），而在 `packages/core/agent-loop/src/agent.ts` 的 `ReactLoopAgent`：`kick()` 跑 `while (await this.turn()) {}`（`agent.ts:254`），`turn()` 在 `:296-379`，`step()` 在 `:381-527`。" />

> **图 52-1**　一个轮次的完整阶段流：主线上四个 waterfall 拦截点（`agent/pre-step` / `agent/request` / `llm/stream` / `tools/*`）都用高亮色标出，轮次收尾 `agent/turn-stopping` 是 **serial、无 `next()`**；`agent/pre-step` 若"拒绝或首次 enter 被改写为空"，直接关闭一个**不含步骤的轮次**。

代码级细节（`packages/core/agent-loop/src/agent.ts`）——驱动入口与 `turn()`：

```ts
  private async kick(): Promise<void> {
    try {
      while (await this.turn()) {}
```

`pre-step` 的 waterfall 与它的默认 `next()`（`agent.ts:276-282`）：

```ts
    const decision = await this.dispatch.waterfall(
      'agent/pre-step', { messages: claimed, ...position, signal },
      (): Promise<PreStepDecision> => Promise.resolve<PreStepDecision>({
        kind: 'enter',
        messages: context === undefined ? claimed : [...claimed, context],
      }),
    )
```

`PreStepDecision` 只有两种形态（`packages/core/agent/src/runtime-types.ts:111-119`）：

```ts
/** Whether and with which messages the loop enters a proposed step. */
export type PreStepDecision =
  | { kind: 'reject' }
  | {
    kind: 'enter'
    messages: UserMessage[]
    /** Start a distinct model-message series before this step's admitted messages. */
    startsRequestSeries?: true
  }
```

`step()` 里 `agent/request` waterfall 的调用点（`agent.ts:559-562`）：

```ts
    const proposedConfig = await this.dispatch.waterfall(
      'agent/request', { turn, step, signal },
      () => Promise.resolve(seedConfig),
    )
```

轮次收尾（`agent.ts:343`）——注意这里是 `serial`，且没有 `next` 参数：

```ts
          await this.dispatch.serial('agent/turn-stopping', { turn, signal })
```

### 2.10 三张机制图：流水线四段、waterfall 对照、取消成对提交

**第一张：工具执行流水线。** `docs/tool-execution-pipeline.zh.md:10-63` 的 mermaid 共 **20 个具名节点**（`model`、`toolCall`、`presentCall`、`pre`、`guards`、`denied`、`approval`、`around`、`toolBody`、`fsGate`、`owned`、`project`、`post`、`normalized`、`finalize`、`final`、`context`、`toolResult`、`allResults`、`presentResult`）。按"策略 / 实现 / 规范化 / 通知"四段归置如下：

<img class="mermaid-svg" src="/zh/book-assets/diag-0125.svg" alt="第一张：工具执行流水线。 `docs/tool-execution-pipeline.zh.md:10-63` 的 mermaid 共 20 个具名节点（`model`、`toolCall`、`presentCall`、`pre`、`guards`、`denied`、`approval`、`around`、`toolBody`、`fsGate`、`owned`、`project`、`post`、`normalized`、`finalize`、`final`、`context`、`toolResult`、`allResults`、`presentResult`）。按&quot;策略 / 实现 / 规范化 / 通知&quot;四段归置如下：" />

> **图 52-2**　工具执行流水线的四段分区：`docs/tool-execution-pipeline.zh.md:10-63` 的 mermaid 共 20 个节点，按"策略段 4 / 实现段 4 / 规范化段 6 / 通知段 3"归置，入口 3 节点另计——**20 = 3 + 4 + 4 + 6 + 3**。第 2.8 节那张 13 行表是本图的**归并**，不是文档原文的阶段数。

流水线的"策略后段"由两个函数串起（`packages/core/tools/src/index.ts:1641-1659`、`1669-1692`）：

```ts
  private async finalizeScheduledExecution(exec: ToolRunContext, result: ToolExecutionResult): Promise<ToolExecutionResult> {
    try {
      const project = this.contentProjectors.get(exec)
      this.contentProjectors.delete(exec)
      const content = project?.(exec, result)
      const projected = content === undefined
        ? result
        : this.markCanonical(exec, this.materializeFinalResult({ ...result, content }))
      const postResult = await this.postExecute(exec, projected)
      return this.finishScheduledExecution(
        exec,
        this.callerCancelled(exec) && !postResult.isError
          ? this.cancellationResult(exec, postResult)
          : postResult,
      )
    } catch (error: unknown) {
      return this.finishScheduledExecution(exec, toolErrorResult(error))
    }
  }
```

```ts
  private finishScheduledExecution(exec: ToolRunContext, result: ToolExecutionResult): ToolExecutionResult {
    let materializedResult: ToolExecutionResult
    try {
      materializedResult = this.materializeFinalResult(result)
    } catch (error: unknown) {
      materializedResult = this.materializeFinalResult(toolErrorResult(error))
    }
    let finalResult: ToolExecutionResult
    try {
      finalResult = this.materializeFinalResult(this.applyFinalContent(exec, materializedResult))
    } catch (error: unknown) {
      finalResult = this.materializeFinalResult(toolErrorResult(error))
    }
    this.notifyResult(exec, finalResult)
    return finalResult
  }
```

而三个 `tools/*` waterfall 的声明就写在工具注册表自己的事件表里（`tools/src/index.ts:143-208`，`pre-execute` `:153`、`execute` `:164`、`post-execute` `:176`、`result` `:198`）：

```ts
    'tools/pre-execute'(this: Scoped<ToolRuntime>, exec: ToolExecution, next: () => Promise<PreToolDecision>): Promise<PreToolDecision>
```

```ts
    'tools/execute'(this: Scoped<ToolRuntime>, exec: ToolDispatchExecution, next: () => Promise<ToolExecutionResult>): Promise<ToolExecutionResult>
```

```ts
    'tools/post-execute'(this: Scoped<ToolRuntime>, exec: ToolExecution, result: Readonly<ToolExecutionResult>, next: () => Promise<PostToolDecision>): Promise<PostToolDecision>
```

```ts
    'tools/result'(this: Scoped<ToolRuntime>, exec: Readonly<ToolExecution>, result: Readonly<ToolExecutionResult>): undefined
```

**第二张：waterfall 与 serial 的对照。**

<img class="mermaid-svg" src="/zh/book-assets/diag-0126.svg" alt="第二张：waterfall 与 serial 的对照。" />

> **图 52-3**　`waterfall` 与 `serial` 的对照：左边 waterfall 靠 `next()` 嵌套，**监听器顺序能改结论**；右边 serial 的监听器**只投递事实**（`agent.steer()`），由状态机重读数据决定，**顺序改不了结论**。这是第 2.5 节那条判据的形状。

`dispatch.ts:119-149` 的真实派发实现——三种模式在这里分道扬镳：

```ts
  return {
    emit(name, payload) {
      // Cordis emit invokes callbacks through Array.map: one synchronous throw
      // starves later listeners, and returned promises are discarded. Agent
      // notifications are non-vetoing, so resolve the same filtered callback
      // set ourselves and contain both failure modes independently.
      const args: unknown[] = [carrier, name, fused(payload)]
      const callbacks = ctx.events.dispatch('emit', args)
      for (const callback of callbacks) {
        try {
          const returned: unknown = callback(...args)
          void Promise.resolve(returned).catch((error: unknown) => {
            ctx.logger.warn(`agent event "${name}" listener rejected: ${String(error)}`)
          })
        } catch (error: unknown) {
          ctx.logger.warn(`agent event "${name}" listener threw: ${String(error)}`)
        }
      }
    },
    async serial(name, payload) {
      // oxlint-disable-next-line typescript/unbound-method -- the events mixin accessor returns a pre-bound function
      const serial = ctx.serial as (thisArg: Scoped<Agent>, name: string, ...args: unknown[]) => Promise<never>
      return await serial(carrier, name, fused(payload))
    },
    waterfall(name, payload, ...rest) {
      // oxlint-disable-next-line typescript/unbound-method -- the events mixin accessor returns a pre-bound function
      const waterfall = ctx.waterfall as (thisArg: Scoped<Agent>, name: string, ...args: unknown[]) => never
      return waterfall(carrier, name, fused(payload), ...rest)
    },
  }
```

**三种模式的区别就写在这段代码的长度里**：`emit` 要自己包一层 try/catch（因为它必须**容错**——一个监听器同步抛错不能饿死后面的监听器，返回的 promise 也被丢弃），`serial` 与 `waterfall` 直接委托给 Cordis 的对应派发器（因为它们的语义——顺序执行 / 环绕嵌套——已由框架保证）。

**第三张：取消语义的"成对提交"。**

<img class="mermaid-svg" src="/zh/book-assets/diag-0127.svg" alt="第三张：取消语义的&quot;成对提交&quot;。" />

> **图 52-4**　取消语义的"成对提交"：在 `agent/request` / `prepareCall()` 的任一异步阶段取消，**system 提示词与 user 消息成对不提交**；同时标出提示词准入依据的是**这次实际路由的能力**，不是上一轮快照。

代码级证据：`agent.ts:387-427` 里"先 `prepareRequest` → 提交 system → 提交 user → 送流"的顺序，正是"成对"的物理依据：

```ts
      const { config, preparedCall } = await this.prepareRequest(turn, step, signal)
      ...
      for (const { message, intent } of commits) {
        this.session.append('system/message', { turn, step, message }, intent)
      }
      if (firstAttempt) {
        for (const message of decision.messages) {
          this.session.append('user/message', message, { surfaceOp: 'append' })
        }
      }
```

而 `turn()` 的 catch 分支把"取消"单独裁出成 `aborted` 原因，写进 `turn/end`（`agent.ts:349-364`）：

```ts
      // A cause is present exactly while the signal is aborted.
      const cause = abortedCancelCause(signal)
      if (cause !== undefined) {
        turnEnds = { kind: 'aborted', reason: cause }
        throw error
      }
```

### 2.11 一手数据：81 条事件主表按分发模式分类

事件主表共 **81 条**记录（`docs/event-producer-consumer.zh.md` 主表行数），按分发模式分：

- **`emit` 59 条** —— 只观察、不改变行为；
- **`waterfall` 17 条** —— 能做环绕中间件，忘调 `next()` 即否决；
- **`parallel` 3 条**；
- **`serial` 2 条** —— 顺序执行、可投递事实。

**这四个数放在一起，结论很清楚：绝大多数事件是 `emit`（只观察）。** 真正"能改行为"的 `waterfall` 只有 17 条，而 `serial` 只有 2 条——**"拦截"是稀缺能力，不是默认可得的。** 这解释了本讲为什么把 `agent/turn-stopping` 的 serial 当成重点：它不是"少数的另一种选择"，而是**整套设计里刻意保留的极少数**。

另外三个和本讲直接相关的数，**口径不同、别混用**：

- 工具执行流水线的 mermaid 是 **20 个节点**（`docs/tool-execution-pipeline.zh.md:10-63`）；同文件的 `:8` 叙述段逐一点名的是 **7 类关注点**（`tools/pre-execute`、单调守卫、`tools/execute`、`tools/post-execute`、`projectContent`、`finalizeContent`、`tools/result`）；本书 2.8 节把它归并成 **13 行**。三个数分别是"节点数 / 文档叙述关注点数 / 本书归并行数"。
- 本讲的两个关键事件 `agent/pre-step`、`agent/turn-stopping` 声明在 `packages/core/agent/src/runtime-types.ts`（`:309-320`、`:364-381`），属 `agent/*` 域——**它们不进日志**（进日志的是 `session/*` 会话事件，第 51 讲那 14 种）。
- 工具执行流水线的四个会话侧配对事件 `tool/call` 与 `tool/result` 由 `packages/core/agent-loop/src/tool-calls.ts:262-290` 写入，**不在 `tools/` 包内**：流水线跑在工具注册表里，而"记录这次调用"是循环侧的事。

### 2.12 搬到你自己系统：把 while 循环的四个拦截点暴露出来

第 2.4 节给了判据，这里补**动手改自己循环**的路径。

| dsh 的做法 | 你自己系统里该问的问题 |
|---|---|
| 四个位置都做成事件：`pre-step` / `request` / `llm/stream` / `tools/*`（`agent.ts:276`、`:559`、`:419`、`:517`） | 你的循环里，这四类位置现在是"事件、`if`、还是不存在"？ |
| 主循环只有一句 `while (await this.turn()) {}`（`agent.ts:254`） | 你的循环主体有多少行？超过一屏，说明策略写进了循环。 |
| 忘调 `next()` 即否决（`dispatch.ts:119-149`） | 你的"观察型"钩子有没有和"拦截型"共用一个挂载点？ |
| 轮次收尾用 serial + `steer()` 投递事实（`agent.ts:343`） | 你的"能不能结束"判断，是返裁决还是投递事实？ |

**落地步骤（五步，每步一个动作）**：

1. **画一遍你系统的循环**，在图上标出四个位置：① 决定能否进入 ② 请求发出前 ③ 流式中 ④ 工具执行前后。
2. **对每个位置写"当前实现"**，只能是三者之一：事件 / `if` / 不存在。
3. **把 `if` 抽成事件**：先加 `emit` 型（只观察），跑一遍确认行为完全不变。
4. **再升级需要"改结论"的那个**：用 `waterfall` 语义，把下游包成 `next()`。
5. **最后改"能不能结束"这类判断**：从"钩子返回布尔"改成"钩子投递一条事实 + 状态机重读数据"。

**★ 判据句**：

> **循环里还剩几个 `if`，就是你还没暴露的拦截点数量。**

## 三、避坑清单

- [ ] **别把循环写成"没有可挂载点"的 while。** 至少要暴露四个点：进入前、请求前、流式中、工具执行前后。
- [ ] **"轮次可以含零个步骤"要能自然表达。** 如果实现里假设"至少一步"，"全部拒绝"就会被逼成异常。
- [ ] **选事件域只问一句：这个判断要活过重启吗？** 要 ⇒ 会话事件；不要 ⇒ `agent/*` 或能力事件。
- [ ] **waterfall 里忘调 `next()` = 否决。** 只做观察就别挂 waterfall。
- [ ] **顺序能改结论 ⇒ waterfall；顺序改不了结论 ⇒ serial。** 更优的形态是**让钩子投递事实、由数据决定结论**（`agent/turn-stopping` 就是范例）。
- [ ] **包装型监听器一律用 `{ ...decision, ... }` 透传**，不要手写字段清单。丢字段的后果是静默变贵。
- [ ] **"在哪一步取消"决定"提交什么"。** 系统提示词与用户消息必须成对提交或成对不提交，否则日志里会出现畸形请求。
- [ ] **提示词准入依据"这次实际走的路由能力"**，不要用上一轮记下的快照。
- [ ] **给 UI 的逐字流与落日志的证据分开**：前者瞬态、进程本地；后者紧凑、完整、持久。
- [ ] **策略的挂载位置要能核对。** 审批挂错了阶段（比如挂在后置），你会得到"审批通过了但工具已经跑完"这种最尴尬的结果。
- [ ] **把"13 行归并"与"20 个节点"分清。** 文档图是 20 个节点、`:8` 叙述 7 类关注点，13 行是本书的归并——口径混用，你会先在"到底几个阶段"上吵起来。
- [ ] **"流水线有几个阶段"这种数，要写清是哪一层的数。** 节点数、文档叙述的关注点数、你归并的行数，是三个不同的量。
- [ ] **别只数"能拦的点"，要数"只观察的点"。** 全仓 81 条事件里 59 条是 `emit`——观察是常态，拦截是稀缺。

## 四、动手任务

> **目标**：把"拦截点"装到你自己的循环上，并亲手验证一次"提交或都不提交"。

**步骤 1：给你自己的循环标出四个拦截点**

画出你系统里那个 agent 循环，在图上标出四类位置：**① 决定这一步能不能进入 ② 请求发出前（还能改路由）③ 流式过程中 ④ 工具执行前/后**。**任务**：对每个位置写出"当前是怎么实现的"——是事件、是 if、还是根本不存在。

**步骤 2：做一个"只观察不拦"的监听器，并证明它没吞掉下游**

在 `agent/pre-step` 等价的位置挂一个**只记日志**的监听器。**任务**：验证系统行为完全不变（下游照常跑到）。**如果你的实现在这一步就卡住了，说明你把"观察"和"拦截"用了同一个挂载点。**

**步骤 3：写一个"投递事实而非返回裁决"的钩子**

找一个"能不能结束 / 能不能通过"的判断点，把它从"钩子返回布尔"改成"**钩子投递一条事实 + 状态机读数据重算**"。**任务**：说明改完之后，**为什么这个钩子的执行顺序不再重要**。这一条是本节最有复用价值的改写。

**步骤 4：验证一次"成对提交"**

在"请求发出前"的位置人为取消一次。**任务**：检查日志里是否留下了**孤立的系统提示词**或**孤立的用户消息**。若有，你缺的正是这条"两半都不提交"的纪律。

**步骤 5：数一遍你自己系统的事件，按分发模式分类**

照 2.11 节的口径，把你系统里的事件按"只观察 / 能改结论 / 顺序执行"分三类，各数一遍。**任务**：如果你的"能改结论"那一类占比远高于 `dsh` 的 17/81，说明你把太多"观察"做成了"拦截"——那正是"谁先执行"这类无解问题的来源。

---

## 本讲小结

1. **step = 一次模型请求 + 它调起的工具；turn = 含零或多个 step 的排空过程。** "零个步骤"是设计重点——"全部拒绝"必须是能被自然表达的正常结论。
2. **一个轮次暴露了四个拦截点**：`agent/pre-step`（能否进入）、`agent/request`（提交前可取消）、`llm/stream`（流式可包装）、三个 `tools/*`（执行前后）。**四个都是 waterfall，忘调 `next()` 就等于否决。**
3. **选事件域只问一句：这个判断要活过重启吗？** 要 ⇒ 会话事件；不要 ⇒ `agent/*` 或能力事件。
4. **`agent/turn-stopping` 是 serial、没有 `next()`，理由是"数据决定，因此监听器顺序改不了结论"**——它把"表达异议"从**控制流**变成了**数据**（`agent.steer()`）。**这是本讲最值得搬走的一条设计。**
5. **取消语义是"成对提交或成对不提交"**：在 `agent/request` / `prepareCall` 的任一异步阶段取消，system 与 user 消息都不进日志。提示词准入依据**这次实际路由的能力**，不是上一轮快照。
6. **`startsRequestSeries` 这类声明必须用 `{ ...decision, ... }` 透传。** 手写字段清单会静默丢掉它，后果是缓存前缀失效——不报错，只是变贵。
7. **工具执行是一条固定顺序的流水线（文档图 20 节点，本书归并为 13 行）**：策略（审批 / 沙箱）在前、实现（`execute()`）在中、规范化（`projectContent` / `finalizeContent`）在后。**"审批没拦住"的答案，往往是它挂错了阶段。**

下一讲打开这一模块**最"架构"的一讲**：**Capability Seam**。上一讲的 `ctx.llm`、`ctx.tools`、`ctx.fs` 都是同一类东西——但"三个角色的可替换能力"和"一个接口一份实现"**不是一回事**。我们要回答：**为什么"只有一个 provider"的接口，会因为"只有一个"而彻底失去可替换性。**
