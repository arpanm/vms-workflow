# F06 Historical Migration — Code Issues

## Post-codegen disposition

- P0 items 1–3 are remediated: the browser/server DTO contract is aligned and
  exercised by a real-system lane; all 14 physical templates invoke typed
  bounded-context adapters; approval authority is derived from exactly one
  active scoped assignment.
- P1 items 4–6, 8–9 and P2 items 10, 12 are remediated with append-only
  validation attempts, rejected-only child reprocessing, employee-zone
  attendance authority, domain compensation/consumption guards, sink-only CSV
  neutralization, signed actor/scope-bound cursors and Spring/PostgreSQL tests.
- P1 item 7 and the storage-provider portion of security review remain
  production integrations marked `ACTION_REQUIRED`. Local source scanning is
  fail-closed and has no production bypass.
- P2 item 11 is resolved by policy: the registry checksum identifies the
  downloadable canonical sample artifact; uploaded business data is validated
  against the exact versioned header/schema and has its own immutable SHA-256.
  Requiring user data bytes to equal a sample checksum would reject every valid
  import.

## P0

1. **Unusable real API contract.** See `CODE_REVIEW.md` P0 #1. Align the OpenAPI/controller DTOs and frontend generated/typesafe client before feature testing. Do not resolve this in fixtures.
2. **Generic-fact commit is not a historical migration.** See P0 #2. Introduce explicit, transactional template/domain adapters with canonical domain invariants, provenance, idempotency and version/supersede behavior.
3. **Client-chosen sign-off role breaks SoD.** See P0 #3. Derive allowed sign-off role from the server-side active assignment; reject self-approval, same-person dual approval, and same authority-chain approval according to policy.

## P1

4. **Immutable validation cannot be retried** — lines 293–294 conflict with V17 triggers. Model validation attempts as append-only or retain prior rows and create a new job/revision.
5. **Rejected-row reprocessing is semantically wrong** — it must carry a rejected-row selection/edited source and a parent link, rather than rediscover all source rows.
6. **Attendance authority is not timezone-safe** — derive employee local date using the imported timestamp plus validated IANA zone; use the attendance calculation service to pair raw events and reconcile daily summary rather than generic facts.
7. **No real scan/quarantine boundary** — source files must enter `PENDING`, obtain an external scanner verdict asynchronously, and remain unreadable to validation/download/package paths until `PASSED`.
8. **Rollback consumption detection is too narrow.** [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:784) checks only three month tables and cannot detect domain facts consumed by plan/certification/confirmation/other downstream aggregates. Domain adapters must report dependencies and force reopen/version correction once any derived snapshot/action exists.
9. **Formula sanitization mutates canonical values.** [MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:1681) prepends `'` before persisted payload, corrupting legitimate leading `-`, `+`, `@`, or `=` source text. Preserve raw typed business values; neutralize only downloadable spreadsheet cells and rendered untrusted output.

## P2

10. **Job list pagination is internally inconsistent.** Server returns `hasMore`/`nextCursor` but accepts only `limit` ([MigrationController.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/api/MigrationController.java:73)); the client sends a `cursor` it never supplies scope for ([api.ts](/Users/arpan1.mukherjee/code/personal/vms-workflow/src/features/migration/api.ts:32)). Implement an opaque cursor or remove it.
11. **Manifest checksum is exposed but never verified.** Registry embeds template sample hashes ([MigrationTemplateRegistry.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationTemplateRegistry.java:28)); upload validates only headers ([MigrationService.java](/Users/arpan1.mukherjee/code/personal/vms-workflow/backend/src/main/java/com/vms/workflow/application/MigrationService.java:129)). Define whether uploaded data templates must match a signed template artifact and enforce that decision consistently.
12. **No controller-level integration tests.** `MigrationCsvParserTest` tests only parser/registry; it cannot catch the database-trigger, authorization, lifecycle, or DTO defects.
