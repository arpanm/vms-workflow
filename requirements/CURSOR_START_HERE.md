# Cursor / Claude Code — Start Here

Use this repository together with the existing `vms-workflow` codebase. Do not implement from this file alone.

## Mandatory reading sequence

1. `22_JAVA_POSTGRES_ARCHITECTURE_OVERRIDE.md` — controlling stack addendum; read this before any implementation.
2. `README.md`
3. `00_INDEX_IMPLEMENTATION_TODO.md`
4. `17_EXISTING_CODE_IMPACT_FILE_LEVEL_TODO_AND_MIGRATION_ORDER.md` (domain/migration intent only; Supabase/Lovable/TanStack stack instructions are superseded)
5. `13_DATA_MODEL_API_EVENTS_BACKGROUND_JOBS_AND_STORAGE.md` (domain/API/event intent only; Supabase-specific implementation is superseded)
6. `14_SECURITY_PRIVACY_AUDIT_RETENTION_AND_COMPLIANCE.md`
7. the functional PRD(s) for the phase being implemented;
8. `16_ACCEPTANCE_TEST_CATALOG_NFR_ROLLOUT_AND_OPERATIONS.md`
9. `21_REQUIREMENT_TRACEABILITY_AND_GAP_CLOSURE.md`
10. supporting `schemas/` and `templates/`.

## Execution instruction

Implement this as an additive, in-place **product migration** with Java 25, Spring Boot 4.1.0, Maven, springdoc 3.0.3, and PostgreSQL. The Maven backend lives at `backend/pom.xml` with Java sources under `backend/src/`; the standard Vite React/TanStack frontend remains at repository-root `src/`. The historical React/TypeScript/TanStack/Supabase application is a baseline-tag/rollback reference; remove its Lovable/Supabase dependencies from the working tree and do not add new business features to it. Before adding workforce data, implement JWT/OIDC authentication, Spring service authorization, organization/engagement scope checks, Flyway migrations, and Testcontainers PostgreSQL integration tests.

Work one implementation phase at a time. For each phase:

1. inspect the current code and database rather than assuming file paths;
2. produce a short implementation plan and map it to requirement IDs;
3. add additive Flyway database migrations, rollback notes and synthetic seed data;
4. implement Spring server-side authorization and idempotency before UI actions;
5. implement all normal, empty, loading, error, stale, conflict and unauthorized states;
6. add unit, Spring/Testcontainers integration, authorization/tenant-isolation and end-to-end tests named from PRD 16/21;
7. run frontend checks where applicable plus `mvn -B -f backend/pom.xml verify`, Testcontainers integration tests, Flyway validation, generated OpenAPI checks, and migration validation;
8. update `00_INDEX_IMPLEMENTATION_TODO.md` checkboxes and a phase changelog;
9. do not proceed past an exit gate with failing security/data-integrity tests.

## Non-negotiables

- Do not store salary, CTC, markup, employee rates or payroll calculations.
- Do not hard-code ArrowFoundry, Reliance, NAM, ShopOS or 26 employees in business logic.
- Do not use a browser role dropdown as authorization.
- Do not rely on browser code, local roles, or database policy alone for authorization; enforce authorization in Spring services/controllers and verify PostgreSQL scope isolation with integration tests.
- Do not let mutable current records rewrite closed-month evidence.
- Do not turn a missing checkout into synthetic attendance.
- Do not let greytHR and internal data both become authoritative for the same employee-day.
- Do not treat Linear `Done` as delivery acceptance.
- Do not treat email delivery, read receipt or silence as confirmation.
- Do not put JWT, integration, database, or signing credentials in frontend code or ordinary database columns.
- Do not mutate evidence packages; create a new version with supersession links.

## First implementation task

Complete **Phase 0 and Phase 1 only** from `00_INDEX_IMPLEMENTATION_TODO.md`:

- baseline/backup/feature flags;
- environment validation;
- organizations, users, memberships, permissions and engagements/months through Flyway;
- real OIDC/JWT auth;
- Spring authorization matrix and automated PostgreSQL/Testcontainers tenant-isolation tests;
- seed data for the initial ArrowFoundry × Reliance Intelligence engagement;
- legacy route compatibility.

Stop after the Phase 1 exit gate is demonstrated. Do not load attendance or production personal data into a schema with unverified Java authorization and PostgreSQL integration-test evidence.
