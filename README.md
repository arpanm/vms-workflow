# Cadence — Workforce and Delivery Evidence Governance

Cadence is being rebuilt as a Java/PostgreSQL application with a standard Vite React frontend. The current working tree contains the foundation, the read-only identity/core vertical, a reviewed local workforce/attendance vertical, and a reviewed provider-neutral delivery/Linear demonstrator. Lovable, Supabase, Cloudflare and TanStack Start server dependencies have been removed.

## Current status

The detailed, continuously maintained ledger is
[FEATURE_STATUS.md](docs/FEATURE_STATUS.md). The extensible browser/system
regression catalog is
[E2E_REGRESSION_CASES.md](docs/testing/E2E_REGRESSION_CASES.md).

| Track | Status | Evidence |
|---|---|---|
| F00 foundation and SDLC harness | Implemented locally; staging backup/smoke inputs remain external blockers | [F00 tasks](docs/features/00-foundation/TASKS.md), [changelog](docs/features/00-foundation/CHANGELOG.md) |
| F01 Java identity/core read vertical | Implemented and verified with PostgreSQL Testcontainers | [F01 tasks](docs/features/01-identity-core/TASKS.md), [codegen](docs/features/01-identity-core/CODEGEN.md) |
| F01 production identity/provisioning | Blocked until an OIDC provider, same-origin BFF login endpoint and approved user/role provisioning path are selected/configured | [fix disposition](docs/features/01-identity-core/FIXES.md) |
| F02 workforce/attendance local vertical | Implemented, independently reviewed and locally regressed; provider/admin/full-stack scope remains open | [F02 fixes](docs/features/02-workforce-attendance/FIXES.md), [API](docs/features/02-workforce-attendance/API_DOCUMENTATION.md), [UI guide](docs/features/02-workforce-attendance/UI_DOCUMENTATION.md) |
| F03 Delivery and Linear | Reviewed provider-neutral local demonstrator; P0 resolved by V10, local P1 and live-provider/BFF gates remain open | [F03 evidence](docs/features/03-delivery-linear/CODEGEN.md), [API](docs/features/03-delivery-linear/API_DOCUMENTATION.md), [UI](docs/features/03-delivery-linear/UI_DOCUMENTATION.md) |
| F04 Certification and confirmation | Local Java/PostgreSQL + React provider-neutral vertical verified: 111 backend, 64 frontend and 59 intercepted Playwright tests pass; provider/full-stack gates remain open | [F04 evidence](docs/features/04-certification-confirmation/CODEGEN.md), [API](docs/features/04-certification-confirmation/API_DOCUMENTATION.md), [UI](docs/features/04-certification-confirmation/UI_DOCUMENTATION.md) |
| F05 evidence, invoice and reporting | Locally quality-gated: 154/154 backend, 88/88 Vitest, 69/69 combined Playwright and 3/3 isolated system E2E; performance/scale and external release gates remain ACTION_REQUIRED | [F05 status](docs/FEATURE_STATUS.md), [F05 E2E catalog](docs/testing/E2E_REGRESSION_CASES.md), [F05 closure](docs/features/05-evidence-invoice-reporting/FINAL_CLOSURE_REVIEW.md) |
| F06–F07 product features | Task/test specifications are ready; implementation proceeds sequentially | [feature plans](#feature-delivery-plans) |

Production release remains blocked until the identity/BFF decision and staging
tenant-isolation gate are complete. Local feature development uses explicit
demo/intercepted-browser and Testcontainers boundaries documented in the
[status ledger](docs/FEATURE_STATUS.md).

## Architecture

```text
Vite + React 19 + TanStack Router/Query
                  |
             /api/v1 (JWT)
                  |
Spring Boot 4.1.0 / Java 25 / Maven
                  |
     Spring Security + JPA + Flyway
                  |
             PostgreSQL 18
```

- The controlling stack override is [Requirement 22](requirements/22_JAVA_POSTGRES_ARCHITECTURE_OVERRIDE.md).
- The decision rationale is [ADR-010](docs/architecture/ADR-010-JAVA-POSTGRES.md).
- The indexed source/dependency map is [CODE_INDEX.md](docs/architecture/CODE_INDEX.md).
- The baseline commit is `5e463c7`; the local rollback tag is `baseline/pre-workforce-20260725`.

## Run locally

Prerequisites: Java 25, Maven 3.9+, Node.js/npm, and Docker.

```bash
docker compose -f backend/compose.yaml up -d
mvn -f backend/pom.xml spring-boot:run
npm install
npm run dev
```

The frontend proxies `/api` to `http://localhost:8080`. Demo mode is enabled only when explicitly configured and never grants backend permissions. See [.env.example](.env.example). The Java service validates bearer JWTs; production browser login remains disabled until a same-origin OIDC/BFF entry point is configured.

## Validate

```bash
npm run sdlc:check
npm run typecheck
npm run lint
npm run test
npm run build
mvn -B -f backend/pom.xml verify
npm run e2e
npm run regression
```

The Maven verification applies Flyway to ephemeral PostgreSQL and runs 49
integration tests covering real signed-JWT validation, lifecycle and scoped
RBAC denial, tenant/object isolation, database constraints, legacy reads,
workforce/attendance invariants, delivery/Linear integrity and OpenAPI security
metadata.

`npm run regression` combines frontend checks, Maven/Testcontainers
PostgreSQL integration and Playwright Chromium browser-contract tests. The
Playwright API is deterministic and intercepted; it is not evidence of a real
OIDC provider or deployed full-stack environment. See the
[testing guide](docs/testing/README.md).

## Requirements and implementation order

- [Start here](requirements/CURSOR_START_HERE.md)
- [Master implementation index](requirements/00_INDEX_IMPLEMENTATION_TODO.md)
- [Java/PostgreSQL override](requirements/22_JAVA_POSTGRES_ARCHITECTURE_OVERRIDE.md)
- [Requirement traceability](requirements/21_REQUIREMENT_TRACEABILITY_AND_GAP_CLOSURE.md)
- [Acceptance/NFR/operations catalog](requirements/16_ACCEPTANCE_TEST_CATALOG_NFR_ROLLOUT_AND_OPERATIONS.md)
- [Complete requirement pack](requirements/README.md)

## Feature delivery plans

| Feature | Tasks | Tests | Implementation/review evidence |
|---|---|---|---|
| F00 Foundation | [Tasks](docs/features/00-foundation/TASKS.md) | [Tests](docs/features/00-foundation/TEST_CASES.md) | [Folder](docs/features/00-foundation/) |
| F01 Identity and core | [Tasks](docs/features/01-identity-core/TASKS.md) | [Tests](docs/features/01-identity-core/TEST_CASES.md) | [Folder](docs/features/01-identity-core/) |
| F02 Workforce and attendance | [Tasks](docs/features/02-workforce-attendance/TASKS.md) | [Tests](docs/features/02-workforce-attendance/TEST_CASES.md) | [Reviewed local vertical and remaining scope](docs/features/02-workforce-attendance/) |
| F03 Delivery and Linear | [Tasks](docs/features/03-delivery-linear/TASKS.md) | [Tests](docs/features/03-delivery-linear/TEST_CASES.md) | [Reviewed local evidence](docs/features/03-delivery-linear/CODEGEN.md), [fix disposition](docs/features/03-delivery-linear/FIXES.md), [API](docs/features/03-delivery-linear/API_DOCUMENTATION.md), [UI](docs/features/03-delivery-linear/UI_DOCUMENTATION.md) |
| F04 Certification and confirmation | [Tasks](docs/features/04-certification-confirmation/TASKS.md) | [Tests](docs/features/04-certification-confirmation/TEST_CASES.md) | [Codegen](docs/features/04-certification-confirmation/CODEGEN.md), [review](docs/features/04-certification-confirmation/CODE_REVIEW.md), [fixes](docs/features/04-certification-confirmation/FIXES.md), [API](docs/features/04-certification-confirmation/API_DOCUMENTATION.md), [UI](docs/features/04-certification-confirmation/UI_DOCUMENTATION.md) |
| F05 Evidence, invoice and reporting | [Tasks](docs/features/05-evidence-invoice-reporting/TASKS.md) | [Tests](docs/features/05-evidence-invoice-reporting/TEST_CASES.md) | [Codegen](docs/features/05-evidence-invoice-reporting/CODEGEN.md), [reviews/issues](docs/features/05-evidence-invoice-reporting/FINAL_ISSUES.md), [closure](docs/features/05-evidence-invoice-reporting/FINAL_CLOSURE_REVIEW.md), [API](docs/features/05-evidence-invoice-reporting/API_DOCUMENTATION.md), [UI](docs/features/05-evidence-invoice-reporting/UI_DOCUMENTATION.md) |
| F06 Historical migration | [Tasks](docs/features/06-historical-migration/TASKS.md) | [Tests](docs/features/06-historical-migration/TEST_CASES.md) | Planned |
| F07 Hardening and go-live | [Tasks](docs/features/07-hardening-go-live/TASKS.md) | [Tests](docs/features/07-hardening-go-live/TEST_CASES.md) | Planned |

## SDLC harness

[The agentic SDLC harness](docs/sdlc/HARNESS.md) requires tasks, tests, codegen, independent review, test review, static/security analysis, fixes, API/UI docs and changelog evidence for every completed feature. Code generation uses `gpt-5.6-sol`; independent reviews use `gpt-5.6-terra`. The final repository workflow creates a local commit only and never pushes.

## Operations

- [Rollback and recovery](docs/operations/ROLLBACK.md)
- [Detailed feature status and open issues](docs/FEATURE_STATUS.md)
- [End-to-end regression case catalog](docs/testing/E2E_REGRESSION_CASES.md)
- [Testing and Playwright guide](docs/testing/README.md)
- [F01 API documentation](docs/features/01-identity-core/API_DOCUMENTATION.md)
- [F01 UI guide](docs/features/01-identity-core/UI_DOCUMENTATION.md)
- [F02 API documentation](docs/features/02-workforce-attendance/API_DOCUMENTATION.md)
- [F02 UI guide](docs/features/02-workforce-attendance/UI_DOCUMENTATION.md)
- [F03 API documentation](docs/features/03-delivery-linear/API_DOCUMENTATION.md)
- [F03 UI guide](docs/features/03-delivery-linear/UI_DOCUMENTATION.md)
- [F04 API documentation](docs/features/04-certification-confirmation/API_DOCUMENTATION.md)
- [F04 UI guide](docs/features/04-certification-confirmation/UI_DOCUMENTATION.md)
- [F05 architecture](docs/features/05-evidence-invoice-reporting/ARCHITECTURE.md)
- [F05 API documentation](docs/features/05-evidence-invoice-reporting/API_DOCUMENTATION.md)
- [F05 UI guide](docs/features/05-evidence-invoice-reporting/UI_DOCUMENTATION.md)
- [F05 runbook](docs/features/05-evidence-invoice-reporting/RUNBOOK.md)

Do not store salary, CTC, markup, employee billing rates or payroll calculations. Do not infer human approval from silence, delivery receipts, elapsed time or external ticket status.
