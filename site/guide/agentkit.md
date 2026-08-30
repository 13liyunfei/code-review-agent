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

## What is adopted (6 of 17 packages)

| Capability | Where it lands | How it is used |
|------------|----------------|----------------|
| **toolcalling** | `core/toolcalling/ToolEquippedAgent.java`, `ReviewAgentConfig:397-402` | A decorator wraps any `ReviewAgent` and runs a `ToolCallingLoop` first to gather intelligence, merging the loop's findings with the delegate's. Registers `CurrentTimeTool`, `RegexScanTool` and `FileReadTool` (allow-listed root, path traversal rejected). If the loop fails it degrades to a plain delegate call |
| **planning** | `core/planning/TaskPlanningSupport.java`, `ReviewAgentConfig:462-463` | Optional: decomposes the review goal into a task DAG, routes subtasks by assignee, executes topologically. Unparseable output degrades to a single-task pass-through |
| **eval** | `GiteaConfig.java:88`, `GiteaReviewService.java` | After each review, `LlmJudge` computes precision / recall / F1 against ground truth and runs an llm-as-judge pass to flag false positives |
| **extension** | assembly layer | Every built-in behaviour is replaceable; same-name registration overrides, woven in `order()` sequence |
| **security** | `core/security/KeywordInjectionDetector.java` | Two layers: agent-kit's general pattern library plus domain-specific regex (see below) |
| **model** | `core/llm/LlmClient.java` | See architectural anchors |

## What is deliberately *not* adopted (11 of 17)

Adopting a library does not mean routing every concern through it. Where this repository already has a stronger or better-fitting implementation, swapping in the generic one would be a **downgrade**.

| Not adopted | This project uses instead | Why |
|-------------|---------------------------|-----|
| `struct` | LangChain4j AiServices | Real schema binding **plus** `ChatMemory`, with a text-parsing fallback (`LlmFindingParser`) |
| `rag` / `memory` | `core/rag/`, `core/memory/` on pgvector | Needs pgvector persistence, a `__global__` baseline overlay, hybrid retrieval and reranking — the library ships in-memory implementations only |
| `hitl` | 11-state × 19-transition workflow engine | Human approval is one small part of a larger loop that includes rework, spot-checks, exception recovery and false-positive feedback |
| `obs` | `TraceContext`, `ReviewTrajectoryRecorder`, `LoggingChatModelListener` | Full-chain traceId propagated across agent threads, JSONL trajectories that are **replayable** — a better fit for "why did the review conclude this" than generic spans |
| `router` | `ModelGateway` → TokenHub | Multi-model gateway with quota limits and a 4-level degradation chain (agent → orchestration → rules → human) |
| `checkpoint` | `core/resume/ResumeState` | Resume semantics here are "review progress", not a generic snapshot |
| `graph` / `agent` | Star-topology `CompletableFutureCoordinator` | A fixed five-agent fan-out with aggregation and arbitration, not general graph orchestration |
| `session` | LangChain4j `ChatMemory` | Review is stateless batch work; memory is keyed `agent-team-PR` |
| `mcp` / `stream` / `model.native` | — | No current use case |

## Three adaptation lessons

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

### Structured output: pick the stronger tool, not the in-house one

agent-kit ships `StructuredChatModel`; this project uses LangChain4j AiServices instead. That is deliberate — the criterion is capability, not lineage. A foundation's value is that the component is *available when needed*, not that everything must route through it.

## Verify it yourself

```bash
grep -n -A3 "agent-kit" pom.xml                        # dependency declared
grep -rn "com.codereview.kit" src/ --include="*.java"  # usage sites
ls src/main/java/com/codereview/kit                    # must not exist (no vendoring)
./mvnw -o test -Dtest='KeywordInjectionDetectorTest'   # adaptation regression
```
