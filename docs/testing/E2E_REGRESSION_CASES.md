# End-to-End Regression Case Catalog

This is the durable, extensible catalog for cross-feature regression behavior.
An automated test title must contain its catalog ID. “Browser contract E2E”
means a real Chromium UI with intercepted deterministic API responses; it does
not mean the Java/PostgreSQL stack or an identity provider was exercised.

## Automated browser-contract cases

| ID | Feature | Data/setup | Steps | Expected outcome | Automation | Status |
|---|---|---|---|---|---|---|
| E2E-F00-001 | F00 shell | Demo profile; legacy flags on | Open `/` | Demo banner states persona changes never grant permission; enabled navigation and role presentation are visible | `e2e/demo.spec.ts` | Passing |
| E2E-F00-002 | F00 failure recovery | Demo profile | Open an unknown route | Safe 404 appears with a dashboard recovery link | `e2e/demo.spec.ts` | Passing |
| E2E-F01-001 | F01 legacy requirements | Legacy fixture with two records | Open `/requirements`, search, inspect available controls and requests | Results filter correctly; compatibility warning appears; no mutation control or non-read request occurs | `e2e/demo.spec.ts` | Passing |
| E2E-F01-002 | F01 approvals | Pending and `deemed_approved` fixtures | Open `/approvals` | Pending record is read-only; deemed value displays “explicit review required”; no approval/rejection action exists | `e2e/demo.spec.ts` | Passing |
| E2E-F01-003 | F01 UAT | In-progress and `deemed_signed_off` fixtures | Open `/uat` | Unverified state is placed in “Needs Explicit Review” with the safe legacy label | `e2e/demo.spec.ts` | Passing |
| E2E-F01-004 | F01 legacy route compatibility | Complete safe fixture set | Visit dashboard, engagements, scope and invoices | Every enabled major screen renders its primary heading and fixture evidence | `e2e/demo.spec.ts` | Passing |
| E2E-F01-005 | F01 accessibility smoke | Requirements route | Inspect title, landmarks, heading, named controls and images | One main landmark, page title/H1, named navigation/sidebar/search, and no image missing `alt` | `e2e/demo.spec.ts` | Passing |
| E2E-F01-006 | F01 login gate | Non-demo profile; `/me` returns 401; no login path | Open `/login` | The deployment blocker is visible and SSO is disabled | `e2e/auth-disabled.spec.ts` | Passing |
| E2E-F01-007 | F01 protected deep link | Same no-BFF profile | Open `/requirements?filter=pending` | User is routed to login with a same-origin `/requirements` return target; SSO remains disabled | `e2e/auth-disabled.spec.ts` | Passing |
| E2E-F01-008 | F01 safe redirect handoff | Test-BFF profile; `/me` returns 401 | Open login with `/requirements`, continue | Browser navigates only to same-origin `/test-bff/login` with the safe return target | `e2e/redirect-safety.spec.ts` | Passing |
| E2E-F01-009 | F01 open-redirect defense | Test-BFF profile; external `returnTo` | Continue from login | External target is replaced with `/`; navigation stays on the application origin | `e2e/redirect-safety.spec.ts` | Passing |
| E2E-F02-001 | F02 employee directory/profile | Workforce-enabled demo; employee, detail, allocation and leave fixtures | Open directory, inspect columns, open employee profile | Effective employee and 60% project allocation render; no salary, payroll, rate, CTC or markup field is exposed | `e2e/workforce.spec.ts` | Passing |
| E2E-F02-002 | F02 open attendance session | `/employees/me` resolves self; `OPEN_SESSION` attendance day | Open Today, inspect actions, submit checkout | Duration remains unresolved; check-in is disabled, checkout is enabled, and POST contains `CHECK_OUT` for the server-resolved employee | `e2e/workforce.spec.ts` | Passing |
| E2E-F02-003 | F02 missing checkout | `/employees/me` resolves self; backend-shaped `MISSING_CHECKOUT_EXCEPTION` day with zero recorded minutes | Open Today | Explicit resolution warning and “Unresolved” worked time appear; UI says no checkout/duration was synthesized | `e2e/workforce.spec.ts` | Passing |
| E2E-F02-004 | F02 leave | `/employees/me` resolves self; one paid leave balance; intercepted API returns approved paid/LWP split | Try 1.5 units for one day, then correct the inclusive range to two working days | Visible validation rejects the one-day request with no POST; corrected POST carries `2026-08-03` through `2026-08-04`, type/reason/1.5 units; approved history renders API-returned paid 1/LWP 0.5 | `e2e/workforce.spec.ts` | Passing |
| E2E-F02-005 | F02 regularization | `/employees/me` resolves self; prior exception day and empty request history | Submit empty form, then complete exception/reason/outcome/evidence declaration | Required evidence errors appear first; valid POST is attributable and submitted history preserves narrative/outcome | `e2e/workforce.spec.ts` | Passing |
| E2E-F02-006 | F02 month status | Closed v1 and reopened v2 fixtures | Open Month status | Newest snapshot appears first, supersession is visible, and no close/reopen/snapshot mutation control or request exists | `e2e/workforce.spec.ts` | Passing |
| E2E-F02-007 | F02 self-service privacy | Intercept and record every API request | Visit Today, Leave and Regularizations | Each route resolves `/workforce/employees/me`; no organization/employee selector, organization query or peer roster/list request occurs | `e2e/workforce.spec.ts` | Passing |

All cases also apply the shared console/page-error quality gate in
`e2e/fixtures/quality-gates.ts`.

## Required backend integration invariants

These IDs reserve Spring HTTP/Testcontainers PostgreSQL evidence identified by
backend review. Intercepted Playwright results do not satisfy them.

| ID | Feature | Required invariant | Required lane | Status |
|---|---|---|---|---|
| IT-F02-001 | F02 attendance reads | `GET /attendance/days` is read-only: repeated GET/prefetch/cache retry changes no attendance-day, calculation-version, exception or audit row | Spring HTTP + Testcontainers PostgreSQL | Reserved — required |
| IT-F02-002 | F02 multi-day leave | Exact per-day paid/LWP allocations sum once to the request across working, non-working, holiday and fractional days | Spring HTTP + Testcontainers PostgreSQL | Reserved — required |
| IT-F02-003 | F02 snapshot completeness | Close materializes every required employee-day without relying on a prior GET and rejects incomplete/blocked month state | Spring HTTP + Testcontainers PostgreSQL | Reserved — required |
| IT-F02-004 | F02 command authorization | Reviewer/query permission cannot punch, submit leave or regularize for another employee; self commands bind to the authenticated employee | Spring Security HTTP + Testcontainers PostgreSQL | Reserved — required |

## Required future regression cases

These IDs reserve the intended cross-feature coverage. Change `Planned` to
`Automated` only when an executable test exists and has passed in the named
lane.

| ID | Feature | Scenario and expected outcome | Required lane | Status |
|---|---|---|---|---|
| E2E-F01-010 | F01 identity | Real provider login/logout establishes and clears a secure BFF session without exposing tokens to browser storage | Full-stack system | Blocked |
| E2E-F01-011 | F01 tenant boundary | Provisioned user selects an authorized active organization/engagement/month; cross-tenant deep links fail uniformly | Full-stack system | Blocked |
| E2E-F02-101 | F02 workforce | Authorized admin creates/updates effective-dated employee, allocation and calendar data without payroll/rate fields | Full-stack system | Planned |
| E2E-F02-102 | F02 attendance | Real UI/API/PostgreSQL check-in/check-out is idempotent, attributable and isolated to authorized roles | Full-stack system | Planned |
| E2E-F02-103 | F02 leave | Partial/excess leave follows configured balance/LWP policy and writes the immutable PostgreSQL ledger | Full-stack system | Planned |
| E2E-F02-104 | F02 month close | Real close/reopen creates immutable lineage and never rewrites the prior closed database version | Full-stack system | Planned |
| E2E-F03-001 | F03 planning | Complete monthly plan proceeds through explicit review/freeze; incomplete plan is rejected | Full-stack system | Planned |
| E2E-F03-002 | F03 Linear | Signed webhook deduplicates and reconciles; invalid/replayed webhook is rejected | Full-stack system | Planned |
| E2E-F03-003 | F03 acceptance boundary | Linear `Done` never certifies or accepts delivery | Full-stack system | Planned |
| E2E-F04-001 | F04 certification | Eligible client actor records an attributable item-level certification against an exact version | Full-stack system | Planned |
| E2E-F04-002 | F04 confirmation | Expiring single-use confirmation works once; silence, receipts and ambiguous replies never approve | Full-stack system | Planned |
| E2E-F04-003 | F04 month workflow | Readiness, close, exception and reopen preserve snapshot/version lineage | Full-stack system | Planned |
| E2E-F05-001 | F05 evidence | Regenerating a closed-month package produces the same canonical manifest and checksums | Full-stack system | Planned |
| E2E-F05-002 | F05 invoices | Procurement exception/payment history requires explicit authorized actions and preserves prior versions | Full-stack system | Planned |
| E2E-F05-003 | F05 privacy | UI, API and exports contain no salary, CTC, markup, employee rate or derived payroll data | Full-stack system | Planned |
| E2E-F06-001 | F06 migration | Dry-run/import/retry is idempotent and produces row-level provenance and reconciliation evidence | Full-stack system | Planned |
| E2E-F06-002 | F06 migration safety | Invalid template/checksum/formula content is quarantined with actionable errors | Full-stack system | Planned |
| E2E-F07-001 | F07 regression | All earlier feature cases pass in the release environment with real services | Full-stack system | Planned |
| E2E-F07-002 | F07 operations | Backup/restore and application rollback meet recorded integrity and recovery criteria | Full-stack system | Planned |
| E2E-F07-003 | F07 quality | Supported personas pass WCAG, responsive, concurrency and security release gates | Full-stack system | Planned |

## Result history

| Date | Commit/worktree | Command | Result | Notes |
|---|---|---|---|---|
| 2026-07-26 | `221a7c9` plus regression-harness worktree | `npm run e2e` | 11 passed, 0 failed | Chromium browser-contract lane; API intercepted |
| 2026-07-26 | `221a7c9` plus integrated F02 worktree | `npm run e2e` | 17 passed, 0 failed | F00–F02 Chromium browser-contract lane; API intercepted; six F02 journeys |
| 2026-07-26 | Self-service frontend-fix worktree before harness update | `npx playwright test --project=workforce-chromium` | 2 passed, 4 failed | Preserved observed failure: F02-002–005 lacked `/employees/me` fixture support and still expected the removed Employee selector |
| 2026-07-26 | Self-service harness update | `npx playwright test --project=workforce-chromium` | 7 passed, 0 failed | Self routes use `/employees/me`; new F02-007 proves no organization/peer discovery |
| 2026-07-26 | Self-service harness update | `npm run e2e` | 18 passed, 0 failed | Complete F00–F02 browser-contract suite; intercepted API only |
| 2026-07-26 | F02-004 leave-contract alignment | `npx playwright test --project=workforce-chromium` | 7 passed, 0 failed | One-day 1.5-unit request rejected without POST; corrected inclusive two-working-day request passed |
| 2026-07-26 | F02-004 leave-contract alignment | `npm run e2e` | 18 passed, 0 failed | Complete F00–F02 browser-contract suite after leave range validation coverage |

Append a row for every feature-completion regression run. Never overwrite a
failure: record the later passing run as a new row and link the issue/fix in
[FEATURE_STATUS.md](../FEATURE_STATUS.md).
