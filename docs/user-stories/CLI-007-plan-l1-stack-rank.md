# CLI-007 — L1 approve next-month assignments and stack rank

**Persona:** Client L1 approver
**Implementation:** IMPLEMENTED — `PLAN_L1` version-bound approval
**Testing:** AUTOMATED — approval/version/permission tests; [execution](TEST_EXECUTION.md)

## Description and UI flow

Choose `PLAN_L1`, decision and positive stack rank in the task approval panel.
Approval sets task rank and `APPROVED` status.

## Acceptance criteria

- `workitem.plan.approve` is required and positive rank is mandatory.
- Decision binds to the exact task version; stale approval fails.
- Actor, rank, decision and comment remain append-only.

## Test cases

- Authorized approval sets rank/status and writes evidence.
- Missing rank, stale version and unauthorized actor fail.
