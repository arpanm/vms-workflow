# F05 Final Backend Review

**Reviewer:** Terra independent re-review  
**Scope:** Current F05 Java backend, Flyway migration and F05 backend tests.  
**Method:** Static review only. Maven was intentionally not run in this review pass.

## Decision

**NO-GO.** Four actionable findings remain: one P0, one P1 and two P2 items. Nine of the thirteen post-fix findings are fixed locally.

## Remaining actionable findings

| ID | Priority | Finding | Evidence | Required completion evidence |
|---|---|---|---|---|
| F05-FINAL-BE-001 | P0 | A blocked readiness exception cannot be accepted. `acceptException` invokes `requireReviewState`, which excludes `EVIDENCE_PENDING`; the new `requireExceptionState` includes it but is unused. This leaves the required `EVIDENCE_PENDING → EXCEPTION_ACCEPTED → SUBMITTED_TO_PROCUREMENT` workflow unreachable. | `FinanceGovernanceService.acceptException` line 349; `requireReviewState` lines 1028–1038; unused `requireExceptionState` lines 1040–1050. | Automated E2E success path plus expired exception, same-user second approver, mismatched package/readiness, revoked authority and cross-tenant denial paths. |
| F05-FINAL-BE-002 | P1 | Report export authorization is checked when queued and an authority snapshot is stored, but the worker ignores both the report ID’s data contract and the snapshot. It renders the same full control-tower row set for every report code, with no persona-specific projection/masking or defined metric calculation. | `FinanceGovernanceService` lines 1452–1506 and 635–648; `FinanceOperationsWorker.render` lines 157–191. | Per-report query/projection and formula tests; cross-persona export/screen parity and non-disclosure tests; test that worker uses the persisted request authority contract. |
| F05-FINAL-BE-003 | P2 | Controller cursors are opaque stable keys, but are created after complete in-memory result lists are loaded. They are not scoped database keyset/snapshot cursors, so large histories are capped/materialized and concurrent writes have no snapshot continuity guarantee. | `FinanceController.page` lines 494–555; control tower/report routes lines 310–324 and 423–437. | Keyset/snapshot cursor implementation with scope binding, plus concurrent insert/update continuity and stale-cursor tests. |
| F05-FINAL-BE-004 | P2 | Artifact scan transitions are narrowed by the trigger, but legal hold has no controlled audited transition and scanner state changes have no independent audit record. The trigger in fact prevents any legal-hold update, while describing a legal-hold transition. | `V14__finance_evidence_invoice_reporting.sql` lines 722–774; artifact update paths in invoice/package/export services. | Authorized legal-hold workflow, audit event/authority snapshot for legal-hold and scan transitions, and direct-SQL rejection tests. |

## Verified local resolutions

- Exact F04 readiness ID is validated before package generation.
- Package entries disclose upstream source-binary unavailability and provide JSON, PDF, CSV and XLSX outputs, with byte-level hashes.
- Effective policy is applied to upload/readiness/exception processing.
- Finance AP organization scope, report replay, query-response visibility, production-default scanner fail-closed behavior and database-backed finance rate limits are present.

The package PDF renderer currently produces only a title and row count. This is not separately counted because it belongs in the report/package-content completion under F05-FINAL-BE-002; the required human-readable evidence summary and appendices must be rendered before release.

## Release condition

Implement and test all four findings, then run the F05 backend integration/security suite and the repository end-to-end regression gate. Update [POST_FIX_ISSUES-BACKEND.md](POST_FIX_ISSUES-BACKEND.md) when the P0 exception test is passing.
