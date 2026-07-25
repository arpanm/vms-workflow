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

Vitest covers the API client/error mapping, memory access-token provider, environment/demo rules, route flags, historical status presentation and redirect safety.

Command:

```bash
npm run test
```

## Coverage gaps

- configured OIDC/BFF browser login/logout;
- exhaustive multi-membership organization/engagement/project list-and-detail matrix;
- database migration-owner/runtime-role grant enforcement;
- complete OpenAPI snapshot/error-schema/generated-client compatibility;
- contacts, approvals/delegations and guarded month-transition contracts;
- browser E2E and SPA hosting fallback;
- native PostgreSQL RLS (not selected in ADR-010).

These gaps remain in [TEST_ISSUES.md](TEST_ISSUES.md) and prevent claiming the complete Phase 1 exit gate.
