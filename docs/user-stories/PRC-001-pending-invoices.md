# PRC-001 — See pending invoice submissions

**Persona:** Client procurement user
**Implementation:** IMPLEMENTED — F05 Procurement control tower
**Testing:** VERIFIED — finance browser/system suites

## Description and UI flow

Open **Procurement** to see submitted invoices awaiting review, readiness
blockers, queries and exception status.

## Acceptance criteria

- Queue is procurement-organization scoped and cursor-paginated.
- Only submitted/reviewable invoices appear as pending.
- Commercially restricted fields remain excluded.

## Test cases

- Submitted invoice appears once in pending queue.
- Draft/foreign-client invoices do not appear.
