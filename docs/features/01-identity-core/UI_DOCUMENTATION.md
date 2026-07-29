# F01 UI Flow and Use Guide

## Demo mode

Set `VITE_DEMO_MODE=true` only in a non-production build. The banner remains visible and the persona selector affects presentation only. It never changes JWT claims, memberships or API authorization.

## Production/session mode

1. The app requests `GET /api/v1/me`.
2. An authenticated response opens the application shell.
3. HTTP 401 routes the user to `/login`.
4. Sign-in remains disabled unless `VITE_OIDC_LOGIN_PATH` names a configured same-origin BFF login entry point.
5. The BFF/provider must validate `returnTo` independently; the SPA also rejects unsafe redirect forms.

## Active scope and navigation

The shell resolves authorized organizations, engagements and months from the
Java API. A saved selection contains IDs only and is restored only when the
authenticated identity, membership/effective-date set and permission set are
unchanged. An authority change clears F01 query caches before resolving a new
selection.

Navigation is derived from `/me.permissions`, not the demo persona. Direct
navigation to an unavailable administration capability shows a generic
permission-denied state without loading the protected collection.

## Core administration

- **Organizations & engagements** shows the active engagement, immutable tenant
  parties, optimistic administration version and effective configuration
  history. A new configuration is prospective, effective-dated and published
  against the displayed engagement version.
- **Contact groups** creates scoped groups and effective members with verified
  email and role attribution. The displayed group version is required when
  adding a member.
- **Approval policies & delegations** builds ordered policy stages and quorums,
  publishes immutable policy versions, creates the next future-effective draft
  revision under the same policy identity, selects only server-returned
  eligible users, and creates/revokes effective-dated delegations.
- **Approval requests** creates an idempotent governed reopen from a published
  engagement-scoped `REOPEN` policy and a reopen-requested month. It does not
  ask the browser to provide object type/version/hash/scope evidence; the server
  derives those values. Detail shows the snapshotted stages, server-returned
  quorum state and attributable action history. An action may use only a
  currently returned delegation matching the stage permission and submits the
  displayed request version plus an actor-scoped idempotency key. The reason
  field is mandatory when the captured request says evidence is required.
  `ALL` displays the request-time derived quorum, not a stale policy-design
  count.
- **Month governance** shows only supported administrative transition targets,
  requires a reason and explicit impact confirmation, and renders append-only
  actor/version/correlation history.

Loading, empty, denied and error states are explicit. A 409/412 response is
presented as stale data with a reload action; no overwrite control is offered.

## Legacy compatibility pages

When `VITE_FEATURE_LEGACY_FIXED_COST=true`, the existing dashboard, engagements, requirements, scope, approvals, UAT and invoice URLs remain visible. They load read-only PostgreSQL compatibility snapshots.

- Requirements cannot be created or changed.
- Approvals have no approve/reject/auto-approve actions.
- Silence/deemed-acceptance statuses display as requiring explicit review.
- API failures show an actionable error and correlation ID when available.

When the legacy flag is false, navigation hides those routes and deep links return a safe unavailable state.

## Known deployment requirement

The backend validates JWTs but has no browser login/BFF endpoint. Select the identity provider, implement/configure the BFF flow, then execute browser E2E login/logout and tenant tests before production.

The policy designer and approval inbox never represent a draft, elapsed timeout
or a client-counted action as approval. Request completion and quorum are shown
only from the server-returned approval request. Final approval is reflected only
after the backend atomically reopens the bound month.
## F01 administration completion — 2026-07-29

The application shell derives available administration links from
`/api/v1/me.permissions` and preserves the current organization, engagement
and month scope. Administration routes cover engagements/configuration,
contact groups, approval policies/delegations and governed month transitions.
Forms expose loading, empty, denied, validation and optimistic-conflict
feedback; they do not infer approval authority or offer production role
switching.

The permanent browser-contract lane is `e2e/core-admin.spec.ts` with
deterministic API fixtures. It validates navigation visibility and core admin
forms, but is explicitly not provider-backed login or Java/PostgreSQL system
acceptance.
