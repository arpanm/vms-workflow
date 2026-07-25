# 15 — UI/UX, Information Architecture and Persona Flows

**Version:** 1.0
**Status:** Frontend build specification
**Related:** 01-14, 17

---

## 1. Objective

Transform the seven-page prototype into a complete, coherent governance product with all screens, forms, CTAs, dialogs, timelines, empty/error/loading states and role-specific flows required to complete the process without email/spreadsheet coordination gaps.

Use the existing React/TanStack/Radix-shadcn-style component approach. Reuse standard accessible components; do not create unnecessary bespoke UI primitives.

---

## 2. Experience principles

- Show the current month, state, blockers and next action immediately.
- Use progressive disclosure: summary first, evidence/audit detail on drill-down.
- A status is always accompanied by owner, deadline and source/freshness where relevant.
- Distinguish live, snapshot, imported and superseded data visually and textually.
- No critical action relies on color alone.
- High-impact actions show exact consequences and require explicit confirmation/reason.
- Employee attendance is mobile-friendly; governance/review is desktop-optimized and responsive.
- Never show a CTA the user cannot legally perform; server still enforces it.
- Avoid modal chains; use full-page/wizard flows for complex planning/import/close.

---

## 3. Primary navigation

### Home

- Dashboard / My Work
- Notifications

### Workforce

- Employees
- Allocations
- Attendance
- Leave & Regularization
- Calendars & Holidays
- Attendance Close

### Delivery

- Monthly Plans
- Deliverables
- Linear Work Items
- Delivery Submission
- Certification

### Month Close

- Readiness
- Business Confirmation
- Evidence Packages

### Finance & Procurement

- Invoices
- Procurement Review
- Payment Status

### Reports

- Dashboards/Control Tower
- Reports & Exports
- Audit Search

### Administration

- Organizations & Engagements
- Projects & Teams
- Users, Roles & Delegations
- Approval Policies
- Contact Groups & Templates
- Integrations
- Import/Migration
- System/Job Health

Navigation items are permission scoped. Preserve legacy routes behind feature flag or redirect.

---

## 4. Global shell

- Product/engagement selector (not a role impersonation control).
- Month selector with state badge.
- Search/command entry for allowed objects.
- Notification/task indicator.
- User/organization menu and sign out.
- Environment/demo banner where applicable.
- Breadcrumbs and page-level actions.
- Stale/source warning banner on relevant pages.
- Accessibility skip link and keyboard navigation.

The current top-right demo role dropdown appears only in explicit demo mode and never grants production permissions.

---

## 5. Home / My Work

### Components

- engagement-month status strip;
- “Your actions” cards ordered by urgency;
- evidence readiness pillars;
- workforce/delivery/invoice KPIs based on role;
- recent notifications and activity;
- integration health warning;
- upcoming deadlines/calendar.

### CTAs

Examples: Check in, Fix attendance, Review leave, Complete plan, Certify delivery, Confirm month, Resolve package blocker, Review invoice.

### States

- no assigned engagement;
- no open tasks;
- month not initialized;
- partial data/stale integration;
- unauthorized object link.

---

## 6. Employee self-service flows

### 6.1 Today / attendance

Card shows calendar state, expected net minutes, source mode, current session and check-in/out button.

Dialogs:

- check-in confirmation only if policy needs location/device declaration;
- checkout summary with sessions/net minutes and short-hours warning;
- duplicate/open-session recovery;
- source unavailable/read-only explanation.

### 6.2 Attendance calendar/detail

- monthly calendar/list toggle;
- status, raw and credited minutes;
- session timeline and break deduction;
- leave/regularization badges;
- source/freshness;
- CTA to regularize/apply leave where eligible.

### 6.3 Apply leave

Wizard:

1. dates/units/type;
2. balance and LWP split preview;
3. reason/evidence/handover;
4. approver/policy summary;
5. submit and timeline.

Supports edit draft, cancel, respond to information request.

### 6.4 Regularization

- choose date/exception;
- see raw data and policy outcome;
- select reason/proposed correction;
- attach evidence;
- preview resulting status;
- submit/timeline.

### 6.5 My assignments

Project/allocation timeline, monthly deliverables and Linear links (read access permitted), owner/contact.

---

## 7. Workforce administration

### 7.1 Employee list

Table/cards with name, number, status, project/allocation, calendar, attendance source, mapping and exception badges. Filters/search/bulk import/actions.

Primary CTAs: Add employee, Import, Bulk assign, Export roster.

### 7.2 Employee create/edit wizard

1. identity/employment;
2. engagement/project allocation;
3. calendar/attendance/leave policies and source;
4. opening leave balances;
5. user invite/greytHR mapping;
6. review/activate.

Edit shows effective-date and impact preview. Disable/exit are separate destructive dialogs with reason.

### 7.3 Employee profile

Tabs defined in PRD 04 with audit timeline and version/effective-date views.

### 7.4 Allocations

Timeline/Gantt-style and table views; over-allocation warnings; add/end/change effective-dated allocation; roster snapshot comparison.

### 7.5 Calendars & holidays

- calendar list/version status;
- weekly pattern editor;
- holiday import/add/edit;
- employee/group override;
- “resolve date for employee” diagnostic;
- impact preview/publish version.

### 7.6 Leave balances

- balances/ledger by employee/type;
- grant/accrual preview;
- manual adjustment with reason/approval;
- import and reconciliation.

### 7.7 Attendance exception inbox

Master-detail queue with filters, bulk reminders, source comparison, request/correction decision, aging and downstream impact.

### 7.8 Attendance close

Checklist by employee/day/source, blockers, snapshots, reviewer declaration, close CTA. Reopen action is separate and permission guarded.

---

## 8. Monthly planning and deliverables

### 8.1 Plans list

Month, status, version, owner, deadlines, deliverable count, Linear coverage, approval and email status. CTAs create/continue/review/compare versions.

### 8.2 Plan builder

Full-page flow:

- plan header/business outcomes;
- deliverable rows/cards with drag rank/filter;
- completeness panel;
- employee allocation coverage;
- dependencies/risks;
- Linear search/link and sync status;
- recipient preview;
- save draft/validate/submit.

Do not auto-freeze at midnight without a completed approval. Deadline changes risk/escalation status.

### 8.3 Deliverable editor

Sections:

- outcome/business context;
- project/owners/priority/target;
- acceptance criteria editor;
- expected evidence;
- employee contributors;
- Linear issues;
- dependencies/assumptions;
- attachments.

Inline validation and “why required” help.

### 8.4 Plan review/approval

Read-only version with diff, exceptions and approval panel. Approve/request changes/reject. Approval dialog displays version checksum and email preview.

### 8.5 Revision

Compare old/new side-by-side with added/removed/changed fields; impact statement and reapproval.

---

## 9. Linear integration UX

### Link picker

Paste URL/identifier or search. Results show team, state, assignee, project and access status. Duplicate/multi-deliverable warnings.

### Issue card/detail

- original state + normalized state;
- assignee/priority/labels/dates;
- plan snapshot, month-end snapshot and current state tabs/diff;
- last sync/webhook and refresh;
- open in Linear;
- inaccessible/deleted/stale error CTA.

### Integration admin

OAuth connect/reconnect, workspace/team scope, webhook health, mapping/state rules, sync history, dead-letter replay and test.

---

## 10. Delivery submission and certification

### 10.1 Vendor submission

Month overview with completeness. Per deliverable:

- baseline summary;
- outcome/percentage/date;
- criterion responses;
- evidence uploader/links;
- Linear snapshot;
- variance/carry-forward;
- save draft.

Submit wizard validates all items, shows declaration and locks version.

### 10.2 Product-owner certification inbox

Assigned reviews by month/project/aging. Detail is a three-column/section layout: baseline, vendor outcome/evidence, decision.

Actions: accept, accept with observations, partial, defer, reject, request information. Non-accept decisions reveal required fields.

### 10.3 Clarification thread

Object-bound messages/evidence with SLA and immutable prior submission. Avoid external free-form email as the only record.

### 10.4 Monthly certification complete

Summary, decisions, carry-forward, approvers and send-certification-email state.

---

## 11. Month close and confirmation

### 11.1 Readiness page

Five prominent pillars:

1. roster/allocation;
2. attendance;
3. approved plan/Linear;
4. certification;
5. confirmation/package/invoice.

Each shows complete/warning/blocking, version/source/freshness, owner and action.

### 11.2 Confirmation request preview

Exact recipients/CC, summary, attachment/package version, due date and actions. Send/test/resend permitted by role.

### 11.3 Confirmation response page

Authenticated user sees exact version and diff; Confirm/Request Correction/Reject with comment. Success page shows outcome and audit reference.

### 11.4 Inbound/manual evidence review

Side-by-side original message metadata/content and proposed interpreted decision; verify/reject/route. Warn when sender/thread/authentication is weak.

### 11.5 Version lineage/reopen

Timeline of baseline, attendance, certification, confirmation, package and invoice versions. Reopen wizard shows downstream invalidations and required rework.

---

## 12. Invoice/procurement UX

### Invoice list/detail

Status, month, number/date/amount metadata, readiness, package, confirmation, Procurement/payment aging. Upload/replace/submit actions per state.

### Readiness checklist

Every rule with result, source object/version and remediation CTA. Distinguish exception accepted.

### Package viewer

Contents/manifest/checksums, download, prior versions, access log. Human-readable preview and machine manifest.

### Procurement review

Approve/process, request changes, hold, reject or accept exception. Exception dialog lists exact failed rules and requires rationale/authority.

### Payment status

Timeline and external references; vendor sees sanitized updates.

---

## 13. Historical import UX

Wizard and validation grid from PRD 11/18:

- choose domain/month/template;
- upload/scan;
- map/validate;
- errors/warnings and downloadable correction file;
- impact preview;
- commit/progress;
- reconciliation report;
- create retro approval/certification/confirmation tasks.

Never hide partial failures behind a generic success toast.

---

## 14. Administration UX

- Organization/engagement/project master and configuration versioning.
- Users/roles/delegations/access review.
- Approval policy visual builder/simulator.
- Contact groups and notification template preview/test.
- Integrations and secrets via masked connection forms.
- Job/queue health and replay.
- Audit search with field-level permissions.
- Feature flags visible only to platform admin.

---

## 15. Common component requirements

- `StatusBadge` with text/icon/tooltip.
- `SourceFreshnessBadge`.
- `VersionBadge` and `VersionDiff`.
- `EvidenceChecklist`.
- `ApprovalTimeline`.
- `AuditTimeline`.
- `ExceptionPanel`.
- `User/GroupPicker` scoped search.
- `FileUploader` with scan/progress/error.
- `DateRange/MonthPicker` with timezone.
- `DataTable` with server pagination/filter/export.
- `EmptyState`, `ErrorState`, `StaleDataBanner`, `PermissionDenied`.
- Confirmation/destructive-action dialog with impact, not generic “Are you sure?”.

---

## 16. Loading, errors and concurrency

- Skeletons for initial load; preserve table layout.
- Mutations disable duplicate action and show progress.
- Optimistic UI only for low-risk reversible actions; approvals/check-in/package generation wait for server result.
- Version conflict shows who changed it and offers reload/compare, not overwrite.
- Integration errors show last known data and retry/admin action.
- Offline check-in is not supported unless separately designed; do not fake success. Display network failure clearly.
- Every error has correlation ID for support.

---

## 17. Responsive and accessibility

- WCAG 2.1 AA target for relevant web flows.
- Keyboard-operable navigation/forms/dialogs/tables.
- Labels, focus order, error summary and ARIA where appropriate.
- Minimum touch target for mobile check-in/out.
- Charts have table/text alternatives.
- Color contrast and non-color status cues.
- Date/time/status language is clear; avoid abbreviations without explanation.
- Desktop complex tables collapse to cards/details on small screens rather than horizontal unusable layouts.

---

## 18. Acceptance criteria

- Every canonical workflow can be completed end-to-end through UI without direct database edits.
- All pages have loading, empty, error, permission denied and stale-data states.
- Demo role switcher is absent in production mode.
- Employee can complete check-in/out, leave and regularization on mobile viewport.
- Frozen/closed records render read-only with version/reopen CTA, not editable controls.
- Product owner can compare baseline, vendor evidence, Linear snapshots and certify from one workflow.
- Confirmation page binds visibly to exact month/package version.
- Procurement can identify every blocker and navigate to its source record.
- Historical import displays row-level errors and does not report partial commit as full success.
- Keyboard-only user can complete critical forms and dialogs.
- Unauthorized deep link produces safe denial without leaking record details.
