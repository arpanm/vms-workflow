# ADR-010 — Java Backend and PostgreSQL as the Production Platform

**Status:** Accepted (architecture override, 25 July 2026)
**Supersedes:** stack-specific portions of ADR-001 and PRD 13/17; not their domain requirements

## Context

The historical baseline application was a TanStack/Supabase prototype with browser-direct data access and permissive anonymous policies. Its Supabase/Lovable dependencies and migration files are being removed from the working tree, while the baseline tag/history remains available for rollback reference. The new system will hold multi-organization attendance, approval, evidence, and invoice-supporting records, requiring a server-enforced trust boundary.

## Decision

Use Java 25, Spring Boot 4.1.0, Maven, PostgreSQL, Spring Security JWT/OIDC, Flyway, springdoc 3.0.3 OpenAPI, JUnit 5, and Testcontainers PostgreSQL. The backend owns authorization, integration credentials, mutation validation, jobs, audit, immutable evidence, and storage access. The root Vite React/TanStack frontend consumes secured OpenAPI endpoints only.

## Consequences

- The historical baseline tag is retained as a rollback reference, but Supabase/Lovable dependencies and migration files are removed from the working tree and are not a target for feature expansion.
- New data schema is implemented as Flyway migrations under `backend/src/main/resources/db/migration`. Historical Supabase SQL is not the target schema.
- Cross-tenant isolation must be proven at the HTTP/service/database integration boundary, not only in client code or mocks.
- API documentation is executable OpenAPI generated from controllers and synchronized with endpoint authorization.
- Cutover needs an explicit source export, mapping, staging rehearsal, backup, and rollback decision. These external prerequisites are currently uncompleted.

## Alternatives considered

- Continue the browser-direct Supabase prototype: rejected because it cannot provide the required production authorization/audit boundary without a major architectural change.
- Rewrite only the UI: rejected because business mutations, provider secrets, and evidence integrity need a server boundary.

## Validation

`mvn -B -f backend/pom.xml verify`, Testcontainers PostgreSQL integration tests, Flyway validation, authenticated/unauthenticated/cross-tenant API tests, and generated OpenAPI contract checks are required once the backend exists.
