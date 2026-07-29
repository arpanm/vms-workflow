# F02 Backend Test Automation

## 2026-07-29 completion evidence

`WorkforceAdministrationIT` adds three fresh-schema Testcontainers cases for
calendar publication with holidays, exact balance-command replay/conflict,
deliverable engagement/date/percentage bounds, manager-only leave and
regularization inboxes, governed leave approval/cancellation ledger movement,
bounded CSV validation/apply and durable break pairs.

```text
mvn -B -f backend/pom.xml -Dtest=NoSuchTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=WorkforceAdministrationIT verify
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

npm run typecheck
npm run lint -- --quiet
npm run test -- --run src/features/workforce/api.test.ts \
  src/features/workforce/presentation.test.ts
2 files, 11 tests passed

npx playwright test --project=workforce-chromium --grep E2E-F02-008
1 passed
```

The permanent `E2E-F02-008` journey exercises alias, deliverable allocation,
policy, calendar holiday, balance command, leave decision, regularization and
CSV manager flows through intercepted API contracts.

`WorkforceAttendanceIT` uses Spring Boot, MockMvc, Flyway and Testcontainers
PostgreSQL 18. Its test-only fixtures are isolated in
`backend/src/test/resources/db/testdata/V1001__workforce_attendance_test_fixtures.sql`.

Covered:

- `T-WF-001/002`: employee read and effective lifecycle version history;
- `T-WF-003/004`: normal concurrent allocation and PostgreSQL rejection when
  overlapping totals exceed 100 percent;
- `T-WF-005/006`: weekly off, holiday and employee working-date override
  precedence;
- `T-WF-007–009`: paid/LWP split, request replay idempotency, immutable
  consumption entry and duplicate monthly-accrual rejection;
- `T-ATT-001–003`: server-timed check-in/out, immutable punch count, session
  close and retry idempotency;
- `T-ATT-004/005`: 270 worked minutes plus half paid leave classification;
- `T-ATT-006`: post-cutoff open session becomes an explicit missing-checkout
  exception with zero minutes and no synthetic event;
- `T-ATT-012/013`: deterministic snapshot checksum/content, database
  immutability and `REOPENED` supersession lineage;
- tenant denial: cross-organization employee and attendance access returns the
  same sanitized not-found response;
- `T-GHR-001/002`: draft/uncertified capability cannot enable
  greytHR-authoritative source mode, and revoked certification fails closed at
  effective source evaluation;
- repeated attendance GET is read-only and creates/resolves no day/exception;
- multi-day leave aggregates reconcile to immutable per-date allocation,
  reject units beyond eligible dates, and skip weekly offs/holidays under the
  implemented non-sandwich policy;
- reviewer read permission cannot punch, submit leave or submit a
  regularization as another employee;
- `/employees/me` resolves exactly one active/enabled authorized linked employee
  without workforce-roster permission;
- close materializes all allocated month dates without a prior attendance GET,
  inactive allocation evidence neither blocks nor enters the snapshot, and
  reopen is limited to the current closed leaf.

Existing F01 coverage remains active, including real signed-JWT decoder tests
and catalog tenant-boundary tests.

Command and result:

```text
mvn -B -f backend/pom.xml verify
WorkforceAttendanceIT: 20 tests
ApiTenantSecurityIT: 11 tests
JwtDecoderIT: 3 tests
Tests run: 34, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Explicit remaining gates

- tenant-specific greytHR credential/capability certification, read-only
  discovery, mapping, sync/reconciliation, outage and cutover tests;
- regularization approval/dual-control actions rather than submission only;
- break-pair, overnight-shift and 16-hour warning cases;
- leave cancellation/release and full leave-policy approval workflow;
- exhaustive multi-employee roster completeness/policy checks before close;
- full-stack browser/provider-boundary tests. Seven F02 intercepted
  browser-contract cases are present but do not satisfy this gate.

No provider call is simulated or claimed by the current suite.

Final recorded frontend/browser-contract results:

```text
npm run test: 26 passed
npm run e2e: 18 passed
```

The E2E result is intercepted browser-contract evidence, not full-stack
provider/Java/PostgreSQL evidence.

See [TEST_CASES.md](TEST_CASES.md), [TEST_REVIEW.md](TEST_REVIEW.md) and
[FIXES.md](FIXES.md).

## Shift/roster focused coverage — 2026-07-29

`WorkforceAdministrationIT` proves the initial missing-shift blocker, overnight
policy publication, effective assignment, full 30-day readiness, immutable
snapshot creation, checksum presence and idempotent same-content finalization.
`WorkforceAttendanceIT` remains the focused regression for split sessions,
break deduction, day calculation and close/reopen lineage. `E2E-F02-008`
automates manager publication, assignment, readiness review and finalization.
