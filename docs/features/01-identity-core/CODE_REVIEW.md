# F01 Frontend Code Review

> Review snapshot: this file preserves findings from the independent review
> before the second/third codegen fix passes. Current dispositions and fresh
> verification evidence are maintained in [FIXES.md](FIXES.md); unresolved
> scope remains in [TASKS.md](TASKS.md).

**Model role:** independent review (`gpt-5.6-terra`)
**Reviewed scope:** Vite SPA migration and the Java API contract present on 25 July 2026

## Outcome

The Lovable, Supabase, Cloudflare and TanStack Start runtime dependencies have been removed from the frontend source and top-level dependency tree. Vite build, TypeScript, ESLint and the six Vitest files pass. The release is nevertheless **blocked**: the SPA has no usable production authentication flow and attempts a legacy write that the Java service deliberately does not implement. See [CODE_ISSUES.md](CODE_ISSUES.md) and [SECURITY_ANALYSIS.md](SECURITY_ANALYSIS.md).

## Positive observations

- `vite.config.ts` is a conventional Vite/React/TanStack Router configuration, and the deleted files remove direct Supabase and Lovable runtime use.
- Legacy deep links are guarded with `requireLegacyRoute`; the sidebar also hides those entries when the flag is disabled.
- Demo role selection is non-production only, bannered, and kept in `sessionStorage` rather than token-bearing storage.
- Unsafe `auto_approved` and `deemed_accepted` presentations are mapped to an explicit-review state rather than shown as human approval.

## Backend contract mismatches

| SPA assumption | Java implementation | Consequence |
|---|---|---|
| `GET /api/v1/me` can be authenticated by included session cookies after OIDC login. | `SecurityConfig` accepts JWT bearer tokens only; no OIDC login/session configuration is present. | Users cannot establish a session/token from the SPA. |
| `/api/v1/auth/login` and `/api/v1/auth/logout` exist. | No matching controller/security configuration exists; `anyRequest()` denies non-API paths. | Sign-in/sign-out fail. |
| `POST /api/v1/legacy/requirements` creates a requirement. | `LegacyController` exposes only `GET`; the Flyway migration says legacy rows are immutable. | Requirement creation always receives 405/404 and violates the historical-import boundary. |
| The page can derive a current organization from compatibility collections. | The controller returns all organizations in the caller's memberships unless an `organizationId` query parameter is supplied. | No active organization scope is selected or surfaced in the UI. |

## Backend review — 26 July 2026

**Model role:** independent review (`gpt-5.6-terra`)
**Reviewed scope:** `backend/pom.xml`, Java API/application/security/repository layers, Flyway V1, local Compose configuration, OpenAPI setup, and `ApiTenantSecurityIT`

### Outcome

`mvn -B -f backend/pom.xml clean verify` succeeds. Failsafe really executes `ApiTenantSecurityIT`: 4 tests pass against PostgreSQL 18.4, and Flyway validates and applies V1 from an empty database. This is useful foundation evidence, but it does **not** satisfy the F01 exit gate.

The backend is a tenant-filtered read-only catalog prototype, not the specified Phase 1 identity/RBAC/core implementation. Membership status and dates are checked, cross-organization test IDs are denied, OpenAPI/Swagger are authenticated, and the legacy SQL table name is selected from a fixed allowlist. Release remains blocked by missing issuer validation, missing role/permission/object-scope authorization, incomplete Phase 1 schema/workflows, fail-open local credentials, and insufficient security tests.

### Backend contract and scope summary

- JWT authentication is resource-server-only; there is no login/callback/logout/session implementation.
- Authorization is `active membership in any participating organization`, not the required intersection of permission, role, resource assignment, effective date, object state, and separation of duties.
- Only organizations, memberships, engagements, projects, engagement months, and immutable legacy snapshots are modeled. Roles/permissions/assignments, configuration versions, contacts/teams, approvals/delegations, guarded transitions/history, audit/idempotency, and mutation contracts are absent.
- The generated OpenAPI endpoint is protected, but the OpenAPI model declares only title/version/description and no bearer security scheme or standardized ProblemDetail responses.
- Successful `verify` proves container startup and four read cases; its mock JWTs bypass the configured decoder and therefore do not prove real JWT validation.
## Final completion review — 2026-07-29

Reviewed scope: V34, authorization/session changes, core DTO/controller/service,
fixtures, `CoreAdministrationIT`, React administration/navigation work and
feature documentation.

Closed findings include cross-engagement configuration/policy pointers,
effective-window overlap, participant membership checks, delegation authority
windows, exact pending stage enforcement, approval idempotency, project scope,
self-approval, stale versions and delegate/authority quorum double counting.

The final remediation pass also closed seven adversarial gaps:

- delegated self-approval now compares the requester with the underlying
  authority;
- database request-transition/grant guards block direct SQL mutation;
- public request input is server-derived and limited to governed month reopen;
- approval actions have actor-scoped exact-replay idempotency;
- policy changes use immutable revision/supersession;
- each request freezes stage eligibility/quorum/delegation;
- final request approval atomically dispatches the bound month reopen, with a
  database backstop against an unapproved reopen.

No open local P0–P3 F01 product finding remains after focused verification.
