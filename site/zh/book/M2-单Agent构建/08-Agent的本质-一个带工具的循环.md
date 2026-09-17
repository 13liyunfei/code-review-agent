# 第 08 讲 · Agent 的本质：一个带工具的循环

> 📌 代码锚点：`agent-kit / agent/Agent.java`、`AgentContext.java`、`AgentOutput.java`、`AgentRuntime.java`、`AgentRunResult.java`、`SupervisorAgent.java`
> 🎯 导读问题：**"你说的 Agent，和 ChatBot 的区别是什么？"**

<img class="mermaid-svg" src="/zh/book-assets/diag-0101.svg" alt="🎯 导读问题："你说的 Agent，和 ChatBot 的区别是什么？"" />


> **图 8-0**　本讲地图：判据只有一条：删掉本轮输出，下一轮输入会不会变。会变才是闭环，模型只调一次就只是 ChatBot。闭环里必须备齐状态、动作与三条正交的终止线，四种错误一律当结果返回。

## 一、痛点

你把一个"代码审查助手"做成了产品：用户贴代码，模型给建议。跑得挺好。

然后老板问："我们什么时候能上 Agent？"

你花了两周，做了这些事：换了一个更强的模型、在 prompt 里加了"你是一个 Agent，你可以自己决定怎么做"、加了工具描述、加了几轮对话记忆。

上线后，产品同学用了一周，反馈：

> "感觉……和之前没什么区别？就是回答长了一点。"

你去看日志，发现了真相：

```
[轮1] 用户：审查这段代码
[轮1] 模型：我需要看到完整的文件才能判断。请提供 UserService.java 的内容。
[结束] ← 请求结束，返回给用户
```

**模型说"我需要看到完整文件"——然后请求就结束了。**

它提出了一个需求，系统没有满足它，也没有让它继续。**这不是 Agent，这是一个会更礼貌地拒绝你的 ChatBot。**

第二个更隐蔽的症状：

```
[轮1] 模型：我要调用 file_read 工具
[轮1] 系统：调用成功，返回 200 行代码
[轮1] 模型：（给出了审查意见，但完全没有引用那 200 行代码里的任何内容）
```

**工具调用了，但观察没有回到下一轮上下文。** 系统记录了一次"成功的工具调用"，模型却根本没看到结果。

这三个症状（不会自己再试一次、观察丢失、无限转交）背后是同一个东西：

> **缺一个"循环"——以及循环的三条终止线。**

## 二、原理

### 2.1 分界线：输出是否影响下一步的输入

先把判据说清楚，因为面试里这一问几乎必考。

**ChatBot 是一次函数映射：**

```
text_in ──► [模型] ──► text_out
```

输出**只给人看**。它不改变系统的任何状态，也不影响下一次调用的输入（除了作为历史消息）。

**Agent 是一个闭环：**


<img class="mermaid-svg" src="/zh/book-assets/diag-0102.svg" alt="Agent 是一个闭环：" />


> **图 8-1**　Agent 的闭环。**判据在这条回边上**：把 `Observe` 删掉，如果下一轮的输入不变，那它就还是 ChatBot——不管 prompt 里写了几遍"你是一个 Agent"。

**所以判据是**：

> **把它这一轮的输出删掉，下一轮的输入会不会不一样？**
>
> 会 → 是 Agent。不会 → 是 ChatBot，不管它的 prompt 里写了几遍"你是一个 Agent"。

再给一个更工程化的判据：

| | ChatBot | Agent |
|---|---|---|
| 调用模型次数 | **1 次** | ≥ 1 次，**直到满足终止条件** |
| 输出去向 | 给人 | **既给人，也回灌进上下文** |
| 有工具吗 | 可以没有 | **必须有**（否则循环没有意义） |
| 失败怎么办 | 返回错误 | **可以再试、换方法、换工具** |
| 终止由谁决定 | 调用方 | **模型自己说 done，或撞上限** |

**"调用模型一次"是 ChatBot 的硬特征。** 只要你的实现里模型只被调用一次，它就一定不是 Agent——因为一次调用意味着"没有下一轮"，也就没有"观察回灌"。

### 2.2 最小骨架只需要三样东西

去掉所有装饰，一个 Agent 运行时只有三个组成部分：

| 组成 | 回答的问题 | 在本项目里是 |
|---|---|---|
| **状态** | 循环之间要保持什么 | `AgentContext`（任务 + 历史 + 共享状态 + 工具） |
| **动作** | 能对外界做什么 | `ToolRegistry`（第 05 讲） |
| **终止条件** | 什么时候停 | `AgentOutput.FinalAnswer` + `maxHops` |

看代码，一个"领域 Agent"的定义只有一行：

```java
// agent/Agent.java
/**
 * 领域 Agent：一个可被 {@link AgentRuntime} 调度的执行单元。
 *
 * <p>handler 返回 {@link FinalAnswer}（完成任务）或 {@link Handoff}（转交其他 Agent），
 * 运行时负责消息传递与协作协议。
 *
 * @param name          Agent 唯一名（Handoff 目标引用）
 * @param systemPrompt  系统指令（进入对话上下文）
 * @param handler       执行逻辑（入上下文、出结果）
 */
public record Agent(String name, String systemPrompt, Function<AgentContext, AgentOutput> handler) {
}
```

**注意 `handler` 的签名：`AgentContext → AgentOutput`。**

这个签名把整个 Agent 模型说清楚了：

- 输入是**上下文**（不是一段字符串）——因为循环之间要携带历史与状态；
- 输出是**一个决策类型**（不是一段文本）——因为它要回答"继续还是结束"。

**如果 `handler` 的签名是 `String → String`，那这个类就不可能是 Agent。** 类型签名在这里就是架构声明。

### 2.3 循环必须有三条终止线

这是这一讲最实用的一节。**任何 Agent 循环都必须有这三条终止线，缺任意一条都会在生产上挂。**

```
       ┌─ ① 模型自己说 done（正常出口）
       │
循环 ──┼─ ② 迭代上限（防"模型永远不说 done"）
       │
       └─ ③ 时间预算（防"单轮特别慢，10 轮就是 10 分钟"）
```

**只有 ① 的后果**：模型陷入"我再确认一下"的循环，永远不说 done。你会看到一次审查跑了 40 分钟、花了 30 块钱。

**只有 ①② 的后果**：上限设成 10 轮，但每轮调一次慢模型要 30 秒——**最坏情况 5 分钟**，而你的接口超时是 3 分钟。**上限并不能保证及时返回**，因为"次数"和"时间"是两个维度。

**只有 ①③ 的后果**：时间预算内模型疯狂调工具（每次只要 100ms），跑了几百轮，把配额烧光。

**三条线各管一个维度：模型意愿、次数、时间。** 它们不是重复的保险，而是**三个正交的约束**。

### 2.4 协作协议：只有两种输出

`AgentOutput` 用 sealed interface 把协作协议钉死成两个分支：

```java
// agent/AgentOutput.java
/** Agent 输出（协作协议）：完成或转交。 */
public sealed interface AgentOutput permits AgentOutput.FinalAnswer, AgentOutput.Handoff {

    /** 完成任务：给出最终结论。 */
    record FinalAnswer(String content) implements AgentOutput {
    }

    /** 转交：把子任务交给另一个 Agent。 */
    record Handoff(String target, String task) implements AgentOutput {
    }
}
```

**为什么用 `sealed`？** 因为它是**编译期的完备性检查**：以后有人想加第三种输出（比如 `AskUser`），编译器会强制他回到每一个 `instanceof` 分支去处理。**这比"运行时发现某个分支没处理"好得多。**

再看运行时怎么消费这两个分支：

```java
// AgentRuntime.java（循环体）
if (out instanceof AgentOutput.FinalAnswer fa) {
    return new AgentRunResult(current, fa.content(), List.copyOf(trajectory));
}
if (out instanceof AgentOutput.Handoff h) {
    log.info("[AgentRuntime] {} 转交任务给 {}：{}", current, h.target(), h.task());
    history.add(ChatMessage.user("（来自 " + current + " 的转交）" + currentTask));
    current = h.target();
    currentTask = h.task();
}
```

**`Handoff` 的处理里有一行很容易被忽略，但它决定了这套协作能不能用**：

```java
history.add(ChatMessage.user("（来自 " + current + " 的转交）" + currentTask));
```

**上一棒的任务被写进了历史。** 于是下一个 Agent 拿到的上下文里，包含了"是谁转交给我的、当时的任务是什么"。

如果没有这一行，B 只知道"我要做一个任务"，不知道**这个任务的来历**——于是它无法判断"我该做多深"，甚至可能把上游已经查过的东西再查一遍。

**另外注意那个前缀格式 `（来自 X 的转交）`**。它不是给人看的日志，是**给模型看的信号**——让模型知道"这句话不是用户说的，是另一个 Agent 转来的"。这个区分在长链路里很重要：模型对"用户指令"和"同事转述"的处理方式应该不同。

### 2.5 护栏：未知目标不抛异常，异常不炸循环

```java
// AgentRuntime.java（循环体开头）
Agent agent = agents.get(current);
if (agent == null) {
    return new AgentRunResult(current, "错误：Agent " + current + " 不存在", List.copyOf(trajectory));
}
trajectory.add(current + ": " + currentTask);
AgentContext ctx = new AgentContext(currentTask, history, new LinkedHashMap<>(), null);
AgentOutput out;
try {
    out = agent.handler().apply(ctx);
} catch (Exception e) {
    log.warn("[AgentRuntime] {} 执行异常：{}", current, e.getMessage());
    return new AgentRunResult(current, "Agent " + current + " 执行异常: " + e.getMessage(),
            List.copyOf(trajectory));
}
```

**两处都返回结果，而不是抛异常。** 这和第 05 讲工具循环里的取向完全一致：

> **在循环里，"错误"是一种可以返回的结果，不是一种需要炸掉整个流程的信号。**

因为它已经产出了部分结果——`trajectory` 里记着走过的每一步。抛异常会让这些信息全丢掉。

最后是循环出口：

```java
// AgentRuntime.java
return new AgentRunResult(current, "达到最大转交次数（" + maxHops + "），任务未收敛",
        List.copyOf(trajectory));
```

**注意这句话的措辞："任务未收敛"，而不是"任务失败"。**

这是一个重要的语义区分：达到上限**不等于失败**——它意味着"我们不知道有没有做完"。把它报成"未收敛"，比报成"失败"更准确，因为它会促使你去查"是转交逻辑写坏了，还是这个任务本来就需要更多轮"。

### 2.6 轨迹是一等公民

```java
// agent/AgentRunResult.java
/**
 * @param finalAgent  产出最终结论的 Agent
 * @param answer      最终结论
 * @param trajectory  协作轨迹（agent: task 序列，审计用）
 */
public record AgentRunResult(String finalAgent, String answer, List<String> trajectory) {
}
```

**循环里每一步都在往 `trajectory` 里追加：**

```java
trajectory.add(current + ": " + currentTask);
```

格式是 `agent: task`。**这个设计在排障时的价值极高**——一次多 Agent 协作跑歪了，你不需要读日志，只需要看 `trajectory`：

```
reviewer: 审查 PR #42
security: 检查 SQL 注入风险
logic: 检查并发安全
security: 检查 SQL 注入风险      ← 回到 security 了，说明有环形转交
```

**最后一行暴露了问题**：`security → logic → security` 是一个环。有了轨迹，这个环一眼可见；没有轨迹，你只能靠读几百行日志去拼。

> **"把控制流的每一步都记成一条可读的记录"是 Agent 系统里回报最高的一个工程习惯。** 因为 Agent 的行为是不可预测的——你无法靠单元测试覆盖它，只能靠**事后能看懂它做了什么**。

### 2.7 第二个循环：LLM 来决策派发给谁

`AgentRuntime` 是"Agent 自己决定转交给谁"。还有另一种编排叫 **Supervisor 模式**：由**一个 LLM** 决定"派给谁、做什么、是否完成"。

```java
// SupervisorAgent.java
/**
 * Supervisor Agent（路由式多 Agent 编排）：LLM 决策「派给谁、干什么、是否完成」，
 * 循环派发到 worker 直到目标达成（supervisor 路由模式）。
 */
public String run(String goal, List<Worker> workers, Function<String, String> runWorker, int maxIterations) {
    StringBuilder transcript = new StringBuilder();
    for (int i = 1; i <= maxIterations; i++) {
        String decision = model.chat(buildPrompt(goal, workers, transcript));
        JsonNode json = extractJson(decision);
        if (json == null || (!json.hasNonNull("worker") && !json.path("done").asBoolean(false))) {
            log.warn("[Supervisor] 第 {} 轮输出非法 JSON，按完成返回", i);
            return "已完成（supervisor 决策异常）：" + decision;
        }
        if (json.path("done").asBoolean(false)) {
            String summary = json.path("answer").asText("目标已完成");
            log.info("[Supervisor] 目标完成，共派发 {} 轮", i);
            return summary;
        }
        String worker = json.path("worker").asText();
        String task = json.path("task").asText();
        boolean known = workers.stream().anyMatch(w -> w.name().equals(worker));
        String out;
        if (!known) {
            out = "错误：worker " + worker + " 不存在";
        } else {
            try {
                out = runWorker.apply(worker + "：" + task);
            } catch (Exception e) {
                out = "worker 执行异常: " + e.getMessage();
            }
        }
        transcript.append("派发 ").append(worker).append(" 任务：").append(task)
                .append("\n结果：").append(out).append('\n');
    }
    return "达到最大派发轮数（" + maxIterations + "），任务未收敛";
}
```

**和 `NativeToolCallingLoop` 对照着看，你会发现它们是同一个东西的两个视角：**

| | `ToolCallingLoop` | `SupervisorAgent` |
|---|---|---|
| 可选的"动作" | 工具（`ToolRegistry`） | **Agent**（`workers`） |
| 决策方式 | `{"action":"call_tool","tool":...}` | `{"worker":"xxx","task":"..."}` |
| 结束方式 | `{"action":"finish"}` | `{"done":true}` |
| 观察回灌 | `transcript`（工具输出） | `transcript`（worker 输出） |

> **把 Agent 当成一种"工具"，就是 Supervisor 模式。** 这个视角一旦建立，你会发现自己不需要学新的架构——**多 Agent 编排本质上就是工具循环，只不过"工具"变成了"会说话的协作方"。**

三个细节值得记住：

1. **未知 worker 返回"可纠正的错误"**（`错误：worker xxx 不存在`），而不是抛异常 —— 同第 05 讲。
2. **worker 异常也被降级为一条观察**（`worker 执行异常: ...`），下一轮 supervisor 还能换人重试。
3. **非法 JSON 时"按完成返回"**，并把模型的原始输出作为结论 —— 和第 05 讲 `ToolCallingLoop` 的处理完全一致。

**注意这三条里没有一条是"抛异常"。** 一个多 Agent 编排器里，任何一处抛异常都意味着前面所有 Agent 的工作全部作废——**这是不能接受的代价。**

## 三、代码

### 3.1 `AgentContext`：上下文的四个槽位

<img class="mermaid-svg" src="/zh/book-assets/diag-0103.svg" alt="### 3.1 `AgentContext`：上下文的四个槽位" />


**四个槽位的分工是有讲究的**：

| 槽位 | 语义 | 生命周期 |
|---|---|---|
| `task` | 当前这一轮要做什么 | 每轮可变（Handoff 会改它） |
| `history` | 对话/交互历史 | **累积**，跨轮携带 |
| `state` | 结构化共享状态（键值对） | 跨 Agent 共享 |
| `tools` | 可用工具 | 装配时注入 |

**为什么 `history` 和 `state` 要分开？** 因为它们服务的东西不同：

- `history` 是**给模型读的**（自然语言消息序列，会进 prompt）；
- `state` 是**给代码读的**（结构化数据，不会进 prompt，除非你显式渲染它）。

**把结构化数据塞进 history 是常见错误**：它会让 prompt 里混进一堆 JSON 噪声，既贵又降低模型注意力。**要进模型的东西才放 history，程序自己用的东西放 state。**

还有一处细节：`history()` 返回 `List.copyOf(history)` 而不是直接返回内部列表。**这是防止调用方从外部改掉内部状态**——尤其当这个列表会被多个 Agent 共享时。

### 3.2 `AgentRuntime` 的构造：`maxHops` 默认 10

```java
// AgentRuntime.java
private final Map<String, Agent> agents = new LinkedHashMap<>();
private final int maxHops;

public AgentRuntime(Agent... agents) {
    this(10, agents);
}

public AgentRuntime(int maxHops, Agent... agents) {
    this.maxHops = Math.max(1, maxHops);
    for (Agent a : agents) {
        this.agents.put(a.name(), a);
    }
}
```

**两个设计动作**：

1. **`LinkedHashMap`** 而不是 `HashMap`——Agent 的注册顺序被保留下来。虽然在 `Handoff` 模式里顺序不直接决定行为，但**注册顺序在 `describe()` / 调试输出里是可读的**，排障时有用。
2. **`Math.max(1, maxHops)`**——防御"传了 0 或负数"，否则循环一次都不跑，直接返回"达到最大转交次数（0）"。**这种"用一行兜住非法入参"的写法，在本项目的构造器里反复出现**（第 04 讲的 `BackoffPolicy`、第 05 讲的 `ToolCallingLoop` 都有）。

### 3.3 循环骨架逐行

```java
// AgentRuntime.java:start(...)
String current = agentName;
String currentTask = task;
List<String> trajectory = new ArrayList<>();
List<ChatMessage> history = new ArrayList<>();

for (int hop = 0; hop < maxHops; hop++) {
    Agent agent = agents.get(current);
    if (agent == null) { return ...错误...; }
    trajectory.add(current + ": " + currentTask);
    AgentContext ctx = new AgentContext(currentTask, history, new LinkedHashMap<>(), null);
    AgentOutput out;
    try {
        out = agent.handler().apply(ctx);
    } catch (Exception e) { return ...异常...; }
    if (out instanceof AgentOutput.FinalAnswer fa) {
        return new AgentRunResult(current, fa.content(), List.copyOf(trajectory));
    }
    if (out instanceof AgentOutput.Handoff h) {
        history.add(ChatMessage.user("（来自 " + current + " 的转交）" + currentTask));
        current = h.target();
        currentTask = h.task();
    }
}
return new AgentRunResult(current, "达到最大转交次数（" + maxHops + "），任务未收敛",
        List.copyOf(trajectory));
```

**逐行读一遍，注意这几个变量的生命周期**：

- `current` / `currentTask`：**每轮可变**，是"接力棒"；
- `trajectory`：**只写不读**（除了返回），是审计记录；
- `history`：**跨轮累积**，是真正的上下文；
- `ctx`：**每轮新建**，但 `history` 是同一个对象引用 —— 于是历史自然延续。

**最后一个点最关键**：`new AgentContext(currentTask, history, new LinkedHashMap<>(), null)` 每次都新建了 `AgentContext`，但 `history` 传的是**同一个 List 引用**，而 `state` 传的是**新建的空 Map**。

这是一个**有意的选择**：**历史跨轮共享，状态每轮重置。**

为什么状态要重置？因为 `AgentContext` 的构造函数里有一行：

```java
this.state = state == null ? new java.util.LinkedHashMap<>() : state;
```

而 `AgentRuntime` 明确传了 `new LinkedHashMap<>()`——**每个 Agent 拿到独立的状态 Map**。

**这是防止"Agent A 往 state 里写的临时变量，被 Agent B 当成事实读走"。** 如果 state 也共享，一次多 Agent 协作里就会有隐式的、无人声明的耦合——而这种耦合在排障时几乎不可见。

> **共享"必要的东西"（history），隔离"可能被误用的东西"（state）。** 这是一个在 Agent 编排里很容易配错的边界。

## 四、避坑清单

- [ ] **判据是"输出是否影响下一步输入"。** 模型只被调用一次的实现一定不是 Agent。
- [ ] **循环必须有三条终止线**：模型说 done、迭代上限、时间预算。三条各管一个维度，不重复。
- [ ] **上限不能代替超时**：10 轮 × 30 秒 = 5 分钟，次数上限保证不了及时返回。
- [ ] **协作协议用 `sealed interface` 定死**，让编译器帮你检查每个分支都被处理。
- [ ] **转交时把上游任务写进历史**（含"来自谁"的前缀），否则下一个 Agent 不知道事情的来历。
- [ ] **未知目标 / 执行异常都返回结果，不抛异常**——循环走到一半的信息不能被丢掉。
- [ ] **上限出口的措辞是"未收敛"而不是"失败"**，否则会误导排障方向。
- [ ] **轨迹要带上"谁 + 做了什么"**，并且始终随结果返回。**环形转交只有靠轨迹才能一眼看出来。**
- [ ] **`history` 跨轮共享、`state` 每轮重置。** 别让一个 Agent 的临时变量被另一个当成事实。
- [ ] **给模型读的放 history，给代码读的放 state。** 把结构化数据塞进 prompt 既贵又降注意力。
- [ ] **构造器里对非法入参兜底**（`Math.max(1, maxHops)` 这类），否则 0 会让循环一次都不跑。
- [ ] **Agent 就是一种"工具"。** 把 Agent 当作可调用对象，Supervisor 编排就不需要新概念。

## 五、动手任务

> **任务一：亲手复现"环形转交"。**
>
> **仓库位置**：`agent-kit`
>
> **操作**：
> 1. 构造三个 Agent：`a` 转交给 `b`、`b` 转交给 `c`、`c` 转交给 `a`（**谁都不返回 FinalAnswer**）。
> 2. 用 `new AgentRuntime(5, a, b, c)` 跑 `start("a", "任务")`。
> 3. 打印返回的 `AgentRunResult.trajectory()`。
>
> **预期结果**：`trajectory` 有 5 条、`answer` 是"达到最大转交次数（5），任务未收敛"。
> **然后把它改成 `new AgentRuntime(a)`（默认 maxHops=10）**，观察轨迹长度变成 10 —— **这就是"三条终止线"里第 ② 条在起作用的样子。**
>
> **任务二：证明"没有观察回灌就不是 Agent"。**
> 1. 写一个只有 `FinalAnswer` 的 handler（永远不返回 Handoff）。
> 2. 打印 `trajectory.size()`。
>
> **预期结果**：**恒为 1**。因为第一轮就返回了 FinalAnswer——**只调用了一次 handler，等价于"只调用了一次模型"**。这正是本章开头那个"包装成 Agent 的 ChatBot"。
>
> **任务三：让 `maxHops` 传 0，看兜底。**
> 用 `new AgentRuntime(0, agent)`，跑一次。
>
> **预期结果**：`maxHops` 被 `Math.max(1, 0)` 兜成 1，循环**跑一次**，而不是立刻返回"达到最大转交次数（0）"。**如果你看到的是"0 次"，说明这行兜底被删了。**

---

## 本讲小结

1. **判据只有一条**：输出是否影响下一步的输入。**模型只调一次 = ChatBot。**
2. **最小骨架三件**：状态（`AgentContext`）、动作（工具）、终止条件。而**终止条件必须有三条线**（模型意愿 / 迭代上限 / 时间预算），它们正交。
3. **协作协议只有两种输出**：`FinalAnswer` 与 `Handoff`，用 `sealed interface` 让编译器检查完备性。
4. **转交要带上下文溯源**（"来自谁的转交"），否则下一个 Agent 无从判断该做多深。
5. **循环里的错误都是结果，不是异常。** 未知目标、执行异常、非法 JSON、达到上限——四种都返回可读结果。
6. **轨迹是一等公民**。Agent 行为不可预测，你唯一能依靠的就是"事后能看懂它做了什么"。
7. **`history` 共享、`state` 隔离**；给模型的进 history，给代码的进 state。

下一讲把这个循环的另外两件事补齐：**工具该怎么组织、以及什么时候真的需要"先规划再执行"。**
