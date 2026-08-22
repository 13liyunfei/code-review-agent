# Multi-Agent Code Review System

> [English](README.md) | [中文](README.zh-CN.md)

An intelligent code review system powered by a **multi-agent collaboration** architecture. It listens for Pull Request events from Gitea / GitLab, runs 5 specialized review agents in parallel (Security / Logic / Performance / Style / Architecture) plus AST / call-graph / SCA static analysis, and posts a structured review report (top-level overview + inline suggestions) back to the PR.

The design is heavily inspired by open-source agentic coding harnesses — [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) (event-sourced sessions, fail-closed sandbox) and [openai/codex](https://github.com/openai/codex) (rollout traces, context fragments, permission convergence, tool exposures).

## Tech Stack

| Area | Choice |
|------|--------|
| Runtime | Spring Boot 3.3.4 · **Java 17** |
| LLM | LangChain4j 1.19.0 + TokenHub multi-model gateway (Hunyuan / DeepSeek / GLM / mock) |
| Vector memory | PostgreSQL 17 + pgvector (RAG, per-team isolation) |
| Queue | Redis / in-memory MessageQueue |
| Frontend console | Vue 3 + ElementPlus (`code-review-console`, :8081) |

## Features

- **5 parallel review agents** (Security / Logic / Performance / Style / Architecture) + Advanced static analysis (AST metrics, call-graph impact, SCA dependency CVE)
- **Top-level overview report + inline suggestions** posted back to PR comments
- **Priority arbitration, false-positive feedback loop, incremental re-check** between pushes
- **Multi-tenant isolation** (global baseline + per-team rules / knowledge / memory / history)
- **Full-chain tracing** (`traceId` across webhook → agents → LLM → persistence)
- **Low-code YAML rule platform** — teams ship custom rules without writing Java
- **IDE integration** — LSP over stdio for real-time diagnostics

### Architecture-aligned enhancements (from deepseek-harness / codex)

| # | Capability | Inspiration |
|---|-----------|-------------|
| P0 | Event-sourced review **trajectory** (JSONL per run) | dsh `Session` / codex `RolloutItem` |
| P0 | **Impact-surface slicing** injected into agent prompts | codex `context-fragments` |
| P0 | **Checkpoint resume** — crash-safe re-run of unfinished agents | codex `suspend/recover_turn` |
| P1 | **Review profile** hot-switch (STRICT / ADVISORY / SUGGEST) | codex exposure control |
| P1 | **AutoFix fail-closed** + real sandbox probe | dsh `SandboxUnavailableError` |
| P1 | **Veto policy** — Blockers can't be suppressed/overridden | codex `intersect_permission_profiles` |
| P1 | **Tool gating** (DEFERRED heavy tools denied by default) | codex `ToolExposures` |
| P2 | **Persistent mailbox** for agent delegation | dsh `agent-team TeamMailbox` |
| P2 | **Deterministic replay evaluation** of trajectories | dsh `llm-replay` / codex `rollout` |
| P2 | **External tool SPI** (MCP-style plugin point) | codex `mcp_tool` |

**i18n**: All developer-facing output (review report, auto-fix suggestions, quality report, review titles) is localized via `i18n/messages*.properties` — switch with `review.lang=zh|en` (default `zh`). Logs and comments stay in Chinese (internal debug use).

## Quick Start

### Standard deployment (production / standalone service)

The agent is a **standalone service**: build it, point it at your external PostgreSQL/Redis/Gitea, and run the jar.

```bash
# 1. Build
./mvnw -o package -DskipTests

# 2. Configure (application.yml / env vars): PG+pgvector, Redis, Gitea/GitLab base URL,
#    tokenhub API key, review.lang, review.profile ... see "Key Configuration" below.

# 3. Run the service
java -jar target/code-review-agent-1.0.0.jar
```

- Defaults: webhook `http://<host>:8080/webhook/gitea`; console (separate repo `code-review-console`) on :8081.
- No bundled infrastructure — external dependencies (PG/Redis/Gitea) are provided by your environment/ops.

### Local development (optional, macOS Colima+Homebrew or Docker)

> ⚠️ The `dev/` scripts are for **local debugging only** — they pull up the full stack (PG/Redis/Colima/Gitea/engine). Not used in production.

```bash
# Option A: macOS with Colima + Homebrew (one-click full stack)
cp .env.example .env
./dev/start-all.sh dev        # PG → Redis → Colima → Gitea → engine (:8080)
./dev/status.sh
./dev/stop-all.sh --all       # --all also stops the Colima VM

# Option B: any machine with Docker — infra via compose, engine via maven
cd dev && docker compose -f docker-compose.dev.yml up -d   # PG17+pgvector / Redis / Gitea
cd .. && ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

## Integrations

### Gitea (recommended for local)

1. Start Gitea (see `dev/docker-compose.dev.yml` or `dev/start-all.sh`, container `gitea/gitea` on :3000).
2. Create a token for the bot account (e.g. `reviewer`).
3. In the repo → Settings → Webhooks, add `http://localhost:8080/webhook/gitea`, content type JSON, secret matching `gitea.webhook-secret`, events: **Pull Request**.
4. Open a PR — the engine reviews it and posts the report automatically.

> Gitea 1.27 note: the independent inline-comment API was removed, so inline comments degrade to file-level (no "Apply suggestion" button); the full fix list with `suggestion` blocks is always available in the top-level overview comment.

### GitLab

Create a Personal Access Token, configure `gitlab.*` in `application.yml`, and add a webhook for Merge Request events (`/webhook/gitlab`).

## LLM Setup (TokenHub multi-model)

Configure `tokenhub` in `application.yml`:

```yaml
tokenhub:
  api-key: ${TOKENHUB_API_KEY}
  models:
    - name: hunyuan        # primary
      model: hunyuan-turbo
      base-url: https://api.hunyuan.cloud.tencent.com/v1
    - name: deepseek
      model: deepseek-chat
    - name: glm
      model: glm-4-flash
```

- `ModelGateway` routes, quotas and failover across models; `review.llm.embedding.enabled=true` switches to real semantic vectors (`kinfra-text-embedding-0.6b`, 1024-dim, keep `pgvector.vector-dim` aligned).

## Architecture Overview

```
Gitea/GitLab webhook → GiteaWebhookController (HMAC verify, traceId)
        ↓  async
   ReviewWorkflowEngine / Coordinator (CompletableFutureCoordinator)
        ↓  parallel            ↓
5 Review Agents           AdvancedAnalyzer (AST / call-graph / SCA)
  (Security/Logic/             + ImpactAnalyzer (impact surface)
   Performance/Style/
   Architecture)               ↓
        └──────→ ReportGenerator (dedupe → arbitrate → suppress → tier)
                    ↓
          ReviewReport (i18n markdown) → PR top comment + inline suggestions
```

Nine core modules: `AstAnalyzer` · `CallGraphAnalyzer` · `YamlRuleEngine` · `ModelGateway` · `ScheduledScanService` · `AutoFixEngine` · `ReviewWorkflowEngine` · `ScaScanner` · `IdeReviewServer` (+ `CompletableFutureCoordinator`).

## Multi-Tenant Isolation

- **Global baseline** (`__global__`: built-in skills + spec handbook RAG) + **per-team overlay** (custom rules / knowledge / memory / history / feedback keyed by `teamId`).
- Team resolution: webhook `owner/repo` (exact match > org > default), or `X-Team-Id` header override.
- Config: `review.teams.default` + `review.teams.mapping`.

## Key Configuration

| Key | Default | Description |
|-----|---------|-------------|
| `review.lang` | `zh` | Output language for reports/suggestions (`zh`/`en`) |
| `review.profile` | `ADVISORY` | Review strictness: STRICT / ADVISORY / SUGGEST |
| `review.data-dir` | `./data` | Persistence root (trajectory / resume / mailbox / feedback / history) |
| `review.tools.deferred-enabled` | `false` | Allow DEFERRED heavy tools (fail-closed by default) |
| `review.autofix.mode` | `SUGGEST` | AutoFix mode: SUGGEST / APPLY (APPLY needs a sandbox) |
| `gitea.base-url` | — | Gitea address |
| `gitea.webhook-secret` | — | Webhook HMAC secret (empty = skip verification) |
| `review.teams.*` | — | Team isolation mapping |

## Observability

- `traceId` (12-hex) in every log line (`%X{traceId}`), propagated across threads via `TraceContext.wrap(...)`.
- Per-run **trajectory JSONL**: `data-dir/<teamId>/trajectories/<runId>.jsonl` (events: started → diff-loaded → injected → agent.started/completed → completed), replayable by `ReviewReplay`.
- LLM boundary logging via `LoggingChatModelListener` (all ChatModel calls, both AiServices and text fallback).

## License

Open-sourced under the [MIT license](LICENSE). © 2026 13liyunfei.
