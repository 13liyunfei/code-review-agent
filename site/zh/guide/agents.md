# 多 Agent 协同

## 五个内置 Agent

| Agent | 关注点 |
|-------|--------|
| `LogicAgent` | 正确性、空指针、资源泄漏、控制流 |
| `SecurityAgent` | 注入、密钥、越权、危险 API |
| `PerformanceAgent` | 热点路径、内存分配、N+1 查询、阻塞 IO |
| `StyleAgent` | 规范、死代码、System.out、TODO 卫生 |
| `ArchitectureAgent` | 分层违例、耦合、模块边界 |

每个 Agent 获得自己的专业提示词 + 团队 YAML 规则，产出结构化 `Finding`（文件、行区间、严重级别、规则 id、修复建议）。

## 协调器

`CompletableFutureCoordinator` 并行执行（traceId 跨线程传播），随后：

1. **聚合**全部发现
2. **去重**跨 Agent 的重叠
3. **仲裁**冲突——优先级模型决定严重级别与文案
4. **排序**按严重级别与置信度

某 Agent 失败或超时，其槽位降级为空结果——审查照常完成。

## 业务方自定义 Agent

团队可以不改引擎代码定义自己的审查 Agent。自定义 Agent 是声明式配置（系统提示词、规则、模型），按团队存储、审查时注入。控制台提供管理接口，每个自定义提示词存储前都会过注入检测预检。

## 可选规划

开启 `review.planning.enabled=true` 后，协调器先让模型把目标拆成任务 DAG，按负责人路由子任务并拓扑执行。规划失败自动降级回固定并行路径——规划可以让结果更好，但绝不能让它更糟。
