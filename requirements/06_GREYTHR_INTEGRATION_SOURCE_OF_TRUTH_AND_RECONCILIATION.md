# 06 — greytHR Integration, Source of Truth and Reconciliation

**Version:** 1.0
**Status:** Build specification
**Related:** 04, 05, 11, 13, 14, 19, 20

---

## 1. Objective

Use ArrowFoundry's greytHR tenant as the authoritative employee/attendance source where its licensed APIs and data quality support the procurement requirements. Otherwise, operate the complete internal attendance workflow from PRD 05. Never create a dual-source ambiguity.

---

## 2. Research-based integration position

Official greytHR API V2 documentation describes OAuth-style client-token authentication and APIs across employee, leave and attendance modules; the employee API overview explicitly covers listing, individual details, adding and updating employees. Official greytHR administration help also documents attendance policies, multiple marking methods, swipe APIs, manual processing/override, regularization, calendars and attendance finalization.

Public documentation does **not** establish which endpoints, fields, write capabilities or plan entitlements are enabled in ArrowFoundry's tenant. Therefore, production enablement requires a tenant-specific capability certification and cannot be assumed from public documentation alone.

References are listed in PRD 19.

---

## 3. Attendance source modes

Configure per organization or employee with effective dates.

### 3.1 `GREYTHR_AUTHORITATIVE`

- Employee identity, calendar, leave and attendance are synchronized from greytHR to the extent certified.
- In-app check-in/out is hidden or read-only unless an explicitly certified swipe-write mode is enabled.
- greytHR data is the final authority for the day; corrections originate in greytHR or pass an approved source-override workflow.
- This application stores normalized copies and immutable evidence snapshots.

### 3.2 `INTERNAL_AUTHORITATIVE`

- PRD 05 check-in/out, leave and regularization are fully enabled.
- greytHR may remain disconnected or be used only for non-authoritative employee reference comparison.
- Internal computed attendance is final.

### 3.3 `HYBRID_TRANSITION`

- Temporary migration/parallel-run mode only.
- Both sources are ingested and compared, but a configured source remains authoritative for each employee-date.
- Discrepancies create reconciliation cases.
- A planned effective date ends transition mode.
- It is prohibited to add hours or choose the more favorable result automatically.

### 3.4 `HISTORICAL_IMPORT`

- Authorized data import is authoritative for specified historical employee-days where neither live source is available.
- Provenance, source file, uploader, represented date and approval are mandatory.

---

## 4. Capability certification workflow

Before enabling greytHR:

1. Integration admin enters tenant base URL and secret reference, not raw secrets in UI logs.
2. System obtains a token and records only non-secret health metadata.
3. Run read-only discovery against a sandbox/test employee/date range.
4. Complete the matrix below with `SUPPORTED`, `PARTIAL`, `NOT_SUPPORTED`, `NOT_ENTITLED`, or `UNKNOWN`.
5. Compare sample data against greytHR UI/export and obtain ArrowFoundry HR sign-off.
6. Select source mode and effective date.
7. Run parallel reconciliation for configured days.
8. Publish the connection version; all changes are audited.

### 4.1 Certification matrix

| Capability | Required for full authoritative mode | Validation |
|---|---:|---|
| Employee list and stable external ID | yes | row and field comparison |
| Employee active/join/exit status | yes | effective-date comparison |
| Manager/organization attributes | desirable | mapping validation |
| Working calendar/shift/weekly off | yes, unless maintained internally with approved reconciliation | date-level sample |
| Holiday calendar | yes, unless approved internal calendar | yearly/date sample |
| Raw punches/swipes | desirable | event/order/timezone comparison |
| Processed daily attendance status/hours | yes | closed-day comparison |
| Leave request/status/type/units | yes if greytHR leave is authoritative | approved/pending/cancelled cases |
| Regularization/override outcome | yes if used in greytHR | before/after sample |
| Attendance finalization/closed period indicator | desirable | month close sample |
| Incremental change field or query filter | desirable | delta-sync test |
| Write attendance swipe | optional and disabled until certified | sandbox-only test |
| Webhook/event support | optional; do not assume | documented/tenant confirmation |

If required capabilities are unavailable, use internal authority and approved exports/imports for evidence.

---

## 5. Authentication and secrets

- Token endpoint follows tenant pattern `/uas/v1/oauth2/client-token` as documented by greytHR.
- Store client credentials in platform secret storage/Supabase Vault or deployment secret manager, never browser environment variables, source control or ordinary table columns.
- Fetch and refresh tokens server-side; do not persist access tokens longer than needed.
- Redact authorization headers and employee PII from logs.
- Connection record stores secret reference, tenant URL, scopes/capability metadata, owner, status and last test time.
- Rotation creates a new secret version and health test; old secret is revoked after successful cutover.
- Restrict egress to approved greytHR endpoints where infrastructure permits.

---

## 6. Employee mapping

### 6.1 Mapping keys

Priority:

1. stored greytHR immutable employee reference;
2. verified employee number;
3. verified work email;
4. manual mapping after candidate review.

Name-only automatic matching is prohibited.

### 6.2 Mapping states

`UNMAPPED`, `AUTO_MATCHED_PENDING_REVIEW`, `MAPPED`, `CONFLICT`, `IGNORED_WITH_REASON`, `INACTIVE`.

### 6.3 Reconciliation cases

- one greytHR record matches multiple internal employees;
- employee number reused/changed;
- email changed;
- active status differs;
- join/exit date differs;
- greytHR employee not in engagement;
- internal employee absent in greytHR.

A mapping decision records candidate values, actor, reason and effective date. Historical imported records retain the mapping version used.

---

## 7. Data synchronized

### 7.1 Employee

Minimum normalized fields when available:

- external employee ID/number;
- name and work email;
- join/exit/employment status;
- manager/department/designation;
- updated timestamp/source.

Do not import salary/payroll fields. Query only allowlisted fields; discard unexpected commercial fields.

### 7.2 Attendance

Normalize:

- employee/date/timezone;
- raw punches where available;
- processed in/out/worked minutes;
- attendance status/shift/calendar;
- exception/regularization identifiers and status;
- source updated/finalized timestamps;
- raw-source payload hash and restricted raw archive where policy permits.

### 7.3 Leave

Normalize leave type, paid/unpaid classification mapping, dates/units, request status, approver and source update time. Keep source type/name and internal canonical mapping.

### 7.4 Calendar/holiday

Import or reconcile work pattern, shifts, weekly offs and holidays when supported. A source calendar change is versioned and impact-analyzed rather than rewriting closed months.

---

## 8. Sync architecture

### 8.1 Jobs

- `greythr_connection_health`: token and minimal endpoint test.
- `greythr_employee_full_sync`: initial/controlled full sync.
- `greythr_employee_delta_sync`: scheduled incremental sync where supported.
- `greythr_attendance_daily_sync`: current/recent date window.
- `greythr_attendance_month_reconcile`: broader month window before close.
- `greythr_leave_sync`.
- `greythr_calendar_sync`.
- `greythr_backfill_sync`: explicit date range, rate controlled.

### 8.2 Staging-to-canonical pattern

1. Fetch page/batch.
2. Store job/batch metadata and raw payload checksum in restricted staging.
3. Validate schema and required fields.
4. Resolve employee/type mappings.
5. Upsert external-version record idempotently.
6. Recompute affected attendance days.
7. Create conflicts instead of overwriting authoritative internal/manual decisions.
8. Record counts: fetched, unchanged, inserted, updated, conflicted, rejected.

### 8.3 Idempotency

Use `(connection_id, entity_type, external_id, external_updated_at or payload_hash)` and job batch keys. Reprocessing the same source version creates no duplicate events/leave entries.

### 8.4 Cadence

Initial defaults, configurable and tenant-rate-aware:

- employee/leave/calendar: several scheduled syncs daily plus on-demand refresh;
- current attendance: periodic during workday and after day close;
- month reconciliation: nightly and immediately before attendance snapshot;
- no aggressive polling that breaches provider limits.

---

## 9. Source reconciliation

For each employee-day compare:

- expected calendar state;
- first/last punch and session count;
- net minutes;
- canonical status;
- leave units/type;
- regularization state;
- source last updated/finalized time.

Tolerance configuration can ignore harmless minute/rounding differences for alert severity, but evidence retains exact values.

### 9.1 Conflict states

- `IDENTICAL`
- `WITHIN_TOLERANCE`
- `STATUS_MISMATCH`
- `DURATION_MISMATCH`
- `LEAVE_MISMATCH`
- `CALENDAR_MISMATCH`
- `MISSING_IN_GREYTHR`
- `MISSING_INTERNAL`
- `STALE_SOURCE`
- `MAPPING_ERROR`

### 9.2 Resolution

Allowed decisions:

- accept greytHR as configured authority;
- correct in greytHR and resync;
- accept approved internal override for specific date;
- mark legitimate difference with reason;
- defer/block month close.

Resolution never deletes either source record and must show downstream evidence impact.

---

## 10. Optional swipe write-back

Only build/enable if ArrowFoundry confirms the official attendance swipe API, credentials and policy permit it.

- Internal UI captures event.
- Server queues an outbound swipe with deterministic idempotency key.
- UI shows `PENDING_GREYTHR`, `ACCEPTED`, `REJECTED`, or `RECONCILIATION_REQUIRED`.
- A locally captured event is not final greytHR attendance until acknowledged and processed.
- Failed write-back never silently switches authority.
- Do not implement leave or regularization write-back unless separately certified.

Default release position: read/sync or internal authority, not dual-write.

---

## 11. Failure and fallback behavior

| Failure | Behavior |
|---|---|
| Authentication failure | Disable sync, alert integration admin, retain last data with stale banner |
| Provider timeout/5xx | exponential backoff with jitter; bounded retries; dead-letter after threshold |
| Schema/field change | quarantine batch; do not apply partial unsafe mapping; alert |
| Employee mapping missing | reject affected rows to reconciliation queue; continue unaffected rows |
| Partial page failure | checkpoint successful pages, resume safely |
| Extended outage near close | approved greytHR export/CSV import with source provenance; mark integration exception |
| Source changed after snapshot | create post-close discrepancy; do not mutate snapshot |

Month close policy decides whether a stale source is blocking. The UI must state the last successful sync and affected date range.

---

## 12. Integration administration UI

- Connection status, tenant and mode/effective date.
- Capability matrix and sign-off evidence.
- Last success/failure by entity/date range.
- Job history with counts/duration/error categories.
- Mapping and conflict queues.
- Test connection and read-only sample preview.
- On-demand sync/backfill with date range, estimated impact and confirmation.
- Replay dead-lettered batches.
- Secret rotation action without exposing secret.
- Source-switch wizard with parallel-run comparison and impact preview.

---

## 13. Evidence requirements

Attendance reports show:

- source mode and connection version;
- last reconciliation/close timestamp;
- greytHR source identifiers where appropriate;
- imported/overridden employee-days and reasons;
- unresolved/non-blocking exceptions;
- snapshot checksum.

Do not embed raw API credentials or unrestricted raw payloads in evidence packages.

---

## 14. Acceptance tests

- Tenant capability test fails safely when an endpoint is not entitled; mode cannot be published as fully authoritative.
- Employee mapping never auto-matches solely by name.
- The same API page/job replay produces no duplicate attendance events or leave ledger entries.
- A greytHR update to a closed month creates a discrepancy, not an in-place evidence mutation.
- In greytHR-authoritative mode, employee self check-in is unavailable unless swipe write-back is explicitly enabled.
- In hybrid mode, conflicting durations create a reconciliation case and one configured source remains authoritative.
- Authentication failure preserves last successful data with visible stale status and alerts.
- An export-based fallback is identified as `HISTORICAL_IMPORT`/approved source, with file hash and uploader.
- Salary/payroll fields returned unexpectedly by a provider are not stored.
- Source-mode change effective August leaves July evidence unchanged.
