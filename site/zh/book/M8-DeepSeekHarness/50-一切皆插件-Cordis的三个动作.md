# 第 50 讲 · 一切皆插件：Cordis 的三个动作

> 🎯 导读问题：**"一个插件系统，凭什么能撑起一整个产品？"** ——能答出"因为它把**所有扩展都收敛成三个动作**：贡献服务、发类型化事件、装可逆副作用；而这三个动作有一个共同约束——**必须可回收**"——这一句就把"插件化"和"随手加个扩展点"分开了。**本讲要建立的判据是：可组合性不是白拿的，账单记在"注册必须可回收"这一条上。**

> **模块八 · 第二讲**：上一讲说 `dsh` 的每一部分都是插件。这一讲打开底下那层框架——**Cordis**。它是 vendored 进 `dsh` 的插件框架（`docs/cordis-primer.zh.md:5`），也是理解后面五讲的前提：**不先搞清"一个插件能做什么、卸载时会发生什么"，会话日志、agent loop、capability seam 都只是名词。**
>
> **⚠️ 一处措辞订正（我先说清楚）**：`docs/cordis-primer.zh.md:7` 的原文标题是「**五个**核心概念」（插件即 Service、上下文即容器、inject 声明依赖、类型化事件、注册是可逆副作用）。本讲把它们**按"动作"归成三类**（服务 / 类型化事件 / 可逆副作用），另两个（上下文即容器、inject 声明依赖）作为**贯穿三类的基础设施**讲——因为对读源码的人，"能做什么"比"有几条概念"更好用。**归类是我的，条目名是原文的。**

> **⚠️ 事实基准**：本讲所有 `路径:行号` 引用，与第 49 讲讲首的模块级声明同源 —— 取自 **2026-09-26 实读**的 `github.com/deepseek-ai/deepseek-harness`，commit `477b4f4`（标签 `rel/dsh-0.1.7-rc.2`）。**路径是"这一版的事实"，不是永久契约**（`dsh` 官方自称处于开发者预览并预告破坏性变更）。

<img class="mermaid-svg" src="/zh/book-assets/diag-0113.svg" alt="⚠️ 事实基准：本讲所有 `路径:行号` 引用，与第 49 讲讲首的模块级声明同源 —— 取自 2026-09-26 实读的 `github.com/deepseek-ai/deepseek-harness`，commit `477b4f4`（标签 `rel/dsh-0.1.7-rc.2`）。路径是&quot;这一版的事实&quot;，不是永久契约（`dsh` 官方自称处于开发者预览并预告破坏性变更）。" />

> **图 50-0**　本讲地图：插件只做三件事——贡献服务、发类型化事件、装可逆副作用；三者的共同约束是**注册必须可回收**。连带出来的第四条（`inject` 依赖）决定了一件更隐蔽的事：**依赖没人提供时，插件静默等待，而不是报错。**

## 一、痛点

如果你写过 Java，你熟悉这套"扩展"手法：留一个 `interface`，用 Spring 把实现注进来；不够就加一个 `@ConditionalOnProperty`；再不够，`BeanPostProcessor`、反射、甚至改框架源码。

这套手法能跑，但它有一个共同的**账单**：**扩展是"加进去"的，不是"可撤销"的。** 一个 bean 一旦注册进容器，你怎么"临时把它拿掉再观察系统行为"？一个 `BeanPostProcessor` 一旦挂上，你怎么让它在某个 agent 生效、在另一个不生效？

这就是本讲要讲的那个词——**可组合性（composability）**——在工程上真正的含义：**不是"能插进去"，而是"插进去之后还能干净地拔出来"。**

而只要你把标准提到"能拔出来"，立刻会撞上三件必须回答的事：

1. **拔的时候，它留下的状态谁清？** 监听器、连接、注册表条目、缓存——谁负责？
2. **别人依赖它的时候，它被拔掉会怎样？** 依赖方是崩、是降级、还是无声无息？
3. **"我没依赖到东西"和"我依赖到的东西坏了"，怎么区分？**

**Cordis 的答案，就是把所有扩展收敛成三个动作，然后给这三个动作配一套统一的回收语义。** 下面逐个看。

## 二、原理

### 2.1 动作一：贡献服务（占住一个 `ctx` 键）

第一条（`docs/cordis-primer.zh.md:10` 原文）：

> **上下文是服务的容器。** 一个服务占据一个稳定的 `ctx.<key>`（如 `ctx.tools`、`ctx.llm`、`ctx.sessions`）；其他插件通过 key 查找服务，而非导入具体实现。

**"通过 key 查找，而非导入具体实现"** —— 这一句是"可替换"的全部秘密。上一讲 `--dump-config` 能打印出可替换的条目，前提就是**消费方从来没有 import 过提供方的类**，它只认 key。

注册的写法与语义（`docs/cordis-tutorial/03-services.zh.md:39` 原文）：

> **运行时**：`super(ctx, 'greeter')` 以名称 `greeter` 注册该实例。此后，任何插件都可以通过 `ctx.greeter` 访问它。**注册属于 effect，卸载提供方时会移除该服务。**

配套还有**编译时**的一半（`docs/cordis-tutorial/03-services.zh.md:40`）：

> `declare module '@deepseek-ai/cordis'` 块使用 TypeScript 声明合并，把 `greeter` 加入 `Context` 接口，使 `ctx.greeter` 在各处都能通过类型检查。**它不会生成代码；没有该声明时，服务在运行时仍能工作，但消费方会失去类型安全。**

**这两句话放在一起，是一条很有用的判据**：

> **服务是"运行时的名字"+"编译时的类型"，两者可以分开失效。** 声明合并写错/漏写，**运行时照样工作**——于是它不会在 CI 里报错，只会在你写错名字时**从类型检查退化成运行时 undefined**。这是 TypeScript 世界里最典型的"静默降级"。

### 2.2 动作二：发类型化事件（五种分发模式）

第二条（`docs/cordis-primer.zh.md:12` 原文）：

> **类型化事件用于通信。** 服务通过 TypeScript 声明合并注册事件名，然后以 `emit`、`waterfall`（瀑布式事件）、`parallel`、`serial` 或 `bail` 方式分发，分别对应监听者观察、包装、并行扇出、按序执行或停在首个 bail 值。

这里有本讲**最该记住的一张对照表**——五种模式不是风格差异，是**语义差异**：

| 模式 | 语义 | 用它的场合 |
|---|---|---|
| `emit` | 同步广播，不等返回 | 纯通知（"日志写完了"） |
| `parallel` | 并发等待全部结束 | 多个独立检查，谁都不许挡谁 |
| `serial` | 按序等待；首个非 `null`/`false`/`undefined` 的返回胜出 | 需要"顺序确定、结论唯一" |
| `bail` | `serial` 的同步版本 | 同上但不需要异步 |
| **`waterfall`** | **环绕中间件** | **拦截 / 改写 / 放行** |

`waterfall` 是五种里唯一能做"中间件"的那个（`docs/cordis-primer.zh.md:35` 原文）：

> `ctx.waterfall` 是环绕中间件。监听器接收 `(...args, next)`。调用 `next()` 会执行下游监听器；下游返回值通过 `next()` 返回当前包装层，可由该层包装后继续向外返回。**不调用 `next()` 直接返回则短路。**

API 侧的说法更直白（`docs/cordis-api/events.zh.md:118`）：

> 每个监听器都会包装调用链的其余部分：调用 `next()` 会执行下一个监听器，最终执行内置行为；**不调用则会否决后续执行。**

**★ 判据句（本讲第一个架构判据）**：

> **"忘调 `next()`"不是一个小疏忽，它是这个模式里唯一的否决语。** 在 `waterfall` 里，**沉默 = 否决**——你没说话，系统就认为你不让下游跑。这意味着两件事：① 一个只做"观察"的监听器如果写在 `waterfall` 上，会**静默吞掉整条链**；② 想"看一眼然后放行"，必须显式 `next()`。
>
> **对照你自己的系统**：你用 AOP/拦截器时，"没写 return"通常等于"放行"；而在瀑布事件里，"没写 `next()`"等于"拦截"。**同一个视觉形态，相反的语言默认值**——这是迁移时最容易踩的一脚。（第 52 讲会看到 `dsh` 自己在轮次流程里怎么处理这件事。）

### 2.3 动作三：装可逆副作用（这是账单，不是赠品）

第三条（`docs/cordis-primer.zh.md:13` 原文）：

> **注册是可逆的副作用。** 提示词片段、工具 schema、适配器、提供方和监听器通过 `ctx.effect()` 或 `ctx.on()` 安装，**reload 和 teardown 时会按预期撤销。**

注意清单里有什么：**提示词片段、工具 schema、适配器、提供方、监听器**——这正是你在 43–48 讲读过的六大机制的"挂载方式"。**它们全都是 effect。**

那么 effect 的契约到底是什么？原文给得非常精确（`docs/cordis-api/fiber.zh.md:30-32`）：

> `execute` 会立即运行；它产生的清理函数将被收集，并在**调用返回的清理函数或卸载 fiber** 时**按相反顺序**运行，**以先发生者为准**。重复调用清理函数不会产生任何效果。

拆成三条可核对的性质：

1. **立即执行**：注册是同步生效的，不是"延迟到启动完成"。
2. **逆序回退**：`A` 先注册、`B` 后注册 ⇒ 卸载时 `B` 先退、`A` 后退。**像一个栈。**
3. **幂等**：清理函数调两次，第二次是 no-op。

**但第 4 条要特别当心**——异步清理的顺序（`docs/cordis-tutorial/02-lifecycle-and-effects.zh.md:94`）：

> disposer 按注册顺序的逆序**启动**，但多个**异步** disposer 会**并发运行**；需要顺序拆除时应放进同一个 disposer 内依次等待。

**★ 判据句（本讲第二个架构判据，也是最容易被"看起来对"骗过去的一条）**：

> **"逆序卸载"只保证"启动顺序"逆序，不保证"完成顺序"逆序。** 如果你的两个 disposer 有隐式依赖（比如 B 的清理需要 A 还活着），那么"各自异步、并发跑"就会**偶发地**出错——**这是最难复现的一类 bug：单测全过，压测偶挂。**
>
> 正确做法是**把有依赖的清理放进同一个 disposer 里顺序 await**，而不是指望框架的逆序。**"框架保证顺序"这句话，永远要追问"保证到哪一步"。**

还有一条**内置 API 已经是 effect** 的清单（`docs/cordis-tutorial/02-lifecycle-and-effects.zh.md:86-90`）：`ctx.on`、`ctx.plugin(child)`、以及服务注册（如 `ctx.tools.register(...)` 把 disposer 附着到调用插件）。教程对 `ctx.on` 的说法是（`docs/cordis-tutorial/04-events.zh.md:78`）：

> 因为 `ctx.on()` 属于 effect，监听器会随插件一同消失，**绝不需要手动维护 `removeListener`。**

### 2.4 上下文即容器、inject 声明依赖：两个"基础设施"

剩下两个概念是贯穿性的。

**"上下文即容器"** 的机制是**作用域**（`docs/cordis-api/context.zh.md:12`）：`extend()` / `isolate()` / `intercept()` 创建**有作用域的**子上下文，且**不修改父上下文**；`ctx.get(name, strict?)` **默认 strict**，只返回"提供方 fiber 当前活动的"实现（`docs/cordis-api/context.zh.md:250-257`）。

这个 `strict` 默认值是**有意为之的**：它让"我读到一个已经卸载的服务"变成错误，而不是返回一个陈旧引用。**第 53 讲（capability seam）会看到它撑起了整个"按 agent 作用域化"的能力。**

**"inject 声明依赖"** 的关键性质上一讲已经点过，这里补足完整语义——它**不是启动时的一次性检查**（`docs/cordis-tutorial/03-services.zh.md:76`）：

> 运行期所需服务消失时，每个依赖插件随之卸载，服务恢复后再加载。

于是 `inject` 同时是**声明**和**热插拔钩子**：依赖没了，插件自己退场；依赖回来了，插件自己上场。**这既是优点，也是上一讲那条"静默 PENDING"成本的来源。**

最后是**状态机**（`docs/cordis-tutorial/02-lifecycle-and-effects.zh.md:73-80`）：

```
PENDING → LOADING → ACTIVE → UNLOADING → DISPOSED
                     ↓
                   FAILED
```

**`PENDING` 是合法状态**——这就是"我什么都没输出"最常见的解释。

### 2.5 前沿深挖：一次真实事故——`export default` 如何把 `inject` 吃掉

抽象地讲"注册必须可回收"不够有说服力。看 `dsh` 仓库里一篇**真实事故复盘**（`docs/postmortem/0001-acp-default-export-drops-inject.zh.md`）。它会让你对"静态检查能保证什么"有更清醒的认识。

**事故现象**（`:13` 原文）：

> ACP 服务器（`dsh --profile acp`、`@deepseek-ai/dsh-acp`）在真实编辑器（Zed）连接的瞬间崩溃：第一个 `session/new` 请求返回 `Internal error: cannot get property "agents" without inject`，`session/load` 对 `sessionPersistence` 返回同样的错误。

**根因是一个字符都不多的差别**：插件文件里**多写了一行** `export default apply`。Cordis Loader 规范化模块时用的是（`:29-50` 原文）：

```
exports = exports.default ?? exports        // ← prefers `.default`
```

**存在默认导出时，它解析成的是那个"裸 `apply` 函数"** —— 而裸函数**没有 `inject`、没有 `name`、没有 `Config` 属性**（这三者是**同级的命名导出**）。于是 Loader **基于一个空的 `inject` 建了 fiber**，插件根本不知道自己要依赖什么。

而真正的**杀伤力**在两处细节：

**细节一：崩在加载时，不崩在请求处理器里**（`:52`）。

> 崩溃发生在**加载时**而非请求处理器中；请求只是触发了加载。

⇒ 你顺着 `session/new` 去搜代码，会一路搜到"请求处理逻辑完全正常"，**因为错误不在那儿**。

**细节二：100% 行覆盖率全绿**（`:91-98`）。原因是**测试的挂载方式与真实的挂载方式不同**：

> 所有测试都通过不会触及真实加载/解析路径的方式挂载插件（`ctx.plugin({ name, inject, apply })` 手动提供 `inject`，`unwrapExports` 只被 Loader 调用）。

原文那句总结，请逐字读（`:98`）：

> **100% 行覆盖率始终满足。覆盖率证明代码行*被执行过*；它不能说明功能是否*按交付方式正常工作*。**

**★ 判据句（本讲第三个架构判据，也是给研究员最值钱的一条）**：

> **"按交付方式工作"和"按测试方式工作"是两个命题。** 覆盖率只证明"代码行跑过了"，它对你替换过的那个**加载路径**一无所知。这件事在模型侧同样成立：**你评测的是 `model.generate()`，但你线上跑的是"harness 组装 prompt → 路由 → 工具校验 → 重试"那条完整链**。评测口径与交付口径不一致时，你测的分数**与用户看到的表现无关**。
>
> **实操落点**：① 任何"规范化 / 解包 / 动态加载"的地方，都要有一条**走真实入口**的端到端测试（`dsh` 的修法是"增加不用 key 的、经真实 stdio 的 `session/new` e2e"，`:102-106`）；② 对**可能读不到的服务**用 `ctx.get(name)`，而不是 `ctx.<name>`——前者明确表达"可能没有"，后者会走一个**会抛错的代理**（`:110-111`）。

事故的最终防护（`:102-106`）：删掉 `export default apply`；`AgentLoop.resume` 改用 `this.ctx.get('sessionPersistence')`；补真实 stdio 的 e2e；并把"测试真实入口路径"写成规则收进测试文档。**注意最后那条——修完 bug 之后，把判据写进规范，才叫修完。**

### 2.6 Cordis 的三个动作与其生命周期：先画出来

前面 2.1–2.4 是逐条讲的。把它们合成一张图，才能看清**三个动作为什么能用同一套回收语义**——因为它们最后都落在同一张账本上。

<img class="mermaid-svg" src="/zh/book-assets/diag-0114.svg" alt="前面 2.1–2.4 是逐条讲的。把它们合成一张图，才能看清三个动作为什么能用同一套回收语义——因为它们最后都落在同一张账本上。" />

> **图 50-1**　插件的三个动作（贡献服务、发类型化事件、装可逆副作用）在实现上全部收敛为 `ctx.effect`；因此"注册是副作用"，随插件卸载而**逆序**撤销。图里那条 `U` 节点是这张图的关键：**三个动作的回收不是三套逻辑，而是一张按栈顺序清空的账本。**

**这里要给出"三者都是 effect"的源码级证据——一条四层证据链。** 自上而下走一遍 `ctx.effect()` 从登记到撤销的全过程：

**第一层：每个 fiber 有一份 disposer 账本。**

```ts
  public readonly _hooks: Dict<DisposableList<Function>> = Object.create(null)
  public readonly _disposables = new DisposableList<Disposable>()
```

（`vendor/cordis/src/fiber.ts:202-203`）

**第二层：`effect()` 一进来就把自己的 wrapper 压进这份账本**（注释说明这是为了让"重入的 owner 卸载"在 `execute()` 跑任何插件代码之前就能看见它）：

```ts
    removeWrapper = this._disposables.push(wrapper)
    try {
      task = this._execute(runner)
```

（`vendor/cordis/src/fiber.ts:517-522`）

**第三层：卸载时把账本整体取出，而 `clear()` 明确"反转返回"。**

```ts
  clear() {
    const values = [...this.map.values()]
    this.map.clear()
    return values.reverse()
  }
```

（`vendor/cordis/src/utils.ts:27-31`）

```ts
  private async _unload() {
    await Promise.all(this._disposables.clear().map(async (dispose) => {
```

（`vendor/cordis/src/fiber.ts:675-687`）

**第四层：effect 体内部产出的 disposer 走另一条反向循环**（`splice(0).reverse()`），与账本那一层互不重复：

```ts
      for (const disposable of disposables.splice(0).reverse()) {
```

（`vendor/cordis/src/fiber.ts:427-442`）

四层合起来就是 2.3 节那句"逆序启动回退"的机制本身。**注意第三层与第四层是两份清单**（fiber 级 `_disposables` 与 effect 内部的 `disposables`），这也解释了 2.3 节第 4 条那个"逆序只保证启动顺序"的边界——**两份清单各自 reverse，但彼此之间只有 `Promise.all`，没有全局顺序保证。**

还有一条更容易被忽略的事实：**`ctx.provide` 自己就是一个 effect**。它的实现体第一行就是 `this.ctx.fiber.effect(() => {...})`：

```ts
  provide(name: string, value?: any, check?: () => boolean) {
    return this.ctx.fiber.effect(() => {
```

（`vendor/cordis/src/reflect.ts:277-305`）

所以"服务注册随 fiber 卸载撤销"不是一条额外约定，而是**复用同一套 effect 回收**的结果。`Service` 基类也只是把这一步塞进构造器：`self.ctx.reflect.provide(name, self, this[symbols.check])`（`vendor/cordis/src/service.ts:32-59`）。

而 `ctx.on` / `ctx.once` / `ctx.parallel` / `ctx.emit` / `ctx.serial` / `ctx.bail` / `ctx.waterfall` 这七个方法，是由 `ReflectService` 的 mixin 一次性挂上 `ctx` 的——**五个分发方法（`parallel` / `emit` / `serial` / `bail` / `waterfall`）加两个注册方法（`on` / `once`），都在同一处混入**：

```ts
    this.mixin('events', ['on', 'once', 'parallel', 'emit', 'serial', 'bail', 'waterfall'])
```

（`vendor/cordis/src/reflect.ts:219-222`）

**这条 mixin 清单值得记**：它说明"插件能做什么"是一份**显式的白名单**，不是 `ctx` 上的任意属性。可组合系统的可信度，首先来自"能力面是列举得完的"。

### 2.7 五种分发模式、依赖解析与内核分层

#### 五种模式：返回值与控制流的差异

2.2 节那张表是"用哪个"的选择表。这一张图回答的是另一个问题：**这五个方法在实现上到底哪里不一样。** 五个模式里，`serial` 与 `bail` 是一对（只在"等不等"上不同），`emit` 与 `parallel` 是一对（只在"等不等"上不同），`waterfall` 是唯一形态不同的那个。

<img class="mermaid-svg" src="/zh/book-assets/diag-0115.svg" alt="2.2 节那张表是&quot;用哪个&quot;的选择表。这一张图回答的是另一个问题：这五个方法在实现上到底哪里不一样。 五个模式里，`serial` 与 `bail` 是一对（只在&quot;等不等&quot;上不同），`emit` 与 `parallel` 是一对（只在&quot;等不等&quot;上不同），`waterfall` 是唯一形态不同的那个。" />

> **图 50-2**　五种分发模式的返回值与控制流：`emit` / `parallel` 无返回值（前者不等、后者 `await` 全部），`serial` / `bail` 返回"首个非空返回胜出"，`waterfall` 返回最外层监听器的返回值、且**不调 `next()` 即否决整条链**。

**`bail` 与 `waterfall` 的区别必须用源码说清**（这是最容易讲混的一处）。两者的判断依据来自同一个谓词：

```ts
export function isBailed(value: any) {
  return value !== null && value !== false && value !== undefined
}
```

（`vendor/cordis/src/events.ts:7-15`）

`bail` 的实现是一条**普通 for 循环**：

```ts
  bail(...args: any[]) {
    for (const cb of this.dispatch('bail', args)) {
      const result = cb(...args)
      if (isBailed(result)) return result
    }
  }
```

（`vendor/cordis/src/events.ts:217-222`）

`waterfall` 的实现是**构造一个 `next` 闭包并立刻调用它**：

```ts
  waterfall(...args: any[]) {
    const cbs = this.dispatch('waterfall', args)
    const inner = args.pop()
    const next = () => {
      const cb = cbs.shift() ?? inner
      return cb(...args)
    }
    args.push(next)
    return next()
  }
```

（`vendor/cordis/src/events.ts:234-243`）

**两段源码一对比，区别就落在三处，而且每一处都能机械核对**：

| 差别 | `bail` | `waterfall` |
|---|---|---|
| 控制流形态 | for 循环逐个调用 | 构造 `next` 闭包 + 立即调用 |
| 监听器怎么决定"下一个" | 不需要决定——框架自己往下走 | 必须自己调 `next()`；不调即否决 |
| 最后一个参数 | 是普通实参 | 是 `next` 续接（`args.pop()` 取出的 `inner` 是最内层内置行为） |
| 返回值 | 首个非空返回（`isBailed`） | 最外层监听器的返回值 |
| JSDoc 原话 | "calling listeners in order until one bails" | "not calling it vetoes" |

（源码出处：`vendor/cordis/src/events.ts:7-15`、`:217-222`、`:234-243`；JSDoc 在 `vendor/cordis/src/events.ts:66-85`）

一句话记住：**`bail` 是"短路取第一个非空返回值"，`waterfall` 是"每层包裹剩余链、用 `next()` 放行或用沉默否决"。** 前者不需要监听器做任何事就会继续往下走；后者**默认什么都不做就等于拦住全链**——这正是 2.2 节那条判据句的源码依据。

`waterfall` 的"必须带尾参"也不是口头约定，**生成器里有一条结构校验**：

```ts
        if (isMode(mode) && hasNext && mode !== 'waterfall') {
          violations.push(`${where} has a trailing 'next' parameter (structurally a waterfall) but is tagged '@mode ${mode}'. Fix the tag or the signature.`)
        }
        if (isMode(mode) && !hasNext && mode === 'waterfall') {
          violations.push(`${where} is tagged '@mode waterfall' but has no trailing 'next' parameter. A waterfall delegates via next().`)
        }
```

（`packages/typert/generator/src/cordis-catalog.ts:206-211`）

**"签名与 `@mode` 标签不一致就报错"** —— 这一条把"注释撒谎"这种最隐蔽的文档漂移变成了能在 CI 拦住的错误。可组合系统里，**接口声明本身就是契约**，校验它比校验实现更划算。

#### 依赖解析与 fiber 状态机

`inject` 那条"无人提供就静默等待"的行为，在 2.4 节只有文字描述。这里给出**六态状态机 + 依赖环路**的完整图。

<img class="mermaid-svg" src="/zh/book-assets/diag-0116.svg" alt="`inject` 那条&quot;无人提供就静默等待&quot;的行为，在 2.4 节只有文字描述。这里给出六态状态机 + 依赖环路的完整图。" />

> **图 50-3**　`FiberState` 六态与依赖环路：`inject` 指向无人提供的服务 ⇒ 停在 `PENDING`；提供方卸载 ⇒ 依赖它的插件整体卸载（回到 `PENDING`）；提供方恢复 ⇒ 重新加载。**这条环路说明 `inject` 是运行期约束，不是启动检查。**

六态取值本身逐字为：

```ts
export const enum FiberState {
  PENDING,
  LOADING,
  ACTIVE,
  FAILED,
  DISPOSED,
  UNLOADING,
}
```

（`vendor/cordis/src/fiber.ts:139-154`；声明顺序即数值 0..5）

**"缺依赖就 PENDING"的机制有三步，每步一行源码。** 第一步，fiber 天生是 PENDING：

```ts
  /** Current lifecycle state; transitions emit `internal/status`. */
  public state = FiberState.PENDING
```

（`vendor/cordis/src/fiber.ts:193-194`）

第二步，`_refresh()` 遍历 `inject`，任一缺失就把 epoch 置为 `INACTIVE` 并停止：

```ts
  _refresh() {
    let epoch: string | boolean = false
    epoch = ''
    for (const name of Object.keys(this.inject)) {
      const impl = this._store[name]
      if (!impl) {
        epoch = INACTIVE
        break
      }
      epoch += ':' + impl.fiber.uid
    }
    this._setEpoch(epoch)
  }
```

（`vendor/cordis/src/fiber.ts:611-623`；`INACTIVE` 常量在 `vendor/cordis/src/fiber.ts:176`）

第三步，对外报告的状态据此返回 PENDING：

```ts
  private _getState() {
    if (this.uid === null) return FiberState.DISPOSED
    if (this._error) return FiberState.FAILED
    if (this._runner.epoch !== INACTIVE) return FiberState.ACTIVE
    return FiberState.PENDING
  }
```

（`vendor/cordis/src/fiber.ts:574-579`）

**图 50-3 里那条环路的核心在 `_setEpoch()`——它只看 epoch 的"方向变化"：**

```ts
    this._updateState(() => {
      if (epoch !== INACTIVE && oldEpoch === INACTIVE) {
        this.inertia = this._reload()
        return FiberState.LOADING
      } else {
        this.inertia = this._unload()
        return FiberState.UNLOADING
      }
    })
```

（`vendor/cordis/src/fiber.ts:625-639`）

**"从 INACTIVE 变成有值 ⇒ 装载；其它情况 ⇒ 卸载"** —— 这一个 `if/else` 就是"依赖恢复自动上场、依赖消失自动退场"的全部逻辑。它也是把 `inject` 从"声明"升级成"热插拔钩子"的那行代码。

**谁在唤醒 PENDING fiber？** 服务变更时 `provide` 与它的 disposer 都会调用 `notify([name])`，而 `notify()` 遍历所有 runtime 的 fibers，对 `inject` 里含该名字的 fiber 重跑 `_checkImpl` + `_refresh`：

```ts
  notify(names: string[], filter = (ctx: Context, name: string) => ctx[symbols.isolate][name] === this.ctx[symbols.isolate][name]) {
    const fibers: Fiber[] = []
    for (const runtime of this.ctx.registry.values()) {
      for (const fiber of runtime.fibers) {
        let hasUpdate = false
        for (const name of names) {
          if (!(name in fiber.inject)) continue
          if (!filter(fiber.ctx, name)) continue
          hasUpdate = true
          fiber._checkImpl(name)
        }
        if (!hasUpdate) continue
        fiber._refresh()
        fibers.push(fiber)
      }
    }
```

（`vendor/cordis/src/reflect.ts:314-329`）

注意那个 `filter` 默认值：**它按 isolate 作用域比较，而不是全局比较**。这解释了 2.4 节为什么说"作用域"是基础设施——**同一个服务名在不同 isolate 里是不同的实现**，`notify` 不能跨作用域唤醒。

#### 内核的分层：`vendor/` 是九个同级兄弟目录

最后一张图要纠正一个**极易画错的事实**：`loader` / `hmr` / `group` / `include` / `schemastery` / `cosmokit` / `logger-console` / `timer` **不是 `vendor/cordis/` 的子包**——`vendor/cordis/` 内部**只有 `src/`**（`_build/m8-facts/cordis-core.md` 第 1.1 节，据 `find vendor/cordis -type f` 与 `find vendor -maxdepth 2 -type d`）。它们是 `vendor/` 下与 `cordis` **平级**的目录。

<img class="mermaid-svg" src="/zh/book-assets/diag-0117.svg" alt="最后一张图要纠正一个极易画错的事实：`loader` / `hmr` / `group` / `include` / `schemastery` / `cosmokit` / `logger-console` / `timer` 不是 `vendor/cordis/` 的子包——`vendor/cordis/` 内部只有 `src/`（`_build/m8-facts/cordis-core.md` 第 1.1 节，据 `find vendor/cordis -type f` 与 `find vendor -maxdepth 2 -type d`）。它们是 `vendor/` 下与 `cordis` 平级的目录。" />

> **图 50-4**　`vendor/` 下的九个**同级兄弟目录**：`cordis` 是内核，其余八个各司其职。**注意拓扑——八个包都挂在 `vendor/` 上，不是挂在 `cordis` 下**；把 `loader` 画成 `cordis` 的子包是这张图最容易被画错的地方。

各包职责取自各自的 `package.json` `description`，逐字如下：

| 目录 | 包名 | `description`（逐字） |
|---|---|---|
| `cordis` | `@deepseek-ai/cordis` | `Meta-Framework for Modern JavaScript Applications` |
| `loader` | `@deepseek-ai/cordis-plugin-loader` | `Plugin loader for cordis` |
| `hmr` | `@deepseek-ai/cordis-plugin-hmr` | `Hot Module Replacement Plugin for Cordis` |
| `group` | `@deepseek-ai/cordis-plugin-group` | `Nested plugin group for cordis` |
| `include` | `@deepseek-ai/cordis-plugin-include` | `Include files in cordis configurations` |
| `schemastery` | `@deepseek-ai/schemastery` | `Type driven schema validator` |
| `cosmokit` | `@deepseek-ai/cosmokit` | `A collection of common utilities` |
| `logger-console` | `@deepseek-ai/cordis-plugin-logger-console` | `Console logger exporter for cordis` |
| `timer` | `@deepseek-ai/cordis-plugin-timer` | `Timer service for cordis` |

（出处：`vendor/<dir>/package.json:2-3` 逐包；`_build/m8-facts/cordis-core.md` 第 1.2 节整理）

**图 50-4 中"层"的归类是作者的（把八个包按"装载 / 基础工具 / 附加服务"分成三组）**，包与职责的对应则是逐字的。内核自身只含九个源文件（`context.ts` / `events.ts` / `fiber.ts` / `index.ts` / `logger.ts` / `reflect.ts` / `registry.ts` / `service.ts` / `utils.ts`），且 `index.ts` 是唯一 barrel 入口，逐条注释了每个模块的职责（`vendor/cordis/src/index.ts:1-14`）。

**这条分层对本模块的意义**：第 49 讲说的"装配"（`loader` + `include`）和"热更新"（`hmr`）**都不在内核里**——它们是挂在内核旁边的插件。**"内核小到只剩 9 个文件、其余全是插件"**，这就是"一切皆插件"在目录结构上的样子。

### 2.8 一手数据：事件模式与状态机取值

本节全部是"数取值 / 数条目"的可复算量。

| 数 | 值 | 复算方式 / 出处 |
|---|---|---|
| 内核枚举的分发模式取值 | **5** 个 | `vendor/cordis/src/events.ts:32` 的 `DispatchMode` 逐项计数 |
| 同集合在生成器侧的镜像 | **5** 个 | `packages/typert/generator/src/cordis-catalog.ts:23` 的 `type Mode` |
| `internal/*` 内置事件条数 | **6** 条 | `vendor/cordis/src/events.ts:334-349` 逐条计数 |
| 其中标 `@mode waterfall` | **4** 条 | 同上：`internal/config`、`internal/update`、`internal/get`、`internal/set` |
| 其中标 `@mode bail` | **1** 条 | 同上：`internal/listener` |
| 未标 `@mode` | **1** 条 | 同上：`internal/service` |
| `FiberState` 状态取值 | **6** 个 | `vendor/cordis/src/fiber.ts:148-153` 逐项计数 |
| `ctx` 混入的方法总数 | **16** 个 | `vendor/cordis/src/reflect.ts:219-222` 四组混入逐项相加 |
| `Service` 静态符号 | **7** 个 | `vendor/cordis/src/service.ts:12-25` 逐行计数 |
| 事件主表条目 | **81** 条 | `docs/event-producer-consumer.zh.md` 主表行数：`emit` 59 / `waterfall` 17 / `parallel` 3 / `serial` 2（`_build/m8-facts/SPEC-加深度规范.md` 第 6 节） |
| 服务表条目 | **90** 条 | `core` 55 / `seam` 33 / `service` 1 / `bundle` 1（同上） |
| `vendor/` 下一级兄弟目录 | **9** 个 | `find vendor -maxdepth 2 -type d`（`_build/m8-facts/cordis-core.md` 第 1.1 节） |
| 内核 `src/` 下源文件 | **9** 个 | 同上（另加 `bin.js` 共 10 个"源码文件"） |

**两处值得单独读的数**：

第一处是 `internal/*` 那 6 条里 **waterfall 占 4 条**。这说明在内核自己的内部钩子上，**"可拦截 / 可否决"是主用法**，不是例外——`internal/config`、`internal/update`、`internal/get`、`internal/set` 全部给了外部否决权。对照之下 `bail` 只有 1 条、`emit` 一条都没有。**内核对自己用了最"重"的那个模式。**

第二处是事件主表里 **`emit` 59 条 vs `waterfall` 17 条**（`docs/event-producer-consumer.zh.md` 主表行数）。业务事件里 `emit` 占绝对多数——**通知是常态，拦截是少数**。这两组数放一起才是一句完整的话：**内核内部偏爱可拦截，业务事件偏爱只通知。**

### 2.9 搬到你自己系统：把散落的注册收进统一生命周期

本讲最可迁移的不是"有事件总线"，而是"**每个注册动作都对应一个 disposer，而且这件事由框架保证、不由人记得**"。对照你自己的系统：

| Cordis 的做法 | 你自己系统里"该问的问题" |
|---|---|
| 注册统一走 `ctx.provide` / `ctx.on` / `ctx.effect` | 我的监听器 / 计时器 / 连接 / 注册表有统一入口吗，还是散在各处 `addEventListener`？ |
| 每个注册返回 disposer（`ctx.provide` 返回 `() => void`） | 我的每个注册动作，有对应的"撤销动作"吗？ |
| fiber 卸载时 `_disposables.clear()` 逆序启动回退 | 我的"关闭"路径知道该按什么顺序撤销吗？ |
| `inject` 声明依赖，缺失则 `PENDING` | 我区分得开"没依赖到"和"依赖坏了"吗？ |
| `ctx.get(name)` 读可选服务（可能返回 undefined） | 我的可选依赖读取会不会抛错，把"可选"变成"必需"？ |
| `ctx.on` 已是 effect，**绝不需要手写 `removeListener`** | 我的代码里还有多少手写的 `removeListener` / `close`？ |

**可执行的落地步骤（5 步）**：

1. **清点**：列出你系统里所有"注册型"副作用——监听器、定时器、连接、订阅、缓存条目、工具注册、路由注册。**先把清单写全**，不要边清点边改。
2. **收口**：为每一类写一个 `register(...) -> dispose` 形态的封装，把裸 `addEventListener` / `setInterval` / 裸连接全部替换成封装调用。**替换的标准是"调用方拿得到一个 disposer"**，不是"包了一层函数"。
3. **记账**：让每个插件 / 模块持有一个自己的注册清单，并提供 `dispose()`；`dispose()` 内部只做一件事——**按注册逆序调用各 disposer**（借 `DisposableList.clear()` 那种"反转返回"）。
4. **补诊断**：加一个 `listEffects()`（对应 `fiber.getEffects()`，`vendor/cordis/src/fiber.ts:568-572`）输出当前活动注册的标签清单。**它的唯一用途是回答"这个插件到底装了什么"**——注意 `ctx.provide` 这类注册自带 `ctx.provide("name")` 形态的标签（`EffectMeta.label`，见 `docs/cordis-api/fiber.zh.md:324`）。
5. **补可选读取规则**：凡是"可能读不到的服务"，一律走 `get`（可返回 undefined），不走属性访问（会抛错）。**写成一条 lint 规则或评审清单**，别只写进文档。

**★ 判据句**：

> **"插件化"成不成立，判据不是"有没有扩展点"，而是：每一个注册动作是否都有一个能在卸载时被自动调用的 disposer。** 拿到一个插件系统，先数"注册点"和"disposer 点"这两个数——**两个数不相等，多出来的那些就是卸载后的残留。**

## 三、避坑清单

- [ ] **服务用 key 查找，别 import 提供方的类。** 一旦 import，`--dump-config` 里那条就再也替换不动了。
- [ ] **声明合并漏写不会在 CI 报错**，只会让 `ctx.xxx` 从类型安全退化成运行时 `undefined`。写新服务时把 `declare module` 和 `super(ctx, name)` **当成一对**检查。
- [ ] **`waterfall` 里"没调 `next()`" = 否决，不是放行。** 只做观察的监听器不要挂在 waterfall 上；要放行就显式 `next()`。
- [ ] **`serial` vs `parallel` 想清楚再选**：`serial` 的"首个非空返回胜出"意味着**顺序决定结论**，那就必须能说清"为什么这个顺序是对的"。
- [ ] **"逆序卸载"只保证启动顺序逆序，不保证完成顺序逆序。** 有依赖的清理放进**同一个** disposer 里顺序 await。
- [ ] **绕过框架 API 管的资源，卸载时没人替你撤。** 一律包 `ctx.effect()`，否则就是"假卸载"。
- [ ] **别把 `PENDING` 当 bug**，它是合法状态；但要给它配一条"能看出我在等什么"的观测。
- [ ] **"测试通过"证明不了"按交付方式工作"。** 凡有解包 / 动态加载 / 配置规范化的地方，补一条走真实入口的 e2e。
- [ ] **可能不存在的服务用 `ctx.get(name)`**，不要用 `ctx.<name>`（后者会抛错，把"可选"变成"必需"）。
- [ ] **`bail` 与 `waterfall` 不要混着讲。** `bail` 是"短路取第一个非空返回值"，监听器**什么都不做也会继续往下走**；`waterfall` 是"每层包裹剩余链"，**不调 `next()` 就是否决**。看签名就能判：`waterfall` 必带尾参 `next`，否则生成器会直接报结构违规（`packages/typert/generator/src/cordis-catalog.ts:206-211`）。
- [ ] **别把 `vendor/cordis/` 当成"装了 loader / hmr / … 的父包"。** `vendor/cordis/` 内部只有 `src/`；`loader` / `hmr` / `group` / `include` / `schemastery` / `cosmokit` / `logger-console` / `timer` 是 `vendor/` 下的**同级兄弟目录**（`_build/m8-facts/cordis-core.md` 第 1.1 节）。
- [ ] **`notify` 按 isolate 作用域唤醒，不跨作用域。** 同名服务在不同 isolate 里是不同实现（`vendor/cordis/src/reflect.ts:314-329` 的 `filter` 默认值），排查"为什么依赖恢复了却没被唤醒"先看作用域，别先怀疑 `inject` 写错。

## 四、动手任务

> **目标**：把"三个动作"和"一次回收"亲手做一遍。

**步骤 1：写一个最小插件并跑起来**

从 `docs/cordis-tutorial/01-first-plugin.zh.md:12-19` 抄那 7 行（`export const name` + `export function apply(ctx)`），挂进一棵最小配置，确认 `apply` 被调用。

**任务**：把这个文件**故意加一行** `export default apply`，观察现象——**它是崩、是不输出、还是照常工作？** 把结论写下来，再对照 2.5 节的事故复盘，说明"为什么这个现象会骗过测试"。

**步骤 2：亲手验证"注册即买回退"**

在同一个插件里做三件事，**都通过框架 API**：

1. 注册一个服务（`super(ctx, 'demo')` 形态）；
2. 用 `ctx.on()` 挂一个监听器；
3. 用 `ctx.effect()` 开一个"资源"（打印一行 `acquire`，返回一个打印 `release` 的 disposer）。

**任务**：卸载插件，确认三件事都**自动回退**，且 `release` 的打印顺序与 `acquire` 相反。**再把第 3 项改成异步清理，重复两次，记录你是否观察到并发完成**——这就是 2.3 节那条判据的实证。

**步骤 3：制造一次"静默 PENDING"并把它变成可见**

让插件 `inject` 一个**没人提供**的服务。

**任务**：先记录它的表现（大概率是：不输出、不报错）；然后**具体说明**你用什么手段把它和"逻辑写错"区分开。**能写出这条观测手段，才算真的理解了 `PENDING`。**

**步骤 4：把"三个动作"映射回你的系统**

用下面三行，给你自己的系统填空：

| 动作 | 你的系统里对应什么 | 卸载时靠什么回退 |
|---|---|---|
| 贡献服务 | ? | ? |
| 发类型化事件 | ? | ? |
| 装可逆副作用 | ? | ? |

**验收标准**：第三列里，凡是填"业务代码里手动清"或"没考虑过"的，就是你说的"可组合性"的漏洞。

**步骤 5：把 `bail` 与 `waterfall` 的差别写成一条可执行的判据**

抄 `docs/cordis-primer.zh.md:35` 那段 `waterfall` 语义，再对照 `vendor/cordis/src/events.ts:217-222`（`bail` 的 for 循环）与 `vendor/cordis/src/events.ts:234-243`（`waterfall` 的 `next` 闭包）。

**任务**：写出**一句话判据**，让你团队里没读过源码的人也能判断"这个监听器该挂在 `bail` 还是 `waterfall` 上"。写成两组：① **只观察、不干预**的监听器该挂哪；② **可能拦截或改写**的监听器该挂哪。**再补一条反例**：把只做日志的监听器挂到 `waterfall` 上会发生什么（用 2.2 节"沉默 = 否决"解释）。

---

## 本讲小结

1. **插件只做三件事**：贡献服务（占 `ctx` 键）、发类型化事件（五种分发模式）、装可逆副作用（`ctx.effect()` / `ctx.on()`）。定性为"三类动作"是本讲的归类，条目名取自 `docs/cordis-primer.zh.md:7` 的"五个核心概念"。
2. **"通过 key 查找、而非导入实现"是可替换的全部秘密。** 服务是"运行时的名字 + 编译时的类型"，两者会分开失效——声明合并漏写不会让 CI 变红。
3. **`waterfall` 是唯一能做中间件的模式，而它的默认值是反的**：不调 `next()` = 否决。只做观察的监听器不该挂在这里。
4. **可逆副作用的契约是：立即执行、逆序启动回退、清理幂等。** 但**逆序只保证启动顺序**——异步 disposer 并发完成，有依赖就要自己放进同一个 disposer 顺序 await。
5. **`inject` 是运行期约束，不是启动检查。** 依赖消失插件自动卸载、恢复自动加载；没人提供则**静默 `PENDING`**。
6. **一次真实事故证明了"测试通过 ≠ 按交付方式工作"**：多一行 `export default apply` 就让 `inject` 被吃掉，而 100% 行覆盖率全程全绿。修完要能把判据写进规范，才叫修完。

下一讲我们看这三个动作**最重的一个产物**：`ctx.sessions` 上那本**仅追加的会话日志**。它是 `dsh` 里唯一的"真相源"——fork、恢复、transcript、遥测、持久化**五个消费者**全部从它派生。我们要回答：**一份日志凭什么能同时喂饱五个消费者，而不出现"两份历史互相漂移"。**
