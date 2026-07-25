# 08 — Delivery Evidence, Certification and Month-End Closure

**Version:** 1.0
**Status:** Build specification
**Related:** 05, 07, 09, 10, 11, 13

---

## 1. Objective

Provide a controlled end-of-month process in which ArrowFoundry declares the outcome of each approved deliverable, Reliance product owners evaluate it against the frozen baseline, and the system produces an immutable certification record for consolidated confirmation and invoice evidence.

---

## 2. Delivery submission

### 2.1 Who submits

An active `VENDOR_MANAGER` or authorized vendor delivery owner for the engagement/project. Employee contributors may add draft evidence if granted, but cannot submit the month by default.

### 2.2 Deliverable outcome states

- `COMPLETED`
- `COMPLETED_WITH_VARIANCE`
- `PARTIALLY_COMPLETED`
- `NOT_COMPLETED`
- `DEFERRED_BY_CLIENT`
- `DEFERRED_BY_VENDOR`
- `CANCELLED_BY_APPROVED_CHANGE`
- `NOT_APPLICABLE`

The outcome is vendor-declared until certified.

### 2.3 Submission fields per deliverable

- declared outcome;
- completion percentage (informational, 0–100);
- completion/deployment date;
- summary of work delivered;
- acceptance-criterion responses;
- evidence attachments/URLs and evidence type;
- linked Linear month-end snapshot/freshness;
- known limitations/open defects;
- variance from baseline and cause category;
- proposed carry-forward/new target month if applicable;
- contributor confirmation optional;
- vendor owner declaration.

Comments are mandatory for every state except uncomplicated `COMPLETED`.

### 2.4 Evidence types

Configurable catalog:

- deployed release/build/version;
- demo recording/screenshot;
- test report/evaluation result;
- design/architecture/PRD;
- pull request/repository link;
- production/monitoring metric;
- API/integration proof;
- user/business outcome report;
- Linear issues/snapshot;
- client dependency/evidence;
- other.

Sensitive source-code or restricted URLs should be referenced rather than copied unless package policy allows access.

### 2.5 Submission validation

- every effective baseline deliverable has an outcome;
- required evidence expectations are met or excepted;
- partial/not completed/deferred items have cause, impact and next action;
- all acceptance criteria are answered;
- month-end Linear snapshot attempted and status recorded;
- no unresolved plan revision is pending;
- submitter declaration accepted.

On submission, vendor-editable content is locked. Changes require withdrawal before review or a product-owner request for information.

---

## 3. Product-owner certification

### 3.1 Certification decisions per deliverable

- `ACCEPTED`
- `ACCEPTED_WITH_OBSERVATIONS`
- `PARTIALLY_ACCEPTED`
- `DEFERRED_CLIENT_DEPENDENCY`
- `DEFERRED_VENDOR_DEPENDENCY`
- `REJECTED`
- `CANCELLED_BY_APPROVED_CHANGE`
- `MORE_INFORMATION_REQUIRED`

### 3.2 Review context

Product owner sees:

- approved baseline and revision history;
- acceptance criteria and vendor responses;
- evidence links/attachments;
- plan-time, month-end and live Linear states;
- assigned contributors/allocations;
- known defects/limitations;
- dependency/change history;
- prior comments/questions.

### 3.3 Rules

- Only an owner/approver authorized for the deliverable/project can certify.
- Non-accepted decisions require comment, cause and next action.
- `ACCEPTED_WITH_OBSERVATIONS` requires observations but counts as accepted for evidence; observations remain visible.
- `PARTIALLY_ACCEPTED` requires accepted/rejected scope description and carry-forward decision.
- `MORE_INFORMATION_REQUIRED` returns the item to vendor response without erasing original submission.
- Linear status is evidence only; it never makes the decision.
- Acceptance criteria can be certified individually; aggregate decision must be consistent or explain override.
- Product owner cannot silently modify vendor submission; annotations/certification are separate records.

### 3.4 Certification history

Each action signs:

- deliverable and baseline/submission version hashes;
- decision/comments;
- actor/authority snapshot;
- source and timestamp;
- evidence viewed/added;
- approved carry-forward or exception.

---

## 4. Clarification cycle

1. Product owner chooses `MORE_INFORMATION_REQUIRED` and specific questions/evidence needs.
2. Vendor receives task and responds with additive evidence/comments.
3. Original submission stays immutable; response version is linked.
4. SLA timer may pause only according to policy and displays elapsed/paused time.
5. Product owner resumes decision.

Clarification cannot change the frozen baseline; scope changes use plan revision/change record.

---

## 5. Monthly certification summary

When all deliverables have terminal decisions, generate:

- counts by vendor outcome and certification decision;
- accepted/partial/rejected/deferred list;
- acceptance-criteria result summary;
- baseline/revision references;
- Linear plan/month-end status summary and freshness;
- delivery evidence index;
- variance and carry-forward register;
- product-owner decisions and timestamps;
- open observations/risks;
- checksum/version.

An optional overall monthly delivery decision can be `CERTIFIED`, `CERTIFIED_WITH_OBSERVATIONS`, `PARTIALLY_CERTIFIED`, or `NOT_CERTIFIED`, based on configurable policy and explicit authorized action. It is not inferred solely from percentages.

---

## 6. Carry-forward and deferred work

- Create a lineage record from original deliverable to next-month deliverable.
- Preserve original baseline and certification decision.
- New month deliverable gets new code/version but references origin.
- Cause owner (`CLIENT`, `VENDOR`, `JOINT`, `EXTERNAL`) and agreed next action are mandatory.
- Do not double-count the same outcome as delivered in two months.
- Dashboard separates new commitments and carry-forward.

---

## 7. Attendance and certification relationship

Certification can proceed while attendance exceptions are being finalized, but consolidated confirmation and invoice readiness require the configured attendance snapshot.

The product owner certifies business delivery, not individual payroll or cost. Attendance evidence proves deployment/attendance; it does not automatically determine deliverable acceptance.

---

## 8. Month-end readiness gate

To enter `CONFIRMATION_PENDING`:

- effective monthly plan is approved/frozen;
- delivery submission exists;
- all deliverables have terminal certification decisions;
- certification summary is generated;
- roster snapshot exists;
- attendance snapshot is closed or an authorized exception is recorded;
- Linear month-end snapshot exists/has explicit unavailable status;
- unresolved observations are classified as blocking/non-blocking;
- required recipients/confirmers are active;
- no pending approved-plan revision.

The readiness page shows blockers with owner and action CTA.

---

## 9. Month closure and immutability

### 9.1 Pre-close

- business confirmation verified;
- evidence package generated;
- invoice status meets engagement policy;
- Procurement review/exception complete if required;
- downstream invalidation flags cleared.

### 9.2 Close

System writes a closure record containing current version IDs and hashes. Included records become read-only through database/service controls.

### 9.3 Reopen

Authorized request must include:

- reason/category;
- impacted records and proposed corrections;
- whether invoice/package was already submitted;
- notification recipients;
- risk/impact statement.

Approval invalidates current readiness, creates a new version lineage and requires recertification/reconfirmation only for impacted evidence according to policy. Previous confirmation/package is retained and clearly marked superseded, never erased.

---

## 10. No-response and anti-bureaucracy behavior

Initial policy:

- reminders and escalation to delegate/backup product owners;
- governance and Procurement visibility on breached certification SLA;
- authorized reassignment/delegation;
- explicit Procurement exception route where business chooses to proceed;
- no automatic `ACCEPTED`/`CERTIFIED` merely because a deadline elapsed.

If a future legal/commercial policy authorizes deemed acceptance, it must be a separate state (`DEEMED_ACCEPTED_BY_POLICY`), versioned policy, visible in package, and must not masquerade as direct product-owner approval. Default is disabled.

---

## 11. Historical certification

For June onward backfill:

- import vendor outcome and product-owner decision separately;
- capture represented historical decision date if evidenced, plus actual system-recorded date;
- attach original emails/documents where available;
- missing historical approval triggers a new retroactive certification request, clearly labeled;
- do not backdate audit timestamps;
- historical current Linear state is not represented as historical month-end state without evidence.

---

## 12. UI flows

### Vendor month submission

- month overview and blockers;
- one card/row per deliverable;
- bulk “mark completed” allowed only with per-item evidence completeness and final review; no bulk certification;
- draft autosave and explicit submit declaration;
- locked submission timeline.

### Product-owner review

- assigned/pending list and aging;
- baseline versus submission comparison;
- criterion-by-criterion panel;
- Linear timeline/snapshot diff;
- evidence viewer;
- decision/comments/carry-forward form;
- monthly review completion wizard.

### Governance month close

- five evidence pillars with status: roster, attendance, plan, certification, confirmation;
- blockers, exceptions and responsible owner;
- version lineage and reopen history.

---

## 13. Notifications and SLAs

Configurable events:

- delivery submission due/overdue;
- submission received;
- review assignment/reminder/escalation;
- more information requested/responded;
- certification completed;
- partial/rejected/deferred decision;
- month ready for confirmation;
- reopen requested/approved/denied.

---

## 14. Acceptance tests

- Vendor cannot submit while a baseline deliverable lacks outcome/mandatory evidence response.
- Submitted vendor evidence becomes read-only; clarification adds a new response rather than overwriting.
- Product owner outside project scope cannot certify.
- `COMPLETED` Linear issues do not auto-generate `ACCEPTED` decision.
- Non-accepted decision without comment/cause is rejected.
- Partial acceptance creates explicit scope and carry-forward lineage.
- All terminal decisions generate a deterministic certification summary/version.
- Confirmation pending is blocked when attendance snapshot is missing, unless an authorized exception exists and is disclosed.
- Certification timeout sends escalation but does not manufacture approval.
- Closed-month edit is blocked; approved reopen creates new versions and invalidates downstream package/readiness.
- Historical retro-certification displays represented date and actual capture timestamp.
