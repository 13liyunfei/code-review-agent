# Introduction

code-review-agent is a **multi-agent collaborative code review engine**. A webhook triggers it on every PR or MR; five specialized agents review the diff in parallel; the findings are aggregated, deduplicated and arbitrated into one report; and the report — with line-level auto-fix suggestions — is written straight back to the SCM.

## What it does

- **Parallel review** — Logic, Security, Performance, Style and Architecture agents, each with its own specialty prompt and rules
- **Rules you can ship without code** — detection rules live in YAML, configured per team
- **Auto-fix suggestions** — line-level suggestions with one-click Apply in Gitea
- **Human workflow** — a state machine from submit to approve/reject/rework, with false-positive feedback
- **Team grounding** — a RAG knowledge base of specs, runbooks and review history

## Tech stack

Java 17, Spring Boot 3.3, LangChain4j with a multi-model gateway (TokenHub), PostgreSQL + pgvector for memory, Redis for queues, Gitea/GitLab as SCM, and a Vue 3 management console.

## Reference architecture

The engine is a **star-topology pipeline**: webhook → `CompletableFutureCoordinator` → five agents in parallel → aggregation/arbitration → report write-back. Two diagrams capture the static layers and the end-to-end flow, including the console's skills market and team knowledge backend.

![Layered architecture](/architecture-layered-en.svg)

The capability layer runs on the standalone [`agent-kit`](https://github.com/13liyunfei/agent-kit) library — tool calling loops, task decomposition DAG, reflection, LLM evaluation and extension SPI. See [Built on agent-kit](./agentkit) for exactly which capabilities are adopted here, and which are deliberately not.

Continue to [Quick start](./quickstart).
