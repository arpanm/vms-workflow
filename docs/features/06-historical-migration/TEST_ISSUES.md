# F06 Historical Migration — Required Test Issues

## Current disposition

The real Spring/Flyway/PostgreSQL workflow suite now covers contract,
authorization, idempotent upload, duplicate-before-domain-apply, rejected-only
reprocess, signed cursor, reconciliation-bound SoD, rollback compensation and
retro time. `MigrationDomainAdapterIT` exercises all 14 physical templates
against their authoritative tables. The isolated Playwright system lane covers
the browser/server contract, scan/validate/reconcile, forged-role denial,
dual approval, commit/domain visibility, audit/rollback, safe error export,
reprocess and retro creation. Exact final counts are recorded after the final
regression rather than inferred here.

Production scanner/provider, 100k-row capacity and controlled-environment
acceptance remain explicit `ACTION_REQUIRED` gates; local fixtures do not
silently close them.

## P0 release blockers

- `T-MIG-CONTRACT-001`: generated/client contract test for all controller routes, parameter names, request fields, status, ETag and response fields. Run against Spring, not route interception.
- `T-MIG-DOMAIN-001..014`: one Testcontainers commit test per physical template (including both `07a` and `07b`) proving canonical domain adapter effects, provenance and normal API visibility.
- `T-MIG-SEC-001`: user with migration approval permission but no governance/business assignment cannot nominate themselves as that approval role.
- `T-MIG-SEC-002`: cross-tenant, same-tenant-wrong-organization, expired/disabled, and direct download attempts return non-disclosing denial; audit/security event is emitted where policy requires.

## P1 before historical cutover

- `T-MIG-LIFE-001`: retry validation after invalid result does not delete immutable evidence and reaches a legal new attempt/version.
- `T-MIG-LIFE-002`: stale `If-Match`, duplicate idempotency key, and parallel commit attempts have one business effect and deterministic response.
- `T-MIG-LIFE-003`: rejected-row reprocess stages only selected rejected records, carries parent/provenance, and cannot recommit accepted facts.
- `T-MIG-ATT-001`: raw/daily authority uses employee-local date across offsets/overnight sessions; duplicate authority is rejected without canonical side effect.
- `T-MIG-REC-001`: pre-commit reconciliation includes source hashes, rows, expected/imported attendance coverage, domain reconciliation exceptions, confidence, package/version linkage; changes invalidate prior approval.
- `T-MIG-RB-001`: unconsumed domain batch compensates through versioned deactivation; any downstream attendance/plan/certification/confirmation/package/invoice dependency forces reopen correction.
- `T-MIG-FILE-001`: malicious, malformed, binary, too-large, invalid-encoding, formula, and scan-failed uploads are quarantined and not exposed or packaged.

## P2

- `T-MIG-UX-001`: keyboard-only upload, validation errors, row filter/pagination, approval and destructive-action confirmation meet WCAG 2.1 AA expectations.
- `T-MIG-PERF-001`: 100k-row validation is asynchronous, resume-safe, bounded in memory, emits progress, and does not starve normal APIs.
