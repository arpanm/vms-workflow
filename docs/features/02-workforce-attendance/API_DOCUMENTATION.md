# F02 API documentation

## Employee and allocation lifecycle commands (2026-07-30)

- `POST /api/v1/workforce/employees` creates an authorized effective-dated employee.
- `PATCH /api/v1/workforce/employees/{employeeId}` preserves the prior employee version and starts a new master-field version.
- `PATCH /api/v1/workforce/employees/{employeeId}/lifecycle` records archive/exit/access changes.
- Allocation create, edit, end, and split commands live below `/employees/{employeeId}/allocations`.

Every allocation mutation takes an employee-scoped PostgreSQL transaction lock. The database recomputes effective-date boundary sums and rejects a committed total above 100%, including competing sessions.

All F02 endpoints are under `/api/v1`, require an authenticated bearer JWT and
return JSON. Validation and domain failures use RFC 7807 Problem Details.
Runtime OpenAPI is authenticated at `/v3/api-docs`; Swagger UI is authenticated
at `/swagger-ui.html`.

Dates use ISO `yyyy-MM-dd`; timestamps include an offset; identifiers are UUIDs.

## Authorization model

| Capability | Permitted operations |
|---|---|
| `workforce.read` in an organization | List that organization's employees; read employee, allocations, leave balances and requests. |
| `workforce.manage` in an organization | Create employees, create effective lifecycle versions and create allocations. |
| Active linked employee plus `attendance.self` | Resolve `/employees/me`; read self workforce/leave/attendance; create self punches, leave requests and regularizations. |
| `attendance.review` in an organization | Read employee attendance days and regularizations; it does not authorize self-service commands for another employee. |
| `attendance.close` for an engagement | Read snapshot history and close the engagement month. |
| `attendance.reopen` for an engagement | Reopen the current closed snapshot leaf. |

Unknown or inaccessible employee, engagement-month and snapshot objects use the
same sanitized `404 Resource not found.` response. Organization collection
permission failures use `403`.

## Workforce endpoints

| Method and path | Request | Success semantics |
|---|---|---|
| `GET /workforce/employees?organizationId={uuid}` | Required organization query parameter. | `200 EmployeeView[]`, ordered by employee number. Requires `workforce.read`. |
| `GET /workforce/employees/me` | None. | `200 EmployeeView` for exactly one active, enabled linked employee with `attendance.self`; otherwise sanitized `404`. It does not require or expose a roster. |
| `GET /workforce/employees/{id}` | Employee UUID path. | `200 EmployeeView` for workforce readers or the authorized linked self. |
| `POST /workforce/employees` | `CreateEmployeeRequest`. | `201 EmployeeView`; creates identity, lifecycle version 1 and source-mode assignment. `GREYTHR_AUTHORITATIVE` is rejected until a governed certification workflow exists. |
| `PATCH /workforce/employees/{id}/lifecycle` | `EmployeeLifecycleRequest`. | `200 EmployeeView`; closes the prior effective version and inserts a new version. |
| `GET /workforce/employees/{id}/allocations` | Employee UUID path. | `200 AllocationView[]`. |
| `POST /workforce/employees/{id}/allocations` | `AllocationRequest`. | `201 AllocationView`; database rules reject invalid project/engagement/tenant relationships, invalid dates and overlapping totals over 100%. |
| `GET /workforce/employees/{id}/leave-balances` | Employee UUID path. | `200 LeaveBalanceView[]` derived from the append-only ledger. |
| `GET /workforce/employees/{id}/leave-requests` | Employee UUID path. | `200 LeaveRequestView[]`, newest first. |
| `POST /workforce/employees/{id}/leave-requests` | `LeaveRequest`. | `201 LeaveRequestView`; self-only, idempotent, currently auto-approved, split into aggregate paid/LWP units and immutable eligible per-date allocations. |

### Workforce DTOs

```text
CreateEmployeeRequest
  organizationId, employeeNumber, firstName, lastName, displayName, workEmail,
  joinDate, designation?, attendanceSourceMode, userProfileId?

EmployeeLifecycleRequest
  effectiveFrom, employmentStatus, activationStatus, exitDate?, reason

AllocationRequest
  engagementId, projectId, validFrom, validTo?, allocationPercent,
  roleOnProject?

LeaveRequest
  leaveTypeId, startDate, endDate, units, reason, idempotencyKey
```

`EmployeeView`, `AllocationView`, `LeaveBalanceView` and `LeaveRequestView`
fields are summarized in [API.md](API.md).

Leave units must use the leave type's minimum increment, fit within eligible
working dates, and use a date span of at most 366 days. With the implemented
non-sandwich policy, zero-expected-minute weekly offs/holidays are not charged.
An exact idempotency replay returns the original request; reusing the key for
different request content returns `409`.

## Attendance endpoints

| Method and path | Request | Success semantics |
|---|---|---|
| `POST /attendance/punches` | `PunchRequest { employeeId, eventType, idempotencyKey }`. | `201 PunchView`; linked self only. Server time and effective employee timezone determine occurrence/work date. Exact replay returns the original immutable event. |
| `GET /attendance/days?employeeId={uuid}&from={date}&to={date}` | Inclusive, ordered range, maximum 63 days. | `200 AttendanceDayView[]`; authorized self or reviewer. This is a read-only calculation. |
| `GET /attendance/regularizations?employeeId={uuid}` | Employee UUID query. | `200 RegularizationView[]`; authorized self or reviewer. |
| `POST /attendance/regularizations` | `RegularizationRequest`. | `201 RegularizationView`; linked self only, idempotent submission. It does not approve or apply a correction. |
| `GET /attendance/month-snapshots?engagementMonthId={uuid}` | Engagement-month UUID query. | `200 AttendanceSnapshotView[]`, oldest version first. Requires close permission. |
| `POST /attendance/month-snapshots` | `{ engagementMonthId }`. | `201 AttendanceSnapshotView`; locks the month, materializes allocated dates, blocks unresolved critical exceptions, then creates an immutable closed version. Repeating close on an already-closed leaf returns that leaf. |
| `POST /attendance/month-snapshots/{id}/reopen` | `{ reason }`. | `201 AttendanceSnapshotView`; creates an immutable `REOPENED` child only when `{id}` is the current `CLOSED` leaf. |

### Read-only transient attendance days

`GET /attendance/days` evaluates calendar, effective source capability, closed
sessions, open-session cutoff and per-date leave without writing
`attendance_days` or `attendance_exceptions`.

If an existing current materialized row still matches, it is returned with its
normal UUID. If no row exists or the live calculation differs, the response is
a transient projection:

```json
{
  "id": null,
  "employeeId": "00000000-0000-0000-0000-000000000801",
  "workDate": "2026-07-09",
  "calculationVersion": 1,
  "computedAt": "2026-07-26T10:00:00Z"
}
```

The null ID means “not persisted”; `calculationVersion` is the version that a
later explicit materialization would use. Clients must not treat the transient
ID as a stable resource identifier. Punch and month-close commands perform
materialization.

`OPEN_SESSION` credits zero minutes before the configured local cutoff.
`MISSING_CHECKOUT` is the exception code after cutoff and maps to final status
`MISSING_CHECKOUT_EXCEPTION`; neither state synthesizes a checkout or duration.

### Idempotency

- Punch keys are unique per employee. A replay with the same event type returns
  the original event/session; a different event type returns `409`.
- Leave and regularization keys are unique per employee. Exact replays return
  the original response; different content returns `409`.
- Employee and month advisory/row locks serialize conflicting commands.

## Status codes

| Status | Meaning |
|---|---|
| `200` | Successful query or lifecycle patch. |
| `201` | Successful POST, including an exact idempotent replay under the current controller contract. |
| `400` | Invalid body, UUID/date/range, enum-like value, leave increment/span or other request validation. |
| `401` | Missing, invalid or unacceptable bearer authentication. |
| `403` | Authenticated caller lacks organization collection/manage permission. |
| `404` | Unknown or inaccessible object/self binding, intentionally non-disclosing. |
| `409` | Domain/data conflict: open-session state, changed idempotency payload, overlap/allocation constraint, source capability, snapshot readiness/lineage, or similar conflict. |

## Related documentation

- [UI_DOCUMENTATION.md](UI_DOCUMENTATION.md)
- [CODEGEN.md](CODEGEN.md)
- [FIXES.md](FIXES.md)
- [TEST_AUTOMATION.md](TEST_AUTOMATION.md)
- [CHANGELOG.md](CHANGELOG.md)
