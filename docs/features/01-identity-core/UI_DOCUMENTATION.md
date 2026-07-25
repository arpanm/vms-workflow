# F01 UI Flow and Use Guide

## Demo mode

Set `VITE_DEMO_MODE=true` only in a non-production build. The banner remains visible and the persona selector affects presentation only. It never changes JWT claims, memberships or API authorization.

## Production/session mode

1. The app requests `GET /api/v1/me`.
2. An authenticated response opens the application shell.
3. HTTP 401 routes the user to `/login`.
4. Sign-in remains disabled unless `VITE_OIDC_LOGIN_PATH` names a configured same-origin BFF login entry point.
5. The BFF/provider must validate `returnTo` independently; the SPA also rejects unsafe redirect forms.

## Legacy compatibility pages

When `VITE_FEATURE_LEGACY_FIXED_COST=true`, the existing dashboard, engagements, requirements, scope, approvals, UAT and invoice URLs remain visible. They load read-only PostgreSQL compatibility snapshots.

- Requirements cannot be created or changed.
- Approvals have no approve/reject/auto-approve actions.
- Silence/deemed-acceptance statuses display as requiring explicit review.
- API failures show an actionable error and correlation ID when available.

When the legacy flag is false, navigation hides those routes and deep links return a safe unavailable state.

## Known deployment requirement

The backend validates JWTs but has no browser login/BFF endpoint. Select the identity provider, implement/configure the BFF flow, then execute browser E2E login/logout and tenant tests before production.
