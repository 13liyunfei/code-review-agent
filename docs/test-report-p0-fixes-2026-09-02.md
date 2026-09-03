# 测试报告 — 三个 P0 缺陷真实修复 + 降级可观测性（2026-08-31）

> 结论：**162 个测试全部通过（0 失败 0 错误），37 个测试类，BUILD SUCCESS。**
> 本轮新增 **21 个测试**（协调器超时降级 4 + 模型网关降级 4 + 置信度校准闭环 8 + 报告降级 5——2026-09-03 去 Mock 后内部构成微调，总数不变），旧有 141 例零改动通过，无回归。
> 对应代码：`CompletableFutureCoordinator` / `ModelGateway` / `ConfidenceCalibrationService` / `ReviewReport` / `AgentResult` / `ReportGenerator` / `FeedbackStore` 系。

## 一、修复的三个 P0 + 一个可观测性增强

缺陷源自《架构流程图与面试深挖手册》（`docs/interview-walkthrough.md`）自查指认，本次全部真实修复并用测试锁定：

| 缺陷 | 修复前行为 | 修复后行为 | 关键代码 |
|------|-----------|-----------|---------|
| **P0-1 超时挂错位置** | `orTimeout` 挂在 `allOf` 聚合 future 上只打 warn；随后逐个 `join()` **无超时仍会无限阻塞**主线程；`advancedFuture`（AST/SCA）连超时体系都没进 | 以同一 `deadline` 对**每个 future 逐个** `get(remaining, MILLISECONDS)` 限时取；`advancedFuture` 纳入同一体系；超时/异常 → `cancel(true)` + 该 Agent 标 `degraded` 进报告 | `CompletableFutureCoordinator.java:375-430` |
| **P0-2 网关静默失败** | 全供应商 + Mock 都失败时 `return ""`，上层解析成「0 条发现」→ 产出**「看起来通过」的假报告** | 抛 `ModelUnavailableException`（带 providerCount/attempts/累计失败）；**2026-09-03 起 Mock 兜底彻底移除**：假模型类删除、装配不再注入 mock 供应商、未配 Key 直接启动失败（fail-fast） | `ModelGateway.java` + `ReviewAgentConfig.java` |
| **P0-3 置信度校准空转** | `calibrate()` 被调用但 `ruleAccuracy` 依赖的 `markFalsePositive/markTruePositive` **全仓零调用方** → 恒为空 → `calibrate` 退化成「乘 1.0」恒等函数 | 新增 `FeedbackListener` SPI，`FeedbackStore.save` 末尾广播；`ConfidenceCalibrationService implements FeedbackListener` 按 `isFalsePositive` 分派 mark；加防「一票否决」：误报指数衰减下限 `MIN_ACCURACY=0.5`，正报回升封顶 1.0；**2026-09-03 派生状态（ruleAccuracy）快照落 `<data-dir>/calibration/accuracy.json`，重启不丢失** | `FeedbackListener` + `ConfidenceCalibrationService` + 两个 FeedbackStore |
| **可观测性（配套）** | `AgentResult` 无状态字段——模型挂了/超时在最终报告里完全不可见 | `AgentResult.degraded` + `AgentDegradation(stage,reason)` + `ReviewReport.degradations` 列表 + `toMarkdown()` 在发现明细**之前**渲染「⚠️ 审查降级提示」告警块 | `AgentResult` / `AgentDegradation` / `ReviewReport` |

**修复中的两个隐性回归**（一并堵住）：
1. `timeoutMillis` 声明时无显式初值 → 非 Spring 装配（单测）下为 0 → `orTimeout(0)` **立即误触发**。修复：声明时显式 `= 300_000L` + `setTimeoutMillis()`（`<=0` 回退默认）。
2. 降级 Agent 也写断点 → 续跑把「超时/异常」当成「已完成」。修复：`saveCheckpoint` 跳过 `degraded()` 结果，续跑时重试。

## 二、新增测试明细（21 个）

### CoordinatorTimeoutDegradationTest（4）— P0-1
- **超时 Agent 被标记降级而非丢弃**：PERFORMANCE 睡 5s + SECURITY 秒回，`setTimeoutMillis(300)` → 断言报告在 `elapsed < 3000` 返回（不卡慢任务）、`report.degraded()`、降级列表含 `PERFORMANCE`+「超时」、健康 Agent 的发现保留
- **异常 Agent 标记降级而非静默消失**：抛 `RuntimeException` 的 LOGIC → 降级且根因消息透出，健康 Agent 不受拖累
- **高级静态分析超时记为基础设施降级**：阻塞 `AdvancedAnalyzer` 5s → `AgentDegradation.STAGE_ADVANCED_ANALYSIS` 降级、报告限时返回
- **健康执行无降级、不误超时**（回归）：非 Spring 构造默认 `timeoutMillis=300000` 生效，全健康无降级

### ModelGatewayDegradationTest（4）— P0-2（2026-09-03 去 Mock 后 6→4）
- 全供应商失败 → 抛 `ModelUnavailableException`（providerCount=2、attempts=2、`totalFailures` 计 1，消息不含 mock）
- happy path → 第一个可用供应商返回、无降级计数
- 不可用供应商被跳过 → 不计 attempts（但计入 providerCount）
- 失败消息携带真实 provider/attempt 计数
- ~~原 3 例 mock 行为用例~~（默认构造允许兜底 / mock 兜底成功计数 / 禁用兜底计数）随 Mock 代码一并删除

### CalibrationFeedbackLoopTest（8）— P0-3（2026-09-03 增补持久化 6→8）
- 一次误报 → `accuracy("SEC-001")==0.8` 且 `calibrate("SEC-001", 0.9)==0.72`
- 正报回升（×1.05）且封顶 1.0
- 50 次误报后 `accuracy >= 0.5`（防一票否决下限生效）
- 空白/null ruleId 忽略
- `FileFeedbackStore`（带校准监听器）同样驱动 → 闭环与存储实现无关
- listener 抛异常**不阻断**反馈保存（观测旁路原则）
- **准确率快照重启恢复**：同 data-dir 重建服务实例 → accuracy 从 `calibration/accuracy.json` 恢复，校准继续生效并再次持久化
- **内存模式不落盘**：无 data-dir 构造（单测默认）不写快照、行为不变

### ReportDegradationTest（5）— 可观测性
- 降级 AgentResult → `report.degraded()` + degradations 含 stage/reason，健康 finding 不变
- infra + agent 降级合并按 stage 去重（Agent 级优先）
- `toMarkdown()` 告警块含 ⚠️ 且位置在发现明细**之前**
- 健康报告无告警块
- `Arrays.asList(null, degraded)` 混合入参安全（`List.of` 禁 null 的规避）

## 三、全量回归

| 指标 | 结果 |
|------|------|
| 测试类 | 37（旧 33 + 新 4） |
| 测试总数 | **162**（旧 141 + 新 21） |
| 失败 / 错误 | **0 / 0** |
| 构建 | BUILD SUCCESS |

回归保护验证点：旧有 141 例**零改动通过**（Coordinator/Calibration 改动未破坏既有行为假设）；新增用例全部针对修复语义，不依赖旧缺陷行为。

## 四、测试过程中的两个契约修正（诚实记录）

1. **`CompletableFuture.cancel(true)` 不中断已运行的 `supplyAsync` 线程**——Java 只对未开始的 stage 传中断，已运行的线程收不到（与 `ExecutorService.submit` 的 FutureTask 不同）。最初两条用例断言「底层线程被中断」，实际失败。**修正**：测试只验收行为契约（超时按时返回、降级进报告、健康 Agent 不受影响）并在类 JavaDoc 记录该限制——真实 Agent 的阻塞 LLM 调用同样无法真正打断，验收点应是「主线程不被卡 + 降级可见」。
2. **`List.of(null, ...)` 抛 NPE**——测试要模拟 null 入参须用 `Arrays.asList`。
3. **varargs 构造重载歧义**——`StubProvider(name, boolean, Object...)` 与 `StubProvider(name, Object...)` 会互相吞参 → 改私有构造 + 静态工厂 `up/down`。

## 五、运行方式

```bash
# 新 21 例（快）
cd code-review-agent
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home \
  ./mvnw -o -B test -Dtest='CoordinatorTimeoutDegradationTest,ModelGatewayDegradationTest,CalibrationFeedbackLoopTest,ReportDegradationTest'

# 全量 162 例
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./mvnw -o -B test
```

---

## 六、2026-09-03 增补：校准派生状态落盘 + 全量去 Mock（162 例保持全绿）

> 本轮源自开发者评审反馈的三个问题（Q1 校准没落库 / Q2 底层数据为何存文件 / Q3 去掉所有 mock），Q1 与 Q3 落地为代码变更，Q2 为架构说明（见下）。

### 6.1 Q1：校准准确率（ruleAccuracy）落盘 —— 只持久化了事件、没持久化派生状态

**问题**：P0-3 闭环打通后，`FileFeedbackStore.save` 只把**原始反馈事件**落盘；而派生的 `ruleAccuracy`（规则准确率）只改 `ConcurrentHashMap`——进程重启即清空，校准学习全部归零，重新退化为「乘 1.0」。

**修复**：
- `ConfidenceCalibrationService` 每次 `markFalsePositive/markTruePositive` 后，把 `ruleAccuracy` 快照**原子写**到 `<data-dir>/calibration/accuracy.json`（tmp + `ATOMIC_MOVE`，与断点存储同款）；
- 构造时自动加载快照（损坏/缺失不阻断启动，退化为无学习记录）；
- 由 `@Service` 组件扫描改为 `ReviewAgentConfig` 显式 `@Bean`（需要注入 `review.data-dir`），保留无参构造 = 内存模式（单测友好）；
- 语义：反馈事件 = 事实日志（审计），ruleAccuracy = 可重建的派生缓存（快照）。两者都在 `review.data-dir` 下，k8s 部署时挂同一个 PVC 即全部保留。

**验证**：`CalibrationFeedbackLoopTest` 新增 2 例——同 data-dir 重建实例准确率从快照恢复且继续累计、内存模式不落盘。日志佐证：`[Calibration] 已从 .../calibration/accuracy.json 恢复 N 条规则准确率`。

### 6.2 Q3：生产与开发代码全量去 Mock（不再有任何假模型）

**删除**：`MockProvider`、`MockLlmClient`、`NoOpChatModel` 三个类整体删除。

**网关瘦身**（`ModelGateway`）：
- 删 `allowMockFallback` 字段/3 参构造/兜底分支与 `mockFallbacks` 计数——降级统计只剩 `DegradationStats(totalFailures)`；
- 全供应商失败一律抛 `ModelUnavailableException`，消息带 providerCount/attempts/累计失败，不再有「Mock」措辞。

**装配 fail-fast**（`ReviewAgentConfig`）：
- `llmClient` Bean 不再 `providers.add(new MockProvider())`，只装配真实 TokenHub 模型；
- `primaryChatModel` 未配 `tokenhub.api-key` / `models` 为空时抛 `IllegalStateException` 拒绝启动——没有 Key 就不要启动，而不是拿假输出撑场面；
- 同步清理：`application.yml` 注释、`ModelProvider`/`AbstractReviewAgent`/`CodeReviewAiService` JavaDoc 中所有 Mock 措辞。

**测试**：`ModelGatewayDegradationTest` 6→4（删 3 例 mock 行为用例、补 1 例失败消息计数断言）；`EnterpriseFeaturesTest.modelGatewayFallsBackToMock` 改为 `modelGatewayReturnsConfiguredRealProviderOutput`（真实供应商断言、describe 不含 mock）。全仓 grep 校验 `MockProvider|MockLlmClient|NoOpChatModel|allowMockFallback` 零命中。

### 6.3 Q2：文件存储与 k8s 的关系（说明，未改代码）

**为什么是文件**：引擎最初定位单机/自托管、零外部依赖可跑；数据量小（反馈/经验/配置 KB~MB 级）；JSON 可人工检查、`tmp + ATOMIC_MOVE` 不写半截；PG 只引入给 pgvector RAG（本就是 DB 选型）。所有 store 已收敛到同一个可配置根目录 `review.data-dir`（默认 `./data`）。

**k8s 风险**：Pod 文件系统是临时盘——重启/滚动更新/迁移节点即丢数据。若部署时**不挂 PVC**，等于每次部署都清空 `./data`。

**修复路径**（两档）：
1. **单实例 + PVC（改动为零）**：引擎所有文件 store 都在 `review.data-dir` 下，部署时把该目录挂 `emptyDir` → `PersistentVolumeClaim` 即可全部持久化（feedback/history/calibration/custom-agents/experience/mailbox/resume/trajectories 一次全保）。
2. **多副本/HA**：文件是单写者模型，多实例必须换共享存储——权威数据（`CustomAgentStore`/`FileFeedbackStore`/`ExperienceStore`/`FileReviewHistoryStore`/校准快照）改走 PG 实现（RAG 已有 `PgKnowledgeStore` 先例），轨迹/断点这类可再生成数据放 MinIO/对象存储。

**各 store 数据语义速查**：

| 存储 | 路径 | 性质 | 丢了会怎样 |
|---|---|---|---|
| FileFeedbackStore | `<team>/feedback.json` | 权威 | 校准与抑制依据丢失 |
| ConfidenceCalibrationService（新） | `calibration/accuracy.json` | 派生快照 | 校准学习归零 |
| CustomAgentStore | `<team>/custom-agents.json` | 权威 | 业务方自定义 Agent 丢失 |
| ExperienceStore | `<team>/experience.json` | 权威 | 反思经验丢失 |
| FileReviewHistoryStore | `<team>/review-history.json` | 权威 | 复检/质量趋势丢失 |
| TeamMailbox | `<team>/mailbox/<to>.json` | 恢复用 | 信箱消息丢失 |
| FileResumeStore | `<team>/resume/<runId>.json` | 检查点 | 可重跑（丢顶多重审该 PR） |
| ReviewTrajectoryRecorder | `<team>/trajectories/<runId>.jsonl` | 事件轨迹 | 可观测/回放丢失 |
