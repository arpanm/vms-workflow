# F01 Frontend Test Review

> Review snapshot: this evidence predates the backend hardening reruns.
> [FIXES.md](FIXES.md) and [TEST_AUTOMATION.md](TEST_AUTOMATION.md) contain the
> current test inventory and dispositions.

**Model role:** independent review (`gpt-5.6-terra`)

## Checks run

- `npm run typecheck` — passed.
- `npm run lint` — passed with six pre-existing React Fast Refresh warnings in shared UI primitives.
- `npm run test` — passed: 6 files, 14 tests.
- `npm run build` — passed; Vite emitted a chunk-size warning for a 568.67 kB minified chunk.
- `npm audit --omit=dev` — 0 vulnerabilities; full `npm audit` — 5 high development dependency findings.
- `npm ls @lovable.dev/vite-tanstack-config @supabase/supabase-js @tanstack/react-start @cloudflare/vite-plugin --depth=0` — empty.

The unit tests provide useful coverage of API header/error mapping, environment parsing, explicit demo mode, ordinary legacy flag parsing, status presentation, and token-memory behavior. They do not prove the SPA can sign in, use the Java contract, preserve route gating in a browser, or isolate data by organization.

## Backend test review — 26 July 2026

### Verification run

- `mvn -B -f backend/pom.xml clean verify` — passed.
- Failsafe 3.5.6 executed `ApiTenantSecurityIT`; `backend/target/failsafe-reports/failsafe-summary.xml` records 4 completed, 0 failures/errors/skips.
- Testcontainers started PostgreSQL 18.4 and Flyway successfully validated/applied V1 from an empty schema.
- Surefire found no unit tests.
- `mvn -B -f backend/pom.xml dependency:tree -Dscope=runtime` — passed and resolved the Spring Boot-managed runtime graph.

### What the four tests prove

- An unauthenticated MockMvc request receives JSON ProblemDetail 401.
- The seeded Reliance user can read its organization and engagement.
- That user receives 403 for seeded Northstar organization/engagement IDs.
- Legacy requirements are flattened and explicit foreign-organization selection is denied.

### What they do not prove

`SecurityMockMvcRequestPostProcessors.jwt()` constructs an authenticated test security context (`ApiTenantSecurityIT.java:85-87`); it does not send a signed bearer token through the configured `JwtDecoder`. Consequently, the suite does not test JWKS lookup/signature, issuer, expiry/not-before, audience rejection, algorithms, or key rotation. It also omits inactive/expired/revoked membership, inactive organization, project/month direct IDs, permissions/object assignments, schema constraints, OpenAPI, CORS/CSRF, database grants, mutations, audit/idempotency, and typed domain errors.
