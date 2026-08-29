---
layout: home

hero:
  name: code-review-agent
  text: Multi-agent code review engine
  tagline: Five specialized agents review every PR in parallel — YAML rules, auto-fix suggestions, human-in-the-loop workflow and a RAG knowledge base
  image:
    src: /architecture-layered-en.svg
    alt: code-review-agent
  actions:
    - theme: brand
      text: Get started
      link: /guide/quickstart
    - theme: alt
      text: View on GitHub
      link: https://github.com/13liyunfei/code-review-agent

features:
  - icon: 🤖
    title: Five specialized agents
    details: Logic, Security, Performance, Style and Architecture agents review in parallel, then aggregate, deduplicate and arbitrate conflicts — plus business-defined custom agents per team.
  - icon: 📜
    title: Declarative YAML rules
    details: Rules live in YAML, not code. Pattern skills, severity levels and suggestions are configured per team without touching the engine.
  - icon: 🔧
    title: Auto-fix suggestions
    details: Detectable problems ship with line-level suggestions. Gitea shows them inline with an Apply button for one-click fixes.
  - icon: 🔁
    title: Human-in-the-loop workflow
    details: A full state machine — submit, review, approve, reject, rework, spot-check. False-positive feedback loops back into the rules.
  - icon: 📚
    title: RAG knowledge base
    details: Team specs, runbooks and historical reviews are chunked, embedded and retrieved to ground every review in your team's context.
  - icon: 🧩
    title: Agentic capabilities
    details: Tool calling loops, task decomposition DAGs, reflection and LLM evaluation — powered by the standalone agent-kit library.
---
