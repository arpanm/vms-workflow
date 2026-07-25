# 19 — Research Findings and Integration Decisions

**Version:** 1.0
**Research date:** 25 July 2026
**Status:** Evidence and architecture rationale
**Related:** 06, 07, 13, 17, 20

---

## 1. Scope

Research covered:

- current public repository/application structure;
- official greytHR API/attendance administration capabilities;
- official Linear GraphQL, OAuth, SDK and webhook capabilities;
- implications for source authority, real-time status and historical evidence.

Only official vendor documentation is treated as authoritative for provider capabilities. Tenant-specific entitlements still require verification.

---

## 2. Existing application/repository findings

Observed public repository: `https://github.com/arpanm/vms-workflow`.

### Current structure

- React/TypeScript/TanStack/Vite frontend with Supabase.
- Routes: dashboard, engagements, requirements, scope, approvals, UAT and invoices.
- One Supabase migration: `20260509073727_a3ac9a00-fe4c-49cd-8422-811480f9010d.sql`.
- Current business tables: engagements, requirements, approvals, UAT items and invoices.
- Current RLS policies allow anonymous full access to those prototype tables.
- Current role selection is a local demo role switcher, not authenticated RBAC.

### Decision

Extend the application in place but treat authentication/RLS as phase-zero/phase-one work. The existing pages/tables are legacy fixed-cost workflow artifacts and are not semantically sufficient for workforce attendance, deliverable snapshots or verified confirmation.

Repository URLs:

- `https://github.com/arpanm/vms-workflow`
- `https://github.com/arpanm/vms-workflow/tree/main/src/routes`
- `https://github.com/arpanm/vms-workflow/tree/main/supabase/migrations`

---

## 3. greytHR official findings

### 3.1 API and authentication

Official greytHR API V2 documentation presents a tenant client-token endpoint in the form:

`https://<tenant>.greythr.com/uas/v1/oauth2/client-token`

It describes client credentials/basic authentication to obtain a bearer token. The public API overview includes employee, leave and attendance modules; the employee section states that APIs can retrieve all/individual employee data and add/update employee details.

Source: `https://api-docs.greythr.com/`

### 3.2 Attendance/leave administration

Official admin help documents:

- attendance policies/schemes/settings;
- office/geofence/GPS/selfie/visage and other marking methods;
- elapsed timer/device controls;
- raw swipe review/resync and attendance swipe API key generation;
- manual attendance processing when swipes are unavailable;
- manual override, split shifts, attendance exceptions and finalization;
- regularization/permission policy;
- attendance cycles/calendars, holidays and working-day/weekend overrides;
- leave calendar/reports and manager permissions.

Representative official sources:

- `https://admin-help.greythr.com/admin/143678554/` — Attendance settings.
- `https://admin-help.greythr.com/admin/answers/122759105/` — Process attendance manually.
- `https://admin-help.greythr.com/admin/answers/123712498/` — Manual attendance override.
- `https://admin-help.greythr.com/admin/answers/145047432/` — Regularization/permission policy.
- `https://admin-help.greythr.com/admin/w4qcbirgreamckcsg6bnjw/` — Leave/attendance reports.

### 3.3 Unknowns

Public documentation does not prove:

- ArrowFoundry tenant plan/API entitlement;
- exact attendance/leave/calendar endpoints and field coverage available to that tenant;
- webhook/incremental-change support;
- whether swipe write API is permitted for this integration;
- finalization/regularization write-back behavior;
- rate limits and contractual restrictions.

### 3.4 Decisions

1. Build full internal attendance capability regardless, because it is required if integration is unavailable.
2. Add a tenant capability-certification wizard and matrix before `GREYTHR_AUTHORITATIVE` can be enabled.
3. Support four source modes: greytHR authoritative, internal authoritative, hybrid transition and historical import.
4. Keep exactly one authoritative source per employee-day; preserve and reconcile both sources where present.
5. Default to read/sync or internal authority, not unverified dual-write.
6. Use approved CSV/export fallback for historical/outage periods.
7. Allowlist imported fields and exclude salary/payroll data.

---

## 4. Linear official findings

### 4.1 GraphQL and SDK

Linear's public API is GraphQL at:

`https://api.linear.app/graphql`

Official documentation recommends OAuth2 for applications and the TypeScript SDK for typed access. It supports querying issue metadata and workflow state.

Sources:

- `https://linear.app/developers/graphql`
- `https://linear.app/developers/sdk.md`

### 4.2 OAuth

Official documentation:

- recommends OAuth2;
- supports read/write and narrower scopes;
- supports `actor=app` for agents/service accounts;
- supports state and PKCE;
- states OAuth applications moved to the refresh-token system on 1 April 2026;
- describes refresh-token and client-credentials behavior.

Sources:

- `https://linear.app/developers/oauth-2-0-authentication`
- `https://linear.app/developers/oauth-actor-authorization`

### 4.3 Webhooks

Official documentation states:

- data-change webhooks include issues and related objects;
- endpoint must be public HTTPS and return HTTP 200;
- delivery is considered failed when unavailable, non-200 or slower than five seconds;
- failed pushes are retried with backoff and a persistently failing webhook may be disabled;
- webhook authenticity must be verified;
- official SDK offers a typed, signature-verified webhook helper and warns that raw body must be preserved.

Sources:

- `https://linear.app/developers/webhooks`
- `https://linear.app/developers/sdk-webhooks`

### 4.4 Freshness guidance

Linear recommends webhooks for near-real-time issue updates rather than excessive polling; if polling is needed, query recent changes/filter/paginate.

Source: `https://linear.app/developers/graphql#fetching-updates`

### 4.5 Rate limits and evolving API

Linear documents rate limiting and exposes response information; its GraphQL API evolves through deprecations rather than a conventional versioned REST API.

Sources:

- `https://linear.app/developers/rate-limiting`
- `https://linear.app/developers/deprecations`

Numeric limits may change and documentation views can differ, so implementation must obey response headers/current docs rather than hard-code a historical number.

### 4.6 Decisions

1. Production uses OAuth app/service actor and least-privilege read scope initially.
2. Use official SDK or strongly typed GraphQL.
3. Resolve issue URL/identifier to immutable UUID and store both.
4. Webhook-first current-state updates plus nightly delta reconciliation and manual refresh.
5. Verify raw-body signature/timestamp/replay; durably enqueue and respond within five seconds.
6. Preserve provider state name and map to normalized categories through configuration.
7. Capture plan-time and month-end snapshots; current state never rewrites them.
8. Linear `Done`/`Completed` is evidence only and never business certification.
9. Historical current API state is labeled current-only unless issue history/export proves earlier state.
10. Monitor OAuth revoke/webhook health and preserve stale last-known state with warning.

---

## 5. Email confirmation research/decision boundary

The user requirement is provider-independent: send consolidated email and record Reliance product-person confirmation. The actual mail platform/tenant permissions were not specified.

Decision:

- implement an email-adapter contract and secure in-app/email-link confirmation first;
- support provider webhook/subscription or controlled polling for a dedicated mailbox when tenant access is available;
- preserve message IDs/thread/authentication metadata;
- allow manual original-email evidence with second review for history/fallback;
- never interpret delivery/read receipt or silence as confirmation;
- keep provider selection and permissions in PRD 20.

---

## 6. Architecture implications

- Provider calls happen server-side; browser displays normalized data.
- Integration connections are versioned, secret-referenced and health monitored.
- Current provider state and procurement evidence snapshots are separate data products.
- Webhook/sync/import processing is idempotent and replayable.
- Provider outages degrade with explicit stale/conflict state, not hidden fallback.
- Source provenance/connection/policy versions are included in evidence manifest.

---

## 7. Research caveats

- Official documentation can change after this research date.
- Before implementation, Cursor/Claude should verify current SDK/API schemas and generate typed operations from the live official schema.
- ArrowFoundry must provide greytHR tenant/API access and approve field/source mapping.
- Reliance must approve the Linear OAuth app/workspace/team scope and webhook endpoint.
- Reliance/ArrowFoundry must identify the email platform and controlled mailbox/SSO approach.
- No source reviewed authorizes storing salary/markup; the product explicitly excludes them.
