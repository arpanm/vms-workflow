# F01 Review Fixes

## Release-blocker disposition

| ID | Status | Resolution |
|---|---|---|
| F01-FIX-001 | Resolved | Removed the legacy requirement POST client, creation sheet, CTA and mutation messaging. `/requirements` is now an explicitly read-only compatibility view. |
| F01-FIX-002 | Resolved | Return paths now reject absolute and protocol-relative URLs, raw/encoded/repeatedly encoded backslashes, control characters, malformed encoding, oversized input and values that do not resolve to the application origin. Regression tests cover `/\\attacker.example`, `/%5c%5cattacker.example` and `//attacker.example`. |
| F01-FIX-003 | Blocked — external configuration required | No OIDC provider or BFF login route is fabricated. `VITE_OIDC_LOGIN_PATH` has no default and must identify an externally configured, same-origin BFF login endpoint. The current Java resource server validates bearer JWTs but does not supply that login endpoint. Outside demo mode the login UI visibly blocks and disables SSO until the variable is configured. |

## Security boundary

The SPA validates redirects defensively, but the eventual BFF must independently
validate `returnTo`, own the OAuth authorization-code + PKCE exchange, protect
session cookies and unsafe methods, and expose an authenticated `/api/v1/me`
contract. These frontend tests are not evidence that an identity provider or BFF
flow is operational.

## Backend hardening disposition — second CODEGEN pass

| Review IDs | Status | Resolution and evidence |
|---|---|---|
| F01-BE-CR-002, F01-BE-SA-001, F01-BE-TR-001 | Resolved for the bearer resource server | The decoder now requires an exact issuer, audience, valid time window, RS256 algorithm, and a signature chaining to the configured JWKS. `JwtDecoderIT` uses real signed compact JWTs and an ephemeral JWKS server; it accepts the valid case and rejects wrong issuer, audience, key, algorithm, expiry, and not-before. The broader OIDC login/BFF remains externally blocked and was not fabricated. |
| F01-BE-CR-001, F01-BE-SA-002 | Partially resolved in the requested read-catalog boundary | Added canonical `roles`, `permissions`, `role_permissions`, and effective-dated organization/engagement/project `role_assignments`. Every current read API requires `catalog.read` at the applicable scope, in addition to active identity, membership, and organization. Wrong-role, expired-role, and project-specific allowed/denied cases run against PostgreSQL. Full approval/delegation/object-state/separation-of-duties domains remain deferred. |
| F01-BE-CR-004, F01-BE-CR-008, F01-BE-SA-005, F01-BE-SA-007, F01-BE-TR-002 | Resolved for current endpoints | Unknown and disabled users, inactive/expired/future memberships, inactive organizations, and identities without a current permission assignment fail closed. The HTTP test iterates these states across `/me`, all canonical list/detail families, and legacy reads. |
| F01-BE-CR-006, F01-BE-SA-006 | Resolved | Inaccessible and nonexistent organization, engagement, project, and month IDs now return the same sanitized 404 response, removing the external existence oracle. |
| F01-BE-CR-007, F01-BE-SA-004, F01-BE-TR-004 | Resolved | PostgreSQL now enforces a composite `(parent_project_id, engagement_id)` foreign key. Testcontainers proves a cross-engagement parent insert is rejected. |
| F01-BE-CR-005, F01-BE-SA-003 | Partially resolved | Base configuration has no database/JWKS/issuer/audience defaults and fails startup when required environment configuration is absent. Local-only defaults moved to `application-local.yml`; Compose publishes PostgreSQL only on `127.0.0.1`. Separate migration/runtime database roles and grant tests remain a later hardening item. |
| F01-BE-CR-010, F01-BE-SA-010 | Partially resolved | OpenAPI now declares a global JWT HTTP bearer security scheme and the authenticated generated document is tested. Typed legacy schemas and generated-client compatibility remain deferred. |
| Fixture finding | Resolved | Production Flyway retains only the required ArrowFoundry, Reliance Intelligence, Procurement, ArrowFoundry × Reliance engagement, NAM/Agentic ShopOS projects, and June month master seed. Synthetic identities, membership states, Northstar, and legacy records exist only in the test Flyway location. |

Final second-pass evidence:

```text
mvn -B -f backend/pom.xml verify
Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Backend correction disposition — third CODEGEN pass

| Finding | Status | Resolution and evidence |
|---|---|---|
| Project-scoped principals could not establish their session through `/me` | Resolved | Session memberships now accept `catalog.read` at any valid scope, while organization and legacy access continue to require organization scope. HTTP coverage proves a project-only principal receives 200 from `/me` and 403 from `/organizations`. |
| Project list authorization treated project-only readers as engagement-wide readers or denied the list entirely | Resolved | Organization/engagement readers receive the normal list; project-only readers receive only explicitly assigned projects. Cross-scope and nonexistent engagement IDs return the same sanitized 404. |
| Role-assignment scope identifiers were not fully bound to valid tenant catalog targets | Resolved | Append-only Flyway V3 enforces organization target equality and engagement/project target existence plus organization participation. Testcontainers PostgreSQL rejects mismatched organization scope, cross-participant engagement/project scope, and a nonexistent project target. V1 and V2 are unchanged. |

Final third-pass evidence:

```text
mvn -B -f backend/pom.xml verify
ApiTenantSecurityIT: 11 tests
JwtDecoderIT: 3 tests
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Remaining independent-review findings

- Production migrations intentionally seed no people, memberships or
  assignments. An approved invitation/provisioning/import path is required
  before deployment; manual production SQL is not an accepted operating model.
- `/me` proves an effective permission exists but still reports the legacy
  membership `roleCode`, not the complete effective scoped-role/permission
  model. Permission-aware navigation and an authoritative active scope remain
  open under F01-T11.
- JWT cryptography is tested through the production decoder and API
  authorization through the Spring filter chain, but a compact signed bearer
  token is not yet sent end-to-end through that filter chain.
- V1 and V2 have not been released from this working tree. V3 is append-only,
  but a persistent-environment upgrade/checksum rehearsal remains mandatory
  before any deployment.
- Migration-owner/runtime database roles, the full contacts/approval/
  delegation/month-transition model, typed legacy schemas and generated-client
  compatibility remain open.
