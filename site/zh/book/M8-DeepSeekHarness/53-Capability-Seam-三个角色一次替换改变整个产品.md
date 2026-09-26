# 第 53 讲 · Capability Seam：三个角色，一次替换改变整个产品

> 🎯 导读问题：**"我写了 `interface` + 一个实现类，这算不算 capability seam？"** ——能答出"**不算**。seam 是**三个角色**（Service Definition / Service Provider / Consumer）**齐全**的一项能力，**单一角色本身不是 seam**"——这一句就把"面向接口编程"和"可替换能力"分开了。**本讲要建立的判据是：只留一个 provider 的接口，会因为"只有一个"而退化成内部实现——那时你改不动它，不是因为代码写得差，而是因为从来没人验证过它能被替换。**

> **模块八 · 第五讲**：前面几讲反复出现一个词——`ctx.llm`、`ctx.tools`、`ctx.fs`、`ctx.subagents`。它们不是普通服务，而是**capability seam**。这一讲把"seam"从一个名词变成一套**可判定的角色清单**，然后给你一整套"新行为该放哪"的映射表。
>
> **对研究员的落点**：你要换一个模型、换一个执行后端（本地 / 容器 / 远程沙箱）、换一个子 agent 的实现——这些都**不该改消费方**。这一讲的判据，就是"你的系统能不能做到这一点"的验收标准。

<img class="mermaid-svg" src="/zh/book-assets/diag-0128.svg" alt="对研究员的落点：你要换一个模型、换一个执行后端（本地 / 容器 / 远程沙箱）、换一个子 agent 的实现——这些都不该改消费方。这一讲的判据，就是&quot;你的系统能不能做到这一点&quot;的验收标准。" />

> **图 53-0**　本讲地图：seam = 三个角色齐全的一项**可替换能力**。三个角色缺一，它就不是 seam。而 seam 的价值主张只有一句：**替换一个 provider，就想改变整个产品行为**——文件系统与进程提供方共享执行世界，所以把它们指向远程沙箱，Bash / PTY / LSP 会**一起**搬过去。

## 一、痛点

先看一段"看起来完全正确"的 Java：

```java
public interface Storage { void put(String k, byte[] v); byte[] get(String k); }

@Component
public class LocalStorage implements Storage { /* 本地文件 */ }

@Service
public class ReportService {
    private final Storage storage;   // 构造注入
    ...
}
```

这段代码**符合几乎所有"面向接口编程"的教学**：有接口、有注入、有实现分离。但请诚实回答三个问题：

1. **你现在能把它换成对象存储吗？** ——能，**只要 `LocalStorage` 里没有任何"只有本地文件才有"的语义**。而实际上，`get()` 返回 `byte[]` 就已经把"可以流式读大对象"这条路砍掉了。
2. **你写过第二个实现吗？** ——如果没有，那么**这个接口的边界从来没被验证过**。它大概率只在"本地文件"的形状下自洽。
3. **谁来消费它？** ——`ReportService`。注意：**只有它一个**。那么接口的每个方法签名，实际上都是"照着 `ReportService` 的需要"长出来的——**它已经不是接口，而是 `ReportService` 的内部实现被提到外面了**。

> **这就是本讲的坑：接口的存在不代表可替换，只代表"你把它写成了两半"。** 真正决定可替换性的，是**有没有第二份实现来验证边界**，以及**消费方是否只认契约、不认实现**。

`dsh` 把这件事制度化成一个三角色概念：**capability seam**。下面看它的原文定义。

## 二、原理

### 2.1 三角色的原文定义（这条建议逐字读）

术语表（`docs/glossary.zh.md:9`）的原文：

> **seam**：一种包含三种角色的*可替换能力*：**Service Definition**（拥有自身 `ctx.<key>` 和词汇类型的 Cordis `Service`——可以是 `ShellExecutor` 这样的抽象类，也可以是 `WebRuntime` 这样的具体注册表，**绝不是 TypeScript `interface`**）、一个或多个 **Service Provider**，以及一个或多个注入该服务的 **Consumer**。`packages/shell` 是规范范例：`dsh-shell`（Service Definition）、`dsh-bash-local` / `dsh-bash-sandbox`（提供方），以及 `dsh-tool-bash`（Consumer）。角色需要独立演进时通常位于不同包，但属于同一关注点时，一个包也可以承担多个角色（`dsh-user-approval` 在同一个包中承担 approval seam 的 Service Definition 与其具体实现）。**seam 是完整能力，绝不是其中一个角色**；该术语仅保留此义，能力成员应按其角色、类、服务、约定或扩展点命名。

这段定义里有**四个**值得单独抠出来的点：

**点一：Service Definition "绝不是 TypeScript `interface`"。** 为什么？因为 Definition 要**拥有一个 `ctx.<key>`**——它得能**被注册、被查找到、被卸载**。一个纯类型声明做不到这三件事。原文给的两个形态是：**`ShellExecutor` 这样的抽象类**，或 **`WebRuntime` 这样的具体注册表**。⇒ **Definition 是一个"活的"对象，不是一张类型图纸。**

**点二：三个角色可以落在不同包，也可以合并。** "角色需要独立演进时通常位于不同包"；但 "**属于同一关注点时**，一个包也可以承担多个角色"（例子：`dsh-user-approval` 同时在同一个包里做 Definition 和实现）。⇒ **"分不分包"不是判据，"角色是否独立演进"才是。**

**点三（最重要）："seam 是完整能力，绝不是其中一个角色"。** 架构文档的同一条说得更直接（`docs/architecture.zh.md:135`）：

> 一个包可以合并承担多个角色，但**单一角色本身不是 seam**；添加一项能力意味着**把三者一并设计**。

约定级出处（根 `AGENTS.md:138`）把它写成了硬规则：

> **A capability seam comprises Service Definition / Service Provider / Consumer roles.** It is complete, never one role; split only when roles evolve independently

**点四**：术语有排他性——"该术语仅保留此义，能力成员应按其角色、类、服务、约定或扩展点命名"。⇒ **不要叫一个 `interface` 为 "seam"**，那是误用，会让讨论失去精度。

**★ 判据句（本讲第一个架构判据）**：

> **"可替换"的验收标准不是"我抽了接口"，而是"我换过一个 provider，且消费方一行没改"。** 抽接口是**必要条件**（消费方得只认契约），**不是充分条件**。充分条件要靠**第二份实现**来证明——它会把所有"接口其实只为第一份实现量身定做"的地方**逼出来**。
>
> **实操建议**：设计 seam 时，**先写第二份 provider**（哪怕是个 stub、哪怕只在测试里），再回头定 Definition。**顺序反过来，你会得到一个假装通用的接口。**

### 2.2 规范范例：`ctx.shell` 的三角色与"换一个 provider 意味着什么"

`dsh` 用 `packages/shell` 做教材级范例。`docs/capability-seams.zh.md:637` 那一行的信息很密：

| 角色 | 具体是谁 |
|---|---|
| **Service Definition** | `shell` —— 它拥有 `ctx.shell` |
| **实现（Provider）** | `bash-local`、`bash-sandbox`、`pwsh-local` |
| **直接消费方（Consumer）** | `tool-bash`、`tool-pwsh`、`hooks-claude-code`、`hooks-codex` |
| **这张表给的判据**（原文） | "沙箱、远程或 PowerShell 执行器**可以替换 bash-local，而无需改动这些消费方**" |

**"无需改动这些消费方"** 是整段文字的落点。注意消费方里有什么：**两个面向模型的工具 + 两个"别人的 hook 适配器"**（`hooks-claude-code`、`hooks-codex`）。⇒ **替换一个 shell provider，同时作用于"模型能调的能力"和"外部产品钩子的执行方式"**——这才是 seam 的价值量级：**一次替换，多处行为同时改变。**

对照 2.1 节的痛点：**如果你只有 `bash-local` 一个 provider，`ctx.shell` 的方法签名就会慢慢长成"bash 的形状"**（比如某个方法偷偷假设了 `bash` 的退出码约定）。等你要接沙箱时，你会发现改的是**消费方**，不是 provider。**这就是"单一角色不是 seam"在工程上的具体长相。**

### 2.3 最漂亮的一条：为什么"换一次 provider"能同时搬走 Bash、PTY 和 LSP

这是本讲**最值得记住的一处设计**。`docs/architecture.zh.md:137` 原文：

> seam 正是**替换一个提供方就能改变整个产品**的原因。文件系统与进程提供方**共享同一个执行世界**，因此把它们指向远程沙箱，也就把 **Bash、PTY 和 LSP** 一并搬了过去，**无需提供方专用 fork**。subagent 提供方在同一个接口之后同样千差万别，从新建一个子 agent，到把一个轮次委派给另一个产品。

原文说的"共享执行世界"，在能力图里能直接看到：`ctx.subprocess` 的消费方包括（`docs/capability-seams.zh.md:636`）：`bash-local`、`bash-sandbox`、`terminal-bash`、`lsp-stdio`、`subagent-acp`、`subagent-codex`、`subagent-claude-code`。

⇒ **Bash 执行器、PTY shell 后端、LSP Host、以及三个"进程外 subagent 后端"，全部通过同一个 `ctx.subprocess` 执行 spawn。** 而 `ctx.sandbox` 的消费方正是 `bash-sandbox`、`terminal-bash`——"与配套子进程提供方**共享执行环境**的后端，按每次调用的策略包装该 argv"（`:640`）。

**★ 判据句（本讲第二个架构判据，也是"seam 值不值"的量尺）**：

> **一个好的 seam 划分，会让"换一处"产生"一片行为同时改变"的效果——而且**不是**靠分支判断实现的。**
>
> `dsh` 这里没有任何 `if (remote) { ... }`：**它只是把 `ctx.fs` 与 `ctx.subprocess` 的 provider 一起换成了沙箱/远程实现**，于是所有通过这两个 seam 干活的东西（bash、PTY、LSP、以及走外部产品的 subagent）**自动跟着搬走了**。
>
> **反过来，这也是一条 seam 划分的检验法**：如果"支持远程执行"这件事，需要你在 5 个消费方里各加一个 `if`，那说明你的 seam **划错了位置**——你按"功能"分了 seam（bash 一个、pty 一个、lsp 一个），而**它们真正的共同依赖是"进程在哪执行"**。**按共同依赖划分，而不是按功能表面划分，是 seam 设计的核心手艺。**

### 2.4 `ctx.subagents`：一个接口背后，差得有多远

上一节末尾那句"subagent 提供方在同一个接口之后同样千差万别"，值得单独看——因为它是 **"同一个 seam 的多个 provider 可以差异极大"** 的极端例子。

`dsh` 的 subagent provider 有**六个兄弟包**（`docs/subsystems/subagent.zh.md:5`）：`dsh-subagent-spawn-in-process`、`dsh-subagent-fork-in-process`、`dsh-subagent-acp`、`dsh-subagent-codex`、`dsh-subagent-claude-code`、`dsh-subagent-dsh-sdk`。而**与 bash 不同**，"同一上下文中可共存多个提供方实现并**按名称注册**"（`:7`）。

差异有多大？它们**表达能力不同**。差异被表达成一组**启动时能力 flag**（`SubagentCapabilities` 的五个布尔位，`:15`）：`agentOptions`、`outputSchema`、`depthLimit`、`toolFilter`、`persona`。

而**最关键的一条纪律在这里**（`:29-35`）：请求依赖提供方不具备的能力时，以 `SubagentError('UNSUPPORTED_CAPABILITY')` **明确拒绝**，**绝不会被接受后静默忽略**——原文的说法是 **"fail loud, no silent degradation"**。

跨传输的具体差异（`:40`）：进程内后端把 `agentOptions` **合并到父 Agent 选项之上**；DSH SDK 后端把**四个 Agent 路由字段**合并到实例默认值之上并在子运行时初始化期间校验；而 **ACP、Codex 与 Claude Code 在启动传输前直接拒绝 `agentOptions`**。

还有一处**用"方法存在与否"当能力发现**的设计（`:15`）：可继续子 agent 由继续执行管理器自行组合，因此由**唯一一个可选方法 `prepareContinuable`** 把关——**方法存在即为能力，以类型收窄作为发现机制**。

**★ 判据句（本讲第三个架构判据）**：

> **多个 provider 表达力不同时，只有两种可接受的行为：「做到」和「明确拒绝」。** 第三种——"接受了，但忽略掉你不支持的那部分"——**必须被禁止**，因为它在观测上和成功一模一样。
>
> 这条判据在模型侧你天天遇到：某个模型不支持 tool calling / 不支持图片，**如果框架"默默把工具定义丢掉"继续跑**，你会得到一个"看起来在跑、但能力被静默降级"的系统。**`fail loud` 是把这类失效从"运行期谜题"变成"启动期错误"的唯一办法。**

⚠️ **一处诚实标注**：用"方法存在与否"做能力发现（`prepareContinuable`）是一个**巧妙的写法，但也有代价**——能力变成"隐式"的，读代码时不容易一眼看出"哪些 provider 支持续跑"。**这是原文的做法，不是我的推荐**；要不要学，取决于你的团队对"隐式契约"的容忍度。

### 2.5 前沿深挖一：把**协作**也做成一个 seam（Agent Teams）

到这里，seam 讲的都是"工具类能力"（shell / fs / subprocess）。`dsh` 把它推到了**协作**上——这才是前沿的地方。

`docs/architecture.zh.md:139` 原文：

> **实验性 Agent Teams** 是 `ctx.agentTeams` 上**公开发布、显式启用**的协作 seam，在可继续 subagent 之上提供**持久 roster、任务板和 mailbox**。

**"显式启用"** 有具体含义（`packages/experimental/agent-team/README.zh.md:28`）：需要时**才把本包加入组合**；它**本身不带工具**，必须与 `@deepseek-ai/dsh-experimental-tool-agent-team` 一起挂载，让模型能创建 teammate、发消息、使用任务板。而且（`:32`）**"团队功能需要持久会话存储才能激活。"**

三块持久状态的实现细节，每一处都能看出"崩溃后要能恢复"这个目标：

| 部件 | 关键设计 | 出处 |
|---|---|---|
| **roster** | 每个 member 从 `provisioning` 开始，**只到达一个终态 roster phase**（`active` 或 `failed`）；**持久身份是 Session id** | `docs/subsystems/agent-team.zh.md:9`、`:13`、`:24` |
| **mailbox** | Lead Session **先存完整 queued message**，**仅当** target 的 pending inbox 条目或已记录用户消息**已持久化后**，才写独立的 acknowledgement；**queued − delivered = 恢复用的 mailbox** | `:28` |
| **共享任务 DAG** | 每条 task event 存**完整快照**；`revision` 是 **compare-and-set** 值、每次变更 +1；`blockedBy` **必须无环**；`writeScopes` 是**提示性路径前缀，不是锁** | `:56`、`:58` |

**回放语义**（`:125`）：`agentTeam` 的 Session 投影把 Root Session 回放成 roster、任务板与 queued-minus-delivered mailbox；记录按 `TeamId` 选取；**普通 fork 继承的 event 保留 ancestor id，绝不进入新 Root 的状态**。

**★ 判据句（本讲第四个架构判据）**：

> **"先记事实、再发确认"是可恢复协作的唯一顺序。** mailbox 那里写得极精确：**消息先落库，ack 之后才写**——于是"我收到过但没确认"和"我确认过"是两种可区分状态，`queued − delivered` 就自然成了**待补投递集合**。如果反过来（先 ack 再落库），崩溃就会丢掉"已经告诉对方我知道"的那条消息——**而丢的正好是"对方以为我收到了"那条。**
>
> 同族的还有 `writeScopes`"**是提示性路径前缀，不是锁**"——它明确区分了"**提示模型别越界**"和"**强制不许越界**"两件事。**把提示当强制，是协作系统里最常见的一类虚假安全感。**

### 2.6 前沿深挖二："新行为该放哪"——一张可以照抄的归类表

`dsh` 把这套东西的**入口**整理成一张表（`docs/architecture.zh.md:145-166`），标题就是本节的判据——"**新行为的归属位置**"，并注明"改动循环本身时，本映射随之更新"。节选最常用的若干行：

| 目标 | 机制 |
|---|---|
| 添加模型提供方 | 在 `ctx.llm` 上注册其适配器 |
| 添加面向模型的能力 | 在 `ctx.tools` 上注册；其 schema 加入提示词组装 |
| 让某个会话拥有不同的能力集合 | 组装一个 agent preset；其中的服务行需要 `isolate` realm |
| 添加 shell 执行 | 注册 `ctx.shell` 后端；本地后端通过 `ctx.subprocess` spawn 进程 |
| 添加用户命令 | 在 `ctx.commands` 上注册；**它无需模型轮次即可分派** |
| 添加文件系统访问或策略 | 注册 `ctx.fs` 提供方，或监听 `fs/*` 事件 |
| 限制所启动的进程 | 使用 `ctx.sandbox` 后端；**消费方在启动进程前包装 argv** |
| **拦截请求、工具或轮次** | 使用相应的 `agent/*` 或 `tools/*` 事件；`agent/turn-stopping` 会停止轮次 |
| 添加模型可见上下文 | 调用 `agent.inject()`；它会落到下一次获准的请求中 |
| 添加持久会话状态 | 扩展 `SessionEventMap`；**从日志渲染和回放** |
| 将注册项限定到单个 agent | 使用该 agent 的 `agent.ctx` |

**★ 判据句（本讲第五个架构判据）**：

> **"扩展点清单"应该是一份可维护的对照表，而不是散落在各处的约定。** 这张表的价值不在"列得全"，而在两件事：① 它把"你想做什么"直接映射到"挂哪里"，**堵住了"随便找个地方改一改"这条路**；② 表头那句"**改动循环本身时，本映射随之更新**"——**它把"架构文档会过期"这个已知问题变成了一个显式的维护动作。**
>
> **实操建议**：给你自己的系统写一张同样的表，并在 PR 模板里加一句"若新增扩展点，请更新该表"。**这张表的行数增长很慢，但它省下的定位时间极多。**

（这张表还连着两个生成物：能力图由 `scripts/gen-doc-graphs.ts` 生成，而 seam 表本身的维护方式原文是"**服务从 Cordis 声明中发现**；接口、实现和消费方角色在 `scripts/gen-doc-graphs.ts` 中分类，并设有**完整性守卫**"，`docs/capability-seams.zh.md:663`。⇒ **连"谁是谁的角色"都是生成出来的**，人只负责分类规则。）

**顺便把本讲的"量级"给出来**：按 `docs/capability-seams.zh.md` 的服务表逐行统计（表头为 `| ctx 键 | 角色 | 所属包 | 实现 | 直接消费方 | 配套插件 | 说明 |`），共 **90 行**，角色分布是 **`seam` 33 · `core` 55 · `service` 1 · `bundle` 1**；33 个 seam 从 `ctx.mcpResources`（`:577`）到 `ctx.lsp`（`:659`）。**"33 个可替换能力"是这一版代码库的实测值**（数法：用正则 `` ^\|\s*`ctx\. `` 抓行，取第二列的 `seam`）。

### 2.7 先画出来：三个角色，缺一不成立

<img class="mermaid-svg" src="/zh/book-assets/diag-0129.svg" alt="### 2.7 先画出来：三个角色，缺一不成立" />

> **图 53-1**　seam 的三个角色：Provider 负责实现、Consumer 负责注入，两者都指向同一个 Definition；只有三者齐备才构成一项可替换能力，任何单一角色本身都不是 seam。

2.1 已经给过定义原文。这里补一件定义里**没写、但代码里必须成立**的事：**Definition 为什么必须是一个"活对象"。**

`docs/cordis-api/service.zh.md:8–14` 是 `vendor/cordis/src/service.ts` 的生成投影，页首那段就是答案：

> 上下文服务的基类。以插件形式加载的子类会将自身注册为 `ctx.<name>`。
>
> 子类在构造函数中调用 `super(ctx, name)`。服务会立即注册，并随所属 fiber 自动移除。

（出处 `docs/cordis-api/service.zh.md:8–14`；其中 `super(ctx, name)` 那句在 `:12`。）

"**立即注册**"和"**随所属 fiber 自动移除**"这两件事，恰好是 `interface` 给不了的。看构造函数本体（`vendor/cordis/src/service.ts`:42、:57）：

```ts
  constructor(protected ctx: Context, name: string) {
    name ??= this.constructor['provide'] as string
    ...
    self.ctx.reflect.provide(name, self, this[symbols.check])
    return self
  }
```

注册动作是 `self.ctx.reflect.provide(name, self, ...)`——**把实例本身挂到 `ctx` 上**。所以你写 `ctx.shell` 时拿到的不是一张类型，而是**这个 seam 的 Definition 实例**。`Service` 还公开一个成员 `name`（`docs/cordis-api/service.zh.md`:18–21）：

```ts
/** The service name this instance is registered under. */
public name!: string
```

再加上 **7 个静态 symbol** 作为行为挂载点：`init`（`:31–34`）、`check`（`:42–45`）、`config`（`:53–56`）、`invoke`（`:64–67`）、`extend`（`:75–78`）、`tracker`（`:86–89`）、`resolveConfig`（`:97–100`）。⇒ **Definition 是一个有注册、有名称、有卸载、有 7 个行为点的对象**——这才配得上"角色"两个字。

⚠️ 顺手纠一处容易引错的地方：**`ctx.provide()` 的签名不在这份文档里。** `docs/cordis-api/service.zh.md` 只收录 `Service` 类与那 7 个静态 symbol；`provide` 的真实签名在 vendor 源码 `vendor/cordis/src/reflect.ts`:44、`:46`：

```ts
    provide<K extends string & keyof this>(name: K, value: undefined | this[K]): () => void
    /** Same as above for service names outside the typed `Context` surface. */
    provide(name: string, value?: any): () => void
```

**★ 判据句**：**判断一个候选 Definition 是不是"活"的，只问三件事——它能不能被查到、能不能被卸载、卸载时会不会连带清理。** 三件事里只要有一件答不上来，它就不是 Definition，只是一张类型图纸；而一张类型图纸撑不起一个 seam。

### 2.8 一次替换、一片搬走：`ctx.shell` 的"一换多搬"

<img class="mermaid-svg" src="/zh/book-assets/diag-0130.svg" alt="### 2.8 一次替换、一片搬走：`ctx.shell` 的&quot;一换多搬&quot;" />

> **图 53-2**　`ctx.shell` 的"一换多搬"：把 provider 从 `bash-local` 换成 `bash-sandbox`，消费方一行不改；真正让 Bash / PTY / LSP 一起搬走的，是"文件系统与进程提供方共享同一个执行世界"这条因果链。

2.2 给过 `docs/capability-seams.zh.md:637` 的三角色对照，这里把**那一行本身**逐字读一遍（这是全书信息最密的一行之一）：

```markdown
| `ctx.shell` | `seam` | [`shell`](../packages/shell/shell) | [`bash-local`](../packages/shell/bash-local), [`bash-sandbox`](../packages/shell/bash-sandbox), [`pwsh-local`](../packages/shell/pwsh-local) | [`tool-bash`](../packages/shell/tool-bash), [`tool-pwsh`](../packages/shell/tool-pwsh), [`hooks-claude-code`](../packages/hooks/hooks-claude-code), [`hooks-codex`](../packages/hooks/hooks-codex) | - | 面向模型的 shell 工具和钩子桥接消费此 seam；沙箱、远程或 PowerShell 执行器可以替换 bash-local，而无需改动这些消费方。 |
```

（出处 `docs/capability-seams.zh.md:637`。）

逐列拆开：**实现 3 个**（`bash-local` / `bash-sandbox` / `pwsh-local`）、**直接消费方 4 个**（`tool-bash` / `tool-pwsh` / `hooks-claude-code` / `hooks-codex`）、**配套插件列是 `-`**。注意最后那一列：**这条 seam 没有配套插件**——它不需要额外挂件，替换只发生在 provider 层。

"一换多搬"的因果链在 2.3 引过（`docs/architecture.zh.md:137`）。这里补上**能被行号验证的依赖证据**：

| 共享点 | 谁依赖它 | 出处 |
|---|---|---|
| `ctx.subprocess` | `bash-local`、`bash-sandbox`、`terminal-bash`、`lsp-stdio`、`subagent-acp`、`subagent-codex`、`subagent-claude-code` | `docs/capability-seams.zh.md:636` |
| `ctx.sandbox` | `bash-sandbox`、`terminal-bash`（与配套子进程提供方共享执行环境，按每次调用策略包装 argv） | `docs/capability-seams.zh.md:640` |

⇒ **Bash 执行器、PTY 后端、LSP Host、以及三个"进程外 subagent 后端"，全都从同一个 `ctx.subprocess` spawn 进程**；而"进程在哪执行"由 `ctx.sandbox`（配合 fs 提供方）决定。所以把执行世界指向远程沙箱时，**搬走的是这一整条链**，不是某一条能力。

**★ 判据句**：**"一换多搬"不是设计目标，是"按共同依赖划分"之后自然掉出来的结果。** 如果换一个 provider 只能改一条能力，说明你按功能表面划了 seam；如果它连带搬走一串能力，说明你划在了共同依赖上。**前者的"精准"其实是坏事——它意味着共享依赖被你藏进了各处消费方。**

### 2.9 一手数据：90 条服务表的四类角色、33 个 seam，与一处口径不一致

<img class="mermaid-svg" src="/zh/book-assets/diag-0131.svg" alt="### 2.9 一手数据：90 条服务表的四类角色、33 个 seam，与一处口径不一致" />

> **图 53-3**　服务全景：左半是 90 条服务表的四类角色分布（core 55 / seam 33 / service 1 / bundle 1），右半是 33 个 seam 中的代表（此处列 11 个，均为表中真实存在的 `seam` 行）。

先把可复算的数给全。按 `docs/capability-seams.zh.md` 服务表（表头 `:570`，数据行 `:572–661`）逐行统计：

| 角色 | 条数 |
|---|---|
| `core` | 55 |
| `seam` | 33 |
| `service` | 1 |
| `bundle` | 1 |
| **合计** | **90** |

（复算方式：解析 `:572–661` 每行第二列取角色值。唯一的 `service` 行是 `ctx.productTelemetry`（`:610`），唯一的 `bundle` 行是 `ctx.agentLoop`（`:632`）——这两行都能在表里逐字核对。）**"33 个 seam"** 的边界同样可读：从 `ctx.mcpResources`（`:577`）到 `ctx.lsp`（`:659`）。

**这里必须把一件容易被误传的事说清楚**：仓库里**没有**"seam 必须 ≥2 个实现"的强制校验。存在的只是一个**分类完备性守卫**——它只检查"每个被发现的 `ctx` 键都被归类了、且没有陈旧条目"，**不看实现数量**（`scripts/gen-doc-graphs.ts:887–898`，调用点 `:901`）：

```ts
function assertServiceRolesComplete(services: readonly ServiceEntry[]): void {
  const discovered = new Set(services.map(service => service.key))
  const classified = new Set(SERVICE_ROLES.map(role => role.key))
  const missing = [...discovered].filter(key => !classified.has(key)).sort()
  const stale = [...classified].filter(key => !discovered.has(key)).sort()
  if (missing.length || stale.length) {
    throw new Error([
      missing.length ? `missing service role classification: ${missing.join(', ')}` : '',
      stale.length ? `stale service role classification: ${stale.join(', ')}` : '',
    ].filter(Boolean).join('; '))
  }
}
```

```ts
interface ServiceRole {
  key: string
  pkg: string
  title: string
  mode: 'core' | 'seam' | 'bundle' | 'service'
  implementations?: string[]
  consumers?: string[]
  companions?: string[]
  note: string
}
```

（出处 `scripts/gen-doc-graphs.ts`:887–898、`:38–47`。）

两点可以由此确认：**① `implementations` 是可选字段**，允许为空——`ctx.authorization` 就是 `implementations: []`（`scripts/gen-doc-graphs.ts:404`，对应表行 `docs/capability-seams.zh.md:609` 的实现列为 `-`）；**② 零实现的 seam 照样通过门禁。** ⇒ **"写第二份 provider"是设计纪律，不是门禁在替你把关。** 谁想把这里引成"CI 强制 seam 必须有多个 provider"，都会被 `ctx.authorization` 这一行直接反驳。

**现在，把 2.5 那处口径不一致如实标出来。**

这里需要如实指出一处 dsh 自身的不一致：服务表把 `ctx.agentTeams` 归为 `core`（`docs/capability-seams.zh.md:649`），而架构文档在讨论 seam 时把协作算作 seam 之一（`docs/architecture.zh.md:139` 称它是"`ctx.agentTeams` 上公开发布、显式启用的协作 seam"）。**读者按'三角色'自己判**——`ctx.agentTeams` 有 Definition（`TeamService extends Service`，注册名 `agentTeams`，`packages/experimental/agent-team/src/index.ts:55–57`、`:77–78`）、有 provider、有 consumer（兄弟包 `dsh-experimental-tool-agent-team`），**形状上是 seam**；但它的角色标注是 `core`。这类"两份文档口径不一致"在真实项目里很常见，**遇到时不要替它圆场，把它标出来**。

**★ 判据句（可迁移）**：**两份文档打架时，信"生成物"，但把打架本身写进正文。** 生成物（服务表）有脚本守着、可复算（`docs/capability-seams.zh.md:663` 写明"服务从 Cordis 声明中发现……并设有完整性守卫"）；叙述性文档（架构散文）没有这个约束。所以**分类以生成物为准，但不要删掉叙述那一侧的说法**——两句话并列写出来，读者才有机会自己判断。**替文档圆场，比文档出错更坏：它把"可被发现的不一致"变成了"查不出来的错误"。**

### 2.10 搬到你自己系统：给一项能力定 seam 前先问的三个问题

<img class="mermaid-svg" src="/zh/book-assets/diag-0132.svg" alt="### 2.10 搬到你自己系统：给一项能力定 seam 前先问的三个问题" />

> **图 53-4**　"新行为该放哪"的归类决策：先问"是不是替换某项能力"（⇒ seam），再问"是不是主干服务"（⇒ core）、"是不是组合点"（⇒ bundle），都不是才落到"独立服务"（⇒ service）——四条分支对应服务表角色列的四个取值（`docs/capability-seams.zh.md:570`）。

图 53-4 给的是**结论怎么落**；真正的动作在归类之前。对照表：

| dsh 的做法 | 你自己系统里该问的问题 |
|---|---|
| Definition 是能注册、能查到、能卸载的 `Service` 子类（`docs/cordis-api/service.zh.md:8–14`） | 你的"接口"有生命周期吗？卸载时会不会连带清理？ |
| Provider 一个或多个，可共存、可按名注册（见 2.4） | 你的第二份 provider 在哪？它逼出了哪些"只为第一份实现定制"的地方？ |
| Consumer 只认契约，替换时零改动（`docs/capability-seams.zh.md:637`） | 换一个 provider，你要动几处消费方？ |
| 按共同依赖划 seam（`:636`、`:640`） | 支持某能力要在 N 处加 `if` 吗？这些 `if` 的共同依赖是什么？ |
| 四类角色由脚本分类 + 完备性守卫（`:663`、`scripts/gen-doc-graphs.ts:887–898`） | 你的分类表谁来守？陈旧条目会不会被静默留下？ |

**定 seam 前先问的三个问题**（按顺序问，一问答不上就停）：

1. **它的 Definition 有没有生命周期？** ——能被查到、能被卸载吗？（没有 ⇒ 这是内部实现，不是 seam。）
2. **它的第二份 provider 在哪？** ——写在测试里的 stub 也算，但必须真的存在。（没有 ⇒ 你从未验证过边界。）
3. **换掉 provider，消费方要不要动？** ——要求的答案是"一行不动"。（要动 ⇒ 先修契约，再谈 seam。）

**落地步骤**：

1. 把 图 53-1 的三个角色做成一张空表，列出你系统里所有"候选能力"，逐行填写。
2. 每个候选能力**先写第二份 provider**（stub / mock 都行），再回头定 Definition。
3. 做一次**零改动替换**：切到第二份 provider，**记录所有被迫修改的地方**——那些就是泄漏出去的实现细节。
4. 用 图 53-4 判断这其实是 seam / core / bundle / service 中的哪一类，**别把 bundle 硬塞进 seam**。
5. 把分类写进一张表，配一个"新键必须被归类、旧键不许留下"的检查（照 `assertServiceRolesComplete` 的形状，`scripts/gen-doc-graphs.ts:887–898`）。

**★ 判据句**：**定 seam 之前先答三问：Definition 有没有生命周期？第二份 provider 在哪？换 provider 要不要动消费方？三问里有一问答不上，你现在没有 seam，只有一个还叫"接口"的内部实现。**

## 三、避坑清单

- [ ] **别把"抽了 interface"当可替换。** 验收标准是"换过一个 provider 且消费方零改动"。
- [ ] **设计 seam 时先写第二份 provider**（哪怕是 stub），再定 Definition。顺序反了会得到一个专为第一份实现定制的"接口"。
- [ ] **Service Definition 必须是能注册、能查到、能卸载的活对象**，不是一个纯类型声明。
- [ ] **"分不分包"不是判据，"角色是否独立演进"才是。** 同一关注点完全可以一个包承担多角色。
- [ ] **按共同依赖划分 seam，不要按功能表面划分。** bash / PTY / LSP 之所以能"一次搬走"，是因为它们共享同一个执行世界。要求你在 5 处加 `if` 的设计，就是划错了。
- [ ] **多个 provider 表达力不同时，只允许"做到"或"明确拒绝"**，禁止"接受但静默忽略"（`fail loud, no silent degradation`）。
- [ ] **"提示性"与"强制性"要在命名上说清楚**（`writeScopes` 是提示前缀，不是锁）。把提示当强制，会给你虚假的安全感。
- [ ] **协作状态要"先记事实、再发确认"**，让 `queued − delivered` 自然成为可恢复的待投递集合。
- [ ] **维护一张"新行为放哪"的对照表**，并在改动循环时同步更新它。没有这张表，"扩展点"只存在于老员工脑子里。
- [ ] **别把术语用宽**：一个 `interface` 不叫 seam。术语一旦失去排他性，讨论就没有精度了。
- [ ] **别把服务表的四类角色（`core` / `seam` / `service` / `bundle`）与 seam 内部的三角色（Definition / Provider / Consumer）混为一谈。** 前者是分类列，后者是能力内部结构。
- [ ] **不要相信"CI 强制 seam 必须有多个 provider"。** 仓库里只有分类完备性守卫（`scripts/gen-doc-graphs.ts:887–898`）；`ctx.authorization` 零实现（`:404`）仍以 `seam` 通过。"写第二份 provider"是设计纪律，门禁不管。
- [ ] **遇到两份文档口径不一致，把它标出来，不要替它圆场。** `ctx.agentTeams` 就是现成例子：服务表里是 `core`（`docs/capability-seams.zh.md:649`），架构散文里却叫"协作 seam"（`docs/architecture.zh.md:139`）。

## 四、动手任务

> **目标**：把"三角色"用在你自己的系统上，并找出一个"划错位置"的 seam。

**步骤 1：给你的 `interface` 做三角色体检**

挑出你系统里**三个**最像"可替换能力"的接口，逐个填下表：

| 你的接口 | Definition 是谁（能被注册/查到/卸载吗） | Provider **有几个**（列名字） | Consumer 是谁（列名字） |
|---|---|---|---|

**验收标准**：Provider 那一列**少于 2 个**的，在下一列标注"**未验证可替换**"。**这就是你系统里"假装通用的接口"清单。**

**步骤 2：做一次"零改动替换"实验**

从上面挑一个你不确定边界的接口，写第二份实现（可以只是 `Map` 内存实现或 mock），**然后把消费方一行不改地切过去跑通**。

**任务**：记录**你被迫修改了哪些地方**。凡是必须改消费方的地方，就是你原来那个"接口"其实**漏掉了实现细节**的证据。

**步骤 3：找一个"按功能表面划分"的 seam，改成"按共同依赖划分"**

用 2.3 节那条检验法自查：**你系统里有没有一件事，需要在 5 个以上消费方各加一个 `if` 才能支持？**（典型例子：支持"远程执行/容器执行"、"多租户隔离"、"审计留痕"。）

**任务**：把那 5 个 `if` 背后的**共同依赖**找出来（它们共同依赖什么？），把它抽成一个 seam。**验收标准**：抽完之后，那 5 处 `if` 变成 0 处。

**步骤 4：写你的一张"新行为该放哪"表**

照 2.6 节那张表的形状，给你自己的系统写至少 10 行。**任务**：写完检查一遍——**表里有没有一行，其"机制"栏你其实答不上来（"不知道挂哪"）？** 那一行就是下一个要补的扩展点。

**步骤 5：给你的"新行为"过一遍归类决策树**

用 图 53-4 的四条分支，为你手上**三件**待做的新行为各选一条——**替换某项能力（⇒ seam）/ 主干服务（⇒ core）/ 组合点（⇒ bundle）/ 独立服务（⇒ service）**。

**验收标准**：每件都必须落到唯一一条分支上；**凡是"想放进 seam、但凑不出第二个 provider"的，退回去**——它现在只是一个内部实现。

---

## 本讲小结

1. **seam = 三个角色齐全的一项可替换能力**：Service Definition（能注册/查到/卸载的**活对象**，不是纯类型）、一个或多个 Service Provider、一个或多个 Consumer。**单一角色本身不是 seam；加一项能力意味着三者一并设计。**
2. **可替换性的验收标准是"换过 provider 且消费方零改动"**，不是"抽了接口"。**先写第二份 provider，再定 Definition。**
3. **按共同依赖划分 seam，而不是按功能表面划分。** `ctx.fs` 与 `ctx.subprocess` 共享"执行世界"，所以把它们指向远程沙箱，**Bash、PTY、LSP 和走外部产品的 subagent 一起搬走**——没有任何 `if (remote)`。
4. **多个 provider 表达力不同时，只允许"做到"或"明确拒绝"**（`SubagentError('UNSUPPORTED_CAPABILITY')`，`fail loud, no silent degradation`）。"接受但静默忽略"必须禁止。
5. **协作也能做成 seam**：`ctx.agentTeams` 在可继续 subagent 之上提供持久 roster / 任务板 / mailbox；其纪律是"**先记事实、再发确认**"，并明确区分"提示性路径前缀"与"锁"。
6. **"新行为该放哪"应该是一张可维护的对照表**，并在改动循环时同步更新。这张表把"随便找地方改一改"这条路堵住了。
7. **实测量级**：这一版 `capability-seams` 服务表共 **90 行**，其中标为 **`seam` 的 33 个**、`core` 55 个；且**角色分类本身由脚本生成并带完整性守卫**——连"谁是谁的角色"都不是手写的。

下一讲把这套东西推到交付层：**同一套内核，怎么变成五种产品形态**（Web / 无服务器一次性运行器 / SDK 服务器 / ACP 服务器 / 刻意极简的 SDK）。我们要回答：**"可替换"如果只发生在 provider 层，够不够？**
