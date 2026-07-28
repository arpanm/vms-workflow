# F06 Historical Migration — Static and Architecture Analysis

## Post-remediation analysis

The active implementation replaces the generic-only path with mandatory typed
domain adapters and provenance. Duplicate classification occurs before any
adapter call. Flyway V19 records ordered domain effects and append-only
compensations; rollback deletes/restores unconsumed effects in reverse order
and cancels imported invoices while retaining evidence. Validation and
reprocessing are append-only, reconciliation is generated before approval,
and signed cursors bind actor, scope and snapshot. The final regression command
and counts are appended to the status ledger after execution.

The approved production malware scanner/object-storage deployment remains an
external boundary. The repository implementation deliberately fails closed
when that boundary is disabled and must not be described as provider
acceptance.

## Historical pre-remediation checks

- `mvn -q -f backend/pom.xml test` — passed.
- `npm run test -- --run src/features/migration/presentation.test.ts` — passed (2 tests).
- `npm run build` — passed.

Passing these checks does not establish F06 correctness because they do not run V17 with PostgreSQL or use the real server from the browser.

## Historical findings

1. **State machine conflict:** V17 makes `migration_rows` and findings non-deletable, while service retry validation deletes them. This is a deterministic runtime database failure, not a hypothetical race.
2. **Contract drift is hidden by structural typing and mocks:** TypeScript compiles because `apiClient` is generic and mock bodies satisfy frontend types; Spring compiles independently. No shared OpenAPI/schema validation bridges them.
3. **Generic persistence bypasses bounded contexts:** The service validates select references but stores JSON in `migration_canonical_facts`. It neither invokes nor proves constraints/services for workforce, delivery, confirmation, finance, or approval state machines.
4. **Reconciliation is post-effect:** The report hash contains committed fact hashes and is created after canonical mutation. It therefore cannot be the exact impact artifact approved before commit.
5. **Authority model is incomplete:** `migration_attendance_authorities` only arbitrates import template selection. It does not create attendance sessions/days, calculate in the employee timezone, or reconcile against calendar/leave authority.
6. **Operational gap:** the schema has leases, retries, checkpoints and dead-letter columns but the service runs synchronous request-thread parsing/validation and does not implement worker acquisition, recovery or scanner integration.

## Historical recommended design correction

Use a versioned import-job aggregate with append-only validation attempts. Each template adapter should produce a typed proposed-domain change, perform domain validation/reconciliation, and commit through its owning service in one transaction with provenance and an outbox event. Generate a deterministic pre-commit reconciliation from proposed changes; bind independently derived approvals to that report hash. Use an async scanner/worker pipeline for source files and retain source blobs in controlled object storage rather than request-thread canonical processing.
