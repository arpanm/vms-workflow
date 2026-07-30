# F06 — Historical Migration Tasks

## Final integrated reconciliation — 2026-07-30

F06 local code is complete through V43: V41 supplies retro lifecycle/source
declarations and V43 supplies the durable asynchronous migration queue. The
current migration system lane passes 6/6. The exact migration/OpenAPI recovery
passes 1/1 and focused accessibility passes 3/3. The failed full Maven
aggregate (340 executed; 2 failures + 1 error) and browser aggregate (287/292)
remain preserved separately from the passing recovery slices. Production
source-owner, storage/scanner, controlled scale/cutover and DR approval remain
external `NO-GO / ACTION_REQUIRED` gates.

**Phase:** 6
**Requirements:** RQ-027–RQ-029; PRD 11, 13–16, 18, 21–22
**Stack:** Java 25 / Spring Boot / PostgreSQL + Flyway / React + TanStack Query / Playwright

## 30 July 2026 completion audit addendum

- [x] Retro lifecycle: immutable current-time approve/reject/cancel actions,
  requester/decider separation, scoped inbox, Procurement notification
  envelopes and historical-month outcome transitions.
- [x] Operational row workbench: bounded row paging, state filtering and
  duplicate keep/reject/versioned-supersede actions; persisted upload source
  declarations.
- [x] Validation/audit closure: complete stable employee/temporal/attendance/
  leave/invoice/closed-month codes and request-correlated migration audit.
- [x] Async capacity: queue request-thread scan/validation into the durable
  bounded worker with lease/checkpoint resume, cancellation and a 100k boundary.
- [x] Generated templates: active-tenant reference codes and optional XLSX
  lookup sheets.
- [x] Contract/accessibility: executable migration OpenAPI/auth parity plus
  axe, keyboard, focus and tablet tests.
- [x] Consumed correction: expose the exact F04 reopen then F05 superseding
  package route while retaining immutable imported and package lineage.

## Planning status

- [x] Detailed task catalog authored before code generation.
- [x] Detailed test catalog authored before test automation.
- [x] Local implementation, reviews, fixes and G0–G3 evidence.
- [ ] External data-owner sign-off and production migration rehearsal (`ACTION_REQUIRED`).

## Scope and safety invariants

F06 loads governed data from 1 June 2026 onward. Every path follows
`stage → scan → parse → validate → preview → approve → commit`; no CSV row
directly upserts a canonical table. Represented historical time, actual
record/import/approval time, source, confidence, job/file/row provenance and
limitations remain distinct. Closed evidence is corrected only through
versioned domain workflows. Salary, rate, markup, margin, payroll and other
commercial columns are rejected and never persisted.

## Foundation, schema and contracts

- [x] **F06-TASK-001 — Template registry.** Register and checksum all 14 physical CSV files (`01`–`06`, `07a`, `07b`, `08`–`13`), exact v1 headers, data types, dependency wave, natural keys, allowed source/confidence enums, downloadable safe samples and compatibility rules.
- [x] **F06-TASK-002 — Migration schema.** Add Flyway tables for immutable source files, jobs, rows, normalized staging payloads, row errors/warnings, dependency edges, decisions, approvals, checkpoints, reconciliation reports/items/sign-offs, canonical provenance links, rollback/compensation actions, retro requests and audit/outbox events.
- [x] **F06-TASK-003 — Database invariants.** Enforce tenant/scope foreign keys, append-only source/decision/audit records, legal state transitions, unique file-hash+template+scope identity, unique job+row, idempotency keys, optimistic versions, one active reconciliation/sign-off tuple and no generic delete/update of committed evidence.
- [x] **F06-TASK-004 — Authorization and SOD.** Add `migration.read/upload/validate/approve/commit/rollback/retro` permissions with server-derived organization/engagement scope. Require distinct migration lead and governance/business reviewer for commit/sign-off; restrict source-file/error export and audit reads; deny cross-tenant discovery.

## Ingestion, validation and dependency execution

- [x] **F06-TASK-005 — Secure upload and source retention.** Stream bounded UTF-8 CSV, validate extension/MIME/size/encoding, hash SHA-256, sanitize filename and formula cells, reject archive/path/macro/binary abuse and prohibited commercial headers, store private bytes and immutable metadata, scan before parsing and audit every download.
- [x] **F06-TASK-006 — RFC 4180 parser and validation pipeline.** Parse quoted commas/newlines/BOM deterministically; preserve row numbers/raw hash; perform file, header, field, enum, timezone, narrative, duplicate, referential, temporal and cross-file validation with stable error codes and redacted messages.
- [x] **F06-TASK-006A — Complete registry-owned row schemas.** Declare required values for every physical template in the canonical registry and validate required values plus ISO date/month/timestamp and evidence-hash formats before approval/commit, with stable field-level findings.
- [x] **F06-TASK-007 — Job lifecycle and resumable worker.** Implement `UPLOADED → SCANNING → PARSING → VALIDATING → READY_TO_COMMIT → COMMITTING → COMPLETED | COMPLETED_WITH_ERRORS | FAILED | CANCELLED | ROLLED_BACK`, leased checkpoints, bounded retry/backoff/dead-letter, progress counters, cancellation, authorized replay and exactly-once business effects.
- [x] **F06-TASK-008 — Dry run, duplicate and conflict resolution.** Support `DRY_RUN`, `COMMIT`, `REPROCESS_REJECTS`, `SUPERSEDE`; classify identical/conflicting duplicates; preview canonical impact without writes; require explicit keep/reject/versioned-supersede resolution and preserve parent/prior-job lineage.
- [x] **F06-TASK-009 — Dependency graph.** Enforce masters → allocations/calendars → leave/attendance → deliverables/Linear → certifications → confirmations → invoices/approvals. Return the exact missing master/job/row and prevent commit while required predecessors or mappings are unresolved.

## Domain adapters for every template

- [x] **F06-TASK-010 — Employees.** Resolve organization/calendar/policies, natural-key uniqueness, manager cycles, employment dates/status and greytHR mappings; version corrections rather than overwrite governed history.
- [x] **F06-TASK-011 — Allocations, holidays and date overrides.** Validate employment/engagement/project scope, overlap and allocation-percent policy, calendar versions, half-day minutes, approver evidence and effective-date provenance.
- [x] **F06-TASK-012 — Leave balances and requests.** Append ledger entries, derive consumption, validate session/unit/status/outcome/approver lineage and never trust a supplied final mutable balance.
- [x] **F06-TASK-013 — Attendance punches and daily summaries.** Preserve immutable raw events, validate ordering/timezones/duration and expected employee-days, reconcile authority by employee/date/date-range, prohibit additive raw+daily minutes, surface missing days, and close snapshots only after review.
- [x] **F06-TASK-014 — Deliverables and Linear.** Create versioned plan/deliverable/criteria/owner/assignment facts, classify on-time/late/reconstructed plans, validate issue identifiers, and label API state captured now `CURRENT_STATE_ONLY` unless historical evidence proves month-end state.
- [x] **F06-TASK-015 — Certifications and confirmations.** Keep vendor outcome separate from client certification; import evidenced original decisions with represented/recorded timestamps and authority checks; route missing/ambiguous evidence to retro workflow; distinguish original confirmed, retroactively confirmed now and Procurement exception.
- [x] **F06-TASK-016 — Invoices and approval history.** Import represented invoice metadata/document references through F05 version services, enforce month/vendor/number/amount/hash lineage, preserve original approval evidence and refuse fabricated approvals or commercial calculations.

## Reconciliation, correction and historical workflows

- [x] **F06-TASK-017 — Reconciliation.** Produce immutable per-job/month reports with source hashes, counts by row state/error, expected/imported employee-days, leave/attendance exceptions, plan/Linear/certification/confirmation coverage, invoice/version linkage, low-confidence list and canonical checksums.
- [x] **F06-TASK-018 — Dual sign-off and month state.** Require migration-lead plus distinct governance/business sign-off bound to exact reconciliation version/hash. Drive `HISTORICAL_DRAFT`, `HISTORICAL_IMPORT_IN_PROGRESS`, `HISTORICAL_REVIEW`, `HISTORICAL_PENDING_CERTIFICATION`, `HISTORICAL_PENDING_CONFIRMATION` and normal downstream states without lowering readiness rules.
- [x] **F06-TASK-019 — Retro approval workflows.** Create explicitly historical commitment/certification/confirmation requests with current decision timestamps, represented month, reason, delegation evidence, Procurement notification/outcome and no backdated audit fiction.
- [x] **F06-TASK-020 — Rollback and correction.** Cancel pre-commit jobs without canonical effect; compensate an unconsumed committed batch under authorization; after snapshot/approval/package usage, deny hard rollback and invoke normal reopen/version/supersede flow. Retain source, job, provenance and audit.

## API, UI, automation and documentation

- [x] **F06-TASK-021 — REST/OpenAPI.** Add scoped template/download, upload, jobs/list/detail, validate, row/error paging, conflict resolution, approval, commit, cancel, retry, reprocess, rollback, reconciliation/sign-off and retro-request endpoints with typed errors, ETags, idempotency keys, cursor pagination and synthetic Swagger examples.
- [x] **F06-TASK-022 — Migration Center UI.** Build permission-gated `/migration` workspace for template selection/download, upload, scan/validation progress, row filters, error export, mappings/conflicts, impact preview, approval/commit, reconciliation/sign-off, recovery and retro actions. Cover loading/empty/error/stale/conflict/denied/cancelled/failed/partial/rolled-back states with accessible keyboard/focus/status semantics.
- [x] **F06-TASK-023 — Observability and runbooks.** Add metrics/logs/alerts for job age, rows/sec, failures by code/template, retries/dead letters, reconciliation mismatch, denied access, low confidence and rollback. Redact source values. Document quarantine, resume, conflict, partial commit, rollback, reconciliation and incident procedures.
- [x] **F06-TASK-024 — Test automation.** Implement `TEST_CASES.md` using JUnit/Spring/Testcontainers/PostgreSQL, deterministic parser/service tests, Vitest, Playwright `E2E-08`, accessibility checks and full F00–F06 regression after the feature.
- [x] **F06-TASK-025 — Independent SDLC reviews and fixes.** Complete code review/issues, test-automation review/issues, code analysis/issues and security analysis/issues with Terra; implement all P0/P1 and accepted P2 fixes with Sol; rerun focused and full gates.
- [x] **F06-TASK-026 — Documentation and local commit.** Add code/API/Swagger/UI/architecture/change/runbook/test/review documentation, update root README and feature/test status ledgers, run harness validation and commit locally without pushing.

## External acceptance

- [ ] **F06-EXT-001 — Source-owner acceptance.** Approved owners supply real exports, mappings, classifications, retention basis and template sign-off.
- [ ] **F06-EXT-002 — Production rehearsal.** Execute masked rehearsal, capacity window, backup/restore checkpoint, reconciliation approval and go/no-go evidence in the controlled environment.

**Exit gate:** all local G0–G3 tests pass; June-onward packages are reproducible;
provenance distinguishes represented from recorded/approved time; external
items remain `ACTION_REQUIRED` until real evidence exists.
