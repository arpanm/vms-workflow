# F02 — Workforce, Leave and Attendance Tasks

**Phase:** 2
**Requirements:** RQ-002–RQ-010; PRD 04–06, 13–16, 18–20
**Entry gate:** F01 Spring Security/JWT authorization, Flyway migration, and cross-tenant integration tests pass against Testcontainers PostgreSQL and are evidenced in approved staging.

Status below is for the current bounded implementation. An unchecked item may
contain partial work; its note identifies what remains.

- [x] Add effective-dated employee master, aliases, user links and engagement/project/deliverable allocations; prohibit payroll/rate fields.
- [x] Add versioned working/holiday calendars, weekdays, employee assignments and date overrides.
- [x] Add leave policy/types, immutable balance ledger, idempotent accrual/grant/adjustment and excess-to-LWP handling.
- [x] Add immutable attendance events, non-overlapping sessions, day calculation versions and one source authority per employee-day.
- [x] Implement check-in/out, leave and regularization submission services with idempotency, permission checks and audit.
- [x] Implement missing-punch exceptions without synthetic checkout.
- [x] Add monthly roster/attendance validation, immutable snapshots, close/reopen lineage and correction rules. Effective shift/calendar/source/employee coverage is evaluated per allocated employee-day; finalization freezes allocation, project, shift, timezone and expectation evidence; attendance close consumes that roster snapshot.
- [ ] Implement greytHR capability certification, mapping, staging, reconciliation and source-mode cutover behind its flag. **Partial:** same-tenant certified assignment/effective-use gates exist; provider workflow and integration do not.
- [x] Add employee/manager/admin normal, empty, loading, error, conflict, stale and unauthorized UI states.
- [x] Add bounded CSV validation/import for employee aliases, deliverable allocations and leave balance commands with row error codes.
- [ ] Complete all `T-WF`, `T-ATT` and `T-GHR` automation, Spring authorization/PostgreSQL scope review, fixes, API/UI docs and runbook. **Partial:** bounded implementation/review/docs automation is present; deferred cases and staging/full-stack/provider gates remain.

**Exit gate:** Attendance truth-table, missing checkout, partial leave, override, idempotency, source-conflict and post-close correction tests pass.

The full exit gate is not met. See [TEST_CASES.md](TEST_CASES.md),
[FIXES.md](FIXES.md), [API_DOCUMENTATION.md](API_DOCUMENTATION.md),
[UI_DOCUMENTATION.md](UI_DOCUMENTATION.md) and
[CHANGELOG.md](CHANGELOG.md).

## 2026-07-29 shift and roster completion

- [x] Publish immutable, effective-dated shift policy versions with timezone,
  scheduled start/end, overnight cutoff, expected net minutes, maximum session,
  split-session and break configuration.
- [x] Assign non-overlapping same-organization shift versions to employees.
- [x] Attribute early-morning punches to the prior work date under an effective
  overnight policy and retain all sessions on exactly one work date.
- [x] Enforce the configured maximum session and split-session policy.
- [x] Evaluate complete allocation/calendar/shift/employee/source coverage for
  every employee-day and return bounded diagnostic details plus exact counts.
  Readiness also rejects incomplete seven-day calendar templates, non-effective
  or superseded shift versions, employees outside active/enabled employment
  windows, and effective allocation totals above 100 percent.
- [x] Create immutable, checksummed, superseding roster snapshots and make
  attendance close materialize and validate exactly that finalized roster.
- [x] Add manager UI for shift publication, assignment, roster readiness and
  finalization.

External-only gates are live greytHR tenant credentials/certification,
provider reconciliation evidence, production identity configuration and
controlled staging/operational sign-off.

## Final local reconciliation — 2026-07-30

- [x] The requested local F02 roster, shift, attendance, leave,
  regularization, administration, API, UI and PostgreSQL implementation is
  complete in the worktree.
- [x] Static frontend tests passed 120/120 and the relevant system lanes passed
  6/6 and 7/7.
- [x] The consolidated browser run passed 287/292; all five affected
  browser/project combinations then passed focused recovery.
- [x] The consolidated Maven run executed 340 tests with two failures and one
  error; the affected backend tests passed focused recovery after their fixes.
- [ ] Live greytHR, controlled identity, deployed grants and staging/production
  acceptance remain external gates.

These results are cumulative evidence, not a claim that one final full Maven
and browser invocation was entirely green after the focused fixes.
