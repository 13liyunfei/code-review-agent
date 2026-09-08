# 面试手册缺陷审计 → 全量落地 测试报告（2026-09-08）

> 范围：`docs/interview-walkthrough.md` 中标注「仍在 / 缺口」的【缺陷与改进】项，逐条核对代码现状（git + grep + 读码三证据），区分「文档滞后（已修未同步）」与「真缺陷（当场修）」，并对真实仍存在的缺陷做全面落地优化。
> 分支：`feature/dead-code-cleanup`（基于 ff2feb5）；测试 296 例全绿（281 基线 + 15 本轮新增）。

## 1. 审查结论矩阵（文档声明 vs 真实状态）

| 文档缺陷项 | 文档标注位置 | 审计结论 | 处置 |
|---|---|---|---|
| 无熔断半开 | `ModelGateway`「仍在」 | **文档滞后**：`CircuitBreakerProvider` 三态机（CLOSED/OPEN/HALF_OPEN）已实现并接线，默认开启 | 刷新文档 7.1 |
| 静默返回空串 | `ModelGateway`（P0-2） | 已修复（文档已同步） | — |
| 配额计数竞态 | `ModelGateway.java:180-189`「仍在」 | **真缺陷**：`incQuota` 锁外自增，窗口翻转与自增跨临界区 → 超发 | ✅ 本轮修复 |
| runId ≡ traceId 断点续跑无效 | 2.3 / Q7 / 9.1-P2「仍在」 | **文档滞后**：`resumeKey` 稳定派生已实现 | 刷新文档 2.3/Q7/8 |
| embedding 维度默认值 | 9.1-P0「待改」 | **文档滞后**：yml 已统一 1024 + 禁止覆盖注释 | 刷新文档 Q10/9.1 |
| SCA 硬编码 8 条 CVE | 4.2「生产应接 OSV」 | **文档滞后**：已接 OSV（OsvVulnSource/BuiltinVulnSource/ScaSourceConfig） | 刷新文档 4.2 |
| 网关不重试（在 LangChain4j 层） | 7.1「网关自身不重试」 | **文档滞后**：网关已内置 BackoffPolicy 退避重试 + RetryClassifier + maxAttempts | 刷新文档 7.1 |
| Agent 无 supports() 谓词 | 2.1 / Q5 / 9.1-P1 | **真缺陷** | ✅ 本轮落地 |
| diff token 预算缺失 | 4.4 / Q6 / 9.1-P1 | **真缺陷** | ✅ 本轮落地 |
| AggregateTracer 指标死数据 | 8 节 / 9.1-P2 | **真缺陷** | ✅ 本轮落地 |
| webhook 幂等缺失（Gitea + GitLab） | L80「大方承认的缺口」 | **真缺陷**（Gitea）；GitLab 侧无 headSha 贯穿需先扩展 | ✅ 本轮 Gitea + GitLab 同构落地 |
| 死代码 | 9.1-P3 | 已清理（ff2feb5，文档已同步） | — |

## 2. 本轮落地 6 项（均带测试锁定）

### ① 配额计数竞态（`ModelGateway.java:258-283`）
修复前 `quotaExceeded` 在 `synchronized(qs)` 内做窗口翻转、`incQuota` 在锁外自增 → 翻转与自增跨临界区：翻转瞬间旧窗口计数被清零，该窗口实际调用数被低估而**超发**。修复 = `incQuota` 与 `quotaExceeded` 共用 `refreshWindow`，统一在锁内做「窗口翻转 + 自增」。

### ② supports() 内容准入
- `ReviewAgent` 新增 `default boolean supports(List<CodeDiff>, ReviewContext)`（恒 true，向后兼容，`ReviewAgent.java:34-50`）
- Logic/Perf/Style/Arch 覆写为 `CodeDiff.containsCodeFile(diffs)`（`CodeDiff.java:49-56`：language ≠ unknown 即代码内容；xml/sql 视为代码）
- Security 恒跑（不覆写）；`ToolEquippedAgent` 透传 delegate；`DeclarativeReviewAgent` 等走接口默认
- `CompletableFutureCoordinator.java:405-425` 调度前过滤，剔除记 `agent.skipped-by-supports` 轨迹，**不产生降级语义**

### ③ diff 字符预算（`AbstractReviewAgent.java:68,175-227`）
`volatile int diffCharBudget` 默认 **-1 不截断**（向后兼容）；超预算按**文件均摊**截断（单文件下限 200 字符，保证每个文件头部可见），末尾追加截断统计标注（模型明确知道 diff 不完整）。注入：`application.yml:129`（`review.prompt.diff-char-budget`，env `REVIEW_DIFF_CHAR_BUDGET`）→ `ReviewAgentConfig.java:549`。

### ④ AggregateTracer 指标端点（`LlmHealthController.java:103-113`）
`@Autowired(required=false)` 注入 agent-kit `AggregateTracer`（未装配返回 unavailable 而非 500）；新增 `GET /api/admin/llm/trace` 暴露 `snapshot()` + `byOperation()` —— 指标从「只写不读的死数据」变闭环。

### ⑤ Gitea webhook 幂等（`GiteaReviewService.java:125-136`）
注入 `ReviewHistoryStore`；审查前按 `PullRequest.resumeKey(owner/repo, prNum, headSha)`（与 Coordinator 断点键同源）查最近历史，runId 相等即静默跳过 —— webhook 重复投递/手动重推不再重复审查。headSha 来自 webhook，拉取前判重零成本。

### ⑥ GitLab webhook 幂等（`GitLabApiClient` / `GitLabReviewService`）
GitLab webhook 载荷不贯穿 head SHA → 给 `MrChanges` 增加 `sha` 字段（`/changes` 响应顶层 `sha`，`GitLabApiClient.java:92,110`），判重放在 `fetchMrChanges` 之后（多一次轻量 fetch，远便宜于重复审查）；`PullRequest` 全量构造首次透传 headSha（影响面/断点键与 Gitea 同源）。

**关键重构**：Coordinator 私有 `resumeKey(pr)` 上移为 `PullRequest.resumeKey(repo, prId, headSha)` 公共静态方法（`PullRequest.java:90-97`，repo 的 `/`→`_` 保证文件系统安全）—— 断点、历史判重、webhook 判重三处共用一套键，杜绝格式漂移。

## 3. 测试

| 测试类 | 例数 | 覆盖 |
|---|---|---|
| `CoordinatorSupportsRoutingTest` | 3 | containsCodeFile 判定口径（unknown 非代码/xml·sql 视为代码/null 安全）；纯文档 PR 语义 Agent 零调用 + Security 恒跑；含代码 PR 全跑 |
| `DiffCharBudgetTest` | 4 | 预算关闭全量无标注；预算充足全量无标注；单文件超限截断 + 双标注；多文件均摊不偏向列表头部 |
| `GiteaReviewIdempotencyTest` | 5 | resumeKey 稳定且文件系统安全；同 headSha 重复投递静默跳过（Coordinator/API 零调用）；新 headSha 触发重审；headSha 空/无 historyStore 判重关闭 |
| `GitLabReviewIdempotencyTest` | 3 | 同 headSha 判重命中（拉取后）；新 headSha 触发重审回写；无 historyStore 判重关闭 |
| **合计新增** | **15** | |

**全量回归**：`./mvnw -o test` → `Tests run: 296, Failures: 0, Errors: 0, Skipped: 0`（既有 281 例零破坏；曾出现 1 例 PgClusterE2eTest 偶发失败，单跑复跑 5/5 全绿，判定为 PG 连接抖动非代码回归）。

## 4. 文档对账（`docs/interview-walkthrough.md`）

- 刷新滞后声明：2.1 supports 落地、2.3/Q7 runId 已改派生、4.2 静态分析已升级（JavaParser+tree-sitter+OSV）、4.4/Q6 diff 预算落地、7.1 网关缺陷表全清（熔断/配额/重试）、8 节 /trace 端点 + runId 语义、Q10 embedding 已统一、9.1 优先级表 P0–P3 全清
- 1.1 幂等缺口注记 → ✅ 已落地说明（Gitea 拉取前 / GitLab 拉取后）
- 9.1 新增「仍开放」4 条（诚实边界）：配额按租户隔离 / `cancel(true)` 不中断线程 / 幂等失效窗口（审查中崩溃且未落历史）/ diff 预算结构级优化
- 附录 grep 验证清单追加 2026-09-08 第二轮验证块

## 5. 遗留与建议

- **仍开放项**（见 9.1「仍开放」表）：模型配额按租户隔离、CompletableFuture 线程不可中断、幂等失效窗口、diff 预算结构级优化
- README 能力章节存在早于本轮的若干历史失真（resume/FileResumeStore、trajectory data-dir 等），建议后续统一校对（与死代码清理 PR 汇报一致）
- 工作区 `docs/business-flow.*` 改名噪音来自外部预览进程，提交时精确 add 已避开
