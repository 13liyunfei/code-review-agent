# Auto-fix

Findings that carry a concrete fix ship with **line-level suggestions**, and the SCM renders them as inline proposals.

## How it works

The engine appends a `suggestion` to each fixable finding. On write-back it publishes them as review comments positioned at the exact lines, so the developer sees the problem and the fix in one place.

```
if (rs.getInt("stock") < qty) { ... }
^^^
SEC-001: SQL injection via string concatenation
Fix: use a parameterized PreparedStatement query
```

## What is fixable

Not everything is — only findings whose rules define a deterministic `suggestion` are published as fixable. Vague or model-dependent advice stays in the summary report instead of pretending to be a mechanical fix.

## Guardrails

- Fixes are **suggestions, not edits** — nothing is committed automatically
- The reviewer applies the suggestion in their editor/SCM with one click
- Post-fix, the re-check flow can verify the change and clear the finding

## Post-fix re-check

When a developer applies a fix, the incremental verification path re-runs the relevant checks and confirms whether the finding is resolved — closing the loop between "suggested" and "done".
