# F05 Backend Test Issues

**Disposition:** follow-up automation is prepared but not yet executed. These
findings distinguish product blockers from remaining evidence gaps.

## P1

### F05-TEST-001 — The new F05 follow-up lane has no fresh compile/run evidence

- **Evidence:** `FinanceLocalAdaptersTest`, `FinanceDatabaseControlsIT`,
  `FinanceWorkflowIT`, `FinanceExportWorkerIT`, `FinanceExportRetryIT`, and the
  V1004 fixture were added or expanded after the last focused green execution.
  Maven was intentionally not run because another agent owned validation of
  the shared `backend/target` directory.
- **Impact:** the sources are prepared for compilation, but their status must
  not be represented as green or release evidence.
- **Required action:** run the exact focused commands in
  `TEST_AUTOMATION-BACKEND.md`, retain the XML/log counts, correct any fixture or
  assertion mismatch, and then run full `mvn verify`.
- **Disposition:** Open execution gate.

### F05-TEST-002 — A naturally blocked invoice cannot currently reach the exception API

- **Evidence:** `FinanceGovernanceService.acceptException` calls
  `requireReviewState`, whose allowed states exclude `EVIDENCE_PENDING`.
  Quarantined/missing evidence produces a blocked readiness result before
  submission, while submission correctly refuses an ineligible run. The
  follow-up test therefore injects one deterministic blocked result into an
  otherwise submitted exact lineage solely to exercise second-approver and new
  readiness-run behavior.
- **Impact:** the second-approver implementation is testable, but there is no
  executable real-user path from an initial blocked readiness result to
  exception acceptance. `T-PROC-003` and E2E-06 are not fully satisfied by the
  synthetic setup.
- **Required action:** define and implement the intended exception entry state
  (normally use the existing `requireExceptionState` contract), then replace
  the injected readiness result with a quarantined or otherwise naturally
  blocked invoice flow.
- **Disposition:** Open product/test integration blocker.

### F05-TEST-003 — Commit-boundary and true concurrency proof is still absent

- **Evidence:** the main F05 integration classes use test-managed
  `@Transactional` rollback. Idempotent repeats are serial MockMvc calls, and
  the export worker is invoked synchronously.
- **Impact:** rollback can conceal commit/outbox defects, and the suite does not
  prove competing package generation, invoice transition, review, share,
  export claim, lease-loss, or replay behavior on independent connections.
- **Required action:** add non-test-transactional cases using independent
  `TransactionTemplate`/connections and barriers; assert the committed winner,
  safe loser response, immutable lineage, and exact event/outbox counts.
- **Disposition:** Open local release gap.

## P2

### F05-TEST-004 — Export failure coverage is deterministic but narrow

- **Evidence:** `FinanceExportRetryIT` uses the configured unavailable scanner
  to reach retry/dead letter/replay. It does not inject a renderer crash,
  storage write failure, corrupted stored bytes, expired download, worker
  restart, concurrent claim, or lease loss.
- **Impact:** `T-F05-JOB-001`, `T-STOR-004`, and `T-REP-006` retain resilience
  gaps.
- **Required action:** add fake adapter fault points and independently
  committed worker tests for each failure phase, including checksum mismatch
  and expired result download.
- **Disposition:** Open.

### F05-TEST-005 — Several catalogued security and operations cases remain manual/external

- **Evidence:** the new tests cover RBAC scopes, share expiry/revoke, formula
  safety, quarantine, and private download, but not database application-role
  grants, upload size/MIME/path traversal, rate limits, retention/legal hold,
  signed object URLs, backup/restore, p95 load, or approved production
  storage/scanner/renderer/AP integration.
- **Impact:** local G1–G3 and external G4 cannot be closed by this focused lane
  alone.
- **Required action:** implement the remaining local security/operations cases
  and keep `T-STOR-006`, `E2E-F05-PROVIDER-001`, and
  `E2E-F05-PROVIDER-002` explicitly external.
- **Disposition:** Open.

## 2026-07-30 focused audit update

- `F05-TEST-001` is no longer accurate as a blanket “no fresh run” statement:
  main/test compilation plus `FinanceSecurityIT` 5/5,
  `FinancePaginationIT` 2/2 and `FinanceExportWorkerIT` 5/5 passed in this
  focused audit. This is not a fresh full `mvn verify`.
- `F05-TEST-004` is partially reduced: expired export download and altered
  result-checksum denial are now permanent passing cases. Storage/renderer
  fault injection, worker restart, concurrent claim and lease loss remain open.
- `F05-TEST-003` is partially reduced by
  `FinanceCommittedConcurrencyIT`: a non-transactional two-worker export race
  commits exactly one artifact/event/outbox effect. Competing package, invoice,
  review and share mutations still need equivalent independent-connection
  proof.
- `F05-TEST-002`, `F05-TEST-003` and the local/external portions of
  `F05-TEST-005` remain open and must not inherit the focused green result.
