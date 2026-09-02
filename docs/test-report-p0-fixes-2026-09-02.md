# 测试报告 — 三个 P0 缺陷真实修复 + 降级可观测性（2026-08-31）

> 结论：**162 个测试全部通过（0 失败 0 错误），37 个测试类，BUILD SUCCESS。**
> 本轮新增 **21 个测试**（协调器超时降级 4 + 模型网关降级 6 + 置信度校准闭环 6 + 报告降级 5），旧有 141 例零改动通过，无回归。
> 对应代码：`CompletableFutureCoordinator` / `ModelGateway` / `ConfidenceCalibrationService` / `ReviewReport` / `AgentResult` / `ReportGenerator` / `FeedbackStore` 系。

## 一、修复的三个 P0 + 一个可观测性增强

缺陷源自《架构流程图与面试深挖手册》（`docs/interview-walkthrough.md`）自查指认，本次全部真实修复并用测试锁定：

| 缺陷 | 修复前行为 | 修复后行为 | 关键代码 |
|------|-----------|-----------|---------|
| **P0-1 超时挂错位置** | `orTimeout` 挂在 `allOf` 聚合 future 上只打 warn；随后逐个 `join()` **无超时仍会无限阻塞**主线程；`advancedFuture`（AST/SCA）连超时体系都没进 | 以同一 `deadline` 对**每个 future 逐个** `get(remaining, MILLISECONDS)` 限时取；`advancedFuture` 纳入同一体系；超时/异常 → `cancel(true)` + 该 Agent 标 `degraded` 进报告 | `CompletableFutureCoordinator.java:375-430` |
| **P0-2 网关静默失败** | 全供应商 + Mock 都失败时 `return ""`，上层解析成「0 条发现」→ 产出**「看起来通过」的假报告** | 抛 `ModelUnavailableException`（带 providerCount/attempts/mock 状态）；装配时**有真实 Key 即禁 Mock 兜底**（`allowMockFallback = !tokenHub.hasKey()`），Mock 仅离线演示保留 | `ModelGateway.java` + `ReviewAgentConfig.java` |
| **P0-3 置信度校准空转** | `calibrate()` 被调用但 `ruleAccuracy` 依赖的 `markFalsePositive/markTruePositive` **全仓零调用方** → 恒为空 → `calibrate` 退化成「乘 1.0」恒等函数 | 新增 `FeedbackListener` SPI，`FeedbackStore.save` 末尾广播；`ConfidenceCalibrationService implements FeedbackListener` 按 `isFalsePositive` 分派 mark；加防「一票否决」：误报指数衰减下限 `MIN_ACCURACY=0.5`，正报回升封顶 1.0 | `FeedbackListener` + `ConfidenceCalibrationService` + 两个 FeedbackStore |
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

### ModelGatewayDegradationTest（6）— P0-2
- mock 关闭 + 全供应商失败 → 抛 `ModelUnavailableException`（providerCount=2、attempts=2、`totalFailures` 计 1）
- 默认构造允许兜底但列表无 mock → 仍抛（不留假成功）
- mock 兜底成功 → `mockFallbacks=1`
- happy path → 无降级计数
- 不可用供应商被跳过 → 不计 attempts
- 禁用兜底 → `mockFallbacks=0` / `totalFailures=1`

### CalibrationFeedbackLoopTest（6）— P0-3
- 一次误报 → `accuracy("SEC-001")==0.8` 且 `calibrate("SEC-001", 0.9)==0.72`
- 正报回升（×1.05）且封顶 1.0
- 50 次误报后 `accuracy >= 0.5`（防一票否决下限生效）
- 空白/null ruleId 忽略
- `FileFeedbackStore`（带校准监听器）同样驱动 → 闭环与存储实现无关
- listener 抛异常**不阻断**反馈保存（观测旁路原则）

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
