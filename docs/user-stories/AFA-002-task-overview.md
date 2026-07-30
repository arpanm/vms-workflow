# AFA-002 — See task assignment and approval overview

**Persona:** ArrowFoundry administrator
**Implementation:** IMPLEMENTED — `/work-items` cards, filters and approval evidence
**Testing:** AUTOMATED — collaboration browser test; [execution](TEST_EXECUTION.md)

## Description and UI flow

Open a client’s **Client work items** page to see timeline, status, stack rank,
assignees, estimates/actuals and plan/delivery decisions.

## Acceptance criteria

- Summary contains only active-scope tasks.
- Assignment and approval lists preserve distinct actors/roles.
- Backlog/current/next/past filters remain combinable with personal filters.

## Test cases

- Render multi-assignee and L1/L2 evidence.
- Verify filter counts and cross-client isolation.
