# Multi-agent collaboration

## The five built-in agents

| Agent | Focus |
|-------|-------|
| `LogicAgent` | Correctness, null handling, resource leaks, control flow |
| `SecurityAgent` | Injection, secrets, authz gaps, dangerous APIs |
| `PerformanceAgent` | Hot paths, allocations, query N+1, blocking IO |
| `StyleAgent` | Conventions, dead code, System.out, TODO hygiene |
| `ArchitectureAgent` | Layering violations, coupling, module boundaries |

Each agent gets its own specialty prompt plus the team's YAML rules, and returns structured `Finding` objects with file, line range, severity, rule id and a suggestion.

## Coordinator

`CompletableFutureCoordinator` runs them in parallel (traceId propagates across threads), then:

1. **Aggregates** all findings
2. **Deduplicates** overlaps across agents
3. **Arbitrates** conflicts — a priority model decides which severity and message wins
4. **Ranks** by severity and confidence

If an agent fails or times out, its slot degrades to an empty result — the review still completes.

## Business-defined custom agents

Teams can define their own review agents without touching the engine. A custom agent is a declarative config — system prompt, rules, model — stored per team and injected at review time. The console provides management endpoints, and every custom prompt passes an injection-detection pre-check.

## Optional planning

With `review.planning.enabled=true`, the coordinator first asks the model to decompose the goal into a task DAG, routes subtasks to matching agents, and executes topologically. If planning fails, it falls back to the fixed parallel path — planning can improve the result but never break it.
