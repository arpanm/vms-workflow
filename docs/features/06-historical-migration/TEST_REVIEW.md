# F06 Historical Migration — Test Automation Review

> **Historical pre-automation review.** The gaps below are preserved because
> they drove the automation work. The authoritative disposition is the
> post-remediation section at the end of this document.

## Verdict

The current tests are useful smoke checks but are **not acceptance evidence** for F06. `mvn -q -f backend/pom.xml test`, `npm run test -- --run src/features/migration/presentation.test.ts`, and `npm run build` pass locally. None exercises V17 against PostgreSQL or a real frontend/backend contract.

## Existing coverage

- `MigrationCsvParserTest` covers BOM, quotes, escaped quotes, unterminated quotes, row cap, and registry count/order.
- `presentation.test.ts` covers two pure UI helpers.
- `e2e/migration.spec.ts` provides three mocked-browser journeys.

## Material test quality gaps

| Priority | Gap | Evidence | Acceptance needed |
|---|---|---|---|
| P0 | The E2E suite intercepts every migration API response and uses a separate, incompatible fictional contract. | [migration-api.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/e2e/fixtures/migration-api.ts:143) mocks `**/api/v1/migrations/**`; its plural `/approvals` path is not implemented by the server. | Add an authenticated real-backend Playwright/system suite against Testcontainers/staging fixture DB. Retain mocks only for visual-state tests. |
| P0 | No test proves commits create usable domain records for any of 14 templates. | No migration service integration test exists; commit writes generic facts. | Parameterized Testcontainers tests for all 14 template fixtures plus domain read-model/API verification. |
| P0 | No authorization/SoD test proves server rejects forged role, tenant, organization, report hash, or second approving identity. | Existing cross-tenant E2E is a mock 403 ([migration.spec.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/e2e/migration.spec.ts:56)). | HTTP-level JWT + PostgreSQL tests for every mutation and download; include same engagement/wrong organization and disabled membership. |
| P1 | No persistence/lifecycle test catches immutable-trigger retry failure, stale ETag, idempotency races, reprocess selection, rollback/reopen. | Parser-only Java test class. | Testcontainers transition/state-machine tests with concurrent requests and expected audit/outbox/provenance rows. |
| P1 | No semantic test for attendance authority, timezone/overnight raw punches, raw/daily reconciliation, leave/calendar coverage, or non-additive minutes. | `validateDaily` and `enforceAttendanceAuthority` have no tests. | Calculation-service integration tests for same employee/date, local-midnight, source conflict and computed expected-day reconciliation. |
| P1 | No adversarial file test beyond parser syntax. | Parser test does not exercise upload scan/MIME/formula/XSS/size control. | Multipart controller tests for invalid UTF-8, archive/magic bytes, name/MIME mismatch, oversized input, EICAR scan failure, CSV formula export, multiline fields and PII-safe errors. |
| P2 | UI tests do not test keyboard navigation, focus/error summary, progress polling, row pagination/filtering, download failure, or inaccessible action controls. | [migration.spec.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/e2e/migration.spec.ts:6). | Axe + keyboard E2E plus responsive migration-workbench tests with actual permissions. |

## Post-remediation disposition

The P0 gaps are closed locally:

- `MigrationDomainAdapterIT` applies every physical template to its owning
  bounded context against PostgreSQL.
- `MigrationWorkflowIT` uses signed JWTs and real HTTP/database state for scope,
  SoD, idempotency, duplicate-before-apply, rejected-only reprocess, signed
  cursors, retries, compensation and retro time.
- `migration-system.spec.ts` contains six non-intercepted browser journeys
  through the real Spring/Flyway/PostgreSQL stack.
- The combined browser-contract suite covers five Migration Center state/action
  journeys and retains all F00–F05 regression.

Definitive evidence before the final review edge-case patch was 14/14 unit,
158/158 integration, 90/90 Vitest, 74/74 intercepted Playwright and 6/6 real
system Playwright. The status ledger records the focused and full reruns after
the final policy/provenance hardening.
