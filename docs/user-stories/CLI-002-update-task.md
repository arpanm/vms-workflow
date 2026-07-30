# CLI-002 — Update task definition and acceptance criteria

**Persona:** Client product owner
**Implementation:** IMPLEMENTED — optimistic `PATCH /work-items/{id}`
**Testing:** AUTOMATED — integration stale-write test; [execution](TEST_EXECUTION.md)

## Description and UI flow

Edit title, description, workflow, acceptance criteria, priority or planned
month before/during governed planning.

## Acceptance criteria

- Only the creator or scoped manager can edit definition fields.
- `expectedVersion` prevents lost updates and increments exactly once.
- Month/project scope remains database-enforced and changes are audited.

## Test cases

- Current-version update succeeds and returns the new version.
- Stale version and non-manager/non-creator updates fail.
