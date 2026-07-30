# F07 — Hardening and Go-live Task Catalog

**Phase:** 7
**Requirements:** PRD 14, 16, 20 and 22; RQ-033–RQ-035
**Controlling stack:** Java 25, Spring Boot 4.1.0, Maven, PostgreSQL,
Flyway, JWT/OIDC, springdoc, React/Vite, Vitest and Playwright.

This is the implementable release catalog for F07. `LOCAL` means the
repository can implement and prove the control with synthetic data and local
infrastructure. `EXTERNAL — ACTION_REQUIRED` means named customer, provider,
legal, Procurement or production-platform evidence is required and must never
be inferred from a green local test.

## Current evidence reconciliation

The final working-tree evidence is additive and does not erase earlier failed
runs:

- final frontend/static gates pass: typecheck; lint with 0 errors and 6
  non-blocking Fast Refresh warnings; Vitest 24 files/92 tests; production
  build of 3,006 modules with a 586.90 kB largest-chunk optimization advisory;
  and diff-check;
- the pre-final focused backend gate passes 73 unit plus 45 integration tests;
- the capacity rerun passes 73 unit plus 2 capacity tests, with dashboard
  101ms, check-in p95 404ms, replay p95 69ms, 10,000-employee search p95 2ms
  and 300,000-row report p95 9ms;
- the ordered F07 local-system suite passes E2E-01/02/03/04/05/07/10 (7/7);
- the finance and migration local-system suites pass 4/4 and 6/6;
- the full browser matrix passes 274/274 after the preserved 268/274 first
  run and exact 7/7 failed-slice rerun;
- production schema history is current through V38. V1000+ migrations are test
  fixtures only;
- the definitive complete Maven R3 passes 73 unit + 217 integration tests
  (290/290), zero failures/errors/skips, in 03:21. The earlier R2 215/217
  integration result remains preserved; its two delivery-worker failures were
  test-database isolation defects and the dedicated-database correction passes
  in R3.

Only tasks whose complete local contract is directly established by the
evidence above are checked below. Every `EXTERNAL — ACTION_REQUIRED` task and
the overall production decision remain open/`NO-GO`.

## A. Release evidence, traceability and configuration

- [ ] **F07-T001 — LOCAL:** Create a machine-readable release-evidence
  manifest containing release version/commit, requirement IDs, test IDs,
  automation command, result, evidence path, owner, exception and expiry.
- [ ] **F07-T002 — LOCAL:** Add a CI/local release-gate command that rejects
  missing evidence, failed P0/P1 tests, unowned exceptions, expired risk
  acceptances and unlinked mandatory requirements.
- [x] **F07-T003 — LOCAL:** Map every F07 task and test to PRD sections,
  the applicable subset of RQ-033–RQ-035, schema/Flyway impact, API/UI impact,
  runbook and rollback evidence; update the repository status source from the
  manifest. The release gate validates the canonical
  [traceability matrix](traceability-matrix.json) for all 85 tasks and 76
  tests and rejects missing/orphaned policy.
- [ ] **F07-T004 — LOCAL:** Implement the production-readiness configuration
  registry with `NOT_CONFIGURED`, `CONFIGURED_UNVERIFIED`, `VERIFIED` and
  `EXPIRED_ACTION_REQUIRED` states, owning role, evidence reference, effective
  dates and affected workflow.
- [ ] **F07-T005 — LOCAL:** Fail closed at startup or workflow entry when a
  mandatory issuer, audience, signing key, database role, provider capability,
  scanner, storage, email or policy configuration is absent.
- [ ] **F07-T006 — LOCAL:** Preserve Java/PostgreSQL architecture precedence in
  all release docs; translate old RLS/Supabase gates into Spring
  authorization, PostgreSQL role/row-scope tests and server-managed storage.
- [ ] **F07-T007 — EXTERNAL — ACTION_REQUIRED:** Name release, security, data,
  operations, support and rollback approvers and record their approval
  evidence.
- [ ] **F07-T008 — EXTERNAL — ACTION_REQUIRED:** Resolve every tenant decision
  in PRD 20 (identity, attendance, leave, Linear, email, Procurement,
  retention and infrastructure), or record an approved dated exception.

## B. Authentication, authorization and database least privilege

- [ ] **F07-T009 — LOCAL:** Complete the HTTP/controller/service/object-scope
  permission matrix for every role, organization, engagement, project, month,
  employee-self case, report, export and private-artifact route.
- [ ] **F07-T010 — LOCAL:** Add adversarial MockMvc/Testcontainers tests for
  cross-tenant IDs, same-organization wrong scope, disabled/expired identities,
  stale roles, forged client roles, object enumeration and child/parent scope
  mismatch.
- [ ] **F07-T011 — LOCAL:** Define separate PostgreSQL migration-owner,
  application read/write, read-only reporting, job-worker and backup roles;
  revoke public/default privileges and grant only required schemas,
  tables/sequences/functions.
- [ ] **F07-T012 — LOCAL:** Add PostgreSQL integration tests proving the
  application role cannot alter Flyway history, audit/security events,
  immutable evidence or restricted secret-reference tables and cannot bypass
  tenant predicates through views/functions.
- [ ] **F07-T013 — LOCAL:** Review every database function/view for fixed
  `search_path`, safe ownership/security-definer use, parameterized inputs and
  least grants; add a catalog assertion that fails on drift.
- [ ] **F07-T014 — LOCAL:** Enforce active identity/membership/authority checks
  for every consequential operation, including delegation, reopen, manual
  evidence, retro approval, Procurement exception and mass export.
- [ ] **F07-T015 — LOCAL:** Add service-account identities with non-interactive,
  minimal scopes and tests proving human-only operations are unavailable.
- [ ] **F07-T016 — LOCAL:** Protect Swagger/OpenAPI and Actuator endpoints by
  environment and role; expose only health information that does not leak
  dependencies or secrets.
- [ ] **F07-T017 — EXTERNAL — ACTION_REQUIRED:** Configure and validate the
  production OIDC issuer/tenant, claims contract, MFA/step-up, key rotation,
  logout/session revocation and service-account policy.

## C. HTTP, browser and application security

- [ ] **F07-T018 — LOCAL:** Add a security-header policy for CSP,
  `frame-ancestors`, HSTS in TLS environments, content-type sniff prevention,
  referrer policy, permissions policy and safe cache controls.
- [ ] **F07-T019 — LOCAL:** Implement an explicit production CORS allowlist;
  reject wildcard origins with credentials and untrusted preflight requests.
- [ ] **F07-T020 — LOCAL:** Document the JWT bearer/API CSRF boundary; require
  CSRF tokens and `SameSite`/`Secure`/`HttpOnly` cookies for any
  cookie-authenticated mutation or BFF session introduced for production.
- [ ] **F07-T021 — LOCAL:** Centralize validation, output encoding and safe
  rendering for comments, filenames, Linear/email text, Markdown/HTML and CSV
  exports; test stored/reflected XSS and formula injection.
- [ ] **F07-T022 — LOCAL:** Centralize outbound HTTP clients with HTTPS,
  hostname allowlists, redirect restrictions, timeouts, bounded payloads and
  redacted diagnostics to prevent SSRF and credential forwarding.
- [ ] **F07-T023 — LOCAL:** Apply actor/scope-aware rate limits to
  authentication-facing operations, attendance punches, confirmation,
  webhooks, migration, export and download endpoints; publish retry metadata
  without internal details.
- [ ] **F07-T024 — LOCAL:** Standardize problem responses with a correlation ID
  and safe code/detail; prevent stack traces, SQL, tokens, PII and provider
  payloads from reaching clients or ordinary logs.
- [ ] **F07-T025 — LOCAL:** Add replay/idempotency abuse tests for punches,
  approvals, confirmations, webhooks, imports, exports and job commands under
  concurrency.
- [ ] **F07-T026 — LOCAL:** Verify geolocation, biometric, selfie, keystroke and
  continuous productivity tracking are absent/disabled and cannot be silently
  enabled by client input.

## D. Secrets, dependencies and software supply chain

- [ ] **F07-T027 — LOCAL:** Inventory all environment variables and secret
  references; validate minimum entropy/format where appropriate and ensure no
  default production credentials or provider tokens enter browser bundles.
- [ ] **F07-T028 — LOCAL:** Add repository secret scanning, SAST, Java/Node
  dependency vulnerability checks, license inventory and container/SBOM
  generation to the release gate.
- [ ] **F07-T029 — LOCAL:** Pin/review build inputs, produce checksums/SBOM and
  document artifact provenance so the backend and frontend release can be
  reproduced from the committed source.
- [x] **F07-T030 — LOCAL:** Add automated redaction tests for authorization
  headers, cookies, JWTs, passwords, webhook secrets, object keys, raw email,
  restricted PII and exception payloads.
- [ ] **F07-T031 — LOCAL:** Sanitize or remove committed local `.env` material,
  document rotation procedure and assert tracked files/built assets contain no
  live-secret patterns.
- [ ] **F07-T032 — EXTERNAL — ACTION_REQUIRED:** Provision the approved
  production secret manager, rotate any exposed credentials, configure overlap
  rotation and attach provider/platform verification evidence.

## E. File, evidence and privacy controls

- [ ] **F07-T033 — LOCAL:** Apply shared upload limits, content sniffing,
  extension/MIME allowlists, filename normalization, hash validation and
  quarantine state transitions to migration, evidence, email and invoice
  artifacts.
- [ ] **F07-T034 — LOCAL:** Ensure failed/pending/quarantined artifacts cannot
  be viewed, downloaded, rendered or included in a package; authorization must
  be rechecked for every signed-link/download request.
- [ ] **F07-T035 — LOCAL:** Add malicious-file corpus tests (EICAR test string,
  MIME mismatch, macro/active HTML, archive bomb metadata, path traversal,
  oversized body and duplicate hash).
- [ ] **F07-T036 — LOCAL:** Create and enforce a data-classification inventory
  for tables, fields, API DTOs, logs, exports and artifacts (`INTERNAL`,
  `CONFIDENTIAL`, `RESTRICTED`), including an explicit prohibited
  salary/rate/markup boundary.
- [ ] **F07-T037 — LOCAL:** Implement privacy-minimized exports/packages,
  field masking, short-lived download capability, download audit and controlled
  correction/versioning without silent evidence deletion.
- [ ] **F07-T038 — EXTERNAL — ACTION_REQUIRED:** Integrate the approved malware
  scanner/object-storage services with durable verdict callback, quarantine,
  retry/dead-letter monitoring and access-control evidence.
- [ ] **F07-T039 — EXTERNAL — ACTION_REQUIRED:** Obtain legal/privacy approval
  for purpose, notice, personal-data fields, attendance wording, data sharing
  and the data-subject correction route.

## F. Audit, retention and legal hold

- [ ] **F07-T040 — LOCAL:** Complete immutable audit coverage for identity,
  role/config, workforce, attendance, integration, plan, certification,
  confirmation, package, invoice, close/reopen, export/download and failed
  security actions.
- [ ] **F07-T041 — LOCAL:** Propagate trusted correlation and causation IDs
  through HTTP, service, database audit, outbox and worker steps; redact
  sensitive diffs while retaining actor/authority/object/version/result.
- [ ] **F07-T042 — LOCAL:** Add append-only PostgreSQL grants/triggers and
  periodic hash/count integrity verification for audit/security/evidence
  records; corrections must append compensating facts.
- [ ] **F07-T043 — LOCAL:** Implement configurable retention schedules by
  record class, dry-run deletion reports, referenced-record protection,
  export/signed-link expiry, proof of deletion and retryable retention jobs.
- [ ] **F07-T044 — LOCAL:** Implement legal-hold placement/release with scoped
  authority, dual-control where configured, immutable transition evidence and
  deletion-job exclusion.
- [ ] **F07-T045 — EXTERNAL — ACTION_REQUIRED:** Approve production retention
  periods, legal-hold authority, immutable archive requirement and audit export
  recipients; no statutory duration may be invented locally.

## G. Observability, reliability and provider degradation

- [ ] **F07-T046 — LOCAL:** Add Micrometer metrics and structured logs for API
  latency/errors, authorization denials, attendance duplicates, unresolved
  close items, job/outbox/dead-letter depth/age, provider freshness, scan,
  package/hash, invoice aging, retention and backup status.
- [ ] **F07-T047 — LOCAL:** Define SLI/SLO dashboards, alert severity, owner,
  deduplication key, threshold, runbook link and correlation drill-down; avoid
  high-cardinality PII labels.
- [ ] **F07-T048 — LOCAL:** Add health/readiness checks that distinguish
  mandatory dependency failure from optional-provider degradation and never
  expose credentials, database details or tenant data.
- [ ] **F07-T049 — LOCAL:** Prove bounded retry/backoff, idempotent
  at-least-once effects, checkpoint/resume, dead-letter visibility and
  authorized replay for every worker/outbox/provider flow.
- [ ] **F07-T050 — LOCAL:** Add failure injection for database interruption,
  worker restart, scanner timeout, object-store failure, webhook burst, email
  bounce, Linear/greytHR outage and partial package/import failure.
- [ ] **F07-T051 — LOCAL:** Preserve last-known provider state with explicit
  stale timestamps and fallback workflow; provider outage must never be shown
  as fresh truth or inferred approval.
- [ ] **F07-T052 — EXTERNAL — ACTION_REQUIRED:** Connect production metrics,
  logs, traces, paging and on-call services; validate alert delivery,
  escalation and retention with the named operations/security owners.

## H. Performance, concurrency and capacity

- [x] **F07-T053 — LOCAL:** Build repeatable load profiles for 26-person peak
  check-in, 10,000-person future burst, dashboard/search, webhook duplicate
  storm, large migration, report/export, package generation and mixed tenant
  activity.
- [x] **F07-T054 — LOCAL:** Enforce measured targets: check-in p95 ≤1.5s,
  standard mutation/search p95 ≤2s, dashboard/list p95 ≤2.5s and durable
  webhook acknowledgment <5s (target p95 <1s), excluding documented network or
  asynchronous provider time.
- [x] **F07-T055 — LOCAL:** Add concurrency tests for optimistic locking,
  uniqueness, idempotency, source authority, approval SoD, package
  determinism, tenant isolation and no acknowledged-event loss.
- [x] **F07-T056 — LOCAL:** Inspect PostgreSQL query plans/indexes for critical
  scoped lists and workers; fail on unbounded table scans/N+1 provider calls at
  the target data profile.
- [ ] **F07-T057 — LOCAL:** Run a 24-hour-or-longer scheduled-job/outbox soak
  harness with bounded queues, stable memory/connections and zero duplicate
  business effects.
- [ ] **F07-T058 — EXTERNAL — ACTION_REQUIRED:** Execute capacity and soak
  tests in production-like infrastructure and approve headroom, autoscaling,
  database/storage limits and the 99.9% availability target.

## I. Accessibility, compatibility and user safety

- [x] **F07-T059 — LOCAL:** Add automated axe checks to all critical persona
  routes and Playwright journeys; block serious/critical WCAG 2.1 AA findings.
- [x] **F07-T060 — LOCAL:** Verify keyboard order, visible focus, focus return,
  dialogs, validation summaries, tables, status announcements, color contrast,
  zoom/reflow and screen-reader labels for critical workflows.
- [x] **F07-T061 — LOCAL:** Run responsive tests for employee mobile flows and
  governance desktop/tablet flows, including constrained browser
  storage/cookies and actionable errors with correlation IDs.
- [x] **F07-T062 — LOCAL:** Add browser/timezone/UTF-8 compatibility coverage
  for Chrome, Edge, Firefox, Safari-equivalent WebKit, Android viewport, iOS
  viewport, overnight dates and Indian-language names/filenames.
- [ ] **F07-T063 — EXTERNAL — ACTION_REQUIRED:** Complete manual keyboard and
  supported screen-reader review with representative users and record approved
  exceptions/remediation dates.

## J. Backup, restore and disaster recovery

- [ ] **F07-T064 — LOCAL:** Provide scripts/runbooks for encrypted PostgreSQL
  logical backup, object metadata/content inventory, configuration/export
  inventory and integrity manifests without logging secrets.
- [ ] **F07-T065 — LOCAL:** Automate restore into an isolated environment,
  run Flyway validation, reconcile row/artifact counts and re-hash immutable
  packages/evidence after restore.
- [ ] **F07-T066 — LOCAL:** Exercise point-in-time/transaction-boundary
  recovery semantics locally where supported and prove no orphaned metadata,
  broken provenance, duplicate business effects or falsely current provider
  state.
- [ ] **F07-T067 — LOCAL:** Implement `T-DR-001` evidence: timed recovery,
  manifest comparison, immutable lineage validation, access revalidation,
  discrepancy report and signed drill result.
- [ ] **F07-T068 — EXTERNAL — ACTION_REQUIRED:** Select production PostgreSQL,
  object storage, region/residency, encryption, backup/PITR and disaster
  recovery services; approve final RPO/RTO (initial target RPO ≤15 minutes,
  RTO ≤4 hours).
- [ ] **F07-T069 — EXTERNAL — ACTION_REQUIRED:** Execute and approve a
  production-like restore/DR drill and schedule quarterly repetitions.

## K. Deployment, feature flags, cutover and rollback

- [ ] **F07-T070 — LOCAL:** Produce reproducible backend/frontend deployment
  artifacts with build metadata, checksums, SBOM, database compatibility range
  and health/readiness contract.
- [ ] **F07-T071 — LOCAL:** Implement server-authoritative feature flags with
  scope, owner, default, dependency, effective window and audit; UI hiding
  alone must not authorize or enable a workflow.
- [ ] **F07-T072 — LOCAL:** Add pre-deploy Flyway validate/migrate rehearsal,
  forward/backward application compatibility checks and evidence-preserving
  rollback instructions; never use destructive schema rollback.
- [ ] **F07-T073 — LOCAL:** Define canary cohorts, success/error/latency/data
  integrity thresholds, observation windows, automated hold/abort and
  measurable rollback triggers.
- [x] **F07-T074 — LOCAL:** Automate post-deploy smoke and E2E-01–E2E-10
  regression, audit/outbox verification and post-rollback integrity checks.
  The aggregate is fail-closed: its record remains unverified until every
  underlying real-system journey and supporting audit/outbox case passes.
- [ ] **F07-T075 — LOCAL:** Preserve all new events/data during rollback,
  disable affected flags/integrations, revert application deployment and
  reconcile before resume.
- [ ] **F07-T076 — EXTERNAL — ACTION_REQUIRED:** Provision staging/production
  runtime, database, object storage, DNS/TLS, network policy, OIDC and provider
  credentials; attach environment validation evidence.
- [ ] **F07-T077 — EXTERNAL — ACTION_REQUIRED:** Approve pilot, parallel run,
  historical backfill, first invoice-evidence cutover and one-cycle contingency
  boundaries with named business/Procurement owners.

## L. Operations, training, documentation and final gates

- [x] **F07-T078 — LOCAL:** Write all 15 PRD 16 operational runbooks with
  detection, owner, diagnostics, safe actions, escalation, communications,
  rollback/containment and closure evidence.
- [ ] **F07-T079 — LOCAL:** Add role-specific user/support guides and in-app
  contextual help for employee, vendor HR/admin, vendor delivery, product
  owner, Procurement, integration admin, governance/reopen and migration.
- [x] **F07-T080 — LOCAL:** Complete architecture, threat model, data
  classification, API/Swagger, UI flows, configuration, deployment,
  observability, backup/restore, DR, incident response and support
  documentation with root README cross-links.
- [x] **F07-T081 — LOCAL:** Run the complete unit, database, contract,
  integration, component, browser E2E, migration, performance, security,
  accessibility and recovery regression suites and publish exact counts,
  failures and evidence.
- [x] **F07-T082 — LOCAL:** Run independent code, test, architecture,
  security/privacy and operations reviews; convert findings to issue Markdown,
  fix all local P0/P1 findings and rerun affected/full gates.
- [ ] **F07-T083 — EXTERNAL — ACTION_REQUIRED:** Complete role training, UAT,
  operational-readiness review, support handoff, incident tabletop and
  Procurement evidence-format acceptance.
- [ ] **F07-T084 — EXTERNAL — ACTION_REQUIRED:** Complete greytHR parallel
  reconciliation/capability certification and provider-specific Linear,
  email, scanner and storage acceptance, or approve the documented internal
  fallback without dual truth.
- [ ] **F07-T085 — LOCAL/EXTERNAL GATE:** Mark release `GO` only when all local
  P0/P1 tasks/tests pass, no critical/high vulnerability remains, every
  external blocker has dated approval evidence, rollback/restore is proven and
  no path can infer approval or mutate closed evidence in place.

## Exit gate

Local engineering completion is necessary but not production approval.
Production remains `NO-GO / ACTION_REQUIRED` until named security, data
integrity, operations, legal/privacy, business and Procurement owners approve
the external evidence in F07-T007, T008, T017, T032, T038–T039, T045, T052,
T058, T063, T068–T069, T076–T077 and T083–T084.
