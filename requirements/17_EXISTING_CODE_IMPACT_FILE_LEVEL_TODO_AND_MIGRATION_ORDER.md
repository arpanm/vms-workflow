# 17 — Existing Code Impact, File-Level TODO and Migration Order

**Version:** 1.0
**Status:** Cursor/Claude implementation guide
**Repository inspected:** `arpanm/vms-workflow`, public `main`, 25 July 2026
**Related:** 00, 13-16

---

## 1. Current-state findings

### Stack

- React 19 + TypeScript.
- TanStack Start/Router/Query with Vite.
- Tailwind/Radix/shadcn-style UI components.
- Supabase client/PostgreSQL.
- Current root folders: `.lovable`, `public`, `src`, `supabase`.

### Existing routes

- `src/routes/index.tsx` — dashboard.
- `src/routes/engagements.tsx`.
- `src/routes/requirements.tsx`.
- `src/routes/scope.tsx`.
- `src/routes/approvals.tsx`.
- `src/routes/uat.tsx`.
- `src/routes/invoices.tsx`.
- `src/routes/__root.tsx` — shell/navigation/role selector.

### Current data/access

- One migration: `supabase/migrations/20260509073727_a3ac9a00-fe4c-49cd-8422-811480f9010d.sql`.
- Five principal tables: `engagements`, `requirements`, `approvals`, `uat_items`, `invoices`.
- Current migration enables RLS but grants anonymous full access through permissive policies.
- Current role store is a local/demo role selection persisted in browser storage; it is not production authorization.
- Current data hooks perform direct Supabase reads for the five tables and dashboard aggregation.
- Current pages are mostly read-oriented prototype views and do not implement the new end-to-end mutations/workflows.

### Immediate risk

Do not load employee/attendance/invoice evidence into the current schema until authentication and RLS replacement are deployed and tested.

---

## 2. Implementation strategy

- Keep current framework and component library.
- Introduce domain modules/services and typed schemas instead of adding all logic to route files.
- Apply additive migrations first; preserve legacy tables/routes behind a feature flag.
- Build production Auth/RBAC/RLS before workforce data.
- Migrate legacy data explicitly; do not rename `requirements` to `deliverables` and assume semantic equivalence.
- Generate Supabase TypeScript types after every schema wave.
- Use server functions/Edge Functions for privileged/provider/file/package actions.
- Add tests alongside each phase.

---

## 3. Proposed source structure

```text
src/
  components/
    app-shell/
    data-table/
    evidence/
    approvals/
    workforce/
    delivery/
    finance/
    integrations/
    ui/                 # existing standard components
  features/
    auth/
    organizations/
    engagements/
    workforce/
      employees/
      allocations/
      calendars/
      leave/
      attendance/
    delivery/
      plans/
      deliverables/
      linear/
      submissions/
      certifications/
    confirmations/
    evidence-packages/
    invoices/
    procurement/
    migration/
    reports/
    admin/
  hooks/
    query-keys.ts
    use-permissions.ts
    use-current-scope.ts
    ...domain hooks
  lib/
    auth/
    permissions/
    domain-errors.ts
    validation/
    dates/
    hashing/
    feature-flags.ts
    env.ts
  server/
    services/
    repositories/
    integrations/
      greythr/
      linear/
      email/
    jobs/
    files/
    audit/
  integrations/supabase/
    client.ts
    server.ts
    types.ts            # generated
  routes/
    __root.tsx
    index.tsx
    login.tsx
    workforce/...
    delivery/...
    month-close/...
    finance/...
    reports/...
    admin/...
  tests/
    fixtures/
    unit/
    integration/
    e2e/

supabase/
  migrations/
  functions/
    linear-webhook/
    email-webhook/
    integration-sync/
    package-generate/
    export-generate/
  tests/
    rls/
    constraints/
  seed.sql
```

TanStack route generation should create `routeTree.gen.ts`; do not edit the generated file manually.

---

## 4. Route migration map

| Current route | Future behavior |
|---|---|
| `/` | Role-aware My Work / engagement-month dashboard |
| `/engagements` | Retain and expand under `/admin/engagements`; redirect or alias |
| `/requirements` | Legacy fixed-cost module; new canonical route `/delivery/plans` and `/delivery/deliverables` |
| `/scope` | Legacy scope/capacity module under feature flag; not primary for dedicated-resource evidence |
| `/approvals` | Replace/expand with `/my-work/approvals` plus object-specific approval panels |
| `/uat` | Legacy UAT; new `/delivery/certification`; migrate only explicit evidence |
| `/invoices` | Expand to `/finance/invoices`; preserve redirect |

New routes are listed in PRD 15. Use redirects preserving query/month context.

---

## 5. File-level TODO: existing files

### `src/routes/__root.tsx`

- Replace static sidebar with permission-aware navigation configuration.
- Replace production role dropdown with user/organization/engagement/month selector.
- Keep demo selector behind `VITE_DEMO_MODE` and visible demo banner.
- Add Auth guard/session loading, permission-denied and stale/integration banners.
- Add notification/task center and responsive shell.

### `src/routes/index.tsx`

- Replace five-table prototype aggregation with role-aware dashboard queries/views.
- Add engagement/month scope and evidence pillars.
- Keep current visual components only where metric definitions remain valid.

### `src/routes/engagements.tsx`

- Move logic into engagement feature/service.
- Add organization parties, model, projects, config versions, contacts, approval/source policy and month initialization.
- Restrict admin mutations by permission/RLS.

### `src/routes/requirements.tsx`

- Freeze as legacy or add redirect.
- Do not reuse existing `requirements` records as new deliverables without migration mapping.
- Extract reusable table/status components where useful.

### `src/routes/scope.tsx`

- Retain only for legacy fixed-cost scope planning behind `FEATURE_LEGACY_FIXED_COST`.
- Dedicated-resource monthly baseline comes from new plan/deliverable model.

### `src/routes/approvals.tsx`

- Replace single-list assumptions with generic approval inbox backed by approval requests/actions.
- Add scope, object/version, due/aging, delegation and deep links.

### `src/routes/uat.tsx`

- Retain legacy view/read mapping.
- Build new certification route/model; UAT status is not automatically certification.

### `src/routes/invoices.tsx`

- Move to new invoice/version/readiness/package/procurement workflow.
- Remove assumptions that UAT status alone approves invoice.
- Add file upload security, versioning and evidence checklist.

### Existing data hooks (`src/hooks/data-hooks.ts` or equivalent)

- Stop centralizing all domains in one hook file.
- Create stable query-key factory and per-domain hooks.
- Route writes through server functions/domain services.
- Add cancellation/error typing/permission and version handling.
- Do not fetch external Linear/greytHR directly from browser.

### Existing role store (`src/lib/role-store.ts` or equivalent)

- Rename to `demo-role-store.ts` and load only in demo mode.
- Add real auth/session/current-scope and permission hooks.
- Never use selected demo role in RLS/security decisions.

### `src/integrations/supabase/*`

- Separate browser and server clients.
- Validate env variables.
- Regenerate database types.
- Remove any service role from client environment.
- Add typed RPC/server interfaces as needed.

### `.env`

- Confirm it contains no live keys; rotate any committed secrets.
- Replace committed real values with `.env.example` placeholders.
- Add environment schema validation and deployment secret references.

### `package.json`

Add only justified dependencies, likely:

- official `@linear/sdk`;
- validation (`zod` if not present);
- date/time library with timezone support if needed;
- file/MIME/hash utilities on server;
- test tooling (Vitest, Playwright, Testing Library as needed);
- optional job/observability libraries approved for deployment.

Avoid replacing the framework or duplicating UI libraries.

---

## 6. New frontend modules/routes TODO

### Auth/scope

- [ ] `/login`, callback, invite acceptance, logout/session-expired.
- [ ] current organization/engagement/month context.
- [ ] `PermissionGate`/server permission checks.

### Workforce

- [ ] `/workforce/employees` and `/$employeeId`.
- [ ] allocations timeline.
- [ ] calendars/holidays/overrides.
- [ ] leave balances/ledger and requests.
- [ ] attendance today/calendar/detail.
- [ ] regularization/leave approval inbox.
- [ ] attendance exceptions and month close.

### Delivery

- [ ] plans list/builder/version comparison/approval.
- [ ] deliverable editor/detail.
- [ ] Linear issue picker/card/health.
- [ ] vendor submission.
- [ ] product-owner certification/clarification/carry-forward.

### Close/evidence/finance

- [ ] readiness dashboard.
- [ ] confirmation request/response/inbound review.
- [ ] evidence package viewer/lineage.
- [ ] invoices/readiness/procurement/payment.

### Admin/reports

- [ ] organizations/engagements/projects/contact groups.
- [ ] users/roles/delegations/policies.
- [ ] integrations/jobs/dead letter.
- [ ] import wizard/reconciliation.
- [ ] reports/exports/audit.

---

## 7. Migration-file order

Create additive, reviewable migrations; illustrative names:

1. `2026..._iam_organizations_engagements.sql`
2. `2026..._rbac_functions_and_rls_base.sql`
3. `2026..._projects_months_policies_contacts.sql`
4. `2026..._workforce_employee_allocation_calendar.sql`
5. `2026..._leave_ledger_requests.sql`
6. `2026..._attendance_events_sessions_days.sql`
7. `2026..._plans_deliverables_linear.sql`
8. `2026..._delivery_submission_certification.sql`
9. `2026..._notifications_confirmations.sql`
10. `2026..._evidence_packages_invoice_procurement.sql`
11. `2026..._integration_jobs_import_audit_outbox.sql`
12. `2026..._reporting_views_indexes.sql`
13. `2026..._storage_policies.sql`
14. `2026..._legacy_mapping_and_seed.sql`
15. `2026..._remove_anon_policies_and_final_grants.sql`

In practice, anonymous policy removal/security must occur before any sensitive production data. It can be an earlier dedicated migration; the numbered list separates logical concerns, not permission to defer security.

Every migration has:

- forward SQL;
- data precondition checks;
- no destructive drop in initial waves;
- RLS/policy and index review;
- local reset and staging test;
- migration checksum in source control.

Use `supabase/seed.sql` for synthetic/reference seed data, not production secrets or real employee data.

---

## 8. Legacy data mapping

### Engagements

- Map existing engagement row to new engagement and parties after organization seeds.
- Preserve legacy ID in mapping table/source metadata.

### Requirements

Candidate mapping to deliverables only if:

- month/project/owner can be resolved;
- acceptance criteria and baseline version can be reconstructed;
- migration review approves.

Otherwise keep as legacy records and link/reference.

### Approvals

Map to generic approval actions only when actor/object/decision/timestamp evidence is sufficient. Do not infer authority.

### UAT items

Map as delivery evidence/certification only when the product-owner decision is explicit. Otherwise import as legacy UAT evidence, not accepted certification.

### Invoices

Map file/number/status to invoice version with `LEGACY_APP` provenance; readiness is recalculated against new evidence.

---

## 9. Server functions/Edge Functions TODO

- [ ] `linear-oauth-start/callback/refresh/revoke`.
- [ ] `linear-webhook` raw-body verification + enqueue.
- [ ] greytHR test/sync/backfill adapter jobs.
- [ ] email send and inbound webhook/poll processing.
- [ ] secure confirmation token action.
- [ ] file upload finalize/scan callback.
- [ ] package generation.
- [ ] large export generation.
- [ ] scheduled reminders/accruals/reconciliation/retention.

Each validates caller/provider, uses idempotency, writes audit and returns sanitized errors.

---

## 10. Query/data pattern TODO

- Create query-key factory: `organizations`, `engagements`, `months`, `employees`, `attendance`, `plans`, `deliverables`, `certifications`, `confirmations`, `packages`, `invoices`, `jobs`.
- Server-side pagination/filtering and selected columns.
- Central domain error mapping and toast/form rendering.
- Mutations invalidate narrow keys, not all data.
- Current/snapshot/version params are explicit.
- Realtime subscriptions only for high-value task/status updates; do not use as security or replace reconciliation.

---

## 11. Test files TODO

```text
supabase/tests/rls/*.sql
supabase/tests/constraints/*.sql
src/tests/unit/attendance-calculation.test.ts
src/tests/unit/leave-ledger.test.ts
src/tests/unit/month-state-machine.test.ts
src/tests/unit/readiness-rules.test.ts
src/tests/integration/linear-webhook.test.ts
src/tests/integration/greythr-sync.test.ts
src/tests/integration/email-confirmation.test.ts
src/tests/integration/imports.test.ts
src/tests/e2e/employee-attendance.spec.ts
src/tests/e2e/month-plan-certification.spec.ts
src/tests/e2e/confirmation-invoice.spec.ts
src/tests/e2e/historical-backfill.spec.ts
src/tests/e2e/rbac-cross-tenant.spec.ts
```

Use provider fixtures with secrets removed and schema versions recorded.

---

## 12. Suggested implementation commits

1. Baseline/security/env/feature flags.
2. Auth/organizations/RBAC/RLS/tests.
3. Engagement/project/month/config/admin shell.
4. Employee/allocation/calendar/leave ledger.
5. Internal attendance/regularization/close.
6. greytHR adapter/reconciliation.
7. Plans/deliverables/approvals/commitment email.
8. Linear OAuth/webhooks/snapshots.
9. Delivery submission/certification.
10. Confirmation/email ingestion.
11. Evidence packages/invoices/Procurement/payment.
12. Historical imports/templates.
13. Dashboards/reports/exports/audit.
14. Hardening/performance/accessibility/runbooks/cutover.

Keep commits deployable behind flags and database-compatible with rollback.

---

## 13. Cursor/Claude execution rules

- Read `00_INDEX_IMPLEMENTATION_TODO.md` and dependency PRDs before coding a phase.
- Do not invent product rules where PRD 20 marks a tenant decision; implement configurable defaults and surface setup blocker.
- Do not skip RLS/audit/tests to make UI appear complete.
- Do not edit generated route tree/types manually.
- Do not put provider secrets or calls in client components.
- Do not drop legacy tables until migration acceptance and rollback window closes.
- Add an ADR when deviating materially from this pack.
- Update the master TODO and traceability test IDs in the same pull request.

---

## 14. Completion verification

- Production build contains no client-side service key/greytHR/Linear secret.
- `VITE_DEMO_MODE=false` removes role impersonation UI.
- All new routes/actions are permission tested.
- Supabase migration reset succeeds locally from zero.
- Staging migration from current schema succeeds with legacy rows preserved.
- RLS tests prove cross-tenant isolation.
- End-to-end journeys in PRD 16 pass.
- Historical June templates import and package correctly.
- No current anonymous full-access policy remains.
