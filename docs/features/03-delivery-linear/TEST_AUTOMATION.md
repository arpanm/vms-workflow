# F03 Backend Test Automation

`DeliveryLinearIT` and `DeliveryApprovalConcurrencyIT` use Spring Boot, MockMvc, Flyway and Testcontainers
PostgreSQL 18. Its provider metadata is recorded test data; no test calls a live
Linear workspace or mail provider. Test-only fixtures are isolated in
`backend/src/test/resources/db/testdata/V1002__delivery_linear_test_fixtures.sql`.

Covered:

- a complete nested plan submits with a deterministic checksum, enforces
  eligible scoped approval and self-approval separation, reaches quorum once,
  freezes, creates immutable baseline/recipient/plan-time evidence and one
  commitment outbox record;
- a frozen version rejects evidence mutation, and revision-by-clone preserves
  prior-version and stable deliverable lineage;
- an incomplete plan returns explicit blockers and remains draft;
- an exact-raw-body signed webhook validates delivery/organization/connection
  and dual timestamps, persists and queues before success, deduplicates replay,
  and processes separately;
- worker processing preserves provider state, appends immutable event history,
  updates current state idempotently and proves `Done` changes only execution
  projection, never plan/month approval state;
- invalid, replay-window-expired and duplicate webhook paths create no
  unintended state;
- plan-time snapshots remain unchanged after current projection changes;
- wrong-tenant/object reads and unauthorized replay are non-disclosing;
- generated OpenAPI contains F03 routes while secret reference values and
  webhook secret configuration are absent.

Targeted command and result:

```text
mvn -B -f backend/pom.xml -Dit.test=DeliveryLinearIT,DeliveryApprovalConcurrencyIT verify
DeliveryLinearIT: 14 tests
DeliveryApprovalConcurrencyIT: 1 test
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Full regression command and result:

```text
mvn -B -f backend/pom.xml verify
DeliveryLinearIT: 14 tests
DeliveryApprovalConcurrencyIT: 1 test
WorkforceAttendanceIT: 20 tests
ApiTenantSecurityIT: 11 tests
JwtDecoderIT: 3 tests
Tests run: 49, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## Explicit remaining gates

- exhaustive `N_OF_M`, rejection, and stale/disabled vote matrix (`ANY_ONE`,
  concurrent `ALL`, duplicate and SOD cases are covered);
- no-deliverables exception, cross-project dependency exception and exhaustive
  cycle/allocation/date cases;
- production mail adapter attempts, retry/dead-letter/replay/callback behavior;
- live Linear OAuth/PKCE, GraphQL partial-error/rate-limit/pagination behavior,
  registration, revocation and reconnect;
- scheduled delta/nightly/month-end reconciliation, queue crash recovery,
  quarantine and bounded retry/dead-letter worker;
- exhaustive Swagger audience/access and database least-privilege-role tests;
- full-stack Playwright/product-owner acceptance and external tenant-authorized
  `T-MSG-005`/`T-LIN-013`.

These gates are not represented as passing. They remain local follow-up or
externally blocked exactly as identified in `TASKS.md` and `TEST_CASES.md`.
