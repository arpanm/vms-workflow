# F02 test issues

> Historical pre-fix issue register. Current dispositions are at the end and in
> [FIXES.md](FIXES.md).

## P1 — Integration test order creates a hidden data dependency

**Evidence.** The class forces one shared ordered context
(`WorkforceAttendanceIT.java:30-42`). Order 4 creates the July 7 half-paid
leave (`137-184`); order 5 then expects that leave to classify 270 minutes as
`PRESENT_HALF_PLUS_PAID_LEAVE_HALF` (`225-248`). Order 5 creates no leave of
its own.

**Impact.** The attendance calculation test does not establish its own premise;
it will fail or test a different result when isolated, reordered or run beside
new tests. Persistent writes (lifecycle, allocations, ledger, snapshots) also
make diagnosis and parallelization brittle.

**Fix.** Use per-test transactional rollback or reset fixtures, and create the
exact leave/session data in each test. Remove method ordering except where an
explicit workflow is the subject under test.

## P1 — Browser E2E fixtures contradict the backend contract

**Evidence.** The fixture sends `PRESENT_IN_PROGRESS` / `MISSING_CHECKOUT`, an
undefined `netMinutes`, and submitted leave status
(`e2e/fixtures/workforce-api.ts:52-67`, `166-181`). The backend emits
`OPEN_SESSION` / `MISSING_CHECKOUT_EXCEPTION`, an `int netMinutes`, and persists
leave as `APPROVED` (`AttendanceService.java:337-364`,
`AttendanceDtos.java:36-50`, `WorkforceService.java:269-275`).

**Impact.** Passing E2E tests approve a UI contract the backend does not supply.
They cannot detect a breaking backend change and currently normalize an
incompatible fake API.

**Fix.** Generate fixture schemas from OpenAPI/contract types or run these flows
against a seeded Java service. At minimum make fixtures byte-for-byte compatible
with the documented DTO/status vocabulary and add contract tests in CI.

## P1 — E2E “server-authoritative” and safety claims are not proven

**Evidence.** F02-003 and F02-004 claim no synthesized duration and a
server-authoritative leave split (`e2e/workforce.spec.ts:63-114`), but the route
interceptor supplies the missing-punch state and the 1/0.5 split itself
(`e2e/fixtures/workforce-api.ts:52-67`, `166-181`). The test only proves that
the UI renders that fixture.

**Fix.** Label these browser-contract tests accurately. Add API/integration
assertions for event counts, session minutes, ledger entries and paid/LWP
apportionment; add at least one browser test against the seeded backend.

## P1 — Authorization coverage omits the dangerous write paths

**Evidence.** The only F02 tenant denial test uses employee GET and days GET
(`WorkforceAttendanceIT.java:307-322`). There is no test for reviewer-to-other
employee punch, leave, regularization, or self-user discovery. The mock grants
the UI both `workforce.read` and `attendance.write`, permissions not matching
the database's EMPLOYEE role (`e2e/fixtures/workforce-api.ts:93-101`;
`V4__workforce_attendance.sql:440`).

**Fix.** Add a permission matrix of 401/403/404/201 assertions for every F02
command and query. Include exact production role fixtures, an employee who is
not linked, a reviewer, a manager and cross-tenant identities.

## P2 — Missing deterministic boundary coverage

**Gaps.** No test covers future date reads, time-zone/cutoff equality, inactive
or exited employment dates, multi-day leave across non-working days, minimum
leave increments, close with unmaterialized days, certification revocation, or
re-close after reopen. `TEST_AUTOMATION.md:42-50` already lists several related
gaps but not the unsafe GET/multi-day/close completeness cases.

**Fix.** Inject `Clock` into services and test fixed instants at the cutoff and
local-day boundary; build fixture-driven tests for each listed persistence and
authorization condition.

## Post-fix disposition — 2026-07-26

| Finding above | Current disposition |
|---|---|
| P1 test order dependency | Fixed: class-level transaction rollback, no order annotation, and each scenario owns its premise. |
| P1 browser/backend fixture mismatch | Fixed for statuses, numeric minutes, leave status and nullable transient day IDs. |
| P1 overstated E2E claims | Fixed in the testing catalog/guide: explicitly browser-contract, intercepted API evidence only. |
| P1 command authorization gaps | Fixed for reviewer punch/leave/regularization denial, self resolution and missing employee. Wider persona/scope matrix remains hardening. |
| P2 deterministic boundaries | Partly fixed: multi-day/off-day leave, invalid units/no POST, close without reads, inactive allocations, revocation and reopen/re-close are covered. Fixed-clock cutoff, overnight/break and future/inactive-employment cases remain. |

The final recorded evidence is 34 backend tests, 26 frontend tests and 18
browser-contract cases, all passing. Exact implemented/planned status is in
[TEST_CASES.md](TEST_CASES.md) and [TEST_AUTOMATION.md](TEST_AUTOMATION.md).
