# 12 — Dashboards, Reports, Exports and Control Tower

**Version:** 1.0
**Status:** Build specification
**Related:** 03-11, 13-16

---

## 1. Objective

Give each persona real-time, actionable visibility into workforce compliance, monthly commitments, delivery acceptance, confirmations, integrations and invoice evidence without exposing data outside their scope or confusing live status with historical snapshots.

---

## 2. Dashboard principles

- Action first: every exception metric drills to an owner/action queue.
- Definitions visible: metric tooltip/formula, source and freshness.
- Snapshot versus live state labeled clearly.
- Filters are permission scoped and retained in export metadata.
- No salary, resource rate or markup metrics.
- Avoid a misleading single “compliance score” without component disclosure.
- Zero/unknown/stale are distinct.
- Historical months use represented period and provenance badges.

---

## 3. Executive/engagement dashboard

### 3.1 Header

- engagement, month and state;
- plan/certification/confirmation deadlines;
- overall evidence readiness;
- current package/invoice version;
- data freshness/integration health.

### 3.2 KPI cards

- active deployed employees and allocation coverage;
- expected/resolved employee-days;
- full/half present, paid leave, LWP, absent and unresolved days;
- planned/accepted/partial/deferred/rejected deliverables;
- Linear linked/fresh/stale/inaccessible issue counts;
- pending approvals/certifications/confirmations;
- evidence pillars complete/blocked/exception accepted;
- invoice/procurement/payment state and aging.

### 3.3 Trend views

- attendance resolution and exceptions;
- leave/LWP/absence;
- deliverable acceptance/carry-forward;
- approval/confirmation cycle time;
- package/invoice aging;
- integration sync health.

All trends use comparable definitions and indicate policy changes.

---

## 4. Employee dashboard

- today's attendance and check-in/out CTA;
- work calendar/upcoming holidays;
- current month attendance calendar/status/minutes;
- unresolved attendance issues and regularization SLA;
- leave balances/projected balance and request status;
- project/deliverable assignments;
- notifications.

An employee sees only their own data unless a separate manager role exists.

---

## 5. Vendor HR/admin dashboard

- active/disabled/exited employee counts;
- unmapped greytHR employees;
- missing check-in/out, short-hours, absent/source-conflict queues;
- pending leave/regularization and aging;
- allocation >100%, missing assignment/calendar/policy;
- month attendance close readiness;
- leave accrual job health;
- historical import status.

---

## 6. Vendor delivery dashboard

- current/next month plan status;
- assigned deliverables and Linear progress;
- submission completeness;
- questions/changes requested;
- accepted/partial/deferred/rejected outcomes;
- confirmation/evidence/invoice status;
- carry-forward actions.

---

## 7. Product-owner dashboard

- plans to draft/review/approve;
- deliverables owned and upcoming target dates;
- linked Linear state, freshness and blockers;
- submissions awaiting certification;
- information requests and aging;
- month confirmation requests;
- attendance summary only to the level authorized for evidence, not unrestricted HR detail.

---

## 8. Procurement control tower

### 8.1 Readiness matrix

Rows: engagement months/invoices. Columns:

- roster;
- attendance;
- plan/commitment email;
- Linear snapshot;
- certification;
- consolidated confirmation;
- package;
- invoice;
- payment.

Cell states: complete, warning, blocking, exception accepted, stale, not applicable.

### 8.2 Queues

- confirmation overdue;
- missing/blocking attendance evidence;
- package superseded after reopen;
- invoice awaiting review/change/hold;
- Procurement exception requests;
- historical low-confidence items;
- payment aging.

### 8.3 Drill-down

Shows evidence version, actor/timestamp/source, exceptions and exact remediation owner. Procurement cannot edit attendance/deliverables directly.

---

## 9. Integration control tower

For greytHR, Linear and email adapters:

- connection status/auth expiry/revocation;
- last successful sync/message/webhook;
- current lag/freshness SLA;
- fetched/processed/rejected/conflicted counts;
- webhook signature failures/duplicates;
- dead-letter queue;
- mapping conflicts;
- manual refresh/replay controls by permission;
- provider/rate-limit/backoff status without exposing secrets.

---

## 10. Core reports

### Workforce

1. Employee roster and allocation history.
2. Daily attendance register.
3. Monthly attendance summary by employee/project.
4. Leave/LWP/absence summary.
5. Regularization/correction report.
6. Calendar/holiday/override report.
7. Attendance source/provenance and reconciliation report.
8. Leave balance/ledger report.

### Delivery

9. Approved monthly plan/baseline.
10. Deliverable register and owner/assignment.
11. Linear issue mapping/current/snapshot report.
12. Delivery submission/certification report.
13. Acceptance criteria result report.
14. Variance/deferred/carry-forward report.

### Evidence and procurement

15. Monthly readiness checklist.
16. Business confirmation register.
17. Evidence package/version register.
18. Invoice/procurement/payment aging.
19. Exception and reopen register.
20. Communication delivery/audit register.

### Administration/audit

21. User/role/delegation access review.
22. Approval SLA/action report.
23. Integration job/webhook/error report.
24. Historical migration/reconciliation report.
25. Immutable audit export.

---

## 11. Metric definitions

### Attendance resolution rate

`finalized expected employee-days / total expected employee-days`.

Weekly offs/holidays not expected are excluded from denominator; working-day overrides are included.

### Full attendance compliance

`employee-days meeting full expected minutes or approved full-day regularization / expected working employee-days`, with leave/partial days reported separately rather than hidden.

### Plan timeliness

Approved/frozen timestamp compared to month start/deadline; historical/late plans are separate categories.

### Deliverable acceptance rate

Report counts and optional weighted measure. `ACCEPTED_WITH_OBSERVATIONS` can count as accepted but remains separately visible. Partials must not be treated as fully accepted.

### Confirmation completion

Only verified confirmation satisfying quorum. Procurement exception and deemed-policy state are separate.

### Invoice readiness

All mandatory rules pass for current versions, or explicit Procurement exception exists. Show which.

### Integration freshness

Current time minus last successful source update for required date range, evaluated against connection policy.

---

## 12. Filters, search and drill-down

Global filters:

- month/date range;
- organization/engagement/project/team;
- employee;
- product/vendor owner;
- status/exception/source/provenance;
- invoice/Linear identifier.

Search is permission-aware and does not leak object existence through counts/autocomplete. Deep links preserve safe filters and version context.

---

## 13. Exports

- CSV/XLSX for tabular reports; PDF for approved summaries; JSON manifest for packages/integration audit.
- Export includes report name/version, generated by/time, filters, timezone, source freshness, snapshot/current label and row count.
- Large exports run asynchronously with secure download and expiry.
- PII masking and field-level permission apply to export, not only screen.
- Spreadsheet formulas are escaped to prevent CSV injection.
- Every export/download is audited.

---

## 14. Alerts and exception prioritization

Prioritize using severity, deadline, blocked downstream objects and age:

- P0: security/integrity incident, package corruption, cross-tenant exposure.
- P1: month/invoice blocked near deadline, confirmation invalid, attendance snapshot invalidated.
- P2: overdue approval/regularization, stale integration, mapping conflicts.
- P3: warnings, non-blocking observations and future deadlines.

Control tower shows suggested action and owner but does not autonomously take business approval actions.

---

## 15. Performance and freshness

- Current-month dashboards target initial load p95 ≤2.5 seconds for normal engagement size.
- Heavy reports/exports are async.
- Summary tables/materialized views may be used but must expose refresh time.
- Critical mutation results update relevant cards promptly; do not wait for nightly batch.
- Live Linear state can be eventually consistent; snapshot evidence remains stable.

---

## 16. Acceptance tests

- Employee cannot retrieve another employee's dashboard/export through modified URL/query.
- Product owner sees only assigned/project-scoped items.
- Procurement matrix clearly distinguishes confirmed from exception accepted.
- Attendance denominator correctly excludes weekly off/holiday but includes working override.
- Current Linear state and month-end snapshot are labeled separately.
- Stale integration data shows timestamp/warning, not as current truth.
- Export reflects current filters and permission masking, and is audited.
- CSV fields beginning with formula characters are escaped.
- Reopened month invalidates old readiness card and displays package lineage.
- Historical low-confidence data is visible and cannot be mistaken for original verified source.
- A zero metric and an unavailable metric render differently.
