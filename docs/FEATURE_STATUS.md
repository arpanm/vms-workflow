# Feature Delivery Status

This file is the single detailed delivery ledger. Update it after every
codegen, review, fix and regression run. Task/test documents remain the detailed
specifications; this ledger records what is actually complete now.

**Last updated:** 2026-07-26 12:00 IST
**Repository commit under test:** `b4f209b` plus active F03 worktree
**Working-tree context:** The F03 provider-neutral local vertical and
browser-contract harness are present in the active worktree. The hardened F03
Playwright project, complete F00–F03 Playwright matrix, typecheck and lint pass.
Live provider, full-stack identity/BFF and controlled-environment acceptance
remain open.

## Overall status

| Feature | State | Completed subparts | Pending subparts | Automated evidence | Current failures |
|---|---|---|---|---|---|
| F00 Foundation | Implemented locally; external gates open | Java/PostgreSQL decision, rollback tag/plan, environment rules, feature flags, SDLC harness, local validation, Playwright harness | Staging backup references, deployed health/auth smoke, rollback rehearsal | 2 Playwright cases passing as part of the 26-case browser run; existing frontend and backend validation | None in latest local browser-contract run |
| F01 Identity/core | Partial vertical slice implemented and locally verified | JWT resource server validation, active scoped RBAC reads, tenant/object denial, Flyway core catalog, Testcontainers coverage, OpenAPI bearer metadata, demo safety banner, read-only legacy routes, redirect defense | Real OIDC/BFF login/logout, approved provisioning, active scope selector/navigation, contacts, approval/delegation model, guarded month transitions/history, separate migration/runtime DB roles, generated client/schema gates, staging exit evidence | 9 Playwright cases passing as part of the 26-case browser run; prior Java/PostgreSQL and frontend validation | None in latest recorded lanes; full-stack lane unavailable |
| F02 Workforce/attendance | Reviewed local vertical accepted; broader feature partial | Effective employee/lifecycle reads, allocations, self-only employee discovery and commands, attendance projection/punches, missing-checkout handling, immutable per-working-date leave allocation/ledger, regularization submission, complete allocated-month snapshot/reopen lineage, effective capability fail-closed checks, API/UI docs and SDLC review artifacts | greytHR discovery/sync/reconciliation/cutover; calendar/policy admin UI; leave approval/cancellation; regularization review/dual-control correction; breaks/overnight shifts; CSV imports; real BFF/provider/browser-to-Java/PostgreSQL staging E2E | 7 F02 cases passing as part of the 26-case browser run; prior integrated frontend/Java/PostgreSQL suite recorded green | None in latest local lanes; external full-stack lane unavailable |
| F03 Delivery/Linear | Provider-neutral local vertical reviewed; broader feature partial | PostgreSQL/Flyway delivery schema, plan/create/submit/approval/freeze/revision services, exact checksum/baseline evidence, recorded Linear link/current/snapshot and signed-webhook processing, tenant/security/OpenAPI checks, delivery UI/integration-health states and final documentation; V10 resolves reviewed local P0 integrity findings | Local P1 queue/retry/dead-letter/replay, reconciliation, exhaustive quorum/cycle/allocation/perimeter and Swagger/least-privilege coverage; live Linear OAuth/PKCE/GraphQL/webhook/mail; real BFF-to-Java-to-PostgreSQL and tenant-authorized provider acceptance | 47 frontend tests, 49 Spring/Testcontainers tests, and 26/26 intercepted Playwright cases (8 F03/cross-feature) passing | No unresolved local P0; local P1 and external/full-stack gates remain open |
| F04 Certification/confirmation | Planned | Task and test specifications | All implementation, review, automation, docs and provider-neutral confirmation integration | None | Not run; feature absent |
| F05 Evidence/invoice/reporting | Planned | Task and test specifications | All implementation, storage/security review, deterministic package tests, docs | None | Not run; feature absent |
| F06 Historical migration | Planned | Task and test specifications | All implementation, templates, reconciliation, rollback, automation and docs | None | Not run; feature absent |
| F07 Hardening/go-live | Planned | Task and test specifications | Security/NFR/accessibility/DR/release-environment regression and sign-offs | None | Not run; release gate absent |

## Open issues and blockers

| ID | Feature | Severity/state | Owner/input needed | Detail |
|---|---|---|---|---|
| STATUS-BLOCK-001 | F01/F07 | Release blocker; external | Product/security/identity owner | Select and configure the OIDC provider, same-origin BFF flow, cookie/CSRF controls and logout behavior. |
| STATUS-BLOCK-002 | F01 | Release blocker; external | Product/security/operations | Approve a user invitation/provisioning/import process. Production migrations intentionally seed no users or assignments. |
| STATUS-BLOCK-003 | F00/F01 | Exit evidence blocker; external | Operations | Supply a controlled staging-like environment, backup destination and deployment references for tenant-isolation/rollback evidence. |
| STATUS-ISSUE-004 | F01 | Open | Backend/database | Add separate migration-owner/runtime roles and prove grants. |
| STATUS-ISSUE-005 | F01 | Open | Product/backend/frontend | Complete current organization/engagement/month selection and effective permission-aware navigation. |
| STATUS-ISSUE-006 | F01 | Open | API/frontend | Complete typed legacy schemas and generated-client compatibility checks. |
| STATUS-BLOCK-007 | All | Test-lane blocker; external | Identity/operations | Full-stack browser-to-BFF-to-Java-to-PostgreSQL E2E cannot run until the identity/provisioning and controlled-environment blockers are resolved. Current Playwright evidence is browser-contract E2E with mocked APIs. |
| STATUS-ISSUE-008 | F02 | Resolved for local vertical | Feature agents | Independent review, fixes, exact artifacts and the consolidated regression now pass. The broader feature remains partial because the provider/admin/full-stack items below are not implemented. |
| STATUS-ISSUE-009 | F02 | Resolved in active worktree | Frontend feature agent | The earlier TS2339 fixture-type failure was corrected and remains preserved in the regression history. |
| STATUS-ISSUE-010 | F02 | Open scope | Product/backend/frontend | Complete greytHR integration, policy/calendar administration, leave and regularization approval/correction workflows, break/overnight rules and imports before claiming the entire F02 requirement set complete. |
| STATUS-ISSUE-011 | F02 | Open hardening | Backend/database | Application advisory locks serialize supported commands; direct database writers must use governed import paths and compatible coordination. V6 deliberately fails incompatible legacy leave backfill instead of silently misallocating units. |
| STATUS-ISSUE-012 | F03 | Resolved in browser-contract harness | E2E agent | Initial selector, validation-copy and fixture-key mismatches produced preserved 2/6, 5/3 and 6/2 pass/fail runs. A later hardened-contract check preserved a 1/7 pass/fail run from stale fixture enums and browser metadata fields. The synced fixture now passes 8/8 and the full suite passes 26/26. |
| STATUS-ISSUE-013 | F03 | Open local scope | Backend/frontend/QA | Complete exhaustive quorum/concurrency, dependency/cycle/allocation, queue crash/retry/dead-letter/replay, scheduled reconciliation, OpenAPI audience and least-privilege-role coverage before claiming full local F03 completion. |
| STATUS-BLOCK-014 | F03 | Release blocker; external | Tenant product/security/operations | Approve/configure Linear OAuth app/workspace/scopes/webhook secret and mail provider/sender/contact groups; run live OAuth/GraphQL/webhook and commitment-mail acceptance without exposing credentials. |
| STATUS-BLOCK-015 | F03 | Test-lane blocker; external | Identity/operations | Run real browser-to-BFF-to-Java-to-PostgreSQL F03 journeys in a controlled environment. Current Playwright results are deterministic intercepted browser-contract evidence only. |

Detailed F01 review dispositions are in
[F01 FIXES.md](features/01-identity-core/FIXES.md).
Detailed F02 findings and post-fix dispositions are in
[F02 FIXES.md](features/02-workforce-attendance/FIXES.md).

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
