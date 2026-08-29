# 简介

code-review-agent 是**多 Agent 协同代码审查引擎**。Webhook 在每个 PR / MR 上触发审查，五个专业 Agent 并行分析 diff，发现聚合、去重、仲裁成一份报告，连同行级修复建议直接写回代码托管平台。

## 核心能力

- **并行审查** —— Logic、Security、Performance、Style、Architecture 五个 Agent，各有专业提示词与规则
- **免代码发规则** —— 检测规则写在 YAML 里，按团队配置
- **自动修复建议** —— 行级建议，Gitea 上一键 Apply
- **人机工作流** —— 从提交到通过/驳回/返工的完整状态机，带误报反馈闭环
- **团队知识底座** —— 规范、手册、历史审查组成的 RAG 知识库

## 技术栈

Java 17、Spring Boot 3.3、LangChain4j（多模型网关 TokenHub）、PostgreSQL + pgvector 记忆、Redis 队列、Gitea/GitLab 代码托管、Vue 3 管理控制台。

## 架构速览

引擎是**星型拓扑流水线**：webhook → `CompletableFutureCoordinator` → 五个 Agent 并行 → 聚合/仲裁 → 报告回写。两张图分别呈现静态分层与端到端流程（含控制台的技能市场与团队知识后管）。

![分层架构](/architecture-layered.svg)

能力层运行在独立的 [`agent-kit`](https://gitee.com/liyunfei2030/agent-kit) 组件库之上——工具调用循环、任务拆解 DAG、反思、LLM 评估与扩展点机制。

继续阅读 [快速开始](./quickstart)。
