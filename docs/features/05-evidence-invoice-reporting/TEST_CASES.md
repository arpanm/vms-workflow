# F05 — Evidence, Invoice and Reporting Test Cases

**Traceability:** RQ-024–RQ-026, RQ-032; PRD 10, 12–16, 21–22.
**Test boundary:** Local cases run with JUnit 5, Spring Boot Test + MockMvc/WebTestClient, Testcontainers PostgreSQL/Flyway, fake/recorded F04 handoff, object-storage, malware-scan, renderer and ERP/AP adapters, React unit/component tests, and Playwright against the Vite application. No local case needs live storage, scanner, renderer, AP/ERP, email, SSO, provider secret or production personal data. Cases marked **external acceptance** require approved tenant/deployment configuration and cannot be passed with fixtures.

## Planning-artifact status and common fixtures

- [x] This test catalog is the completed F05 planning artifact; product-test implementation and execution remain subject to the acceptance gates in `TASKS.md`.
- [x] Focused automation evidence is recorded in `TEST_AUTOMATION.md`: the finance-focused Spring/Flyway/PostgreSQL integration selection is **34/34 passing** and the isolated real-system Playwright lane is **3/3 passing**. See `FINAL_CLOSURE_REVIEW.md` and [the feature-status ledger](../../FEATURE_STATUS.md).
- [ ] Fresh regression evidence is required for the current shared worktree.
  The backend **154/154**, Vitest **88/88**, intercepted Playwright **69/69**
  and system Playwright **3/3** figures are historical baseline evidence.
  Fresh focused evidence on 2026-07-30 is committed concurrency **2/2**,
  natural scanner-readiness **1/1**, accessibility **3/3**, and isolated
  Java/PostgreSQL system flow **4/4**.
- [ ] External-production acceptance is not configured and is not implied by local test success.

Seed two organizations; one organization with two engagements/two projects; active/inactive vendor, product-owner, Procurement, finance, governance, auditor and outsider identities; scoped delegations and SOD-conflicting identities. Seed F02 closed/current/superseded attendance snapshots, F03 frozen/effective/revised plans and Linear plan/month-end/current states, F04 verified/exception/pending/superseded confirmation handoffs and reopen invalidations. Include scan-passed/pending/failed/unknown artifacts, invoice duplicate/correction/note samples, historical/reconstructed provenance, a deterministic clock/UUIDs/idempotency keys and malicious filename/comment/export cells. Every consequential case asserts typed outcome/error, current version/ETag and correlation ID; durable domain/audit/security/outbox facts; no cross-scope disclosure; no unexpected adapter call; and absence of salary/CTC/rate/markup/margin/payroll/employee-allocation calculation data.

## F04 handoff, canonical package and artifact tests

- `T-F05-HANDOFF-001` — Only a complete, current, scoped F04 readiness/confirmation handoff with immutable input IDs/versions/checksums, policy and provenance/freshness is consumable. Missing/forged/client-asserted confirmation, absent closed attendance, non-frozen plan, non-terminal certification, cross-engagement object, stale/superseded handoff or mismatched hash returns a named blocker and creates no package/invoice readiness result.
- `T-F05-HANDOFF-002` — A valid versioned F04 event is consumed idempotently: duplicate delivery, worker retry and replay create at most one F05 invalidation/readiness business effect; a newer schema is rejected or handled only by explicit compatibility policy and preserves the raw event/reference for audit.
- `T-F05-HANDOFF-003` — F04 confirmation correction, attendance/plan/certification source change or authorized reopen marks only dependent F05 readiness/package/invoice records invalid/superseded with causation and rework CTA. It never deletes prior package/download/review/payment history or rewrites F04 source facts.
- `T-F05-HANDOFF-004` — F05 cannot create, edit, approve or infer F04 attendance, plan, certification or confirmation. A package/invoice operation with F04 unavailable remains explicit `BLOCKED`/`ACTION_REQUIRED`, not a fabricated completion.
- `T-PKG-001` — Readiness evaluates each mandatory pillar—identity/contract, roster/allocation, attendance, approved plan, Linear snapshot/disclosure, delivery/certification, verified confirmation, invoice file/metadata and audit/package manifest—and returns per-rule result, severity, owner, source/version/hash, freshness and CTA. Missing confirmation, invalid/superseded snapshot, blocking source conflict, revised source, month mismatch or failed invoice scan blocks.
- `T-PKG-002` — Equivalent logical inputs (including differently ordered maps/arrays and equivalent UTC offsets) serialize to exactly identical canonical UTF-8 manifest bytes and SHA-256 under the recorded hash-schema version; hashes are integrity comparisons, never signer identity. A defined included source/version/policy/render/template field change creates the expected new input hash.
- `T-PKG-003` — Repeated/concurrent generation with the same canonical input hash uses lock/idempotency correctly: exactly one canonical package/version and one matching event/outbox effect result (or a linked render copy by declared policy), with stable JSON/PDF/CSV output hashes and no timestamp/font/order nondeterminism.
- `T-PKG-004` — A package manifest contains every required cover/checklist, roster, attendance/leave/exception, plan/Linear, delivery/certification, confirmation, invoice, audit/lineage and each artifact’s logical type/name, business object/version, provenance, represented/recorded time, object version, MIME/size, SHA-256, classification/retention and signer/approver reference where applicable. No prohibited commercial or unnecessary contact data is present.
- `T-PKG-005` — Attendance/plan/Linear/certification/confirmation/invoice artifact change or authorized reopen creates a new package version with new canonical input hash and visible diff; v1 stays byte-addressable under retained authorization, marked superseded, and its manifest/items cannot be updated/deleted. New generation cannot silently reuse a stale/superseded source.
- `T-PKG-006` — Historical/reconstructed inputs preserve represented versus recorded timestamps, source/confidence labels and reconstruction disclosure in package/manifest; current Linear data is not presented as a historical snapshot and imported evidence is not mislabelled original verification.

## Private storage, scan, package download and retention tests

- `T-STOR-001` — Upload rejects unauthorized actor/scope, absent classification/retention/source, oversize/allowlist violation, extension-versus-sniffed MIME mismatch, path traversal/control characters, active content/macro and unsafe filename. It stores safe name/hash/object version only in a private scoped prefix and logs a redacted audit/security event.
- `T-STOR-002` — `PENDING`, `UNKNOWN`, malware-failed/quarantined or retention-disposed artifact cannot preview, package, export, share or generate a signed URL; generator returns a named scan blocker. Scan-passed artifact metadata is immutable/versioned and subsequent replacement creates new object/artifact lineage.
- `T-STOR-003` — Authorized signed download is short-lived, single-object/version and organization/engagement/classification scoped; expired, revoked, tampered, reused outside scope or URL guessed by outsider is safely denied without existence leakage. Every view/download/share/revoke is audited; URLs/credentials/raw restricted bytes do not appear in JSON, browser state, normal logs, events or OpenAPI.
- `T-STOR-004` — Package and export artifacts use only scan-passed current immutable object versions; output hash is recomputed and a corrupted/restored object or manifest causes visible integrity failure, alert and safe download block.
- `T-STOR-005` — Authorized organization-scoped finance-content schedules seed no default duration. The governance API records an immutable eligible/held/referenced/not-due dry-run report; explicit execution rechecks state and disposes an approved candidate at most once while retaining metadata/hash/closed-month lineage. Local byte deletion runs only for the transactionally coupled PostgreSQL adapter; external storage requires a durable provider-specific pending/retry workflow.
- `T-STOR-006` — **External acceptance:** approved private storage, scanner and renderer configuration validates actual object versioning, malware/quarantine callback, scoped short URL expiry/revocation, at-rest/transport controls, retention/legal-hold operation and restored-object hash verification without exposing credentials or production personal data.

## Invoice versioning and readiness tests

- `T-INV-001` — Only an active scoped vendor invoice authority can create/upload its engagement-month invoice draft; Procurement/finance/product owner/outsider/inactive/service identities are denied according to policy. Server derives vendor/legal organization/month scope and rejects client-provided substitutions.
- `T-INV-002` — Invoice number uniqueness uses documented normalized case/spacing policy per vendor legal organization. A duplicate active primary invoice is rejected with safe conflict/current lineage; a permitted correction/replacement links the original and retains same month, while credit/debit note is separately numbered/dated/linked.
- `T-INV-003` — Upload validates billing period/month, vendor/client/engagement/PO/work-order, currency and represented document metadata, scan-passed file/hash and allowed state. `DRAFT` replacement preserves prior version/hash; submitted replacement requires withdrawal/change request, creates new version and cannot overwrite prior file/metadata.
- `T-INV-004` — Schema migrations, DTOs, form fields, manifests, reports, exports, UI text, logs and fixtures contain no employee salary/CTC/rate/markup/margin/payroll or employee-level invoice allocation. Invoice amount/tax fields are represented document metadata and no service derives them from attendance/employee inputs.
- `T-INV-005` — State machine permits only guarded lifecycle transitions and required readiness/package version: upload may remain `EVIDENCE_PENDING`; procurement submission is blocked until eligible readiness; `SUPERSEDED`, `CANCELLED`, `EXCEPTION_ACCEPTED` and `CLOSED` are not generic-edit states. Invalid transition/ETag returns typed error/current state without partial change.
- `T-INV-006` — Concurrent upload/replace/submit with same idempotency key returns one result; same key/different payload conflicts; concurrent distinct transitions serialize correctly and emit at most one invoice transition/outbox event per committed version.
- `T-INV-007` — Deterministic readiness rerun with same invoice/F04/package input produces the same rule results/input hash or reuses it per policy. A changed invoice hash/metadata, package supersession, F04 invalidation or reopen creates a new run, invalidates prior eligibility and preserves historic run/results.

## Procurement review, exception and query tests

- `T-PROC-001` — Only scoped active Procurement reviewer can open a package/invoice/review queue; vendor, product owner, unrelated Procurement user, finance without review authority, inactive user and cross-tenant actor are denied safely. A valid reviewer sees only permitted summary/PII and cannot edit upstream attendance/deliverable/confirmation.
- `T-PROC-002` — `APPROVED_FOR_PROCESSING`, `CHANGES_REQUESTED`, `ON_HOLD`, `REJECTED` and `EXCEPTION_ACCEPTED` are valid only from allowed state/authority/SOD. Each non-approval requires category/comment; audit snapshots actor authority, exact invoice/package/readiness versions, before/after state, policy, reason and correlation.
- `T-PROC-003` — Exception acceptance requires an effective-policy exceptionable business rule, exact failed rule/evidence, rationale, scoped validity/expiry and configured authority/second distinct approver. Invoice-document scan/integrity and package-manifest integrity blockers are non-waivable even under misconfiguration. An accepted exception remains visibly exception-accepted in package/readiness/control tower, never changes unverified confirmation to verified, cannot apply to newer package/invoice version without reapproval, and expires/reblocks as policy requires.
- `T-PROC-004` — Changes requested/hold/reject creates a durable idempotent assigned query/task with owner, category, due date, response and closure history. Source correction routes to owner workflow/F04 reopen, not an editable Procurement mutation; correction resubmission creates new package/invoice version while original review/query remains immutable.
- `T-PROC-005` — Concurrent review/exception/query actions obey expected version and idempotency: one decision/task/outbox business effect, stale actor receives typed conflict/current state, and conflicting decision does not overwrite/relabel history. Retry/dead-letter/replay preserves event/attempt lineage.

## Payment, event/outbox, job and audit tests

- `T-PAY-001` — Authorized finance/AP actor appends only allowed payment statuses with sanitized comment, external reference and status/expected/actual date. Wrong scope/vendor cannot see restricted internal notes; vendor sees permitted sanitized timeline only.
- `T-PAY-002` — Duplicate ERP callback/idempotency key, worker replay and concurrent status update create one payment-history event/business effect. Invalid transition or duplicate external reference follows configured conflict policy and is audited without loss of prior history.
- `T-PAY-003` — Payment update does not alter package, invoice document/hash/version, readiness source set, F04 confirmation/certification, invoice commercial calculation or represent money movement. `PAID` remains a recorded/integrated AP status with source/provenance.
- `T-F05-OUTBOX-001` — Every committed package/invoice/review/query/payment/invalidation transition atomically writes one domain event and transactional outbox row; transaction rollback leaves neither partial business state nor outbound event. Consumer duplicates/retries/admin replay cannot duplicate package, notification or business effect.
- `T-F05-JOB-001` — Synchronous package/readiness mutations remain version/idempotency fenced. Export workers expose claim/attempt/progress/next retry/error/correlation; crash/restart resumes safely, expired claims are reclaimed, stale workers cannot complete after lease loss, bounded backoff reaches visible dead letter and authorized replay performs at most one effect. Finance-content retention reuses the leased/idempotent governance execution lifecycle; competing approved runs dispose an eligible artifact at most once.
- `T-F05-AUD-001` — Audit/security events are append-only/redacted and capture actor/authority/object/version/source/reason/policy/result/evidence refs/correlation. Failed authorization, scan failure, integrity mismatch, download/share, exception, payment update, cross-scope attempt and compensating correction are all auditable; ordinary/admin roles cannot modify audit records.

## Reporting, control tower and export tests

- `T-REP-001` — Each metric dictionary entry supplies definition/formula, version/policy, source, timezone/freshness and zero/unknown/stale semantics. Attendance denominator excludes weekly off/holiday but includes working override; partial delivery is not fully accepted; verified confirmation is distinct from Procurement exception; invoice readiness names mandatory-rule pass/exception.
- `T-REP-002` — Dashboard/control-tower queries apply server-side organization/engagement/project/persona scope, safe filters/search/deep links and pagination; modified URL/query/autocomplete/count cannot reveal a different employee, engagement, package or invoice. Product owner sees authorized workforce summary only; Procurement matrix is scoped.
- `T-REP-003` — Procurement readiness matrix renders roster, attendance, plan/commitment, Linear, certification, confirmation, package, invoice and payment with complete/warning/blocking/exception-accepted/stale/not-applicable labels plus owner/CTA/version/freshness. It clearly distinguishes live Linear/current data from historical snapshot, confirmed from exception accepted, zero from unavailable, and reopened/superseded lineage from current readiness.
- `T-REP-004` — Report/export preserves exact authorized filters, report/version, generated actor/time/timezone, source freshness, snapshot/current label and row count. PII and restricted fields are masked/omitted identically to permission policy; all report/export/download accesses are audited.
- `T-REP-005` — CSV/XLSX values beginning `=`, `+`, `-`, `@` or equivalent formula payload are escaped; PDF/JSON/HTML labels are output encoded; malicious comments, filenames, Linear text and filter values cannot cause XSS, SQL injection, unsafe HTML or spreadsheet execution.
- `T-REP-006` — Heavy export runs asynchronously with private scan-passed artifact/progress/error/dead-letter/short signed download expiry; it is idempotent by export request and does not block transactional work. Summary refresh time is visible and mutation invalidation updates critical cards without presenting nightly stale data as current.

## Persistence, authorization, API and security tests

- `T-F05-DB-001` — Testcontainers PostgreSQL from empty applies all Flyway migrations. Constraints reject duplicate current package/invoice/readiness, invalid transition, cross-scope parent/child reference, duplicate normalized number, changed immutable manifest/item/hash, direct deleted superseded evidence and duplicate idempotency/provider event atomically.
- `T-F05-DB-002` — Direct SQL/service attempts to mutate submitted package/invoice/review/payment/audit history fail under database/app-role policy; permitted correction/exception/reopen appends lineage and preserves exact old manifest/hash/reference. Database roles and reporting views use least privilege/security invoker rather than accidental RLS bypass.
- `T-F05-SEC-001` — MockMvc/WebTestClient and direct database/storage tests deny unauthenticated, disabled, wrong organization/engagement/project, vendor/client/Procurement/finance SOD-conflicting, stale-token and hidden-route/deep-link requests without record existence leakage. Valid scoped JWT succeeds; client-supplied organization/role/actor/authority is ignored/rejected.
- `T-F05-SEC-002` — Authorization parity covers every F05 table, report/view, package/item/artifact/export prefix and signed download/share action. Browser UI control visibility is non-authoritative; no direct route/API/report/storage URL bypass grants data or action.
- `T-F05-SEC-003` — Input/output controls cover MIME/content sniffing, malware, oversized body/rate limits, XSS, SQL injection, CSRF for cookie mutations, filename/path manipulation, formula injection, SSRF-safe adapter configuration, short URL expiry and error redaction. Responses include safe code/correlation ID but no stack trace, secret, token, raw restricted content or unnecessary PII.
- `T-F05-SEC-004` — Secret/config/provider and restricted evidence values are absent from browser bundle/React query cache, DTOs, OpenAPI examples, normal logs, events, test fixtures and ordinary database dump. Audit/report/export retention and legal-hold access are restricted and logged.
- `T-F05-API-001` — Executable springdoc OpenAPI documents F05 endpoints, states, typed errors, ETag/idempotency headers, cursor/filter semantics, manifest/export schemas and authorization audience using synthetic redacted examples. `/v3/api-docs` and Swagger are access-controlled and expose no signed URL/secret/raw restricted example.

## React, Playwright, accessibility and regression tests

- `T-F05-UI-001` — Playwright vendor journey uploads valid invoice, sees scan progress/blocker, exact readiness rules/source versions/CTAs, package generation progress/history and submits only when ready. Invalid invoice, failed scan, missing confirmation and stale F04 handoff render safe actionable states; no commercial calculation field is shown.
- `T-F05-UI-002` — Playwright Procurement journey opens scoped control tower, drills into package manifest/version/diff, requests a change, requests an authority-bound exception with exact failed rule/readiness/package/policy lineage and performs approve/hold/reject under role gates. The exception requester cannot nominate an approver and receives a typed self-approval denial; a distinct current authenticated Procurement actor can approve the pending tuple, while expired/stale/mismatched requests remain denied. It verifies a package source is read-only and correction routes to an assigned task/reopen flow.
- `T-F05-UI-003` — Playwright finance/vendor journey shows sanitized payment timeline and restricted notes behavior, replacement/correction/credit-note lineage, expired/revoked download safe denial, prior package access under retained permission and no duplicate action after refresh/replay.
- `T-F05-UI-004` — Playwright report/export journey validates scoped filters, current-versus-snapshot/freshness/provenance labels, complete/warning/blocking/exception state text, zero versus unavailable, CSV formula-safe output, export progress/expiry and audit status. Cross-scope deep link/search has safe denial.
- `T-F05-UI-005` — Keyboard-only and screen-reader checks cover uploader, readiness checklist, package download/share, review/exception/query, payment update and export dialogs: accessible names/instructions/error summary, focus trap/return, non-color status, contrast, table/chart alternative, version/read-only state and responsive tablet layout.
- `T-F05-UI-006` — Loading, empty, permission-denied, scan-pending/quarantined, stale, network/error, job retry/dead-letter, expired signed URL and ETag conflict states contain clear next action/correlation ID and do not leak restricted records. UI never treats local cache/transport success as readiness or approval.
- `T-F05-REG-001` — F01–F04 regression after F05 migrations proves identity/RBAC, attendance snapshot/reopen, frozen plans/Linear snapshot semantics, certification/confirmation state and F04 handoff remain unchanged. F05 does not write F02–F04 source records or make Linear/delivery/transport state approval.
- `T-F05-REG-002` — Contract regression stubs F04 readiness/invalidation producer and F05 event consumer across compatible schema versions, duplicate/reordered delivery and unknown future version. It proves source/version/hash integrity, typed incompatibility, exactly-once invalidation and no package/invoice action from incomplete event.

## End-to-end, resilience and external acceptance

- `E2E-06` — With a scoped F04 verified handoff and scan-passed invoice, vendor uploads invoice; readiness evaluates all pillars; deterministic package/manifest is generated; Procurement reviews/approves; an authorized payment status advances. Assert role scope, audit/outbox, no commercial calculation and package/invoice/version integrity.
- `E2E-07-F05` — Closed-month attendance/certification/confirmation correction triggers authorized F04 reopen and F05 invalidation. Prior package/invoice/readiness remain retained/superseded; a corrected source yields new confirmation/package/readiness/invoice version and fresh Procurement path; payment history remains append-only.
- `E2E-08-F05` — Historical June invoice/evidence with reconstructed provenance generates a disclosed package and readiness result, preserves represented versus recorded timestamps and never alters paid status without documentary reference.
- `E2E-09-F05` — Vendor/Procurement actor from engagement A manipulates package/invoice/export/download IDs and signed URL for engagement B. API, report, storage and UI deny safely, reveal no record details and emit security/audit evidence.
- `T-F05-FAIL-001` — Failure injection for scanner unavailable/malware, object/render/hash mismatch, package/export crash, F04 event duplication/outage, database conflict, outbox/ERP retry and retention failure leaves durable explicit blocked/stale/dead-letter status, bounded retry/replay and no duplicate package/invoice/review/payment effect.
- `T-F05-PERF-001` — Indexed normal scoped dashboard/list/search meets p95 ≤2.5s and normal mutation ≤2s excluding async file/provider work; concurrent package generation/export and repeated idempotency traffic do not block transactions or cause N+1 external calls. Exercise initial and future-scale fixture/load targets from PRD 16.
- `T-F05-DR-001` — Backup/restore rehearsal restores package/manifests/artifact versions and re-verifies checksums, retention/legal-hold metadata, lineage and restricted access. Corruption is detected; RPO/RTO evidence is recorded against approved infrastructure target.
- `E2E-F05-PROVIDER-001` — **External acceptance:** approved private object storage, scan/render pipeline, retention/legal-hold and deployment grants execute an authorized real upload, scan/quarantine, deterministic package generation, signed/revoked download and restore-hash verification using non-production synthetic evidence.
- `E2E-F05-PROVIDER-002` — **External acceptance:** approved Procurement package format/process and, if enabled, AP/ERP adapter execute controlled submission/review/payment-status callback with scoped identities, idempotency, sanitized vendor view, audit/outbox and reconciliation evidence. This does not certify funds movement.

## Requirement/test traceability and completion rule

| Requirement | Representative tests |
|---|---|
| RQ-024 | T-F05-HANDOFF-001–004, T-PKG-001–006, T-STOR-001–005, T-INV-004, T-F05-SEC-001–004, E2E-06 |
| RQ-025 | T-INV-001–007, T-PROC-001–005, T-PAY-001–003, T-F05-OUTBOX-001, E2E-06 |
| RQ-026 | T-PKG-002–005, T-F05-DB-001–002, T-F05-HANDOFF-003, T-F05-DR-001, E2E-07-F05 |
| RQ-032 | T-REP-001–006, T-F05-UI-004–006, T-F05-SEC-002, E2E-09-F05 |
| PRD 14/16 release controls | T-STOR-001–006, T-F05-SEC-001–004, T-F05-JOB-001, T-F05-PERF-001, T-F05-DR-001, external E2E gates |

F05 is locally complete only when every non-external case has automated evidence or an approved, time-bounded exception; Flyway/Testcontainers, API/OpenAPI, React/Playwright, security/RLS, accessibility, F01–F04 regression and quality-command gates pass; and G0–G3 in `TASKS.md` are satisfied. Production invoice evidence additionally requires G4 and both explicitly external acceptance cases. A mock, fixture, PDF preview, email/transport receipt or UI-only result cannot close those gates.

## Final execution disposition — 2026-07-30

`T-PROC-003` is covered by a natural scanner-derived document blocker plus an
append-only, policy-declared exceptionable business-rule lineage; no readiness
result is updated. `T-F05-JOB-001` covers expired export-claim reclamation,
live-lease completion fencing and stale-worker denial.

Evidence is intentionally split by lane: exact Finance recovery **1/1**;
finance local-system **4/4**; F05 accessibility intercepted-browser **3/3**.
The integrated Maven row remains 340 executed with 2 failures and 1 error, and
the combined browser row remains **287/292**, followed by exact recovery
**5/5**. These recovery rows do not rewrite either aggregate as green.
`T-F05-PERF-001`, `T-F05-DR-001`, F07-T057 and G4/provider acceptance retain
their existing performance, recovery, soak and external boundaries.

## Completion-audit permanent regressions

- `T-REP-001-DASHBOARD-CONTRACT` — Java publishes the metric DTO consumed by
  React with explicit availability and live semantics.
- `T-REP-002-FULL-SCOPE` — a 55-month fixture proves dashboard totals cover
  the complete authorized scope, not only the first 50-row tower page.
- `T-REP-006-DOWNLOAD-GUARDS` — expired and checksum-mismatched exports are
  denied without a successful download audit fact.
- `T-F05-JOB-001-COMMITTED-RACE` — two independently committed worker threads
  compete for one export; PostgreSQL leasing produces one ready artifact,
  domain event and outbox effect.
- `T-F05-JOB-001-RESTART` — an expired dead-worker export claim is reclaimed;
  the replacement attempt produces one artifact and one ready event, and a
  repeated worker pass produces no additional effect.
- `E2E-F05-SYS-001` now checks the real Java metric response and visible React
  metric label before the vendor invoice/package journey.
- `T-STOR-005-RETENTION` applies V45, proves that missing authorized schedules
  fail closed, versions an organization-scoped finance-content schedule,
  records eligible/held/referenced dry-run decisions, explicitly executes one
  due artifact, preserves metadata/hash/audit/event/outbox/proof and rejects
  direct blob/state bypass.
- `T-F05-JOB-001-RETENTION-RACE` executes two independently approved dry runs
  against one due artifact and proves one disposal transition/proof.
- `T-PROC-005-SHARE-RACE` races two overlapping grants and proves one active
  share/event plus a typed `PACKAGE_SHARE_WINDOW_CONFLICT` loser.
- `T-F05-UI-UPLOAD-POLICY` proves the API-supplied policy drives the upload
  classification/retention controls rather than a client hard-coded value.
