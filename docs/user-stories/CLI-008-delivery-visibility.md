# CLI-008 — See end-of-month delivery status

**Persona:** Client user
**Implementation:** IMPLEMENTED — work-item status/summary plus F04 certification
**Testing:** AUTOMATED — workspace browser and existing certification tests; [execution](TEST_EXECUTION.md)

## Description and UI flow

Open current/past tasks to see delivery status, summary, estimate/actual totals,
assignees and L1/L2 decisions; use Certification for immutable month evidence.

## Acceptance criteria

- Current task status and immutable month certification are visually distinct.
- Delivery status is scope-authorized and includes last update evidence.
- Past delivered tasks remain readable.

## Test cases

- Render delivered, partial, blocked and not-delivered states.
- Verify past item and certification navigation remain accessible.
