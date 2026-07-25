# 22 — Java / PostgreSQL Architecture Override

**Status:** Controlling addendum — effective immediately
**Applies to:** every implementation phase and all stack-specific guidance in PRD 13 and PRD 17

## Decision

Build the production system as a Java backend and PostgreSQL application. The authoritative target stack is:

- Java 25 with Spring Boot 4.1.0 and Maven;
- Spring Security with JWT/OIDC resource-server validation and method-level authorization;
- PostgreSQL with Flyway migrations, constraints, views/functions only where reviewed, and database roles that support least privilege;
- Spring Data JDBC/JPA (choose and document one per bounded context), Bean Validation, and an explicit service/API boundary;
- springdoc 3.0.3 generating OpenAPI from executable Spring controllers, with API documentation in source and `/v3/api-docs`/Swagger UI protected appropriately;
- JUnit 5, Spring Boot Test, Testcontainers PostgreSQL, MockMvc/WebTestClient, and integration/security tests against a real ephemeral PostgreSQL database.

The existing React/TanStack/Supabase/Lovable implementation is a **historical baseline and rollback reference in Git history**, not the production architecture. Remove its Lovable/Supabase dependencies and migration files from the current working tree; do not expand it with new workforce, delivery, certification, invoice, or security features. A standard Vite React/TanStack frontend remains at the repository root and consumes secured Java APIs.

## Supersession and preservation

- PRD 13 and PRD 17 remain authoritative for business entities, lifecycle, API/event semantics, routes, data migration ordering, and test intent.
- Their instructions that prescribe Supabase clients, Edge Functions, Supabase Auth/RLS, generated Supabase types, `supabase/` migrations/tests, Lovable, or TanStack Start server execution are superseded by this addendum.
- Translate “RLS proof” to Spring authorization plus PostgreSQL role/row-scope integration tests. PostgreSQL may use row-level security only where an ADR and tests justify it; it is not a substitute for server authorization.
- Translate additive Supabase migration/seed steps to versioned Flyway migrations and synthetic seed fixtures. The legacy Supabase migration is preserved only by the baseline Git tag/history and must not remain in the current working tree or be run as part of the new rollout.
- Preserve the baseline Git tag/history and a verified pre-cutover deployment artifact for rollback. No production data migration may begin until the source system, target PostgreSQL staging environment, mapping, backup, and rehearsal are approved.

## Required repository target layout

```text
backend/pom.xml
backend/src/main/java/.../{api,application,domain,infrastructure,security}/
backend/src/main/resources/{application*.yml,db/migration}/
backend/src/test/java/.../{unit,integration,security}/
src/                       # Vite React/TanStack frontend at repository root
docs/
```

Maven is mandatory and its project descriptor is `backend/pom.xml`. The exact Java package root must be recorded before F01 is marked complete. Keep provider clients, background jobs, file scanning, email delivery, and package generation server-side.

## Security and operating rules

- JWT issuer, audience, JWKS, database, provider, and signing secrets are server configuration; never expose them in browser bundles or commits.
- Every request must resolve an authenticated principal and active organization/engagement scope. UI controls never establish authority.
- Enforce tenant/object authorization in Spring services/controllers and prove it with HTTP-level integration tests using Testcontainers PostgreSQL.
- Flyway migrations are append-only, reviewed, checksummed, forward-compatible, and rehearsed on a staging copy. A migration that loses evidence requires an approved restore plan.
- Use explicit idempotency, audit events, immutable snapshots, and optimistic/version checks for consequential workflows.

## Phase translation

| Earlier wording | Controlling implementation |
|---|---|
| Supabase Auth/session/RLS | OIDC/JWT + Spring Boot 4.1.0 Security + service authorization + PostgreSQL integration tests |
| Supabase migration/reset/lint | Flyway validate/migrate/clean only in ephemeral test DB + Testcontainers |
| Edge Function/server function | Spring controller/application service or secured worker |
| Browser `supabase.from(...)` | typed HTTP client calling secured OpenAPI endpoint |
| Supabase Storage | server-managed object storage adapter with metadata, malware scan and access audit |
| generated Supabase TypeScript types | springdoc 3.0.3 OpenAPI contract/client generation where the root Vite React/TanStack UI requires it |

## Exit evidence

F01 cannot pass until a Spring Boot application starts against Testcontainers PostgreSQL; Flyway applies from an empty database; unauthenticated and cross-tenant HTTP requests are denied; and a valid JWT with authorized scope succeeds. Existing prototype access controls do not count as this evidence.

## Deferred decisions / blockers

- Select OIDC issuer/tenant, claims contract, key rotation, session/logout policy, and service-account model.
- Select PostgreSQL staging and production instances, data-residency controls, backup RPO/RTO, monitoring, object storage, job runner, and email provider.
- Inventory and export the existing staging prototype before any cutover. No staging project, export destination, or storage metadata backup has been supplied; this remains blocked and must not be inferred from local configuration.
