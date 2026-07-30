# F06 Historical Migration — Independent Code Review

**Review scope:** V17, migration backend/API/security classes, migration frontend, and E2E fixture. Product code was not changed.

## Verdict

**Do not release F06.** The feature compiles, but the browser client and controller implement incompatible contracts, and commit writes generic migration facts rather than applying governed domain effects. Those defects prevent a real governed migration from completing.

## Confirmed findings

| Priority | Finding | Evidence | Required acceptance test |
|---|---|---|---|
| P0 | Frontend and server API contracts are incompatible end to end. | [MigrationController.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/api/MigrationController.java:44) requires `engagementId` for access/templates/jobs; [api.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/migration/api.ts:29) sends none. Upload sends `monthId` and source fields instead of required `engagementMonthId`/server metadata ([api.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/migration/api.ts:43), [MigrationDtos.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/api/MigrationDtos.java:15)). UI also calls `/approvals` while server exposes `/approval` ([api.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/migration/api.ts:74), [MigrationController.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/api/MigrationController.java:154)). | Browser-to-real-Spring contract test for every operation, asserting request and response schema; upload, validation, two approvals, partial commit, reprocess, rollback, error export, and retro request must succeed without API mocks. |
| P0 | Commit creates only `migration_canonical_facts`; it does not create/version the actual employee, allocation, leave, attendance, delivery, certification, confirmation, invoice, or approval domain records. | [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:626) inserts the generic table for every template. No template-specific domain adapter is invoked. | For each of the 14 templates, commit a valid fixture and verify the intended existing domain aggregate/version is visible to its normal API and carries migration provenance; prove no generic fact is accepted as a replacement for an authoritative domain record. |
| P0 | Approval role is client-selected, so migration separation of duties is not tied to the actor's real authority. Any principal with `migration.approve` can submit `GOVERNANCE` or `BUSINESS`. | [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:510) only checks commit authority for a lead; [MigrationDtos.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/api/MigrationDtos.java:42) accepts a requested role; V17 gives admins all permissions ([V17__historical_migration.sql](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/resources/db/migration/V17__historical_migration.sql:23)). | HTTP security test: a lead/admin cannot record governance/business approval or reconciliation sign-off merely by changing JSON; authorized lead and authorized governance actors must be distinct and scope-valid. |
| P1 | Retry validation deletes immutable rows/findings and therefore fails after a prior validation. | Validation deletes staging data ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:293)); V17 prevents deletes from both tables ([V17__historical_migration.sql](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/resources/db/migration/V17__historical_migration.sql:384)). | Test `validate → FAILED/COMPLETED_WITH_ERRORS → validate` and prove a new immutable validation attempt/version is retained, not deleted or blocked. |
| P1 | `REPROCESS_REJECTS` reparses the entire original source, not only rejected rows; it can recreate already committed rows and produce conflicts. | Reprocess reuses source blob ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:740)); validation always loops every source row ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:310)). | Partial commit fixture with one invalid row; reprocess must stage exactly that rejected row, preserve parent/provenance, and never duplicate/alter accepted facts. |
| P1 | Raw punch/daily authority is derived from the first ten characters of `occurred_at`, not the supplied IANA timezone or calculated attendance local date. | [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:1370). | Boundary test for an offset crossing local midnight and overnight sessions; the same employee-local day must reject conflicting raw/daily authority and never double count minutes. |
| P1 | Upload records every file as `PASSED` without a scanner/quarantine workflow. | [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:158) writes `PASSED`; no scanner adapter/job is called. | EICAR/failed-scan fixture remains quarantined, inaccessible, unparseable and excluded from reconciliation/package; only a scanner-success callback may make a job validateable. |
| P2 | Reconciliation is generated only after commit, while the UI and requirement require review/sign-off of an exact pre-commit impact reconciliation. | [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:676); UI says validation must produce it ([workspace.tsx](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/migration/workspace.tsx:416)). | Validation produces immutable pre-commit report/hash; approvals bind that hash; any row resolution invalidates it; commit requires the current approved report. |

## Strengths observed

- CSV parsing has bounded field/row handling and quoted-field coverage.
- Source hashes, row hashes, represented timestamp, provenance links, optimistic job versioning, and compensating rollback intent are present.
- The controller consistently requires `If-Match` for consequential job changes.

## 2026-07-30 independent-review resolution

The follow-up findings are closed in code and focused PostgreSQL tests:

- confirmation decisions resolve evidence by exact SHA-256 and scope; arbitrary
  filenames cannot authorize a decision;
- allocation approval actor/time values are conditional as one pair;
- missing invoice bytes/hash leave the artifact link null and never synthesize
  a byte hash;
- validation findings are deduplicated by stable identity; and
- requirement-18 local timestamps plus IANA timezone work through validation
  and commit.

Production mailbox/object-store verification remains an external deployment
gate and is not inferred from these local tests.
