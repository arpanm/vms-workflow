# F01 Changelog

## 25 July 2026

- Replaced the prescribed Supabase/Lovable architecture with the user-directed Java 25/Spring Boot 4.1/PostgreSQL architecture.
- Removed Lovable, Supabase, Cloudflare, TanStack Start server and Bun artifacts from the working tree.
- Converted the frontend to standard Vite React/TanStack and typed `/api/v1` access.
- Added safe demo presentation, session boundary, read-only legacy routes and redirect-security tests.
- Added Spring Boot JWT resource server, Flyway core schema/seed, JPA queries, tenant authorization, RFC 7807 and protected OpenAPI.
- Added PostgreSQL Testcontainers HTTP tenant-isolation tests.
- Fixed unsafe legacy mutations and open-redirect paths found by independent review.
- Added exact issuer/audience/time/RS256/JWKS validation with real signed-token tests.
- Added effective-dated scoped RBAC, lifecycle fail-closed checks, uniform
  inaccessible/not-found responses and database-enforced project hierarchy.
- Moved all synthetic identities, invalid lifecycle states, Northstar isolation
  data and legacy rows out of production migrations into test-only fixtures.
- Made base server configuration fail closed and declared JWT bearer security
  in generated OpenAPI.
- Kept the production OIDC/BFF selection as an explicit release blocker rather than inventing credentials/provider behavior.
## 2026-07-29 — local F01 product completion

- Added V34 identity/core administration, canonical role completion and
  least-privilege runtime grants.
- Added engagement configuration, contact group, approval policy, delegation,
  governed reopen request/action and month transition APIs.
- Added exact-project approval eligibility, authority-identity quorum,
  request/action idempotency, direct/delegated self-approval and immutable
  evidence controls.
- Added immutable policy revisions with non-overlapping future-effective
  handoff that preserves the current effective policy, immutable
  request electorate snapshots, server-derived month evidence and atomic final
  approval-to-reopen dispatch with database mutation backstops.
- Derived `ALL` quorum from the request-time electorate and enforced
  policy-required nonblank action evidence through Java/API/UI contracts.
- Added effective permissions, current-scope and permission-aware React
  administration/navigation behavior.
- Expanded PostgreSQL integration coverage and permanent F01 regression catalog
  entries for forged evidence, eligibility drift, action replay, delegated
  self-approval, policy revision and direct SQL bypass.
- Kept provider-backed OIDC/BFF/provisioning acceptance explicitly external.
