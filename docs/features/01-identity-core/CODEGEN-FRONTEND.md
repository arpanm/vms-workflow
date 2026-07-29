# F01 Frontend Codegen — Vite SPA and Java API Boundary

**Requirements:** RQ-033, RQ-034; F01-T09, F01-T10, F01-T13, F01-T14; T-IAM-007, T-IAM-010
**Architecture decision supplied during implementation:** replace the Lovable, Supabase, Cloudflare and TanStack Start frontend runtime with a standard React 19 + Vite + TanStack Router/Query SPA backed by the Java API under `/api/v1`.

## Implemented

- Replaced the TanStack Start/Cloudflare server entry with `index.html`, `src/main.tsx` and a standard Vite plugin configuration.
- Removed all frontend Supabase clients, generated Supabase types, auth middleware, migrations/config, Lovable metadata and Cloudflare runtime configuration.
- Removed the tracked legacy `.env` and added ignore rules so only `.env.example` remains versioned.
- Replaced the stale Bun dependency lock with npm dependency metadata.
- Added a typed fetch boundary:
  - `ApiError` carries HTTP status, backend error code and correlation ID.
  - requests use same-origin credentials by default;
  - an optional in-memory bearer-token provider supports a future OIDC authorization-code + PKCE client without storing tokens in `localStorage`;
  - network, JSON error and empty-response cases are normalized.
- Added an OIDC-ready session boundary:
  - `GET /api/v1/me` resolves the current authenticated principal;
  - unauthenticated protected routes redirect to `/login`;
  - sign-in is enabled only when `VITE_OIDC_LOGIN_PATH` explicitly names a
    same-origin BFF login entry point;
  - sign-out calls the configurable backend logout endpoint;
  - the SPA does not derive authorization from the demo role.
- Added the canonical F01 administration client and UI:
  - runtime-validated TypeScript contracts for engagement administration,
    immutable effective-dated configurations, contact groups/members, approval
    policies/stages, approval requests/actions, eligible users, delegations,
    engagement months and append-only transition history;
  - `/administration/engagements`,
    `/administration/contact-groups`,
    `/administration/approval-policies`, `/administration/approval-requests`
    and `/administration/months`;
  - every consequential mutation sends the displayed optimistic version and
    treats HTTP 409/412 as a reload/compare condition, never an overwrite;
  - contact members retain verification, role attribution and effective-date
    fields, configuration/policy publishing is distinct from mutable drafts,
    and approval requests are bound to a published governed-reopen policy,
    server-selected month evidence and a retry-stable idempotency key;
  - approval actions show direct/delegated attribution, send the selected
    delegation and displayed request version, and render only the
    server-returned quorum/request state;
  - month administration offers only the server-supported administrative
    transitions and requires a reason plus consequence confirmation.
- Hardened the final approval contract:
  - the request form offers only published engagement-scoped `REOPEN` policies
    and reopen-requested months, and sends only `policyId`, month `objectId` and
    a retry-stable idempotency key; type/version/hash/scope remain server-owned;
  - every action sends an actor-scoped idempotency key in addition to the
    displayed request version and optional delegation;
  - the policy card can create a next draft revision under the same policy
    identity with an explicit future effective date.
- Added a global active organization/engagement/month selector. Its saved IDs
  are bound to a fingerprint of the authenticated identity, memberships,
  effective dates and server permissions. Authority change removes cached F01
  administration data and restores no selection from the prior authority.
- Replaced demo-role navigation decisions with server permission checks.
  Unauthorized deep links render a non-disclosing permission state and do not
  issue the protected administration collection request.
- Migrated legacy reads to:
  - `GET /api/v1/legacy/engagements`
  - `GET /api/v1/legacy/requirements`
  - `GET /api/v1/legacy/approvals`
  - `GET /api/v1/legacy/uat-items`
  - `GET /api/v1/legacy/invoices`
- Kept every legacy compatibility collection read-only. The Requirements page
  has no creation CTA, form or mutation client.
- Added loading and typed error/correlation states to legacy screens.
- Kept `/engagements`, `/requirements`, `/scope`, `/approvals`, `/uat` and `/invoices` behind `VITE_FEATURE_LEGACY_FIXED_COST`, including deep-link guards.
- Kept the persona switcher only in non-production safe demo mode, moved demo selection to session storage, and added a persistent warning that persona selection never grants backend permissions.
- Removed all approval mutation buttons from the legacy approval page.
- Removed messages and metrics that represented elapsed time or silence as approval/UAT acceptance. Historical unsafe statuses render as `Legacy status: explicit review required`.

## Tests added

- API URL, credential and optional bearer-header construction.
- Typed backend error and correlation-ID mapping.
- OIDC return-path rejection for absolute/protocol-relative paths, raw and
  encoded backslashes, control characters and repeated encodings.
- Memory-only access-token lifecycle.
- Production environment demo-mode rejection.
- Legacy-route flag behavior.
- Unsafe historical status presentation.
- Authority-bound scope restore/reset, malformed-storage failure, stale catalog
  reconciliation and permission-derived presentation.
- Runtime Java-response validation and exact core administration URL/body
  contracts, including group, policy and month governance versions.
- Browser contracts `E2E-F01-BC-010` through `E2E-F01-BC-018` cover persisted scope
  invalidation, denied deep links, contact member concurrency, stale month
  transition refusal, prospective configuration publishing, server-derived
  reopen creation, idempotent delegated attribution without fabricated quorum,
  stale approval refusal and approval-detail denial without a protected fetch.

These are DB-independent frontend tests. They do not prove Java authorization, OIDC provider configuration, tenant isolation or backend security.

## Backend contract assumptions

- Compatibility collection endpoints return JSON arrays.
- `GET /api/v1/me` returns `{ id, subject, email, displayName, memberships,
  organizationIds, permissions }` or HTTP 401. Navigation and mutation
  affordances use only `permissions`; the selector never establishes authority.
- An externally configured same-origin BFF login endpoint accepts a validated
  `returnTo` query parameter. The current resource-server backend does not
  provide this endpoint.
- Session cookies, when used, are HttpOnly/Secure/SameSite and protected by the Java backend.
- Future backend mutations remain responsible for permission checks, CSRF
  protection where applicable, audit and tenant scoping.

## Remaining integration risks

- `VITE_OIDC_LOGIN_PATH` remains an explicit release blocker until a
  same-origin BFF login endpoint is implemented and configured.
- Validate the principal payload and logout behavior against that BFF.
- Run browser-level login/logout and feature-flag navigation tests with the Java service.
- Confirm production SPA hosting rewrites unknown paths to `index.html`.
- Production dependency audit reports zero findings. The full audit reports five
  high-severity development-tool findings through ESLint/minimatch; npm only
  offers an ESLint 10 major upgrade. No forced major-version audit fix was
  applied during this migration.
