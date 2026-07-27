# F05 — Final Independent Review

**Reviewer:** Independent final review

**Scope:** Current uncommitted F05 Java/Flyway/React/Playwright implementation,
F05 `TASKS.md`/`TEST_CASES.md`, and requirements 10, 12, 13, 14, 15, 16,
21 and 22.

**Method:** Static source and contract review. This review did **not** run Maven,
Playwright, or repository regression commands; a green outcome must be supplied
by the coordinating validation run.

## Decision

**NO-GO for local regression sign-off.** Two P0 integrity/security defects and
four P1 product/test defects remain. The fixes recorded after the earlier
backend review materially improved F05, but they do not close the findings in
[FINAL_ISSUES.md](FINAL_ISSUES.md).

## What the final pass verified as resolved

| Earlier finding | Disposition | Current evidence |
| --- | --- | --- |
| `F05-FINAL-BE-001` blocked-readiness exception route was unreachable | **Resolved** | `FinanceGovernanceService.acceptException` calls `requireExceptionState`, which now permits `EVIDENCE_PENDING`; the Flyway transition guard permits `EVIDENCE_PENDING → EXCEPTION_ACCEPTED → SUBMITTED_TO_PROCUREMENT`. |
| `F05-FINAL-BE-002` every export rendered a generic tower data set | **Resolved for data projection** | `FinanceReportDataService` has an explicit governed query for each published report code and validates the stored authority snapshot before rendering. This does **not** resolve the new per-report download/replay authorization defect. |
| `F05-FINAL-BE-003` control-tower/report export cursor materialized all rows | **Resolved for those two endpoints** | `FinanceGovernanceService` now uses signed, actor/scope-bound keyset cursors and a bounded SQL query for control-tower rows and export history. Other F05 list endpoints still use the old generic paginator; see `F05-FINAL-005`. |
| `F05-FINAL-BE-004` legal hold/scan changes lacked controlled audit | **Resolved** | `FinanceArtifactGovernanceService` writes a transition/audit/event, and V14 permits the hold mutation only with a same-transaction ledger transition. The scan trigger writes an immutable scan-state audit record. |
| Package PDF omitted the evidence rows | **Resolved** | `LocalFinanceReportRenderer.pdf` renders metadata and row fields rather than a title/count-only output. |

## Remaining local findings

See [FINAL_ISSUES.md](FINAL_ISSUES.md) for exact evidence, impact, remediation
and acceptance tests.

| ID | Priority | Summary |
| --- | --- | --- |
| F05-FINAL-001 | P0 | A report-specific authorization check is dropped after request creation, allowing a generic report-export user to status/download/replay a restricted report by ID. |
| F05-FINAL-002 | P0 | Package and readiness are month-scoped, not invoice-scoped; a primary invoice can be submitted against a package containing a different current invoice/note document. |
| F05-FINAL-003 | P1 | A time-bound exception never expires operationally, and the configured second approver is only a caller-supplied identifier rather than an authenticated approval. |
| F05-FINAL-004 | P1 | Package XLSX output is nondeterministic because ZIP entry timestamps are not fixed; this violates reproducible output hashes. |
| F05-FINAL-005 | P1 | Several F05 list endpoints retain the unsigned, in-memory generic paginator, contrary to cursor/no-unbounded-select requirements. |
| F05-FINAL-006 | P1 | Browser journeys are fixture-intercepted only; no Playwright flow exercises the packaged Java/PostgreSQL system or proves the critical denial paths. |
| F05-FINAL-007 | P2 | The immutable canonical JSON manifest omits required artifact metadata even though the read-model reconstructs it from the artifact table. |
| F05-FINAL-008 | P2 | The control-tower cursor excludes newly created months, but does not snapshot values derived from invoices/readiness/packages updated after page one. |

## External G4 acceptance — not a local defect

The following remain explicitly external and are not counted as implementation
findings: approved private object storage; malware/quarantine and renderer
services; production OIDC, PostgreSQL grants, backup/restore environment and
retention approval; Procurement package/process approval; and AP/ERP adapter
acceptance. They remain required before production release.

## Local release condition

1. Resolve all P0/P1 items in [FINAL_ISSUES.md](FINAL_ISSUES.md), including
   the stated HTTP/integration and deterministic renderer tests.
2. Re-run the F05 integration/security suite, full Maven verification,
   frontend type/lint/unit/build checks, real-system Playwright regression and
   the F01–F04 regression suite.
3. Re-review the final diff and update the issue status with actual command
   outputs. Only then can F05 be **GO for local regression**; G4 remains a
   separate production acceptance gate.
