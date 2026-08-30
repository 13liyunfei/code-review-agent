# agent-kit 落地清单

> 本文档回答一个问题：**code-review-agent 到底在多大程度上"基于 agent-kit"？**
>
> 逐项列出 agent-kit 的每一项能力在本仓库的落地位置（或刻意不采纳的理由），
> 并给出可验证的代码位置。这不是宣传文案，而是可被 `grep` 验证的事实清单。

- agent-kit：`io.github.13liyunfei:agent-kit:0.1.0`（Maven Central）
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

## 二、已落地能力（6 / 17 个包）

| agent-kit 能力 | 落地位置 | 具体用法 |
|----------------|---------|---------|
| **toolcalling**<br>`ToolCallingLoop` / `ToolRegistry` / `BuiltinTools` | `core/toolcalling/ToolEquippedAgent.java`<br>`config/ReviewAgentConfig.java:397-402` | 用**装饰器模式**包装任意 `ReviewAgent`，审查前先跑工具循环收集情报；注册 `CurrentTimeTool` / `RegexScanTool` / `FileReadTool`（后者限定白名单根目录，拒绝路径穿越）。循环失败静默降级为纯委托 |
| **planning**<br>`TaskPlanner` / `TaskPlan` / `DagExecutor` | `core/planning/TaskPlanningSupport.java`<br>`config/ReviewAgentConfig.java:462-463` | 可选开启：把审查目标拆成任务 DAG，按负责人路由子任务并拓扑并行执行。解析失败降级为单任务直通 |
| **eval**<br>`LlmJudge` | `integration/gitea/GiteaConfig.java:88`<br>`integration/gitea/GiteaReviewService.java` | 每次审查后基于 ground-truth 计算 precision / recall / F1，并对发现跑 llm-as-judge 标记误报 |
| **extension**<br>`ExtensionRegistry` / `ExtensionPoint` | 装配层 | 内置行为全部可替换，同名注册即覆盖，按 `order()` 排序织入 |
| **security**<br>`PromptInjectionDetector` | `core/security/KeywordInjectionDetector.java` | **基座通用模式库 + 本仓库领域正则增强**的两层检测（详见第四节） |
| **model**<br>`ChatModel` | `core/llm/LlmClient.java` | 见架构锚点 |

---

## 三、刻意不采纳的能力（11 / 17 个包）及理由

> **原则：不为"用而用"。** 当本仓库已有更强或更贴合场景的实现时，
> 强行替换成 agent-kit 的通用实现是**降级**，不是落地。

| agent-kit 能力 | 本仓库方案 | 不采纳的理由 |
|----------------|-----------|-------------|
| `struct`<br>`StructuredChatModel` | LangChain4j AiServices<br>（`core/llm/aiservice/`） | AiServices 提供真正的 schema 绑定 + **ChatMemory 短期记忆**，比"提示词要求返回 JSON + 重试"更可靠。且已有文本解析兜底（失败 → `LlmFindingParser`） |
| `rag` / `memory` | `core/rag/` + `core/memory/`<br>pgvector + 团队隔离 | 本仓库要的是 **pgvector 持久化 + `__global__` 全局基线叠加 + 混合检索 + 重排**（`ApiReranker` / `HeuristicReranker`）。kit 提供的是内存实现，能力子集 |
| `hitl` | 11 状态 × 19 转移<br>工作流状态机 | 人工审批只是本仓库人机协作的一小部分；已有更完整的**返工闭环 / 抽检 / 异常通道 / 误报反馈** |
| `obs` | `core/trace/TraceContext` +<br>`core/trajectory/ReviewTrajectoryRecorder` +<br>`LoggingChatModelListener` | 已有全链路 traceId（12 位 hex 写 MDC，跨 Agent 线程传播）+ **JSONL 轨迹可回放** + 网关级请求日志，比 span 级追踪更贴合"审查可复盘"的诉求 |
| `router` | `ModelGateway` → TokenHub | 已有多模型网关 + 配额限流 + **4 级降级链**（`core/degrade/DegradationChain`：Agent → 编排 → 规则 → 人工） |
| `checkpoint` | `core/resume/ResumeState`<br>+ `FileResumeStore` | 已有断点续跑，且状态语义是"审查进度"而非通用快照 |
| `graph` / `agent` | 星型拓扑 `CompletableFutureCoordinator` | 本仓库是**固定 5 Agent 并行 + 聚合去重仲裁**的星型结构，不是通用图编排；专用协调器更简单可测 |
| `session` | LangChain4j `ChatMemory` | 审查是**无状态批处理**，会话记忆由 AiServices 的 ChatMemory 按 `Agent-团队-PR` 键隔离提供 |
| `mcp` / `stream` / `model.native` | — | 暂无场景需求 |

**结论**：6 项深度落地（含 2 个接口级锚点）+ 11 项有理由的刻意不采纳。
这是"能力基座"与"业务系统"应有的关系——**基座提供通用件，业务保留自己的护城河**。

---

## 四、落地适配经验（三条真实踩坑）

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

### 4.3 结构化输出：选择更强的那个，而不是"自己人"

agent-kit 提供 `StructuredChatModel`，本仓库却用 LangChain4j AiServices。
**这是有意为之**——评判标准是能力而非血缘（见第三节）。
基座的价值在于"需要时唾手可得"，不在于"所有东西都必须用它"。

---

## 五、如何验证本文档

```bash
# 1. 确认依赖声明
grep -n -A3 "agent-kit" pom.xml

# 2. 确认全部引用点（应只在 main + test，无内联 kit 源码）
grep -rn "com.codereview.kit" src/ --include="*.java"

# 3. 确认无内联遮蔽
ls src/main/java/com/codereview/kit   # 应报"不存在"

# 4. 跑安全适配回归测试
./mvnw -o test -Dtest='KeywordInjectionDetectorTest'
```
