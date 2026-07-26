# F02 Backend API

For authorization, endpoint semantics, idempotency, transient attendance-day
IDs and Swagger paths, see [API_DOCUMENTATION.md](API_DOCUMENTATION.md).

All routes require a bearer JWT and use RFC 7807 problem responses. UUID, date
and timestamp values use canonical JSON strings; dates are ISO `yyyy-MM-dd`.

## Workforce

| Method | Path | Request | Response |
|---|---|---|---|
| GET | `/api/v1/workforce/employees?organizationId={uuid}` | query | `EmployeeView[]` |
| GET | `/api/v1/workforce/employees/me` | — | `EmployeeView` |
| GET | `/api/v1/workforce/employees/{id}` | — | `EmployeeView` |
| POST | `/api/v1/workforce/employees` | `CreateEmployeeRequest` | `201 EmployeeView` |
| PATCH | `/api/v1/workforce/employees/{id}/lifecycle` | `EmployeeLifecycleRequest` | `EmployeeView` |
| GET | `/api/v1/workforce/employees/{id}/allocations` | — | `AllocationView[]` |
| POST | `/api/v1/workforce/employees/{id}/allocations` | `AllocationRequest` | `201 AllocationView` |
| GET | `/api/v1/workforce/employees/{id}/leave-balances` | — | `LeaveBalanceView[]` |
| GET | `/api/v1/workforce/employees/{id}/leave-requests` | — | `LeaveRequestView[]` |
| POST | `/api/v1/workforce/employees/{id}/leave-requests` | `LeaveRequest` | `201 LeaveRequestView` |

DTO fields:

```text
EmployeeView
  id, organizationId, employeeNumber, firstName, lastName, displayName,
  workEmail, employmentStatus, joinDate, exitDate, activationStatus,
  attendanceSourceMode, validFrom, validTo, version

CreateEmployeeRequest
  organizationId, employeeNumber, firstName, lastName, displayName, workEmail,
  joinDate, designation?, attendanceSourceMode, userProfileId?

EmployeeLifecycleRequest
  effectiveFrom, employmentStatus, activationStatus, exitDate?, reason

AllocationRequest
  engagementId, projectId, validFrom, validTo?, allocationPercent,
  roleOnProject?

AllocationView
  id, employeeId, engagementId, projectId, validFrom, validTo,
  allocationPercent, roleOnProject, status

LeaveBalanceView
  leaveTypeId, leaveTypeCode, leaveTypeName, paid, availableUnits

LeaveRequest
  leaveTypeId, startDate, endDate, units, reason, idempotencyKey

LeaveRequestView
  id, employeeId, leaveTypeId, startDate, endDate, units, paidUnits, lwpUnits,
  reason, status, idempotencyKey, createdAt
```

## Attendance

| Method | Path | Request | Response |
|---|---|---|---|
| POST | `/api/v1/attendance/punches` | `PunchRequest` | `201 PunchView` |
| GET | `/api/v1/attendance/days?employeeId={uuid}&from={date}&to={date}` | query, maximum 63 days | `AttendanceDayView[]` |
| GET | `/api/v1/attendance/regularizations?employeeId={uuid}` | query | `RegularizationView[]` |
| POST | `/api/v1/attendance/regularizations` | `RegularizationRequest` | `201 RegularizationView` |
| GET | `/api/v1/attendance/month-snapshots?engagementMonthId={uuid}` | query | `AttendanceSnapshotView[]` |
| POST | `/api/v1/attendance/month-snapshots` | `CloseSnapshotRequest` | `201 AttendanceSnapshotView` |
| POST | `/api/v1/attendance/month-snapshots/{id}/reopen` | `ReopenSnapshotRequest` | `201 AttendanceSnapshotView` |

DTO fields:

```text
PunchRequest
  employeeId, eventType(CHECK_IN|CHECK_OUT), idempotencyKey

PunchView
  id, employeeId, eventType, occurredAt, workDate, source, idempotencyKey,
  sessionId, sessionStatus, netMinutes

AttendanceDayView
  id? (null for a read-only transient calculation), employeeId, workDate,
  expectedClassification, expectedMinutes,
  sourceMode, netMinutes, leaveUnits, leaveTypeCode, finalStatus,
  exceptionCode, calculationVersion, computedAt

RegularizationRequest
  employeeId, workDate, reasonCode, narrative, requestedOutcome, idempotencyKey

RegularizationView
  id, employeeId, workDate, reasonCode, narrative, requestedOutcome,
  idempotencyKey, status, createdAt

CloseSnapshotRequest
  engagementMonthId

ReopenSnapshotRequest
  reason

AttendanceSnapshotView
  id, engagementMonthId, version, status(CLOSED|REOPENED), supersedesId,
  closedAt, reopenedAt, checksum, dayCount
```

`OPEN_SESSION` means an actual checkout is still possible before the effective
calendar cutoff. `MISSING_CHECKOUT` means the cutoff has passed. Neither state
credits provisional minutes or creates a synthetic event.

Attendance-day GETs are read-only calculations. They do not create or version
`attendance_days`, open/resolve exceptions, or otherwise materialize database
rows. Punch commands materialize impacted days, and month close materializes
every allocated employee/date in the month before readiness validation and
snapshotting. Multi-day leave totals are stored once and apportioned into
immutable per-date paid/LWP units.

Punches, leave submissions and regularization submissions require the active
linked employee plus `attendance.self`; `attendance.review` remains read-only.
`/employees/me` applies the same active-link boundary without requiring
`workforce.read`, and returns the standard sanitized `404` when no unique
active authorized employee link exists.

## Error status

- `400` invalid DTO, date range, enum or leave type;
- `401` missing/invalid bearer authentication;
- `403` organization collection/create permission denial;
- `404` sanitized inaccessible or unknown employee/engagement/snapshot scope;
- `409` open-session conflict, allocation/temporal constraint conflict,
  overlapping leave, unavailable source mode or snapshot readiness conflict.
