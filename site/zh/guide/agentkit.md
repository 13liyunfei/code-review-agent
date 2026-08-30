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

## 已落地能力（6 / 17 个包）

| 能力 | 落地位置 | 用法 |
|------|---------|------|
| **toolcalling** | `core/toolcalling/ToolEquippedAgent.java`<br>`ReviewAgentConfig:397-402` | 装饰器模式包装任意 `ReviewAgent`，审查前先跑 `ToolCallingLoop` 收集情报，把循环发现与委托结果合并。注册 `CurrentTimeTool`/`RegexScanTool`/`FileReadTool`（后者限定白名单根目录、拒绝路径穿越）。循环失败降级为纯委托 |
| **planning** | `core/planning/TaskPlanningSupport.java`<br>`ReviewAgentConfig:462-463` | 可选开启：目标拆成任务 DAG，按负责人路由，拓扑并行执行。解析失败降级为单任务直通 |
| **eval** | `GiteaConfig.java:88`<br>`GiteaReviewService.java` | 每次审查后 `LlmJudge` 基于 ground-truth 计算 precision/recall/F1，并跑 llm-as-judge 标记误报 |
| **extension** | 装配层 | 内置行为全部可替换；同名注册即覆盖，按 `order()` 排序织入 |
| **security** | `core/security/KeywordInjectionDetector.java` | 基座通用模式库 + 领域正则两层检测（见下文） |
| **model** | `core/llm/LlmClient.java` | 见架构锚点 |

## 刻意不采纳的能力（11 / 17 个包）

采纳一个库，不等于把所有关注点都绕经它。当本仓库已有更强或更贴合场景的实现时，换成通用实现是**降级**。

| 不采纳 | 本仓库方案 | 理由 |
|--------|-----------|------|
| `struct` | LangChain4j AiServices | 真正的 schema 绑定 **+** `ChatMemory`，且有文本解析兜底（`LlmFindingParser`） |
| `rag` / `memory` | `core/rag/`、`core/memory/`（pgvector） | 需要 pgvector 持久化、`__global__` 全局基线叠加、混合检索与重排——库里只有内存实现 |
| `hitl` | 11 状态 × 19 转移工作流引擎 | 人工审批只是更大闭环的一小部分，该闭环还包含返工、抽检、异常恢复与误报反馈 |
| `obs` | `TraceContext`<br>`ReviewTrajectoryRecorder`<br>`LoggingChatModelListener` | 全链路 traceId 跨 Agent 线程传播 + **可回放**的 JSONL 轨迹——比通用 span 更贴合"这次审查为什么得出这个结论" |
| `router` | `ModelGateway` → TokenHub | 多模型网关 + 配额限流 + 4 级降级链（Agent → 编排 → 规则 → 人工） |
| `checkpoint` | `core/resume/ResumeState` | 这里的续跑语义是"审查进度"，不是通用快照 |
| `graph` / `agent` | 星型拓扑 `CompletableFutureCoordinator` | 固定 5 Agent 扇出 + 聚合仲裁，不是通用图编排 |
| `session` | LangChain4j `ChatMemory` | 审查是无状态批处理；记忆按 `Agent-团队-PR` 键隔离 |
| `mcp` / `stream` / `model.native` | — | 暂无场景需求 |

## 三条适配经验

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

### 结构化输出：选更强的那个，而不是"自己人"

agent-kit 提供 `StructuredChatModel`，本仓库却用 LangChain4j AiServices。这是有意为之——评判标准是能力而非血缘。基座的价值在于"需要时唾手可得"，不在于"所有东西都必须用它"。

## 自行验证

```bash
grep -n -A3 "agent-kit" pom.xml                        # 依赖声明
grep -rn "com.codereview.kit" src/ --include="*.java"  # 使用点
ls src/main/java/com/codereview/kit                    # 不应存在（无内联遮蔽）
./mvnw -o test -Dtest='KeywordInjectionDetectorTest'   # 适配回归测试
```
