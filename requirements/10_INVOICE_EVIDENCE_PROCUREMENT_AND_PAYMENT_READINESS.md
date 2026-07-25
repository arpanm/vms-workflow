# 10 — Invoice Evidence, Procurement Review and Payment Readiness

**Version:** 1.0
**Status:** Build specification
**Related:** 08, 09, 11, 13, 14

---

## 1. Objective

Generate a complete, attributable and reproducible evidence package for each ArrowFoundry monthly invoice, validate mandatory proof, support Central Procurement review and track processing/payment status without implementing salary, markup or rate-card calculations.

---

## 2. Invoice scope and commercial-data boundary

The system may store invoice-document metadata required for identification and workflow:

- invoice number/date/billing period;
- vendor/client/engagement;
- PO/work-order/reference number;
- currency, taxable value, taxes and total invoice amount where present on the invoice;
- uploaded invoice file/hash/version;
- submission, procurement and payment status.

The system must **not** store or derive:

- employee salary/CTC;
- individual resource cost/rate;
- ten-percent or any other markup computation;
- vendor margin;
- payroll details;
- employee-level invoice amount allocation.

Attendance/leave/LWP is evidence. Any commercial deduction/adjustment remains an external approved invoice decision and may be recorded only as a high-level invoice adjustment reference/reason, never calculated from salary here.

---

## 3. Invoice record

### 3.1 Fields

- invoice ID;
- engagement month and vendor/client;
- invoice number/date and billing period;
- PO/contract/work-order refs;
- currency and optional invoice totals/tax metadata;
- invoice file and hash;
- vendor submitter and timestamps;
- linked evidence package version;
- readiness validation version/result;
- procurement review/action;
- finance/payment reference/status/date;
- rejection/hold reasons;
- superseded/credit-note/corrected-invoice relation;
- audit metadata.

### 3.2 Uniqueness

- Invoice number unique per vendor legal organization, subject to normalized case/spacing policy.
- One active primary invoice per engagement month by default; corrected/replacement invoices create version/lineage.
- Credit/debit notes are linked separately.

### 3.3 States

```text
DRAFT
 -> UPLOADED
 -> EVIDENCE_PENDING
 -> READY_FOR_VENDOR_SUBMISSION
 -> SUBMITTED_TO_PROCUREMENT
 -> PROCUREMENT_REVIEW
 -> APPROVED_FOR_PROCESSING | CHANGES_REQUESTED | ON_HOLD | REJECTED
 -> PAYMENT_INITIATED
 -> PAID
 -> CLOSED
```

Additional: `SUPERSEDED`, `CANCELLED`, `EXCEPTION_ACCEPTED`.

`PAID` is a recorded/integrated status; it does not imply the platform moved money.

---

## 4. Readiness checklist

### 4.1 Mandatory evidence pillars

1. **Engagement and month identity** — active contract/PO refs and represented period.
2. **Approved roster/allocation snapshot** — employees deployed and effective projects.
3. **Attendance snapshot** — all expected employee-days, leave/LWP/regularization and exceptions.
4. **Approved monthly deliverable baseline** — including plan approval and commitment communication.
5. **Linear evidence snapshot** — plan-time/month-end state and sync quality/unavailability disclosure.
6. **Delivery submission and product-owner certification** — item-level and monthly summary.
7. **Verified consolidated business confirmation** — email/link/in-app evidence and quorum.
8. **Invoice document/metadata** — file hash and identifiers.
9. **Audit/version manifest** — package version and included object checksums.

### 4.2 Validation outcomes

- `PASS`
- `PASS_WITH_DISCLOSED_NON_BLOCKING_EXCEPTION`
- `BLOCKED_MISSING_EVIDENCE`
- `BLOCKED_INVALID_VERSION`
- `BLOCKED_CONFIRMATION_PENDING`
- `BLOCKED_REOPEN_OR_CORRECTION`
- `EXCEPTION_ACCEPTED_BY_PROCUREMENT`

Readiness validation is deterministic, versioned and repeatable. It identifies each failed rule, object, owner and CTA.

### 4.3 Blocking defaults

- Missing/invalid business confirmation.
- Missing attendance snapshot or unresolved blocking source conflicts.
- Missing approved plan/certification.
- Evidence package based on superseded data.
- Invoice billing period not matching engagement month.
- Invalid/malware-failed invoice file.

Non-blocking exceptions must be configured and disclosed, not silently ignored.

---

## 5. Procurement evidence package

### 5.1 Package formats

- human-readable PDF summary/cover and appendices;
- machine-readable JSON manifest;
- CSV/XLSX exports where operationally useful (attendance register, deliverables, exceptions);
- original approved/confirmed communications and invoice copy or secure references;
- ZIP archive if allowed by file/security policy.

### 5.2 Required package contents

1. Cover page: engagement, parties, month, invoice, package version/status.
2. Executive evidence checklist and exceptions.
3. Approved roster/allocation summary.
4. Employee attendance register and aggregate summary.
5. Leave/LWP/regularization and unresolved-exception appendix.
6. Approved deliverable baseline and plan approval.
7. Linear linked-issue plan/month-end snapshot and freshness/confidence.
8. Vendor delivery submission.
9. Product-owner certification and observations/carry-forward.
10. Consolidated confirmation request and verified response evidence.
11. Invoice metadata/copy.
12. Audit summary and version lineage.
13. Manifest listing every artifact ID/version/checksum/source.

### 5.3 Package manifest

For each included artifact:

- logical type/name;
- business object ID/version;
- source/provenance;
- generated/recorded timestamps;
- file path/object storage version;
- MIME type/size;
- SHA-256 hash;
- classification/retention;
- signer/approver refs where applicable.

Package-level checksum is computed over canonical manifest content. Rendering timestamps that would change on regeneration are versioned explicitly.

### 5.4 Determinism and regeneration

- Generating from the same evidence-version set creates the same canonical manifest and content hashes.
- Regeneration after no source change may reuse the existing package or create a render copy pointing to the same canonical version.
- Any source-version change creates a new package version and marks prior package current/superseded as appropriate.
- Previous packages are never deleted merely because a newer version exists.

### 5.5 Access

- Authenticated, scoped access with signed/expiring download URLs.
- Download/view audit.
- Procurement can download package without seeing unrelated employee PII.
- External sharing, if permitted, uses explicit share record, expiry and revocation.

---

## 6. Procurement review

### 6.1 Review screen

- invoice and package header;
- readiness checklist with drill-down;
- key attendance/deliverable/certification/confirmation summaries;
- exceptions and source confidence;
- version history and recent changes;
- approve/process, request change, hold, reject or accept exception actions.

### 6.2 Decisions

- `APPROVED_FOR_PROCESSING`
- `CHANGES_REQUESTED`
- `ON_HOLD`
- `REJECTED`
- `EXCEPTION_ACCEPTED`

Every non-approval requires category/comment. Exception acceptance requires:

- exact failed rules/evidence;
- rationale and authority;
- validity limited to this invoice/package version unless stated;
- optional second approver;
- visibility in package and dashboards.

Exception acceptance does not rewrite missing confirmation as confirmed.

### 6.3 Changes requested

- creates task to responsible owner;
- blocks readiness;
- correction uses source-module workflow/reopen;
- new package/invoice version is submitted;
- original review remains in audit.

---

## 7. Invoice corrections and notes

- Draft invoice can be replaced; retain prior draft hash/version.
- Submitted invoice replacement requires withdrawal/change request and creates new version.
- Corrected invoice links to original and retains same month.
- Credit/debit note has its own file/number/date and relation.
- Any amount/tax change is document metadata; the system does not compute its basis.
- Package is regenerated if invoice copy/metadata is included.

---

## 8. Payment status

Initial manual/integration-ready states:

- `NOT_SUBMITTED`
- `SUBMITTED_TO_AP`
- `VALIDATION_IN_PROGRESS`
- `PAYMENT_SCHEDULED`
- `PAYMENT_INITIATED`
- `PAID`
- `PAYMENT_FAILED`
- `ON_HOLD`

Store external ERP/AP reference, status timestamp, expected/actual payment date, and sanitized comments. Future SAP integration uses an adapter/event contract without changing invoice evidence model.

Vendor receives status notifications but no internal restricted finance notes.

---

## 9. Timing and lifecycle

The invoice may be uploaded at/after delivery submission as configured, but cannot become procurement-ready until the evidence chain and confirmation are complete.

Recommended sequence:

1. draft/upload invoice;
2. complete certification and attendance close;
3. request/obtain business confirmation;
4. generate evidence package;
5. validate readiness;
6. submit package/invoice to Procurement;
7. Procurement review;
8. AP/payment tracking;
9. close month.

---

## 10. Historical invoices

- Import invoice metadata/file for June onward.
- Link to reconstructed/imported evidence objects.
- Generate package and retroactive confirmation requests where original evidence is incomplete.
- Preserve original invoice/submission dates as represented source values; actual import/confirmation timestamps remain current.
- Package discloses historical/reconstructed provenance and confidence.
- Do not alter paid status without documentary reference.

---

## 11. Dashboards and notifications

### Vendor

- months awaiting evidence/confirmation;
- ready to submit;
- Procurement changes/holds;
- payment status/aging.

### Procurement/finance

- readiness queue, missing pillar counts;
- confirmation pending/exception accepted;
- package/invoice aging;
- version/reopen alerts.

Notifications:

- invoice uploaded/ready/submitted;
- package generation failed/completed;
- confirmation received;
- review assigned/changes/hold/approval;
- payment initiated/paid/failed;
- closed month reopened, invalidating package.

---

## 12. Acceptance tests

- Invoice cannot become ready without verified confirmation unless Procurement records an explicit exception.
- An exception-accepted invoice remains visibly different from fully compliant/confirmed.
- No salary/rate/markup field exists in forms, API or evidence schema.
- Total invoice amount may be stored as document metadata but is never calculated from employees.
- Same source-version set creates identical canonical manifest hashes.
- Attendance correction after package generation invalidates readiness and requires a new package version.
- Prior package remains downloadable and marked superseded after regeneration.
- Duplicate invoice number for same vendor is rejected or linked as correction according to workflow.
- Procurement reviewer outside engagement scope cannot access package.
- Changes requested does not edit source evidence; it creates correction tasks.
- Payment status update does not change business confirmation or certification.
- Historical package discloses imported/reconstructed provenance and actual capture timestamps.
