# 11 — Historical Migration, Backfill and Retroactive Approvals

**Version:** 1.0
**Status:** Build specification
**Related:** 04-10, 13, 18

---

## 1. Objective

Load and govern all relevant data from the engagement start on **1 June 2026** onward, allowing prior attendance, deliverables, delivery confirmation and invoices to be validated, approved/confirmed now where needed, and converted into reproducible evidence packages without falsifying dates or provenance.

---

## 2. Migration principles

1. **Stage, validate, preview, approve, commit** — never import directly into final tables.
2. **Masters before transactions** — organizations/projects/employees/calendars/mappings precede attendance/deliverables.
3. **No backdated audit fiction** — store represented historical date and actual recorded/imported timestamp separately.
4. **Preserve source evidence** — original file hash, row number, uploader and source description.
5. **Idempotent and resumable** — repeated import does not duplicate records.
6. **Explicit confidence/provenance** — distinguish original system export, approved spreadsheet, reconstructed data and newly obtained retroactive approval.
7. **Closed evidence is versioned** — corrections create new versions; no overwrite.
8. **Partial success is controlled** — valid rows can commit only when job policy allows and rejected rows remain visible/reprocessable.
9. **No commercial leakage** — reject salary/markup columns from templates; do not store them.

---

## 3. Historical month states

- `HISTORICAL_DRAFT`
- `HISTORICAL_IMPORT_IN_PROGRESS`
- `HISTORICAL_REVIEW`
- `HISTORICAL_PENDING_CERTIFICATION`
- `HISTORICAL_PENDING_CONFIRMATION`
- normal `CONFIRMED`, `INVOICE_READY`, `INVOICE_SUBMITTED`, `CLOSED`

Historical state is a provenance attribute, not a lower standard. Required evidence/approvals still apply, with disclosed unavailable items or Procurement exceptions.

---

## 4. Migration sequence

### Wave 1 — Foundation

1. Organizations and engagement.
2. Projects/teams/contact groups/approval policies.
3. Working/holiday calendars and policy versions.
4. Users/memberships/roles.
5. Employee master and greytHR mappings.

### Wave 2 — Workforce

6. Engagement/project allocations.
7. Leave opening balances and ledger adjustments.
8. Leave requests/outcomes.
9. Raw attendance punches or daily attendance summaries.
10. Regularization/corrections.

### Wave 3 — Delivery

11. Monthly plans and plan versions.
12. Deliverables, criteria, owners and employee assignments.
13. Linear issue links and available historical evidence/snapshots.
14. Plan approvals and commitment email evidence.
15. Vendor delivery outcomes/evidence.
16. Product-owner certifications.

### Wave 4 — Confirmation and invoice

17. Consolidated confirmation requests/responses or retro requests.
18. Invoices and original package/communications where available.
19. Generate normalized snapshots and evidence package.
20. Review, resolve exceptions and close.

The application must enforce dependencies and tell the operator which missing master caused each rejection.

---

## 5. Migration job model

### 5.1 Job fields

- job ID/type/template version;
- target organization/engagement/month/date range;
- original filename, MIME type, size and SHA-256;
- uploaded by/time;
- mode (`DRY_RUN`, `COMMIT`, `REPROCESS_REJECTS`, `SUPERSEDE`);
- duplicate policy;
- row counts/status;
- validation summary;
- approval record for high-impact/overwrite operations;
- parent/prior job;
- logs and output/error files.

### 5.2 Job states

`UPLOADED → SCANNING → PARSING → VALIDATING → READY_TO_COMMIT → COMMITTING → COMPLETED | COMPLETED_WITH_ERRORS | FAILED | CANCELLED | ROLLED_BACK`.

### 5.3 Row states

`VALID`, `WARNING`, `INVALID`, `DUPLICATE_IDENTICAL`, `DUPLICATE_CONFLICT`, `COMMITTED`, `REJECTED`, `SUPERSEDED`.

### 5.4 Idempotency

- File hash + template version + target scope identifies an identical upload.
- Natural/external keys identify row duplicates.
- Identical duplicate is skipped/reported.
- Conflicting duplicate requires explicit resolution: keep existing, supersede through versioned correction, or reject.
- Never update closed evidence by generic upsert.

---

## 6. Validation layers

### 6.1 File

- approved template/version and encoding;
- file size/type and malware scan;
- required sheet/header names;
- no unknown sensitive commercial columns;
- row limit and delimiter/date format.

### 6.2 Field

- required/non-null;
- formats/enums/date/time/timezone;
- numeric bounds;
- valid email/URL;
- duplicate columns/rows;
- narrative length and sanitization.

### 6.3 Referential

- employee/project/engagement/owner/approver exists or maps uniquely;
- Linear identifier format and optional API resolution;
- leave type/calendar/policy mapping;
- deliverable and certification lineage.

### 6.4 Temporal/business

- dates within employment/engagement/month;
- allocation overlap/percent;
- attendance classification consistent with calendar/leave;
- punch order and duration;
- plan approval date versus month start is classified on-time/late/retro, not rejected silently;
- certification decision exists for a deliverable version;
- confirmation actor was authorized or requires retro review;
- invoice billing period matches month.

### 6.5 Cross-file reconciliation

- employee counts versus roster source;
- expected workdays versus attendance rows;
- deliverable count and IDs across plan/certification;
- certification totals/status consistency;
- confirmation/package version linkage;
- invoice count/number uniqueness.

---

## 7. Provenance and confidence

Every imported business record has:

- `source_type`: `GREYTHR_EXPORT`, `LINEAR_API`, `LINEAR_EXPORT`, `ORIGINAL_EMAIL`, `SIGNED_DOCUMENT`, `APPROVED_SPREADSHEET`, `MANUAL_RECONSTRUCTION`, `OTHER`;
- source file/job/row;
- represented effective/decision timestamp;
- actual imported/recorded timestamp;
- source owner/approver;
- confidence: `HIGH`, `MEDIUM`, `LOW`, `UNVERIFIED`;
- notes/limitations.

Evidence reports disclose low/unverified items.

---

## 8. Attendance backfill

Supported inputs:

1. **Daily summary template** — expected date, in/out, net minutes, final status, leave, source.
2. **Raw punches template** — multiple events per employee/date; system derives sessions/day.
3. **greytHR/API/export** — normalized through PRD 06.

Rules:

- Do not load both raw and daily data as additive hours.
- If both exist, raw events can be retained as detail and daily source status reconciled according to authority.
- Generate all expected employee-days from calendar/allocation, then compare imported coverage.
- Missing days are errors/exceptions, not assumed present.
- Imported regularization/leave must reference source decision/evidence or be marked unverified.
- Month attendance snapshot occurs only after review.

---

## 9. Deliverable and Linear backfill

- Import plan/version/deliverable/criteria/owners/target dates.
- Classify plan as on-time, late approved or reconstructed.
- Import Linear URLs/identifiers and attempt resolution.
- Current API state captured now is `CURRENT_STATE_ONLY` unless historical events/export establish month-end state.
- Allow historical Linear export/event evidence upload and snapshot confidence.
- Missing link does not invent a ticket; record approved exception if permitted.

---

## 10. Historical certification and approval

### 10.1 Original evidenced decision

Where an original email/signed document/system export proves the decision:

- import the represented decision/actor/date;
- attach/hash source evidence;
- verify actor mapping/authority as far as possible;
- actual import time remains audit time;
- optional reviewer attests authenticity.

### 10.2 Missing original decision

Create a **retroactive approval/certification request** now:

- subject/body label it “Historical confirmation for June 2026”;
- product owner reviews reconstructed evidence;
- decision source/timestamp are current;
- record represented month separately;
- Procurement CC receives request/outcome;
- do not backdate the response.

### 10.3 Unavailable approver

Use configured delegation/replacement authority and record why original owner is unavailable. Do not use a random admin as business approver.

---

## 11. Historical consolidated confirmation

Three supported results:

- `ORIGINAL_CONFIRMED_EVIDENCE_IMPORTED`
- `RETROACTIVELY_CONFIRMED_NOW`
- `PROCUREMENT_EXCEPTION_ACCEPTED`

They remain distinct in dashboards/packages.

If an old email reply is imported, apply PRD 09 manual/verified evidence rules. If no response exists, send a new request; silence remains unconfirmed.

---

## 12. Reconciliation report and approval

Before commit/close, generate:

- source files and hashes;
- row totals by state/error code;
- expected versus imported employee-days;
- employee/leave/attendance exceptions;
- plan/deliverable/Linear coverage;
- certification/confirmation provenance;
- invoices and version linkage;
- low-confidence/unverified list;
- proposed records/snapshots/package;
- approver/sign-off.

A migration lead and business/governance reviewer approve the reconciliation for each month or batch.

---

## 13. Rollback and correction

- Pre-commit jobs can be canceled without canonical effect.
- Committed import has a batch ID on created records.
- If no downstream approval/snapshot exists, authorized rollback uses compensating/deactivation operations.
- Once evidence is approved/confirmed, correction uses normal reopen/version workflow, not hard delete.
- Original source file and job audit remain.

---

## 14. Migration UI

- Choose template/domain/scope/month.
- Download template/instructions.
- Upload and scan.
- Map headers/enums/external IDs when permitted.
- Validation dashboard with row grid, filters and downloadable errors.
- Fix in file/re-upload or resolve mapping conflicts in UI.
- Impact preview and required approval.
- Commit progress and resumable checkpoint.
- Month readiness/reconciliation report.
- Generate retro approval/confirmation tasks.

---

## 15. Acceptance tests

- Data before 1 June is rejected unless explicitly in engagement scope.
- Same file re-upload is identified and does not duplicate rows.
- Invalid employee reference rejects the row with stable error code.
- Partial import reports committed/rejected counts and permits safe reprocess of rejects.
- Raw punches and daily summary do not double-count work minutes.
- Missing expected attendance days appear as exceptions.
- Current Linear status imported in July is not labeled June month-end without historical source evidence.
- An original June approval email can be imported with represented date and actual import timestamp.
- A new retroactive approval records today's actual decision time, not a fake June timestamp.
- Salary/markup columns are rejected/not persisted.
- Closed historical package correction creates a new version and preserves prior package.
- Reconciliation report counts match canonical records and source hashes.
