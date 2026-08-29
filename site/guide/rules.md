# Rule engine

Rules are the detection knowledge of the system, and they live in **YAML — not Java**. Adding a rule is a config change, not a code change.

## What a rule looks like

```yaml
rules:
  - id: SEC-001
    title: "SQL injection via string concatenation"
    severity: BLOCKER
    patterns:
      - "executeQuery\\(\\s*\".*\\\"\\s*\\+"
      - "createStatement\\(\\)"
    suggestion: "Use parameterized queries (PreparedStatement) instead of string concatenation."
```

## Rule anatomy

| Field | Meaning |
|-------|---------|
| `id` | Stable identifier, referenced in reports and feedback |
| `title` / `severity` | Display and classification (INFO / MAJOR / BLOCKER) |
| `patterns` | Regex or AST patterns the analyzer checks |
| `suggestion` | The fix text attached to line-level comments |

## Where rules live

Rules are resolved per team:

- A **global baseline** ships with the engine
- Each team overlays its own rules on top
- Missing team config falls back to the baseline

This is the same multi-tenant pattern used for custom agents and knowledge bases.

## Two analysis paths

1. **AST analysis** — structural checks (resource leaks, layering violations) via the `AstAnalyzer`
2. **Pattern scanning** — regex-style checks (hardcoded secrets, dangerous APIs) via pattern skills

Both produce the same `Finding` shape, so downstream aggregation treats them identically.

## Feedback loop

Rejected findings (false positives) flow back through the workflow engine — repeated rejections on the same rule can demote it, so the rule set learns from your team's judgment.
