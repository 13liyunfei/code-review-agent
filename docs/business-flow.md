# code-review-agent · 完整业务流程图集

从「PR 被推送」到「评论回写平台」的全链路，逐层拆解到单 Agent 内部与聚合仲裁细节。

- 入口：`GiteaWebhookController` · `GitLabWebhookController` · `ScheduledScanService` · `ReviewApiController` · `IdeReviewServer`
- 编排：`GiteaReviewService` → `CompletableFutureCoordinator` → `ReportGenerator`
- 状态层：`PgDb` → PostgreSQL 8 张表（`pgvector.enabled` 开关，未启用则回退内存并告警）

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
        S3["定时<br/>全量扫描"]
        S4["REST<br/>API"]
        S5["IDE<br/>本地审查"]
    end

    subgraph ENG["② 审查引擎 :8080"]
        direction TB
        E1["接入层 · 验签 / 事件过滤 / 异步分发"]
        E2["编排层 · GiteaReviewService"]
        E3["协调层 · CompletableFutureCoordinator"]
        E4["执行层 · 5 内置 + N 自定义 + AdvancedAnalyzer"]
        E5["聚合层 · ReportGenerator"]
        E6["输出层 · AutoFixEngine / ReviewWorkflowEngine"]
        E1 --> E2 --> E3 --> E4 --> E5 --> E6
    end

    subgraph EXT["③ 外部依赖"]
        direction LR
        X1["LLM 网关<br/>熔断 / 退避重试 / 路由"]
        X2["LangChain4j<br/>AiServices"]
        X3["agent-kit<br/>基座"]
        X4["Token 工厂<br/>公司级计量计费"]
    end

    subgraph OUT["④ 平台回写 · 4 类"]
        direction LR
        O1["PR 顶层<br/>评论"]
        O2["行内<br/>suggestion"]
        O3["Issue<br/>整改工单"]
        O4["Commit<br/>Status"]
    end

    subgraph STATE["⑤ 状态层 · PostgreSQL（多实例共享，进程无本地状态）"]
        direction LR
        T1["PgDb · 统一连接池<br/>HikariCP max 12"]
        T2["8 张状态表<br/>每行带 team_id"]
        T3["StateStoreConfig<br/>pgvector.enabled 开关<br/>关闭则内存回退 + 告警"]
    end

    SRC --> E1
    E4 -.-> EXT
    X1 -.->|"直连时补报用量"| X4
    E6 --> OUT
    E3 -.->|"断点 / 轨迹落库与续跑"| STATE
    E4 -.->|"经验 / 校准 / 反馈<br/>抑制源回读"| STATE

    classDef grp fill:#FBFCFB,stroke:#2c7d2c,stroke-width:1.5px;
    classDef n fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef ext fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    classDef store fill:#F3F8F4,stroke:#2c7d2c,stroke-width:1.5px,color:#1f2933;
    class SRC,ENG,OUT,STATE grp;
    class S1,S2,S3,S4,S5,E1,E2,E3,E4,E5,E6,O1,O2,O3,O4 n;
    class X1,X2,X3,X4 ext;
    class T1,T2,T3 store;
```

> 源码：`docs/diagrams/l0-overview.mmd`

> **要点**
> **拓扑是「星型汇聚」而非流水线**：5 个内置 Agent 之间互不通信、无依赖，协作只发生在聚合阶段（去重 / 仲裁 / 抑制）。
> **LLM 侧已接入公司级 Token 工厂**：工厂供应商与直连供应商同列候选，复用已有 failover；直连时由 `UsageReportingProvider` 把用量补报回工厂（无 usage 按字符数估算并打 estimated 标记）。
> **状态层已全量迁 PostgreSQL**：断点 / 经验 / 校准 / 反馈 / 历史 / 轨迹 / 团队配置 / 知识元数据共 8 张表，全部带 `team_id`；`pgvector.enabled=true` 时多实例读写同一份状态，**审查进程不再落任何本地状态文件**。

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
    J --> K["fetchPrChanges<br/>拉取 PR diff 与元信息"]
    K --> L{"diffs 为空<br/>或拉取失败?"}
    L -->|"是"| L1["postSkipNote<br/>说明跳过原因"]
    L -->|"否"| M["构造 PullRequest 模型<br/>携带 teamId 做租户隔离<br/>透传 headSha 供影响面索引与续跑键"]
    M --> N["Coordinator.review<br/>多 Agent 并行审查 → 见 L2"]

    N --> O{"审查抛异常?"}
    O -->|"是"| O1["postSkipNote<br/>引擎异常说明"]
    O -->|"否"| P["AutoFix 生成修复建议 Markdown"]
    P --> Q["WorkflowEngine.handle<br/>工单 / 提交状态 → 见 L5"]
    Q --> R["buildReviewComment<br/>报告 + 修复 + 工作流"]
    R --> S["postPrComment<br/>回写 PR 顶层评论"]

    S --> T["AutoFix 生成结构化修复项"]
    T --> U["过滤可锚定项<br/>包装为 suggestion 代码块"]
    U --> V["postReviewComments<br/>一次性提交 comments 数组"]

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
    class L,O dec;
    class L1,O1 fail;
```

> 源码：`docs/diagrams/l1b-async-review.mmd`

**源码锚点**

| 说明 | 位置 |
| --- | --- |
| 主链路编排（含 headSha 透传） | `integration/gitea/GiteaReviewService.java:99-192` |
| headSha 缺失时回落 sourceBranch | `:129` |
| 报告 Markdown 拼装 | `integration/gitea/GiteaReviewService.java:199-219` |

> **注意**
> **Gitea 1.27 的平台限制（踩过的坑）**
>
> - 逐条直发行内评论的接口返回 **405**，唯一入口是 `POST /pulls/{index}/reviews` + `comments[]` + `event:COMMENT` 一次性提交；
> - PENDING 预建方式下 `line`/`side` 会被丢弃，恒降级为文件级评论 → 可采纳修复必须写进「顶层概览 + 行内 suggestion」双通道。

---

<a id="l2a"></a>

## L2a · 协调器 · 准备与并行调度

**runId 由 PR 身份派生**（不再用随机 traceId）→ 断点续跑 → 影响面与 RAG 上下文增强 → 自定义 Agent 展开 → 规划/固定两种并行路径。

```mermaid
flowchart TD
    S["Coordinator.review(pullRequest)"] --> T["runId 由 PR 身份派生：repo 编号 @ headSha<br/>斜杠转下划线，traceId 设为同值，轨迹 begin"]

    T --> RS{"断点续跑<br/>存在同 runId 快照?"}
    RS -->|"命中"| RS1["加载 ResumeState<br/>已完成结果直接复用"]
    RS -->|"未命中"| IA
    RS1 --> IA

    IA["影响面分析 ImpactIndexBuilder<br/>按 head SHA 拉全量源码建索引 → 详见 L2c"] --> RAG
    RAG["RagContextBuilder 检索团队规范<br/>合并为 enrichedCtx（影响面 + RAG）"]

    RAG --> CA{"有启用中的<br/>自定义 Agent?"}
    CA -->|"是"| CA1["展开 DeclarativeReviewAgent<br/>按 teamId 隔离"]
    CA -->|"否 / 异常"| CA2["仅 5 个内置 Agent"]
    CA1 --> PL
    CA2 --> PL

    PL{"review.planning<br/>.enabled?"}
    PL -->|"开"| PL1["TaskPlanningSupport<br/>LLM 拆子任务 DAG 并行"]
    PL -->|"关 / 失败"| PL2["固定并行路径"]

    PL1 --> FU
    PL2 --> FU
    FU["为每个 Agent 建 CompletableFuture<br/>TraceContext.wrap 跨线程"] --> ADV["并行 AdvancedAnalyzer<br/>AST / 调用图 / SCA"]
    ADV --> NB(["转入限时收口 → 见 L2b"])

    classDef start fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef proc fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef dec fill:#FFF7ED,stroke:#b45309,stroke-width:1px,color:#7c3d0b;
    classDef warn fill:#FEF2F2,stroke:#b91c1c,stroke-width:1px,color:#991b1b;
    classDef enhance fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    class S start;
    class T,IA,ADV,FU proc;
    class RS,CA,PL dec;
    class RS1,CA2 warn;
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

> **注意**
> **runId 曾用随机 traceId，导致断点续跑在生产上永远不生效**：崩溃重试是新 HTTP 请求，拿到另一串随机 traceId，`resumeStore.load(runId)` 永远查不到断点——典型的「feature 只在测试里跑过、线上是死代码」。现在从 PR 身份派生稳定键 `repo#编号@headSha`（`/`→`_`，因 runId 同时被当作文件名）；含 head SHA 保证换新 commit 时重开审查而非续跑旧断点。

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

<a id="l3"></a>

## L3 · 单 Agent 三段式与三级降级

每个 Agent 内部三段：**① 输入防护（逐文件分级隔离）** → **② Skill 确定性预扫描** → **③ LLM 语义增强**。规则出确定结论，LLM 只做补充。

```mermaid
flowchart TD
    A["agent.review(diffs, enrichedCtx)"] --> B{"① 输入防护<br/>DiffInputGuard 逐文件分级"}

    B -->|"BLOCK<br/>隐写字符 / 关键词 HIGH"| B1["产文件级 BLOCKER（带行号）<br/>该文件隔离，不进 LLM"]
    B -->|"TAG<br/>关键词 LOW / 语义近似"| B2["渲染时标注「数据非指令」<br/>仍参与审查"]
    B -->|"CLEAN"| B3["正常放行"]

    B1 --> C
    B2 --> C
    B3 --> C

    C["② Skill 预扫描<br/>registry 按 teamId + 分类取技能<br/>parallelStream 并行，全量 diff 照跑"] --> D["SkillResult → Finding<br/>按 ruleId 历史准确率校准置信度"]

    D --> G{"还有未被 BLOCK<br/>隔离的文件?"}
    G -->|"否"| G0["跳过 LLM 语义审查<br/>仅保留规则发现"]
    G -->|"是"| H{"③ LLM 增强<br/>优先 AiServices<br/>结构化输出 + ChatMemory"}
    H -->|"成功且非空"| H1["映射为 Finding<br/>来源 LLM"]

    H -->|"失败 / 空"| I{"次选 agent-kit<br/>类型推导 schema"}
    I -->|"成功"| I1["映射为 Finding<br/>来源 LLM"]
    I -->|"结构化失败<br/>但原文非空"| J["复用原始输出做文本解析<br/>不再多调一次模型"]
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
    class C,D,M proc;
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
        P6[("knowledge_meta<br/>RAG 知识元数据")]
        P7[("experience_entry<br/>四态 + 证据计数")]
        P8[("calibration_accuracy<br/>rule_id PK")]
    end

    subgraph OUT["消费侧"]
        direction TB
        C1["崩溃后同 runId 续跑<br/>runId 由 PR 身份派生"]
        C2["computeVerification<br/>已解决 / 新引入"]
        C3["aggregate 阶段<br/>误报抑制"]
        C4["ReviewReplay 回放"]
        C5["runSkills 规则生效<br/>跨实例 2s 收敛"]
        C6["RagContextBuilder<br/>注入提示词"]
        C7["经验检索注入<br/>仅 CANDIDATE + ACTIVE"]
        C8["ConfidenceCalibration<br/>置信度校准"]
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

    G[("__global__ 基线<br/>团队配置叠加")] -.->|"缺失回退默认"| STORE
    F[("pgvector.enabled=false<br/>InMemory 回退 + 启动告警")] -.->|"单机 / 测试"| STORE

    classDef grp fill:#FBFCFB,stroke:#2c7d2c,stroke-width:1.5px;
    classDef n fill:#FFFFFF,stroke:#2c7d2c,stroke-width:1px,color:#1f2933;
    classDef store fill:#F3F8F4,stroke:#2c7d2c,stroke-width:1.5px,color:#1f2933;
    classDef base fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    class IN,STORE,OUT grp;
    class W1,W2,W3,W4,W5,W6,W7,W8,C1,C2,C3,C4,C5,C6,C7,C8 n;
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

    HIT["检索命中 recordHit<br/>刷新 lastHitAt + hitCount"] -.->|"spaced repetition<br/>常用经验不衰退"| S2
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
    class MAINT,HIT,ADMIN side;
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

> **要点**
> **反遗忘靠 spaced repetition**：每次检索命中都刷新 `lastHitAt` 与 `hitCount`，被反复用到的经验永远不会进入 TTL 归档——遗忘只清理「记忆衰退」的冷门条目，常用经验不衰退。

> **注意**
> **阈值是 SQL 常量**（`ACTIVE_EVIDENCE = 3` / `ARCHIVE_NEGATIVE = 2`），升级判定在 `UPDATE ... SET stage = CASE WHEN ...` 内**原子完成**，不存在读-改-写竞态。
> **坑**：Spring `Duration` 绑定不带单位的纯数字按**毫秒**算——`experience-idle: 180` 会变成 180 毫秒，一轮清空全部经验。默认值一律写成 `180d` / `90d`。
> 维护任务**与断点清理 03:30、巡检 02:00 错峰**在 03:40 执行，异常只记 WARN 跳过本轮，不影响审查主链路。

---

<a id="l7"></a>

## L7 · 能力矩阵 · Agent / Skill / 分析引擎 / 安全检测器

执行层的完整构成：5 内置 Agent + 团队自定义 Agent + 高级静态分析（含影响面双引擎）；Skill 按 category 挂载，支持运行期启停与团队自定义规则。

```mermaid
flowchart LR
    ROOT["执行层<br/>effectiveAgents"] --> BI["内置 Agent"]
    ROOT --> A6["CUSTOM<br/>DeclarativeReviewAgent<br/>按 teamId 隔离"]
    ROOT --> A7["AdvancedAnalyzer<br/>静态分析"]
    ROOT --> GW["LLM 网关<br/>优先级路由 + 熔断"]

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
    A7 --> Z3["ScaScanner<br/>依赖安全"]
    A7 --> Z4["影响面 · 双引擎<br/>Java 跨文件 / 其余文件内"]

    GW --> L1["直连供应商<br/>包 UsageReportingProvider 补报"]
    GW --> L2["TokenFactoryChatProvider<br/>工厂路由 / 计价 / 计量"]

    A1 --> P["AbstractReviewAgent<br/>公共三段式"]
    A2 --> P
    A3 --> P
    A4 --> P
    A5 --> P

    P --> P1["① 逐文件注入分级"]
    P --> P2["② Skill 并行预扫描"]
    P --> P3["③ 置信度校准"]
    P --> P4["④ LLM 三级降级"]

    classDef root fill:#2c7d2c,stroke:#2c7d2c,stroke-width:2px,color:#fff;
    classDef agent fill:#F3F8F4,stroke:#2c7d2c,stroke-width:1.5px,color:#1f2933;
    classDef custom fill:#EEF2FF,stroke:#1d4ed8,stroke-width:1px,color:#1e3a8a;
    classDef leaf fill:#FFFFFF,stroke:#9aa5b1,stroke-width:1px,color:#52606d;
    class ROOT,BI root;
    class A1,A2,A3,A4,A5 agent;
    class A6,A7,GW,S3,S5,Z1,Z2,Z3,Z4,D1,D5,L1,L2 custom;
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

> **注意**
> **安全检测按信任边界分层，不同边界用不同强度**：
>
> - **diff 预检**（进 LLM 前）：关键词 + 隐写字符命中 → BLOCK 隔离；语义近似 → TAG 标注。**此处只隔离/标注，不硬拦截**——基座词表里的「越权 / admin mode / 泄露系统提示」在代码审查域是正常业务词（"检查越权风险"曾被直接拦掉）；
> - **写库边界**（自定义 Agent 存库前，本仓库唯一把业务方文本提升为系统提示的位置）：才做异常填充 + 关键词 + 语义复合检测，命中即拒绝。
>
> 中文长描述香农熵天然偏高，**熵检测只对纯 ASCII 生效**，否则误杀。


---

code-review-agent · 业务流程图集 · 全部节点均可在 `src/main/java/com/codereview/` 下按文件路径 grep 复现

mermaid 源码：`docs/diagrams/*.mmd`
