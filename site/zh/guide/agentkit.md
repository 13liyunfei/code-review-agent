# 基于 agent-kit 构建

本项目是 [`agent-kit`](https://gitee.com/liyunfei2030/agent-kit)（发布于 Maven Central 的多 Agent 通用能力库，坐标 `io.github.13liyunfei:agent-kit`）的**首个生产级落地用户**。

这层关系值得说清楚，因为很容易夸大：agent-kit **本就是从本仓库抽离出去的**，抽离之后本仓库**反向依赖**它——这正是 agent-kit 包名仍是 `com.codereview.kit` 的原因。

## 架构锚点：接口级绑定

"基于基座"只有在**接口级绑定**时才成立，而不是"调用了几个工具类"。本仓库是前者：

| 锚点 | 位置 |
|------|------|
| `LlmClient extends com.codereview.kit.ChatModel` | `core/llm/LlmClient.java:10` |
| `Finding implements com.codereview.kit.eval.FindingLike` | `core/model/Finding.java:34` |

第一条最关键：**整个引擎的模型边界就是 agent-kit 的接口**。所有 Agent、协调器、工具循环拿到的都是 `ChatModel`。换掉实现（OpenAI / 私有化模型 / 企业网关），上层九个模块零改动。

## 已落地能力（8 / 17 个包）

| 能力 | 落地位置 | 用法 |
|------|---------|------|
| **toolcalling** | `core/toolcalling/ToolEquippedAgent.java`<br>`ReviewAgentConfig:397-402` | 装饰器模式包装任意 `ReviewAgent`，审查前先跑 `ToolCallingLoop` 收集情报，把循环发现与委托结果合并。注册 `CurrentTimeTool`/`RegexScanTool`/`FileReadTool`（后者限定白名单根目录、拒绝路径穿越）。循环失败降级为纯委托 |
| **planning** | `core/planning/TaskPlanningSupport.java`<br>`ReviewAgentConfig:462-463` | 可选开启：目标拆成任务 DAG，按负责人路由，拓扑并行执行。解析失败降级为单任务直通 |
| **eval** | `GiteaConfig.java:88`<br>`GiteaReviewService.java` | 每次审查后 `LlmJudge` 基于 ground-truth 计算 precision/recall/F1，并跑 llm-as-judge 标记误报 |
| **extension** | 装配层 | 内置行为全部可替换；同名注册即覆盖，按 `order()` 排序织入 |
| **security** | `core/security/KeywordInjectionDetector.java` | 基座通用模式库 + 领域正则两层检测（见下文） |
| **struct** | `core/agent/AbstractReviewAgent.java`<br>（`llmFindings`） | 结构化输出的第二条通路：AiServices 不可用或返回为空时启用。schema 由 `ReviewResultDto` **类型自动推导**（含嵌套 `List<ReviewFindingDto>`），本仓库不维护任何 schema 定义；失败时复用已拿到的原始输出回退 `LlmFindingParser`，不额外多调一次模型 |
| **obs** | `core/llm/LoggingChatModelListener.java`<br>`config/ReviewAgentConfig.java`（`llmTracer` bean） | 模型边界的 LangChain4j 监听器把每次调用**翻译成 `GenAiSpan`**，带上业务 `traceId` 与模型回传的真实 token 用量；记录 / 聚合 / 成本核算全部交给基座，本仓库不自建指标体系 |
| **model** | `core/llm/LlmClient.java` | 见架构锚点 |

## 刻意不采纳的能力（9 / 17 个包）

采纳一个库，不等于把所有关注点都绕经它。当本仓库已有更强或更贴合场景的实现时，换成通用实现是**降级**。

需要说明的是：**"刻意不采纳"不是永久判决，而是当前能力的快照。** 下面第三、四条经验分别记录了 `struct` 与 `obs` 是如何从不采纳翻转为采纳的——判断依据变了（基座补齐了能力），结论就该跟着变。

| 不采纳 | 本仓库方案 | 理由 |
|--------|-----------|------|
| `rag` / `memory` | `core/rag/`、`core/memory/`（pgvector） | 需要 pgvector 持久化、`__global__` 全局基线叠加、混合检索与重排——库里只有内存实现 |
| `hitl` | 11 状态 × 19 转移工作流引擎 | 人工审批只是更大闭环的一小部分，该闭环还包含返工、抽检、异常恢复与误报反馈 |
| `router` | `ModelGateway` → TokenHub | 多模型网关 + 配额限流 + 4 级降级链（Agent → 编排 → 规则 → 人工） |
| `checkpoint` | `core/resume/ResumeState` | 这里的续跑语义是"审查进度"，不是通用快照 |
| `graph` / `agent` | 星型拓扑 `CompletableFutureCoordinator` | 固定 5 Agent 扇出 + 聚合仲裁，不是通用图编排 |
| `session` | LangChain4j `ChatMemory` | 审查是无状态批处理；记忆按 `Agent-团队-PR` 键隔离 |
| `mcp` / `stream` / `model.native` | — | 暂无场景需求 |

## 四条适配经验

### 基座的 LOW 风险等级会造成大规模误杀

agent-kit 的 `PromptInjectionDetector` 把 `override`、`act as` 等判为 `Risk.LOW`。**但 Java 代码里 `@Override` 无处不在。** 在普通 Java diff 上实测：

```
输入：+    @Override
     +    public String toString() { return "x"; }

基座判定：risk=LOW  matched=[override]
```

若把 `flagged()`（LOW 或 HIGH）当作命中，**几乎所有 Java PR 都会被判为注入攻击而拦截**。因此本仓库**仅将 `Risk.HIGH` 升级为拦截**，LOW 视为可疑但不拦截。该决策由 `normalJavaOverrideAnnotationIsNotFlagged()` 回归测试锁死。

> 采纳基座的默认分级前，务必先在你的语料上实测误杀率。

### 字面量匹配覆盖不到变体，需领域正则兜底

基座用字面量子串匹配，例如收录了 `忽略以上所有指令`。真实攻击写成 **`忽略以上指令`**（少了"所有"）就直接漏了。因此在其上保留一层领域正则：

```java
// 第一层：agent-kit 基座（仅 HIGH 拦截）
if (kitDetector.detect(input).risk() == Risk.HIGH) return true;
// 第二层：领域正则，补齐基座漏掉的变体
return DOMAIN_PATTERNS.stream().anyMatch(p -> p.matcher(input).find());
```

### 结构化输出：与其为不采纳找理由，不如把基座补强到够用

agent-kit 原本提供 `StructuredChatModel`，但 0.1.0 版本相对本仓库需求有四个硬伤——第一反应是"继续用 AiServices，把理由写进文档"。**这个反应是错的。正确的做法是把基座补到够用**，agent-kit 0.1.1 就是这么做的：

| 0.1.0 的不足 | 0.1.1 补齐的能力 |
|---|---|
| schema 必须手写 `Map` | `JsonSchemas.fromType(Class)` 用 Jackson 内省自动推导：嵌套 Bean、`List<T>`、枚举，带深度保护 |
| 解析失败直接抛异常 | `StructuredResult<T>` 携带 `value`/`rawResponse`/`attempts`/`error`，不抛异常 |
| 重试只是原样重发 | 重试时把上一次的错误输出**和失败原因**一起回喂给模型 |
| 没有记忆集成 | `chatWithSession(ChatSession, ...)` 注入历史，且仅在成功时回写 |

`llmFindings()` 现在是三级梯度，且每降一级都不会多花一次模型调用：

```
1. LangChain4j AiServices        —— schema 绑定 + ChatMemory（可用时首选）
2. agent-kit StructuredChatModel —— 类型推导 schema，零框架依赖
3. LlmFindingParser              —— 文本解析，直接复用手里已有的原始输出
```

> 当落地项目的实现比基座更强时，先问一句：**这是业务护城河，还是基座的能力缺口？** 前者留在业务侧（上面的 `rag`/`hitl`/`router` 即属此类）；后者应当**反向补进基座**——本次的 `struct` 就是如此。

### LLM 调用追踪：基座不该重复造链路上下文，而该接受业务侧的 traceId

这一条同样经历了从"不采纳"到"采纳"的翻转。原版 `obs` 有四个硬伤：
`GenAiSpan` 没有 `traceId`（5 个 Agent 并行扇出，调用散落各线程，无法归到同一次审查）；
**异常时不记录 span**（恰恰漏掉最该被观测的失败调用）；
`stream()` 完全绕过 tracer；`AggregateTracer` 只有一个总调用数（分不出成功 / 失败，也没法按操作名拆）。

agent-kit 0.1.1 补齐了全部四项——其中值得一提的是新增 `TraceIdSupplier` 而非自带链路上下文：
**traceId 的生成与跨线程传播（MDC、线程池复用、父子线程恢复）是业务侧关注点，基座只负责"把你已有的 traceId 记进 span"。**

结果是清晰的分工，而不是二选一：

```
业务侧：  TraceContext（MDC traceId）   ReviewTrajectoryRecorder（JSONL 可回放）
                        \                    /
基座侧：                 GenAiSpan ──► AggregateTracer / LoggingGenAiTracer
```

> 判断"该不该采纳"时，别拿基座的**整体**去比业务的**整体**。拆到关注点粒度再判断：
> 通用件交给基座，差异化留在业务，两边用 traceId 这样的**最小契约**缝合，而不是互相覆盖。

## 自行验证

```bash
grep -n -A3 "agent-kit" pom.xml                        # 依赖声明
grep -rn "com.codereview.kit" src/ --include="*.java"  # 使用点
ls src/main/java/com/codereview/kit                    # 不应存在（无内联遮蔽）
./mvnw -o test -Dtest='KeywordInjectionDetectorTest'   # security 适配回归
./mvnw -o test -Dtest='AgentKitStructuredOutputTest'   # struct 适配回归
./mvnw -o test -Dtest='AgentKitLlmTracingTest'         # obs 适配回归
```
