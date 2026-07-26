# F02 code generation

F02 is implemented as a bounded workforce, leave and attendance vertical across
the Java/PostgreSQL backend and the React frontend. This document aggregates the
implementation detail in [CODEGEN-BACKEND.md](CODEGEN-BACKEND.md) with the
frontend generated for the same contract.

## Backend

The backend adds:

- `WorkforceController` / `WorkforceService` for authorized employee rosters,
  self resolution, effective lifecycle versions, allocations, leave balances
  and idempotent self-service leave requests;
- `AttendanceController` / `AttendanceService` for immutable server-timed
  punches, read-only attendance-day projections, regularization submission and
  immutable month snapshot lineage;
- `WorkforceAuthorizationService` for organization manage/read, linked
  `attendance.self`, reviewer read, and engagement close/reopen boundaries;
- RFC 7807 conflict handling in `ApiExceptionHandler`;
- authenticated OpenAPI at `/v3/api-docs` and Swagger UI at
  `/swagger-ui.html`.

Flyway migrations provide:

- `V4__workforce_attendance.sql`: employee/version/source, allocation,
  calendar, leave, event/session/day/exception/regularization and snapshot
  tables, constraints, triggers, permissions and role templates;
- `V5__workforce_capability_cutoff_and_reopen.sql`: local missing-checkout
  cutoff, assignment-time provider capability gate and `CLOSED`/`REOPENED`
  snapshot states;
- `V6__leave_request_days.sql`: immutable per-date paid/LWP allocations and a
  consistency-checked backfill for earlier aggregate leave requests.

The backend deliberately contains no salary, CTC, rate, markup or payroll
fields. It also contains no greytHR credential, connector, staging, mapping or
reconciliation implementation.

## Frontend

The frontend adds:

- typed workforce/attendance domain models and API functions in
  `src/features/workforce/`;
- React Query keys, reads, commands and post-command invalidation;
- a self-service scope that resolves `/workforce/employees/me` and never
  discovers an organization roster or peers;
- shared loading, retry, unavailable, conflict, unauthorized and sanitized
  not-found presentation;
- employee directory and employee detail routes;
- Today, Leave, Regularizations and read-only Month status routes;
- navigation and route guards controlled by
  `VITE_FEATURE_WORKFORCE_GOVERNANCE`.

The frontend creates random idempotency keys for punches, leave requests and
regularization submissions. The backend remains authoritative for identity
binding, permission, clock, source mode, open-session state, leave allocation
and conflicts.

## Verification generated with the feature

- `WorkforceAttendanceIT`: Spring HTTP/security and Testcontainers PostgreSQL
  coverage, including the post-review invariants;
- `src/features/workforce/api.test.ts`, presentation tests and route-gate unit
  tests;
- `e2e/workforce.spec.ts`: seven Chromium browser-contract cases with
  deterministic intercepted APIs.

Final recorded integrated evidence is 34 passing backend tests (20 in
`WorkforceAttendanceIT`), 26 passing frontend unit/contract tests and 18 passing
browser-contract cases.

The Playwright lane is intentionally not represented as browser-to-Java,
provider or deployed full-stack evidence. See
[TEST_AUTOMATION.md](TEST_AUTOMATION.md), [TEST_CASES.md](TEST_CASES.md) and the
[fix disposition](FIXES.md).

## Related documentation

- [API_DOCUMENTATION.md](API_DOCUMENTATION.md)
- [UI_DOCUMENTATION.md](UI_DOCUMENTATION.md)
- [CHANGELOG.md](CHANGELOG.md)
- [CODE_REVIEW.md](CODE_REVIEW.md)
- [SECURITY_ANALYSIS.md](SECURITY_ANALYSIS.md)
