# F07 — Test Review

## Evidence reviewed

The implementation contains focused automated tests for database capabilities,
HTTP hardening, request-size enforcement including chunked bodies, trusted
proxy rate limiting, outbound URI policy, retention/legal hold, feature flags,
low-cardinality observability, readiness, capacity/query plans, safe frontend
errors and accessibility.

Final local evidence includes:

- frontend: typecheck pass; lint 0 errors/6 non-blocking Fast Refresh
  warnings; Vitest 24 files/92 tests; build 3,006 modules with a 586.90 kB
  largest-chunk optimization advisory; diff-check pass;
- focused backend: 73 unit + 45 integration;
- capacity: 73 unit + 2 capacity, with all recorded local latency targets;
- F07/finance/migration local-system: 7/7, 4/4 and 6/6;
- browser: preserved 268/274, exact failed-slice 7/7, final 274/274;
- definitive Maven R3: 73 unit + 217 integration (290/290), zero
  failures/errors/skips, BUILD SUCCESS in 03:21;
- final Terra review: closed with no P0–P3 finding.

## Preserved failures

1. A real Testcontainers migration stalled in V21 while ownership transfer
   waited on `flyway_schema_history`. Diagnosis showed that the intended
   exclusion had been placed in the wrong loop. V21 now excludes the history
   relation and uses a five-second lock timeout.
2. A subsequent migration reached V22 and failed because PostgreSQL does not
   accept a comma-separated multi-table `ALTER TABLE ... OWNER`. The ownership
   statements were split.
3. The first five-project accessibility run passed all six cases in Chromium,
   WebKit, Android and iOS, but Firefox timed out under two-worker contention.
   A serialized Firefox run passed 6/6; its project is now non-parallel.
4. The initial operations self-test did not reveal provenance, migration and
   backup substitution weaknesses later found by independent review. The
   harness was strengthened and current local harness/schema gates pass.
5. The first post-capacity frontend test run executed 92 tests successfully but
   the Vitest command still failed because `scripts/f07/load-harness.test.mjs`
   was incorrectly discovered as a zero-test suite. It has been moved to the
   explicitly invoked `load-harness-self-test.mjs`; final Vitest passes 24
   files/92 tests.
6. The first combined F07 database rerun reached a clean 5/5 feature-flag
   result, but the standalone migration-bootstrap container timed out while
   waiting for a Testcontainers log message even though PostgreSQL had become
   ready. The test now waits for the listening port with a three-minute
   startup limit; focused and definitive Maven R3 pass.
7. Complete Maven R2 passed 73 unit and 215/217 integration tests in 39:23,
   with two approximately 16–17 minute Docker host starvation/clock-leap
   pauses. Its two worker counts came from shared test-database state. Assigning
   `DeliveryCommitmentOperationsWorkerIT` the dedicated
   `vms_workflow_delivery_commitment_worker` database produced R3 290/290.

Failures are retained because they explain the fixes and prevent a later green
run from erasing risk history.

## Coverage assessment

Strong local coverage exists for the F07 verticals. Remaining evidence gaps
are:

- commit-bound execution, not just schema validation, of the supply-chain and
  backup/restore gates;
- long-duration soak and production-like capacity;
- manual keyboard/screen-reader review;
- real OIDC/providers/storage/scanner/on-call/DR and organizational approvals.

## Review decision

Local engineering status is **PASS / COMMIT-BOUND EVIDENCE PENDING**.
Production status is **NO-GO / ACTION_REQUIRED** until all mandatory external
records are supplied.
