---
layout: home

hero:
  name: code-review-agent
  text: 多 Agent 协同代码审查引擎
  tagline: 五个专业 Agent 并行审查每个 PR —— YAML 规则、自动修复建议、人机协作工作流与 RAG 团队知识库
  image:
    src: /architecture-layered.svg
    alt: code-review-agent
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/guide/quickstart
    - theme: alt
      text: 在 Gitee 查看
      link: https://gitee.com/liyunfei2030/code-review-agent

features:
  - icon: 🤖
    title: 五个专业 Agent 并行
    details: Logic、Security、Performance、Style、Architecture 五个 Agent 并行审查，再聚合、去重、冲突仲裁——还支持按团队扩展业务方自定义 Agent。
  - icon: 📜
    title: 声明式 YAML 规则
    details: 规则写在 YAML 而不是代码里。模式技能、严重级别、修复建议按团队配置，不改引擎一行代码。
  - icon: 🔧
    title: 自动修复建议
    details: 可检测的问题附带行级修复建议，Gitea 上以行内评论 + Apply 按钮呈现，一键采纳。
  - icon: 🔁
    title: 人机协作工作流
    details: 完整状态机——提交、审查、通过、驳回、返工、抽检。误报反馈闭环回灌规则库。
  - icon: 📚
    title: RAG 团队知识库
    details: 团队规范、操作手册、历史审查记录切块嵌入，让每次审查都基于团队的上下文。
  - icon: 🧩
    title: Agent 通用能力
    details: 工具调用循环、任务拆解 DAG、反思与 LLM 评估——由独立组件库 agent-kit 提供。
---
