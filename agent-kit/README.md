# agent-kit

多 Agent 通用能力积木 —— 开箱即用、以 Maven 组件方式引入、扩展点自定义。

纯 Java 17，零框架依赖（仅 jackson-databind + slf4j），任何多 Agent 项目可直接引入。

## 引入

```xml
<dependency>
    <groupId>com.codereview</groupId>
    <artifactId>agent-kit</artifactId>
    <version>0.1.0</version>
</dependency>
```

## 组件清单

| 组件 | 包 | 能力 |
| --- | --- | --- |
| Tool Calling 决策循环 | `com.codereview.kit.toolcalling` | `AgentTool` + `ToolRegistry` + `ToolCallingLoop`（思考→决策→调用→观察→推理；最大迭代防死循环、非法 JSON 降级、工具异常隔离）+ 内置工具（`current_time` / `regex_scan` / `file_read`·防路径穿越） |
| 任务拆解 DAG | `com.codereview.kit.planning` | `TaskPlanner`（LLM 结构化拆解，失败降级直通）+ `TaskPlan`（id 唯一/依赖存在/Kahn 无环）+ `DagExecutor`（拓扑并行，上游失败下游跳过） |
| LLM 评估 | `com.codereview.kit.eval` | `LlmJudge`：ground-truth precision/recall/F1 + llm-as-judge 逐条真假阳性判定（LLM 失败自动跳过） |
| 扩展点机制 | `com.codereview.kit.extension` | `ExtensionPoint`（order 织入序/同名覆盖/线程安全）+ `ExtensionRegistry` + 5 个 SPI 契约 |

## 唯一模型边界：ChatModel

kit 不依赖任何具体 LLM 供应商，只认一个单方法接口：

```java
public interface ChatModel { String chat(String prompt); }
```

你的项目一行适配即可接入（示例：接自研网关 / OpenAI SDK / 内部 MaaS）：

```java
ChatModel model = prompt -> myLlmGateway.chat(prompt); // 你的实现
```

## 扩展点（使用方自定义扩展）

实现 SPI 接口 → 注册到 `ExtensionRegistry` → 按 `order()` 升序织入（标准实现用大 order，自定义用小 order 叠加）。

| SPI | 作用 | 关键方法 |
| --- | --- | --- |
| `LlmInterceptor` | LLM 调用前置/后置（防注入 / 审计 / 纠偏） | `String before(prompt)` / `String after(prompt, response)` |
| `RagEnhancer<T>` | 检索结果增强（重排 / 去重 / 注入知识库） | `List<T> enhance(hits, query)` |
| `AgentProvider<A>` | 提供领域 Agent 实例 | `List<A> provide()` |
| `MemoryStrategy` | 记忆读写策略替换 | `Optional<String> get(key)` / `put(key, value)` |
| `StageHook` | 工作流阶段回调（追踪 / 轨迹 / 审计） | `void onStage(stage, ctx)` |

```java
ExtensionRegistry registry = new ExtensionRegistry();
registry.register(LlmInterceptor.class, new MyAuditInterceptor()); // 自定义扩展
List<LlmInterceptor> chain = registry.list(LlmInterceptor.class);   // 按 order 取链
```

## 最小使用示例

```java
// 1. 工具调用循环
ToolRegistry tools = new ToolRegistry();
tools.register(new BuiltinTools.CurrentTimeTool());
ToolCallingLoop loop = new ToolCallingLoop(model, tools, 5);
ToolCallingLoop.LoopResult r = loop.run("分析这段日志", context, null);

// 2. 任务拆解 + DAG 执行
TaskPlanner planner = new TaskPlanner(model);
TaskPlan plan = planner.plan("审查 PR #42", List.of("Logic", "Security"));
Map<String, DagExecutor.TaskResult> out = new DagExecutor(executor)
        .execute(plan, node -> runAgent(node.assignee(), node.description()));

// 3. 评估
LlmJudge<MyFinding> judge = new LlmJudge<>(model);
LlmJudge.EvalResult er = judge.evaluate(findings, groundTruth);
```

## 测试

```bash
mvn -f agent-kit/pom.xml test   # 13 例：循环语义 / DAG 拓扑与环拒绝 / 评估精确匹配 / 扩展点织入与覆盖
```
