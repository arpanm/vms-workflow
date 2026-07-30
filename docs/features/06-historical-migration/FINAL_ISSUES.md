# F06 Historical Migration — Final Open Issues

## Final integrated reconciliation — 2026-07-30

No local F06 implementation issue remains open through V43. The outstanding
items are external source-owner mapping/sign-off, approved production
storage/scanner, controlled 100k-row rehearsal, backup/restore and cutover/DR
approval. Aggregate Maven/browser failures remain recorded even though focused
F06 recovery passes.

> **Superseded review snapshot.** This file preserves the independent review
> findings as raised. The authoritative post-fix disposition is recorded in
> `FINAL_REVIEW.md` after the final F06 regression. P0 findings 1–2 and local
> P1/P2 findings 3–4 and 6–9 have been remediated in the active worktree.
> Finding 5 remains an external production-integration gate: the local scanner
> is fail-closed and fully testable, but an approved production malware
> provider, callback credentials and controlled-environment evidence cannot be
> fabricated in this repository.

## Post-remediation findings and disposition

The final bounded review raised four additional local findings. All were fixed
before the definitive regression:

| Finding | Severity | Final disposition |
|---|---|---|
| Approval UI omitted the mandatory `decision` field and the DTO accepted null | P0 | **Resolved.** The DTO validates `APPROVED`, the client sends it, and API plus frontend tests exercise the contract. |
| The displayed partial-commit checkbox did not affect the request | P1 | **Resolved.** Partial commit is an immutable upload-time policy and commit must explicitly reaffirm the recorded value; API, UI and real-system tests cover both values. |
| Delivery assignment/dependency provenance could not be compensated | P1 | **Resolved.** Both child tables are allow-listed with ownership/provenance guards and the Testcontainers rollback case proves removal. |
| Existing unauthorized job/report IDs returned a distinguishable 403 | P1 | **Resolved.** Scope denial now uses the same generic 404 result as absence and integration tests prove non-enumerability. |
| Provenance uniqueness rejected legitimate insert-plus-update effects on one record | P1 regression finding | **Resolved in V19.** Provenance is unique by job, row and effect sequence; rollback remains guarded by exact table/record provenance. |
| A late duplicate could partially apply an all-or-nothing batch | P1 | **Resolved.** The conflict aborts the transaction; a dedicated PostgreSQL integration test proves the ready state and zero earlier effects survive. |
| Upload-time `partial_commit` was not database-immutable | P1 | **Resolved in V20.** A forward-only trigger rejects every later policy change. |
| Any nonblank compensation session flag could satisfy an append-only delete guard | P1 | **Resolved in V20.** The flag must identify a real `COMPENSATE` rollback action whose job matches active table/record provenance. |
| Reconciliation sign-off allowed an omitted decision | P1 | **Resolved.** DTO validation requires `APPROVED` or `REJECTED`; omitted decisions return a typed 400. |

There are no accepted unresolved local P0/P1 findings after the post-fix review.
The production scanner/private object storage, controlled 100k-row capacity
evidence and masked source-owner rehearsal remain external release gates.

## P0 — release blockers

1. **Rollback leaves canonical domain effects active.**

   Commit invokes the adapter before generic provenance is persisted ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:723)) and records `migration_domain_provenance` ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:788)). Rollback only marks `migration_canonical_facts` inactive and removes attendance authority ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:977)). Employees, allocations, leave, attendance events/days, delivery, confirmation, invoice, and approval records remain usable.

   Required acceptance: for each adapter effect, an unconsumed rollback must append a domain-specific compensating/versioned change, mark domain provenance inactive with its compensation reference, and leave no active effect in normal APIs. Any downstream use of any such effect must require a reopen/version correction.

2. **Duplicate rows can mutate a domain before being rejected as a duplicate.**

   `domainAdapter.apply` runs before the active canonical fact is locked/checked ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:723)); only afterward does the service set the row `DUPLICATE_CONFLICT` and continue ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:730)). The transaction therefore commits the adapter's new domain row/session/version even though the migration row is not committed.

   Required acceptance: replay a previously committed employee, allocation, raw punch, daily row, deliverable, certification, confirmation, invoice, and approval-history row. Each must create zero new domain records and zero new active provenance effects; conflicting data must require an explicit supersede before an adapter runs.

## P1 — must close before controlled historical backfill

3. **Reprocess is still full-source revalidation.** It must stage only parent rejected rows or a corrected replacement source with explicit row lineage; add partial-commit/reprocess Testcontainers coverage.

4. **Adapter semantics and acceptance coverage are incomplete.** There is no parameterized evidence for all 14 templates. Fix and test deliverable employee assignments/criteria/dependencies, historical approver identity and authority, invoice evidence-file hash linkage, approval-history verification, leave/holiday/allocation invariants, and overnight/raw-day reconciliation.

5. **Scanner is not production-ready.** The local scanner is acceptable as a test harness only. Integrate an approved malware scanner with durable verdict callback/retry/dead-letter monitoring, object-storage access control, quarantine operations, and staging acceptance evidence.

6. **Reconciliation lacks required coverage.** Replace the synthetic/zeroed UI values with real expected-versus-imported attendance days, employee/leave exceptions, plan/deliverable/Linear coverage, certification/confirmation provenance, invoice linkage, and low-confidence list. Test report invalidation after resolution/reprocess.

7. **SoD closure lacks adversarial proof.** Add HTTP tests for forged approval/sign-off role, a subject with multiple authority assignments, same authority organization, disabled/expired assignment, and stale reconciliation hash.

8. **API acceptance is still mock-only.** Add an authenticated browser-to-real-Spring/fixture-database journey. It must perform the current upload, scan result, validate, reconciliation, two server-derived sign-offs, commit, error export, retro request, reprocess and rollback paths. Do not treat the intercepted Playwright suite as end-to-end evidence.

## P2

9. **Cursor contract is unfinished.** The client sends `cursor` ([api.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/migration/api.ts:35)), but the controller only accepts `limit` ([MigrationController.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/api/MigrationController.java:73)). Implement an opaque cursor or remove it from UI/types.

10. **Template artifact integrity policy remains ambiguous.** Registry stores reference sample checksums, but upload verifies header/version only. Document and implement whether a signed template artifact checksum is required in addition to user data validation.
