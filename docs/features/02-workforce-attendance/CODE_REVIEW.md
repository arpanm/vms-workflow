# F02 code review

> Historical pre-fix review snapshot. Read the dated post-fix disposition at the
> end of this file and [FIXES.md](FIXES.md) for current status.

Review scope: the current uncommitted workforce/attendance backend, frontend and
browser E2E implementation. Review completed 2026-07-26. No application or test
code was changed by this review.

## Release assessment

**Do not release F02 as an attendance system yet.** No P0 issue was found, but
the P1 issues below affect attendance integrity, self-service availability,
month-close completeness and provider fail-closed behaviour.

| Priority | Finding | Evidence |
|---|---|---|
| P1 | `GET /attendance/days` persists recalculations and resolves/opens exceptions. | `AttendanceService.java:130-139`, `286-374` |
| P1 | A user with `attendance.review` can create a punch for any employee. | `WorkforceAuthorizationService.java:56-63`; `AttendanceService.java:47-49` |
| P1 | A multi-date leave request credits its whole requested amount on every date. | `WorkforceService.java:269-275`; `AttendanceService.java:300-312` |
| P1 | An employee with only `attendance.self` cannot use the supplied self-service UI. | `V4__workforce_attendance.sql:440`; `attendance-scope.tsx:16-25`; `WorkforceService.java:55-61` |
| P1 | Month close snapshots only materialized `attendance_days`, silently omitting dates never fetched/calculated. | `AttendanceService.java:216-229`, `555-574` |
| P1 | greytHR authority remains active after the referenced certification is revoked. | `V5__workforce_capability_cutoff_and_reopen.sql:21-55` |

See [CODE_ISSUES.md](CODE_ISSUES.md) for remediation detail, and
[SECURITY_ANALYSIS.md](SECURITY_ANALYSIS.md) for authorization and tenant
isolation analysis.

## Positive observations

- The immutable-event, ledger, snapshot-version and snapshot-day database
  triggers are a sound base (`V4__workforce_attendance.sql:387-410`).
- Employee-object denials deliberately return not-found and the integration
  test verifies two cross-tenant cases (`WorkforceAuthorizationService.java:39-63`,
  `WorkforceAttendanceIT.java:307-322`).
- The source-mode trigger validates same-organization certified capabilities at
  assignment creation/update time (`V5__workforce_capability_cutoff_and_reopen.sql:21-45`).
- The browser routes have a client-side feature gate and avoid local fallback
  records (`workforce-route.ts:5-13`, `query-boundary.tsx:45-53`). This is not
  sufficient as a server-side entitlement boundary.

## Contract observations

The UI and its fixture contract diverge from the implemented API in material
ways: production sends `OPEN_SESSION` / `MISSING_CHECKOUT_EXCEPTION` final
statuses and a numeric `netMinutes` (`AttendanceService.java:337-364`,
`AttendanceDtos.java:36-50`), while the browser fixture sends
`PRESENT_IN_PROGRESS` / `MISSING_CHECKOUT` and omits `netMinutes`
(`e2e/fixtures/workforce-api.ts:52-67`). The fixture also reports a submitted
leave request although the service writes `APPROVED` (`WorkforceService.java:269-275`,
`e2e/fixtures/workforce-api.ts:166-181`).

## Post-fix disposition — 2026-07-26

The original release assessment above is preserved as review history. The six
P1 findings were addressed in the current implementation:

- GET attendance projections are read-only and return a nullable transient ID
  when the live result is not materialized;
- punch, leave and regularization commands require linked active self, while
  reviewer access is query-only;
- V6 stores aggregate leave once and apportions immutable per-date units;
- `/workforce/employees/me` enables least-privilege self-service without roster
  discovery;
- close materializes only active/planned allocated employee-days before
  snapshotting;
- effective greytHR authority is rejected after certification revocation.

The 20-test F02 integration suite includes direct invariants for those fixes and
is transactionally isolated. Frontend types accept nullable transient day IDs,
list keys use a stable date fallback, and leave validation prevents an
impossible one-day/1.5-unit POST. Browser fixtures now use backend-shaped
statuses/minutes and leave status; the suite is explicitly documented as
intercepted browser-contract evidence.

The bounded implementation is no longer blocked by the original P1 review
findings. It is still not a complete provider/admin/full-stack F02 release:
future/inactive date semantics, provider integration, regularization approval,
leave lifecycle, CSV import and full-stack staging evidence remain deferred.
See [FIXES.md](FIXES.md), [TASKS.md](TASKS.md) and
[CHANGELOG.md](CHANGELOG.md).
