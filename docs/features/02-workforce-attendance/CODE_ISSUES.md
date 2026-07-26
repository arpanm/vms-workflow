# F02 code issues

> Historical pre-fix issue register. Findings are intentionally preserved;
> current dispositions are at the end and in [FIXES.md](FIXES.md).

## P1 — Read endpoint changes attendance records

**Evidence.** `AttendanceController.java:47-54` exposes `GET /days`.
`AttendanceService.days` is transactional and maps every requested date through
`calculateDay` (`AttendanceService.java:130-139`). That routine inserts or
supersedes `attendance_days` (`351-374`) and inserts or resolves
`attendance_exceptions` (`322-335`).

**Impact.** A cache, retry, browser refresh, reviewer query or prefetch changes
the audit trail and can resolve an exception. Replaying the same GET after a
cutoff also changes the observed domain state. This breaks safe/read-only HTTP
semantics and makes evidence generation caller-dependent.

**Fix.** Make GET query only precomputed current versions. Recalculate through
explicit command/job paths with actor/reason/audit metadata; alternatively use
a non-persistent projection for an on-demand preview. Prevent state-changing
queries on GET in tests and at the controller/service boundary.

## P1 — Review permission grants attendance write authority

**Evidence.** `requireAttendanceAccess` accepts either `attendance.self` or
organization-wide `attendance.review` (`WorkforceAuthorizationService.java:56-63`).
Both punch and regularization creation call it (`AttendanceService.java:47-49`,
`152-154`). Thus an attendance reviewer can submit `CHECK_IN`/`CHECK_OUT` as
another employee; the recorded subject is the reviewer but the event is still a
new immutable employee punch (`82-93`, `113-124`).

**Impact.** Review access can fabricate the attendance record it is expected to
review. This is a direct integrity and segregation-of-duties failure.

**Fix.** Split read, self-punch, self-request and administrative-correction
authorization. Permit ordinary punch commands only for the linked employee with
`attendance.self`; use a distinct correction permission plus reason/approval
workflow for cross-employee changes. Add negative authorization tests for a
reviewer posting punches and leave/regularization commands for another worker.

## P1 — Leave quantity is applied once per calendar day

**Evidence.** A request stores one `requested_units`, `paid_units` and
`lwp_units` for its whole inclusive range (`WorkforceService.java:269-275`).
Day calculation joins that range then directly sums those request-level fields
for *each* date in it (`AttendanceService.java:300-312`). A two-day, one-unit
request therefore appears as one paid unit on each date, while only one unit is
consumed from the ledger (`276-284`).

**Impact.** Attendance status, paid/LWP reports and close snapshots over-credit
leave. The data model cannot prove how partial leave is apportioned across dates.

**Fix.** Validate requested units against working dates and leave-type minimum
increment; persist immutable per-day leave allocations (or a deterministic,
stored allocation schedule) whose sum equals the request. Calculate days from
that schedule and add multi-day/weekend/holiday/half-day tests.

## P1 — Self-service UI has no authorized employee discovery path

**Evidence.** The EMPLOYEE role only receives `catalog.read` and
`attendance.self` (`V4__workforce_attendance.sql:424-441`). Attendance routes
render `AttendanceEmployeeScope`, which first calls the organization-wide
employee list (`attendance-scope.tsx:16-25`, `scope-selectors.tsx:67-99`). That
list requires `workforce.read` (`WorkforceService.java:55-61`). There is no
`/me/employee` or equivalent self endpoint.

**Impact.** A normal employee receives a 403 at the roster step and cannot reach
the self-authorized days, punch or leave endpoints. The E2E mock masks this by
granting `workforce.read` (`e2e/fixtures/workforce-api.ts:93-101`).

**Fix.** Add a narrowly scoped self-profile endpoint or include linked employee
identity in `/me`; use it for self-service routes. Do not load an organization
roster for employee self-service. Test a token with precisely the EMPLOYEE
role's production permissions.

## P1 — Month close can silently snapshot an incomplete population

**Evidence.** Close builds its payload only from existing current
`attendance_days` (`AttendanceService.java:216-228`, `555-574`). Days are
materialized only as side effects of GET `/attendance/days` or punch handling
(`94`, `125`, `136-138`). There is no roster/calendar expansion or completeness
gate before close.

**Impact.** Employees/dates never viewed or punched are absent rather than
represented as an explicit status. A snapshot can be immutable yet incomplete,
with a checksum that faithfully hashes the wrong set.

**Fix.** Generate the required employee/date population deterministically in a
job/command before close, include explicit no-attendance statuses, and block
close when the computed roster/calendar coverage is incomplete. Test a close
without prior GET requests and assert all allocated employee-days are present.

## P1 — Capability revocation does not fail closed for existing assignments

**Evidence.** The capability trigger runs only on insert/update of the source
assignment (`V5__workforce_capability_cutoff_and_reopen.sql:51-55`). It verifies
the certification status only then (`21-45`). Updating a certification from
`CERTIFIED` to `REVOKED` does not touch assignments, and attendance reads retain
the old `source.mode` (`AttendanceService.java:406-429`).

**Impact.** A tenant can remain in greytHR-authoritative (or greytHR hybrid)
mode after certification is revoked, contrary to a fail-closed capability gate.

**Fix.** Enforce status on certification updates (reject revoke while referenced
or atomically end/downgrade assignments), and validate effective certification
on every authority-sensitive operation. Add certified-to-revoked integration
coverage, including another tenant's certification.

## P2 — Future and inactive employment dates can become persisted absences

**Evidence.** `/days` accepts any ordered 63-day range (`AttendanceService.java:131-138`).
`calculateDay` classifies zero worked minutes as `ABSENT` when no leave is
present (`436-470`) and writes the result (`351-364`). It does not reject future
dates or suppress absence before the employee is active; `employeeState` only
requires an effective version/source assignment (`406-433`).

**Fix.** Disallow future materialization or return a non-persistent
`NOT_STARTED` projection; make expected attendance conditional on active,
enabled employment and join/exit boundaries.

## P2 — Reopened timestamp is inferred from lineage rather than status

**Evidence.** `snapshotView` sets `reopenedAt` to `closedAt` whenever
`supersedes_id` is non-null (`AttendanceService.java:615-628`). A later CLOSED
replacement after reopen also has `supersedes_id`, so it is represented as
reopened even though its status is CLOSED.

**Fix.** Store an explicit `reopened_at`/event type or derive it only when
`status == REOPENED`; test a reopen followed by a re-close.

## P2 — Client route flag is not a server capability gate

**Evidence.** The feature flag only hides/navigationally rejects frontend routes
(`workforce-route.ts:5-13`, `app-sidebar.tsx:76-82`). Backend F02 controllers
remain mounted and authorize independently (`AttendanceController.java:30-92`,
`WorkforceController.java:30-100`).

**Fix.** If the flag represents rollout/security control rather than UI rollout,
enforce it at the API boundary too and add enabled/disabled API tests.

## Post-fix disposition — 2026-07-26

| Finding above | Current disposition |
|---|---|
| P1 unsafe GET writes | Fixed and integration-tested: read-only transient projection. |
| P1 reviewer write authority | Fixed and integration-tested: linked-self command boundary. |
| P1 leave repeated per date | Fixed and integration-tested: immutable V6 per-date allocation. |
| P1 unusable self-service | Fixed and backend/unit/browser-contract tested: `/employees/me`. |
| P1 incomplete close population | Fixed for active/planned allocated employee-days and tested without prior GET; inactive allocations are explicitly excluded from materialization, blockers and snapshot selection. |
| P1 stale provider certification | Fixed at effective-use boundary and revocation-tested. |
| P2 future/inactive persisted absence | Persistence part fixed by read-only GET; future/inactive classification semantics remain deferred. |
| P2 reopened timestamp | Fixed and reopen/re-close tested. |
| P2 client-only rollout flag | Deferred by design; backend JWT/RBAC remains authoritative, but there is no server kill switch. |

Detailed code/test evidence and remaining boundaries are in
[FIXES.md](FIXES.md). The current endpoint contract is in
[API_DOCUMENTATION.md](API_DOCUMENTATION.md).
