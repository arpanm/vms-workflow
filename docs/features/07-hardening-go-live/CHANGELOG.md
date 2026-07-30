# F07 — Changelog

## 2026-07-30 — review-evidence validator hardening

- Advanced review evidence to the current reviewed commit and typed schema
  `f07-review-evidence-v2`.
- Validation now proves `reviewedThroughCommit` is a Git commit object and an
  ancestor of the validated release, rather than accepting a SHA-shaped value.
- Added traceability schema/type checks, exact runbook-anchor verification and
  per-dimension structured closure dispositions linked to their review and
  issue documents.
- Added adversarial tests for stale schema, a Git tree masquerading as a
  commit, missing closure dimensions and missing anchors. Corrected stale
  traceability anchor names. Self-test passes 9/9; ops verification passes 1/1.

## 2026-07-30 — traceability and review-control closure

- Replaced blanket RQ-033/RQ-034/RQ-035 assignment with a machine-validated
  85-task/76-test impact matrix covering PRD, schema, API, UI, runbook and
  rollback consequences.
- Bound F07-T082 and F07-REV-001 to a five-dimension review-evidence case
  instead of treating an empty required-case list as completion evidence.
- Made the release gate derive each record's requirement IDs from canonical
  traceability policy and fail on missing, unknown or orphaned mappings.
- Updated F07 architecture and automation documentation for the current
  production Flyway V1–V38 chain while preserving historical V1–V33 execution
  claims as historical evidence.
- Removed the self-test's nondeterministic assumption that the shared worktree
  must always be dirty; the assertion now compares the gate result with the
  actual decision-time repository state.

## 2026-07-28 — active hardening/go-live worktree

### Added

- PostgreSQL least-privilege capability roles, runtime role verification and a
  constrained migration-login bootstrap.
- Versioned retention schedules, deterministic dry runs, capability-expiry
  proofs, retry/dead-letter recovery, data classification and dual-control
  legal holds.
- Server-authoritative scoped feature flags with effective windows,
  dependencies, immutable transition audit and non-authorizing evaluation.
- Low-cardinality API/operational metrics, mandatory readiness and
  optional-provider degradation health.
- Exact-origin CORS, security headers, request-size enforcement, core rate
  limits, outbound URI policy and safer exception responses.
- F07 capacity/query-plan tests and V24 indexes.
- Cross-browser accessibility cases, skip-link/focus/mobile/reduced-motion
  improvements and safe client error presentation.
- Commit-bound release evidence, provenance/SBOM/supply-chain gates, migration
  preflight, canary/rollback validation, encrypted authenticated backup/restore
  drill and operations runbooks.

### Changed

- PostgreSQL Compose image is digest pinned.
- The finance legal-hold release path now defers to the F07 governance
  workflow.
- Linear URL validation uses the shared outbound URI policy.
- CI is designed to execute frontend, Maven, Playwright, F05/F06 local-system,
  F07 operations and supply-chain lanes before release evaluation.

### Security and review remediation

Preserved independent review drove fixes for migration bootstrap/locks,
runtime privilege escalation, retention concurrency/recovery, chunked-body
limits, commercial-key bypass, callback bucket abuse, proof hashes,
legal-hold bypass, evidence spoofing, backup substitution/replay, path escape,
mutable image provenance and output collisions.

### Verification status

At this historical 2026-07-28 checkpoint, focused evidence existed, including
the F07 accessibility 24/30 matrix and isolated Firefox 6/6 recovery run; the
final consolidated regression and post-fix review had not yet been recorded.
Their later results are preserved in the 2026-07-29 section below.
Production/platform/provider/legal and organizational gates remain
`ACTION_REQUIRED`.

Preserved failures include a Vitest zero-test discovery after 92 tests passed
and a Testcontainers bootstrap log-wait timeout after the feature-flag subset
passed 5/5. The zero-test file was moved outside Vitest discovery and the
bootstrap was bounded by a port wait; corrected focused/static reruns pass.

## 2026-07-29 — final local evidence reconciliation

### Added and changed

- Extended the production migration chain through V33: delivery transport,
  Linear command ledger, greytHR attestation authority, lineage/proxy
  hardening, delivery-worker least privilege and employee-policy tenant gate.
- Made production provider adapters fail closed and confined recorded adapters
  to non-production evidence.
- Preserved source authority, immutable provider attestations, duplicate
  no-effect semantics, staged/cutover/correction behavior and terminal
  reconciliation evidence.
- Corrected finance package upstream source lineage, confirmation predecessor
  lineage after reopen, invalidation-aware idempotent certification handoff and
  Linear terminal-attempt observability.
- Corrected system-test authority, timestamp canonicalization and version
  header setup.

### Supply-chain remediation

- Preserved the first exact supply-chain run as a real failed gate: it found
  PostgreSQL JDBC 42.7.11 HIGH risk, newly published Jackson MEDIUM risks,
  vulnerable packages in the then-current official PostgreSQL 18 Alpine image,
  four worker deployment-policy gaps and license inventory false positives.
- Upgraded PostgreSQL JDBC to 42.7.12 and Jackson 2/3 databind lines to 2.21.5
  and 3.1.5.
- Replaced all executable PostgreSQL image contracts with digest-pinned
  Chainguard PostgreSQL 18.4; its focused Trivy scan has zero HIGH/CRITICAL
  findings and the official entrypoint/environment contract passes a real
  database smoke.
- Added restricted pod and container security contexts for every F07 worker.
- Corrected the fail-closed license inventory to exclude synthetic scanner
  manifests, parse SPDX `AND`, `OR` and `WITH`, enumerate accepted transitive
  licenses and continue rejecting unknown/malformed/forbidden licenses.
- The exact full post-fix supply-chain run passes every scanner/report, both
  release artifacts and the digest-pinned PostgreSQL image with zero findings.
  Commit-bound provenance binding remains required after the remediation
  commit.
- Bounded migration browser polling and captured exact multipart filenames;
  the isolated Firefox `_page` error did not reproduce.

### Verification

- pre-final backend: 73 unit + 45 integration, all pass;
- capacity: 73 unit + 2, all pass; dashboard 101ms, check-in p95 404ms, replay
  p95 69ms, 10k search p95 2ms and 300k report p95 9ms;
- F07 system: 7/7; finance system: 4/4; migration system: 6/6;
- browser history: 268/274, exact failed slice 7/7, complete rerun 274/274;
- frontend/static passes: typecheck; lint 0 errors/6 non-blocking Fast Refresh
  warnings; Vitest 24 files/92 tests; build 3,006 modules with 586.90 kB
  largest-chunk advisory; diff-check;
- complete Maven R2: **73 unit pass; 215/217 integration pass**. The two
  `DeliveryCommitmentOperationsWorkerIT` counts came from shared test-database
  state. The run completed in 39:23 under the corrected one-hour watchdog and
  preserved two approximately 16–17-minute host
  thread-starvation/clock-leap pauses;
- delivery-worker IT now uses dedicated database
  `vms_workflow_delivery_commitment_worker`;
- definitive Maven R3: **73 unit + 217 integration (290/290)**, zero
  failures/errors/skips, BUILD SUCCESS in 03:21 at 15:49:59 IST.
- final Terra review closed with no P0–P3 finding.

Production remains `NO-GO / ACTION_REQUIRED` for real provider, identity,
legal/privacy, deployment/canary, production-like soak/load/DR, manual
accessibility, UAT and named release approvals.
