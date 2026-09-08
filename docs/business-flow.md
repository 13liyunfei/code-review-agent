# code-review-agent · 完整业务流程图集

从「PR 被推送」到「评论回写平台」的全链路，逐层拆解到单 Agent 内部与聚合仲裁细节。

- 入口：`GiteaWebhookController` · `GitLabWebhookController` · `ScheduledScanService` · `ReviewApiController` · `IdeReviewServer`
- 编排：`GiteaReviewService` → `CompletableFutureCoordinator` → `ReportGenerator`
- 状态层：`PgDb` → PostgreSQL 8 张表（`pgvector.enabled` 开关，未启用则回退内存并告警）
- 检索：`RagContextBuilder` 混合检索（dense 0.7 + BM25 0.3 RRF）→ 阈值闸门 abstain → MMR → small-to-big 注入
- 依赖安全：`ScaScanner` 双源降级链（OSV 真实库优先 → 内置样本，报告与 SBOM 带 sourceUsed / degraded）

> 本文由 `docs/diagrams/_build/export-md.mjs` 从 `manifest.json` + `*.mmd` 生成，**不要手改**。
> 改图改 `*.mmd`，改文案改 `manifest.json`，然后 `node export-md.mjs` 重跑。
> 带内联 SVG 的可视化版本见 [`business-flow.html`](business-flow.html)。

## 目录

- [L0 · 系统全景](#l0)
- [L1a · 接入与同步校验](#l1a)
- [L1b · 异步审查与回写](#l1b)
- [L2a · 协调器 · 准备与并行调度](#l2a)
- [L2b · 协调器 · 限时收口与聚合](#l2b)
- [L2c · 影响面分析 · 双引擎分层与真实仓库索引](#l2c)
- [L2d · RAG 检索与召回](#l2d)
- [L2e · RAG 精排与注入](#l2e)
- [L2f · 知识入库与索引（写侧）](#l2f)
- [L3 · 单 Agent 三段式与三级降级](#l3)
- [L4a · 聚合 · 降级收集 / 去重 / 仲裁](#l4a)
- [L4b · 聚合 · 误报抑制与强度定级](#l4b)
- [L5 · 人机协作 · 分级路由决策树](#l5)
- [L6 · 数据流 · PostgreSQL 集群存储与租户隔离](#l6)
- [L6b · 记忆生命周期 · 证据驱动升级与 TTL 遗忘](#l6b)
- [L7 · 能力矩阵 · Agent / Skill / 分析引擎 / 安全检测器](#l7)

---

<a id="l0"></a>

## L0 · 系统全景

五个入口 → 引擎内部六段流水线 → 平台四类回写动作。多租户以 `teamId` 贯穿全链路。

```mermaid
flowchart TD
    subgraph SRC["① 触发源 · 5 个入口"]
        direction LR
        S1["Gitea<br/>Webhook"]
        S2["GitLab<br/>Webhook"]
        S3["定时<br/>扫描"]
        S4["REST<br/>API"]
        S5["IDE<br/>本地审查"]
    end

    subgraph ENG["② 审查引擎 :8080"]
        direction TB
        E1["接入层 验签<br/>事件过滤 / 异步"]
        E2["编排层 GiteaReviewService<br/>幂等判重 resumeKey"]
        E3["协调层 Coordinator"]
        E4["执行层 5 内置 + N 自定义<br/>supports 准入过滤"]
        E5["聚合层<br/>ReportGenerator"]
        E6["输出层 AutoFix<br/>工作流引擎"]
        E1 --> E2 --> E3 --> E4 --> E5 --> E6
    end

    subgraph EXT["③ 外部依赖"]
        direction LR
        X1["LLM 网关<br/>熔断 + 路由"]
        X2["LangChain4j<br/>AiServices"]
        X3["agent-kit<br/>基座"]
        X4["Token 工厂<br/>公司级计量计费"]
    end

    subgraph OUT["④ 平台回写 · 4 类"]
        direction LR
        O1["顶层<br/>评论"]
        O2["行内<br/>suggestion"]
        O3["Issue<br/>工单"]
        O4["Commit<br/>Status"]
    end

    subgraph STATE["⑤ 状态层 · PostgreSQL"]
        direction LR
        T1["PgDb 统一连接池<br/>HikariCP max 12"]
        T2["8 张状态表<br/>每行带 team_id"]
        T3["StateStoreConfig<br/>关闭则内存回退"]
        T4["RAG 知识库<br/>tsvector + 向量"]
    end

    SRC --> E1
    E4 -.-> EXT
    X1 -.->|"直连时补报用量"| X4
    E6 --> OUT
    E3 -.->|"断点 / 轨迹落库与续跑"| STATE
    E4 -.->|"经验 / 校准 / 反馈<br/>抑制源回读"| STATE
    E3 -.->|"知识检索增强上下文"| T4
    T4 -.->|"enrichedCtx"| E4

    classDef grp fill:#FBFCFB,stroke:#2c7d2c,stroke-width:1.5px;
    classDef n fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef ext fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    classDef store fill:#F3F8F4,stroke:#2c7d2c,stroke-width:1.5px,color:#1f2933;
    class SRC,ENG,OUT,STATE grp;
    class S1,S2,S3,S4,S5,E1,E2,E3,E4,E5,E6,O1,O2,O3,O4 n;
    class X1,X2,X3,X4 ext;
    class T1,T2,T3,T4 store;
```

> 源码：`docs/diagrams/l0-overview.mmd`

> **要点**
> **拓扑是「星型汇聚」而非流水线**：5 个内置 Agent 之间互不通信、无依赖，协作只发生在聚合阶段（去重 / 仲裁 / 抑制）。
> **LLM 侧已接入公司级 Token 工厂**：工厂供应商与直连供应商同列候选，复用已有 failover；直连时由 `UsageReportingProvider` 把用量补报回工厂（无 usage 按字符数估算并打 estimated 标记）。
> **状态层已全量迁 PostgreSQL**：断点 / 经验 / 校准 / 反馈 / 历史 / 轨迹 / 团队配置 / 知识元数据共 8 张表，全部带 `team_id`；`pgvector.enabled=true` 时多实例读写同一份状态，**审查进程不再落任何本地状态文件**。
> **知识库走混合检索**：`knowledge_meta` 同时持有 `tsvector`（BM25 稀疏路）与 `embedding`（稠密路），两路 RRF 融合后经阈值闸门 / 重排 / MMR 才注入提示词，细节见 L2d；入库侧见 L2f。

---

<a id="l1a"></a>

## L1a · 接入与同步校验

Webhook 的同步部分**只做校验与分发**，重活全部丢给 `webhookExecutor` 异步执行。这样平台侧不会超时重试。

```mermaid
flowchart TD
    A["开发者推送 / 更新 Pull Request"] --> B["Gitea 发送 Webhook<br/>POST /webhook/gitea"]
    B --> T0["生成 traceId<br/>贯穿全链路"]
    T0 --> C{"X-Gitea-Signature<br/>HMAC-SHA256 校验"}

    C -->|"缺失 / 不一致"| C1["401 Invalid signature"]
    C -->|"一致 / 免签放行"| D["解析 payload JSON"]

    D --> E{"PR 号与仓库名<br/>均有效?"}
    E -->|"否"| E1["200 ignored<br/>非 PR 事件"]
    E -->|"是"| F{"action 属于<br/>opened / reopened / synchronized?"}
    F -->|"否"| F1["200 skipped<br/>动作不关心"]
    F -->|"是"| G["拆分 owner / repo"]

    G --> H{"提交 webhookExecutor<br/>TraceContext.wrap 传递 traceId"}
    H -->|"队列已满"| H2["DiscardPolicy 丢弃<br/>WARN 告警，等上游重投"]
    H -->|"入队成功"| H1["立即返回 200 accepted<br/>不让平台超时重试"]
    H1 --> NB(["转入异步审查主链路 → 见 L1b"])

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef fail fill:#FEF2F2,stroke:#b91c1c,stroke-width:1px,color:#991b1b;
    classDef ok fill:#F0FDF4,stroke:#15803d,stroke-width:1px,color:#14532d;
    class A start;
    class T0,D,G proc;
    class C,E,F,H dec;
    class C1,E1,F1,H2 fail;
    class H1 ok;
```

> 源码：`docs/diagrams/l1a-webhook-intake.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 验签 / 事件过滤 / 异步分发 | `integration/gitea/GiteaWebhookController.java:75-147` |
| 团队解析 | `tenant/TeamResolver.java` |
| 线程池拒绝策略 | `config/ProductionExecutorConfig.java:62` |

> **注意**
> **Gitea Webhook 配置的三个坑**
>
> - `events` 必须是 **布尔字面量**，写成字符串不生效；
> - 重建后的 hook 默认 `active=false`，需单独 PATCH 打开；
> - PATCH **不持久化 secret**，改密钥必须 DELETE + POST 重建。
>
> **拒绝策略已从 CallerRunsPolicy 改为 DiscardPolicy**：原先队列满时会用 Tomcat 接收线程执行审查任务，等于把背压直接传导给 webhook 接入能力，高峰期会让整个服务的接入一起变慢。现在丢弃并记 WARN，由 Gitea 上游重投——对审查系统而言，**丢一次比拖慢接入安全**。

---

<a id="l1b"></a>

## L1b · 异步审查与回写

`GiteaReviewService.reviewPullRequest()` 的编排主线：拉 diff → 并行审查 → 生成修复 → 分级路由 → 双通道回写。 **headSha 全程透传**，既用于 commit status，也是影响面索引与断点续跑键的输入。

```mermaid
flowchart TD
    I["TraceContext 兜底 traceId"] --> J["TeamResolver 解析 teamId<br/>可被 X-Team-Id 覆盖"]
    J --> JD{"幂等判重<br/>同 resumeKey 已完成?"}
    JD -->|"命中"| JD1["跳过本次审查"]
    JD -->|"未命中"| K["fetchPrChanges 拉取 diff 与元信息"]
    K --> L{"diffs 为空<br/>或拉取失败?"}
    L -->|"是"| L1["postSkipNote 说明跳过原因"]
    L -->|"否"| M["构造 PullRequest 模型<br/>teamId 隔离 + headSha"]
    M --> N["Coordinator.review<br/>多 Agent 并行审查 → 见 L2"]

    N --> O{"审查抛异常?"}
    O -->|"是"| O1["postSkipNote 引擎异常说明"]
    O -->|"否"| P["AutoFix 生成修复建议 Markdown"]
    P --> Q["WorkflowEngine.handle<br/>工单 / 提交状态 → 见 L5"]
    Q --> R["buildReviewComment 报告+修复+工作流"]
    R --> S["postPrComment 回写顶层评论"]

    S --> T["AutoFix 生成结构化修复项"]
    T --> U["过滤可锚定项 → suggestion 块"]
    U --> V["postReviewComments 一次性提交"]

    V --> W["可选：反思沉淀 / LlmJudge 评估"]
    W --> X["记录总耗时与分步耗时"]
    L1 --> X
    O1 --> X
    X --> Z(["结束"])

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef endn fill:#1f2933,stroke:#1f2933,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef fail fill:#FEF2F2,stroke:#b91c1c,stroke-width:1px,color:#991b1b;
    class I start;
    class Z endn;
    class I,J,K,M,N,P,Q,R,S,T,U,V,W,X proc;
    class L,O,JD dec;
    class L1,O1,JD1 fail;
```

> 源码：`docs/diagrams/l1b-async-review.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 主链路编排（含 headSha 透传） | `integration/gitea/GiteaReviewService.java:99-192` |
| headSha 缺失时回落 sourceBranch | `:129` |
| 报告 Markdown 拼装 | `integration/gitea/GiteaReviewService.java:199-219` |
| 幂等判重（resumeKey 同源） | `integration/gitea/GiteaReviewService.java:122-135`；GitLab 版在拉取 MrChanges 后判重 |

> **注意**
> **Gitea 1.27 的平台限制（踩过的坑）**
>
> - 逐条直发行内评论的接口返回 **405**，唯一入口是 `POST /pulls/{index}/reviews` + `comments[]` + `event:COMMENT` 一次性提交；
> - PENDING 预建方式下 `line`/`side` 会被丢弃，恒降级为文件级评论 → 可采纳修复必须写进「顶层概览 + 行内 suggestion」双通道。
>
> **webhook 幂等判重与断点续跑共用同一个键**：`PullRequest.resumeKey(repo, prId, headSha)` → `repo#编号@headSha`（`/`→`_`，因它同时被当文件名用）。此前该方法藏在 Coordinator 内部 private，服务层各自手搓键，**三处键不同源 = 判重形同虚设**——2026-09-08 上移为公共静态方法。含 head SHA：换新 commit 必须重开审查，而不是拿旧结果判重命中。GitLab 载荷没有 headSha，先拉 `MrChanges` 补 sha 再判重。

---

<a id="l2a"></a>

## L2a · 协调器 · 准备与并行调度

**runId 由 PR 身份派生**（不再用随机 traceId）→ 断点续跑 → 影响面与 RAG 上下文增强 → 自定义 Agent 展开 → 规划/固定两种并行路径 → **supports 内容准入过滤**。

```mermaid
flowchart TD
    S["Coordinator.review(pullRequest)"] --> T["runId 由 PR 身份派生<br/>repo 编号 @ headSha，traceId 同值"]

    T --> RS{"断点续跑<br/>存在同 runId 快照?"}
    RS -->|"命中"| RS1["加载 ResumeState 复用已完成结果"]
    RS -->|"未命中"| IA
    RS1 --> IA

    IA["影响面分析 ImpactIndexBuilder → 见 L2c"] --> RAG
    RAG["RagContextBuilder 混合检索 → 见 L2d<br/>合并为 enrichedCtx"]

    RAG --> CA{"有启用中的<br/>自定义 Agent?"}
    CA -->|"是"| CA1["展开 DeclarativeReviewAgent"]
    CA -->|"否 / 异常"| CA2["仅 5 个内置 Agent"]
    CA1 --> PL
    CA2 --> PL

    PL{"review.planning<br/>.enabled?"}
    PL -->|"开"| PL1["TaskPlanningSupport 拆子任务 DAG"]
    PL -->|"关 / 失败"| PL2["固定并行路径"]

    PL1 --> SA
    PL2 --> SA
    SA{"supports 内容准入<br/>containsCodeFile?"}
    SA -->|"否 · 纯文档 PR"| SA1["跳过该 Agent<br/>记 skipped-by-supports，省 token"]
    SA -->|"是"| FU
    SA1 --> FU
    FU["为每个 Agent 建 Future<br/>TraceContext.wrap 跨线程"] --> ADV["并行 AdvancedAnalyzer AST / 调用图"]
    ADV --> NB(["转入限时收口 → 见 L2b"])

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef warn fill:#FEF2F2,stroke:#b91c1c,stroke-width:1px,color:#991b1b;
    classDef enhance fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    class S start;
    class T,IA,ADV,FU proc;
    class RS,CA,PL,SA dec;
    class RS1,CA2,SA1 warn;
    class RAG,CA1,PL1 enhance;
```

> 源码：`docs/diagrams/l2a-prepare-dispatch.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| runId 派生（续跑键） | `coordinator/impl/CompletableFutureCoordinator.java:302-303, 589` |
| 断点续跑检测 | `:245-266` |
| 影响面 + RAG 上下文注入 | `:265-295, 347` |
| 自定义 Agent 展开 | `:297-325` |
| 并行 Future 创建 | `:342-359` |
| supports 内容准入 | `core/agent/ReviewAgent.java` 接口 default；`core/model/CodeDiff.java` containsCodeFile |
| 调度前过滤与轨迹 | `coordinator/impl/CompletableFutureCoordinator.java` skipped-by-supports |

> **注意**
> **runId 曾用随机 traceId，导致断点续跑在生产上永远不生效**：崩溃重试是新 HTTP 请求，拿到另一串随机 traceId，`resumeStore.load(runId)` 永远查不到断点——典型的「feature 只在测试里跑过、线上是死代码」。现在从 PR 身份派生稳定键 `repo#编号@headSha`（`/`→`_`，因 runId 同时被当作文件名）；含 head SHA 保证换新 commit 时重开审查而非续跑旧断点。
> **supports 准入解决「纯文档 PR 也烧 4 份语义 LLM」**：README / CI yaml 经 `inferLanguage` 落为 `unknown`，Logic/Perf/Style/Arch 据此跳过（xml/sql 视为代码，仍有注入 / 慢查询语义可审），Security 恒跑。跳过时记 `skipped-by-supports` 轨迹，**不产生降级语义**——只是没有这个 Agent 的结论，不是「Agent 失败了」。
> **RAG 检索已加固为十步链路**（混合检索 + 查询改写 + 阈值 abstain + MMR + small-to-big），详见 L2d；知识入库侧见 L2f。

---

<a id="l2b"></a>

## L2b · 协调器 · 限时收口与聚合

共享 deadline 逐个收口，超时即中断；随后聚合、否决回收、复检对比与落库。高级分析改走 `analyze(pr, diffs)`，结束时释放影响面临时目录。

```mermaid
flowchart TD
    DL["计算共享 deadline<br/>now + agent.timeout-ms（默认 300s）"] --> GT["逐个 future.get(remaining)<br/>剩余时间随 deadline 递减"]

    GT --> GT1{"单个 Agent 结果?"}
    GT1 -->|"正常返回"| GT2["收集 AgentResult<br/>saveCheckpoint 写断点"]
    GT1 -->|"TimeoutException"| GT3["cancel(true) 中断底层线程<br/>标记 degraded"]
    GT1 -->|"其他异常"| GT4["解包根因<br/>标记 degraded"]

    GT2 --> ADV2{"AdvancedAnalyzer<br/>analyze(pr, diffs)<br/>限时等待"}
    GT3 --> ADV2
    GT4 --> ADV2
    ADV2 -->|"超时 / 异常"| ADV3["cancel(true)<br/>STAGE_ADVANCED 降级留痕"]
    ADV2 -->|"正常"| ADV4["并入 results<br/>索引统计落轨迹备查"]

    ADV3 --> AGG
    ADV4 --> AGG
    AGG["ReportGenerator.aggregate<br/>去重 → 仲裁 → 抑制 → 统计 → 见 L4"]

    AGG --> CUS{"有自定义 Agent<br/>参与?"}
    CUS -->|"是"| CUS1["报告追加自定义 Agent 署名"]
    CUS -->|"否"| VP
    CUS1 --> VP

    VP["VetoPolicy 回收被误杀的 BLOCKER<br/>+ ReviewProfile 强度过滤"] --> VER["computeVerification<br/>已解决 / 未解决 / 新引入"]
    VER --> HS["historyStore.save 落地审查历史"]
    HS --> CP["complete 清理断点快照<br/>释放索引临时目录"]
    CP --> TP["轨迹记录 completed 并 close"]
    TP --> R(["返回 ReviewReport"])

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef endn fill:#1f2933,stroke:#1f2933,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef warn fill:#FEF2F2,stroke:#b91c1c,stroke-width:1px,color:#991b1b;
    classDef enhance fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    class DL start;
    class R endn;
    class GT,AGG,CUS1,VER,HS,CP,TP proc;
    class GT1,ADV2,CUS dec;
    class GT2,GT3,GT4,ADV3,ADV4 warn;
    class VP enhance;
```

> 源码：`docs/diagrams/l2b-timeout-aggregate.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 独立限时等待 + 降级 | `:373-432` |
| 高级分析新签名 | `core/analysis/AdvancedAnalyzer.java:97`（旧重载保留于 `:173`） |
| 聚合后处理（Veto / Profile / 复检 / 落库） | `:447-496` |
| 断点快照写入 | `:503-526` |

> **注意**
> **这里修过一个 P0 缺陷**：修复前用 `allOf(...).orTimeout()`，超时只作用在聚合 future 上，后续逐个 `join()` 仍然会无限阻塞，且 `advancedFuture` 完全没进超时体系。现在改为「共享 deadline + 逐个 `get(remaining)`」，超时即 `cancel(true)` 中断底层线程。

---

<a id="l2c"></a>

## L2c · 影响面分析 · 双引擎分层与真实仓库索引

此前影响面在真实 PR 上**恒产出 0 条结论**：diff 只含改动处 ±3 行上下文，手写正则的 AST 扫描器拿不到完整方法，跨文件调用方从来没被看见过。

```mermaid
flowchart TD
    A["Coordinator 传入 pr + diffs"] --> B["ImpactIndexBuilder<br/>拼启动期配置 + 请求期仓库坐标"]

    B --> C{"pr.headSha<br/>可用?"}
    C -->|"否"| C1["退化为 RepoIndex.empty<br/>不抛进主链路"]
    C -->|"是"| D["RepoSourceLocator 拉取<br/>PR head 版本全量源码"]

    D --> E["物化为临时源码树<br/>try-with-resources 用完即释放"]
    E --> F["IndexScope 控制扫描范围<br/>一跳 import + 同包，maxFiles 硬限流"]
    F --> F1["过滤 JDK 标准库 import<br/>否则 404 刷屏并挤占配额"]

    F1 --> G{"按文件语言<br/>选引擎"}
    G -->|"Java"| G1["JavaParser + symbol-solver<br/>Capability.CROSS_FILE<br/>全限定签名天然区分重载"]
    G -->|"其余语言"| G2["tree-sitter 容错解析<br/>Capability.FILE_LOCAL<br/>无符号解析，做不了跨文件"]

    G1 --> H["倒排索引 callersByCallee<br/>callee 签名 → 调用点列表"]
    G2 --> H

    H --> I["summarize(diffs, index)<br/>改动方法 → 上游调用方"]
    I --> J["ImpactReport<br/>含 unsupportedLangs 与 Mode"]
    J --> K["索引统计落轨迹<br/>拉取 / 解析 / 触顶可事后诊断"]
    K --> L(["并入 enrichedCtx"])

    C1 --> M(["返回空 ImpactReport<br/>报告标注 NONE 而非「无影响」"])

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef eng fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    classDef warn fill:#FEF2F2,stroke:#b91c1c,stroke-width:1px,color:#991b1b;
    classDef endn fill:#1f2933,stroke:#1f2933,stroke-width:2px,color:#fff;
    class A start;
    class B,D,E,F,F1,H,I,J,K proc;
    class C,G dec;
    class G1,G2 eng;
    class C1 warn;
    class L,M endn;
```

> 源码：`docs/diagrams/l2c-impact-analysis.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 索引构建入口 | `core/analysis/index/ImpactIndexBuilder.java:20, 40` |
| 倒排索引与能力判定 | `core/analysis/index/RepoIndex.java:196-231` |
| 能力枚举（可区分「查不了」与「确实没有」） | `core/analysis/spi/CodeAnalyzer.java:63-74` |
| 引擎路由（Java→JavaParser，其余→tree-sitter） | `core/analysis/index/AnalysisEngines.java:17-18` |
| 生产接线与临时目录释放 | `coordinator/impl/CompletableFutureCoordinator.java:272-273` |

> **要点**
> **改完必须验证生产日志真的出现「[RepoIndex] 索引完成」**。本仓库已因「分析器写得准但没接生产路径」踩过三次同类坑（AST 层恒 0、断点续跑 runId、影响面）。

> **注意**
> **为什么之前测不出来**：测试用了「新增文件全量 patch」，helper 悄悄替生产代码满足了前提，于是恒 0 的问题被测试掩盖。治本要点：
>
> - **能力显式化**：`Capability` 区分 CROSS_FILE / FILE_LOCAL，把「引擎查不了」与「确实没有影响面」分开，避免把原理性限制说成「没影响」；
> - **按 head SHA 拉全量源码**物化成临时源码树，JavaParser 才有跨文件解析的原料；源码根必须给到 `src/main/java` 而非仓库根，否则全 `resolved=false` 且不报错；
> - **一跳 + 同包 + maxFiles 硬限流**，并过滤 JDK 标准库 import，否则 404 刷屏并挤占配额。

---

<a id="l2d"></a>

## L2d · RAG 检索与召回

**提取 → 改写 → 混合检索 → 阈值闸门**：把 diff 变成能撞上规范库的查询，再用稠密 + 稀疏双路召回，最后决定「值不值得注入」。

```mermaid
flowchart TD
    D0["CodeDiff 列表"] --> Q1["① 查询提取 DiffQueryExtractor<br/>提炼文件 / 类 / 方法 / 符号"]

    Q1 --> Q2{"② 查询改写<br/>rewrite.enabled?"}
    Q2 -->|"开"| Q2a["LlmQueryRewriter<br/>代码形态 → 规范术语"]
    Q2 -->|"关 / 失败 / 空"| Q2b["IdentityQueryRewriter<br/>恒等返回，绝不劣化"]
    Q2a --> Q3
    Q2b --> Q3

    Q3["③ 混合检索<br/>团队内容 + __global__ 基线"]
    Q3 --> R1["稠密路 · pgvector 余弦 · 权重 0.7"]
    Q3 --> R2["稀疏路 · tsvector BM25 · 权重 0.3"]
    AGE["freshness max-age-days<br/>按 created_at 过滤陈旧知识"] -.-> Q3

    R1 --> F1["RRF 融合 1 除以 60+rank<br/>候选窗 retrieve-k 默认 50"]
    R2 --> F1
    F1 --> F2["similarity 恒为真实余弦<br/>rrfScore 只排序，不进阈值"]

    F2 --> T{"④ 阈值闸门<br/>min-similarity 0.3"}
    T -->|"全部低于"| T1["知识分区 abstain<br/>宁可不注入，不拿噪声凑数"]
    T -->|"有放行"| NB(["转入精排与注入 → 见 L2e"])

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef endn fill:#1f2933,stroke:#1f2933,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef short fill:#FEF2F2,stroke:#b91c1c,stroke-width:1px,color:#991b1b;
    classDef enh fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    class D0 start;
    class NB endn;
    class Q1,Q3,R1,R2,F1,F2 proc;
    class Q2,T dec;
    class T1 short;
    class Q2a,Q2b,AGE enh;
```

> 源码：`docs/diagrams/l2d-rag-retrieval.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 查询提取 | `core/rag/DiffQueryExtractor.java` |
| 查询改写（Identity / LLM） | `core/rag/{IdentityQueryRewriter,LlmQueryRewriter}.java` |
| 混合检索 + RRF（dense 0.7 / BM25 0.3） | `core/rag/PgKnowledgeStore.java:162-300` |
| 阈值闸门 filterByThreshold | `core/rag/RagEvaluator.java` |

> **要点**
> **每一步都可降级，没有任何一步会阻断审查**：改写失败回落恒等、稀疏路无词则整段跳过、候选全低于阈值则 abstain 不注入。**RAG 的失败模式是「少给点上下文」，绝不是「审查挂掉」**。

> **注意**
> **similarity 口径是本轮最贵的一课**：`similarity` 恒为**真实余弦**，RRF 归一化排名分另存 `rrfScore`，**绝不参与阈值判断**——RRF 归一化后第一名恒为 1.0、第 50 名仍有约 0.55，拿它去比 0.3 阈值等于闸门永不关闭（测试里看着「有召回」，线上全是噪声）。此外只命中稀疏路的条目必须补算余弦，否则同一结果集内语义不统一。
> **旧实现把 diff 原文截前 500 字符直接当查询**：里面塞满 `+/-`、行号与上下文噪音，截完后真正改动的方法常常根本没被包含——拿这段噪声去撞以自然语言术语为主的规范库，语义鸿沟极大。现在由 `DiffQueryExtractor` 提炼文件 / 类 / 方法 / 符号。

---

<a id="l2e"></a>

## L2e · RAG 精排与注入

**重排 → MMR → 去重 → 评估 → 父章节回填 → 经验回流**：召回的 50 条里挑出真正值得进 prompt 的 5 条。

```mermaid
flowchart TD
    S(["承接 L2d 阈值放行候选"]) --> RR["⑤ 重排池 max Top-N×3<br/>池子太小，MMR 等于没开"]

    RR --> RR1{"Reranker 选型"}
    RR1 -->|"有 key"| RR2["ApiReranker<br/>Cohere / Jina"]
    RR1 -->|"无 key / 超时"| RR3["HeuristicReranker<br/>离线兜底，绝不阻断"]
    RR2 --> MM
    RR3 --> MM

    MM{"⑥ MMR 多样性挑选 λ=0.7"}
    MM -->|"开"| MM1["相关性 − 冗余度贪心<br/>Jaccard on 中文 bigram"]
    MM -->|"关 / 候选不足"| MM2["直接取重排 Top-N"]
    MM1 --> DD
    MM2 --> DD

    DD["⑦ 内容去重<br/>按 content hash 剔重叠块"] --> EV["⑧ RagEvaluator<br/>hit@k / MRR / maxSim"]
    EV --> FMT["⑨ small-to-big<br/>回填 parentExcerpt 父章节摘要"]
    FMT --> INJ["【相关历史知识】<br/>格式化注入提示词"]

    AB["L2d 的 abstain 分支<br/>也走这一步"] -.-> EXP
    INJ --> EXP["⑩ 经验回流独立于知识 abstain<br/>命中即 recordHit 刷新遗忘时钟"]
    EXP --> OUT(["返回 enrichedCtx 到各 Agent"])

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef endn fill:#1f2933,stroke:#1f2933,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef enh fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    class S start;
    class OUT endn;
    class RR,DD,EV,FMT,INJ,EXP proc;
    class RR1,MM dec;
    class RR2,RR3,MM1,MM2,AB enh;
```

> 源码：`docs/diagrams/l2e-rag-rerank.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 重排（Api / Heuristic 双实现） | `core/rag/{ApiReranker,HeuristicReranker}.java` |
| MMR 挑选与 small-to-big 回填 | `core/memory/RagContextBuilder.java:265-345` |
| 评估指标 hit@k / MRR | `core/rag/RagEvaluator.java` |
| 历史经验回流 | `core/memory/RagContextBuilder.java:203-220` |

> **要点**
> **注入 Top-N 的质量比数量重要得多**：LLM 对噪声敏感，塞 20 条低质片段不如塞 5 条高质片段。这一段的每一步都在做减法——去重、去冗余（MMR）、去过期（freshness）。

> **注意**
> **MMR 解决的是「看似注入 5 条，实际只覆盖 1 个知识点」**：重排后的 Top-N 常被同一章节的相邻碎片占满（重叠切块导致内容高度雷同）。MMR 按 `λ·相关性 − (1−λ)·冗余度` 贪心挑选（λ=0.7），冗余度用 `TextTokenizer` 中文 bigram 的 Jaccard——**用英文空格分词算中文相似度会恒为 0，MMR 直接退化成普通排序**。
> **重排池必须开到 Top-N×3**：池子只有 Top-N 时 MMR 无挑选余地，等于没开。
> **small-to-big**：命中叶子块时仅凭一条孤立条款看不出归属，回填 `StructuredChunker` 写入的 `parentExcerpt`（≤300 字符，同一父摘要只追加一次），让模型看到「这一条属于哪一章」。
> **经验回流独立于知识 abstain**：知识分区全低于阈值时仍会注入【历史经验参考】，且命中即 `recordHit` 刷新遗忘时钟（spaced repetition）。

---

<a id="l2f"></a>

## L2f · 知识入库与索引（写侧）

**StructuredChunker 结构感知切块 → TextTokenizer 统一分词 → tsvector + embedding 双写 → HNSW 索引**。写侧口径错了，读侧再怎么调都救不回来。

```mermaid
flowchart TD
    A["知识源 · 团队规范 / 历史 PR / 安全 Wiki"] --> B["StructuredChunker 结构感知切块"]

    B --> B1["优先自然边界<br/>Markdown 标题 / 代码围栏 / 空行"]
    B1 --> B2["超 700 字符按段落 → 句子硬切<br/>相邻块保留 15% 尾部重叠"]
    B2 --> B3["富元数据 · headingPath 标题栈<br/>parentSection 真层级 + parentExcerpt"]

    B3 --> C["TextTokenizer 统一分词<br/>中文 bigram + camelCase 子词"]
    C --> C1["写入侧与检索侧必须同分词器<br/>词位对不上比不分词更隐蔽"]

    C1 --> D["双写索引列"]
    D --> D1["稀疏列 tsvector<br/>to_tsvector simple"]
    D --> D2["稠密列 embedding<br/>1024 维，统一不按默认 256"]

    D1 --> E[("knowledge_meta<br/>8 张状态表之一")]
    D2 --> E

    E --> F{"向量索引选型"}
    F -->|"pgvector 支持"| F1["HNSW<br/>m 16 / ef_construction 64"]
    F -->|"不支持"| F2["ivfflat 回退"]
    F1 --> G["检索用 to_tsquery OR<br/>不用 plainto_tsquery AND"]
    F2 --> G
    G --> NB(["转入检索链路 → 见 L2d"])

    M["存量迁移 tsvector-tokenize-v1<br/>幂等重建旧 tsvector 列"] -.-> E
    W["PG simple 词典不切中文<br/>整段无空格中文 = 一个词位"] -.-> C

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef endn fill:#1f2933,stroke:#1f2933,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef store fill:#F3F8F4,stroke:#2c7d2c,stroke-width:1.5px,color:#1f2933;
    classDef warn fill:#FEF2F2,stroke:#b91c1c,stroke-width:1px,color:#991b1b;
    classDef enh fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    class A start;
    class NB endn;
    class B,B1,B2,B3,C,D,D1,D2,G proc;
    class F dec;
    class E store;
    class W warn;
    class C1,F1,F2,M enh;
```

> 源码：`docs/diagrams/l2f-rag-indexing.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 结构感知切块（700 字符 / 15% 重叠） | `core/rag/StructuredChunker.java` |
| 中文 bigram + 标识符子词分词 | `core/rag/TextTokenizer.java` |
| 双写与 HNSW / ivfflat | `core/rag/PgKnowledgeStore.java`；E2E `PgHnswIndexE2eTest` |

> **要点**
> **PG 的 `simple` 词典不切中文**：无空格的中文整段会被当成一个词位（实测「禁止使用字符串拼接的sql」是**一个**词位），于是「参数绑定」查「…必须使用参数绑定…」命中 false，连 `PreparedStatement` 都命中 false——**稀疏路在中文规范库上等于空转**。

> **注意**
> **写入侧与检索侧必须用同一个分词器**，否则倒排词位对不上——这比「不分词」更隐蔽：不分词是明显零命中，两侧不一致是时好时坏。
> **切块的三个反直觉点**：
>
> - 标题要**进正文**（前缀 `【headingPath】`）而不只是 metadata——只进 metadata 会双重损失：嵌入时丢章节语义、注入时 LLM 看不到归属；
> - `parentSection` 必须是**真层级**（标题栈），早期版本直接写成 `section`（假层级），导致 small-to-big 父子检索无法实现；
> - 检索用 `to_tsquery` 的 **OR** 而非 `plainto_tsquery` 的 AND——RAG 查询是整段长句，AND 要求全词命中，必然零命中，该交给 `ts_rank` 排序。

---

<a id="l3"></a>

## L3 · 单 Agent 三段式与三级降级

每个 Agent 内部三段：**① 输入防护（逐文件分级隔离）** → **② Skill 确定性预扫描** → **③ LLM 语义增强**。规则出确定结论，LLM 只做补充。

```mermaid
flowchart TD
    A["agent.review(diffs, enrichedCtx)"] --> B{"① 输入防护<br/>DiffInputGuard 逐文件分级"}

    B -->|"BLOCK<br/>隐写字符 / 关键词 HIGH"| B1["产文件级 BLOCKER，该文件不进 LLM"]
    B -->|"TAG<br/>关键词 LOW / 语义近似"| B2["渲染时标注「数据非指令」<br/>仍参与审查"]
    B -->|"CLEAN"| B3["正常放行"]

    B1 --> C
    B2 --> C
    B3 --> C

    C["② Skill 预扫描 registry 按 teamId 取技能<br/>parallelStream 并行，全量 diff 照跑"] --> D["SkillResult → Finding<br/>按 ruleId 历史准确率校准置信度"]

    D --> G{"还有未被 BLOCK<br/>隔离的文件?"}
    G -->|"否"| G0["跳过 LLM 语义审查"]
    G -->|"是"| GB["diff 字符预算 diffCharBudget<br/>超限按文件均摊截断 + 末尾标注"]
    GB --> H{"③ LLM 增强 优先 AiServices<br/>结构化输出 + ChatMemory"}
    H -->|"成功且非空"| H1["映射为 Finding<br/>来源 LLM"]

    H -->|"失败 / 空"| I{"次选 agent-kit<br/>类型推导 schema"}
    I -->|"成功"| I1["映射为 Finding<br/>来源 LLM"]
    I -->|"结构化失败<br/>但原文非空"| J["复用原始输出做文本解析"]
    I -->|"彻底失败"| K["askLlm 文本对话<br/>解析器兜底"]

    H1 --> M
    I1 --> M
    J --> M
    K --> M
    G0 --> M
    M["合并 Skill 发现 + LLM 发现"] --> N(["返回 Finding 列表"])

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef endn fill:#1f2933,stroke:#1f2933,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef short fill:#FEF2F2,stroke:#b91c1c,stroke-width:1px,color:#991b1b;
    classDef llm fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    class A start;
    class N endn;
    class C,D,M,GB proc;
    class B,H,I,G dec;
    class B1,G0 short;
    class B2,B3,H1,I1,J,K llm;
```

> 源码：`docs/diagrams/l3-single-agent.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 逐文件分级（BLOCK / TAG / CLEAN） | `core/agent/impl/SecurityAgent.java:63-97` |
| 隔离后渲染（标注数据非指令） | `:99-122` |
| 分级门卫实现 | `core/security/DiffInputGuard.java` |
| Skill 并行预扫描 | `core/agent/AbstractReviewAgent.java:85-89` |
| 置信度校准 | `core/agent/AbstractReviewAgent.java:97-120` |
| LLM 三级降级 | `core/agent/AbstractReviewAgent.java:190-244` |
| diff 字符预算（均摊截断） | `core/agent/AbstractReviewAgent.java` diffCharBudget / formatDiffs |
| 预算注入 5 Agent | `config/ReviewAgentConfig.java`；yml `review.prompt.diff-char-budget` |

> **要点**
> **降级链路的关键设计**：结构化输出失败但 `rawResponse` 非空时，**复用原始输出走文本解析**，不再多调一次模型——既省 token，也避免二次失败。

> **注意**
> **输入防护已从「全 PR concat 一刀切」改为「逐文件分级」**：
>
> - **BLOCK**（隐写字符 / 关键词 HIGH）→ 该文件**不进 LLM 上下文**并产文件级 BLOCKER 带行号；
> - **TAG**（关键词 LOW / 语义近似）→ 渲染前显式标注「被审查数据非指令」，仍参与审查；
> - **CLEAN** → 正常放行。
>
> 收益：单文件命中不再连累整 PR（旧实现一旦命中就 `return`，整 PR 不做语义审查），且能给出文件级/行级定位。新增维度：`StegInjectionScanner` 查**隐写字符**（零宽 / Bidi 拆词藏指令）。**纯规则层无 LLM 风险，因此 Skill 预扫描仍跑全量 diff**，只有 LLM 输入被裁剪。
> **diff 字符预算（2026-09-08）**：大 PR 的 diff 可达数十万字符，全量塞 prompt 既烧 token 又可能顶爆上下文。超预算时按**文件均摊**截断（不偏向列表头部的文件），并**统一追加截断统计标注**——让模型明确知道「diff 不完整」，避免把截断误判成「没有更多变更」。默认 -1 不截断，向后兼容（单测直接 new 不感知）。

---

<a id="l4a"></a>

## L4a · 聚合 · 降级收集 / 去重 / 仲裁

仲裁权重：**安全 100 > 逻辑 90 > 性能 70 > 架构 60 > 风格 10**；先比 Agent 权重，再比严重度，最后比置信度。

```mermaid
flowchart TD
    A["输入：全部 AgentResult<br/>含自定义 Agent 与 AdvancedAnalyzer"] --> D0["⓪ 降级收集<br/>Agent 级 + 基础设施级，按 stage 去重"]

    D0 --> D1["① 去重<br/>dedupKey = 文件 @ 行区间 # 规则"]
    D1 --> D2{"同键冲突?"}
    D2 -->|"更严重 / 同级别更高置信度"| D3["替换保留"]
    D2 -->|"否则"| D4["保留原条目"]
    D3 --> AR
    D4 --> AR

    AR["② 优先级仲裁<br/>ArbitrationPolicy.isConflict"] --> AR1{"不同 Agent + 同文件<br/>+ 行区间重叠 + 建议不同?"}
    AR1 -->|"否"| AR2["全部保留"]
    AR1 -->|"是"| AR3["裁决：Agent 权重 → 严重度 → 置信度"]
    AR3 --> AR4["落败方移入 overridden<br/>生成仲裁说明"]

    AR2 --> NB(["转入误报抑制与定级 → 见 L4b"])
    AR4 --> NB

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef drop fill:#FEF2F2,stroke:#b91c1c,stroke-width:1px,color:#991b1b;
    classDef keep fill:#F0FDF4,stroke:#15803d,stroke-width:1px,color:#14532d;
    class A start;
    class D0,D1,AR proc;
    class D2,AR1 dec;
    class D3,AR4 drop;
    class AR2 keep;
```

> 源码：`docs/diagrams/l4a-dedup-arbitration.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 降级收集 / 去重 / 仲裁 | `core/report/ReportGenerator.java`（`aggregate` 8 参数重载） |
| 权重表与冲突判定 | `core/report/ArbitrationPolicy.java:21-27, 52-95` |

---

<a id="l4b"></a>

## L4b · 聚合 · 误报抑制与强度定级

抑制来自开发者历史反馈；**BLOCKER 强否决不可被抑制或仲裁覆盖**，被误杀时由 VetoPolicy 回收。

```mermaid
flowchart TD
    A["承接 L4a 去重与仲裁后的条目"] --> FP["③ 误报抑制<br/>feedbackStore.falsePositives(teamId)"]

    FP --> FP1{"命中历史误报?"}
    FP1 -->|"是"| FP2["移入 suppressed"]
    FP1 -->|"否"| FP3["进入 kept 列表"]
    FP2 --> ST
    FP3 --> ST

    ST["④ 分级统计<br/>BLOCKER / MAJOR / MINOR / INFO"]
    ST --> VT{"VetoPolicy<br/>BLOCKER 被误杀?"}
    VT -->|"是"| VT1["回收 BLOCKER<br/>强否决不可覆盖"]
    VT -->|"否"| PF
    VT1 --> PF

    PF{"ReviewProfile 强度"}
    PF -->|"STRICT 严格"| PF1["仅剔除 INFO"]
    PF -->|"ADVISORY 默认"| PF2["只留 BLOCKER + MAJOR"]
    PF -->|"SUGGEST 仅建议"| PF3["全部保留"]
    PF1 --> FIN(["最终对外发现列表"])
    PF2 --> FIN
    PF3 --> FIN

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef endn fill:#1f2933,stroke:#1f2933,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef drop fill:#FEF2F2,stroke:#b91c1c,stroke-width:1px,color:#991b1b;
    classDef keep fill:#F0FDF4,stroke:#15803d,stroke-width:1px,color:#14532d;
    class A start;
    class FIN endn;
    class FP,ST proc;
    class FP1,VT,PF dec;
    class FP2 drop;
    class FP3,PF1,PF2,PF3,VT1 keep;
```

> 源码：`docs/diagrams/l4b-suppress-grading.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| BLOCKER 强否决回收 | `core/permission/VetoPolicy.java:26-36` |
| 强度 Profile 过滤 | `core/profile/ReviewProfile.java:24-57` |

---

<a id="l5"></a>

## L5 · 人机协作 · 分级路由决策树

自动审查之后「人」如何介入。`ReviewWorkflowEngine.handle()` 按 BLOCKER 有无分叉。

```mermaid
flowchart TD
    A["WorkflowEngine.handle<br/>report / owner / repo / prNum / headSha"] --> B["统计 findings 中<br/>BLOCKER 的数量"]

    B --> C{"BLOCKER 数量 大于 0 ?"}

    C -->|"是"| D1["拼装 Issue 正文<br/>逐条列文件 L行 · 标题 · 规则"]
    D1 --> D2["createIssue 创建整改工单"]
    D2 --> D3{"headSha 非空?"}
    D3 -->|"是"| D4["提交状态 = failure<br/>context code-review/blocker"]
    D3 -->|"否"| D5["跳过提交状态"]
    D4 --> D6["报告追加：已进入强制审批流"]
    D5 --> D6

    C -->|"否"| E1{"headSha 非空?"}
    E1 -->|"是"| E2["提交状态 = success<br/>context code-review/blocker"]
    E1 -->|"否"| E3["跳过提交状态"]
    E2 --> E4["报告追加：无 BLOCKER"]
    E3 --> E4

    D6 --> F["追加分级路由说明<br/>统计 MAJOR 数量"]
    E4 --> F
    F --> F1["BLOCKER → 人工审批<br/>MAJOR → 建议修复<br/>MINOR / INFO → 提示参考"]
    F1 --> G(["返回工作流片段<br/>合并进 PR 顶层评论"])

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef endn fill:#1f2933,stroke:#1f2933,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef bad fill:#FEF2F2,stroke:#b91c1c,stroke-width:1px,color:#991b1b;
    classDef good fill:#F0FDF4,stroke:#15803d,stroke-width:1px,color:#14532d;
    class A start;
    class G endn;
    class B,F,F1 proc;
    class C,D3,E1 dec;
    class D1,D2,D4,D6 bad;
    class E2,E4 good;
```

> 源码：`docs/diagrams/l5-workflow.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 决策与回写动作 | `core/workflow/ReviewWorkflowEngine.java:42-85` |
| Issue / Commit Status API | `integration/gitea/GiteaApiClient.java` |

> **要点**
> 工单引用用裸 `#N` 而非绝对 URL：Gitea 的 auto-link 会渲染为站内可点链接，不会被域名白名单过滤，也不会因 base 二次拼接导致 404。

---

<a id="l6"></a>

## L6 · 数据流 · PostgreSQL 集群存储与租户隔离

8 张状态表统一由 `PgDb` 连接池承载，每行带 `team_id`；`pgvector.enabled` 一键切换 PG / 内存双后端。基线 `__global__` + 团队叠加，缺失回退默认。

```mermaid
flowchart LR
    subgraph IN["写入侧"]
        direction TB
        W1["Coordinator<br/>每完成一个 Agent"]
        W2["Coordinator<br/>审查结束"]
        W3["Console / REST<br/>标记误报"]
        W4["Coordinator<br/>全流程事件"]
        W5["管理端<br/>Skill / 自定义 Agent 变更"]
        W6["知识摄入<br/>团队规范入库"]
        W7["ReflectionService<br/>反思沉淀 / 跨 PR 复现"]
        W8["ResumeJanitor<br/>每日 03:30"]
    end

    subgraph STORE["持久化 · PostgreSQL 统一连接池 PgDb"]
        direction TB
        P1[("resume_state<br/>run_id PK · payload JSONB")]
        P2[("review_history<br/>team + hkey")]
        P3[("review_feedback<br/>抑制源")]
        P4[("trajectory_store<br/>run_id PK · events JSONB")]
        P5[("team_kv<br/>团队配置 JSONB")]
        P6[("knowledge_meta<br/>RAG 知识 · tsvector + 向量<br/>HNSW / ivfflat")]
        P7[("experience_entry<br/>四态 + 证据计数")]
        P8[("calibration_accuracy<br/>rule_id PK")]
    end

    subgraph OUT["消费侧"]
        direction TB
        C1["崩溃后同 runId 续跑<br/>runId 由 PR 身份派生"]
        C2["computeVerification<br/>已解决 / 新引入"]
        C3["aggregate 阶段<br/>误报抑制"]
        C4["轨迹按 runId 读取<br/>事后审计 / 排障复盘<br/>原 ReviewReplay 已随死代码清理下线"]
        C5["runSkills 规则生效<br/>跨实例 2s 收敛"]
        C6["RagContextBuilder 混合检索<br/>dense 0.7 + BM25 0.3 RRF<br/>阈值 abstain → 见 L2d"]
        C7["经验检索注入<br/>仅 CANDIDATE + ACTIVE"]
        C8["ConfidenceCalibration<br/>置信度校准"]
        C9["webhook 幂等判重<br/>断点 / 历史 / webhook 键同源"]
    end

    W1 --> P1
    W2 --> P2
    W3 --> P3
    W4 --> P4
    W5 --> P5
    W6 --> P6
    W7 --> P7
    W8 -->|"purgeExpired<br/>按 updated_at"| P1
    P3 -.->|"复合 FeedbackListener<br/>校准 + 经验证据两路"| P8
    P3 -.->|"误报 = 负证据"| P7

    P1 --> C1
    P2 --> C2
    P3 --> C3
    P4 --> C4
    P5 --> C5
    P6 --> C6
    P7 --> C7
    P8 --> C8
    P2 -.->|"resumeKey 命中即跳过重投"| C9

    G[("__global__ 基线<br/>团队配置叠加")] -.->|"缺失回退默认"| STORE
    F[("pgvector.enabled=false<br/>InMemory 回退 + 启动告警")] -.->|"单机 / 测试"| STORE

    classDef grp fill:#FBFCFB,stroke:#2c7d2c,stroke-width:1.5px;
    classDef n fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef store fill:#F3F8F4,stroke:#2c7d2c,stroke-width:1.5px,color:#1f2933;
    classDef base fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    class IN,STORE,OUT grp;
    class W1,W2,W3,W4,W5,W6,W7,W8,C1,C2,C3,C4,C5,C6,C7,C8,C9 n;
    class P1,P2,P3,P4,P5,P6,P7,P8 store;
    class G,F base;
```

> 源码：`docs/diagrams/l6-dataflow.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 统一连接池与 8 张表建表 | `core/store/PgDb.java:46, 109` |
| PG / 内存双后端装配开关 | `core/store/StateStoreConfig.java:46-145` |
| 团队配置落 team_kv + 2s TTL 刷新 | `core/skill/SkillRegistry.java:43, 68` |
| 反馈复合监听器（校准 + 经验证据） | `config/ReviewAgentConfig.java:614-619` |
| 断点定时清理（每日 03:30） | `core/resume/ResumeJanitor.java:27, 47` |
| RAG 知识表混合检索 | `core/rag/PgKnowledgeStore.java` RRF k=60 / dense 0.7 |
| 幂等键公共方法 | `core/model/PullRequest.java` resumeKey |
| 轨迹事件源读写 | `core/trajectory/{TrajectoryStore,ReviewTrajectoryRecorder}.java`；PG 实现 `core/store/PgTrajectoryStore.java` |

> **要点**
> **跨实例收敛靠「写穿 + 短 TTL 惰性刷新」**：团队配置（Skill 启停 / 自定义 Agent）写穿 `team_kv` JSONB，读方带 2s 短 TTL 惰性重载——A 实例管理端变更，B 实例最迟 2s 生效，不需要消息广播。

> **注意**
> **为什么迁**：改造前 8 类状态落在 `data-dir//` 本地 JSON，A 机沉淀 B 机不可见，多实例必然分叉——**「经验越用越准」在集群下反而退化成「每台机器各长一套记忆」**。
> **三个设计取舍**：
>
> - 沿用仓库既有风格——原生 JDBC + Hikari，**不引 starter-jdbc**，避免改变运行时行为；
> - `StateStoreConfig` 按 `pgvector.enabled` 装配双后端，未启用时回退内存并**启动告警**（单机/测试可用，集群必须开 PG）；
> - 表结构 `CREATE TABLE IF NOT EXISTS` 幂等初始化（无 Flyway），与既有 `memory_store` 口径一致。
>
> **迁移后 `data-dir` 只剩一个用途**：agent-loop 开启时作为 `FileReadTool` 的默认读取根。
> **坑**：Spring 的 `Duration` 绑定，不带单位的纯数字按**毫秒**算——写成 `ttl: 24` 会变成 24 毫秒、把所有断点一扫而空。
> **2026-09-08 死代码清理后消费侧只剩 9 类**：`core/mq` 消息子系统 7 类、`core/tools/external` 2 类、`core/eval/ReviewReplay` 全部下线（main 零引用、仅测试在兜底），连带移除 Redis 依赖链。`trajectory_store` 因此**不再有自动回放器消费**，只按 `runId` 读事件序列做事后审计与排障；清理由 `DeadCodeGuardTest` 门禁固化，防死代码复发。

---

<a id="l6b"></a>

## L6b · 记忆生命周期 · 证据驱动升级与 TTL 遗忘

经验条目四态：`CANDIDATE → ACTIVE → ARCHIVED → PURGED`。**升级靠证据不靠时间**，遗忘只作用于长期无命中的冷门经验。

```mermaid
flowchart TD
    A1["反思沉淀 · 新 pattern 入库"] --> S1
    A2["跨 PR 复现 · 同 pattern 再现"] -->|"evidence_pos + 1"| S1
    A3["人工正报"] -->|"evidence_pos + 1"| S1
    A4["人工误报"] -->|"evidence_neg + 1"| S1

    S1["CANDIDATE<br/>候选 · 未经验证"]
    S2["ACTIVE<br/>转正 · 检索权重最高"]
    S3["ARCHIVED<br/>软删 · 退出检索注入"]
    S4["PURGED<br/>物理删除 · 不可恢复"]

    S1 -->|"evidence_pos ≥ 3<br/>SQL 内原子判定"| S2
    S1 -->|"evidence_neg ≥ 2"| S3
    S2 -->|"evidence_neg ≥ 2"| S3
    S3 -->|"保留期 90d"| S4

    MAINT["MemoryMaintenanceScheduler<br/>每日 03:40 · 与断点清理错峰"]
    MAINT -.->|"无命中 / 无更新 180d"| S3
    MAINT -.->|"purgeArchived"| S4

    INJ["注入点 · RagContextBuilder<br/>追加【历史经验参考】分区<br/>Coordinator 主链路自动生效"] -.->|"仅读 CANDIDATE + ACTIVE"| S2
    INJ -.->|"知识 abstain 时经验仍注入"| S1
    HIT["命中即 recordHit<br/>刷新 lastHitAt + hitCount"] -.->|"spaced repetition<br/>常用经验不衰退"| S2
    ADMIN["ExperienceAdminController<br/>/api/admin/memory/experiences"] -.->|"手动归档 / 删除"| S3

    classDef n fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef cand fill:#F3F8F4,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef act fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef arch fill:#F5F7FA,stroke:#9aa5b1,stroke-width:1px,color:#52606d;
    classDef purge fill:#FFFFFF,stroke:#b0443c,stroke-width:1.5px,color:#b0443c;
    classDef side fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    class A1,A2,A3,A4 n;
    class S1 cand;
    class S2 act;
    class S3 arch;
    class S4 purge;
    class MAINT,HIT,ADMIN,INJ side;
```

> 源码：`docs/diagrams/l6b-memory-lifecycle.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 四态枚举与升级规则 | `core/memory/ExperienceStage.java:21-25` |
| 接口：沉淀 / 反馈 / 命中 / 归档 / 清理 | `core/memory/ExperienceLibrary.java:20-69` |
| PG 实现（SQL 内原子判定） | `core/store/PgExperienceLibrary.java:27, 29, 46-48` |
| 每日维护 03:40（180d 归档 / 90d 硬删） | `core/memory/MemoryMaintenanceScheduler.java:36-48` |
| 管理端点 | `core/memory/ExperienceAdminController.java:24-56` |
| 生产注入点（读侧闭环） | `core/memory/RagContextBuilder.java:203-220` 追加【历史经验参考】分区 |

> **要点**
> **反遗忘靠 spaced repetition**：每次检索命中都刷新 `lastHitAt` 与 `hitCount`，被反复用到的经验永远不会进入 TTL 归档——遗忘只清理「记忆衰退」的冷门条目，常用经验不衰退。

> **注意**
> **阈值是 SQL 常量**（`ACTIVE_EVIDENCE = 3` / `ARCHIVE_NEGATIVE = 2`），升级判定在 `UPDATE ... SET stage = CASE WHEN ...` 内**原子完成**，不存在读-改-写竞态。
> **坑**：Spring `Duration` 绑定不带单位的纯数字按**毫秒**算——`experience-idle: 180` 会变成 180 毫秒，一轮清空全部经验。默认值一律写成 `180d` / `90d`。
> 维护任务**与断点清理 03:30、巡检 02:00 错峰**在 03:40 执行，异常只记 WARN 跳过本轮，不影响审查主链路。
> **读侧闭环是本仓库第四次同类病**：生命周期写得很完整，但 `RagContextBuilder` 从未拿到 `ExperienceStore`，经验只在管理端可见、审查链路里是死的——「只写不读」的 feature 在演示与单测里都看不出来，因为二者都直接调 store 而不走生产链路。2026-09-07 改构造注入，命中即 `recordHit`；同时废弃向量经验死通道（唯一写入方 `ReflectionAgent` 主链路零调用）。**经验分区独立于知识 abstain**：知识全低于阈值时仍注入经验。**验收必须走生产入口 + 真实探针**，只看单测会被 helper 悄悄满足前提。

---

<a id="l7"></a>

## L7 · 能力矩阵 · Agent / Skill / 分析引擎 / 安全检测器

执行层的完整构成：5 内置 Agent + 团队自定义 Agent + 高级静态分析（含影响面双引擎、SCA 真实漏洞库）+ RAG 检索子系统 + LLM 网关；Skill 按 category 挂载，支持运行期启停与团队自定义规则。工程侧由 `DeadCodeGuardTest` 门禁防死代码复发。

```mermaid
flowchart LR
    ROOT["执行层<br/>effectiveAgents"] --> BI["内置 Agent"]
    ROOT --> A6["CUSTOM<br/>DeclarativeReviewAgent<br/>按 teamId 隔离"]
    ROOT --> A7["AdvancedAnalyzer<br/>静态分析"]
    ROOT --> GW["LLM 网关<br/>优先级路由 + 熔断"]
    ROOT --> RAG["RAG 检索子系统<br/>读侧 L2d / 写侧 L2f"]
    ROOT --> QA["工程门禁 DeadCodeGuardTest<br/>main 生产零引用即 CI 失败<br/>防死代码复发"]

    BI --> A1["SECURITY 安全<br/>权重 100"]
    BI --> A2["LOGIC 逻辑<br/>权重 90"]
    BI --> A3["PERFORMANCE 性能<br/>权重 70"]
    BI --> A4["ARCHITECTURE 架构<br/>权重 60"]
    BI --> A5["STYLE 规范<br/>权重 10"]

    A1 --> S1["HardcodedSecretSkill"]
    A1 --> S2["SqlInjectionSkill"]
    A1 --> S3["YamlRuleEngine<br/>category = security"]
    A3 --> S4["PatternSkill<br/>正则模式匹配"]
    A5 --> S5["YamlRuleEngine<br/>category = style"]

    A1 --> D1["DiffInputGuard<br/>逐文件 BLOCK / TAG / CLEAN"]
    D1 --> D2["StegInjectionScanner<br/>隐写字符 · 零宽 / Bidi"]
    D1 --> D3["KeywordInjectionDetector<br/>攻击句式 · HIGH / LOW"]
    D1 --> D4["SemanticInjectionDetector<br/>向量近似 · 不可达则降级为空"]

    A6 --> D5["ContentInjectionDetector<br/>写库前预检 · 异常填充 + 关键词 + 语义"]

    A7 --> Z1["AstAnalyzer<br/>AST 结构"]
    A7 --> Z2["CallGraphAnalyzer<br/>调用图"]
    A7 --> Z3["ScaScanner 依赖安全<br/>primary / fallback 降级链"]
    A7 --> Z4["影响面 · 双引擎<br/>Java 跨文件 / 其余文件内"]

    Z3 --> V1["OsvVulnSource 真实漏洞库<br/>OSV /v1/query 逐组件<br/>Maven group:artifact · npm 剥 semver<br/>severity 归一 + aliases 取 CVE<br/>代理仅本源出站"]
    Z3 --> V2["BuiltinVulnSource 内置样本<br/>离线兜底 + 确定性单测 fixture<br/>绝不冒充真实扫描"]
    Z3 --> V3["结果标注<br/>报告 + CycloneDX-lite SBOM<br/>sourceUsed / degraded"]

    GW --> L1["直连供应商<br/>包 UsageReportingProvider 补报"]
    GW --> L2["TokenFactoryChatProvider<br/>工厂路由 / 计价 / 计量"]

    A1 --> P["AbstractReviewAgent<br/>公共三段式"]
    A2 --> P
    A3 --> P
    A4 --> P
    A5 --> P

    P --> P0["⓪ supports 内容准入<br/>纯文档 PR 跳过语义 Agent"]
    P --> P1["① 逐文件注入分级"]
    P --> P5["⑤ diff 字符预算<br/>按文件均摊截断 + 标注"]
    P --> P2["② Skill 并行预扫描"]
    P --> P3["③ 置信度校准"]
    P --> P4["④ LLM 三级降级"]

    classDef root fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef agent fill:#F3F8F4,stroke:#2c7d2c,stroke-width:1.5px,color:#1f2933;
    classDef custom fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    classDef leaf fill:#FFFFFF,stroke:#9aa5b1,stroke-width:1px,color:#52606d;
    class ROOT,BI root;
    class A1,A2,A3,A4,A5 agent;
    class A6,A7,GW,S3,S5,Z1,Z2,Z3,Z4,D1,D5,L1,L2,QA,RAG custom;
    class V1,V2,V3 leaf;
    class S1,S2,S4,P,P1,P2,P3,P4,D2,D3,D4 leaf;
```

> 源码：`docs/diagrams/l7-capability.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 5 个内置 Agent | `core/agent/impl/{Security,Logic,Performance,Architecture,Style}Agent.java` |
| 自定义 Agent | `core/agent/DeclarativeReviewAgent.java`；管理接口 `core/admin/AgentAdminController.java` |
| 高级静态分析 + 影响面 | `core/analysis/{AdvancedAnalyzer,AstAnalyzer,CallGraphAnalyzer,ScaScanner}.java`；`core/analysis/index/` |
| 注入检测四件套 | `core/security/{DiffInputGuard,DiffInjectionDetector,StegInjectionScanner,SemanticInjectionDetector}.java` |
| 写库边界预检 | `core/security/ContentInjectionDetector.java` |
| Token 工厂接入 | `core/tokenfactory/{TokenFactoryChatProvider,UsageReportingProvider}.java` |
| Skill 注册与运行期启停 | `core/skill/SkillRegistry.java:201-267` |
| RAG 检索子系统 | `core/rag/{DiffQueryExtractor,ReviewQueryRewriter,StructuredChunker,TextTokenizer,PgKnowledgeStore,RagEvaluator}.java` |
| LLM 指标端点 | `core/admin/LlmHealthController.java` GET /api/admin/llm/{health,stats,usage,trace} |
| 网关配额竞态修复 | `core/llm/ModelGateway.java:258-283` |
| SCA 依赖安全双源与降级链 | `core/analysis/{ScaScanner,OsvVulnSource,BuiltinVulnSource}.java`；装配 `config/ScaSourceConfig.java` |
| 死代码门禁 | `src/test/java/com/codereview/agent/DeadCodeGuardTest.java` |

> **注意**
> **安全检测按信任边界分层，不同边界用不同强度**：
>
> - **diff 预检**（进 LLM 前）：关键词 + 隐写字符命中 → BLOCK 隔离；语义近似 → TAG 标注。**此处只隔离/标注，不硬拦截**——基座词表里的「越权 / admin mode / 泄露系统提示」在代码审查域是正常业务词（"检查越权风险"曾被直接拦掉）；
> - **写库边界**（自定义 Agent 存库前，本仓库唯一把业务方文本提升为系统提示的位置）：才做异常填充 + 关键词 + 语义复合检测，命中即拒绝。
>
> 中文长描述香农熵天然偏高，**熵检测只对纯 ASCII 生效**，否则误杀。
> **SCA 的「同源」原则**：`sca.source=auto` 时生产 / 开发 / 测试**一律先查 OSV 真实漏洞库**，只有 OSV 不可达才降级内置样本——此前内置 CVE 样本在所有环境冒充真实扫描，**dev 里看着有结果、线上是假数据**，是最难发现的一类 bug。三态语义必须分清：`auto`=OSV 失败降级内置并标 `degraded`；`osv`=强制，失败标 `failed` **不造假**；`builtin`=离线 / CI 断网。报告与 CycloneDX-lite SBOM 都带 `sourceUsed`，事后可追溯数据来源。OSV 走**仅本数据源**的 HttpClient 代理出站，不污染内网直连。
> **死代码门禁**：2026-09-08 清理掉 `core/mq`（7 类）`core/tools/external`（2 类）`core/eval/ReviewReplay` 及其 Redis 依赖链——共同点是 **main 生产零引用、只有测试在引用**。此后 `DeadCodeGuardTest` 把「main 生产零引用且非豁免」判为 CI 失败；**@Component 不在豁免名单**（孤儿 Bean 正是上次的主角）。删测试后必须 clean 再回归，否则 target 残留旧 class 让计数虚高。


---

code-review-agent · 业务流程图集 · 全部节点均可在 `src/main/java/com/codereview/` 下按文件路径 grep 复现

mermaid 源码：`docs/diagrams/*.mmd`
