# F01 Frontend Security Analysis

> Security review snapshot: findings below describe the reviewed revision.
> [FIXES.md](FIXES.md) records later remediation evidence and remaining
> blockers; this review is not silently rewritten after codegen.

## Findings

| ID | Severity | Finding | Required gate |
|---|---|---|---|
| F01-SA-001 | High | The purported safe OIDC return path accepts a backslash protocol-relative URL (`src/lib/auth/session-client.ts:21-25`), which resolves cross-origin in browser URL parsing. A future backend that trusts this parameter creates an OAuth open redirect. | Reject it in SPA and backend; test it before enabling login. |
| F01-SA-002 | High | The frontend consistently sends `credentials: include` (`src/lib/api-client.ts:86-91`) and assumes cookie sessions, but the Java service disables CSRF (`SecurityConfig.java:26`) and currently accepts bearer tokens only. If cookie OIDC is added without changing this, state-changing endpoints become CSRF-exposed. | Pick bearer+PKCE or BFF cookies deliberately. For BFF cookies, use Secure/HttpOnly/SameSite, origin checks and Spring CSRF token validation on unsafe methods. |
| F01-SA-003 | High | The compatibility write UI attempts to mutate a record class documented as immutable; no valid authorization/audit/idempotency contract exists for the action. | Remove the mutation or implement a canonical service authorization and audit boundary; prove wrong-org/wrong-role denial in Testcontainers. |
| F01-SA-004 | Medium | Browser feature flags and demo role have been correctly separated from Java authorization, but the UI still has no server-derived active organization scope. Accidental multi-org display is not a bypass today, but it raises tenant-confusion and future authorization regression risk. | Obtain scope from a secured server response and scope every query/action; test multi-membership behavior. |
| F01-SA-005 | Low | Full dependency audit retains five high dev-tool findings. Production dependency audit is clean. | Upgrade ESLint/minimatch in a separately tested breaking-change task and keep CI auditing production dependencies. |

## Security posture

The migration removes the historical direct Supabase trust boundary and does not store access tokens in `localStorage`, which is a material improvement. It cannot be considered an authentication/security completion until a coherent OIDC credential flow, redirect validation, CSRF posture, organization scope, and Java integration tests are in place. Client flags and client role state must remain presentation-only.

## Backend security analysis — 26 July 2026

| ID | Severity | Finding and exact evidence | Required security gate |
|---|---|---|---|
| F01-BE-SA-001 | Critical | JWT issuer is not validated (`SecurityConfig.java:43-53`). Trust is based on any token signed by the configured JWKS whose time and audience pass, without binding the token to the selected issuer/tenant. | Configure and validate exact issuer plus audience/time/signature/algorithm; prove negative cases with real signed tokens. |
| F01-BE-SA-002 | Critical | Authorization has no role/permission/resource-assignment model. Any active organization member is treated as an authorized reader for all participating engagements/projects/months (`TenantAuthorizationService.java:28-46`), contrary to the required scope hierarchy and Project-A-only isolation. | Implement deny-by-default permission and assignment checks with complete allowed/denied matrix tests. |
| F01-BE-SA-003 | High | Default server configuration contains usable local database credentials and endpoints (`application.yml:5-7,22-26`), and Compose publishes the database with the same known password (`compose.yaml:3-9`). There is no separate runtime role. | Make non-test deployments fail closed without secret configuration; separate migration/runtime accounts and bind local PostgreSQL to loopback only. |
| F01-BE-SA-004 | High | Project parentage can cross engagement/tenant boundaries because `parent_project_id` references only `projects(id)` (`V1__identity_core.sql:69-83`). | Add database-enforced same-engagement parentage and adversarial SQL/JPA tests. |
| F01-BE-SA-005 | High | Disabled/unknown identities can receive 200 empty collections because membership-filtered list methods do not require an active application principal (`CatalogQueryService.java:52-57`, `LegacyQueryService.java:41-53`). | Reject every protected request for inactive/unmapped principals consistently and test session/token reuse after deactivation. |
| F01-BE-SA-006 | Medium | Global fetch-before-authorize creates a tenant object-existence oracle for engagement/project/month IDs (`CatalogQueryService.java:71-103`). | Query through authorized scope and return indistinguishable not-found results externally. |
| F01-BE-SA-007 | Medium | Organization lifecycle is omitted from membership authorization (`MembershipRepository.java:14-39`), so inactive/archived organizations remain valid scopes. | Require active organization status or an explicitly audited archival-access permission. |
| F01-BE-SA-008 | Medium | Raw framework/validation exception messages are returned in ProblemDetail (`ApiExceptionHandler.java:30-45`) and there is no stable error code or correlation ID. | Sanitize public detail, log correlated internal evidence, and contract-test error bodies. |
| F01-BE-SA-009 | Medium | CSRF is globally disabled (`SecurityConfig.java:26`). That is acceptable only for a stateless Authorization-header API; it is unsafe if the planned same-origin BFF adds ambient cookie authentication without revisiting this chain. No explicit CORS policy exists. | Freeze the auth architecture. Keep bearer APIs stateless and same-origin/strict-CORS, or enable CSRF defenses for cookie-backed endpoints. |
| F01-BE-SA-010 | Low | OpenAPI and Swagger are authenticated (`SecurityConfig.java:28-30`), which prevents anonymous schema enumeration, but no OpenAPI bearer scheme is declared (`VmsWorkflowApplication.java:16-21`) and any authenticated member can see the full contract. | Add the bearer scheme and decide/document whether all members or only developer/support permissions may access API documentation. |
| F01-BE-SA-011 | Informational | The legacy JDBC table identifier is derived only from the immutable map (`LegacyQueryService.java:17-23`) and the controller regex (`LegacyController.java:26`); organization IDs are parameters. | Preserve and regression-test the allowlist. No current SQL-injection route was found. |
| F01-BE-SA-012 | Low | Maven `verify` performs no vulnerability/SBOM/static analysis, and the PostgreSQL Compose image is not digest-pinned. A successful build is not dependency-risk evidence. | Add CI software-composition analysis, SBOM generation, update policy, and pinned image provenance. |

The backend improves confidentiality over the historical browser-direct database design, but Phase 1 must remain closed until F01-BE-SA-001 through F01-BE-SA-005 are resolved and exercised against real JWT validation plus Testcontainers PostgreSQL.
## Final security posture — 2026-07-29

- JWT subject/audience and active principal are server-authoritative.
- Organization, engagement and project permissions are resolved from active,
  effective-dated memberships and assignments.
- Cross-scope identifiers produce uniform denial/not-found behavior.
- Published configurations/policies and approval/transition/audit evidence
  reject update/delete; public function execution is revoked.
- Delegation is bounded by both users' active membership, the delegator's
  effective authority, scope, action, time window and stage policy.
- Approval actions bind actor subject, current stage, exact request project and
  original authority holder; one authority cannot contribute multiple quorum
  votes through delegates.
- Self-approval compares the requester with the original authority holder, so
  acting through a delegate does not bypass separation of duties.
- Request creation derives month type/scope/version/hash and required
  permission on the server; callers cannot forge generic evidence fields.
- Immutable per-request stage snapshots prevent later role, membership or
  contact-group drift from changing an in-flight electorate or quorum; `ALL`
  is derived from every request-time eligible authority.
- Captured evidence-required policy rejects blank action evidence before
  mutation.
- Future-effective policy revisions preserve the currently effective
  authorization window and use a non-overlapping handoff.
- Actor-scoped action idempotency prevents retry duplication and rejects
  attempts to reuse a key with changed decision/delegation content.
- Runtime grants and triggers reject direct request state mutation and reject a
  `REOPEN_REQUESTED` → `REOPENED` month update without the matching approved
  request; final approval performs both changes in one transaction.
- `PLATFORM_ADMIN`, `SUPPORT_OPERATOR` and `SERVICE_ACCOUNT` receive no
  implicit business-approval authority.

Residual risk is external identity operations: provider tenant configuration,
MFA, invitation/provisioning, BFF cookie controls and signing-key rotation must
be accepted in a controlled environment.
