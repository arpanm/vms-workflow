# 20 — Assumption Register, Configuration Defaults and Decision Log

**Version:** 1.0
**Status:** Product/architecture control document
**Related:** All PRDs

---

## 1. Purpose

Separate confirmed requirements from configurable defaults and tenant-specific unknowns so Cursor/Claude can build without embedding guesses. Unknowns must become setup/configuration tasks, not blocking code questions or hard-coded behavior.

---

## 2. Confirmed requirements

| ID | Requirement/decision | Source/status |
|---|---|---|
| C-001 | Existing application is to be extended, not replaced without reason. | Confirmed by user |
| C-002 | ArrowFoundry initially deploys 26 full-time people to Reliance Intelligence. | Confirmed |
| C-003 | Initial team/project context is NAM / Agentic ShopOS; later projects/teams are possible. | Confirmed |
| C-004 | Engagement began on 1 June 2026; historical data/approvals/confirmations must be supported. | Confirmed |
| C-005 | Monthly invoice proof requires attendance, pre-month deliverables, end-month delivery approval and consolidated email confirmation. | Confirmed |
| C-006 | Commitment/certification/confirmation emails include ArrowFoundry and Reliance product persons, with Central Procurement CC. | Confirmed |
| C-007 | Admin manages employee lifecycle, projects/deliverables, weekly offs/working exceptions, holidays and leave balances/accruals. | Confirmed |
| C-008 | Employees can check in/out; nine hours is mandatory for full attendance under initial policy, otherwise leave/LWP/regularization resolution applies. | Confirmed; detailed calculation configurable |
| C-009 | ArrowFoundry uses greytHR; integrate if tenant capability permits, otherwise internal attendance is required. | Confirmed |
| C-010 | Reliance Intelligence uses Linear; deliverables link tickets and display synchronized status. | Confirmed |
| C-011 | Salary, markup and related commercial calculation details are outside this application. | Confirmed |
| C-012 | PRD pack must be detailed enough for Cursor/Claude to implement end to end. | Confirmed |

---

## 3. Initial configurable defaults

These are seed defaults, not immutable rules.

| ID | Configuration | Initial default | Change behavior |
|---|---|---|---|
| D-001 | Timezone | `Asia/Kolkata` | effective-dated |
| D-002 | Full-day expected net work | 540 minutes | per attendance policy/calendar |
| D-003 | Half-day threshold | 270 minutes | per policy/calendar |
| D-004 | Grace/rounding | none | explicit policy version |
| D-005 | Weekly offs | Saturday and Sunday | base calendar; employee/date overrides allowed |
| D-006 | Missing checkout | blocking exception; no synthetic checkout | policy may set reminder/cutoff, not fake time |
| D-007 | Paid break | none inferred; subtract recorded/configured unpaid breaks | policy version |
| D-008 | Over-allocation | total >100% blocked | authorized override with reason |
| D-009 | Deemed plan/certification/confirmation approval | disabled | requires formal policy/legal approval and separate state |
| D-010 | Linear status effect | informational evidence only | cannot auto-certify |
| D-011 | Attendance source | `INTERNAL_AUTHORITATIVE` until greytHR certification | effective-dated source switch |
| D-012 | Linear integration | OAuth app actor, read scope, webhook + reconciliation | tenant approval |
| D-013 | Confirmation | secure authenticated link; email reply adapter when available | per engagement |
| D-014 | Package | PDF + JSON manifest + CSV appendices | Procurement-approved template version |
| D-015 | Plan/certification deadlines | configurable per engagement/month; no inherited old dates assumed | setup required |
| D-016 | Leave accrual | no assumed quantity/type until ArrowFoundry policy configured | setup required |
| D-017 | Historical confidence | explicit source-based enum | cannot be hidden |
| D-018 | Invoice total | optional document metadata | no employee-level derivation |

---

## 4. Tenant/configuration decisions required before production

The application should provide setup screens/checklists and block affected production workflows until these are resolved.

### Organization/access

- [ ] Legal organization/contact codes and verified domains.
- [ ] Reliance and ArrowFoundry SSO/invite method.
- [ ] Initial user/role/scope assignments.
- [ ] Product-owner, approver, governance, vendor and Procurement contact groups.
- [ ] Delegates/escalation hierarchy.

### Attendance/leave

- [ ] Exact paid work/break policy and rounding/grace.
- [ ] Half-day threshold and treatment of short hours.
- [ ] Calendar/holiday list and employee exceptions.
- [ ] Leave types, opening balances, monthly accrual quantity/effective timing, carry-forward/expiry and sandwich rules.
- [ ] Approval hierarchy and retroactive window.
- [ ] Whether location/device data is required; default no.
- [ ] greytHR tenant URL, API access, entitlements, mappings, rate limits and source effective date.
- [ ] Whether any swipe write-back is allowed; default no.

### Delivery/Linear

- [ ] Monthly plan deadline and approval quorum.
- [ ] Delivery submission/certification deadlines and alternates.
- [ ] Mandatory Linear link exceptions.
- [ ] Linear OAuth workspace/app owner/scopes/team/project mapping/webhook secret.
- [ ] Normalized workflow-state mapping.
- [ ] Evidence type/attachment restrictions.

### Confirmation/email

- [ ] Mail platform/provider and dedicated mailbox/sender.
- [ ] Permission for inbound reply ingestion and retention.
- [ ] Eligible confirmation quorum and alternate approvers.
- [ ] Accepted explicit response phrases/manual review rules.
- [ ] Reminder/escalation schedule.
- [ ] Whether secure link requires SSO or OTP.

### Procurement/finance

- [ ] Exact evidence package layout/branding and required appendix fields.
- [ ] Which exceptions are blocking/non-blocking.
- [ ] Procurement exception authority/quorum.
- [ ] Invoice upload timing, PO/work-order fields and finance statuses.
- [ ] SAP/AP integration scope; initial manual status is acceptable unless changed.
- [ ] Retention/legal hold periods.

### Infrastructure/operations

- [ ] Supabase deployment model, staging/production projects and data residency approval.
- [ ] Job runner/queue and email/file scanning services.
- [ ] RPO/RTO/SLO final targets.
- [ ] Monitoring/on-call/support ownership.
- [ ] Backup/export/legal hold policy.

---

## 5. Explicit rejected assumptions

- R-001: “greytHR certainly exposes every needed endpoint in ArrowFoundry's plan.” Rejected; capability certification required.
- R-002: “Nine hours means first check-in to last checkout elapsed time.” Rejected; net credited work policy.
- R-003: “Every Saturday/Sunday is off for everyone.” Rejected; calendar and overrides.
- R-004: “Linear Done means delivered and approved.” Rejected.
- R-005: “No response by deadline is approval.” Rejected by default.
- R-006: “A sent/delivered email proves confirmation.” Rejected.
- R-007: “Historical approval can be backdated to look original.” Rejected.
- R-008: “Admin can edit closed evidence directly.” Rejected; reopen/version.
- R-009: “The local role dropdown is sufficient RBAC.” Rejected.
- R-010: “Anonymous RLS is acceptable because the app is internal.” Rejected.
- R-011: “Requirements/UAT tables are equivalent to deliverable baseline/certification.” Rejected without explicit migration evidence.
- R-012: “Attendance determines invoice amount.” Rejected; commercial calculation outside app.
- R-013: “Current Linear state can reconstruct June month-end.” Rejected without historical source.

---

## 6. Architecture decision records

### ADR-001 — In-place stack extension

**Decision:** Continue React/TanStack/Supabase and add domain/server boundaries.
**Rationale:** Existing code/prototype, sufficient scale, faster migration; avoid gratuitous backend rewrite.
**Consequences:** Strong RLS/server functions/jobs/tests are mandatory; service extraction remains possible.

### ADR-002 — Multi-organization generic domain

**Decision:** ArrowFoundry/Reliance are seed data, not code constants.
**Rationale:** Future teams/projects/vendors.
**Consequences:** All rows scoped by organization/engagement and RLS.

### ADR-003 — One attendance authority per employee-day

**Decision:** Source modes with explicit effective dates/conflicts.
**Rationale:** Prevent double counting/ambiguous proof.
**Consequences:** Hybrid is transitional and reconciliation-heavy.

### ADR-004 — Immutable versioned evidence

**Decision:** snapshots/package/checksums and reopen lineage.
**Rationale:** Procurement/audit reproducibility.
**Consequences:** Corrections trigger downstream invalidation/reconfirmation.

### ADR-005 — Human approval not inferred

**Decision:** Linear state, timeout and email delivery do not certify/confirm.
**Rationale:** Evidence integrity.
**Consequences:** Escalation/delegation/Procurement exception flows are required.

### ADR-006 — Linear webhook-first plus reconciliation

**Decision:** OAuth app, signed webhook, normalized current state, nightly delta and snapshots.
**Rationale:** Freshness without excessive polling and resilience to missed events.
**Consequences:** Queue/idempotency/health monitoring required.

### ADR-007 — greytHR capability gate

**Decision:** enable authoritative mode only after tenant-specific certification.
**Rationale:** Public documentation does not prove tenant entitlements.
**Consequences:** full internal attendance and CSV fallback remain.

### ADR-008 — Commercial data boundary

**Decision:** permit invoice-level metadata/document, prohibit salary/rate/markup derivation.
**Rationale:** user instruction and privacy/minimization.
**Consequences:** no payroll/rate tables or employee billing allocation.

### ADR-009 — Secure confirmation link first

**Decision:** authenticated version-bound link is primary; email reply ingestion/manual evidence are adapters/fallbacks.
**Rationale:** dependable identity/version binding despite unknown email platform.
**Consequences:** email still sent/archived and can be ingested when available.

---

## 7. Decision-change process

- Product/architecture owner records proposed change, reason, impacted PRDs/data/months.
- Security/procurement/legal review where relevant.
- Publish a new configuration/policy/template/ADR version with effective date.
- Run impact analysis and tests.
- Never apply retroactively to closed months without reopen/reconfirmation.
- Update this register and master TODO in the same change.

---

## 8. Setup readiness checklist

Production readiness UI should mark each required decision `NOT_CONFIGURED`, `CONFIGURED_UNVERIFIED`, `VERIFIED`, or `EXPIRED/ACTION_REQUIRED` and link to the owning admin screen. A month cannot enter a workflow whose mandatory configuration is unresolved.
