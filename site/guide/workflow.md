# Human-in-the-loop workflow

Review output becomes valuable when it can be **acted on and corrected**. A state machine owns the lifecycle of every review item, with humans in the loop at the decision points.

## States and transitions

The workflow engine defines 11 states with 19 transitions across 4 roles (author, reviewer, manager, QA):

```
SUBMITTED ──► IN_REVIEW ──► APPROVED ──► (done)
    │              │
    ▼              ▼
REJECTED ◄── (rework loop) ◄── SUBMITTED again
    │
    ▼
ASSIGNED ──► (fix) ──► SUBMITTED
```

Key transitions:

- A human rejects `SUBMITTED` / `IN_REVIEW` → `REJECTED` → back to `ASSIGNED` (rework)
- A spot-check that fails on an approved item → `ASSIGNED` with a spot-check mark
- Any in-flight item can go `EXCEPTION` → `ASSIGNED` for manual recovery

The transition matrix is exhaustively tested — 440 combinations across 11 test classes.

## False-positive feedback loop

When a reviewer dismisses a finding as a false positive, that signal feeds back to the rules:

- The finding's rule id is recorded with the dismissal
- Repeated dismissals can demote the rule's severity
- The knowledge base stores the dismissal as a negative example

Over time the rule set converges on what your team actually cares about.

## Spot-check (抽检)

Quality assurance can pull an approved review for spot-checking. A failed spot-check flips the item back to `ASSIGNED` with a `SPOT_FAIL` mark, keeping the audit trail intact.

## Traceability

Every transition is recorded in the trajectory log with a traceId, so "who rejected what and why" is replayable end-to-end.
