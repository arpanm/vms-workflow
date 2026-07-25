# Cadence — Workforce and Delivery Evidence Governance

Cadence is being rebuilt as a Java/PostgreSQL application with a standard Vite React frontend. The current working tree contains the Phase 0 foundation and a Phase 1 read-only identity/core vertical slice. Lovable, Supabase, Cloudflare and TanStack Start server dependencies have been removed.

## Current status

| Track | Status | Evidence |
|---|---|---|
| F00 foundation and SDLC harness | Implemented locally; staging backup/smoke inputs remain external blockers | [F00 tasks](docs/features/00-foundation/TASKS.md), [changelog](docs/features/00-foundation/CHANGELOG.md) |
| F01 Java identity/core read vertical | Implemented and verified with PostgreSQL Testcontainers | [F01 tasks](docs/features/01-identity-core/TASKS.md), [codegen](docs/features/01-identity-core/CODEGEN.md) |
| F01 production identity/provisioning | Blocked until an OIDC provider, same-origin BFF login endpoint and approved user/role provisioning path are selected/configured | [fix disposition](docs/features/01-identity-core/FIXES.md) |
| F02–F07 product features | Planned only; their task/test specifications are ready | [feature plans](#feature-delivery-plans) |

Phase 2 must not start until the production identity/BFF decision and staging tenant-isolation gate are complete.

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
```

The Maven verification applies Flyway to ephemeral PostgreSQL and runs 14
integration tests covering real signed-JWT validation, lifecycle and scoped
RBAC denial, tenant/object isolation, database constraints, legacy reads and
OpenAPI security metadata.

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
| F02 Workforce and attendance | [Tasks](docs/features/02-workforce-attendance/TASKS.md) | [Tests](docs/features/02-workforce-attendance/TEST_CASES.md) | Planned |
| F03 Delivery and Linear | [Tasks](docs/features/03-delivery-linear/TASKS.md) | [Tests](docs/features/03-delivery-linear/TEST_CASES.md) | Planned |
| F04 Certification and confirmation | [Tasks](docs/features/04-certification-confirmation/TASKS.md) | [Tests](docs/features/04-certification-confirmation/TEST_CASES.md) | Planned |
| F05 Evidence, invoice and reporting | [Tasks](docs/features/05-evidence-invoice-reporting/TASKS.md) | [Tests](docs/features/05-evidence-invoice-reporting/TEST_CASES.md) | Planned |
| F06 Historical migration | [Tasks](docs/features/06-historical-migration/TASKS.md) | [Tests](docs/features/06-historical-migration/TEST_CASES.md) | Planned |
| F07 Hardening and go-live | [Tasks](docs/features/07-hardening-go-live/TASKS.md) | [Tests](docs/features/07-hardening-go-live/TEST_CASES.md) | Planned |

## SDLC harness

[The agentic SDLC harness](docs/sdlc/HARNESS.md) requires tasks, tests, codegen, independent review, test review, static/security analysis, fixes, API/UI docs and changelog evidence for every completed feature. Code generation uses `gpt-5.6-sol`; independent reviews use `gpt-5.6-terra`. The final repository workflow creates a local commit only and never pushes.

## Operations

- [Rollback and recovery](docs/operations/ROLLBACK.md)
- [F01 API documentation](docs/features/01-identity-core/API_DOCUMENTATION.md)
- [F01 UI guide](docs/features/01-identity-core/UI_DOCUMENTATION.md)

Do not store salary, CTC, markup, employee billing rates or payroll calculations. Do not infer human approval from silence, delivery receipts, elapsed time or external ticket status.
