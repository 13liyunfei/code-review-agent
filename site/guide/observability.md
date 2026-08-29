# Observability & security

## Full-chain tracing

Every review carries a `traceId` (12-hex, in MDC) that propagates across agent threads and every subsystem:

- webhook entry → coordination → each agent → rule engine → model calls → write-back
- The trajectory recorder writes a JSONL per review: `plan.created`, `agent.started`, `plan.task.completed`, `review.finished`…

Trajectories are **deterministically replayable** — the same input reproduces the same steps, which is what makes regression evaluation possible.

## Quality trends

Feedback counts per rule, rejection rates and rework counts are aggregated into quality trend data, exposed through the console — you can see whether the rule set is getting better or noisier.

## Model gateway & observability

All LLM calls go through `ModelGateway` → `TokenHub` (multi-model, token accounting). `LoggingChatModelListener` records every request/response with the traceId at INFO (truncated) / DEBUG (full).

## Security

- **Injection protection** — keyword, semantic and anomaly detection over prompts; XML/Canary hardening; custom agent prompts are pre-checked before storage
- **Multi-tenant isolation** — team data (rules, knowledge, experiences, trajectories) is isolated by `X-Team-Id`
- **Webhook authenticity** — Gitea webhook secret verification
- **Egress control** — RAG retrieval is subject to allowed-host gating

## The 4-level degradation ladder

1. Full capability (LLM + RAG + all agents)
2. LLM-only (no RAG)
3. Rule-only (no LLM)
4. Static analysis only

Each rung works independently — a failed model gateway drops a rung, never the review.
