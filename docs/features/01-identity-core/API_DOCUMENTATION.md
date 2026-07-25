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

Configuration:

- `VMS_DATABASE_URL`
- `VMS_DATABASE_USERNAME`
- `VMS_DATABASE_PASSWORD`
- `VMS_OIDC_JWKS_URI`
- `VMS_OIDC_AUDIENCE`
- `VMS_OIDC_ISSUER`

Secrets must be supplied through deployment configuration, never frontend `VITE_*` values. This slice is a resource server; it does not yet implement browser OIDC authorization/login/logout.
