# F02 — Workforce, Leave and Attendance Tasks

**Phase:** 2
**Requirements:** RQ-002–RQ-010; PRD 04–06, 13–16, 18–20
**Entry gate:** F01 Spring Security/JWT authorization, Flyway migration, and cross-tenant integration tests pass against Testcontainers PostgreSQL and are evidenced in approved staging.

- [ ] Add effective-dated employee master, aliases, user links and engagement/project/deliverable allocations; prohibit payroll/rate fields.
- [ ] Add versioned working/holiday calendars, weekdays, employee assignments and date overrides.
- [ ] Add leave policy/types, immutable balance ledger, idempotent accrual/grant/adjustment and excess-to-LWP handling.
- [ ] Add immutable attendance events, non-overlapping sessions, day calculation versions and one source authority per employee-day.
- [ ] Implement check-in/out, leave and regularization services with idempotency, permission checks and audit.
- [ ] Implement missing-punch exceptions without synthetic checkout.
- [ ] Add monthly roster/attendance validation, immutable snapshots, close/reopen lineage and correction rules.
- [ ] Implement greytHR capability certification, mapping, staging, reconciliation and source-mode cutover behind its flag.
- [ ] Add employee/manager/admin normal, empty, loading, error, conflict, stale and unauthorized UI states.
- [ ] Add CSV import validation using supplied templates and error codes.
- [ ] Complete all `T-WF`, `T-ATT` and `T-GHR` automation, Spring authorization/PostgreSQL scope review, fixes, API/UI docs and runbook.

**Exit gate:** Attendance truth-table, missing checkout, partial leave, override, idempotency, source-conflict and post-close correction tests pass.
