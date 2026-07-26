# F02 Backend Code Generation

For the combined backend/frontend implementation inventory, see
[CODEGEN.md](CODEGEN.md). Review dispositions are in [FIXES.md](FIXES.md).

## Result

Implemented a bounded Java/PostgreSQL workforce, leave and attendance vertical
under `backend/`. It extends the F01 JWT and scoped-RBAC boundary and does not
add frontend, package, BFF, provisioning or external provider code.

## Append-only schema

Flyway V4 adds:

- stable employee identities plus non-overlapping effective lifecycle versions;
- effective attendance-source assignments;
- project allocations with a PostgreSQL trigger that verifies project,
  engagement and participating-organization integrity and rejects concurrent
  totals above 100 percent;
- versioned calendars, weekday rules, holidays, effective employee calendar
  assignments and employee-date overrides;
- leave types, idempotent requests and an immutable, uniquely idempotent leave
  balance ledger;
- immutable attendance events, non-overlapping/open-session constraints,
  versioned calculated attendance days and explicit exceptions;
- idempotent regularization requests;
- immutable monthly snapshot headers/days with checksum, version and
  supersession lineage;
- explicit `workforce.*` and `attendance.*` permissions plus
  `VENDOR_HR_ADMIN` and `EMPLOYEE` role templates.

Flyway V5 adds a configurable local missing-checkout cutoff, a database
capability gate for greytHR-authoritative source assignments, and distinct
immutable `CLOSED`/`REOPENED` snapshot versions. Flyway V6 adds immutable
per-date leave-request allocations and backfills existing request aggregates
exactly once. V1–V3 are unchanged.

There are no payroll, salary or rate columns.

## Service behavior

- Employee lifecycle changes split the effective range and preserve the prior
  version.
- Employee/project allocation is rejected by PostgreSQL if the project is from
  another engagement, the employee organization is not a participant, or
  concurrent active/planned allocation exceeds 100 percent.
- Calendar resolution uses employee-date override, holiday and effective
  weekday rules in that precedence order.
- Leave balance is derived from the append-only ledger. An idempotency key can
  create at most one ledger effect. Paid leave beyond available tracked balance
  is returned as an explicit paid/LWP split, then apportioned once across the
  effective working dates in the inclusive request span; weekly offs and
  holidays are not charged when sandwich leave is disabled.
- Online punches use server time. Equal idempotency retries return the original
  immutable event/session. A second independent check-in with an open session
  or checkout without an open session returns conflict.
- A current pre-cutoff open session is `OPEN_SESSION` with zero credited
  minutes. After the configured local cutoff it becomes
  `MISSING_CHECKOUT_EXCEPTION`; no checkout, duration or attendance credit is
  synthesized.
- Attendance-day reads calculate without creating/versioning day rows or
  opening/resolving exceptions. Explicit punch and close commands materialize
  affected days. Attendance days distinguish working days,
  weekly offs, holidays, worked off-days, full presence, partial paid/LWP,
  short hours, absence and missing checkout.
- Closing first materializes every allocated employee/date in the month, then
  creates an immutable SHA-256-addressed attendance snapshot. Only a `CLOSED`
  leaf can be reopened. Reopen creates an immutable `REOPENED` child; the next
  close creates its `CLOSED` child while retaining all prior versions.
- Effective greytHR source use rechecks same-organization certification status
  and fails closed after revocation.

## Authorization

Authorization is derived from JWT `sub`, active identity/membership/
organization, effective role assignment and explicit permission:

- organization employee list/create requires `workforce.read` or
  `workforce.manage`;
- employee mutation/allocation requires organization-scoped
  `workforce.manage`;
- an employee can read their own workforce/leave/attendance only when the
  employee is linked to the authenticated user and the user has
  `attendance.self`;
- `/employees/me` resolves the unique active/enabled linked employee for
  `attendance.self` without requiring `workforce.read`;
- punches, leave submissions and regularization submissions require the linked
  active self;
  managers/admins with organization-scoped `attendance.review` retain reads;
- close/reopen require `attendance.close`/`attendance.reopen` at a participating
  organization or engagement scope;
- inaccessible employee, day and snapshot resources use sanitized 404
  responses.

## Explicit capability boundary

No greytHR HTTP call, credential, mock provider or fabricated certification is
created. `GREYTHR_AUTHORITATIVE` is blocked unless a same-organization
capability record is `CERTIFIED`; the public employee-create path keeps it
blocked until that separately governed certification workflow exists.
Internal attendance remains fully operational.

## Verification

```text
mvn -B -f backend/pom.xml verify
WorkforceAttendanceIT: 20 tests
ApiTenantSecurityIT: 11 tests
JwtDecoderIT: 3 tests
Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
