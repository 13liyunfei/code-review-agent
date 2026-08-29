# Agent 通用能力

审查流水线之外，引擎还具备企业级 Agent 能力——让系统成为真正的 Agent 平台的那类机制。它们位于独立的 [`agent-kit`](https://gitee.com/liyunfei2030/agent-kit) 组件库中，以可选增强方式接入，默认全部关闭。

## 工具调用循环

```yaml
review.tools.agent-loop.enabled: true
```

为每个内置 Agent 包一层 `ToolCallingLoop`——审查前 Agent 可以决定调用工具（时间、正则扫描、文件读取），把观察结果并入发现。

## 任务拆解 DAG

```yaml
review.planning.enabled: true
```

协调器先让模型把审查目标拆成任务 DAG，按负责人路由子任务并拓扑执行。任何失败都会降级回固定并行路径。

## 反思与经验库

```yaml
review.reflection.enabled: true
```

每份报告产出后，`ReflectionService` 把审查沉淀为经验条目——反复出现的模式、验证有效的建议——按团队存入去重经验库。

## LLM 评估

```yaml
review.eval.enabled: true
```

每次审查后，`LlmJudge` 基于 ground-truth 计算 precision / recall / F1，并对发现跑一遍 llm-as-judge 标记误报。

## 扩展点机制

上述能力全部经由同一套扩展注册中心装配——`LlmInterceptor`、`RagEnhancer`、`AgentProvider`、`MemoryStrategy`、`StageHook`——引擎行为无需 fork 即可调整。
