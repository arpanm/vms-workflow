# Cadence — Workforce and Delivery Evidence Governance

Cadence is a Java/PostgreSQL application with a Vite React frontend. The
working tree contains the F00–F07 local product and hardening verticals.
Lovable, Supabase, Cloudflare and TanStack Start server dependencies have been
removed.

## Current status

The detailed, continuously maintained ledger is
[FEATURE_STATUS.md](docs/FEATURE_STATUS.md). The extensible browser/system
regression catalog is
[E2E_REGRESSION_CASES.md](docs/testing/E2E_REGRESSION_CASES.md). The single
cross-feature list of unfinished local work, pending verification, active
issues and external-only gates is
[PENDING_WORK.md](docs/PENDING_WORK.md).

| Track | Status | Evidence |
|---|---|---|
| F00 foundation and SDLC harness | Implemented locally; staging backup/smoke inputs remain external blockers | [F00 tasks](docs/features/00-foundation/TASKS.md), [changelog](docs/features/00-foundation/CHANGELOG.md) |
| F01 Java identity/core administration | V34 product vertical and governed F04 reopen bridge implemented; focused PostgreSQL verification passes 45/45 | [F01 tasks](docs/features/01-identity-core/TASKS.md), [codegen](docs/features/01-identity-core/CODEGEN.md), [architecture](docs/features/01-identity-core/ARCHITECTURE.md), [API](docs/features/01-identity-core/API_DOCUMENTATION.md), [UI](docs/features/01-identity-core/UI_DOCUMENTATION.md) |
| F01 production identity/provisioning | Blocked until an OIDC provider, same-origin BFF login endpoint and approved user/role provisioning path are selected/configured | [fix disposition](docs/features/01-identity-core/FIXES.md) |
| F02 workforce/attendance | V35/V37 employee and serialized allocation lifecycle, governed workforce administration, overnight/split sessions, exact roster snapshots and month close with manager/self React flows | [F02 tasks](docs/features/02-workforce-attendance/TASKS.md), [fixes](docs/features/02-workforce-attendance/FIXES.md), [API](docs/features/02-workforce-attendance/API_DOCUMENTATION.md), [UI guide](docs/features/02-workforce-attendance/UI_DOCUMENTATION.md) |
| F03 Delivery and Linear | V36/V38/V39 editable repeatable delivery drafts, revision/replay operations, delegated approval lineage and bounded cursor reconciliation with operator UI | [F03 tasks](docs/features/03-delivery-linear/TASKS.md), [codegen](docs/features/03-delivery-linear/CODEGEN.md), [API](docs/features/03-delivery-linear/API_DOCUMENTATION.md), [UI](docs/features/03-delivery-linear/UI_DOCUMENTATION.md) |
| F04 Certification and confirmation | Provider-neutral Java/PostgreSQL + React vertical includes V40 private governed uploads/scans, exact-version withdrawal, cross-month inboxes and operations health; live provider/deployment gates remain external | [F04 tasks](docs/features/04-certification-confirmation/TASKS.md), [evidence](docs/features/04-certification-confirmation/CODEGEN.md), [API](docs/features/04-certification-confirmation/API_DOCUMENTATION.md), [UI](docs/features/04-certification-confirmation/UI_DOCUMENTATION.md) |
| F05 evidence, invoice and reporting | Locally quality-gated, including 4/4 isolated system cases and exact E2E-06/E2E-09 evidence; performance/scale and external release gates remain ACTION_REQUIRED | [F05 status](docs/FEATURE_STATUS.md), [F05 E2E catalog](docs/testing/E2E_REGRESSION_CASES.md), [F05 closure](docs/features/05-evidence-invoice-reporting/FINAL_CLOSURE_REVIEW.md) |
| F06 historical migration | Locally quality-gated: 172/172 backend, 90/90 Vitest, 74/74 combined Playwright and 6/6 real local system journeys; production scanner/storage/capacity/rehearsal gates remain ACTION_REQUIRED | [F06 status](docs/FEATURE_STATUS.md), [tasks](docs/features/06-historical-migration/TASKS.md), [tests](docs/features/06-historical-migration/TEST_CASES.md), [review](docs/features/06-historical-migration/FINAL_REVIEW.md), [API](docs/features/06-historical-migration/API_DOCUMENTATION.md), [UI](docs/features/06-historical-migration/UI_DOCUMENTATION.md) |
| F07 hardening/go-live | V1–V33 local lanes pass: frontend 92/92 + static/build, Maven R3/R4 290/290, focused backend 73+45, capacity 73+2, systems 7/7 + 4/4 + 6/6, browser 274/274 and exact supply-chain zero findings; Terra reviews closed and clean migration/supply evidence is bound to `eda3eb8`. External production gates remain NO-GO/ACTION_REQUIRED. | [status](docs/FEATURE_STATUS.md), [pending work](docs/PENDING_WORK.md), [regression catalog](docs/testing/E2E_REGRESSION_CASES.md), [testing guide](docs/testing/README.md), [tasks](docs/features/07-hardening-go-live/TASKS.md), [tests](docs/features/07-hardening-go-live/TEST_CASES.md), [automation](docs/features/07-hardening-go-live/TEST_AUTOMATION.md), [review status](docs/features/07-hardening-go-live/FINAL_REVIEW.md), [open issues](docs/features/07-hardening-go-live/FINAL_ISSUES.md) |

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
npm install
npm run dev:all
```

`dev:all` starts PostgreSQL, a local JWKS endpoint, the Spring backend and the
Vite frontend. It checks ports `5432`, `9000`, `8080` and `3000` in turn; when
one is occupied it selects the next available port and propagates the resolved
database URL, issuer/JWKS URL, backend proxy target and frontend URL to every
dependent process. The selected values are written to the ignored
`.local-dev/runtime.env` file and printed after startup. `Ctrl+C` stops the
Java/Node services while retaining the PostgreSQL container and named volume.
Development fixtures use the dedicated `vms_workflow_local` database, keeping
the normal `vms_workflow` database's Flyway history production-only. The
local-only token defaults to the seeded `user-reliance` identity; set
`VMS_LOCAL_SUBJECT` before `dev:all` to use another seeded development subject.
The command loads the explicitly synthetic V1000+ development fixtures after
the production V1+ Flyway chain. These fixtures are local-only and are never
part of a production deployment.

To start only PostgreSQL, or to stop the retained local dependency container:

```bash
npm run dev:dependencies
npm run dev:down
```

The local Compose project is explicitly named `vms-workflow-local`, preventing
collisions with unrelated repositories whose directory is also named
`backend`. Direct Compose use remains supported and accepts an explicit port:

```bash
VMS_POSTGRES_PORT=5433 docker compose \
  --project-name vms-workflow-local -f backend/compose.yaml up -d
```

The PostgreSQL 18 named volume is mounted at `/var/lib/postgresql`, matching
the image's version-specific data layout. If this repository was previously
started with the old `/var/lib/postgresql/data` mount, back up the old database
before recreating the service; do not delete or reuse the old volume as an
empty PG18 data root. Restore the backup into a fresh volume created by the
current Compose file.

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

The production Flyway chain is V1–V40 in the integrated worktree. F07 focused backend evidence passes 73
unit plus 45 integration tests; its capacity lane passes 73 + 2, the
F07/finance/migration system lanes pass 7/7, 4/4 and 6/6, and the complete
browser matrix passes 274/274. Definitive complete Maven R3 passes 73 unit +
217 integration (290/290), zero failures/errors/skips, in 03:21. The earlier
R2 215/217 integration result and its Docker pauses remain preserved; assigning
the delivery-worker IT its own database removed the cross-suite state.
Maven R4 repeats 290/290 in 02:48 on digest-pinned Chainguard PostgreSQL 18.4,
and the exact remediated supply-chain gate passes every report, both release
artifacts and the database image with zero findings.
Final frontend checks pass typecheck, lint (0 errors/6 Fast Refresh warnings),
Vitest (24 files/92 tests), production build (3,006 modules; 586.90 kB
largest-chunk optimization advisory) and diff-check. Final Terra product and
supply-remediation reviews closed with no open P0–P3 finding. Current F01
focused evidence passes 73 unit + 17 integration, 15 focused Vitest, 9/9 F01
Playwright and 7/7 demo regression cases.

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
| F06 Historical migration | [Tasks](docs/features/06-historical-migration/TASKS.md) | [Tests](docs/features/06-historical-migration/TEST_CASES.md) | [Codegen](docs/features/06-historical-migration/CODEGEN.md), [reviews/issues](docs/features/06-historical-migration/FINAL_ISSUES.md), [API](docs/features/06-historical-migration/API_DOCUMENTATION.md), [UI](docs/features/06-historical-migration/UI_DOCUMENTATION.md), [runbook](docs/features/06-historical-migration/RUNBOOK.md) |
| F07 Hardening and go-live | [Tasks](docs/features/07-hardening-go-live/TASKS.md) | [Tests](docs/features/07-hardening-go-live/TEST_CASES.md) | [Codegen](docs/features/07-hardening-go-live/CODEGEN.md), [reviews/issues](docs/features/07-hardening-go-live/CODE_REVIEW.md), [fixes](docs/features/07-hardening-go-live/FIXES.md), [API](docs/features/07-hardening-go-live/API_DOCUMENTATION.md), [UI/operator guide](docs/features/07-hardening-go-live/UI_DOCUMENTATION.md), [architecture](docs/features/07-hardening-go-live/ARCHITECTURE.md), [final status](docs/features/07-hardening-go-live/FINAL_REVIEW.md) |

## SDLC harness

[The agentic SDLC harness](docs/sdlc/HARNESS.md) requires tasks, tests, codegen, independent review, test review, static/security analysis, fixes, API/UI docs and changelog evidence for every completed feature. Code generation uses `gpt-5.6-sol`; independent reviews use `gpt-5.6-terra`. The final repository workflow creates a local commit only and never pushes.

## Operations

- [Rollback and recovery](docs/operations/ROLLBACK.md)
- [Detailed feature status and open issues](docs/FEATURE_STATUS.md)
- [Consolidated pending work across every feature](docs/PENDING_WORK.md)
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
- [F06 architecture](docs/features/06-historical-migration/ARCHITECTURE.md)
- [F06 API/Swagger documentation](docs/features/06-historical-migration/API_DOCUMENTATION.md)
- [F06 UI guide](docs/features/06-historical-migration/UI_DOCUMENTATION.md)
- [F06 migration runbook](docs/features/06-historical-migration/RUNBOOK.md)
- [F06 final independent review](docs/features/06-historical-migration/FINAL_REVIEW.md)
- [F07 architecture](docs/features/07-hardening-go-live/ARCHITECTURE.md)
- [F07 API/Swagger documentation](docs/features/07-hardening-go-live/API_DOCUMENTATION.md)
- [F07 UI and operator guide](docs/features/07-hardening-go-live/UI_DOCUMENTATION.md)
- [F07 runbook index](docs/features/07-hardening-go-live/RUNBOOK.md)
- [F07 release, supply-chain and DR procedure](docs/operations/F07-RELEASE-AND-DR.md)
- [F07 incident runbooks](docs/operations/F07-RUNBOOKS.md)
- [F07 review status and open issues](docs/features/07-hardening-go-live/FINAL_REVIEW.md)
- F07 SDLC evidence: [codegen](docs/features/07-hardening-go-live/CODEGEN.md),
  [code review](docs/features/07-hardening-go-live/CODE_REVIEW.md),
  [code issues](docs/features/07-hardening-go-live/CODE_ISSUES.md),
  [test automation](docs/features/07-hardening-go-live/TEST_AUTOMATION.md),
  [test review](docs/features/07-hardening-go-live/TEST_REVIEW.md),
  [test issues](docs/features/07-hardening-go-live/TEST_ISSUES.md),
  [code analysis](docs/features/07-hardening-go-live/CODE_ANALYSIS.md),
  [security analysis](docs/features/07-hardening-go-live/SECURITY_ANALYSIS.md),
  [fixes](docs/features/07-hardening-go-live/FIXES.md),
  [changelog](docs/features/07-hardening-go-live/CHANGELOG.md) and
  [final issues](docs/features/07-hardening-go-live/FINAL_ISSUES.md)

Do not store salary, CTC, markup, employee billing rates or payroll calculations. Do not infer human approval from silence, delivery receipts, elapsed time or external ticket status.
