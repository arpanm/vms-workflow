# F02 changelog

## 2026-07-30 — employee/allocation completion

- Added employee create, effective master edit, and archive UI/API flows.
- Added allocation create/edit/end/split UI/API flows.
- Serialized allocation mutations and added two-session committed-sum regression coverage.

## 2026-07-29 — Local governance completion

- Added immutable effective employee aliases and deliverable allocations
  bounded by project percentage, dates, employee and engagement scope.
- Added versioned calendar/holiday and leave-policy administration, immutable
  balance commands, optimistic leave decisions/cancellation and stable replay.
- Added manager-only leave/regularization inboxes, bounded workforce CSV
  validation/apply, durable break pairs and break-aware checkout.
- Added the workforce administration route with alias/allocation, policy,
  calendar holiday, balance, approval, regularization and CSV controls.
- Added Start break/End break controls to Today's attendance.
- Added fresh-schema integration, API unit and intercepted Chromium evidence.
- Remaining: a full roster/shift-template rule DSL and exhaustive roster
  completeness policies; provider and controlled-staging gates remain external.

## 2026-07-26 — Workforce, leave and attendance vertical

### Backend and database

- Added workforce and attendance controllers, DTOs, services, authorization
  policy and domain conflict handling.
- Added V4 workforce/attendance schema with effective-dated employees, source
  assignments, allocation integrity, calendars, append-only leave/event
  evidence, sessions, calculated days, exceptions, regularizations and immutable
  snapshot lineage.
- Added V5 missing-checkout cutoff, provider capability assignment gate and
  explicit reopened snapshot state.
- Added V6 immutable per-date leave allocation/backfill.
- Added `/api/v1/workforce/employees/me` for least-privilege self-service.
- Changed attendance-day GET to a read-only projection; non-materialized/stale
  calculations return `id: null`.
- Split reviewer read from linked-self commands.
- Materialized the allocated employee/date universe during month close.
- Rechecked provider certification on effective source evaluation.
- Serialized employee and engagement-month commands and tightened idempotency
  payload checks.

### Frontend

- Added employee directory and profile routes.
- Added self-only Today, Leave and Regularizations routes.
- Added read-only Month status/snapshot lineage route.
- Added workforce API/domain/query hooks, validation, action presentation,
  scoped selectors and shared query/mutation error handling.
- Added workforce navigation and the
  `VITE_FEATURE_WORKFORCE_GOVERNANCE` route flag.
- Updated API client handling for RFC 7807 `application/problem+json`.

### Tests

- Added `WorkforceAttendanceIT` with 20 transactional Spring/Testcontainers
  PostgreSQL tests in the latest recorded backend run.
- Added post-review integration coverage for read-only GET, per-date leave,
  reviewer command denial, self employee resolution, missing object
  non-disclosure, snapshot completeness/current-leaf lineage and certification
  revocation.
- Added workforce frontend API/presentation/route unit tests; the final frontend
  run contains 26 passing tests.
- Added seven F02 intercepted Chromium browser-contract journeys, including the
  proof that self-service does not query organizations or peer rosters and that
  an impossible leave quantity makes no POST. The complete browser-contract run
  contains 18 passing cases.

Browser-contract tests do not exercise Java, PostgreSQL, a BFF or an identity
provider. See [TEST_AUTOMATION.md](TEST_AUTOMATION.md).

### Documentation and review

- Added backend/API/test automation notes and the six independent review and
  analysis artifacts.
- Added aggregate [CODEGEN.md](CODEGEN.md), complete
  [API_DOCUMENTATION.md](API_DOCUMENTATION.md), persona
  [UI_DOCUMENTATION.md](UI_DOCUMENTATION.md), review [FIXES.md](FIXES.md) and
  this changelog.
- Updated [TASKS.md](TASKS.md) and [TEST_CASES.md](TEST_CASES.md) to distinguish
  completed, partial and deferred scope.

### Remaining limitations

- No greytHR credentials, provider call, mapping, reconciliation, outage/retry
  or governed cutover workflow.
- No regularization approval/admin dual control, leave approval/cancellation,
  calendar/policy administration or UI mutation controls for workforce/month
  close.
- No break-pair, overnight shift, long-session warning or complete
  future/inactive-employment truth table.
- No CSV import.
- No real provider/BFF/browser-to-Java/PostgreSQL full-stack or controlled
  staging evidence.

See the dated disposition in [FIXES.md](FIXES.md) and detailed current contract
in [API_DOCUMENTATION.md](API_DOCUMENTATION.md).

## 2026-07-29 — Shift and roster production flow

- Added PostgreSQL-owned immutable shift policy, assignment and roster snapshot
  tables with tenant/effective-range constraints and least-privilege grants.
- Added Java API/service flows for shift publication/assignment, exact roster
  readiness diagnostics and checksummed finalization.
- Attendance now applies configured overnight work-date attribution, maximum
  session and split-session rules, and closes only against a finalized roster.
- Added the React manager flow plus Playwright fixtures/journey coverage.
