# Built on agent-kit

This project is the **first production consumer of [`agent-kit`](https://github.com/13liyunfei/agent-kit)** — the reusable multi-agent capability library published as `io.github.13liyunfei:agent-kit`.

The relationship is worth stating precisely, because it is easy to overclaim: agent-kit was **extracted from** this repository, and this repository then **came to depend back on it**. That is why the library's package root is `com.codereview.kit`.

## The architectural anchors

"Built on a foundation" is only meaningful if it binds at the interface level, not merely at "we call a few utility classes". Here it does:

| Anchor | Location |
|--------|----------|
| `LlmClient extends com.codereview.kit.ChatModel` | `core/llm/LlmClient.java:10` |
| `Finding implements com.codereview.kit.eval.FindingLike` | `core/model/Finding.java:34` |

The first one matters most: **the engine's model boundary *is* agent-kit's interface**. Every agent, the coordinator and the tool loop receive a `ChatModel`. Swapping the implementation — OpenAI, a self-hosted model, a corporate gateway — requires no change in any of the nine modules above it.

## What is adopted (8 of 17 packages)

| Capability | Where it lands | How it is used |
|------------|----------------|----------------|
| **toolcalling** | `core/toolcalling/ToolEquippedAgent.java`, `ReviewAgentConfig:397-402` | A decorator wraps any `ReviewAgent` and runs a `ToolCallingLoop` first to gather intelligence, merging the loop's findings with the delegate's. Registers `CurrentTimeTool`, `RegexScanTool` and `FileReadTool` (allow-listed root, path traversal rejected). If the loop fails it degrades to a plain delegate call |
| **planning** | `core/planning/TaskPlanningSupport.java`, `ReviewAgentConfig:462-463` | Optional: decomposes the review goal into a task DAG, routes subtasks by assignee, executes topologically. Unparseable output degrades to a single-task pass-through |
| **eval** | `GiteaConfig.java:88`, `GiteaReviewService.java` | After each review, `LlmJudge` computes precision / recall / F1 against ground truth and runs an llm-as-judge pass to flag false positives |
| **extension** | assembly layer | Every built-in behaviour is replaceable; same-name registration overrides, woven in `order()` sequence |
| **security** | `core/security/KeywordInjectionDetector.java` | Two layers: agent-kit's general pattern library plus domain-specific regex (see below) |
| **struct** | `core/agent/AbstractReviewAgent.java` (`llmFindings`) | The second structured-output tier, used when AiServices is unavailable or returns nothing. The schema is **derived from the `ReviewResultDto` type** (including the nested `List<ReviewFindingDto>`) — this repository maintains no schema definition of its own. On failure it reuses the raw response for text parsing instead of paying for another model call |
| **obs** | `core/llm/LoggingChatModelListener.java`, `config/ReviewAgentConfig.java` (`llmTracer` bean) | The LangChain4j listener at the model boundary **translates every call into a `GenAiSpan`**, carrying the business `traceId` and the token counts the model actually reports. Recording, aggregation and cost accounting are all the foundation's job — no bespoke metrics stack here |
| **model** | `core/llm/LlmClient.java` | See architectural anchors |

## What is deliberately *not* adopted (9 of 17)

Adopting a library does not mean routing every concern through it. Where this repository already has a stronger or better-fitting implementation, swapping in the generic one would be a **downgrade**.

| Not adopted | This project uses instead | Why |
|-------------|---------------------------|-----|
| `rag` / `memory` | `core/rag/`, `core/memory/` on pgvector | Needs pgvector persistence, a `__global__` baseline overlay, hybrid retrieval and reranking — the library ships in-memory implementations only |
| `hitl` | 11-state × 19-transition workflow engine | Human approval is one small part of a larger loop that includes rework, spot-checks, exception recovery and false-positive feedback |
| `router` | `ModelGateway` → TokenHub | Multi-model gateway with quota limits and a 4-level degradation chain (agent → orchestration → rules → human) |
| `checkpoint` | `core/resume/ResumeState` | Resume semantics here are "review progress", not a generic snapshot |
| `graph` / `agent` | Star-topology `CompletableFutureCoordinator` | A fixed five-agent fan-out with aggregation and arbitration, not general graph orchestration |
| `session` | LangChain4j `ChatMemory` | Review is stateless batch work; memory is keyed `agent-team-PR` |
| `mcp` / `stream` / `model.native` | — | No current use case |

## Four adaptation lessons

### The library's LOW risk level would cause mass false positives here

agent-kit's `PromptInjectionDetector` grades `override`, `act as` and similar as `Risk.LOW`. **Java code contains `@Override` everywhere.** Measured on an ordinary Java diff:

```
input:  +    @Override
        +    public String toString() { return "x"; }

library verdict: risk=LOW  matched=[override]
```

Treating `flagged()` (LOW *or* HIGH) as a hit would block nearly every Java PR as an injection attack. So this project escalates **only `Risk.HIGH`** to blocking; LOW is suspicious but never blocking. That decision is locked down by the `normalJavaOverrideAnnotationIsNotFlagged()` regression test.

> Before adopting a library's default severity grading, measure its false-positive rate on your own corpus.

### Literal matching misses variants — keep a domain regex layer

The library matches literal substrings such as `忽略以上所有指令`. A real attack written as **`忽略以上指令`** (dropping "所有") slips straight through. So a thin domain-regex layer sits on top:

```java
// Layer 1: agent-kit baseline — HIGH escalates to blocking
if (kitDetector.detect(input).risk() == Risk.HIGH) return true;
// Layer 2: domain regex, catching variants the literals miss
return DOMAIN_PATTERNS.stream().anyMatch(p -> p.matcher(input).find());
```

### Structured output: strengthen the foundation instead of justifying a workaround

agent-kit shipped `StructuredChatModel`, but the original version had four shortcomings against this project's needs — so the first instinct was "keep AiServices, document why". That instinct was wrong. **The right move was to close the gaps in the foundation**, which is what agent-kit 0.1.1 does:

| Shortcoming (0.1.0) | Added in 0.1.1 |
|---|---|
| Schema had to be hand-written as a `Map` | `JsonSchemas.fromType(Class)` derives it by Jackson introspection — nested beans, `List<T>`, enums, with a depth guard |
| Threw on parse failure | `StructuredResult<T>` carries `value` / `rawResponse` / `attempts` / `error` and never throws |
| Retry just re-sent the same prompt | Retry feeds back the previous bad output *and* the failure reason |
| No memory integration | `chatWithSession(ChatSession, ...)` injects history and writes back only on success |

`llmFindings()` is now a three-tier ladder, and each tier downgrades without an extra model call:

```
1. LangChain4j AiServices      — schema binding + ChatMemory (first choice when available)
2. agent-kit StructuredChatModel — type-derived schema, no framework dependency
3. LlmFindingParser            — text parsing, fed with the raw response already in hand
```

> When a downstream implementation is stronger than the foundation, ask first: **is this a business moat, or a capability gap in the foundation?** The former stays in the business layer (`rag`, `hitl`, `router` above). The latter should be **pushed back down into the foundation** — as `struct` was here.

### LLM tracing: the foundation should accept the business traceId, not reinvent it

This one flipped from "not adopted" to "adopted" too. The original `obs` package had four gaps: no `traceId` on `GenAiSpan` (so parallel agent calls could not be tied to one review), **no span recorded on failure** (hiding exactly the calls you most need to see), `stream()` bypassing the tracer entirely, and an `AggregateTracer` with a single total-call counter.

agent-kit 0.1.1 closes all four — and notably adds `TraceIdSupplier` rather than its own trace context. **Generating and propagating traceIds is the business's concern** (MDC, thread-pool reuse, parent/child restore semantics); the foundation just records the id you already have.

The result is a clean split of responsibilities rather than an either/or:

```
business:      TraceContext (MDC traceId)      ReviewTrajectoryRecorder (JSONL replay)
                            \                  /
foundation:                 GenAiSpan ──► AggregateTracer / LoggingGenAiTracer
```

> When judging adoption, don't compare the foundation's **whole** against the business's **whole**. Break it down to the level of individual concerns: generic parts go to the foundation, differentiators stay in the business, and the two are stitched together by a **minimal contract** (here: a traceId) instead of overriding each other.

## Verify it yourself

```bash
grep -n -A3 "agent-kit" pom.xml                        # dependency declared
grep -rn "com.codereview.kit" src/ --include="*.java"  # usage sites
ls src/main/java/com/codereview/kit                    # must not exist (no vendoring)
./mvnw -o test -Dtest='KeywordInjectionDetectorTest'   # security adaptation
./mvnw -o test -Dtest='AgentKitStructuredOutputTest'   # struct adaptation
./mvnw -o test -Dtest='AgentKitLlmTracingTest'         # tracing adaptation
```
