# F06 — Historical Migration Test Cases

## Final integrated reconciliation — 2026-07-30

Current evidence: migration system 6/6; exact migration/OpenAPI recovery 1/1;
focused accessibility 3/3. The full Maven run executed 340 tests and retained
2 failures plus 1 error; the full browser run passed 287/292. Focused recovery
does not convert either row into a clean aggregate pass.

**Traceability:** RQ-027–RQ-029; PRD 11, 13–16, 18, 21–22.
**Boundary:** local automation uses synthetic files and Testcontainers
PostgreSQL. Production source-owner/rehearsal cases are explicitly external.

## Template, upload and parser

- `T-MIG-001` — Registry exposes all 14 templates in dependency order with exact version, header schema and SHA-256; unknown/version-drifted templates fail closed.
- `T-MIG-002` — Each supplied v1 sample parses; quoted comma/newline/escaped quote/BOM and final newline are deterministic and preserve physical row number.
- `T-MIG-003` — Wrong MIME/encoding/delimiter/header, duplicate header, oversize/row-limit, binary/archive/path filename or unscanned file is rejected before parsing.
- `T-MIG-004` — Salary/rate/markup/margin/payroll headers cause file-level rejection and neither raw payload, log, error detail nor canonical table contains their values.
- `T-MIG-005` — Formula prefixes (`=`, `+`, `-`, `@`) remain inert text in preview/error exports; HTML/script/control characters are escaped or rejected.
- `T-MIG-006` — Source file metadata is immutable and includes safe filename, MIME, size, byte hash, uploader, scope, scan status and recorded time.
- `T-MIG-006A` — Every template's registry-owned required fields are a subset of its exact headers; missing values and malformed ISO date/month/timestamp or SHA-256 fields remain invalid staged rows with stable field-level codes and cannot reach domain-adapter execution.

## Job lifecycle, idempotency and recovery

- `T-MIG-007` — Dry run progresses through scan/parse/validate to `READY_TO_COMMIT` and writes no canonical business row.
- `T-MIG-008` — Same hash+template+scope upload returns the existing job/result; concurrent identical requests produce one source/job and no duplicate effects.
- `T-MIG-009` — Same natural key/content is `DUPLICATE_IDENTICAL`; changed content is `DUPLICATE_CONFLICT` until keep/reject/versioned-supersede decision.
- `T-MIG-010` — Invalid row has stable code, field, safe message and dependency reference; no raw secret or cross-tenant identifier is disclosed.
- `T-MIG-011` — Partial-commit policy commits only valid independent rows, reports exact committed/rejected totals and reprocesses rejects without repeating successful rows.
- `T-MIG-012` — Worker crash after checkpoint resumes once; lease expiry/retry/dead-letter/replay create at most one canonical effect/event per row.
- `T-MIG-013` — Pre-commit cancel leaves zero canonical effects; commit cancellation follows the documented transaction/checkpoint boundary.
- `T-MIG-014` — ETag and idempotency mismatch return typed conflict/current version; stale operator cannot approve, commit, resolve or roll back.

## Dependency and per-template validation

- `T-MIG-015` — Out-of-order template commit is blocked with exact missing predecessor/template/key; once resolved, retry succeeds without re-upload.
- `T-MIG-016` — Employee import enforces scoped natural keys, calendar/policy mapping, employment dates, manager-cycle prevention and unique greytHR reference.
- `T-MIG-017` — Allocation import enforces employee/engagement/project scope, date overlap and >100% policy; holiday/override import versions calendar facts and half-day minutes.
- `T-MIG-018` — Leave balance rows append ledger entries; leave requests validate units/sessions/outcomes/approver and derived balance rather than trusting a final balance.
- `T-MIG-019` — Punch import preserves immutable ordered events/timezones and derives sessions; invalid order, duration or employment/calendar date is rejected.
- `T-MIG-020` — Daily attendance generates expected employee-days, reports missing days and validates calendar/leave/net minutes/final status.
- `T-MIG-021` — Raw and daily inputs for the same authority range never add minutes; reconciliation retains raw detail and selects one authoritative daily result.
- `T-MIG-022` — Deliverables create versioned plan/criteria/owner/assignment lineage and classify on-time, late-approved or reconstructed approval.
- `T-MIG-023` — Linear current state captured after month end is `CURRENT_STATE_ONLY`; proven historical export/event may be month-end with source/confidence.
- `T-MIG-024` — Certification keeps vendor outcome and client decision distinct, binds exact deliverable version and reports missing/unauthorized evidence.
- `T-MIG-025` — Original confirmation evidence preserves represented decision time and actual import time; unmatched/ambiguous actor remains unverified/review-required.
- `T-MIG-026` — Invoice import uses F05 lineage rules, period/scope/normalized number/document hash; corrections create versions and never calculate commercial basis.
- `T-MIG-027` — Approval-history rows require exact governed object/version/actor/authority/source; they cannot create an approval merely from a name/email.

## Provenance, reconciliation, retro approval and rollback

- `T-MIG-028` — Every committed fact is traceable to job/file/hash/row/source/confidence, represented time and actual recorded time; low/unverified data is disclosed downstream.
- `T-MIG-029` — Reconciliation counts and hashes match staging and canonical records, expected/imported days, domain coverage, exceptions and invoice/package lineage.
- `T-MIG-030` — A changed row or report creates a new reconciliation version/hash; stale sign-offs cannot authorize it.
- `T-MIG-031` — Commit/close needs a migration lead and a distinct scoped governance/business reviewer; self, revoked, expired and cross-tenant approvers are denied.
- `T-MIG-032` — Historical month follows import/review/pending-certification/pending-confirmation states and cannot skip ordinary readiness gates.
- `T-MIG-033` — Retro certification/confirmation labels the historical month but records the current authenticated decision timestamp; no June timestamp is fabricated.
- `T-MIG-034` — Unavailable original approver requires configured delegation/replacement and reason; generic admin privilege is insufficient.
- `T-MIG-035` — Unconsumed committed batch rollback uses compensating/versioned actions and retains source/audit; a batch consumed by snapshot/approval/package is denied hard rollback and routed to reopen/correction.
- `T-MIG-036` — Correction of a closed historical package creates a new version and preserves all old package/download/approval evidence.

## API, authorization, privacy, concurrency and performance

- `T-MIG-037` — Every API requires a valid JWT, server-derived active principal and scoped permission; cross-tenant job IDs return non-disclosing denial/not-found.
- `T-MIG-038` — Upload/approve/commit/rollback enforce SOD; source/error downloads are audited and formula-safe; list/search/pagination cannot enumerate another scope.
- `T-MIG-039` — Signed cursor is route/subject/filter bound, tamper-proof and bounded; stable membership policy is documented under concurrent inserts.
- `T-MIG-040` — Concurrent commit/rollback/reprocess uses locks/version checks and produces one legal terminal state with exactly-once outbox/audit effects.
- `T-MIG-041` — Error messages, logs, metrics, events and OpenAPI examples contain no CSV row payload, token, email body, commercial value or signed URL.
- `T-MIG-042` — 100k-row synthetic dry run is bounded/streamed, cancellation responsive and query plans avoid unbounded scans; actual p95/throughput evidence is recorded.
- `T-MIG-043` — OpenAPI describes multipart upload, headers, states, error codes, paging and every mutation; examples are synthetic and schema validation passes.

## Frontend and Playwright regression

- `T-MIG-UI-001` — Template catalog/download and instructions show exact version/order/dependencies without exposing unauthorized scopes.
- `T-MIG-UI-002` — Upload dry run shows scan→parse→validate progress, counts, row filters, warnings/errors and downloadable safe error report.
- `T-MIG-UI-003` — Operator resolves mappings/conflicts, previews impact, requests approval and cannot commit until exact dual sign-off exists.
- `T-MIG-UI-004` — Partial failure/reprocess, cancellation, retry/dead-letter and rollback/correction paths show accurate states and next actions.
- `T-MIG-UI-005` — Reconciliation and provenance drill-down show source hash, row, represented/recorded time, confidence, limitations and sign-offs.
- `T-MIG-UI-006` — Retro action clearly says “Historical confirmation/certification”, shows current decision time and never offers a backdate control.
- `T-MIG-UI-007` — Loading, empty, stale, conflict, denied, failed, cancelled, partial and rolled-back states are keyboard operable, focus managed and announced without color-only meaning.
- `E2E-08` — Stateful Playwright journey uploads a synthetic batch, validates, reconciles, obtains two-person approval and commits.
- `E2E-08A` — Upload records the immutable valid-rows-only partial-commit policy and commit explicitly reaffirms that recorded policy.
- `E2E-08B` — Retro request records current authenticated time and explicit delegation evidence without a backdate control.
- `E2E-08C` — Cross-tenant migration scope is non-disclosing and exposes no restricted content.
- `E2E-08D` — Failed validation exposes a bounded retry path and never retries a commit.
- `E2E-F06-SYS-001` through `E2E-F06-SYS-006` — Real Vite browser → Spring Security/API → Flyway V1–V20 → PostgreSQL 18 journeys cover scope/catalog, upload/scan/validate/reconciliation, SoD/commit, provenance/rollback, formula-safe export/rejected-only reprocess and retro time.
- Full F00–F06 combined Chromium regression passes after F06.

## Local verification evidence

All local G0–G3 lanes passed on 28 July 2026:

| Lane | Result | Principal coverage |
|---|---:|---|
| Parser/unit | 14/14 | RFC 4180 edge cases, row bounds, all-template registry plus existing unit regression |
| Spring/Testcontainers integration | 158/158 | Flyway V1–V20, all 14 domain adapters, upload/idempotency, scope denial, prohibited fields, attendance authority, SoD, atomic duplicate handling, rejected-only reprocess, signed cursor, retry, compensation and retro time |
| Frontend Vitest | 90/90 | Migration API/presentation behavior plus all earlier frontend regression |
| Intercepted Playwright | 74/74 | Five F06 browser-contract cases plus all F00–F05 journeys |
| Real local system Playwright | 6/6 | Browser/API/database F06 journeys listed above |

`T-MIG-042` remains a controlled scale/capacity acceptance item: local parsing
is bounded and cancellation/retry behavior is covered, but production p95,
100k-row throughput and infrastructure capacity must be measured in F07 against
the selected deployment/storage/scanner environment. It is not represented by
the synthetic local pass counts.

## External acceptance

- `T-MIG-EXT-001` — Data owners approve real template mappings, sources, confidence and reconciliation counts.
- `T-MIG-EXT-002` — Masked production rehearsal proves storage/scan capacity, operational window, backup/restore checkpoint and rollback decision.

These external cases remain `ACTION_REQUIRED`; fixtures cannot close them.

## 30 July schema-closure evidence

`MigrationCsvParserTest` verifies all 14 registry entries have a non-empty
required schema wholly contained by the exact header contract.
`MigrationWorkflowIT.schemaValidationRejectsIncompleteAndMalformedTemplateRows`
verifies `FIELD_REQUIRED`, `FIELD_INVALID_DATE` and
`FIELD_INVALID_TIMESTAMP` against PostgreSQL. Focused result: **3 unit + 14
integration = 17/17 passed**.

## Independent-review regression cases

- `T-MIG-043` — A filename without a SHA-256 cannot authorize a historical
  business confirmation; the hash must resolve uniquely to retained,
  scan-approved evidence in the same engagement/month.
- `T-MIG-044` — Invoice metadata without bytes/hash creates no document
  artifact and no synthetic byte-content hash.
- `T-MIG-045` — Allocation approver/time values are absent together or present
  together.
- `T-MIG-046` — Duplicate validators persist one finding per stable
  severity/code/field/dependency identity.
- `T-MIG-047` — Local attendance timestamp plus validated IANA timezone
  produces the same employee-local date at validation, authority arbitration
  and commit.

Current focused result: **3/3 unit, 15/15 workflow integration and 1/1
all-template adapter integration tests passed**.

## Completion-audit regression cases

- `T-MIG-048` — The scanner verdict is obtained before any CSV parse or header
  validation. A quarantined EICAR fixture with malformed CSV persists no
  staging rows and validation returns `SOURCE_SCAN_NOT_PASSED`.
- `T-MIG-049` — Expected employee-days prefer the latest immutable finalized
  roster; otherwise they derive from effective engagement allocation,
  employment lifecycle, calendar weekdays, holidays and date overrides.
  Imported days and bounded missing-day exceptions are counted independently.
- `T-MIG-050` — A raw attendance event before 1 June 2026 is invalid with
  `TEMPORAL_OUTSIDE_ENGAGEMENT` on `occurred_at`.
- `T-MIG-051` — Resolving a duplicate conflict creates a new immutable
  reconciliation/report hash, records an audit event and invalidates approvals
  attached to the prior report.
- `T-MIG-052` — Job responses expose only `APPROVED` sign-offs attached to the
  current reconciliation; stale and rejected decisions cannot satisfy client
  commit readiness.
- `E2E-F06-SYS-007` — Real Vite/Spring/PostgreSQL execution proves a
  quarantined malformed source cannot reach parsing or validation.

Completion-audit evidence: **32/32 focused backend tests**, **2/2 migration
presentation tests**, TypeScript typecheck, and **7/7 real-system Playwright**
passed.
