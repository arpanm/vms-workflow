# F02 — Workforce and Attendance Test Cases

Status reflects executable automation in the current worktree, not the wider
planned feature.

- [ ] `T-WF-001/002`: create, edit, disable/archive an employee while preserving history and authorization. **Partial:** read/effective lifecycle version history is automated; create and disable/archive coverage is incomplete.
- [ ] `T-WF-003/004`: effective-dated allocations split correctly and reject overlapping totals above 100%. **Partial:** normal allocation and overlapping-total rejection are automated; allocation splitting/editing is not.
- [x] `T-WF-005/006`: calendar weekly offs, holidays and employee date overrides resolve by effective date.
- [x] `T-WF-007–009`: opening balance, idempotent ledger effects, leave consumption, immutable per-date allocation and excess-to-LWP entries reconcile for the implemented policy.
- [ ] `T-ATT-001–003`: check-in/out, multiple sessions and retry idempotency create correct immutable events/sessions. **Partial:** one check-in/out session and retry idempotency are automated; multiple sessions are not.
- [ ] `T-ATT-004/005`: worked-minute thresholds and partial leave classify full, half, absent and LWP outcomes. **Partial:** full presence, half-work plus paid leave, missing checkout and LWP paths are automated; the full truth table is not.
- [x] `T-ATT-006`: missing checkout remains open/exception; no worked duration or synthetic punch is invented.
- [ ] `T-ATT-007/008`: unpaid breaks, overnight shifts and non-working overrides calculate deterministically. **Not automated.**
- [ ] `T-ATT-009–011`: regularization approval, admin dual control and single source authority prevent silent merge. **Partial:** attributable self submission and reviewer command denial exist; approval/admin correction does not.
- [x] `T-ATT-012/013`: month snapshot materializes allocated dates, closes deterministically, reopens only the closed leaf and creates immutable superseding versions.
- [x] `T-GHR-001/002`: capability gate accepts only effective same-tenant certification and fails closed after revocation.
- [ ] `T-GHR-003–008`: credential isolation, mapping conflicts, duplicate sync, outage/retry, reconciliation and cutover tests. **Not implemented; no provider integration exists.**
- [ ] Full-stack employee-month and greytHR reconciliation across browser, API and database. **Blocked/not available.**

Additional automated invariants:

- [x] repeated attendance-day GET creates/updates/resolves no persistent row;
- [x] multi-day leave totals are allocated exactly once over eligible dates;
- [x] reviewer reads cannot become employee punch/leave/regularization commands;
- [x] `/employees/me` resolves one active authorized link and missing IDs remain non-disclosing;
- [x] close materializes allocated dates without depending on a prior GET;
- [x] inactive allocations neither enter nor block a month snapshot;
- [x] browser leave validation rejects units above the inclusive date span and
  sends no invalid POST;
- [x] seven F02 Chromium browser-contract journeys, explicitly with intercepted APIs.

See [TEST_AUTOMATION.md](TEST_AUTOMATION.md), [FIXES.md](FIXES.md) and
[UI_DOCUMENTATION.md](UI_DOCUMENTATION.md).
