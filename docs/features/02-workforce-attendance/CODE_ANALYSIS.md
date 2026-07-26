# F02 code analysis

> Historical pre-fix analysis followed by a dated current-architecture section.

## Data flow and state ownership

```text
Browser route -> React Query API client -> controller -> authorization -> service -> PostgreSQL
                                      GET /attendance/days -> calculateDay -> attendance_days/exceptions
                                      POST punch            -> events/sessions -> calculateDay
                                      POST leave            -> request/ledger -> later calculateDay
                                      POST close            -> snapshot version + copied snapshot days
```

The boldest boundary problem is that the nominal read branch invokes the same
state-mutating calculator as the punch branch. This makes materialization order
part of business semantics, and lets month close depend on which screens people
previously opened.

## Backend behavior

`AttendanceService` correctly records source events and sessions separately,
and snapshot rows copy the day values so later day recalculation cannot alter an
existing snapshot. However:

- `days` persists and resolves state on GET (`AttendanceService.java:130-139`,
  `286-374`).
- The calculator's leave query repeats request-level totals on every covered
  date (`300-312`).
- `snapshotDays` selects only current materialized values rather than deriving
  the allocated roster/day universe (`555-574`).
- `punch` accepts only `INTERNAL_AUTHORITATIVE` (`65-68`); a
  `HYBRID_TRANSITION` assignment created with an INTERNAL source is therefore
  not punchable, despite the database allowing the mode. The desired hybrid
  operating model needs an explicit design and tests.
- Future dates are not constrained before an ABSENT row is written.

## Authorization and tenant isolation

Employee read/attendance denials are non-disclosing because object checks throw
`EntityNotFoundException` (`WorkforceAuthorizationService.java:39-63`), and
the exception handler returns 404 (`ApiExceptionHandler.java:21-24`). This is
good for cross-tenant object IDs.

The authorization model is nevertheless too coarse for commands: review grants
punch capability, and leave creation is guarded by `requireEmployeeRead`
(`WorkforceService.java:215-218`) rather than a self/manager command grant.
Collection access returns explicit 403 through `requireOrganizationRead`, while
object access returns 404; that can be acceptable, but should be documented and
tested consistently.

## Frontend/API fit

The UI hides unavailable routes using `VITE_FEATURE_WORKFORCE_GOVERNANCE`, but
it does not use a capability response from the backend. Attendance routes use a
roster selector designed for `workforce.read`, defeating the employee's
`attendance.self` role. The UI's read-only source-mode affordance is sensible
but cannot compensate for server-side authorization or stale capability state.

## Snapshot lineage

The model is append-only: versions cannot update/delete and a replacement
supersedes its predecessor (`V4__workforce_attendance.sql:361-410`). Reopen
copies snapshot day values (`AttendanceService.java:268-282`), preserving the
old evidence. The implementation should distinguish the reopening artifact from
a later re-close in its timestamps, and must calculate/validate all required
attendance days before taking an immutable snapshot.

## Post-fix architecture — 2026-07-26

The read and materialization paths are now separate:

```text
GET days -> evaluateDay -> matching persisted row OR transient projection (id null)
punch    -> employee lock -> event/session -> materializeDay
close    -> month lock -> materialize active/planned allocated dates
         -> validate active/planned blockers -> immutable snapshot
```

Leave evaluation now reads immutable `leave_request_days`, whose aggregate is
validated against the parent request. Self-service resolves one linked active
employee through `/employees/me`; review permission does not cross into command
authorization. Snapshot status determines whether `closedAt` or `reopenedAt` is
populated, and only the current closed leaf can be reopened. Effective greytHR
source evaluation rechecks the same-tenant certification.

Frontend `AttendanceDay.id` is nullable and regularization list keys use the
employee/date pair for transient projections. Leave validation mirrors
the inclusive date-span ceiling before POST, while the backend remains
authoritative for eligible working dates.

Remaining architecture work is outside this bounded vertical: provider
integration/cutover, admin approval/correction workflows, future and inactive
employment truth-table semantics, CSV import and a real full-stack environment.
See [CODEGEN.md](CODEGEN.md), [FIXES.md](FIXES.md) and
[API_DOCUMENTATION.md](API_DOCUMENTATION.md).
