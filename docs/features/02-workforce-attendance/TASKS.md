# F02 — Workforce, Leave and Attendance Tasks

**Phase:** 2
**Requirements:** RQ-002–RQ-010; PRD 04–06, 13–16, 18–20
**Entry gate:** F01 Spring Security/JWT authorization, Flyway migration, and cross-tenant integration tests pass against Testcontainers PostgreSQL and are evidenced in approved staging.

Status below is for the current bounded implementation. An unchecked item may
contain partial work; its note identifies what remains.

- [ ] Add effective-dated employee master, aliases, user links and engagement/project/deliverable allocations; prohibit payroll/rate fields. **Partial:** employee versions, user links and engagement/project allocations exist with no payroll/rate fields; aliases and deliverable allocations do not.
- [x] Add versioned working/holiday calendars, weekdays, employee assignments and date overrides.
- [ ] Add leave policy/types, immutable balance ledger, idempotent accrual/grant/adjustment and excess-to-LWP handling. **Partial:** types, immutable ledger, idempotent consumption, per-date allocation and excess-to-LWP exist; governed accrual/grant/adjustment and policy administration do not.
- [x] Add immutable attendance events, non-overlapping sessions, day calculation versions and one source authority per employee-day.
- [x] Implement check-in/out, leave and regularization submission services with idempotency, permission checks and audit.
- [x] Implement missing-punch exceptions without synthetic checkout.
- [ ] Add monthly roster/attendance validation, immutable snapshots, close/reopen lineage and correction rules. **Partial:** allocated employee-days are materialized and immutable close/reopen lineage is enforced; broader roster policy and approved correction workflow remain.
- [ ] Implement greytHR capability certification, mapping, staging, reconciliation and source-mode cutover behind its flag. **Partial:** same-tenant certified assignment/effective-use gates exist; provider workflow and integration do not.
- [ ] Add employee/manager/admin normal, empty, loading, error, conflict, stale and unauthorized UI states. **Partial:** read and employee self-service states exist; manager/admin mutation workflows and explicit stale-data workflow do not.
- [ ] Add CSV import validation using supplied templates and error codes. **Not implemented.**
- [ ] Complete all `T-WF`, `T-ATT` and `T-GHR` automation, Spring authorization/PostgreSQL scope review, fixes, API/UI docs and runbook. **Partial:** bounded implementation/review/docs automation is present; deferred cases and staging/full-stack/provider gates remain.

**Exit gate:** Attendance truth-table, missing checkout, partial leave, override, idempotency, source-conflict and post-close correction tests pass.

The full exit gate is not met. See [TEST_CASES.md](TEST_CASES.md),
[FIXES.md](FIXES.md), [API_DOCUMENTATION.md](API_DOCUMENTATION.md),
[UI_DOCUMENTATION.md](UI_DOCUMENTATION.md) and
[CHANGELOG.md](CHANGELOG.md).
