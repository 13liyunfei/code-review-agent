# 第 05 讲 · Function Calling：让模型能干活

> 🎯 相关面试考点：**"你的 Agent 是真在用工具，还是只在 prompt 里假装有工具？"**

<img class="mermaid-svg" src="/zh/book-assets/diag-0014.svg" alt="🎯 相关面试考点：&quot;你的 Agent 是真在用工具，还是只在 prompt 里假装有工具？&quot;" />


> **图 5-0**　本讲地图：模型只看得到三个字符串，其中 `description` 决定它用不用这个工具；选不中工具时要把错误变成**可纠正的观察**，而不是抛异常。

## 一、痛点

你给审查 Agent 加了一个 `file_read` 工具，让它能主动去读仓库里的完整文件，而不是只看 diff 片段。

跑通了一个 demo，效果不错。上线一周后，审查报告里开始出现这种东西：

```
[MAJOR] 建议补充完整的上下文分析
描述：由于我无法访问项目文件，我无法确认此处是否存在并发问题。
      建议开发者自行检查 ThreadLocal 的使用是否正确。
```

**这是模型在自言自语，而系统把它当成了一条审查发现。**

再往下查，还有更糟的：

| 现象 | 真实原因 |
|---|---|
| 报告里出现"工具 `read_file` 未找到" | 模型把工具名记错了（真实名是 `file_read`） |
| 工具被调用了，参数是 `{"path": "请读取 UserService.java"}` | 模型的 `arguments` 是一句自然语言，不是 JSON |
| 一次审查触发了 **9 次**编译 | 模型在循环里反复调同一个重工具 |
| 某次审查跑了 **4 分钟**才返回 | 循环没有终止，撞上最大迭代 |

这几个现象指向同一个事实：

> **Function Calling 不是"加上就能用"的能力。它是一个"模型决策 + 你来兜底"的接口——所有不确定性都在你这一侧。**

这一讲就讲清楚：工具该怎么声明、决策循环该怎么写、以及**在模型不按套路出牌时你的每一层兜底**。

## 二、原理

### 2.1 Function Calling 的两条实现路线

"让模型调用工具"在工程上有两条完全不同的路线：

| | 路线 A：prompt-JSON 决策 | 路线 B：原生 tools 协议 |
|---|---|---|
| 怎么做 | 把工具清单写进 prompt，要求模型输出 `{"action":"call_tool","tool":...}` | 用供应商的 `tools` 参数声明工具，模型返回结构化的 `tool_calls` |
| 优点 | **任何模型都能用**（包括不支持的、自部署的、能力弱的） | 可靠性高，参数是合法 JSON，支持**一次返回多个调用** |
| 缺点 | 模型可能输出非 JSON、写错工具名、参数写成自然语言 | 依赖模型支持；不同厂商报文差异要适配 |
| 典型故障 | "我无法访问文件" 被当成结论 | 少数模型不支持 → 直接报错 |

在 `agent-kit` 里，**两条路线都实现了**，而且是互补的：

```java
// NativeToolCallingLoop.java:33-37
/**
 * <p>与 {@link ToolCallingLoop}（prompt-JSON 模式）互补：本类走供应商原生
 * {@code tools} 参数协议，一次可返回多个工具调用并**并行执行**，工具结果以
 * role=tool 消息回填，可靠性远高于文本决策。模型不支持原生时用
 * {@link #runWithFallback} 自动降级。
 */
```

这个"**优先原生、降级 prompt**"的组合是工程上的正确解：

- 用能用的最好的那条路（原生）；
- 但不能**只有**那条路——因为你的 Agent 要能在"换成另一个自部署模型"时继续跑。

降级是**显式的一个方法**，不是隐式行为：

```java
// NativeToolCallingLoop.java:139-149
/** 回退模式：模型不支持原生调用时退化为 prompt-JSON 决策。 */
public static ToolCallingLoop.LoopResult runWithFallback(NativeChatModel nativeModel, ToolRegistry registry,
                                                         String goal, String context, int maxIterations) {
    return new ToolCallingLoop(new com.codereview.kit.ChatModel() {
        @Override
        public String chat(String prompt) {
            return nativeModel.chat(List.of(NativeMessage.system(...)),
                    List.of(), NativeOptions.defaults()).content();
        }
    }, registry, maxIterations).run(goal, context);
}
```

注意 `List.of()` 那个参数——**把 `tools` 参数传空，就是"假装这个模型不支持工具调用"**，于是它必然走文本决策。一行代码完成降级。

### 2.2 给模型看的只有三样东西，而 `description` 决定它用不用

工具对模型的全部可见面，就是三个字符串——把 `AgentTool` 接口画成对象图就一目了然：

<img class="mermaid-svg" src="/zh/book-assets/diag-0015.svg" alt="工具对模型的全部可见面，就是三个字符串——把 `AgentTool` 接口画成对象图就一目了然：" />


> **图 5-1**　`AgentTool` 接口形态。一个工具对外暴露四件事：名字、描述、参数、执行。**模型只认识前三个**——`execute()` 是系统侧的事，模型看不见。而 `description` 是整个接口里最关键的一个方法：**它写得好不好，直接决定模型会不会选用这个工具。**

**`description` 的注释里那句"影响模型是否选用该工具"，是这一讲最容易被低估的一句话。**

模型没有任何关于你工具的额外知识——它只看这三行文本。"模型选不中工具"，九成不是模型笨，是**描述写得像给人看的、不像给模型看的**。

对比一下：

```
❌ 把语义藏起来
read: 读取文件

✅ 把"什么时候用它"写出来
file_read: 读取白名单目录内指定相对路径的文本文件（前 100 行）
```

第二种写法回答了两个模型真正关心的问题：**我能用它做什么**（读文件）、**它能给我什么**（前 100 行）。第一种写法只给了一个动作名。

工具清单最终是被**拼成文本**放进 prompt 的：

```java
// ToolRegistry.java
/** 生成给 LLM 的工具清单文本（决策上下文的一部分）。 */
public String describeForPrompt() {
    List<String> lines = new ArrayList<>();
    for (AgentTool t : list()) {
        lines.add("- " + t.name() + ": " + t.description() + " 参数: " + t.parameterSchema());
    }
    return String.join("\n", lines);
}
```

**三行信息一条工具，这就是模型看到的全世界。** 所以：工具数量越多，每一行的信噪比越重要——**给模型 30 个工具，不如给它 6 个描述精准的工具。**

### 2.3 "模型选不中工具"的四类原因

按出现频率排序：

**① 描述太模糊。** 上面说的。修法：写清"何时用、返回什么"。

**② 工具不存在时，没有给模型"可纠正的反馈"。**

这是最容易漏的一层。模型写错了工具名，你会怎么做？如果直接抛异常，这一轮就废了。正确做法是**把错误变成一个观察，让模型有机会改**：

```java
// ToolCallingLoop.java（循环体）
String toolName = json.path("tool").asText();
AgentTool tool = registry.get(toolName);
String observation;
if (tool == null) {
    observation = "错误：工具 " + toolName + " 不存在，可用工具见清单";
} else { ... }
```

**注意这句话的措辞**——它没说"调用失败"，而是告诉模型"**可用工具见清单**"，把它指回工具清单。下一轮模型就有机会修正。这叫**可纠正的错误**，是 Agent 循环设计的核心技巧。

**③ 参数是自然语言，不是 JSON。**

```java
{"path": "请读取 UserService.java"}
```

典型报错是模型把 `arguments` 当成了对话。这里有两道防线：

- **护栏层**（`GuardedTool` → `ToolSchemaValidator`）拦截；
- **执行层**（`ToolCallingLoop.safeExecute`）兜住异常。

**④ 需要"先规划再调用"的任务，被塞进了一轮决策。**

比如"先看这个文件，再根据看到的内容决定要不要看另一个文件"——这类**依赖前一步结果**的任务，天然无法在一轮里决策完。它需要迭代（也就是循环），而循环就带来终止问题（下一节）。

### 2.4 安全边界：工具是"模型能对外界做的事"

这一节必须放在设计的最前面想：**一旦模型能调工具，你的系统攻击面就从"文本"扩展到了"动作"。**

`agent-kit` 的护栏策略分成两层：

**第一层，参数级的危险模式拦截。**

```java
// ToolSchemaValidator.java
/** 危险子串（命令注入 / SQL 注入向量）。 */
private static final List<String> DANGEROUS = List.of(
        ";", "&&", "||", "`", "$(", "rm -rf", "drop table", "truncate table",
        "delete from", "union select", "..\\", ".." + java.io.File.separator);
```

这是**粗粒度但便宜**的第一道网——它的价值不在于严谨（字符串黑名单永远不严谨），而在于**在参数进入执行体之前就拦掉明显的注入形态**。

**第二层，工具自身的边界约束。**

```java
// BuiltinTools.FileReadTool
Path target = baseDir.resolve(rel).normalize();
if (!target.startsWith(baseDir)) {
    return ToolResult.fail("拒绝访问：路径越界 " + rel);
}
```

**这一行的写法值得背下来**：`resolve → normalize → startsWith(baseDir)`。

- `resolve` 把相对路径拼到根上；
- `normalize` 消掉 `..` 和 `.`（**不做这一步，`startsWith` 是可绕过的**）；
- `startsWith` 确认最终落点还在白名单目录内。

顺序错一个，路径穿越就成立了。而 `../../etc/passwd` 与 `..%2f..%2fetc/passwd` 这类变体，只有在 `normalize` 之后比较才是安全的。

**第三层（架构层的），是"重工具默认不暴露"。**

这一层不在 agent-kit 里，而在主线系统 `code-review-agent` 里。它把工具按**风险/成本**分成三级：

<img class="mermaid-svg" src="/zh/book-assets/diag-0016.svg" alt="这一层不在 agent-kit 里，而在主线系统 `code-review-agent` 里。它把工具按风险/成本分成三级：" />


裁定逻辑（`ToolGate.java:64-76`）：

```java
boolean allowed = switch (exposure) {
    case DIRECT -> true;
    case DEFERRED -> deferredEnabled || profile == ReviewProfile.STRICT;
    case CODE_MODE -> codeModeEnabled;
};
recordStat(tool, allowed);
if (!allowed) {
    log.info("[ToolGate] fail-closed：拒绝工具 {}（exposure={}, deferredEnabled={}, profile={}）",
            tool, exposure, deferredEnabled, profile);
}
```

**默认值是 `false`，注释写着"fail-closed"。** 这是这一节最重要的设计取向：

> 一个"能跑全量编译、能跑测试、能实际改代码"的工具，**默认绝不该被模型碰到**。要开，必须显式开。

为什么这是对的？因为**误用的代价不对称**：拒绝一个本该允许的工具，最坏结果是这次审查少发现一个问题；放行一个不该放行的重工具，最坏结果是模型在别人的 CI 上跑了 `mvn clean test` 九次——**或者更糟，直接改了代码。**

还有一处细节：`ToolGate` 记录了每个工具的**放行/拒绝计数**（`stats`）。它的用途写在类注释里：

> 每次裁定均记录调用统计（工具名 → 放行 / 拒绝计数），便于审计"哪些重工具被尝试调用"。

**"被尝试调用"这个数据本身就是一个安全信号。** 一个正常运行的审查系统，不应该频繁尝试调用 `autofix.apply`。如果这个拒绝计数在涨，说明有人（或者某种注入）在诱导模型去碰它。

## 三、代码

### 3.1 `ToolCallingLoop.java`：三层兜底，每一层都有名字

这个类的类注释已经把安全边界写成了清单：

```java
// ToolCallingLoop.java
/**
 * <p>安全边界：最大迭代次数防死循环；工具执行异常不炸循环；LLM 输出非法 JSON
 * 时优雅降级为最终答案（按纯文本返回），绝不抛出中断业务。
 */
```

对应到代码，是三层：

**第一层：非法 JSON → 降级为最终答案（而不是失败）。**

```java
// ToolCallingLoop.java（循环体）
JsonNode json = tryParse(decision);
if (json == null || !json.hasNonNull("action")) {
    // 非法 JSON：降级为最终答案，绝不让业务失败
    log.warn("[ToolLoop] 第 {} 轮 LLM 输出非 JSON，按最终答案返回", i);
    return new LoopResult(decision, List.copyOf(toolCalls), i);
}
```

**这里的选择值得琢磨**：模型没按格式输出，说明它**已经给了文本回答**——那这段文本很可能就是有用内容（上面那个"我无法访问文件"就是从这里出去的）。把它当作最终答案返回，比抛异常让整次审查失败要好。

**代价是它把模型的"自言自语"也带进了结论**。所以调用方必须做过滤——这就是 `ToolEquippedAgent` 里 `parseFindings` 存在的理由（见 3.4）。

**第二层：工具执行异常 → 不炸循环。**

```java
private AgentTool.ToolResult safeExecute(AgentTool tool, JsonNode argsNode) {
    try {
        Map<String, Object> args = new LinkedHashMap<>();
        if (argsNode != null && argsNode.isObject()) {
            argsNode.properties().forEach(e -> args.put(e.getKey(), e.getValue().asText()));
        }
        return tool.execute(args);
    } catch (Exception e) {
        log.warn("[ToolLoop] 工具 {} 执行异常：{}", tool.name(), e.getMessage());
        return AgentTool.ToolResult.fail("工具执行异常: " + e.getMessage());
    }
}
```

**一个坏工具不该毁掉整个循环。** 而且失败信息会作为观察回到模型——它有机会换个方式再试。

**第三层：达到最大迭代 → 用已有观察给兜底结论。**

```java
String fallback = "已达最大迭代次数（" + maxIterations + "），基于已有观察给出当前结论：\n"
        + String.join("\n", transcript);
log.warn("[ToolLoop] 达到最大迭代 {}，返回兜底结论", maxIterations);
return new LoopResult(fallback, List.copyOf(toolCalls), maxIterations);
```

**兜底结论里带着完整 transcript。** 这不是给模型的，是给**人**看的——运维排障时，看到这行日志就知道"这次是循环没收敛"，以及它在前 N 轮里看到了什么。

顺便注意 `truncate`：

```java
private static String truncate(String s) {
    return s.length() <= 800 ? s : s.substring(0, 800) + "...(截断)";
}
```

**每一条观察都截到 800 字符。** 理由很实在：观察会累积进下一轮的 prompt，**不截断的话，第 5 轮的输入可能是第 1 轮的 5 倍**，token 成本和延迟都是指数级的。

### 3.2 `NativeToolCallingLoop.java`：原生路线的两个额外能力

**能力一：一次决策返回多个工具调用，并行执行。**

```java
// NativeToolCallingLoop.java
// 并行执行本轮的多个工具调用
List<Map<String, Object>> executedCalls = executeInParallel(r.toolCalls(), executed);
messages.add(NativeMessage.assistant(r.content(), r.toolCalls()));
for (Map<String, Object> ec : executedCalls) {
    messages.add(NativeMessage.tool((String) ec.get("toolCallId"), (String) ec.get("output")));
}
```

模型说"我要同时读这 5 个文件"，这里是**真并行**（`ExecutorService` 提交 5 个任务）。这就是原生协议相对 prompt-JSON 的实质优势——**prompt-JSON 模式每次只能决策一个动作，5 个文件要 5 轮**。

**能力二：异常隔离。**

```java
/** 并行执行一批工具调用（异常隔离：单工具失败不影响其余）。 */
private List<Map<String, Object>> executeInParallel(List<NativeToolCall> calls, List<String> executed) {
    List<Future<Map<String, Object>>> futures = new ArrayList<>();
    for (NativeToolCall call : calls) {
        futures.add(executor.submit(() -> runTool(call, executed)));
    }
    List<Map<String, Object>> out = new ArrayList<>();
    for (Future<Map<String, Object>> f : futures) {
        try {
            out.add(f.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.add(Map.of("toolCallId", "", "output", "工具执行被中断"));
        } catch (ExecutionException e) {
            out.add(Map.of("toolCallId", "", "output", "工具执行异常: " + e.getCause()));
        }
    }
    return out;
}
```

两个细节：

- **`f.get()` 逐个取，异常就地转成一条观察**——而不是让第一个失败把整批掀掉。这和"不炸循环"是同一个取向。
- **`InterruptedException` 里调了 `Thread.currentThread().interrupt()`**——把中断标志重新立起来再吞异常。

**最后这一点是很多人漏掉的**：吞掉 `InterruptedException` 而不恢复中断标志，会让上层（比如取消这次审查的请求）**永远收不到中断信号**。看着只是少了一行代码，实际是让"取消"这个功能静默失效。

还有一处值得学的记账方式：

<img class="mermaid-svg" src="/zh/book-assets/diag-0017.svg" alt="还有一处值得学的记账方式：" />


循环里逐轮累加：

```java
long totalIn = 0, totalOut = 0;
double totalCost = 0;
...
totalIn += r.inputTokens() == null ? 0 : r.inputTokens();
```

**把"整个循环消耗了多少 token / 多少钱"作为返回值的一部分返回。** 因为一次多轮工具调用可能花掉比单次调用高 5–10 倍的钱——**如果这个数字不暴露出来，成本失控你连看都看不到。**

### 3.3 `GuardedTool.java`：装饰器，工具本身零感知

```java
// GuardedTool.java
/**
 * <p>在注册处包装即可，工具本身无需感知：
 * <pre>
 * tools.register(new GuardedTool(new BuiltinTools.FileReadTool(baseDir)));
 * </pre>
 */
@Override
public ToolResult execute(Map<String, Object> args) {
    ToolSchemaValidator.Validation v = ToolSchemaValidator.validate(schema, args);
    if (!v.valid()) {
        return ToolResult.fail("参数校验未通过: " + v.reason());
    }
    return delegate.execute(args);
}
```

**装饰器模式在这里的价值是"可加可不加"**：注册处包一层就有护栏，不包就是裸工具。而工具实现类完全不需要知道护栏的存在——**新写一个工具不会忘记加护栏，因为护栏不在工具里。**

> 这个模式在第 03 讲出现过（DTO 自己声明命名策略），在第 04 讲出现过（供应商自己声明超时），这里是第三次。**"把横切关注点从实现里挪出去"，是这套代码里反复出现的同一个动作。**

### 3.4 `ToolEquippedAgent.java`：主线系统里，工具是"横切织入"的

看完了基座，看主线系统怎么用它。`code-review-agent` 里那个已经写好的 `LogicAgent` **一行都不用改**——工具能力是通过装饰器叠上去的：

```java
// ToolEquippedAgent.java
/**
 * 工具增强 Agent 装饰器（横切织入模式）：包装任意 {@link ReviewAgent}，
 * 在其执行审查前先经 {@link ToolCallingLoop} 收集情报（工具观察），
 * 再把观察中 LLM 给出的补充发现与委托 Agent 的结果**合并**返回。
 *
 * <p>委托 Agent 零改动；装饰器异常时退化为纯委托（可降级）。装配层按开关包装。
 */
public class ToolEquippedAgent implements ReviewAgent {

    @Override public boolean supports(List<CodeDiff> diffs, ReviewContext ctx) {
        return delegate.supports(diffs, ctx);
    }

    @Override public List<Finding> review(List<CodeDiff> diffs, ReviewContext ctx) {
        List<Finding> merged = new ArrayList<>(delegate.review(diffs, ctx));
        try {
            ...
            ToolCallingLoop.LoopResult r = loop.run(...);
            merged.addAll(parseFindings(r.answer()));
        } catch (Exception e) {
            // 可降级：工具循环失败不影响委托 Agent 的既有结果
        }
        return merged;
    }
}
```

四处要记住的：

1. **委托结果先拿，再叠加**：`new ArrayList<>(delegate.review(...))` 打头——**工具部分失败了，原有能力仍在**。
2. **整个工具段包在 try 里吞掉异常**：注释写明"可降级"。工具循环是**加法**，不是**前置依赖**。
3. **`supports()` 原样转发**：装饰器不改变准入语义（第 20 讲会详细讲 `supports()`）。
4. **diff 文本截到 6000 字符**再进工具循环——和第 3.1 节的 800 字符截断是同一个理由。

最后看它怎么**过滤模型的"自言自语"**：

```java
// ToolEquippedAgent.java
/** 宽松解析 LLM 工具循环结论中的 findings 数组（无 JSON / 解析失败返回空）。 */
private List<Finding> parseFindings(String answer) {
    try {
        String t = answer.trim();
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (s < 0 || e <= s) {
            return List.of();          // ← 没有 JSON 结构 → 不产出任何发现
        }
        JsonNode arr = mapper.readTree(t.substring(s, e + 1)).path("findings");
        if (!arr.isArray()) {
            return List.of();
        }
        ...
    } catch (Exception ex) {
        return List.of();
    }
}
```

**对照本章开头那个痛点**：模型说"我无法访问文件，建议开发者自行检查"——这段文本里**没有 `findings` 数组**，`parseFindings` 返回空列表，**这段话就进不了报告**。

> 这一层就是痛点的解药。而它的设计取向依然是那个反复出现的：**宽松解析、失败返空、绝不抛异常**。

### 3.5 装配：工具是"开关"不是"硬编码"

三个开关决定工具在这次审查里存不存在（`ToolGate` 的两个 `@Value`）：

```java
// ToolGate.java:43-44
public ToolGate(@Value("${review.tools.deferred-enabled:false}") boolean deferredEnabled,
                @Value("${review.tools.code-mode-enabled:false}") boolean codeModeEnabled)
```

**默认值全是 `false`**，而且写成了 `:` 后面的字面量——**不配置就是不开启**，不是"读不到配置就报错"。

这是部署形态上的一个好处：**同一个 jar，配置一改就从"保守审查"变成"激进审查"**，不需要重新打包，也不需要维护两个分支。

## 四、避坑清单

- [ ] **工具的 `description` 是写给模型看的**，必须回答"何时用、返回什么"。这一行决定模型选不选它。
- [ ] **工具数量要克制。** 给模型 30 个工具不如给 6 个描述精准的。
- [ ] **模型写错工具名时，返回"可纠正的观察"**（并在里面指回工具清单），不要抛异常。
- [ ] **参数解析失败、工具执行异常，一律转成观察回到模型**，绝不炸循环。
- [ ] **循环必须有硬上限**，撞上限时要有"基于已有观察的兜底结论"，而不是空返回。
- [ ] **历史观察必须截断**（这里是 800 字符 / 2000 字符）。不截断的循环，token 成本是指数级的。
- [ ] **把整轮循环的 token 与成本作为返回值暴露出来。** 多轮工具调用的成本可能是单次调用的 5–10 倍。
- [ ] **路径校验必须是 `resolve → normalize → startsWith`**，顺序不能变。少一步 `normalize`，`startsWith` 就可绕过。
- [ ] **重工具默认不暴露（fail-closed）**，且要记录"被拒绝的调用次数"——这个数字异常上涨是安全信号。
- [ ] **护栏用装饰器加在注册处**，不要写进每个工具实现里。写在实现里，新工具一定会漏。
- [ ] **`InterruptedException` 捕获后必须 `Thread.currentThread().interrupt()`**，否则"取消"会静默失效。
- [ ] **工具能力要做成可插拔的装饰器（`ToolEquippedAgent`）**，让原有 Agent 零改动，且**工具段失败不影响主能力**。
- [ ] **最终的 `findings` 一定要走结构化解析**。模型的自言自语（"我无法访问文件"）必须被解析层挡在报告之外。

## 五、动手任务

> **任务一：让模型犯三种错，观察每一层兜底怎么接住。**
>
> **仓库位置**：`agent-kit / src/test/.../toolcalling`
>
> **操作**：
> 1. 写一个**假的 `ChatModel`**（不用真调 LLM），按脚本依次返回：
>    - 第 1 轮：`这不是 JSON，我无法访问文件。`
>    - 第 2 轮：`{"action":"call_tool","tool":"read_file","arguments":{"path":"a.java"}}`（**工具名是错的**）
>    - 第 3 轮：`{"action":"call_tool","tool":"file_read","arguments":{"path":"../../etc/passwd"}}`
>    - 第 4 轮：`{"action":"finish","answer":"done"}`
> 2. 用这个假模型驱动 `ToolCallingLoop`（`maxIterations=5`），注册一个 `GuardedTool(new BuiltinTools.FileReadTool(baseDir))`。
>
> **预期结果**：
> - 第 1 轮：**循环立刻结束**，`LoopResult.answer` 就是那段"我无法访问文件"，`iterations == 1`。
> - 第 2 轮：观察是 `错误：工具 read_file 不存在，可用工具见清单`，**循环继续**（没有异常）。
> - 第 3 轮：观察是 `[工具错误] 参数校验未通过: ...` **或** `拒绝访问：路径越界 ../../etc/passwd`（取决于危险模式先命中哪一条）——**两次拦截叠在一起**。
> - 第 4 轮：正常结束，`toolCalls.size()` 记录了被抓到的调用次数。
>
> **思考题**：把第 1 轮的脚本改成合法 JSON 但 `action` 未知（如 `{"action":"think"}`），会发生什么？**读一遍 `ToolCallingLoop` 的判断顺序**——`hasNonNull("action")` 通过、`finish` 不匹配、于是走 `call_tool` 分支、工具名为空 → `registry.get("")` 返回 null → 又变成一条"可纠正的观察"。**你会看到"宽松解析"是怎么把一次意外变成一次正常迭代的。**
>
> **任务二：把重工具的默认值翻过来，观察安全边界。**
> 1. 用 `new ToolGate(true)` 构造（`deferredEnabled=true`）。
> 2. 分别以 `ReviewProfile.ADVISORY` 和 `ReviewProfile.STRICT` 调 `allows("autofix.apply", ToolExposure.DEFERRED, profile)`。
> 3. 再用 `new ToolGate(false)` 各调一次，并读 `stats("autofix.apply")`。
>
> **预期结果**：`deferredEnabled=true` 时两种 profile 都放行；`false` 时只有 `STRICT` 放行；而 `stats` 把**每一次拒绝都记了下来**——即使调用方从没检查过返回值。**这就是"审计重工具调用尝试"的实现方式。**

---

## 本讲小结

1. **Function Calling 有两条路线**：prompt-JSON（通用但脆）与原生 tools（可靠、可并行、依赖模型支持）。**两条都实现，优先原生、显式降级**，是工程上的正确组合。
2. 模型能看到的只有 `name` / `description` / `parameterSchema` 三个字符串，其中 **`description` 决定它用不用这个工具**。
3. **"模型选不中工具"的修法是把错误变成可纠正的观察**，而不是抛异常。
4. 循环必须有**三层兜底**：非法 JSON 降级为答案、工具异常不炸循环、最大迭代给兜底结论。观察要截断，成本要暴露。
5. **工具是"模型能对外界做的事"**，护栏分三层：参数危险模式拦截、工具自身路径边界、**重工具 fail-closed 不暴露**（并审计被拒绝的尝试）。

下一讲我们处理"模型说人话"的另一个后果：**结构化输出失败时，是重试、降级，还是放弃。**
