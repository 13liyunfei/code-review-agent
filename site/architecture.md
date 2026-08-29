# Architecture

The system is a **star-topology multi-agent pipeline**, shown below in its layered static structure and its end-to-end runtime flow (including the console's skills / team-knowledge / custom-agent backend).

## Layered architecture

![Layered architecture](/architecture-layered-en.svg)

Six layers top-down:

1. **Trigger** — Gitea/GitLab webhooks, IDE LSP server
2. **Integration** — SCM clients, webhook verification, team resolution
3. **Coordination** — `CompletableFutureCoordinator`, planning DAG, aggregation/arbitration
4. **Review agents** — 5 built-in + business custom agents in parallel, tool-equipped
5. **Capability** — rule engine, RAG, memory, auto-fix, workflow, evaluation, extension SPI
6. **Infrastructure** — PostgreSQL+pgvector, Redis, model gateway (TokenHub), trajectory store

Cross-cutting: multi-tenancy, tracing, 4-level degradation, injection guards, i18n.

## End-to-end flow

![End-to-end flow](/architecture-flow-console-en.svg)

```
PR opened → webhook → authenticate → resolve team
  → Coordinator (parallel: 5 agents + custom + optional planning DAG)
  → each agent: AST + patterns + RAG context + optional tool loop
  → aggregate / dedupe / arbitrate / rank
  → auto-fix suggestions + workflow state
  → write back report + inline comments
  → reflection → experience store → LLM evaluation
```

## Design rules

- **Optional enhancement, zero breakage** — every agentic capability is a switch, off by default; unconfigured behaviour equals previous behaviour
- **Degrade, never break** — failures fall back a rung instead of failing the review
- **Trace everything** — every step lands in the trajectory log for replay
