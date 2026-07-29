# F07 — Independent Code Review

This is the preserved pre-fix review record. Findings are not erased when a
fix is generated; their current disposition is cross-linked to
[FIXES.md](FIXES.md). Focused and definitive complete Maven evidence is green.
The final Terra review is closed with no P0–P3 finding.

## Backend/security findings

| ID | Severity | Finding | Current disposition |
|---|---:|---|---|
| F07-BE-001 | P0 | Production Flyway could enter V21 without a workable ownership/bootstrap path. | Remediated in code with a controlled migration-login bootstrap and constrained-login integration test; included in the 73-unit + 45-integration focused pass. |
| F07-BE-002 | P1 | V21 ownership transfer could wait indefinitely and initially attempted to transfer the locked Flyway history relation. | Remediated with history exclusion and a bounded lock timeout. The original Testcontainers hang remains recorded in test review. |
| F07-BE-003 | P1 | Runtime configuration could assume the migration-owner role. | Remediated by removing runtime `SET ROLE` and rejecting migration capability membership at startup. |
| F07-BE-004 | P1 | Retention execution lacked single-owner concurrency, bounded retry/dead-letter and authorized recovery semantics. | Remediated with advisory/row locks, per-candidate atomic work, transition ownership and explicit recovery; focused post-fix tests pass. |
| F07-BE-005 | P1 | Chunked requests could bypass the declared-content-length limit. | Remediated by bounded pre-read/replay for non-multipart mutation bodies and a hard configured maximum. |
| F07-BE-006 | P1 | Commercial field blocking could be bypassed with alternate/nested names. | Remediated in V22 with normalized key checks and adversarial integration fixtures. |
| F07-BE-007 | P1 | Random webhook connection IDs and untrusted forwarded addresses could distort rate-limit buckets. | Remediated with known-connection buckets, an unknown bucket and exact trusted-proxy handling. |
| F07-BE-008 | P1 | Retention candidates/proofs could contain null source hashes. | Remediated with `NOT NULL` constraints and canonical SHA-256 fallback material. |
| F07-BE-009 | P2/P1 | The legacy finance legal-hold mutation could bypass the new release workflow. | Remediated in service code and with a database trigger that protects direct writers. |

## Release, recovery and supply-chain findings

| ID | Severity | Finding | Current disposition |
|---|---:|---|---|
| F07-OPS-001 | P0 | A release could be marked GO using an arbitrary file/digest without binding record, command, result, commit and provenance; CI skipped required suites. | Remediated with structured command evidence, commit-bound provenance and explicit CI suites; local static/schema/harness gates pass. Commit-bound release execution remains open. |
| F07-OPS-002 | P1 | Migration preflight trusted declared booleans and did not compare live Flyway checksums to source. | Remediated with loopback-only live comparison, exact base ref and checksummed rehearsal evidence. |
| F07-OPS-003 | P1 | Backup used unauthenticated encryption/manifest semantics vulnerable to substitution or replay. | Remediated with independent HMAC-SHA256 manifest authentication, UUID/source/time/commit binding and replay/freshness checks. |
| F07-OPS-004 | P1 | Symlinks and unsafe archive members could escape evidence/restore roots. | Remediated with canonical-path, exclusive-create and tar member/link/type checks. |
| F07-OPS-005 | P1 | Mutable image identity was not bound into provenance. | Remediated by digest-pinning PostgreSQL and recording configured image identity. |
| F07-OPS-006 | P2 | Fixed output directories could overwrite or conflate runs. | Remediated with unique run IDs and exclusive evidence paths. |
| F07-OPS-007 | P2 | Only the top-level Semgrep version was pinned. | Remediated to checksum the published wheel; its transitive Python closure is explicitly not claimed hash-locked. |

## Review conclusion

The review found real release-blocking defects and drove material design
changes. Focused post-fix evidence passes 73 unit + 45 integration tests, the
capacity lane passes 73 + 2, F07/F05/F06 system lanes pass 7/7, 4/4 and 6/6,
and the browser matrix passes 274/274. Intermediate Maven R2 passed 215/217
integration tests; its two delivery-worker counts were contaminated by a
non-dedicated test database. After assigning the worker IT a dedicated
database, definitive R3 passes 73 unit + 217 integration (290/290), zero
failures/errors/skips. The final Terra review closed with no P0–P3 finding.
Commit-bound release/restore evidence is still required. External
production decisions remain outside this repository, so production is
`NO-GO / ACTION_REQUIRED`.
