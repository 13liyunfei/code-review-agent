# 测试报告 — Agent 通用能力四件套落地（2026-08-27）

> 结论：**134 个测试全部通过（0 失败 0 错误），32 个测试类，BUILD SUCCESS。**
> 本轮新增 17 个测试（Tool Calling 5 + Planning 5 + Coordinator 规划集成 2 + 四件套能力 5），无回归。

## 一、本轮落地范围

| 能力 | 落地内容 | 接线点 | 开关（默认关） |
|------|---------|--------|--------------|
| ① Tool Calling 完整链路 | `core/toolcalling`：ToolCallingLoop 决策循环（思考→决策→调用→观察→推理）+ ToolRegistry + 3 内置工具 + **ToolEquippedAgent 装饰器** | `ReviewAgentConfig.reviewAgents`（按开关包装 5 内置） | `review.tools.agent-loop.enabled` |
| ② 任务拆解 + DAG | `core/planning`：TaskPlanner（LLM 拆解，失败降级直通）+ TaskPlan（无环校验）+ DagExecutor（拓扑并行，上游失败下游跳过）+ **TaskPlanningSupport 织入** | `CompletableFutureCoordinator.review()`（规划产出空则降级固定路径） | `review.planning.enabled` |
| ③ Memory 三层补实 | `core/memory`：**ExperienceStore**（文件经验条目 + 关键词检索，保留原 MemoryStore 向量检索 API 兼容）+ **ReflectionService**（报告反思沉淀经验） | `GiteaReviewService`（报告回写后反思入经验库） | `review.reflection.enabled` |
| ④ LLM eval | `core/eval`：**LlmJudge**（ground-truth precision/recall/F1 + llm-as-judge 真假阳性判定，LLM 失败自动跳过） | `GiteaReviewService`（审查完成输出评估日志） | `review.eval.enabled` |
| ⑤ 组件化机制 | `core/extension`：**ExtensionPoint**（标记 + order 织入序 + 扩展点清单）+ **ExtensionRegistry**（按类型组织、同名覆盖、线程安全） | `ReviewAgentConfig`（Registry Bean 供运行期注册） | — |

## 二、新增测试明细（17 个）

### ToolCallingLoopTest（5）
- 完整链路：思考→调 regex_scan→观察→结论（断言工具调用与迭代数）
- 非法 JSON 输出优雅降级为最终答案
- 未知工具记录观察并继续（不炸循环）
- 达到最大迭代返回兜底结论
- file_read 拒绝白名单外路径穿越（安全）

### PlanningTest（5）
- LLM 合法 JSON 拆解为 4 节点 DAG
- LLM 非法输出降级单任务直通
- 计划校验：循环依赖 / 不存在依赖被拒绝
- 菱形依赖拓扑顺序断言（t1→{t2,t3}→t4）
- 上游失败下游跳过传播

### CoordinatorPlanningTest（2）
- 启用规划：走 DAG 路径（各 Agent 恰执行一次 + 轨迹含 plan.created）
- 未启用：行为与旧版一致（可选增强回归保护）

### AgentCapabilitiesTest（5）
- ToolEquippedAgent：委托结果与工具发现合并、委托仅执行一次
- ExperienceStore：写入 / 关键词检索 Top-N / 团队隔离 / 同 pattern 去重
- ReflectionService：报告反思沉淀 BLOCKER/MAJOR 经验（INFO 不沉淀）
- LlmJudge：precision=0.5 / recall=1.0 精确计算 + judge 识别误报
- ExtensionRegistry：order 排序 + 同名覆盖

## 三、全量回归

| 指标 | 结果 |
|------|------|
| 测试类 | 32 |
| 测试总数 | **134** |
| 失败 / 错误 | **0 / 0** |
| 构建 | BUILD SUCCESS |

回归保护验证点：Planning/Tool Calling 均为可选增强（开关默认关闭），未启用时 Coordinator 与 5 内置 Agent 行为与旧版完全一致（CoordinatorPlanningTest.未启用规划时行为与旧版一致 + 全量旧测试零改动通过）。

## 四、过程中修复的问题

1. **覆盖同名类破坏既有 API**：新 ExperienceStore 覆盖了旧类（DemoRunner 依赖 `getRelevantExperiences`）→ 合并两版：保留 MemoryStore 向量检索入口，叠加文件经验条目层（多构造 + @Autowired 标注）。
2. Kahn 无环校验入度未递减导致误报环 → 修复为标准拓扑消元。
3. 测试 fake 脚本耗尽默认 finish 干扰 max-iteration 用例 → 测试脚本显式补满。
4. 反射服务 Severity 未导入 / size 类型笔误。

## 五、遗留（后续轮次）

- LLM judge 单条判定的并发批量化与成本控制
- ExperienceStore 检索可平滑升级向量召回（接口已隔离）
- ExtensionPoint 五个清单接口（LlmInterceptor/RagEnhancer/...）的逐一实体化
