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
| E2E-F03-001 | F03 plan creation | Delivery-enabled demo; deterministic organization/engagement/month/list fixtures | Open the plan list, follow Create plan, complete all required plan/deliverable/criterion/assignment/recipient fields and create | List renders version/freeze evidence; POST uses the exact nested Java contract and navigates to the created draft | `e2e/delivery.spec.ts` | Passing — browser contract |
| E2E-F03-002 | F03 completeness | Empty new-plan form; mutation recorder | Submit the incomplete form | Stable completeness blockers render and no create mutation occurs | `e2e/delivery.spec.ts` | Passing — browser contract |
| E2E-F03-003 | F03 review/freeze | Complete draft with exact checksum and resolved recipient preview | Inspect checksum/recipients, submit the exact version, add decision comment and approve | Exact submit and approval requests occur; returned frozen plan is immutable, comment/checksum evidence remains visible, and send/read/silence is not approval | `e2e/delivery.spec.ts` | Passing — browser contract |
| E2E-F03-004 | F03 revision | Frozen approved plan | Inspect disabled actions, enter revision reason/impact and clone | Frozen version exposes no edit/link/approval actions; new draft shows prior-version, reason and impact lineage | `e2e/delivery.spec.ts` | Passing — browser contract |
| E2E-F03-005 | F03 Linear link | Draft with stable deliverable-version, connection and recorded issue UUIDs | Attach a resolved Linear issue without an optional rationale | POST carries exactly `deliverableVersionId`, `connectionId` and `issueUuid`; no browser-supplied identifier, URL, title or provider-state metadata is sent; refreshed plan shows the server-resolved link | `e2e/delivery.spec.ts` | Passing — browser contract |
| E2E-F03-006 | F03 Linear evidence | Frozen plan with stale `Done` issue plus inaccessible issue and `FETCH_FAILED` plan snapshot | Inspect current and plan-time evidence cards | Provider/normalized/current/plan-time states stay distinct; stale and inaccessible/fetch-failed evidence is explicit and last-known data remains visible | `e2e/delivery.spec.ts` | Passing — browser contract |
| E2E-F03-007 | F03 integration health | Separate `NOT_CONFIGURED`/`EXTERNALLY_BLOCKED` and `ACTION_REQUIRED`/`CONFIGURED` health fixtures with queue/dead-letter counts | Open health, observe request count, reload into action-required scenario | Closed provider-registration states, sanitized errors and queue/dead-letter counts render; no secret value, automatic polling, replay, process or refresh control is exposed | `e2e/delivery.spec.ts` | Passing — browser contract |
| E2E-XF-001 | F01/F03 decision boundary | Frozen plan; current Linear state `Done`/normalized `COMPLETED`; `SENT` commitment preview | Inspect execution, message and available actions | Completed/Done is execution evidence only; sent/read is not approval; no acceptance, certification or confirmation action/inference exists | `e2e/delivery.spec.ts` | Passing — browser contract |

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
| IT-F03-001 | F03 immutable review | Canonical checksum submission, scoped approval/quorum, atomic freeze, baseline/recipient snapshot and revision-by-clone preserve the original version; direct baseline mutation is rejected | Spring HTTP + Testcontainers PostgreSQL (`DeliveryLinearIT`, `DeliveryApprovalConcurrencyIT`) | Passing in recorded 49-test backend lane |
| IT-F03-002 | F03 signed webhook | Exact raw-body HMAC signature, delivery/workspace/dual-timestamp validation, durable queue-before-success and delivery/event deduplication hold; invalid/replayed input mutates nothing | Spring HTTP + Testcontainers PostgreSQL (`DeliveryLinearIT`) | Passing in recorded 49-test backend lane |
| IT-F03-003 | F03 snapshot/decision boundary | Webhook processing appends immutable history, updates current execution projection idempotently and never rewrites plan-time evidence or turns `Done` into approval/certification | Spring HTTP + Testcontainers PostgreSQL (`DeliveryLinearIT`) | Passing in recorded 49-test backend lane |
| IT-F03-004 | F03 tenant/security | Wrong-tenant/object reads and unauthorized replay are non-disclosing; OpenAPI contains delivery routes without secret references or configured test secret values | Spring Security HTTP + Testcontainers PostgreSQL (`DeliveryLinearIT`) | Passing in recorded 49-test backend lane |

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
| E2E-F03-101 | F03 planning | Real browser/BFF/Java/PostgreSQL complete monthly plan proceeds through explicit review/freeze; incomplete plan is rejected | Full-stack system | Blocked by identity/controlled-environment gate |
| E2E-F03-102 | F03 Linear | Tenant-authorized Linear OAuth/GraphQL and signed webhook deduplicate/reconcile; invalid/replayed webhook is rejected | Full-stack provider system | Blocked by tenant provider approval/credentials |
| E2E-F03-103 | F03 acceptance boundary | Real provider `Done`, real commitment delivery and frozen PostgreSQL evidence never certify or accept delivery | Full-stack provider system | Blocked by identity/provider/controlled-environment gates |
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
| 2026-07-26 | Initial F03 browser-contract harness | `npx playwright test --project=delivery-chromium` | 2 passed, 6 failed | Preserved harness failures: strict/incorrect locators and exact validation-copy mismatch; product UI rendered the expected evidence |
| 2026-07-26 | F03 selector/fixture alignment | `npx playwright test --project=delivery-chromium` | 5 passed, 3 failed | Remaining harness failures: created-response criterion lacked a stable fixture key, recipient/checksum locators were ambiguous and status text casing differed |
| 2026-07-26 | F03 fixture-key/status alignment | `npx playwright test --project=delivery-chromium` | 6 passed, 2 failed | Remaining harness-only strictness: approval comment matched both evidence and disabled input; inaccessible badge rendered presentation-case text |
| 2026-07-26 | Final F03 browser-contract harness | `npx playwright test --project=delivery-chromium` | 8 passed, 0 failed | Deterministic intercepted delivery/Linear APIs; no live provider, Java or PostgreSQL exercised |
| 2026-07-26 | Final F03 browser-contract harness | `npm run e2e` | 26 passed, 0 failed | Complete F00–F03 Chromium browser-contract suite; 8 F03/cross-feature cases and all prior 18 cases pass |
| 2026-07-26 | Hardened F03 frontend with pre-sync E2E fixture | `npx playwright test --project=delivery-chromium` | 1 passed, 7 failed | Preserved intercepted-harness failure: stale commitment values caused plan-detail render errors; stale snapshot/provider-registration values and removed browser metadata fields broke F03-005–007 expectations |
| 2026-07-26 | Hardened F03 E2E contract sync | `npx playwright test --project=delivery-chromium` | 8 passed, 0 failed | Intercepted fixture aligned to provider-neutral link, commitment, link, snapshot and provider-registration contracts; no Java/PostgreSQL/live Linear exercised |
| 2026-07-26 | Hardened F03 E2E contract sync | `npm run e2e` | 26 passed, 0 failed | Complete F00–F03 Chromium browser-contract suite; deterministic intercepted APIs only |
| 2026-07-26 | Hardened F03 E2E contract sync, post-format final state | `npx playwright test --project=delivery-chromium` | 8 passed, 0 failed | Definitive rerun against the final intercepted fixture/spec state after the last spec edit |
| 2026-07-26 | Hardened F03 E2E contract sync, post-format final state | `npm run e2e` | 26 passed, 0 failed | Definitive complete F00–F03 Chromium browser-contract rerun; deterministic intercepted APIs only |
| 2026-07-26 | Final reviewed post-V10 F03 worktree | `npm run regression` | 26 Playwright passed within the complete regression | The same run also passed 47 frontend and 49 Java/PostgreSQL tests; Playwright remained the explicitly intercepted browser-contract lane |

Append a row for every feature-completion regression run. Never overwrite a
failure: record the later passing run as a new row and link the issue/fix in
[FEATURE_STATUS.md](../FEATURE_STATUS.md).
