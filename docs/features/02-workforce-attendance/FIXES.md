# F02 review fix disposition

Disposition date: 2026-07-26.

This records every P1/P2 issue from [CODE_ISSUES.md](CODE_ISSUES.md) and
[TEST_ISSUES.md](TEST_ISSUES.md). The original findings remain preserved in
those documents.

## Code issues

| Original issue | Disposition | Current evidence | Test evidence |
|---|---|---|---|
| P1 — GET attendance days writes records | **Fixed** | `AttendanceService.days` is read-only and calls `readDay`; a changed/unmaterialized projection is returned transiently with `id = null`. Only punch and close commands call `materializeDay`. | `attendanceGetCalculatesWithoutCreatingVersioningOrResolvingRows` repeats GET and verifies day/exception counts are unchanged. |
| P1 — reviewer can write attendance | **Fixed** | Punch and regularization commands call `requireAttendanceSelf`; leave submission does the same. `attendance.review` remains accepted only by query paths. | `reviewerCanReadButCannotPunchOrSubmitEmployeeRegularization` covers punch, regularization and leave denial. |
| P1 — aggregate leave is repeated on every date | **Fixed** | V6 adds immutable `leave_request_days`; `WorkforceService` allocates the aggregate once over eligible dates and `AttendanceService` reads per-date units. | Multi-day aggregate reconciliation, units-over-span rejection and weekend/holiday exclusion tests. |
| P1 — self-service requires peer roster access | **Fixed** | `GET /workforce/employees/me`, `activeSelfEmployee`, `useMyEmployee` and `AttendanceEmployeeScope` bind self routes to one active/enabled authorized employee without `workforce.read`. | Backend self-link test, frontend API unit tests and `E2E-F02-007` verify no organization/roster discovery. |
| P1 — snapshot can omit unmaterialized employee-days | **Fixed for the implemented allocated-roster rule** | Close locks the month and materializes every date covered by active/planned engagement allocations before blocker validation and snapshot insertion. Materialization, unresolved blockers and snapshot selection all apply the same active/planned status filter. | `snapshotCloseMaterializesEveryAllocatedDayWithoutPriorReads` asserts 30 materialized/snapshotted days; `inactiveAllocationDaysNeitherEnterNorBlockSnapshot` verifies inactive allocation evidence is excluded. Multi-employee and policy-wide roster rules remain future hardening. |
| P1 — provider certification revocation is not rechecked | **Fixed at effective-use boundary** | Effective employee source evaluation joins the referenced certification and rejects greytHR authority unless it is still same-organization and `CERTIFIED`. | `revokedCapabilityFailsClosedWhenEffectiveSourceIsEvaluated`. Provider sync/cutover remains unavailable. |
| P2 — future/inactive dates can become persisted absences | **Partly fixed; remaining semantics deferred** | GET projections no longer persist anything, so future reads cannot create absence rows. The projection can still classify a future active-version date as `ABSENT`, and full inactive/preboarding/exit truth-table behavior is not implemented. | GET no-write test covers persistence only. Fixed-clock future/inactive/exited tests remain open. |
| P2 — reopened time inferred from any supersession | **Fixed** | `snapshotView` derives `closedAt` only for `CLOSED` and `reopenedAt` only for `REOPENED`. Reopen is restricted to the current closed leaf. | Reopen/re-close test asserts the version-3 closed child has no `reopenedAt`; non-leaf reopen is rejected. |
| P2 — browser feature flag is not a server capability gate | **Deferred by current rollout design** | The flag controls navigation/routes; the API is always protected by JWT and RBAC. There is no server kill switch. | Route-gate unit test covers the browser only. Add an API kill switch only if product/operations requires the flag to disable the service itself. |

## Test issues

| Original issue | Disposition | Current evidence |
|---|---|---|
| P1 — integration test order dependency | **Fixed** | `WorkforceAttendanceIT` is class-level transactional, has no method ordering, and the partial-leave calculation test creates its own leave premise. |
| P1 — browser fixture contradicts backend contract | **Fixed for the current DTO/status surface** | Fixtures use `OPEN_SESSION`, `MISSING_CHECKOUT_EXCEPTION`, numeric zero minutes and `APPROVED` leave. `AttendanceDay.id` is nullable and transient rows use an employee/date list-key fallback. |
| P1 — browser safety/server-authoritative claims are overstated | **Fixed in documentation; full-stack lane deferred** | The E2E catalog and testing guide explicitly define these as intercepted browser-contract tests. Backend invariants are separately tested in Spring/PostgreSQL. No full-stack claim is made. |
| P1 — dangerous command authorization lacks coverage | **Fixed for current self/reviewer commands** | Reviewer denial covers punch, regularization and leave; missing employee and self-link tests cover sanitized not-found behavior. A broader persona/project matrix remains hardening work. |
| P2 — deterministic boundary gaps | **Partly fixed** | Revocation, multi-day/non-working leave, invalid inclusive-span/no-POST UI behavior, close without prior reads, inactive allocation exclusion and reopen/re-close are covered. Fixed-clock cutoff equality, overnight/break pairs, future dates and inactive/exited employment dates remain deferred. |

## Deferred work that blocks a broader F02 claim

- greytHR credentials, discovery, mapping, staging, sync, reconciliation,
  outage/retry and governed cutover;
- regularization review/approval and administrative dual-control correction;
- leave approval workflow, cancellation/release and policy administration;
- break pairs, overnight shifts, long-session warnings and injected-clock edge
  cases;
- CSV import and supplied-template validation;
- server-side feature kill switch, if required;
- real provider/BFF/browser/Java/PostgreSQL full-stack E2E and staging evidence.

See [TASKS.md](TASKS.md), [TEST_CASES.md](TEST_CASES.md),
[API_DOCUMENTATION.md](API_DOCUMENTATION.md) and
[UI_DOCUMENTATION.md](UI_DOCUMENTATION.md).
