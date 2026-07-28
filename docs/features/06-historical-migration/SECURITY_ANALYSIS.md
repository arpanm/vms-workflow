# F06 Historical Migration — Security Analysis

## Post-remediation posture

Local release-blocking defects are closed in code: sign-off authority is
server-derived and ambiguity fails closed; staging cannot validate before a
passing scan verdict; upload, job, source/error export, approval, commit,
rollback and audit are scope checked; persisted values are not mutated for CSV
safety; error exports neutralize spreadsheet formula prefixes; cursors are
signed and actor/resource/scope bound; and adversarial SoD plus real-system
cross-scope cases are automated.

The phrase “production ready” remains conditional on `F06-EXT-002`: an approved
malware/object-storage provider, secrets, callback controls, retention/legal
hold configuration and controlled-environment evidence are external inputs.
The local deterministic scanner is a fail-closed adapter, not a claim of
production provider acceptance.

## Historical pre-remediation findings

The table below preserves the findings that drove the security remediation. Its
local P0/P1 entries are superseded by the post-remediation posture above;
production provider/storage/retention controls remain external release gates.

| Priority | Risk | Evidence | Required control/test |
|---|---|---|---|
| P0 | Approval/sign-off authority can be forged with a JSON role. This defeats segregation of duties for evidence-impacting commits. | [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:510), [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:879). | Server derives approval role from active scoped assignment and policy; test forged `GOVERNANCE`/`BUSINESS`, same actor, delegated actor, and conflicting identity. |
| P0 | No real malware quarantine. A source is immediately labelled `PASSED`, contrary to the file-security gate. | [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:154). | Scanner callback/worker, private storage, `PENDING/QUARANTINED` boundary, access audit and negative tests for failed scans. |
| P1 | Scope filtering is incomplete at client boundary and unproven at HTTP/database layer. Browser calls lack required engagement scope, while security tests only mock 403. | [api.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/migration/api.ts:29), [MigrationController.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/api/MigrationController.java:44). | Authenticate requests with actual JWTs and test organization/engagement/object scope for every API and source/error download. |
| P1 | Sanitizing data for CSV safety changes persisted values and does not establish safe rendering/storage policy. | [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:1681). | Store original validated values; encode at render/export sinks. Test spreadsheet injection, HTML/script text, filenames and safe problem payloads. |
| P1 | Sensitive source file bytes are stored directly in database and retention is hard-coded to seven years. | [V17__historical_migration.sql](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/resources/db/migration/V17__historical_migration.sql:70), [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:161). | Use protected object-storage adapter with classification/access logs/retention-policy configuration; encryption, legal hold, restore and deletion behavior must be tested. |
| P2 | Audit correlation is not request-correlated. A new random UUID is generated for each audit write despite `CorrelationIdFilter` import. | [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:1903). | Propagate trusted request correlation/causation identifiers to audit/outbox; test multi-step request traceability. |

Additional required controls: rate-limit upload/validation, enforce content inspection rather than client MIME alone, redact sensitive row values in findings, and ensure any signed/source download authorization is checked on each request.
