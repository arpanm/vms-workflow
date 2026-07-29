# F01 Test Automation

## Backend

`backend/src/test/java/com/vms/workflow/integration/ApiTenantSecurityIT.java` runs with Spring Boot, MockMvc, Flyway and Testcontainers PostgreSQL. Maven Failsafe binds it to `verify`.

Covered:

- unauthenticated API denial with RFC 7807;
- allowed organization/engagement access;
- cross-organization and cross-engagement denial with uniform not-found responses;
- disabled/unknown identities and inactive, expired or future membership/role scopes;
- deny-by-default permission behavior and exact-project authorization;
- project-scoped `/me` session success without organization-wide authority;
- project-list filtering for exact-project readers, normal listing for
  organization readers, and non-disclosing unauthorized/unknown engagement
  responses;
- legacy membership-scope inference, payload compatibility and forged-scope denial;
- PostgreSQL rejection of cross-engagement project parentage;
- PostgreSQL rejection of invalid organization, engagement, and project
  role-assignment targets, including non-participating organizations and
  nonexistent targets;
- authenticated OpenAPI bearer metadata;
- real compact JWT decoding against an ephemeral JWKS, including wrong issuer,
  audience, key, algorithm, expiry and not-before.

Command:

```bash
mvn -B -f backend/pom.xml verify
```

Current result:

```text
ApiTenantSecurityIT: 11 tests
JwtDecoderIT: 3 tests
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Frontend

Vitest covers the API client/error mapping, memory access-token provider,
environment/demo rules, route flags, historical status presentation, redirect
safety, authority-bound scope persistence/reset, permission predicates,
administrative month edges and runtime-validated core administration API
contracts.

Playwright `e2e/core-admin.spec.ts` covers:

- `E2E-F01-BC-010`: scope persistence followed by reset when organization
  authority changes;
- `E2E-F01-BC-011`: permission-derived navigation and non-disclosing denied deep
  link without a protected collection request;
- `E2E-F01-BC-012`: contact-member mutation bound to the displayed group version;
- `E2E-F01-BC-013`: stale month transition rejected with a reload path and no
  overwrite;
- `E2E-F01-BC-014`: prospective configuration publishing bound to the displayed
  engagement version and explicit policy JSON;
- `E2E-F01-BC-015`: governed reopen creation sends policy, month ID and a
  generated idempotency key without client-fabricated type/version/hash/scope;
- `E2E-F01-BC-016`: actor-idempotent delegated approval attribution without
  client-fabricated quorum;
- `E2E-F01-BC-017`: stale approval action rejected with a reload path;
- `E2E-F01-BC-018`: denied approval detail does not fetch the protected request.

Command:

```bash
npm run test
```

## Coverage gaps

- configured OIDC/BFF browser login/logout;
- exhaustive multi-membership organization/engagement/project list-and-detail matrix;
- database migration-owner/runtime-role grant enforcement;
- complete OpenAPI snapshot/error-schema/generated-client compatibility;
- configured browser OIDC and SPA hosting fallback;
- native PostgreSQL RLS (not selected in ADR-010).

These gaps remain in [TEST_ISSUES.md](TEST_ISSUES.md) and prevent claiming the complete Phase 1 exit gate.
## Final F01 backend lane — 2026-07-29

Command:

```text
mvn -B -f backend/pom.xml -Dtest='*CoreAdministrationIT' \
  -DfailIfNoTests=false -Dit.test=CoreAdministrationIT verify
```

Result: `BUILD SUCCESS`; 13 `CoreAdministrationIT` cases passed in both the
Surefire and configured Failsafe invocation against fresh PostgreSQL 18.4
schemas migrated through V34 and V1000–V1005. The cases cover permissions,
tenant/engagement/project denial, version conflicts, configurations, contacts,
policy revision/publication, server-derived reopen evidence, eligibility
snapshots, 2-of-3 quorum, request/action idempotency, direct and delegated
self-approval, expired delegation, duplicate authority votes, exact-project
eligibility, authoritative reopen dispatch, direct SQL bypass rejection, role
inventory, month transition history, least-privilege grants and generated
OpenAPI paths.
