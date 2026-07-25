# 00 — Implementation Index, Dependency Map and Master TODO

**Status:** Controlling implementation specification
**Version:** 1.0
**Date:** 25 July 2026
**Target repository:** `https://github.com/arpanm/vms-workflow`
**Target application:** Java/PostgreSQL replacement; deployment target pending

> Stack note: Requirement 22 supersedes all Lovable/Supabase implementation
> wording below. Historical references remain for migration and rollback
> traceability; current implementation uses Spring Boot, PostgreSQL and Vite.

---

## 1. Why this change exists

The current application models fixed-cost monthly delivery using engagements, requirements, approvals, UAT items and invoices. The new ArrowFoundry contract is governed primarily by **named full-time resource deployment plus monthly delivery evidence**, not by effort-estimation and UAT-only proof.

The product must therefore add a workforce-and-evidence domain while preserving useful existing delivery-governance capabilities. It must support ArrowFoundry's initial 26 resources assigned to Reliance Intelligence / NAM / Agentic ShopOS and remain reusable for other vendors, teams, projects and engagement models.

The invoice-supporting evidence chain is:

`Contract/engagement → approved roster and allocations → daily attendance outcomes → approved monthly deliverable baseline → Linear execution evidence → delivery certification → consolidated business confirmation → versioned evidence package → invoice readiness/submission`.

No step may silently fabricate a human approval or alter evidence after confirmation.

---

## 2. Source-draft audit and supersession map

The seven supplied drafts identify the correct high-level domains, but leave implementation-critical gaps. This pack supersedes them as follows:

| Supplied draft | Retained intent | Superseded by |
|---|---|---|
| PRD-01 Employee Governance & Attendance | Employee lifecycle, attendance, leave, calendars, migration | PRD 04, 05, 06, 11, 13, 14, 16 |
| PRD-02 Deliverable Planning | Monthly plans, deliverables, approval, email, history | PRD 07, 09, 11, 13, 15, 16 |
| PRD-03 Delivery Certification | Vendor submission, product-owner certification, close | PRD 08, 09, 10, 11, 16 |
| PRD-04 Invoice Evidence | Evidence checklist, package, confirmation, audit | PRD 08, 09, 10, 13, 14 |
| PRD-05 greytHR & Linear | Integration intent | PRD 06, 07, 13, 14, 19 |
| PRD-06 Notifications, Audit, Roles | Administration and controls | PRD 03, 09, 13, 14, 15 |
| PRD-07 Dashboards & Historical Migration | Reporting and backfill | PRD 11, 12, 16, 18 |

### Gaps corrected in this pack

- No authoritative-source rule for greytHR versus internal attendance.
- No deterministic attendance math, punch pairing, break handling, half-day thresholds or overnight-shift rules.
- No leave-balance ledger, idempotent accrual, negative-balance/LWP conversion or effective-dated policy model.
- No immutable planning, attendance, Linear-status, certification or confirmation snapshots.
- No reliable email-confirmation capture, sender verification, message/thread identifiers or evidence hashing.
- No production identity model; the prototype uses a local role selector and anonymous database access.
- No integration authentication, webhook validation, idempotency, retry, reconciliation or failure queue.
- No normalized Linear workflow-state mapping or explicit rule that ticket completion does not equal delivery certification.
- No safe historical backfill workflow or retroactive approval provenance.
- No database/API/event model, background-job catalog, file-storage design or retention controls.
- No file-level migration plan for the existing React/TanStack/Supabase codebase.
- Earlier automatic/deemed approvals are not safe as procurement evidence unless explicitly authorized and distinguished from confirmed approval.

---

## 3. Product principles that are release gates

1. **Evidence, not payroll:** Never store salary, employee cost, markup, rate-card calculations or vendor margin.
2. **Multi-tenant by design:** Never hard-code ArrowFoundry, Reliance, NAM, ShopOS, or 26 employees in domain logic. Seed them as data.
3. **One authoritative attendance source per employee-day:** `GREYTHR`, `INTERNAL`, or authorized `HISTORICAL_IMPORT`; never merge conflicting truth silently.
4. **Human confirmation remains human:** reminders, escalation and delegation are allowed; invented approvals are not.
5. **Snapshots are immutable:** planning baseline, attendance close, Linear month-end state, certification, confirmation and package versions remain reproducible.
6. **Current status is not historical truth:** Linear and employee records can change after month close; evidence uses captured snapshots.
7. **Exceptions are first-class:** missing punches, absent approvals, sync failures and post-close corrections appear in queues with owners and SLAs.
8. **Security is server-enforced:** UI hiding is not authorization; Supabase RLS/server actions enforce organization and role scope.
9. **Every consequential action is auditable:** actor, authority, timestamp, old/new values, reason, source and correlation ID.
10. **Existing product remains operable:** legacy fixed-cost delivery modules are migrated or feature-flagged, not broken abruptly.

---

## 4. PRD catalog and ownership

| File | Scope | Depends on | Primary implementation owner |
|---|---|---|---|
| `01_PRODUCT_CHANGE_BRIEF_SCOPE_AND_PRINCIPLES.md` | Product boundaries, outcomes, glossary, success measures | — | Product lead |
| `02_DOMAIN_MODEL_ORGANIZATIONS_ENGAGEMENTS_PROJECTS_MONTHS.md` | Canonical business model and month state | 01 | Product + architecture |
| `03_IDENTITY_RBAC_APPROVAL_MATRIX_AND_ADMINISTRATION.md` | Auth, roles, delegation, contact groups, admin controls | 01-02 | Platform/security |
| `04_EMPLOYEE_MASTER_ALLOCATIONS_CALENDARS_AND_LEAVE_BALANCES.md` | Employee, allocation, calendars, holiday, leave ledger | 02-03 | Workforce pod |
| `05_ATTENDANCE_CHECKIN_CHECKOUT_LEAVE_AND_REGULARIZATION.md` | Punches, calculations, leave and exception workflows | 03-04 | Workforce pod |
| `06_GREYTHR_INTEGRATION_SOURCE_OF_TRUTH_AND_RECONCILIATION.md` | greytHR capability modes and sync controls | 04-05 | Integration pod |
| `07_MONTHLY_DELIVERABLE_PLANNING_AND_LINEAR_INTEGRATION.md` | Baseline, deliverables, Linear OAuth/webhooks/snapshots | 02-03 | Delivery pod |
| `08_DELIVERY_EVIDENCE_CERTIFICATION_AND_MONTH_END_CLOSURE.md` | Submission, certification, closure/reopen | 05,07 | Delivery pod |
| `09_EMAIL_CONFIRMATION_INGESTION_NOTIFICATIONS_AND_ESCALATIONS.md` | Commitment/closure emails, reply evidence, reminders | 03,07,08 | Workflow/integration |
| `10_INVOICE_EVIDENCE_PROCUREMENT_AND_PAYMENT_READINESS.md` | Checklist, package, invoice status and procurement exception | 08-09 | Finance governance |
| `11_HISTORICAL_MIGRATION_BACKFILL_AND_RETRO_APPROVALS.md` | Backfill from 1 June, dry-run, retro confirmation | 04-10 | Data migration |
| `12_DASHBOARDS_REPORTS_EXPORTS_AND_CONTROL_TOWER.md` | Persona dashboards, reports, exception control tower | 04-11 | Product analytics |
| `13_DATA_MODEL_API_EVENTS_BACKGROUND_JOBS_AND_STORAGE.md` | Schema, API, events, jobs, storage and idempotency | 02-12 | Architecture |
| `14_SECURITY_PRIVACY_AUDIT_RETENTION_AND_COMPLIANCE.md` | RLS, secrets, audit, privacy, retention, file safety | 03,13 | Security |
| `15_UI_UX_INFORMATION_ARCHITECTURE_AND_PERSONA_FLOWS.md` | Routes, screens, forms, CTAs, empty/error states | 01-14 | UX/frontend |
| `16_ACCEPTANCE_TEST_CATALOG_NFR_ROLLOUT_AND_OPERATIONS.md` | Cross-module E2E tests, NFRs, rollout and runbooks | All | QA/SRE |
| `17_EXISTING_CODE_IMPACT_FILE_LEVEL_TODO_AND_MIGRATION_ORDER.md` | Current repo changes and sequence | 13-16 | Tech lead |
| `18_IMPORT_TEMPLATES_FIELD_DICTIONARY_AND_SAMPLE_FILES.md` | CSV templates and validation codes | 11,13 | Migration lead |
| `19_RESEARCH_FINDINGS_AND_INTEGRATION_DECISIONS.md` | Official greytHR/Linear findings and decisions | 06-07 | Architecture |
| `20_ASSUMPTION_REGISTER_CONFIG_DEFAULTS_AND_DECISION_LOG.md` | Defaults, unresolved tenant-specific facts, ADR log | All | Product/architecture |
| `21_REQUIREMENT_TRACEABILITY_AND_GAP_CLOSURE.md` | End-to-end requirement-to-PRD/data/UI/test traceability | 01-20 | Product + QA |

### 4.1 Supporting implementation artifacts

| Artifact | Purpose |
|---|---|
| `CURSOR_START_HERE.md` | Exact phased handoff and non-negotiables for Cursor/Claude Code. |
| `IMPLEMENTATION_BACKLOG.csv` | Importable phase/task backlog mapped to PRDs and requirement IDs. |
| `templates/README.md` and `templates/*.csv` | Versioned, synthetic historical-import templates and examples. |
| `templates/template_manifest.json` | Template versions, filenames and checksums. |
| `schemas/README.md` and `schemas/*` | JSON Schemas, state machines, policy examples, API and SQL outlines. |
| `QUALITY_REPORT.md` | Automated structural/syntax/cross-reference validation result. |
| `PRD_PACK_MANIFEST.json` | SHA-256 integrity manifest for delivered files. |

---

## 5. Master implementation phases

### Phase 0 — Baseline, backup and feature flags

- [x] Tag the current repository and record the historical database migration checksum/version.
- [ ] Export existing Supabase schema/data and Storage metadata.
- [x] Add `VITE_DEMO_MODE`, legacy, workforce-governance, greytHR, Linear and email-reply-ingestion flags.
- [x] Introduce environment validation; fail startup for missing production secrets.
- [ ] Establish staging and production projects; never build directly against production data.
- [x] Create ADR-010 confirming the Java/PostgreSQL replacement architecture.

**Exit gate:** existing routes still work in staging and rollback is documented.

### Phase 1 — Identity, tenant boundaries and core masters

- [ ] Complete provider-backed OIDC/BFF browser login; the Java JWT resource-server boundary is implemented.
- [ ] Add organizations, memberships, roles, permissions, engagements, projects, contacts and approval matrices.
- [x] Implement Spring authorization and PostgreSQL/Testcontainers tenant security tests before adding sensitive workforce data.
- [ ] Add engagement-month records and controlled state transitions.
- [x] Seed ArrowFoundry, Reliance Intelligence, Central Procurement, NAM/ShopOS and June-start engagement data through Flyway migrations.

**Exit gate:** a user cannot read or mutate an unauthorized organization/engagement through UI, Spring API or PostgreSQL-backed service paths.

### Phase 2 — Employee, allocation, calendar, leave and attendance core

- [ ] Implement employee lifecycle and effective-dated project/deliverable allocations.
- [ ] Implement organization and employee calendar overrides, holidays and exceptional working days.
- [ ] Implement leave types, balance ledger, grants/accruals/adjustments/LWP.
- [ ] Implement check-in/out sessions, computed daily attendance, regularization and approval queues.
- [ ] Implement monthly attendance validation and snapshot.

**Exit gate:** all attendance calculation truth-table tests pass, including missing checkout, partial leave, weekly-off override and post-close correction.

### Phase 3 — Deliverable planning and Linear

- [ ] Implement monthly plan, measurable deliverables, owners, acceptance criteria and issue linkage.
- [ ] Add plan review/approval/freeze and commitment-email evidence.
- [ ] Implement Linear OAuth2/app-actor setup, issue resolution, webhook receiver, signature validation, event deduplication and reconciliation.
- [ ] Capture plan-time and month-end Linear snapshots.
- [ ] Add integration health dashboard and retry/dead-letter controls.

**Exit gate:** webhook loss or duplicate delivery does not corrupt issue state; ticket status never auto-certifies delivery.

### Phase 4 — Delivery certification and confirmation

- [ ] Implement vendor month-end submission with evidence and statuses.
- [ ] Implement product-owner item-level certification and authorized monthly approval.
- [ ] Generate consolidated confirmation request to Reliance product owners, ArrowFoundry and Procurement CC.
- [ ] Implement secure confirmation link plus provider-neutral email reply ingestion/manual evidence fallback.
- [ ] Enforce verified confirmation before invoice readiness unless Procurement records an explicit exception.

**Exit gate:** every confirmation has verifiable actor/source/message evidence; no timeout creates a false approval.

### Phase 5 — Evidence packages, invoices and reporting

- [ ] Generate versioned, deterministic evidence manifests and PDFs/CSV attachments.
- [ ] Add invoice metadata/upload, readiness checklist, procurement review and payment-status tracking.
- [ ] Add executive, product-owner, vendor, procurement, admin and employee dashboards.
- [ ] Add exports with applied filters, source/version labels and checksums.

**Exit gate:** the same closed-month version regenerates the same manifest content/checksums, excluding explicitly versioned rendering metadata.

### Phase 6 — Historical backfill from 1 June

- [ ] Load masters first, then allocations/calendars, attendance/leave, plans/issues, certifications, confirmations and invoices.
- [ ] Run dry-run validations and reconcile row counts/checksums.
- [ ] Generate retroactive commitment, certification and consolidated-confirmation workflows without falsifying original timestamps.
- [ ] Close historical months only after exception owners approve the migration report.

**Exit gate:** June onward evidence packages are reproducible and distinguish imported facts, reconstructed records and newly obtained confirmations.

### Phase 7 — Hardening and go-live

- [ ] Execute security, RLS, performance, accessibility, backup/restore and disaster-recovery tests.
- [ ] Run parallel attendance reconciliation for an agreed period before switching source authority.
- [ ] Train all roles using role-specific scripts.
- [ ] Go live with monitoring, alerting, support ownership and rollback criteria.

---

## 6. Critical path dependencies

```text
Authentication/RLS
  └─> Organizations/Engagements/Months
       ├─> Employee/Calendar/Leave ─> Attendance snapshot ─┐
       └─> Deliverable plan/Linear ─> Delivery certification ├─> Consolidated confirmation
                                                               └─> Evidence package ─> Invoice readiness
```

Historical migration depends on stable target schemas and validation rules; it must not be used as a shortcut to avoid building normal workflows.

---

## 7. Mandatory configuration before production

- [ ] Full-day and half-day attendance thresholds by calendar/policy.
- [ ] Paid/unpaid break handling.
- [ ] Leave types, opening balances and monthly accrual rules.
- [ ] Working week and holiday calendars.
- [ ] Attendance source mode and effective date.
- [ ] Product-owner, vendor, procurement and escalation distribution groups.
- [ ] Planning/certification/confirmation deadlines and reminder cadence.
- [ ] Approval matrix and delegation rules.
- [ ] Evidence retention period and legal hold process.
- [ ] Linear OAuth app/workspace/team access and webhook endpoint.
- [ ] greytHR tenant/API entitlement validation.
- [ ] Email provider integration or approved manual confirmation-evidence process.

---

## 8. Definition of done for the complete change

The change is complete only when:

1. all mandatory stories and tests in PRDs 01–18 pass;
2. no production table has anonymous write/read policies;
3. all procurement-critical evidence is attributable, versioned and immutable;
4. a closed month can be reopened only with authority and reason, creating a new version;
5. June onward historical months can be imported, reviewed, confirmed and packaged;
6. Linear status is visible and historically snapshotted, with sync health and replay;
7. greytHR is either certified as authoritative for the required data or the internal attendance module is fully operational;
8. the procurement evidence package can be generated without salary/markup data;
9. the system survives retries, duplicate events, integration outages and partial imports without double-processing; and
10. the implementation documentation, migrations, seed data, tests and operational runbooks are committed with the code.

---

## 9. Do-not-build / do-not-assume list

- Do not add payroll computation, salary, cost-to-company, markup, individual billing rates or invoice amount derivation.
- Do not hard-code Saturday/Sunday as universal weekly offs; use calendars and effective-dated overrides.
- Do not treat nine elapsed hours between first-in/last-out as nine worked hours when unpaid breaks or multiple sessions exist.
- Do not auto-invent a checkout for a missing punch.
- Do not treat a Linear issue marked Done as product-owner acceptance.
- Do not use current Linear status as proof of what it was at month end.
- Do not allow an admin edit to overwrite closed-month evidence without reopen/version workflow.
- Do not call a reminder timeout an email confirmation.
- Do not store third-party API credentials in browser code or normal database columns.
- Do not leave the existing local role dropdown enabled in production authorization mode.
