# agent-kit 落地清单

> 本文档回答一个问题：**code-review-agent 到底在多大程度上"基于 agent-kit"？**
>
> 逐项列出 agent-kit 的每一项能力在本仓库的落地位置（或刻意不采纳的理由），
> 并给出可验证的代码位置。这不是宣传文案，而是可被 `grep` 验证的事实清单。

- agent-kit：`io.github.13liyunfei:agent-kit:0.1.1`（Maven Central）
- 本仓库声明位置：`pom.xml` 根 `<dependencies>`
- 包名说明：agent-kit 的包名为 `com.codereview.kit.*`——它本就是从本仓库抽离出去的，
  抽离后本仓库**反向依赖**它，形成"业务系统 ↔ 能力基座"的闭环。

---

## 一、架构锚点：模型边界与领域模型都绑定在 agent-kit 上

这是"基于基座"最实质的证据——不是调用几个工具类，而是**接口级绑定**：
本仓库的模型抽象与核心领域类型，直接继承 / 实现 agent-kit 的接口。

| 锚点 | 代码位置 | 含义 |
|------|---------|------|
| `LlmClient extends com.codereview.kit.ChatModel` | `core/llm/LlmClient.java:10` | **整个引擎的模型边界就是 agent-kit 的接口**。所有 Agent、协调器、工具循环拿到的都是 `ChatModel` |
| `Finding implements com.codereview.kit.eval.FindingLike` | `core/model/Finding.java:34` | 核心领域类型实现 kit 的评估接口，因此能直接喂给 `LlmJudge` 做 precision / recall |

由此产生的效果：换掉 `LlmClient` 的实现（OpenAI / 私有化 / 企业网关），
上层 9 个模块零改动——这正是基座化想要的结果。

---

## 二、已落地能力（8 / 17 个包）

| agent-kit 能力 | 落地位置 | 具体用法 |
|----------------|---------|---------|
| **toolcalling**<br>`ToolCallingLoop` / `ToolRegistry` / `BuiltinTools` | `core/toolcalling/ToolEquippedAgent.java`<br>`config/ReviewAgentConfig.java:397-402` | 用**装饰器模式**包装任意 `ReviewAgent`，审查前先跑工具循环收集情报；注册 `CurrentTimeTool` / `RegexScanTool` / `FileReadTool`（后者限定白名单根目录，拒绝路径穿越）。循环失败静默降级为纯委托 |
| **planning**<br>`TaskPlanner` / `TaskPlan` / `DagExecutor` | `core/planning/TaskPlanningSupport.java`<br>`config/ReviewAgentConfig.java:462-463` | 可选开启：把审查目标拆成任务 DAG，按负责人路由子任务并拓扑并行执行。解析失败降级为单任务直通 |
| **eval**<br>`LlmJudge` | `integration/gitea/GiteaConfig.java:88`<br>`integration/gitea/GiteaReviewService.java` | 每次审查后基于 ground-truth 计算 precision / recall / F1，并对发现跑 llm-as-judge 标记误报 |
| **extension**<br>`ExtensionRegistry` / `ExtensionPoint` | 装配层 | 内置行为全部可替换，同名注册即覆盖，按 `order()` 排序织入 |
| **security**<br>`PromptInjectionDetector` | `core/security/KeywordInjectionDetector.java` | **基座通用模式库 + 本仓库领域正则增强**的两层检测（详见第四节） |
| **struct**<br>`StructuredChatModel` / `JsonSchemas` | `core/agent/AbstractReviewAgent.java`<br>（`llmFindings`） | 结构化输出第二条通路：AiServices 不可用或其返回为空时启用。schema 由 `ReviewResultDto` **类型自动推导**（含嵌套 `List<ReviewFindingDto>`），本仓库不维护任何 schema 定义；失败复用原始输出回退 `LlmFindingParser`，不额外多调一次模型 |
| **obs**<br>`GenAiSpan` / `GenAiTracer` / `AggregateTracer` | `core/llm/LoggingChatModelListener.java`<br>`config/ReviewAgentConfig.java`（`llmTracer` bean） | 模型边界的 LangChain4j 监听器把每次调用**翻译成 `GenAiSpan`**（带上业务 `traceId` 与模型回传的真实 token 用量），记录 / 聚合 / 成本核算全部交给基座。本仓库不自建指标体系 |
| **model**<br>`ChatModel` | `core/llm/LlmClient.java` | 见架构锚点 |

---

## 三、刻意不采纳的能力（9 / 17 个包）及理由

> **原则：不为"用而用"。** 当本仓库已有更强或更贴合场景的实现时，
> 强行替换成 agent-kit 的通用实现是**降级**，不是落地。

| agent-kit 能力 | 本仓库方案 | 不采纳的理由 |
|----------------|-----------|-------------|
| `rag` / `memory` | `core/rag/` + `core/memory/`<br>pgvector + 团队隔离 | 本仓库要的是 **pgvector 持久化 + `__global__` 全局基线叠加 + 混合检索 + 重排**（`ApiReranker` / `HeuristicReranker`）。kit 提供的是内存实现，能力子集 |
| `hitl` | 11 状态 × 19 转移<br>工作流状态机 | 人工审批只是本仓库人机协作的一小部分；已有更完整的**返工闭环 / 抽检 / 异常通道 / 误报反馈** |
| `router` | `ModelGateway` → TokenHub | 已有多模型网关 + 配额限流 + **4 级降级链**（`core/degrade/DegradationChain`：Agent → 编排 → 规则 → 人工） |
| `checkpoint` | `core/resume/ResumeState`<br>+ `FileResumeStore` | 已有断点续跑，且状态语义是"审查进度"而非通用快照 |
| `graph` / `agent` | 星型拓扑 `CompletableFutureCoordinator` | 本仓库是**固定 5 Agent 并行 + 聚合去重仲裁**的星型结构，不是通用图编排；专用协调器更简单可测 |
| `session` | LangChain4j `ChatMemory` | 审查是**无状态批处理**，会话记忆由 AiServices 的 ChatMemory 按 `Agent-团队-PR` 键隔离提供 |
| `mcp` / `stream` / `model.native` | — | 暂无场景需求 |

**结论**：8 项深度落地（含 2 个接口级锚点）+ 9 项有理由的刻意不采纳。
这是"能力基座"与"业务系统"应有的关系——**基座提供通用件，业务保留自己的护城河**。

需要强调的是：**"刻意不采纳"不是永久判决，而是当前能力的快照。**
下面第四节 4.3、4.4 两条分别记录了 `struct` 与 `obs` 是如何从不采纳翻转为采纳的——
判断依据变了（基座补齐了能力），结论就应当跟着变。

---

## 四、落地适配经验（四条真实踩坑）

### 4.1 基座的 LOW 风险等级会在代码审查场景造成大规模误杀

agent-kit 的 `PromptInjectionDetector` 把 `override`、`act as`、`假装你是` 等判为 `Risk.LOW`。

**但 Java 代码里 `@Override` 注解无处不在。** 实测：

```
输入：@@ -1,3 +1,4 @@
     +    @Override
     +    public String toString() { return "x"; }

基座判定：risk=LOW  matched=[override]
```

若把 `flagged()`（LOW 或 HIGH）当作命中，**几乎所有 Java PR 都会被判为注入攻击而拦截**。

**本仓库的适配**：只有 `Risk.HIGH` 升级为拦截，LOW 视为可疑但**不拦截**。
该决策已由 `src/test/java/com/codereview/agent/core/security/KeywordInjectionDetectorTest.java`
中的 `normalJavaOverrideAnnotationIsNotFlagged()` 回归测试锁死。

> 教训：**采纳基座的默认分级前，必须先在你的语料上验证误杀率。**

### 4.2 基座的字面量匹配覆盖不到变体，需领域正则兜底

基座用 `contains` 字面量匹配，例如收录了 `忽略以上所有指令`。
但真实攻击写成 **`忽略以上指令`**（少了"所有"）就漏了。

**本仓库的适配**：保留一层领域正则（容忍空白与可选词），作为基座的补充：

```java
// 第一层：agent-kit 基座（仅 HIGH 拦截）
if (kitDetector.detect(input).risk() == Risk.HIGH) return true;
// 第二层：领域正则增强，补齐基座漏掉的变体
return DOMAIN_PATTERNS.stream().anyMatch(p -> p.matcher(input).find());
```

### 4.3 结构化输出：与其为不采纳找理由，不如把基座补强到够用

这一条的结论**变过一次**，值得完整记录。

**最初的判断：不采纳。** 原版 `StructuredChatModel` 有四个硬伤，
每一条都足以让它在审查链路里不可用：

| 短板 | 为什么在本场景不可接受 |
|------|----------------------|
| 要求调用方**手工拼** `Map<String,Object>` schema | 审查结果是嵌套结构（`ReviewResultDto` 内含 `List<ReviewFindingDto>`）。手写 schema 一旦与 DTO 脱节，错误往往要到线上才暴露 |
| 失败即 `throw IllegalStateException` | 审查系统要的是"结构化失败就回退文本解析"，异常会打断主流程；而且抛出后**原始输出丢失**，想兜底都拿不到文本 |
| 无会话记忆 | AiServices 的 `ChatMemory` 可按 `Agent-团队-PR` 隔离上下文，原版完全没有 |
| 重试**原样重发**同一提示词 | 模型大概率给出同样的错误结果，重试次数形同虚设 |

**正确的做法不是写一份理由存档，而是把基座补上。**（agent-kit `0.1.1`）：

| 补齐的能力 | 解决什么 |
|-----------|---------|
| `JsonSchemas.fromType(Class)` | 用 Jackson 内省从类型推导 schema：嵌套 record、`List<T>`、数组、枚举（展开为取值列表）全自动，改 DTO 即同步 |
| `StructuredResult<T>` | 非抛出式结果，携带 `value` / `rawResponse` / `attempts` / `error`。失败时原始输出仍在，可直接回退文本解析 |
| `chatWithSession(ChatSession, ...)` | 结构化输出与多轮会话结合，成功后本轮对话自动写回会话；**失败时不写入**（不污染记忆） |
| 重试**回灌上一次的错误输出与失败原因** | 让模型知道错在哪，而不是盲重试 |
| 宽容解析 | 自动剥离 Markdown 代码块围栏与前后闲聊文本 |

**于是结论翻转为：采纳。** 现在 `llmFindings` 有三层通路：

```
1. LangChain4j AiServices（已配置时优先，带 ChatMemory）
      ↓ 不可用 / 返回空
2. agent-kit StructuredChatModel（schema 由类型推导，零框架依赖）
      ↓ 解析失败 → 复用其原始输出
3. LlmFindingParser 文本解析（最终兜底）
```

第 2 层失败时**复用已拿到的原始输出**给第 3 层，
因此兜底不会再多调一次模型——这是"降级不增成本"的关键细节。

> **方法论：** 当落地项目的实现比基座更强时，先问
> **"这是业务护城河，还是基座的能力缺口？"**
> 前者留在业务侧（如本仓库的 pgvector RAG、11 状态工作流）；
> 后者应当**反向补进基座**（如本次的 struct），
> 让下一个使用方不必再写一份同样的理由。

### 4.4 LLM 调用追踪：基座不该重复造链路上下文，而该接受业务侧的 traceId

同样经历了从"不采纳"到"采纳"的翻转。

**最初的判断：不采纳。** 原版 `obs` 有四个硬伤：

| 短板 | 为什么在本场景不可接受 |
|------|----------------------|
| `GenAiSpan` 只有 `spanId` / `parentId`，**没有 traceId** | 审查是 5 个 Agent 并行扇出，调用散落在不同线程。没有 traceId 就无法回答"这些调用属于同一次审查" |
| **异常时不记录任何 span** | 恰恰漏掉了最该被观测的那部分——失败调用在指标里完全不可见 |
| `stream()` **完全绕过 tracer** | 流式路径是观测盲区 |
| `AggregateTracer` **只有一个总调用数** | 无法区分成功 / 失败，也无法按操作名拆分；token / 延迟只能自己再算一遍 |

**补齐方式（agent-kit `0.1.1`）：**

| 补齐的能力 | 解决什么 |
|-----------|---------|
| `GenAiSpan` 新增 `traceId` / `model` / `error` / `attributes` | 一次审查的并行调用可聚合；失败原因可回溯 |
| `TraceIdSupplier` | **基座刻意不自带链路上下文**——traceId 的生成与跨线程传播（MDC、线程池复用、父子线程恢复）是业务侧关注点，基座只负责"把你已有的 traceId 记进 span" |
| `TracedChatModel` 失败留痕 + 流式观测 + token 估算可替换 | 失败调用与流式调用都进入指标 |
| `AggregateTracer` 新增错误数 / 按操作分组 / 快照 / 重置 | 直接回答"调了几次、失败几次、多少 token、平均多慢" |
| `GenAiTracer.composite(...)` | 多个 tracer 并行消费，任一故障不影响主链路 |

**于是结论翻转为：采纳，但分工明确。**

```
LangChain4j 模型边界（LoggingChatModelListener）
        │  翻译：带上 TraceContext 的 traceId + 模型回传的真实 token 用量
        ▼
agent-kit GenAiSpan ──► AggregateTracer（指标）
                    └──► LoggingGenAiTracer（明细，可选）
```

这里的关键设计是**职责切分而非二选一**：

- **留在业务侧**：`TraceContext`（MDC traceId + 跨线程恢复语义）、
  `ReviewTrajectoryRecorder`（JSONL 轨迹可回放）、`ModelGateway` 4 级降级链。
  这些是"审查可复盘"的护城河。
- **交给基座**：span 的结构、指标聚合、成本核算。
  这些是每个 Agent 系统都要重写一遍的通用件。

> **可推广的经验：** 判定"该不该采纳"时，不要拿基座的**整体**去比业务的**整体**。
> 拆到关注点粒度再判断：通用件交给基座，差异化留在业务——
> 两边通过 traceId 这样的**最小契约**缝合，而不是互相覆盖。

---

## 五、如何验证本文档

```bash
# 1. 确认依赖声明
grep -n -A3 "agent-kit" pom.xml

# 2. 确认全部引用点（应只在 main + test，无内联 kit 源码）
grep -rn "com.codereview.kit" src/ --include="*.java"

# 3. 确认无内联遮蔽
ls src/main/java/com/codereview/kit   # 应报"不存在"

# 4. 跑适配回归测试（安全 / 结构化输出 / 调用追踪）
./mvnw -o test -Dtest='KeywordInjectionDetectorTest'
./mvnw -o test -Dtest='AgentKitStructuredOutputTest'
./mvnw -o test -Dtest='AgentKitLlmTracingTest'
```
