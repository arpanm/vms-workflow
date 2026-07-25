# F02 — Workforce and Attendance Test Cases

- `T-WF-001/002`: create, edit, disable/archive an employee while preserving history and authorization.
- `T-WF-003/004`: effective-dated allocations split correctly and reject overlapping totals above 100%.
- `T-WF-005/006`: calendar weekly offs, holidays and employee date overrides resolve by effective date.
- `T-WF-007–009`: opening balance, idempotent accrual, leave consumption and excess-to-LWP ledger entries reconcile.
- `T-ATT-001–003`: check-in/out, multiple sessions and retry idempotency create correct immutable events/sessions.
- `T-ATT-004/005`: worked-minute thresholds and partial leave classify full, half, absent and LWP outcomes.
- `T-ATT-006`: missing checkout remains open/exception; no worked duration or synthetic punch is invented.
- `T-ATT-007/008`: unpaid breaks, overnight shifts and non-working overrides calculate deterministically.
- `T-ATT-009–011`: regularization approval, admin dual control and single source authority prevent silent merge.
- `T-ATT-012/013`: month snapshot closes deterministically; correction requires reopen and a superseding version.
- `T-GHR-001/002`: capability gate selects authoritative mode only after tenant-specific certification.
- `T-GHR-003–008`: credential isolation, mapping conflicts, duplicate sync, outage/retry, reconciliation and cutover tests.
- `E2E-01/02`: employee month workflow and greytHR reconciliation across UI/API/direct database boundaries.
