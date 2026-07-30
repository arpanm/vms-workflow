# F06 Historical Migration — Final Independent Closure Review

## Final integrated reconciliation — 2026-07-30

**Local code:** complete through V41/V43. **Focused evidence:** migration
system 6/6, migration/OpenAPI recovery 1/1, accessibility 3/3. **Aggregate
evidence:** Maven 340 retained 2 failures + 1 error; browser retained 287/292,
with recovery recorded separately. **Release:** external
`NO-GO / ACTION_REQUIRED`.

> **Historical pre-fix review.** The decision and matrix below intentionally
> preserve the independent review result before remediation. A post-fix closure
> section will be appended only after the complete F06 backend, frontend,
> intercepted-browser and real-system regression lanes pass.

**Review date:** 28 July 2026
**Scope:** current F06 working tree; no product code changed during this review.

## Decision

**Not ready for historical cutover.** The remediation closes meaningful implementation gaps, but F06 still has two correctness release blockers: duplicate detection happens after domain mutation, and rollback does not compensate the domain records created by the 14 adapters. The real browser-to-Spring acceptance flow and full template coverage are also not demonstrated.

## Verification performed

- `mvn -q -f backend/pom.xml test` — passed.
- `mvn -q -f backend/pom.xml verify` — passed, including `MigrationWorkflowIT` against Testcontainers PostgreSQL.
- `npm run typecheck`, focused migration Vitest, and the `f06-migration-chromium` Playwright project — passed.
- Source review of V17/V18, controller/DTO/client, `MigrationDomainAdapter`, scanner, authorization, integration test, and migration E2E fixture.

The Playwright suite still intercepts all migration endpoints, so its passing result is not proof of the browser/API contract.

## Prior finding closure matrix

| Prior finding | Status | Evidence and conclusion |
|---|---|---|
| Frontend/backend API contract drift | **PARTIALLY CLOSED** | The client now sends template/job scope and upload DTO names ([api.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/migration/api.ts:31)); server supplies frontend aliases in `jobSummary` ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:1754)); plural approval path is accepted ([MigrationController.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/api/MigrationController.java:154)). It remains open at acceptance level: the UI never executes against this controller, and it still sends unsupported `cursor` pagination. |
| Generic facts instead of domain effects | **PARTIALLY CLOSED** | `MigrationDomainAdapter.apply` has all 14 physical template cases ([MigrationDomainAdapter.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationDomainAdapter.java:35)) and commit records domain provenance ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:725)). This is not closed: only employee/raw-attendance paths have integration coverage, several template semantics remain incomplete, and rollback does not compensate those effects. |
| Client-selected approval role / SoD | **CLOSED IN CODE; OPEN IN NEGATIVE TESTS** | Authority is derived from active scoped assignment, and a requested mismatched role is denied ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:618); [MigrationAuthorizationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/security/MigrationAuthorizationService.java:117)). Commit requires distinct actor and authority organizations ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:1526)). No test sends forged roles or proves same-authority-chain rejection. |
| Immutable retry deletes rows/findings | **CLOSED IN CODE; OPEN IN TESTS** | Validation no longer deletes immutable staging evidence; V18 creates append-only validation attempts ([V18__historical_migration_hardening.sql](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/resources/db/migration/V18__historical_migration_hardening.sql:3)). A job with existing rows is fail-closed and directed to reprocess ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:368)). No integration test proves this path. |
| Reprocess reparses all original rows | **OPEN** | Child reprocess job reuses the original source ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:897)); validation always iterates every source record ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:405)). It has no rejected-row selection/edited-source mechanism. |
| Raw/daily timezone authority | **CLOSED IN CODE; OPEN AT BOUNDARY COVERAGE** | Raw authority now converts the timestamp to the provided IANA zone ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:1572)); adapter uses the same conversion ([MigrationDomainAdapter.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationDomainAdapter.java:316)). Existing test covers same-date conflict, not an offset/midnight or overnight boundary. |
| Source scan/quarantine | **PARTIALLY CLOSED** | Upload starts `PENDING`, records a scanner verdict, and validation blocks until `PASSED` ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:194), [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:332)). V18 restricts the permitted state transition. Production remains blocked: the configured implementation is a local EICAR string check and returns `PENDING` when disabled ([ConfiguredMigrationMalwareScanner.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/ConfiguredMigrationMalwareScanner.java:23)); no approved external scanner/callback or external acceptance test exists. |
| Pre-commit reconciliation | **CLOSED IN CODE; OPEN IN CONTENT COVERAGE** | Validation writes a reconciliation before `READY_TO_COMMIT` ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:486)); approval/commit bind the current report/hash. Current report coverage contains only template, source/count flags and zeroed UI employee-day values, not the required cross-domain coverage. |
| Generic formula sanitization corrupts stored business values | **CLOSED IN CODE** | Staged payload is persisted as validated values, not `formulaSafe` transformed values ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:447)); formula neutralization remains in CSV error export. |
| Narrow rollback / no domain compensation | **OPEN — P0** | Commit applies domain effects and records `migration_domain_provenance` ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:725)), but rollback only deactivates generic facts and deletes authority rows ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:977)). It neither compensates nor versions domain effects/provenance. Consumption checks omit delivery, certification, confirmation, leave and many other derived effects. |

## Domain adapter review

All 14 supplied templates are registered, which closes the original “no adapters” defect structurally. This is not equivalent to full domain correctness.

| Templates | Status | Verified gap |
|---|---|---|
| 01–06 workforce | Partial | Employee commit is tested; allocation/holiday/override/leave effects are not. No idempotent/versioned adapter test establishes overlap, ledger, or leave policy invariants. |
| 07a/07b attendance | Partial | Raw/daily conflict test passes. Raw sessions are paired in adapter, but no overnight/session calculation or daily-vs-raw reconciliation test exists. |
| 08–09 delivery/Linear | Open | Deliverable adapter does not consume `assigned_employee_numbers`, dependencies, or multi-criterion input ([MigrationDomainAdapter.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationDomainAdapter.java:469)). No delivery/Linear template commit test exists. |
| 10–11 certification/confirmation | Open | Certification records the importing actor as `decided_by_subject`, not the supplied product owner ([MigrationDomainAdapter.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationDomainAdapter.java:619)); confirmation maps an email but does not demonstrate historical authority verification. |
| 12–13 invoice/approval history | Open | No evidence artifact/hash linkage is created for invoice documents; approval history records claimed authority in an audit event without independent verification. No tests cover either adapter. |

## Authoritative post-fix closure

**Closure date:** 28 July 2026
**Local decision:** **GO for locally controlled F06 behavior; NO-GO for
production historical cutover until external gates close.**

The independent Terra post-fix review confirmed the original approval,
delivery-child compensation and non-enumerability fixes, then raised four
additional P1 edges. Sol and the root agent closed them with Flyway V20,
transactional service changes and focused PostgreSQL evidence:

- all-or-nothing commit now aborts on a late canonical conflict; a dedicated
  non-transaction-wrapped integration test proves the job remains ready and no
  earlier domain/fact/provenance/outbox effect survives;
- `migration_jobs.partial_commit` is immutable after upload and commit must
  reaffirm the persisted value;
- append-only compensation requires the exact persisted `COMPENSATE` action
  for the active provenance job; an arbitrary session UUID is denied;
- job approvals and reconciliation sign-offs both require an explicit valid
  decision, while unauthorized existing/absent identifiers remain
  indistinguishable.

### Definitive evidence

| Command/lane | Result |
|---|---:|
| `mvn -B -f backend/pom.xml -Dit.test=MigrationWorkflowIT,MigrationDomainAdapterIT,MigrationAtomicCommitIT verify` | 14 unit + 15 focused integration passed |
| `mvn -B -f backend/pom.xml verify` | 14 unit + 158 integration = **172/172 passed** |
| `npm run typecheck && npm run lint && npm run test && npm run build` | typecheck/build pass; lint 0 errors and 6 inherited warnings; **90/90 Vitest passed** |
| `npm run e2e` | **74/74 passed**, including five F06 browser-contract cases |
| `npm run e2e:migration:system` | **6/6 passed** through real Vite/Spring/Flyway V1–V20/PostgreSQL |
| `npm run sdlc:check` and `git diff --check` | passed before local commit |

No accepted local P0/P1 remains. Production cutover is still blocked by the
approved scanner/private object-storage selection, controlled 100k-row capacity
evidence, source-owner mappings/sign-off, backup/restore checkpoint and masked
rehearsal. These are `ACTION_REQUIRED`, not implied by local fixtures.
