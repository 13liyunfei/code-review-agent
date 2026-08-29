# 架构

系统是**星型拓扑的多 Agent 流水线**，以下从静态分层结构与端到端运行时流程（含控制台的技能市场 / 团队知识 / 自定义 Agent 后管）两个视角呈现。

## 分层架构

![分层架构](/architecture-layered.svg)

自顶向下六层：

1. **触发层** —— Gitea/GitLab webhook、IDE LSP 服务
2. **集成层** —— SCM 客户端、webhook 鉴权、团队解析
3. **协调层** —— `CompletableFutureCoordinator`、规划 DAG、聚合/仲裁
4. **审查 Agent 层** —— 5 内置 + 业务方自定义并行，支持工具增强
5. **能力支撑层** —— 规则引擎、RAG、记忆、自动修复、工作流、评估、扩展机制
6. **基础设施层** —— PostgreSQL+pgvector、Redis、模型网关（TokenHub）、轨迹存储

横切关注点：多租户、全链路追踪、四级降级、注入防护、i18n。

## 端到端流程

![端到端流程](/architecture-flow-console.svg)

```
PR 打开 → webhook → 鉴权 → 解析团队
  → Coordinator（并行：5 Agent + 自定义 + 可选规划 DAG）
  → 每个 Agent：AST + 模式 + RAG 上下文 + 可选工具循环
  → 聚合 / 去重 / 仲裁 / 排序
  → 自动修复建议 + 工作流状态
  → 报告与行内评论回写
  → 反思 → 经验库 → LLM 评估
```

## 设计原则

- **可选增强、零破坏** —— 每个 Agent 能力都是一个开关，默认关闭；不配置时行为与旧版一致
- **降级而不中断** —— 失败降一级，而不是让审查失败
- **全量留痕** —— 每一步都落轨迹日志，可回放
