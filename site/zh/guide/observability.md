# 可观测与安全

## 全链路追踪

每次审查携带一个 `traceId`（12 位 hex，写入 MDC），跨 Agent 线程与每个子系统传播：

- webhook 入口 → 协调 → 每个 Agent → 规则引擎 → 模型调用 → 回写
- 轨迹记录器每次审查写一份 JSONL：`plan.created`、`agent.started`、`plan.task.completed`、`review.finished`……

轨迹**确定性可回放**——同样的输入重现同样的步骤，这正是回归评估可行的基础。

## 质量趋势

按规则的反馈数、驳回率、返工数聚合成质量趋势数据，通过控制台呈现——你能看到规则集在变好还是变吵。

## 模型网关与观测

所有 LLM 调用走 `ModelGateway` → `TokenHub`（多模型、token 记账）。`LoggingChatModelListener` 统一记录带 traceId 的请求/响应（INFO 截断 / DEBUG 全量）。

## 安全

- **注入防护** —— 关键词、语义、异常三层检测；XML/Canary 硬化；自定义 Agent 提示词存储前预检
- **多租户隔离** —— 团队数据（规则、知识、经验、轨迹）按 `X-Team-Id` 隔离
- **Webhook 鉴权** —— Gitea webhook secret 校验
- **出口管控** —— RAG 检索受允许主机闸门约束

## 四级降级阶梯

1. 完整能力（LLM + RAG + 全部 Agent）
2. 仅 LLM（无 RAG）
3. 仅规则（无 LLM）
4. 仅静态分析

每一级都能独立工作——模型网关挂了降一级，审查不会挂。
