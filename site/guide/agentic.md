# Agentic capabilities

Beyond the review pipeline itself, the engine ships enterprise agent capabilities — the kind of machinery that makes a system feel like a real agent platform. These live in the standalone [`agent-kit`](https://github.com/13liyunfei/agent-kit) library and are wired in as optional enhancements, all off by default.

## Tool calling loop

```yaml
review.tools.agent-loop.enabled: true
```

Wraps each built-in agent with a `ToolCallingLoop` — before reviewing, the agent can decide to call tools (time, regex scans, file reads) and fold the observations into its findings.

## Task decomposition DAG

```yaml
review.planning.enabled: true
```

The coordinator first decomposes the review goal into a task DAG, routes subtasks to the matching agents and executes topologically. Falls back to the fixed parallel path on any failure.

## Reflection & experience store

```yaml
review.reflection.enabled: true
```

After each report, `ReflectionService` distills the review into experience entries — patterns that recur, advice that worked — stored per team in a deduplicated experience library.

## LLM evaluation

```yaml
review.eval.enabled: true
```

After each review, `LlmJudge` computes precision / recall / F1 against ground truth and runs an llm-as-judge pass over the findings to flag false positives.

## Extension points

All of the above are assembled through the same extension registry — `LlmInterceptor`, `RagEnhancer`, `AgentProvider`, `MemoryStrategy`, `StageHook` — so the engine's behaviour is adjustable without forking it.
