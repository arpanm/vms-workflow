# 21 — Requirement Traceability and Gap-Closure Matrix

**Version:** 1.0
**Date:** 25 July 2026
**Purpose:** Prove that every stated business requirement, draft-PRD intent and implementation-critical control has a specification, data owner, UI surface and acceptance test.

---

## 1. Traceability method

Each requirement has:

- a stable requirement ID;
- the normative PRD file(s);
- the primary business object or evidence artifact;
- the required user-facing surface;
- representative acceptance-test IDs; and
- an implementation status that Cursor must maintain in pull requests.

`Covered` means specified in this pack, not already implemented in the current prototype.

---

## 2. Business requirement traceability

| ID | Requirement | Normative files | Core records / artifacts | Primary UI / workflow | Acceptance coverage | Status |
|---|---|---|---|---|---|---|
| RQ-001 | Support ArrowFoundry's initial 26 full-time people without hard-coding the number and allow later projects/vendors/teams | 01, 02, 04, 13 | Organization, engagement, employee, project, allocation | Admin > Organizations, Engagements, Employees | E2E-01; T-IAM-003; T-WF-001 | Covered |
| RQ-002 | Admin can add, edit, enable, disable and retain historical employees | 03, 04, 14, 15 | Employee, status history, audit event | Employee directory/detail | E2E-01; T-WF-001; T-WF-002; T-IAM-004 | Covered |
| RQ-003 | Assign employees to projects and deliverables with correct historical attribution | 02, 04, 07, 13 | Employee allocation, deliverable allocation | Employee Allocation tab; Deliverable Team tab | E2E-01; T-WF-003; T-WF-004 | Covered |
| RQ-004 | Employees can check in and check out daily | 05, 13, 15 | Punch, attendance session, attendance day | Employee Today page / mobile-responsive widget | E2E-01; T-ATT-001 to T-ATT-003 | Covered |
| RQ-005 | Nine working hours determine a full day; shortfall resolves to half day/full-day leave, LWP or regularization | 05, 20; schema attendance policy | Attendance policy, computed day, leave request, regularization | Attendance day detail and exception queue | E2E-01; T-ATT-004; T-ATT-005; T-WF-009 | Covered |
| RQ-006 | No ambiguous or fabricated attendance when checkout is missing | 05, 14, 16 | Open session, exception, audit | Missing-punch banner and regularization CTA | T-ATT-006; T-ATT-009; T-SEC-003 | Covered |
| RQ-007 | Configure default and employee-specific weekly offs, exceptional working days and annual holidays | 04, 05, 15 | Working calendar, holiday, employee calendar assignment/date override | Calendar administration and employee calendar | E2E-01; T-WF-005; T-WF-006; T-ATT-008 | Covered |
| RQ-008 | Employees can apply for leave in advance; opening balance and monthly accrual are admin-configured; excess becomes LWP | 04, 05, 13, 15 | Leave type, ledger, request, accrual job | Leave balance, apply leave, admin ledger | E2E-01; T-WF-007 to T-WF-009 | Covered |
| RQ-009 | Integrate greytHR when the required ArrowFoundry tenant capabilities are available; otherwise run attendance internally | 06, 13, 19, 20 | Integration connection, capability certification, sync run, reconciliation item | Integration setup wizard and health console | E2E-02; T-GHR-001; T-GHR-002; T-GHR-007 | Covered |
| RQ-010 | Prevent dual authoritative truth between greytHR and internal attendance | 05, 06, 11, 13 | Attendance-source assignment, provenance, conflict | Source badge and reconciliation queue | E2E-02; T-GHR-005; T-GHR-008; T-ATT-011 | Covered |
| RQ-011 | Reliance product persons define deliverables before the month starts | 07, 15 | Monthly plan, deliverable, acceptance criteria | Monthly Plan builder/review | E2E-03; T-PLAN-001; T-PLAN-002 | Covered |
| RQ-012 | Deliverables include measurable acceptance criteria, outcomes, owner, dates and evidence expectations | 07, 08 | Deliverable, acceptance criterion, evidence requirement | Deliverable form/detail | E2E-03; T-PLAN-001; T-DEL-001 | Covered |
| RQ-013 | Approved monthly deliverables are emailed to ArrowFoundry and Reliance product persons with Procurement in CC | 07, 09, 13 | Plan snapshot, notification message, email evidence | Approve & Send; communication history | E2E-03; T-MSG-001; T-MSG-002 | Covered |
| RQ-014 | Deliverables accept one or more Linear links and show current ticket status | 07, 13, 15, 19 | Deliverable link, current issue projection | Deliverable Linear panel | E2E-03; T-LIN-001; T-LIN-006; T-LIN-009 | Covered |
| RQ-015 | Linear integrates through secure OAuth and signed webhooks, with retry/reconciliation/manual refresh | 07, 13, 14, 16, 19 | OAuth connection, webhook event, sync run, dead letter | Linear setup/health console | E2E-03; T-LIN-002 to T-LIN-005; T-LIN-008 | Covered |
| RQ-016 | Preserve plan-time and month-end Linear status rather than using mutable current status as historical proof | 07, 08, 10, 13 | Linear issue snapshot | Snapshot viewer / evidence package | E2E-03; T-LIN-007; T-PKG-002 | Covered |
| RQ-017 | Linear completion is supporting evidence, not automatic business acceptance | 07, 08, 20 | Certification decision | Product-owner certification page | T-LIN-010; T-CERT-001 | Covered |
| RQ-018 | ArrowFoundry submits month-end completion/evidence against every baseline deliverable | 08, 15 | Delivery submission, item outcome, attachment | Vendor month-end submission | E2E-03; T-DEL-001; T-DEL-002 | Covered |
| RQ-019 | Reliance product persons approve/certify actual delivery item by item and at month level | 03, 08, 15 | Deliverable certification, approval request, snapshot | Certification workbench | E2E-03; T-CERT-001 to T-CERT-005 | Covered |
| RQ-020 | Delivery approval email goes to ArrowFoundry and Reliance product persons with Procurement in CC | 08, 09 | Certification snapshot, notification message | Complete certification & send | E2E-03; T-MSG-001; T-MSG-002 | Covered |
| RQ-021 | Consolidated month-end communication includes attendance, plan/delivery, certification and invoice/evidence status | 09, 10 | Confirmation request, evidence summary | Confirmation preview/send | E2E-04; T-PKG-001; T-MSG-001 | Covered |
| RQ-022 | Reliance product persons confirm the consolidated statement, preferably through secure action or verifiable email reply | 09, 14, 15 | Business confirmation, message/thread evidence, signature/hash | Confirmation page; reply-ingestion review | E2E-04; E2E-05; T-CONF-001 to T-CONF-005 | Covered |
| RQ-023 | Silence, delivery receipts and reminder timeouts must not be represented as confirmation | 09, 10, 20 | Confirmation status, procurement exception | Confirmation aging queue | E2E-05; T-CONF-004; T-CONF-005; T-CERT-006 | Covered |
| RQ-024 | Generate all data required as proof for invoice approval without exposing salary/markup calculations | 10, 13, 14 | Evidence package, manifest, documents, invoice metadata | Invoice readiness and package viewer | E2E-06; T-PKG-001 to T-PKG-004; T-INV-002 | Covered |
| RQ-025 | Vendor uploads monthly invoice and Procurement can see readiness, exceptions, queries and payment tracking | 10, 12, 15 | Invoice, readiness check, procurement query, payment milestone | Invoice workspace / procurement dashboard | E2E-06; T-INV-001; T-PROC-001; T-PAY-001 | Covered |
| RQ-026 | Package must be immutable, versioned, reproducible and checksumed | 08, 10, 13, 14; evidence schema | Evidence snapshots, package version, manifest | Package history/diff | E2E-06; E2E-07; T-PKG-002; T-PKG-003 | Covered |
| RQ-027 | Support historical upload from 1 June 2026 for employees, attendance, leave, plans, Linear links, certifications, confirmations and invoices | 11, 18; templates | Import batch, row result, target records | Migration center | E2E-08; T-MIG-001 to T-MIG-005 | Covered |
| RQ-028 | Generate retroactive approvals/confirmations for past months without falsifying original timestamps | 09, 11, 14 | Represented-at/recorded-at, retro request, confirmation | Historical month recovery wizard | E2E-08; T-MIG-004; T-CONF-008 | Covered |
| RQ-029 | Closed historical or current months can only change through reopen, reason, authority and new evidence version | 02, 08, 10, 11, 14 | Month version, reopen approval, supersession links | Reopen Month workflow | E2E-07; T-ATT-013; T-PKG-003; T-MIG-005 | Covered |
| RQ-030 | All important steps send role-appropriate email/in-app notifications and retain delivery history | 09, 13, 15 | Notification policy, message, delivery attempt | Notification center/admin templates | T-MSG-001; T-MSG-002 | Covered |
| RQ-031 | Avoid bureaucracy through reminders, delegation, escalation, alternate approvers and explicit exceptions—not false approvals | 03, 09, 10, 20 | Delegation, escalation, exception | My Actions; control tower | T-IAM-005; T-CERT-006; T-PROC-002 | Covered |
| RQ-032 | Provide role-based dashboards for employee, vendor, product owner, procurement, admin and leadership | 12, 15 | Projections/materialized views | Persona dashboards/control tower | T-REP-001; T-REP-002; E2E-09 | Covered |
| RQ-033 | Preserve complete auditability and enforce real security rather than demo role switching | 03, 13, 14, 17 | Auth identity, membership, audit event, RLS | Login, admin, audit explorer | E2E-09; T-IAM-007 to T-IAM-010; T-SEC-001 to T-SEC-003 | Covered |
| RQ-034 | Maintain useful original fixed-cost modules while expanding the product | 01, 02, 17 | Feature flags, legacy mapping | Legacy routes/engagement mode | T-IAM-010; T-MIG-005; regression suite | Covered |
| RQ-035 | Give Cursor/Claude an exact implementation sequence, file impact, schemas, imports and tests | 00, 13, 16-21; schemas/templates | PRD pack artifacts | Repository docs and CI | All E2E-01 to E2E-10; T-DR-001 | Covered |

---

## 3. Seven draft PRD gap-closure traceability

| Draft concern | Gap in draft | Closing specification |
|---|---|---|
| Employee/attendance | No session-pairing algorithm, source precedence, overnight handling or immutable close | 04-06, 13, 16, 20 |
| Leave | Balance described as a mutable number; no ledger or idempotent accrual | 04-05, 13 |
| greytHR | “Synchronize” stated without entitlement check, auth, failure mode or source-of-truth rules | 06, 13, 19-20 |
| Deliverables | No baseline snapshot semantics or revision/supersession rules | 07-08, 13 |
| Linear | No OAuth/webhook security, normalized mapping, history snapshot or stale-state treatment | 07, 13-14, 19 |
| Certification | No distinction between vendor completion, issue state and authorized acceptance | 08, 20 |
| Email confirmation | No authenticated reply capture, message identifiers, secure-link flow or false-positive controls | 09, 13-14 |
| Evidence/invoice | No deterministic manifest, checksums, version cascade or post-close invalidation | 10, 13-14; schemas |
| Historical migration | No dry-run/commit/rollback, import ordering, represented-at timestamp or reconstruction labels | 11, 18; templates |
| Roles/security | Generic roles but no tenant scope, permission matrix, RLS, delegation or separation of duties | 03, 14, 17 |
| Notifications/audit | No outbox, retry, provider status, correlation or append-only controls | 09, 13-14 |
| Dashboards | Metrics listed without canonical definitions, source/version labels or authorization | 12-14 |
| Implementation | No repo/file impact, routes, schema, APIs, jobs, rollout or failure tests | 13, 15-18, 21 |

---

## 4. Pull-request traceability rule

Every implementation PR MUST include:

1. requirement IDs changed or implemented;
2. PRD section links;
3. schema/migration/API contracts changed;
4. tests added with test IDs;
5. screenshots for changed user flows;
6. RLS/security impact;
7. migration/backfill impact;
8. evidence-version impact; and
9. rollback instructions.

A feature is not complete when its screen works; it is complete when its requirement, policy, source provenance, audit, notification, failure path, tests and operational monitoring are all closed.
