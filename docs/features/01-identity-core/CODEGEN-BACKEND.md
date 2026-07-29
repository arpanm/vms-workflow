# F01 Backend Code Generation

## Result

Implemented the first production backend vertical in `backend/` using Java 25,
Spring Boot 4.1.0, Maven, PostgreSQL, Flyway, Spring Data JPA, Bean Validation,
Spring Security OAuth2 resource-server JWT, Actuator, and springdoc-openapi
3.0.3. The Java package root is:

```text
com.vms.workflow
```

No Lovable or Supabase runtime, client, schema, or migration is used.

## Runtime and schema

- `backend/pom.xml` pins Spring Boot 4.1.0, Java 25, and springdoc 3.0.3.
- `backend/compose.yaml` starts PostgreSQL 18 for local development.
- `application.yml` requires the JDBC URL/credentials, JWKS URI, exact issuer,
  and JWT audience with no defaults, so the base profile fails closed.
  `application-local.yml` contains explicit local-development defaults.
- Flyway `V1__identity_core.sql` creates constrained canonical tables for
  organizations, user profiles (unique `identity_subject`), memberships,
  engagements, projects, and engagement months.
- The migration also creates five imported, read-only legacy tables:
  engagements, requirements, approvals, UAT items, and invoices.
- Production seed is limited to the explicitly required ArrowFoundry, Reliance
  Intelligence, Procurement, ArrowFoundry × Reliance engagement, NAM/Agentic
  ShopOS projects, and June engagement month. Synthetic identities, membership
  states, Northstar, and legacy rows are test-only fixtures.
- Flyway V2 adds roles, permissions, role-permission mappings, effective-dated
  scoped role assignments, and same-engagement project parentage.
- Append-only Flyway V3 rejects role assignments whose scope target is missing,
  belongs to a non-participating organization, or does not match the assigning
  organization for organization scope.

Spring Data JPA is the persistence choice for the canonical identity/catalog
bounded context. The legacy snapshot adapter uses read-only `JdbcTemplate`
queries against a fixed table allowlist so no dynamic client-controlled table
name is accepted.

## Security boundary

Every `/api/**` route requires a JWT. The configured decoder validates an exact
issuer, timestamps, required audience, RS256 algorithm, and signature against
the configured JWKS. `/v3/api-docs` and Swagger UI are also authenticated;
Actuator exposure is limited to public `health` and `info`.

Tenant authorization is derived from the JWT `sub` claim plus an active user,
active/effective membership, active organization, and active/effective scoped
role assignment containing `catalog.read`:

- organizations require organization-scoped permission;
- engagements require permission at a participating organization or the
  engagement;
- projects accept organization, engagement, or exact-project permission, while
  engagement months inherit organization/engagement scope;
- legacy reads accept an optional `organizationId`; if absent, the server
  unions only rows for the caller's active membership organizations;
- legacy payload fields are flattened to the original frontend DTO shape, with
  the immutable imported row `id` added.

Authentication and authorization failures, not-found results, and invalid
request values use RFC 7807 `application/problem+json`.

## API surface

```text
GET /api/v1/me
GET /api/v1/organizations
GET /api/v1/organizations/{id}
GET /api/v1/engagements?organizationId={uuid}
GET /api/v1/engagements/{id}
GET /api/v1/projects?engagementId={uuid}
GET /api/v1/projects/{id}
GET /api/v1/engagement-months?engagementId={uuid}
GET /api/v1/engagement-months/{id}
GET /api/v1/legacy/{engagements|requirements|approvals|uat-items|invoices}
```

The legacy paths have GET mappings only.

## Verification evidence

`ApiTenantSecurityIT` uses Testcontainers' PostgreSQL JDBC driver and MockMvc;
`JwtDecoderIT` uses real signed JWTs and an ephemeral JWKS endpoint. Together
they prove authentication validation, lifecycle and role fail-closed behavior,
own-scope success, exact-project scope, uniform non-disclosing 404s, legacy
scope, OpenAPI bearer metadata, and database-enforced project parentage.

The first verification run failed because the Boot 4 Flyway starter was not
yet present, so Hibernate validation ran before migration. After adding
`spring-boot-starter-flyway`, a second run exposed use of the old Jackson 2
`com.fasterxml` bean type. The implementation was migrated consistently to
Boot 4's Jackson 3 `tools.jackson` types.

Current command and result:

```text
mvn -B -f backend/pom.xml verify
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Second CODEGEN pass — review and security hardening

The second bounded pass added:

- exact issuer validation through `JwtValidators.createDefaultWithIssuer`,
  required `vms-api` audience validation, standard time validation, RS256-only
  decoding, and signature verification against the configured JWKS;
- `roles`, `permissions`, `role_permissions`, and effective-dated scoped
  `role_assignments`, with deny-by-default `catalog.read` enforcement;
- active user, membership-date/status, organization-status, role-status, and
  role-assignment-date/status checks on every current read API;
- uniform `Resource not found` responses for unknown and inaccessible object
  IDs;
- a composite project-parent foreign key that prevents parents from another
  engagement;
- fail-closed base configuration, local-only defaults in
  `application-local.yml`, and loopback-only local PostgreSQL publication;
- a global OpenAPI JWT bearer security scheme.

Production migrations contain the required ArrowFoundry, Reliance Intelligence,
Procurement, ArrowFoundry × Reliance engagement, NAM/Agentic ShopOS projects,
and June engagement-month master data. Test actors, invalid lifecycle states,
Northstar isolation data, and legacy fixtures moved to:

```text
backend/src/test/resources/db/testdata/V1000__security_test_fixtures.sql
```

`JwtDecoderIT` invokes the production decoder with real RS256-signed tokens and
an ephemeral JWKS endpoint. It covers the valid case plus wrong issuer,
audience, signing key, algorithm, expiry, and not-before. The MockMvc tests
continue to use Spring Security's JWT request post-processor for application
authorization cases; they do not claim to be an end-to-end identity-provider
login test.

`ApiTenantSecurityIT` now proves:

- unauthenticated denial;
- own-organization and engagement access;
- unknown/disabled identities and inactive, expired, or future scopes fail
  closed across all endpoint families;
- wrong and expired roles are denied;
- project-scoped `catalog.read` grants only its assigned project;
- inaccessible and nonexistent IDs are indistinguishable externally;
- cross-engagement project parentage is rejected by PostgreSQL;
- legacy reads remain tenant- and permission-scoped;
- authenticated OpenAPI advertises the bearer scheme.

Final second-pass result:

```text
mvn -B -f backend/pom.xml verify
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The OIDC BFF/login flow, database owner/runtime-role separation, full
approval/delegation/configuration domains, typed legacy OpenAPI schemas, and
frontend integration remain outside this bounded pass.

## Third CODEGEN pass — scoped session and project-list correction

This narrow review pass separates session visibility from organization
authority. `/api/v1/me` now returns active memberships backed by a valid
`catalog.read` role assignment at any valid organization, engagement, or
project scope. Organization and legacy collection access still require an
organization-scoped assignment, so a project-only principal does not acquire
organization-wide authority.

`GET /api/v1/projects?engagementId=...` now returns the normal engagement list
for organization/engagement readers and only explicitly assigned projects for
a project-only reader. A different or nonexistent engagement remains a
sanitized, non-disclosing 404.

Flyway V3 adds a PostgreSQL trigger without rewriting V1 or V2. It enforces
organization target equality and verifies that engagement/project targets
exist and include the assigning organization as a participant.

Final third-pass evidence:

```text
mvn -B -f backend/pom.xml verify
ApiTenantSecurityIT: 11 tests
JwtDecoderIT: 3 tests
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
## 2026-07-29 local product completion

V34 and the executable Java contract close the previously deferred F01
administration surface:

- versioned engagement configuration, contact groups, approval policies and
  effective-dated delegations;
- governed reopen requests with server-derived month evidence, immutable
  stage-electorate snapshots, ordered stages, N-of-M quorum, delegated
  self-approval enforcement, exact-project eligibility, bounded delegation,
  actor-scoped action idempotency and underlying-authority vote deduplication;
- stable-identity policy revision/publication with immutable version
  supersession, plus atomic approved-request-to-month-reopen dispatch and SQL
  mutation backstops;
- guarded engagement-month transitions with optimistic versions, effective
  configuration snapshots and append-only history;
- canonical role completion, effective permissions in `/api/v1/me`, and
  owner/runtime/public database privilege separation;
- executable `/api/v1/core/**` controllers and DTOs published through
  `/v3/api-docs`.

`CoreAdministrationIT` is the executable backend specification. Its final
focused verify migrated V1–V34 plus V1000–V1005 on PostgreSQL 18.4 and passed
17/17 cases, including request-time `ALL` quorum, future revision continuity
and captured evidence enforcement.
