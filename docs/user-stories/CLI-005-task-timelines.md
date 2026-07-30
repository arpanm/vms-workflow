# CLI-005 — Browse backlog, future and delivered tasks

**Persona:** Client user
**Implementation:** IMPLEMENTED — `ALL/BACKLOG/CURRENT/NEXT/PAST` filters
**Testing:** AUTOMATED — date-bucket integration/browser tests; [execution](TEST_EXECUTION.md)

## Description and UI flow

Use the **Timeline** selector on **Client work items** to view the entire
client scope, unplanned backlog, current month, future months or past months.

## Acceptance criteria

- Buckets are server-derived from engagement month dates, not browser time.
- Only authorized engagement tasks are returned.
- Results are ordered by stack rank then latest update.

## Test cases

- Seed one item per bucket and verify exact membership/order.
- Cross-engagement task never appears.
