# 18 — Import Templates, Field Dictionary and Sample Files

**Version:** 1.0
**Status:** Build/migration specification
**Related:** 04-11, 13, 16
**Template folder:** [`templates/`](templates/)

---

## 1. Objective

Define stable, versioned CSV templates for historical data from 1 June onward and controlled bulk administration. Cursor/Claude must implement downloadable template generation, validation, dry-run, import, error export and provenance exactly as described.

---

## 2. Common file rules

- UTF-8 CSV with header row; comma delimiter; quote fields containing commas/newlines.
- Template version stored in filename or a required `template_version` column where listed.
- ISO dates `YYYY-MM-DD`.
- Local timestamps `YYYY-MM-DDTHH:mm:ss` plus `timezone`, or UTC timestamps ending `Z`.
- Boolean: `true`/`false`.
- Enums use exact codes, not display labels.
- Empty optional field is blank; do not use `NA` unless it is a valid enum.
- External/natural keys are supplied; internal UUIDs are optional only for system export/reimport.
- Salary/rate/markup columns are prohibited and cause file-level rejection or ignored-with-security-warning according to policy; default reject.
- Spreadsheet formulas are treated as text and sanitized.
- Every import records file hash/job/row and source provenance.

---

## 3. Template catalog and order

| Order | File | Domain | Required before |
|---:|---|---|---|
| 1 | `01_employees_v1.csv` | Employee master | all employee-linked files |
| 2 | `02_employee_allocations_v1.csv` | Engagement/project allocation | attendance roster/deliverable assignments |
| 3 | `03_holidays_v1.csv` | Holiday calendar | attendance generation |
| 4 | `04_employee_date_overrides_v1.csv` | Working/off-day exceptions | attendance generation |
| 5 | `05_leave_balances_v1.csv` | Opening/grant/adjustment ledger | leave requests |
| 6 | `06_leave_requests_v1.csv` | Historical leave outcomes | attendance finalization |
| 7A | `07a_attendance_punches_v1.csv` | Raw attendance events | daily calculation |
| 7B | `07b_attendance_daily_v1.csv` | Processed daily attendance | snapshot/reconciliation |
| 8 | `08_deliverables_v1.csv` | Monthly plan/deliverables | Linear/certification |
| 9 | `09_deliverable_linear_links_v1.csv` | Linear issue linkage/history | snapshots/certification |
| 10 | `10_delivery_certifications_v1.csv` | Vendor outcome + client certification | confirmation |
| 11 | `11_business_confirmations_v1.csv` | Original/retro confirmation evidence | invoice readiness |
| 12 | `12_invoices_v1.csv` | Invoice metadata/document ref | package/procurement |
| 13 | `13_approval_history_v1.csv` | Optional historical approvals | evidence/audit reconstruction |

Use either raw punches or daily summary as the authority for a date range unless explicit reconciliation mode is selected. Never add both durations.

---

## 4. Common identity columns

Where applicable:

| Column | Description |
|---|---|
| `template_version` | exact `1` |
| `source_system` | `GREYTHR_EXPORT`, `LINEAR_EXPORT`, `ORIGINAL_EMAIL`, `APPROVED_SPREADSHEET`, `MANUAL_RECONSTRUCTION`, etc. |
| `source_reference` | report/file/document ID or description |
| `represented_at` | historical effective/decision timestamp where relevant |
| `organization_code` | configured organization natural key |
| `engagement_code` | configured engagement key |
| `project_code` | project key |
| `billing_month` | `YYYY-MM` |
| `employee_number` | vendor employee natural key |
| `notes` | sanitized narrative |

Internal IDs are resolved during validation and never guessed by name alone.

---

## 5. Employees template

File: `templates/01_employees_v1.csv`

| Column | Required | Validation |
|---|---:|---|
| `organization_code` | yes | active vendor org |
| `employee_number` | yes | unique within org |
| `first_name`, `last_name`, `display_name` | yes | non-empty |
| `work_email` | yes | valid, unique active |
| `join_date` | yes | ISO date |
| `exit_date` | no | ≥ join date |
| `employment_status` | yes | canonical enum |
| `designation`, `skill_category` | no | text |
| `manager_employee_number` | no | same org; no cycle |
| `timezone` | yes | IANA zone |
| `working_calendar_code` | yes | mapped/existing |
| `attendance_policy_code` | yes | mapped/existing |
| `leave_policy_code` | yes | mapped/existing |
| `attendance_source_mode` | yes | `GREYTHR_AUTHORITATIVE`, `INTERNAL_AUTHORITATIVE`, `HYBRID_TRANSITION`, `HISTORICAL_IMPORT` |
| `greythr_employee_ref` | no | unique mapping |
| `activation_status` | yes | `ENABLED`, `DISABLED` |
| `source_system`, `source_reference` | yes | provenance |

---

## 6. Allocations template

File: `templates/02_employee_allocations_v1.csv`

- employee number, engagement/project/team codes;
- valid from/to;
- deployment status;
- allocation percent;
- project role;
- primary flag;
- approved-by email/ref and represented approval time where historical;
- source/provenance.

Validation sums overlapping allocation percentages per date and requires override reason if >100%.

---

## 7. Holiday and override templates

### Holidays

- holiday calendar code/version;
- date/name/type;
- scope organization/engagement/project/location;
- full/half day and expected minutes;
- optional/restricted flag;
- source/approval.

### Employee date override

- employee/date;
- resulting classification (`WORKING`, `WEEKLY_OFF`, `HOLIDAY`, `HALF_DAY_EXPECTED`);
- expected minutes;
- reason/approver/source.

---

## 8. Leave templates

### Leave balances

Each row is a ledger entry, not a final mutable balance:

- employee, leave type;
- entry type;
- quantity days/units;
- effective date;
- reference/reason;
- approver/source/idempotency reference.

### Leave requests

One row per request day/unit or a parent request with unique `leave_request_external_id`:

- leave type/date/session/full/half;
- quantity;
- request/decision statuses;
- requested/represented decision timestamps;
- approver email;
- paid/LWP mapping;
- reason/evidence reference;
- source.

The importer derives/validates ledger consumption; it does not trust a supplied final balance.

---

## 9. Attendance templates

### 9.1 Raw punches

File: `templates/07a_attendance_punches_v1.csv`

- `attendance_event_external_id` unique;
- employee number;
- event type;
- occurred timestamp and timezone;
- source system/device optional;
- correction/supersession reference;
- justification/evidence for admin/imported event.

Events are immutable and paired/calculated by system.

### 9.2 Daily summary

File: `templates/07b_attendance_daily_v1.csv`

- employee/date/timezone;
- calendar classification and expected minutes;
- first-in/last-out optional;
- net worked minutes;
- final attendance status;
- paid leave/LWP units/type;
- regularization status/reference;
- source finalized/updated timestamp;
- source system/reference;
- exception/note.

Daily summary is validated against calendar/leave and does not synthesize raw events unless explicitly requested. If first/last times are included, net minutes remains independently validated.

---

## 10. Deliverables and Linear templates

### Deliverables

File: `templates/08_deliverables_v1.csv`

One row per deliverable; acceptance criteria may be pipe-separated only for simple imports or referenced by JSON/secondary sheet in generated XLSX. CSV fields include:

- billing month/plan external ID/version;
- plan type/status and represented approval data;
- deliverable code/title/description/objective;
- project/product-owner/vendor-owner emails;
- priority/target date/category;
- acceptance criteria;
- evidence expectations;
- dependencies/risks/assumptions;
- assigned employee numbers separated by `|`;
- baseline revision/source.

### Linear links

File: `templates/09_deliverable_linear_links_v1.csv`

- deliverable code/month;
- issue URL/identifier/UUID if known;
- relationship type;
- historical snapshot timestamp/state/name/type/assignee/completed/canceled/updated timestamps if from a proven export;
- snapshot confidence/source.

Current API retrieval at import time is stored separately and never substituted for supplied historical timestamp without labeling.

---

## 11. Certification template

File: `templates/10_delivery_certifications_v1.csv`

One row per deliverable decision:

- vendor declared outcome/percentage/completion date/summary/evidence refs/variance/carry-forward;
- client certification decision/comment;
- product owner email;
- represented certification timestamp;
- acceptance-criteria result summary;
- original evidence source/reference;
- confidence.

Vendor outcome and client decision are distinct columns/records.

---

## 12. Confirmation template

File: `templates/11_business_confirmations_v1.csv`

- month/confirmation external ID;
- request subject/message ID/sent timestamp/To/CC;
- confirmed package/snapshot version reference;
- decision (`CONFIRMED`, `CHANGES_REQUESTED`, `REJECTED`, `PROCUREMENT_EXCEPTION_ACCEPTED`);
- actor email/represented response timestamp;
- response message ID/thread/reference;
- evidence file path/reference/hash if already known;
- capture method/provenance/confidence;
- reviewer email/comment.

Original evidence file must be uploaded separately and associated by import job or secure object reference.

---

## 13. Invoice template

File: `templates/12_invoices_v1.csv`

- vendor/client/engagement/month;
- invoice number/date/billing start/end;
- PO/work-order refs;
- currency/taxable/tax/total amounts optional;
- document filename/reference/hash;
- represented upload/submission/procurement/payment status timestamps;
- external AP/payment ref;
- source/provenance.

No employee salary/rate/markup columns.

---

## 14. Approval history template

File: `templates/13_approval_history_v1.csv`

Optional for original plan/certification/reopen/procurement decisions:

- object type/external ID/version/month;
- action/decision;
- actor email/organization/role if known;
- represented timestamp;
- comment;
- message/document/evidence reference;
- source/confidence.

The importer verifies actor mapping/authority or marks for review; it never treats a row as authoritative solely because the spreadsheet says “approved.”

---

## 15. Stable validation/error codes

Examples:

- `FILE_TEMPLATE_VERSION_UNSUPPORTED`
- `FILE_PROHIBITED_COMMERCIAL_COLUMN`
- `FIELD_REQUIRED`
- `FIELD_INVALID_DATE`
- `FIELD_INVALID_ENUM`
- `FIELD_INVALID_EMAIL`
- `REFERENCE_ORGANIZATION_NOT_FOUND`
- `REFERENCE_EMPLOYEE_NOT_FOUND`
- `REFERENCE_EMPLOYEE_AMBIGUOUS`
- `REFERENCE_PROJECT_NOT_FOUND`
- `TEMPORAL_OUTSIDE_EMPLOYMENT`
- `TEMPORAL_OUTSIDE_ENGAGEMENT`
- `ALLOCATION_OVER_100_PERCENT`
- `ATTENDANCE_EVENT_ORDER_INVALID`
- `ATTENDANCE_DURATION_INCONSISTENT`
- `ATTENDANCE_SOURCE_CONFLICT`
- `LEAVE_BALANCE_INSUFFICIENT_LWP_REQUIRED`
- `LINEAR_LINK_INVALID`
- `LINEAR_HISTORICAL_STATE_UNPROVEN`
- `CERTIFICATION_APPROVER_UNAUTHORIZED`
- `CONFIRMATION_EVIDENCE_MISSING`
- `INVOICE_DUPLICATE_NUMBER`
- `DUPLICATE_IDENTICAL`
- `DUPLICATE_CONFLICT`
- `CLOSED_MONTH_REQUIRES_REOPEN`

Every error includes row, column, value (redacted where sensitive), message, severity and remediation.

A machine-readable catalog is provided in `schemas/import_error_codes.json`.

---

## 16. Template generation and UX

- Templates downloadable from Import screen and include current reference codes in optional lookup sheets for XLSX mode.
- CSV sample files in this pack are illustrative; production generator must use active tenant configuration.
- Header names/version are stable; adding optional columns creates minor template version, breaking changes create major version.
- Import page shows template docs and accepted enums.
- Error export contains original row plus error codes/comments without secrets.
- Corrected-row reimport can target prior job/rejections.

---

## 17. Acceptance tests

- Sample files parse successfully after replacing reference codes with valid seeded data.
- Unknown/prohibited salary/markup column rejects file.
- Date/time/timezone parsing is deterministic.
- Manager/employee/project references resolve uniquely or reject.
- Same source file reupload does not duplicate rows.
- Overlapping allocation and attendance/leave conflicts produce stable codes.
- Current Linear API status is not accepted as historical snapshot without confidence label.
- Confirmation spreadsheet without evidence/eligible actor is pending review, not confirmed.
- Error export preserves row order and gives actionable field errors.
- Generated template and parser share the same schema version tests.
