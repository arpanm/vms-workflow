# F01 API Documentation

Base path: `/api/v1`
Authentication: RS256 bearer JWT validated for exact issuer, signature/JWKS, time window and configured audience
Errors: RFC 7807 `application/problem+json`

Runtime OpenAPI is generated from controllers at `/v3/api-docs`; Swagger UI is at `/swagger-ui.html`. Both are authenticated.

| Method | Path | Scope rule |
|---|---|---|
| GET | `/me` | Active profile, membership and current `catalog.read` assignment matching JWT `sub` |
| GET | `/organizations` | Organization-scoped `catalog.read` |
| GET | `/organizations/{id}` | Organization-scoped `catalog.read` |
| GET | `/engagements?organizationId={uuid}` | Organization-scoped `catalog.read` in the requested participating organization |
| GET | `/engagements/{id}` | Organization- or engagement-scoped `catalog.read` in a participating organization |
| GET | `/projects?engagementId={uuid}` | Full list for organization/engagement scope; assigned subset for exact-project scope |
| GET | `/projects/{id}` | Organization-, engagement-, or exact-project-scoped `catalog.read` |
| GET | `/engagement-months?engagementId={uuid}` | Organization- or engagement-scoped `catalog.read` |
| GET | `/engagement-months/{id}` | Organization- or engagement-scoped `catalog.read` |
| GET | `/legacy/{collection}` | Union of organization-scoped `catalog.read` memberships, or an authorized `organizationId` |

Allowed legacy collections are `engagements`, `requirements`, `approvals`, `uat-items` and `invoices`. They are immutable GET-only compatibility snapshots. Table selection uses a server allowlist.

## Core administration API

All paths below are relative to `/api/v1/core`. Reads require an active
engagement membership and return a uniform not-found response when the scope is
unknown or unauthorized. Mutations re-check the named permission on the server;
UI visibility is not an authorization boundary.

| Method | Path | Purpose / mutation permission |
|---|---|---|
| GET, PATCH | `/engagements/{engagementId}` | Read engagement administration; update with `engagement.update` and the displayed version |
| GET, POST | `/engagements/{engagementId}/configurations` | List immutable versions; publish a prospective version with `engagement.configure` and `expectedEngagementVersion` |
| GET | `/engagements/{engagementId}/configurations/effective?effectiveOn={date}` | Resolve the configuration effective on a represented date |
| GET, POST | `/engagements/{engagementId}/contact-groups` | List groups; create with `contacts.manage` |
| POST | `/contact-groups/{groupId}/members` | Add an effective member with `contacts.manage` and `expectedGroupVersion` |
| GET | `/engagements/{engagementId}/eligible-users?organizationId={uuid}` | Minimal active candidates from a participating organization |
| GET, POST | `/engagements/{engagementId}/approval-policies` | List executable policies; create a draft with `approval.policy.manage` |
| POST | `/approval-policies/{policyId}/revisions` | Create the next immutable draft version under the same policy identity with `approval.policy.manage` and `expectedPolicyVersion` |
| POST | `/approval-policies/{policyId}/publish` | Validate and publish an immutable policy with `approval.policy.manage` and `expectedVersion`; future revisions close, but do not prematurely disable, the current effective window |
| GET, POST | `/engagements/{engagementId}/approval-requests` | List requests; create an idempotent governed reopen request with `approval.request.create` |
| GET | `/approval-requests/{requestId}` | Read the bound policy stages, quorum state and attributable actions |
| POST | `/approval-requests/{requestId}/actions` | Act idempotently with `approval.request.act`, optional valid delegation, `expectedRequestVersion`, and an actor-scoped `idempotencyKey`; the server alone evaluates eligibility and quorum |
| GET, POST | `/engagements/{engagementId}/delegations` | List delegations; create with `delegation.manage` |
| POST | `/delegations/{delegationId}/revoke` | Revoke with `delegation.manage` and the displayed version |
| GET, POST | `/engagement-months/{monthId}/transitions` | Read append-only history; perform a supported transition with `month.transition`, reason, confirmation and expected month version |

The public approval-request input is deliberately narrow: `policyId`,
`objectId` (an engagement-month ID) and `idempotencyKey`. The current public
engine accepts only an engagement-scoped published `REOPEN` policy and a month
whose current state is `REOPEN_REQUESTED`. The server resolves and stores the
object type, engagement/project scope, current object version, evidence hash
and required `month.transition` permission. It rejects caller-supplied generic
object evidence instead of trusting it.

At request creation the server snapshots each stage's eligible authority IDs,
contact-group version, quorum and delegation rule. For `ALL`, the captured
quorum equals the complete request-time eligible set. Later membership, role or
group changes cannot rewrite the pending request's electorate. Actions accept
only `APPROVED`, `REJECTED`, `CHANGES_REQUESTED`, or `CANCELLED`; exact retries
with the same actor/idempotency key return the existing action result, while
different content conflicts. Quorum counts the original authority once across
direct and delegated votes. A policy that prohibits self-approval compares the
requester with that original authority, so delegation cannot bypass the rule.
`evidenceRequired` is returned on the request; when true, the action must carry
a nonblank reason, which becomes immutable action evidence.
Final approval is the sole service path that atomically advances the bound
month from `REOPEN_REQUESTED` to `REOPENED`.

Configuration:

- `VMS_DATABASE_URL`
- `VMS_DATABASE_USERNAME`
- `VMS_DATABASE_PASSWORD`
- `VMS_OIDC_JWKS_URI`
- `VMS_OIDC_AUDIENCE`
- `VMS_OIDC_ISSUER`

Secrets must be supplied through deployment configuration, never frontend `VITE_*` values. This slice is a resource server; it does not yet implement browser OIDC authorization/login/logout.
## Core administration API — 2026-07-29

All paths require an authenticated JWT and return RFC 7807 problem details
with a correlation ID on failure.

| Method and path | Purpose |
|---|---|
| `GET/PATCH /api/v1/core/engagements/{engagementId}` | Read/update authorized engagement master data using `expectedVersion` |
| `GET/POST /api/v1/core/engagements/{engagementId}/configurations` | List/publish immutable effective-dated configuration |
| `GET /api/v1/core/engagements/{engagementId}/configurations/effective?effectiveOn=` | Resolve represented-date configuration |
| `GET/POST /api/v1/core/engagements/{engagementId}/contact-groups` | List/create versioned contact groups |
| `POST /api/v1/core/contact-groups/{groupId}/members` | Add a verified participant member with expected group version |
| `GET /api/v1/core/engagements/{engagementId}/eligible-users?organizationId=` | List active participant users eligible for delegation selection |
| `GET/POST /api/v1/core/engagements/{engagementId}/approval-policies` | List/create staged policies |
| `POST /api/v1/core/approval-policies/{policyId}/revisions` | Create the next draft version under the stable policy ID |
| `POST /api/v1/core/approval-policies/{policyId}/publish` | Validate quorum and publish immutable policy version |
| `GET/POST /api/v1/core/engagements/{engagementId}/approval-requests` | List/create server-derived, idempotent governed reopen requests |
| `GET /api/v1/core/approval-requests/{requestId}` | Read scoped stages and immutable actions |
| `POST /api/v1/core/approval-requests/{requestId}/actions` | Record current-stage direct/delegated action and evaluate quorum |
| `GET/POST /api/v1/core/engagements/{engagementId}/delegations` | List/create bounded effective-dated delegations |
| `POST /api/v1/core/delegations/{delegationId}/revoke` | Revoke with expected version and reason |
| `GET/POST /api/v1/core/engagement-months/{monthId}/transitions` | Read history/perform allowlisted optimistic transition |

`GET /api/v1/me` includes `organizationIds` for compatibility and the
server-derived `permissions` array used only to hide unavailable navigation.
The executable source of truth is `/v3/api-docs`.

Approval request decisions are `APPROVED`, `REJECTED`,
`CHANGES_REQUESTED` or `CANCELLED`. Expected-version conflicts include a stable
`code` and `currentVersion`; direct/delegated self-action, request/action
idempotency-key reuse, missing required evidence, duplicate authority action and invalid month
transitions have distinct codes.
