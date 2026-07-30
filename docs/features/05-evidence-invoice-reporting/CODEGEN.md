# F05 — Code-generation record

**Status:** Local implementation and combined G0–G3 regression complete.

F05 adds the evidence, invoice, Procurement, payment-status and reporting
vertical on the approved Java/PostgreSQL and React/TanStack stack. It does not
restore Lovable, Supabase, browser database access, provider credentials, or
commercial employee-cost calculations.

## Delivered local capabilities

- Additive Flyway finance schema for policy versions, private artifacts and
  blobs, canonical package lineage, invoice/version/readiness facts,
  Procurement review/query/exception history, payment facts, exports, audit,
  idempotency, outbox, shares, rate-limit buckets and integrity/state guards.
- Typed, version-checked F04 handoff resolver; F05 consumes source facts and
  invalidation events but never mutates F02–F04 records.
- Private PostgreSQL-backed local storage adapter, byte-level SHA-256 checks,
  scan/quarantine state, policy-controlled MIME/size/classification/retention,
  immutable package outputs and authenticated downloads.
- Deterministic manifests and PDF/CSV/XLSX/JSON package/export outputs;
  invoice upload/version/readiness/submission, Procurement decisions/queries/
  exceptions, append-only payment statuses, durable leased export processing,
  retry/dead-letter/replay, scoped reports/control tower and finance mutation
  rate limiting.
- React `/finance`, `/finance/procurement`, and `/finance/reports` routes with
  permission-aware actions, opaque cursor paging, scan/readiness/version
  states, package shares/history, Procurement flows, payment timeline and
  asynchronous export status.

The detailed implementation records are [backend](CODEGEN-BACKEND.md) and
[frontend](CODEGEN-FRONTEND.md). Architecture, endpoint contracts and user
flows are in [ARCHITECTURE.md](ARCHITECTURE.md),
[API_DOCUMENTATION.md](API_DOCUMENTATION.md), and
[UI_DOCUMENTATION.md](UI_DOCUMENTATION.md).

## Completion boundary

Local adapters deliberately fail closed when their production provider is not
configured. They are not evidence of a deployed object store, malware service,
renderer, retention/legal-hold operation, SSO/grants, Procurement process, or
ERP/AP integration. Those remain G4 external acceptance gates.

## 2026-07-30 completion-audit code generation

- `FinanceGovernanceService.dashboard` now aggregates the complete authorized
  engagement scope in PostgreSQL rather than the first 50-row tower page.
- Java emits the React metric contract with explicit availability, dictionary
  version, source/freshness and `LIVE` temporal mode.
- The React adapter consumes that contract, remains compatible with the prior
  projection and honors server `snapshotMode` report definitions.
- That dashboard-contract audit required no Flyway change; the later retention
  work is the additive V45 described below.

## 2026-07-30 retention and policy-contract code generation

- Additive V45 extends the existing organization-scoped, immutable F07
  schedule/dry-run/execution model with finance export/evidence content classes,
  narrow runtime grants and same-transaction disposal guards without changing
  V1–V44. It seeds no duration.
- The governed API creates an authorized schedule, records a reviewable dry-run
  candidate report, and explicitly executes eligible candidates.
  `FinanceRetentionWorker` applies only those approved candidates, rechecks
  holds/references and emits audit/event/outbox facts for local byte disposal.
- `FinancePrivateStorageAdapter` makes transactional deletion an explicit
  capability; only the PostgreSQL implementation opts in.
- Invoice reads expose effective upload policy. React uses the server policy
  for classification and retention instead of a hard-coded fixture value.
- Overlapping concurrent package-share creation returns the typed
  `PACKAGE_SHARE_WINDOW_CONFLICT` domain conflict.
