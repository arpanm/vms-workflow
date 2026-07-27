# F05 Backend Test Automation

**Preparation date:** 2026-07-27  
**Scope:** backend JUnit 5, Spring Boot/MockMvc, Testcontainers PostgreSQL 18,
Flyway V1–V14 plus deterministic fixtures V1000–V1004.  
**Execution status:** the newly added follow-up lane has not been executed in
this work session. Maven was deliberately not started while the coordinating
backend validation owned the shared `backend/target` directory. The last
independently recorded focused results before this follow-up were 2/2 passing
canonical JSON unit tests, 4/4 passing OpenAPI/security integration tests, and
a successful focused `verify`.

## Automation added

- `FinanceCanonicalJsonTest` proves stable key/time normalization and byte
  hashes.
- `FinanceLocalAdaptersTest` proves EICAR/executable quarantine, unavailable
  scanner fail-closed behavior, JSON/CSV/XLSX/PDF signatures, and spreadsheet
  formula escaping.
- `FinanceDatabaseControlsIT` proves exact private-byte round trip and
  persisted SHA-256, immutable artifact metadata/blob rows, and rejection of a
  cross-month invoice-artifact child reference.
- `FinanceSecurityIT` proves safe unauthenticated/wrong-role/cross-tenant
  denial, server-derived persona capabilities, and a versioned,
  permission-filtered report/export catalog.
- `FinanceOpenApiIT` proves the authenticated F05 API surface and consequential
  header contract.
- `FinanceWorkflowIT` exercises a real completed F04 certification and
  confirmed handoff through deterministic package and invoice submission. It
  additionally covers:
  - package idempotency, manifest/item persistence, immutable package parent,
    time-bound share create/list/use/revoke, expired share denial, and
    cross-tenant share denial;
  - draft payment denial, exact Procurement approval lineage, and a valid AP
    `SUBMITTED_TO_AP` fact;
  - assigned query owner response and Procurement closure without rewriting
    response history;
  - EICAR quarantine with exact persisted bytes/hash;
  - distinct second-approver enforcement and creation of a new exception
    readiness run;
  - incompatible newer F04 handoff rejection with
    `F04_HANDOFF_INCOMPATIBLE`;
  - approved F04 reopen propagation to invalidated package/readiness/invoice,
    immutable invalidation effect, domain event, and pending outbox row.
- `FinanceExportWorkerIT` requests JSON/CSV/XLSX/PDF exports, runs the local
  worker, verifies private artifact bytes and persisted hashes, and downloads
  each authorized result.
- `FinanceExportRetryIT` proves bounded retry to attempt five/dead letter and
  explicit replay when the scanner is unavailable.
- `V1004__finance_test_fixtures.sql` adds engagement-scoped Procurement,
  independent second-approver, and Finance AP identities without commercial or
  provider-secret data.

## Case traceability

| Contract | Automated evidence |
| --- | --- |
| F04 handoff compatibility/invalidation | `FinanceWorkflowIT.incompatibleNewerF04HandoffIsDeniedBeforePackageGeneration`, `approvedReopenInvalidatesPackageReadinessAndInvoiceWithOutboxFact` |
| Canonical package and immutable lineage | `FinanceCanonicalJsonTest`, `verifiedHandoffDrivesDeterministicPackageAndInvoiceSubmission`, `FinanceDatabaseControlsIT` |
| Private bytes/hash and quarantine | `FinanceLocalAdaptersTest`, `FinanceDatabaseControlsIT`, `quarantinePersistsExactBytesAndExceptionCreatesNewReadinessLineage` |
| Package share expiry/revoke/scope | `packageSharesGrantThenRevokeAccessAndExpiredGrantsStayInactive` |
| Procurement query/exception/payment | `assignedOwnerResponseAndProcurementClosureAreAppendOnly`, `quarantinePersistsExactBytesAndExceptionCreatesNewReadinessLineage`, `approvedExactLineageAllowsPaymentWhileDraftPaymentIsDenied` |
| Dynamic permissions/report contract | `FinanceSecurityIT` |
| Export formats/formula safety/private download | `FinanceLocalAdaptersTest`, `FinanceExportWorkerIT` |
| Export retry/dead letter/replay | `FinanceExportRetryIT` |

## Commands to run after shared-target coordination clears

From `backend/`:

```bash
mvn -DskipTests test-compile
mvn -Dtest='FinanceCanonicalJsonTest,FinanceLocalAdaptersTest' test
mvn -Dit.test='FinanceOpenApiIT,FinanceSecurityIT,FinanceDatabaseControlsIT,FinanceWorkflowIT,FinanceExportWorkerIT,FinanceExportRetryIT' failsafe:integration-test failsafe:verify
mvn verify
```

Do not record these commands as passed until their fresh console/Surefire and
Failsafe results are retained. The full release lane still also requires the
frontend, accessibility, performance, disaster-recovery, least-privilege
database-role, and explicitly external provider acceptance cases from
`TEST_CASES.md`.
