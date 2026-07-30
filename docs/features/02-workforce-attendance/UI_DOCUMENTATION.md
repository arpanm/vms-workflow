# F02 UI documentation

## Employee administration completion (2026-07-30)

The directory exposes **Add employee**. The profile exposes effective-dated **Edit master fields**, **Archive employee**, and allocation **Add**, **Edit**, **End**, and **Split** commands. Server conflicts remain visible; the browser never estimates concurrent allocation safety.

## Workforce administration

`/workforce/administration` provides manager controls for employee aliases,
bounded deliverable allocations, versioned leave policies and calendars
(including an editable holiday), immutable balance commands, exact-version
leave decisions, regularization review and bounded CSV validate/apply.
Queries and mutations expose loading, empty, error and conflict states through
the shared workforce boundary components. `/attendance/today` also exposes
Start break and End break while an internal session is open; the backend
remains authoritative for valid break order.

F02 routes appear only when `VITE_FEATURE_WORKFORCE_GOVERNANCE=true`. The
browser route guard returns the application's not-found route when the flag is
off. This is a UI rollout flag; JWT/RBAC remains the backend security boundary.

## Persona flows

### Workforce reader or administrator

1. Open `/workforce/employees`.
2. Select an authorized organization.
3. Search by display name, employee number or work email.
4. Open an employee to view lifecycle status, access status, attendance source,
   effective allocations, ledger-derived leave balances and recent leave
   requests.

The directory/profile is read-only. It intentionally displays no salary,
payroll, CTC, rate or markup data. Employee creation, lifecycle editing and
allocation creation exist in the API but do not have F02 UI controls.

### Employee: today's attendance

1. Open `/attendance/today`.
2. The server resolves the signed-in identity through
   `/workforce/employees/me`; there is no organization or peer selector.
3. Review today's calendar expectation, expected/recorded minutes, final status,
   source mode and calculation metadata.
4. Use **Check in** when there is no open session, or **Check out** when the
   server reports `OPEN_SESSION`.

For a missing checkout the page shows “Unresolved” and directs the employee to a
regularization; it never invents worked minutes. For a non-internal source the
punch buttons are disabled and the page explains that the source is externally
managed.

### Employee: leave

1. Open `/attendance/leave`.
2. Select a leave type from ledger-derived balances.
3. Enter start/end dates, requested units and a reason.
4. Submit and read the API-returned paid/LWP split in request history.

Browser validation checks required fields, date order, at least 0.5 units, the
inclusive selected-date ceiling and minimum reason length. An impossible
one-day/1.5-unit request is rejected before any POST. The server still owns
eligible working dates, leave-type minimum increments, balance use, paid/LWP
allocation, overlap rejection and approval status. The current backend
auto-approves valid requests; cancellation and a human approval workflow are not
available.

### Employee: regularization

1. Open `/attendance/regularizations`.
2. Select an eligible prior exception/absence/short-hours date from the current
   month.
3. Select a reason and requested outcome.
4. Provide an evidence narrative and accept the accuracy declaration.
5. Submit and review the resulting `SUBMITTED` request in history.

This records a request only. Approval, rejection, dual-control administrative
correction and recalculation after approval are not implemented. Raw source
events are not edited by this screen.

### Close/reopen reader

1. Open `/attendance/month-close`.
2. Select an authorized organization, engagement and engagement month.
3. Review snapshot versions newest first, including status, day count, close or
   reopen timestamp and supersession ID.

The screen is intentionally read-only. Close and reopen endpoints exist and are
permission-gated, but this page has no close/reopen buttons and makes no
readiness decision.

## Loading, empty and error behavior

- Loading queries show a single “Loading workforce data…” status region.
- Empty directory, allocation, balance, request, regularization and snapshot
  states have explicit messages; no local fallback record is fabricated.
- `401` prompts the user to sign in again.
- `403` and sanitized `404` present authorized-scope/unavailable messaging
  without revealing whether another tenant owns an ID.
- `409` asks the user to refresh before retrying; no attendance/leave outcome is
  assumed.
- Network/5xx failures show service-unavailable messaging and a retry action.
- Correlation IDs from the API are shown when present.

## Known unavailable operations

- workforce create/edit/allocation administration in the UI;
- calendar and leave-policy administration;
- leave approval, rejection, cancellation and balance release;
- regularization review/approval/rejection and administrative correction;
- month close/reopen controls in the UI;
- greytHR configuration, credentials, mapping, sync, reconciliation and cutover;
- CSV import;
- full-stack provider/BFF E2E.

## Related documentation

- [API_DOCUMENTATION.md](API_DOCUMENTATION.md)
- [CODEGEN.md](CODEGEN.md)
- [FIXES.md](FIXES.md)
- [TEST_CASES.md](TEST_CASES.md)
- [CHANGELOG.md](CHANGELOG.md)

## Workforce administration: shift and roster flow

1. Publish a versioned shift policy with timezone, start/end, overnight cutoff,
   expected minutes, maximum session and split-session behavior.
2. Select an employee and assign a published policy over an effective range.
3. Select an engagement and month in **Roster completeness**.
4. Resolve every reported calendar, shift, employee-version or source gap.
5. Enter the review reason and finalize the immutable roster.
6. Use the displayed version, day count and checksum as the attendance-close
   baseline. A later effective change creates a new superseding version.

Earlier “known unavailable” statements in this document are superseded for
calendar/leave administration, governed review, CSV, shift assignment and
roster finalization by the completed `/workforce/administration` workspace.
