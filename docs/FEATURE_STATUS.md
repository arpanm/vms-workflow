# Feature Delivery Status

This file is the single detailed delivery ledger. Update it after every
codegen, review, fix and regression run. Task/test documents remain the detailed
specifications; this ledger records what is actually complete now.

The one-page queue of all unfinished local subfeatures, active findings,
pending tests and external gates is
[PENDING_WORK.md](PENDING_WORK.md).

**Last updated:** 2026-07-30 (V43 final integrated evidence reconciliation)
**Repository commit under test:** `c2d8dfb` plus the active integrated worktree
**Working-tree context:** The integrated worktree adds F02 employee/allocation
lifecycle completion, F03 editable delivery drafts, F04 governed artifact
upload/scan and withdrawal, F05 aggregate/concurrency hardening, F06
registry-driven validation, and F07 typed release evidence. Production Flyway
migrations now run through V43 (V42 is the repeated-reopen invariant and V43
is the durable asynchronous migration queue). The latest frontend gate passes
typecheck, 28 files/120 Vitest tests and the production build; the SDLC manifest
gate passes all eight feature manifests and preserves distinct Sol
codegen/Terra review models. Focused PostgreSQL evidence passes F02 24/24,
F04 10/10, F06 19/19 and all agent-owned F03/F05 concurrency cases. The
dynamic local launcher was browser-smoked end to end with authenticated seeded
data and now selects/propagates PostgreSQL, JWKS, Spring and Vite ports, using a
dedicated `vms_workflow_local` fixture database.
The final aggregate Maven attempt ran 74 unit plus 266 integration tests (340)
and preserved 2 failures plus 1 error; exact recovery selectors subsequently
passed Finance 1/1, Migration 1/1 and Capacity 2/2. The final browser aggregate
passed 287/292 and its exact recovery slice passed 5/5. Neither recovery is
represented as a clean aggregate rerun. Live providers, production
BFF/identity, legal/privacy decisions, production-like soak/DR/deployment and
human approval remain external and must not be represented as local release
acceptance.

## Overall status

### Final integrated evidence — 2026-07-30

| Lane | Result | Interpretation |
|---|---|---|
| Full Maven | 74 unit + 266 integration = 340 executed; **2 failures + 1 error** | Failed aggregate row is retained. |
| Maven exact recovery | Finance 1/1; Migration 1/1; Capacity 2/2 | Affected selectors pass; no clean 340/340 claim. |
| Full browser | **287/292 passed** | Failed aggregate row is retained. |
| Browser exact recovery | **5/5 passed** | Affected slice passes; no clean 292/292 claim. |
| Frontend | typecheck pass; lint 0 errors/13 warnings; Vitest 28 files, 120/120 in 804 ms; build 3,042 modules in 2.80 s | Warnings are 6 Fast Refresh and 7 existing hook-dependency warnings; build has only the >500 kB advisory. |
| System | F05 finance 4/4; F06 migration 6/6; F07 7/7 | Current ordered local-system evidence. |
| Harness/static | F07 self-test 9/9 (45.037 s); operations 15 runbooks/6 alerts; 43 migrations; rollout schema; SDLC 8 features; diff check | All pass with no findings; SDLC preserves Sol codegen/Terra review separation. |
| Release-schema wrapper | `listen EPERM 127.0.0.1`; escalated retry aborted after 467 s without output | Environmental wrapper limitation, not a product finding; wrapper is **not** claimed as passed, while its underlying gates pass. |

Remaining executable F07 work is F07-T057 (real 24-hour soak), F07-T066/T067
(current recovery-boundary and DR drill), F07-T070 (artifact/provenance
production), and the requested clean local commit. External production gates
remain `NO-GO / ACTION_REQUIRED`.

| Feature | State | Completed subparts | Pending subparts | Automated evidence | Current failures |
|---|---|---|---|---|---|
| F00 Foundation | Implemented locally; external gates open | Java/PostgreSQL decision, rollback tag/plan, environment rules, feature flags, SDLC harness, local validation, Playwright harness | Staging backup references, deployed health/auth smoke, rollback rehearsal | 2 Playwright cases passing as part of the 26-case browser run; existing frontend and backend validation | None in latest local browser-contract run |
| F01 Identity/core | Local product complete; external identity acceptance blocked | V34 administration, governed approval engine, F04 authoritative reopen bridge, immutable quorum/eligibility evidence, SoD/idempotency/database guards, OpenAPI and React flows | Real OIDC/BFF login/logout, approved provisioning and deployed acceptance remain external | **Focused PostgreSQL:** 45/45 | None in focused lanes |
| F02 Workforce/attendance | Local product complete; external provider gates open | V35/V37 administration plus employee create/effective edit/disable/archive and serialized allocation create/edit/end/split lifecycle; overnight/split-session and roster-bound close; manager/self UI | Real greytHR, production IdP and controlled staging/load acceptance | **Latest focused:** 24/24 including two-session concurrency | None in focused lanes |
| F03 Delivery/Linear | Local product complete; external provider gates open | V36/V38 plus V39 repeatable deliverables/criteria/dependencies/assignments, guarded editable drafts and cloned revisions; delegated approval/replay and bounded reconciliation operator UI | Live Linear/mail and controlled acceptance | Focused PostgreSQL and 25/25 delivery frontend tests pass | None in focused lanes |
| F04 Certification/confirmation | Local provider-neutral product complete; release acceptance blocked | V11–V13 plus V40 exact-version withdrawal and private governed multipart evidence storage/metadata/hash/scan transitions; cross-month operations UI | Live sender/mailbox/object storage/scanner, production grants/SSO/provider/F05 consumer and deployed acceptance | **Latest focused:** 10/10 PostgreSQL | None in focused lanes |
| F05 Evidence/invoice/reporting | Implemented locally; release acceptance blocked | Flyway V14–V16 finance schema, scoped APIs/OpenAPI, immutable exact invoice/package lineage, private storage/scan/render adapters, package/invoice/Procurement/payment/report/export workflows, React finance workspaces and evidence docs | Production provider/deployment/Procurement acceptance and production-like scale approval | **Fresh focused:** natural scanner-readiness 1/1; committed concurrency 2/2; accessibility 3/3; isolated Spring/Flyway/PostgreSQL system 4/4. Prior 154/154 backend, 88/88 frontend and 69/69 browser results remain historical. | No F05 failure in the fresh focused lanes; external performance/scale and G4 are ACTION_REQUIRED |
| F06 Historical migration | Local code complete; consolidated regression and production cutover blocked | V17–V20/V41/V43, retro outcome ledger, ordered month transitions, Procurement envelopes, row/conflict UI, source declarations, stable codes/correlation, durable async worker, 100k bound, tenant CSV/XLSX, OpenAPI/a11y and consumed-package correction routing | **Local:** consolidated final regression only. **External:** approved scanner/storage, controlled capacity window, source-owner sign-off, backup/restore and masked rehearsal | New focused code/tests are present; exact final counts belong to the root consolidated regression | No unresolved code issue recorded; verification and external cutover prerequisites remain |
| F07 Hardening/go-live | V41 integration/reconciliation in progress; production blocked | Historical V1–V40 traceability, least privilege, retention/legal hold, flags, telemetry, capacity and release/DR/supply-chain harness evidence is retained | Fresh F07 owner verification and all external production gates | Frontend **120/120**, typecheck and build are current static evidence. Do not treat historical Maven/browser/system/supply results as V41 proof until the F07 owner publishes exact results. | Current F07 agent result pending. Production provider/legal/identity/deployment/soak/DR/manual/UAT approval remains `ACTION_REQUIRED / NO-GO`. |

## Open issues and blockers

| ID | Feature | Severity/state | Owner/input needed | Detail |
|---|---|---|---|---|
| STATUS-BLOCK-001 | F01/F07 | Release blocker; external | Product/security/identity owner | Select and configure the OIDC provider, same-origin BFF flow, cookie/CSRF controls and logout behavior. |
| STATUS-BLOCK-002 | F01 | Release blocker; external | Product/security/operations | Approve a user invitation/provisioning/import process. Production migrations intentionally seed no users or assignments. |
| STATUS-BLOCK-003 | F00/F01 | Exit evidence blocker; external | Operations | Supply a controlled staging-like environment, backup destination and deployment references for tenant-isolation/rollback evidence. |
| STATUS-ISSUE-004 | F01 | Resolved locally in V21/V34 | Backend/database | Separate migration-owner/runtime/worker roles and guarded V34 runtime grants are implemented and PostgreSQL-tested. |
| STATUS-ISSUE-005 | F01 | Resolved locally in active worktree | Product/backend/frontend | Server-authorized organization/engagement/month scope and effective permission-aware navigation are implemented and browser-tested. |
| STATUS-ISSUE-006 | F01 | Resolved locally in active worktree | API/frontend | Runtime-validated administration clients plus executable OpenAPI schema/path assertions are implemented; production provider compatibility remains external. |
| STATUS-BLOCK-007 | All | Test-lane blocker; external | Identity/operations | Full-stack browser-to-BFF-to-Java-to-PostgreSQL E2E cannot run until the identity/provisioning and controlled-environment blockers are resolved. Current Playwright evidence is browser-contract E2E with mocked APIs. |
| STATUS-ISSUE-008 | F02 | Resolved for local vertical | Feature agents | Independent review, fixes, exact artifacts and the consolidated regression now pass. The broader feature remains partial because the provider/admin/full-stack items below are not implemented. |
| STATUS-ISSUE-009 | F02 | Resolved in active worktree | Frontend feature agent | The earlier TS2339 fixture-type failure was corrected and remains preserved in the regression history. |
| STATUS-ISSUE-010 | F02 | Resolved by V35/V37 | Product/backend/frontend | Policy/calendar, leave/regularization, break/overnight, import, shift and exact roster governance are implemented. Live greytHR remains external. |
| STATUS-ISSUE-011 | F02 | Open hardening | Backend/database | Application advisory locks serialize supported commands; direct database writers must use governed import paths and compatible coordination. V6 deliberately fails incompatible legacy leave backfill instead of silently misallocating units. |
| STATUS-ISSUE-012 | F03 | Resolved in browser-contract harness | E2E agent | Initial selector, validation-copy and fixture-key mismatches produced preserved 2/6, 5/3 and 6/2 pass/fail runs. A later hardened-contract check preserved a 1/7 pass/fail run from stale fixture enums and browser metadata fields. The synced fixture now passes 8/8 and the full suite passes 26/26. |
| STATUS-ISSUE-013 | F03 | Resolved product scope by V36/V38; test expansion open | Backend/frontend/QA | Dead-letter replay, revision comparison, delegated approval and scheduled bounded cursor reconciliation are implemented. Only exhaustive perimeter/failure-injection expansion remains local. |
| STATUS-BLOCK-014 | F03 | Release blocker; external | Tenant product/security/operations | Approve/configure Linear OAuth app/workspace/scopes/webhook secret and mail provider/sender/contact groups; run live OAuth/GraphQL/webhook and commitment-mail acceptance without exposing credentials. |
| STATUS-BLOCK-015 | F03 | Test-lane blocker; external | Identity/operations | Run real browser-to-BFF-to-Java-to-PostgreSQL F03 journeys in a controlled environment. Current Playwright results are deterministic intercepted browser-contract evidence only. |
| STATUS-ISSUE-016 | F04 | Open local P1 set | Backend/database | Resolve reviewed DB scope/immutability/SOD, request-expiry/quorum notification, evidence-policy, readiness-manifest, outbox/jobs, lifecycle/inbound/closure and durable F05 handoff findings in `CODE_ISSUES-BACKEND.md`. |
| STATUS-ISSUE-017 | F04 | Open local frontend set | Frontend/API | Resolve nested routing, retry-intent, dirty submit, criterion evidence, reviewer contract, exact-scope, timezone, form sync/accessibility/error-redaction and responsive findings in `CODE_ISSUES-FRONTEND.md`. |
| STATUS-ISSUE-018 | F04 | Expected red automation | Feature fix agents | Latest F04 automation preserves 15 backend assertion failures plus one NPE, one frontend unit failure and ten Playwright failures. All known harness defects were removed before classification; independent test review is in progress. |
| STATUS-BLOCK-019 | F04 | Release blocker; external | Tenant product/security/operations | Approve mail sender/provider, controlled mailbox/callback security, recipient/quorum/delegation/SLA/retention policy, SSO/OTP/step-up and sandbox/live acceptance. Provider-neutral local states must not be represented as live delivery. |
| STATUS-ISSUE-020 | F05 | Fresh focused closure evidence | Engineering/QA | Natural scanner-readiness 1/1, committed package concurrency 2/2, accessibility 3/3 and isolated system E2E 4/4 passed on 2026-07-30. The 154/154 backend, 88/88 frontend and 69/69 browser results are preserved historical results, not a new full-regression claim. |
| STATUS-BLOCK-021 | F05 | Release blocker; external | Product/security/operations/Procurement | Production object storage/scanner/renderer, production OIDC/BFF and database grants, retention/legal-hold approval, backup/restore infrastructure, Procurement package sign-off and AP/ERP acceptance are `ACTION_REQUIRED`. Local adapters and signed synthetic-JWT E2E do not close these gates. |
| STATUS-ISSUE-022 | F06 | Resolved local quality gate | Engineering/QA | Independent review corrections are closed by V19/V20 and service/DTO fixes. Focused 14-unit + 15-integration, full 172/172 backend, 90/90 frontend, 74/74 combined browser and 6/6 real local system lanes pass. |
| STATUS-BLOCK-023 | F06 | Release blocker; external | Security/operations/data owners | Select/configure the approved production scanner and object storage, approve real template mappings, and execute the masked controlled-environment rehearsal. The local fail-closed scanner and synthetic system lane do not close this gate. |
| STATUS-ISSUE-024 | F07 | Historical closure; V41 fresh result pending | Engineering/QA | `PEND-F07-001` through `PEND-F07-011` and the prior Maven/supply/review evidence remain recorded. Do not carry their pass status into V41 until the F07 owner supplies exact final commands and counts. |
| STATUS-ISSUE-025 | F07 | Resolved infrastructure cause | Engineering/QA | Prior PostgreSQL/Docker contention caused long waits. Runners use one serialized digest-pinned PostgreSQL 18 container, tmpfs storage, host TCP readiness and bounded service/overall deadlines; subsequent focused/system lanes complete. |
| STATUS-BLOCK-026 | F07 | Release blocker; external | Product/security/legal/operations/provider owners | Production OIDC, secrets, provider services, legal/privacy/retention approval, production-like capacity/soak, backup/restore, observability/on-call and named release approvals require external evidence. Local synthetic evidence cannot convert these gates to `PASS`. |
| STATUS-ISSUE-027 | F07 | Resolved in focused verification | Backend/database/operations | Worker isolation is implemented and covered by the green focused gate: API schedulers default off; non-web Flyway-off profiles enable one scheduler; certification/finance and migration use distinct NOLOGIN capabilities; migration source bytes require a live random lease; startup verifies the exact login/capability. |

Detailed F01 review dispositions are in
[F01 FIXES.md](features/01-identity-core/FIXES.md).
Detailed F02 findings and post-fix dispositions are in
[F02 FIXES.md](features/02-workforce-attendance/FIXES.md).

### F04 issue-status supersession

`STATUS-ISSUE-016` through `STATUS-ISSUE-018` above preserve the initial red
review history. They are resolved locally in the active worktree; detailed
mapping is in [F04 FIXES.md](features/04-certification-confirmation/FIXES.md).
`STATUS-BLOCK-019` remains open, together with storage, deployment-grant,
platform-hardening, and full-stack acceptance gates in
[F04 CODE_ISSUES.md](features/04-certification-confirmation/CODE_ISSUES.md).

## Regression ledger

| Date/time | Commit/worktree | Command | Result | Failing tests/issues |
|---|---|---|---|---|
| 2026-07-26 09:14 IST | `221a7c9` plus Playwright harness | `npm run e2e` | 11 passed, 0 failed | None; browser-contract lane only |
| 2026-07-26 09:17 IST | Active F02 worktree | `npm run typecheck` | Failed | TS2339 in `src/features/workforce/presentation.test.ts:58`; `netMinutes` missing from inferred fixture type |
| 2026-07-26 09:19 IST | Active F02 worktree | `npm run typecheck` and `npm run test` | Passed; 21 unit tests | Prior TS2339 resolved; Playwright is explicitly excluded from Vitest discovery |
| 2026-07-26 09:28 IST | Active integrated F02 worktree | `npm run e2e` | 17 passed, 0 failed | F00–F02 Chromium browser-contract lane; deterministic intercepted APIs, not provider/full-stack E2E |
| 2026-07-26 09:47 IST | Self-service frontend-fix worktree before harness update | `npx playwright test --project=workforce-chromium` | 2 passed, 4 failed | Preserved observed regression: F02-002–005 received fixture 404 for `/workforce/employees/me` and retained obsolete Employee-selector assumptions |
| 2026-07-26 09:49 IST | Self-service harness update | `npx playwright test --project=workforce-chromium` | 7 passed, 0 failed | Self-service fixture/expectations updated; F02-007 proves no organization or peer-roster discovery |
| 2026-07-26 09:50 IST | Self-service harness update | `npm run e2e` | 18 passed, 0 failed | Complete F00–F02 Chromium browser-contract lane; deterministic intercepted APIs, not provider/full-stack E2E |
| 2026-07-26 10:02 IST | Reviewed F02 worktree before residual contract fixes | `npm run regression` | Passed: 25 frontend, 33 backend, 18 Playwright | Post-fix review then found a masked one-day/1.5-unit leave contract mismatch, nullable transient-ID typing gap and inactive-allocation snapshot edge; see F02 FIXES |
| 2026-07-26 10:07 IST | F02 leave-contract alignment | `npx playwright test --project=workforce-chromium` and `npm run e2e` | 7 F02 and 18 total passed | One-day 1.5-unit request rejected without POST; corrected two-day request passed |
| 2026-07-26 10:09 IST | Final reviewed F02 worktree | `npm run regression` | Passed: 26 frontend, 34 backend, 18 Playwright | Typecheck/build/Maven/PostgreSQL/browser-contract lanes all green; full-stack provider lane remains blocked |
| 2026-07-26 10:09 IST | Final reviewed F02 worktree | `npm run lint` and `git diff --check` | Passed | ESLint: 0 errors and 6 existing Fast Refresh warnings; diff whitespace check clean |
| 2026-07-26 10:55 IST | Initial F03 browser-contract harness | `npx playwright test --project=delivery-chromium` | 2 passed, 6 failed | Harness locator/copy mismatches; preserved as `STATUS-ISSUE-012`, no backend/provider conclusion |
| 2026-07-26 11:00 IST | F03 selector/fixture alignment | `npx playwright test --project=delivery-chromium` | 5 passed, 3 failed | Stable fixture key and strict-selector/case follow-ups remained |
| 2026-07-26 11:03 IST | F03 fixture-key/status alignment | `npx playwright test --project=delivery-chromium` | 6 passed, 2 failed | Approval-comment and inaccessible-status strict locators remained |
| 2026-07-26 11:05 IST | Final F03 browser-contract harness | `npx playwright test --project=delivery-chromium` | 8 passed, 0 failed | Intercepted delivery/Linear APIs; browser UI/contract only, not Java/PostgreSQL/live Linear |
| 2026-07-26 11:06 IST | Final integrated F03 browser-contract worktree | `npm run e2e` | 26 passed, 0 failed | Complete F00–F03 Playwright matrix; 8 F03/cross-feature and all prior 18 cases pass |
| 2026-07-26 11:24 IST | Hardened F03 frontend with pre-sync E2E fixture | `npx playwright test --project=delivery-chromium` | 1 passed, 7 failed | Stale `NOT_SENT`/`ARCHIVED` commitments caused plan-detail render errors; stale link/snapshot/provider-registration values and removed browser metadata fields also broke intercepted-harness expectations |
| 2026-07-26 11:24 IST | Hardened F03 E2E contract sync | `npx playwright test --project=delivery-chromium` | 8 passed, 0 failed | Provider-neutral intercepted fixture/spec aligned; no Java/PostgreSQL/live Linear exercised |
| 2026-07-26 11:24 IST | Hardened F03 E2E contract sync | `npm run e2e` | 26 passed, 0 failed | Complete F00–F03 Chromium browser-contract matrix; deterministic intercepted APIs only |
| 2026-07-26 11:24 IST | Hardened F03 E2E contract sync | `npm run typecheck` and `npm run lint` | Passed | TypeScript emitted no errors; ESLint emitted 0 errors and 6 existing Fast Refresh warnings |
| 2026-07-26 11:28 IST | Hardened F03 E2E contract sync, post-format final state | `npx playwright test --project=delivery-chromium` | 8 passed, 0 failed | Definitive rerun after the last spec edit; intercepted API only, not Java/PostgreSQL/live Linear |
| 2026-07-26 11:28 IST | Hardened F03 E2E contract sync, post-format final state | `npm run e2e` | 26 passed, 0 failed | Definitive complete F00–F03 Chromium browser-contract rerun; deterministic intercepted APIs only |
| 2026-07-26 11:28 IST | Hardened F03 E2E contract sync, post-format final state | `npm run typecheck` and `npm run lint` | Passed | TypeScript emitted no errors; ESLint emitted 0 errors and 6 existing Fast Refresh warnings |
| 2026-07-26 12:00 IST | Final reviewed post-V10 F03 worktree | `npm run regression` | Passed: 47 frontend, 49 backend, 26 Playwright | Typecheck, unit, production build, Flyway V1–V10 on PostgreSQL 18, all Java integration tests and the complete intercepted Chromium matrix passed |
| 2026-07-26 13:14 IST | Initial reviewed F04 product + backend automation | `mvn -B -f backend/pom.xml verify` | 79 run: 63 passed, 15 failed, 1 error | All 49 legacy cases pass; 30 F04 cases produce 14 passes, 15 mapped product assertion failures and one product NPE |
| 2026-07-26 13:14 IST | Initial reviewed F04 frontend automation | `npm run test` | 57 passed, 1 failed | `F04-UNIT-API-004` preserves retry-intent/idempotency defect `F04-FE-004` |
| 2026-07-26 13:14 IST | Initial reviewed F04 browser automation | `npm run e2e` | 40 passed, 10 failed | All 26 F00–F03 and 14 F04 cases pass; ten F04 product failures map to FE-001/005–012 and responsive/contract gaps |
| 2026-07-26 19:35 IST | Root-verified F04 consolidated remediation worktree | `npm run regression` | Passed: 64 frontend, 107 backend, 59 Playwright | Typecheck/build, Flyway V1–V12 on PostgreSQL 18, all Java integration tests, and complete intercepted Chromium matrix passed; not provider/full-stack evidence |
| 2026-07-26 20:22 IST | Root-verified F04 V13 final-P1 worktree | `npm run regression` | Passed: 64 frontend, 111 backend, 59 Playwright | Typecheck/build, Flyway V1–V13 on PostgreSQL 18, all 109 Java integration + 2 unit tests, and complete intercepted Chromium matrix passed |
| 2026-07-27 | Active F05 worktree | Finance-focused Maven Failsafe integration selection (`Finance*IT`) | **Passed: 34/34** | Spring Security, Flyway V14–V16 and ephemeral PostgreSQL finance integration evidence. This is a feature-focused lane, not full `mvn verify`/repository regression evidence. |
| 2026-07-27 | Active F05 worktree | `npm run e2e:finance:system` | **Passed: 3/3** | Isolated Vite → Spring Security/API → Flyway → PostgreSQL 18 path with local JWKS/signed test JWTs. Covers vendor/package/readiness/submit, Procurement/AP/restricted export authorization, and expiry/revoke/cross-scope denial; not a deployed BFF/OIDC/provider acceptance. |
| 2026-07-27 | Active F05 worktree | `mvn -B -f backend/pom.xml verify` | **Passed: 154/154** | 11 unit and 143 Spring/Testcontainers PostgreSQL integration tests, including Flyway V14–V16 finance coverage. |
| 2026-07-27 | Active F05 worktree | `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build` | **Passed** | TypeScript and production build pass; ESLint has 0 errors and 6 inherited Fast Refresh warnings; Vitest is 88/88. |
| 2026-07-27 | Active F05 worktree | `npm run e2e` | **Passed: 69/69** | Full combined intercepted Chromium regression, including F00–F05. This is browser-contract evidence, not provider/deployment acceptance. |
| 2026-07-30 | V41 F05 evidence follow-up | `mvn -B -f backend/pom.xml -Dtest=FinanceWorkflowIT#quarantinePersistsExactBytesAndExceptionCreatesNewReadinessLineage test` | **Passed: 1/1** | Quarantined EICAR artifact produces the persisted real `INVOICE_DOCUMENT` readiness blocker; exact exception SOD/expiry/cross-tenant checks remain in the same workflow. |
| 2026-07-30 | V41 F05 evidence follow-up | `mvn -B -f backend/pom.xml -Dtest=FinanceCommittedConcurrencyIT test` | **Passed: 2/2** | Independent callers race package generation: one canonical committed package/event/outbox effect and one safe conflict. |
| 2026-07-30 | V41 F05 evidence follow-up | `npx playwright test e2e/finance-accessibility.spec.ts --project=f05-finance-chromium` | **Passed: 3/3** | Axe serious/critical gate, keyboard entry and tablet no-overflow are intercepted-browser accessibility evidence. |
| 2026-07-30 | V41 F05 evidence follow-up | `npm run e2e:finance:system` | **Passed: 4/4** | Isolated Vite/Spring Security/Flyway/PostgreSQL/JWKS lane covers vendor flow, Procurement/AP/report authorization, non-disclosure attacks, and expired/revoked/cross-scope denial. |
| 2026-07-28 | Pre-final-review F06 worktree | `mvn -B -f backend/pom.xml verify` | **Passed: 172/172** | 14 unit and 158 integration tests against Flyway V1–V19/PostgreSQL; later Terra review raised V20 policy/provenance edges, preserved below. |
| 2026-07-28 | Initial F06 V20 review patch | Focused migration Maven verify | **Failed: 13 passed, 2 failed** | Test exposed the outer-test transaction observation problem and an ineffective arbitrary-flag target; root replaced it with a dedicated real transaction-boundary test and immutable-ledger guard assertion. |
| 2026-07-28 | Final reviewed F06 worktree | Focused migration Maven verify | **Passed: 14 unit + 15 integration** | Includes all 14 domain adapters, atomic late-duplicate rollback, immutable policy, exact compensation-action authorization and decision validation. |
| 2026-07-28 | Final reviewed F06 worktree | `mvn -B -f backend/pom.xml verify` | **Passed: 172/172** | 14 unit and 158 Spring/Testcontainers integration tests with Flyway V1–V20; zero failures. |
| 2026-07-28 | Final reviewed F06 worktree | `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build` | **Passed** | Typecheck/build pass; lint has 0 errors and 6 inherited Fast Refresh warnings; Vitest is 90/90. |
| 2026-07-28 | Final reviewed F06 worktree | `npm run e2e` | **Passed: 74/74** | Complete F00–F06 intercepted Chromium regression, including five F06 Migration Center flows. |
| 2026-07-28 | Final reviewed F06 worktree | `npm run e2e:migration:system` | **Passed: 6/6** | Real local Vite/Spring Security/Flyway V1–V20/PostgreSQL/JWKS journeys; not production scanner/storage/OIDC/source-owner acceptance. |
| 2026-07-30 | F06 completion-audit remediation | `mvn -B -f backend/pom.xml -Dit.test=MigrationWorkflowIT verify` | **Passed: 73 unit + 15 focused integration** | Flyway V1–V41; includes immutable current-time retro outcome, Procurement outbox and confirmed month transition. |
| 2026-07-30 | F06 completion-audit remediation | `npm run typecheck`, `npm run sdlc:check`, `git diff --check` | **Passed** | New migration row/inbox/readiness client contracts compile; harness and whitespace gates pass. |
| 2026-07-28 | Active F07 worktree | `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build`, `node scripts/f07/load-harness-self-test.mjs` | **Passed:** typecheck/build; 92/92 Vitest; 2/2 harness tests | ESLint has 0 errors and 6 inherited Fast Refresh warnings. |
| 2026-07-28 | Active F07 worktree, pre-readiness remediation | Focused F07 retention/capacity/bootstrap PostgreSQL runs | **Infrastructure failure/hang preserved** | PostgreSQL 18 became ready after Testcontainers' fixed log timeout; a later host-port check and Docker `exec` blocked indefinitely. No product assertion executed. Digest-pinned bounded log readiness and child-process timeouts are the corrective actions. |
| 2026-07-29 | F07 pre-final focused worktree | Focused backend unit/integration gate | **Passed: 73 unit + 45 integration** | Zero failures/errors/skips; exact failed-case rerun also passed 73 + 3. Production Flyway chain is V1–V33. |
| 2026-07-29 | F07 capacity first run | Capacity gate | **Failed target: dashboard 3,202ms > 2,500ms** | Preserved performance failure; query/index remediation followed. |
| 2026-07-29 | F07 final capacity worktree | Capacity gate | **Passed: 73 unit + 2 capacity** | Dashboard 101ms; check-in p95 404ms; replay p95 69ms; 10k search p95 2ms; 300k report p95 9ms. Local synthetic evidence only. |
| 2026-07-29 | F07 system first complete debugging cycle | Ordered F07 system suite | **Failures preserved** | Exposed authority, timestamp/header fixture gaps plus finance source lineage, confirmation predecessor, invalidation handoff and Linear attempt-observability product defects; all mapped in F07 fixes. |
| 2026-07-29 | F07 final system worktree | Ordered F07 system suite | **Passed: 7/7** | E2E-01/02/03/04/05/07/10 pass through local Vite/Spring Security/Flyway V1–V33/PostgreSQL. |
| 2026-07-29 | F07 cross-feature system worktree | `npm run e2e:finance:system` and `npm run e2e:migration:system` | **Passed: 4/4 and 6/6** | Covers E2E-06/E2E-09 and E2E-08 locally; no production provider/identity/source-owner acceptance. |
| 2026-07-29 | F07 first complete browser matrix | `npm run e2e` | **268 passed, 6 failed** | Migration upload race, four UTF-8 multipart instrumentation assertions and one Firefox `_page` creation error preserved. |
| 2026-07-29 | F07 exact browser failure slice | Playwright exact failed slice | **Passed: 7/7** | Bounded migration polling and exact `FormData File.name` capture verified; Firefox error did not reproduce. |
| 2026-07-29 | F07 final complete browser worktree | `npm run e2e` | **Passed: 274/274** | Zero failed/skipped/flaky across all configured desktop, auth, feature, Firefox, WebKit, Android and iOS projects. |
| 2026-07-29 | F07 final worktree | Complete Maven verification | **73 unit pass; 215/217 integration pass (2 failed)** | Both failures are in `DeliveryCommitmentOperationsWorkerIT`: configured replay-safe and retry cases observed two provider effects instead of exactly one. Run finished in 39:23 under the corrected 3,600s watchdog; two ~16–17m host thread-starvation/clock-leap pauses are preserved. |
| 2026-07-29 15:49:59 IST | F07 dedicated-worker-database worktree | Complete Maven verification R3 | **Passed: 73 unit + 217 integration (290/290)** | Zero failures/errors/skips; BUILD SUCCESS in 03:21. The dedicated `vms_workflow_delivery_commitment_worker` test database removes R2 cross-suite state. |
| 2026-07-29 | F07 final frontend worktree | `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build`, `git diff --check` | **Passed: 24 files/92 Vitest tests** | Typecheck/build/diff pass; lint 0 errors/6 non-blocking Fast Refresh warnings; build transformed 3,006 modules and reported a non-blocking 586.90 kB largest-chunk optimization advisory. |
| 2026-07-29 | F07 final independent review | Terra code/test/architecture/security review | **Closed: no P0–P3 finding** | Local engineering review gate closed; production and commit-bound evidence gates remain. |
| 2026-07-29 | Integrated F01–F03 worktree | Focused PostgreSQL suites | **Passed: F01 45/45; F02 administration 3/3 + attendance 22/22; F03 17/17** | Fresh databases migrated through V36 with zero failures. |
| 2026-07-29 | Integrated F02/F03 worktree before fixture completion | Feature Playwright projects | **F02 failed, then F03 passed 3/9** | Preserved harness gaps: workforce allocation/status fixtures and delivery revision-comparison response were absent; product APIs and UI had already rendered. |
| 2026-07-29 | Integrated F02/F03 fixture completion | Feature Playwright projects | **Passed: F02 8/8; F03 9/9** | Deterministic browser contracts cover workforce administration/breaks and delivery revision/reconciliation/replay. |
| 2026-07-29 | Integrated F01–F03 worktree | Frontend static/unit/build gate | **Passed: 28 files/116 Vitest tests** | Typecheck, lint with zero errors, build and diff check pass; build reports only its existing chunk-size advisory. |
| 2026-07-29 23:24 IST | Integrated F01–F03 worktree | `mvn -B -f backend/pom.xml verify` | **Passed: 73 unit + 245 integration (318/318)** | Zero failures/errors/skips; BUILD SUCCESS in 03:54 with fresh PostgreSQL 18.4 migrations through V36. |
| 2026-07-29 | Integrated F01–F03 worktree | `npm run sdlc:check` | **Passed: 8 feature manifests** | Harness keeps `gpt-5.6-sol` for codegen and `gpt-5.6-terra` for reviews. |
| 2026-07-29 | Integrated V1–V38 worktree | Serialized F01–F04 PostgreSQL selection | **Passed 63/64; failed 1** | Delegated F03 approval exposed a mismatched freeze-audit authority actor; all other focused cases passed. |
| 2026-07-29 | Integrated V1–V38 freeze-authority fix | Exact delegated approval PostgreSQL case | **Passed: 1/1** | Freeze audit now binds the authority holder while immutable approval evidence preserves the distinct acting subject and delegation. |
| 2026-07-29 23:48 IST | Integrated V1–V38 worktree | Serialized F01–F04 PostgreSQL selection | **Passed: 73 unit + 64 integration (137/137)** | Core administration, workforce administration/attendance, delivery/Linear and certification operations all pass on fresh PostgreSQL 18.4 through V38. |
| 2026-07-29 | Integrated V1–V38 frontend | Typecheck, lint, Vitest and production build | **Passed: 28 files/117 tests** | Zero lint errors; build transforms 3,037 modules with only the existing chunk-size advisory. |
| 2026-07-29 | Integrated V1–V38 browser fixture before alignment | Workforce + delivery Chromium projects | **Passed 16/17; failed 1** | Direct-approval fixture omitted the new `actingSubject`/`delegationId` response fields; product UI correctly rendered the new authority model. |
| 2026-07-29 | Integrated V1–V38 browser fixture alignment | Workforce + delivery Chromium projects | **Passed: 17/17** | F02 8/8 and F03/cross-feature 9/9 pass after direct-approval fixture alignment. |
| 2026-07-26 (baseline commit) | `221a7c9` | `mvn -B -f backend/pom.xml verify` | 14 passed, 0 failed | None; Spring HTTP/JWT/Testcontainers PostgreSQL lane |
| 2026-07-26 (baseline commit) | `221a7c9` | `npm run test` | 15 passed, 0 failed | None; frontend unit/contract lane |

The consolidated command is:

```bash
npm run regression
```

It runs frontend typecheck/unit/build, Maven HTTP/PostgreSQL integration and
Playwright browser-contract E2E. Add a new row after every feature. If any lane
fails, record each failed test and its linked issue here; do not erase the
failure when a later run passes.

## Completion rule

A feature may move to `Complete` only when:

1. its task, test, implementation, review, analysis, fix and documentation
   artifacts are complete;
2. new stable-ID cases are added to the
   [E2E catalog](testing/E2E_REGRESSION_CASES.md);
3. feature-specific tests pass;
4. `npm run regression` passes after the feature is integrated; and
5. every release-blocking issue is resolved or explicitly owned as an external
   gate that prevents release.
