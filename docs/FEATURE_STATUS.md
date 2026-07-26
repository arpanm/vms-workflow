# Feature Delivery Status

This file is the single detailed delivery ledger. Update it after every
codegen, review, fix and regression run. Task/test documents remain the detailed
specifications; this ledger records what is actually complete now.

**Last updated:** 2026-07-26 10:09 IST
**Repository commit under test:** `221a7c9`
**Working-tree context:** The reviewed F02 local vertical and regression
harness are complete in the active worktree. Broader F02 provider,
administration and controlled-environment scope remains explicitly open.

## Overall status

| Feature | State | Completed subparts | Pending subparts | Automated evidence | Current failures |
|---|---|---|---|---|---|
| F00 Foundation | Implemented locally; external gates open | Java/PostgreSQL decision, rollback tag/plan, environment rules, feature flags, SDLC harness, local validation, Playwright harness | Staging backup references, deployed health/auth smoke, rollback rehearsal | 2 Playwright cases passing as part of the 18-case browser run; existing frontend and backend validation | None in latest local browser-contract run |
| F01 Identity/core | Partial vertical slice implemented and locally verified | JWT resource server validation, active scoped RBAC reads, tenant/object denial, Flyway core catalog, Testcontainers coverage, OpenAPI bearer metadata, demo safety banner, read-only legacy routes, redirect defense | Real OIDC/BFF login/logout, approved provisioning, active scope selector/navigation, contacts, approval/delegation model, guarded month transitions/history, separate migration/runtime DB roles, generated client/schema gates, staging exit evidence | 9 Playwright cases passing as part of the 18-case browser run; 14 Maven integration tests passing at commit `221a7c9`; 15 frontend unit tests passing at that commit | None in latest recorded lanes; full-stack lane unavailable |
| F02 Workforce/attendance | Reviewed local vertical accepted; broader feature partial | Effective employee/lifecycle reads, allocations, self-only employee discovery and commands, attendance projection/punches, missing-checkout handling, immutable per-working-date leave allocation/ledger, regularization submission, complete allocated-month snapshot/reopen lineage, effective capability fail-closed checks, API/UI docs and SDLC review artifacts | greytHR discovery/sync/reconciliation/cutover; calendar/policy admin UI; leave approval/cancellation; regularization review/dual-control correction; breaks/overnight shifts; CSV imports; real BFF/provider/browser-to-Java/PostgreSQL staging E2E | Final integrated run: 26 frontend tests, 34 Java/PostgreSQL tests and 18 Playwright cases (7 F02), all passing | None in latest local lanes; external full-stack lane unavailable |
| F03 Delivery/Linear | Planned | Task and test specifications | All implementation, review, automation, docs and external integration certification | None | Not run; feature absent |
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
