# 01 — Product Change Brief, Scope and Principles

**Version:** 1.0
**Status:** Build specification
**Related:** 00, 02, 20

---

## 1. Product vision

Create one auditable system of work for managing vendor-deployed personnel, monthly business commitments, execution evidence, acceptance and invoice-supporting proof across Reliance engagements.

The first configured use case is ArrowFoundry deploying 26 full-time resources to Reliance Intelligence, initially for Neural Agentic Marketplace / Agentic ShopOS. The product must permit future projects, teams, vendors and engagement types without schema or code forks.

---

## 2. Business outcomes

| Outcome | Required observable result |
|---|---|
| Procurement-ready evidence | One versioned package per engagement-month with roster, attendance, deliverables, certification and confirmation |
| Predictable monthly commitment | Reliance product owners define and approve measurable deliverables before execution month |
| Verified delivery acceptance | End-month certification is attributable to authorized product owners and tied to evidence/Linear snapshots |
| Attendance integrity | Every expected working day resolves to present, partial, leave, LWP, holiday, weekly off, absent or approved regularization |
| No administrative deadlock | Reminders, delegates, escalation and exception queues preserve momentum without inventing approvals |
| Historical continuity | Data from 1 June onward can be imported, validated, approved and packaged with transparent provenance |
| Auditability | Every material mutation and external communication is reconstructable |
| Reusability | Organization, project, calendar, approval and integration configuration supports future engagements |

---

## 3. In scope

### 3.1 Organization and engagement governance

- Client, vendor and procurement organization masters.
- Engagements, projects, teams, effective dates, statuses and contacts.
- Product-owner, vendor, procurement, attendance and escalation roles.
- Engagement-month lifecycle, versioning, freeze, close and reopen.

### 3.2 Workforce governance

- Employee create/edit/enable/disable/archive/exit.
- Effective-dated project and deliverable allocation.
- Working calendar, weekly offs, holiday lists and individual overrides.
- Opening leave balances, grants, monthly accrual, adjustments and LWP.
- Internal daily check-in/out, multiple sessions, computed duration/status.
- Leave, attendance regularization, reviewer decision and exception queues.
- greytHR employee/attendance/leave/calendar integration where tenant capabilities permit.
- Monthly attendance validation, closure and evidence snapshot.

### 3.3 Monthly deliverables and execution proof

- Monthly plans and measurable deliverables defined before month start.
- Owners, acceptance criteria, target dates, priority, dependencies, resource assignments and attachments.
- One-to-many Linear issue links, current state, sync health and month snapshots.
- Plan approval/freeze and commitment communication.
- Vendor delivery submission, evidence, variances and carry-forward.
- Product-owner item-level certification and monthly approval.

### 3.4 Communications and confirmation

- Templated transactional emails and in-app notifications.
- Required recipient/CC groups per engagement.
- Reminder, escalation and delegation.
- Secure confirmation links and email-reply evidence ingestion.
- Message metadata, attachment version, send/delivery state and response evidence.

### 3.5 Invoice evidence and reporting

- Invoice upload and non-commercial metadata.
- Mandatory evidence-readiness checklist.
- Versioned evidence package and manifest with checksums.
- Procurement review, exception acceptance and payment-status tracking.
- Persona dashboards, compliance metrics, exports and search.
- Historical migration and regeneration.

---

## 4. Explicitly out of scope

- Salary, CTC, payroll, employee cost, rate card, markup or vendor-margin computation.
- Automatic invoice amount calculation from attendance/resource cost.
- Statutory payroll/tax computation or payslips.
- Biometric device firmware or direct device management unless later contracted.
- Full replacement of greytHR; internal HR functions exist only to support attendance evidence when required.
- Full project management replacement for Linear.
- Source-code activity surveillance as a proxy for attendance.
- Time-sheet-based productivity scoring unless separately approved.
- Automatic product-owner acceptance based only on elapsed time or Linear status.
- SAP payment execution in the first release; payment status/integration hooks are included.

---

## 5. Primary personas

| Persona | Organization | Core jobs |
|---|---|---|
| ArrowFoundry employee | Vendor | View assignment/calendar; check in/out; apply leave/regularization; inspect own records |
| ArrowFoundry manager/admin | Vendor | Maintain employees, balances, calendars, allocations; review exceptions; submit delivery and invoice |
| Reliance product owner | Client | Define monthly deliverables; approve baseline; certify delivery; confirm month |
| Reliance program/governance lead | Client | Configure projects/approvals; monitor exceptions; reopen/close with authority |
| Central Procurement reviewer | Procurement | Receive commitment/closure communication; inspect readiness/package; record exception acceptance |
| Finance/AP user | Client/Procurement | Validate invoice metadata and track processing/payment state |
| Integration administrator | Either authorized organization | Configure/test greytHR, Linear and email adapters; manage failures/replay |
| Security/auditor | Authorized independent role | Read immutable audit and evidence; no operational mutation |
| System service account | Platform | Scheduled accruals, sync, reminders, snapshots and package generation |

A person may hold multiple roles, scoped by organization, engagement and project. Historical actions remain tied to the identity and authority at action time.

---

## 6. Glossary

- **Engagement:** A contractual delivery arrangement between a client and vendor, optionally reviewed by a procurement organization.
- **Project:** A work domain under an engagement, e.g. Agentic ShopOS.
- **Engagement month:** The governance and billing unit for one engagement and calendar month.
- **Deliverable baseline:** The immutable, approved version of expected outcomes before execution.
- **Attendance session:** A paired check-in/check-out interval.
- **Attendance day:** Computed result for one employee on one local calendar date, based on sessions, calendar, leave and approved adjustments.
- **Attendance source:** The authoritative system/operation that supplied the final day record.
- **Regularization:** A justified request to correct or classify an attendance exception.
- **Delivery certification:** Product-owner decision on whether a committed deliverable met its acceptance criteria.
- **Business confirmation:** Authorized confirmation that the monthly attendance/deliverable/certification summary is the agreed record for invoice evidence.
- **Evidence package:** Versioned bundle plus manifest of all procurement-supporting documents/data.
- **Retroactive record:** A record created now to represent a historical month, with present capture timestamp and explicit represented date.
- **Deemed acceptance:** Timeout-based status. Disabled by default and never equivalent to verified business confirmation.

---

## 7. End-to-end canonical workflow

1. Admin creates/maintains organizations, engagement, projects, contacts, calendars and approval policies.
2. Vendor admin activates employees and effective-dated allocations.
3. Reliance product owners draft next month's deliverable plan, link Linear issues and assign resources.
4. Authorized approvers approve/freeze a baseline; system sends the commitment email and records it.
5. Attendance is captured internally or synchronized from greytHR, with daily exceptions resolved.
6. Linear updates flow through webhooks/reconciliation; the platform displays current execution state.
7. At month end, ArrowFoundry submits deliverable outcomes and supporting evidence.
8. Reliance product owners certify each deliverable and complete the monthly delivery decision.
9. Attendance is validated/snapshotted; system creates a consolidated summary.
10. System sends a confirmation request to Reliance product owners, copies ArrowFoundry and Central Procurement, and records the verified response.
11. Vendor uploads invoice metadata/document; readiness rules validate the evidence chain.
12. System generates a versioned procurement evidence package; Procurement reviews or records an explicit exception.
13. Finance/payment status is tracked; month closes and becomes immutable.
14. Any later correction requires reopen authorization, reason, impacted-evidence analysis and a new version/confirmation.

---

## 8. Anti-bureaucracy without false approvals

| Delay/failure | System behavior | Prohibited behavior |
|---|---|---|
| Product owner has not drafted a plan | Notify/escalate; allow authorized delegate; present prior backlog and carry-forward suggestions | Auto-create commitments that nobody owns |
| Baseline approval delayed | Reminder, escalation, delegate/backup approver; mark execution-at-risk | Claim approval on timeout unless an explicit policy legally authorizes it |
| Employee misses punch | Create exception and prompt regularization/admin correction with evidence | Invent a punch or assume nine hours |
| greytHR unavailable | Retry; use last successful sync as provisional; show stale warning; authorized CSV import for missing range | Silently replace source with internal records for the same employee-day |
| Linear unavailable | Preserve last snapshot, show stale state, retry/webhook reconciliation | Block all planning if valid links are already stored |
| Certification delayed | Escalate to configured approver/delegate and Procurement visibility | Turn Linear Done into acceptance |
| Email confirmation delayed | Reminder, alternate authorized confirmer, secure link, captured reply/manual evidence | Treat delivery receipt or no response as confirmation |
| Capacity/work exists outside original baseline | Formal revision/carry-forward with version and approval | Alter frozen baseline in place |

---

## 9. Success measures

| KPI | Initial target | Calculation guardrail |
|---|---:|---|
| Employee-days resolved before attendance close | ≥ 99% | Excludes weekends/holidays; unresolved exceptions visible |
| Months with approved baseline before start | ≥ 95% | Retroactive plans separately labeled |
| Linked Linear issues with fresh sync | ≥ 98% | Freshness threshold configurable; inaccessible links separate |
| Deliverables certified by configured deadline | ≥ 95% | No timeout auto-certification in numerator |
| Months with verified consolidated confirmation | 100% before invoice-ready | Procurement exception is reported separately, not counted as confirmation |
| Evidence package generation success | ≥ 99.5% | Retry-safe and versioned |
| Cross-tenant authorization failures in security tests | 0 | UI and direct API/RLS tests |
| Duplicate webhook/import double-processing | 0 | Idempotency tests |
| Historical imported rows reconciled | 100% | Source count, accepted count, rejected count and checksum |

---

## 10. Product release slices

- **MVP-A:** secure tenant/core masters, employee/calendar/leave/internal attendance, monthly plans, manual Linear links, certification, confirmation link, evidence package.
- **MVP-B:** greytHR sync, Linear OAuth/webhooks, email-reply ingestion, historical bulk import, integration control tower.
- **MVP-C:** SAP/payment adapter, advanced analytics, configurable additional engagement models and AI-assisted quality checks.

MVP slicing must not compromise RLS, audit, snapshots or evidence integrity.
