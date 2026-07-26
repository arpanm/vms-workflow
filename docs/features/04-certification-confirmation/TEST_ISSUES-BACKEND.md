# F04 Backend Test Issues

**Disposition:** all findings are **Open**. These are test-automation defects and coverage gaps; they do not replace the product findings in `CODE_ISSUES-BACKEND.md`.

## P1

### F04-TEST-001 — Invalidation test requires a forbidden in-place update

- **Evidence:** [CertificationPersistenceIT.java](../../../backend/src/test/java/com/vms/workflow/integration/CertificationPersistenceIT.java) lines 264--287 updates `certification_invalidations.status` from `ACTIVE` to `CLEARED`. V11 makes those rows append-only at lines 1192--1194; T-F04-SEC-004 requires compensating corrections to append rather than edit history.
- **Impact:** The red outcome is falsely recorded as a product failure. It can encourage weakening an audit invariant and does not prove the needed effective-resolution design.
- **Recommendation:** Assert that direct update/delete is rejected. Add a separate append-only resolution/supersession fact and test that readiness derives the effective cleared state from lineage.
- **Disposition:** Open.

### F04-TEST-002 — Expiry and secure-token failures are misclassified by downstream correction rollback

- **Evidence:** [BusinessConfirmationIT.java](../../../backend/src/test/java/com/vms/workflow/integration/BusinessConfirmationIT.java) lines 197--219 expects an expiry code, and lines 223--256 expects secure-token consumption/replay, but both use `REQUEST_CORRECTION`. That route reaches the invalidation insert and returns an untyped 409 before their state assertions. The actual product has no due-time guard at [BusinessConfirmationService.java](../../../backend/src/main/java/com/vms/workflow/application/BusinessConfirmationService.java) lines 257--267 and its invalidation insert omits the non-null `status` at lines 350--357.
- **Impact:** A generic 409 is reported as proof of expiry acceptance and token properties even though neither action persists nor replay is exercised.
- **Recommendation:** Isolate authorization/token validation from correction invalidation; use a test-only valid append-only resolution fixture or test service boundary. Assert no action/token consumption after a denied token and separately verify the actual expiry transition/state.
- **Disposition:** Open.

### F04-TEST-003 — No F04 committed-transaction or real-concurrency coverage

- **Evidence:** `CertificationWorkflowIT`, `BusinessConfirmationIT`, `CertificationPersistenceIT`, and `F04RegressionIT` are annotated `@Transactional` (for example [BusinessConfirmationIT.java](../../../backend/src/test/java/com/vms/workflow/integration/BusinessConfirmationIT.java) line 43). Retries and quorum contributions are serial MockMvc calls (lines 99--125 and 275--297); `F04TestSupport.directConfirmation` uses the caller's JDBC transaction (lines 229--297).
- **Impact:** Rollback can hide commit-boundary/outbox errors. No test proves F04 lock handling, exactly-once effects, isolation, or race behavior required by T-DEL-010, T-CERT-009, T-CONF-007/008, T-JOB-002, and T-READY-002.
- **Recommendation:** Add non-test-transactional integration tests using two independent transaction templates/connections plus barriers. Assert final committed rows, audit/domain/outbox count, and loser conflict/current state.
- **Disposition:** Open.

### F04-TEST-004 — The F04/F05 regression test never reaches confirmation or handoff

- **Evidence:** [F04RegressionIT.java](../../../backend/src/test/java/com/vms/workflow/integration/F04RegressionIT.java) lines 90--104 calls only `completedCertification`, which ends after summary generation; it does not create or act on a confirmation request. The test then checks table absence, not side effects from terminal confirmation.
- **Impact:** It can pass while a confirmation/readiness/F05 change creates an invoice, package, procurement fact, or mutable upstream record.
- **Recommendation:** Once local confirmation is executable, run an explicit confirmed/rejected/corrected flow and assert the committed F01--F03 facts remain unchanged and only the specified durable F05 contract/outbox is created. Retain direct SQL immutable checks separately.
- **Disposition:** Open.

### F04-TEST-005 — Critical F04 security and provider-neutral local cases are absent

- **Evidence:** The F04 tests cover only a small subset of `TEST_CASES.md`: JWTs contain subject/audience only in [F04TestSupport.java](../../../backend/src/test/java/com/vms/workflow/integration/F04TestSupport.java) lines 28--30; no test exercises claim tampering, token theft/wrong request/expiry, service identity, CSRF/rate limit, raw evidence/inbound access, fake worker retry/reminder/expiry, inbound/manual review, close/reopen, or database grants.
- **Impact:** G1--G3 security and lifecycle requirements have no executable proof and missing surfaces can be mistaken for external-provider deferrals.
- **Recommendation:** Add parameterized authorization/token matrices, fake adapter worker tests, direct database least-privilege tests, and explicit red tests for every unavailable local lifecycle API until implemented.
- **Disposition:** Open.

## P2

### F04-TEST-006 — Multi-assertion persistence tests stop after the first accepted SQL statement

- **Evidence:** [CertificationPersistenceIT.java](../../../backend/src/test/java/com/vms/workflow/integration/CertificationPersistenceIT.java) lines 119--151 puts summary and confirmation scope checks in one method; lines 158--169 combine request update/delete; and lines 239--262 combine incomplete closure plus closure mutation/delete. An initial `assertSqlRejected` failure stops the remaining assertions.
- **Impact:** The automation report overstates its evidence: only the first SQL failure in each method was executed.
- **Recommendation:** One invariant per test, or `assertAll` with a fresh savepoint per statement. Report each outcome separately.
- **Disposition:** Open.

### F04-TEST-007 — OpenAPI correlation assertion is a substring search and aborts at the first route

- **Evidence:** [CertificationOpenApiIT.java](../../../backend/src/test/java/com/vms/workflow/integration/CertificationOpenApiIT.java) lines 69--91 accepts any occurrence of `X-Correlation-Id` in serialized operation JSON and exits the loop on the first failure.
- **Impact:** A description/example can create a false pass, while the report cannot say whether the other six operations are documented. It also never verifies a runtime response header/error correlation value.
- **Recommendation:** Inspect an explicit header parameter or response-header schema for every operation using `assertAll`; exercise success and typed-error responses and assert a propagated correlation ID.
- **Disposition:** Open.

### F04-TEST-008 — Time and identifiers are nondeterministic

- **Evidence:** [F04TestSupport.java](../../../backend/src/test/java/com/vms/workflow/integration/F04TestSupport.java) lines 229--230 and 252 generate random UUIDs; [BusinessConfirmationIT.java](../../../backend/src/test/java/com/vms/workflow/integration/BusinessConfirmationIT.java) lines 65, 170--171, and 267--268 read the system clock. Production services separately instantiate `Clock.systemUTC()`.
- **Impact:** Deadline boundaries and hashes cannot be reproduced exactly; the suite cannot prove policy-captured TTL/due behavior and may become timing-sensitive.
- **Recommendation:** Inject a fixed/mutable `Clock`, use stable UUID fixtures/factories, and test just-before/at/after expiry and policy changes deterministically.
- **Disposition:** Open.

### F04-TEST-009 — Positive persistence and regression tests overclaim their coverage

- **Evidence:** `submissionOutcomeCriterionAndCertificationScopeAreDatabaseEnforced` only attempts a `delivery_submissions` insert ([CertificationPersistenceIT.java](../../../backend/src/test/java/com/vms/workflow/integration/CertificationPersistenceIT.java) lines 86--105). `deliveryReceiptAutoReplyAndSilenceNeverConfirm` inserts quarantined rows directly rather than exercising an inbound/transport path ([BusinessConfirmationIT.java](../../../backend/src/test/java/com/vms/workflow/integration/BusinessConfirmationIT.java) lines 300--337).
- **Impact:** Passing names suggest outcome/criterion/certification scope and receipt/inbound safety are proven when those boundaries are not exercised.
- **Recommendation:** Rename narrowly or add the named direct-SQL/API cases, including an independently committed inbound worker path.
- **Disposition:** Open.

---

## Post-fix independent review addendum — 2026-07-26

The final 107-test backend run is retained as useful regression evidence. The
following test gap remains a **P1 local release blocker** and supersedes the
claim in `FIXES-BACKEND.md` that all local SOD/lifecycle/F05 cases are covered.

### F04-TEST-010 — Critical post-reopen, cross-affiliation SOD, and reviewed-evidence transitions are untested

- **Evidence:** `CertificationWorkflowIT.tenantPartyProjectAndSeparationOfDutiesAreServerEnforced` only rejects `user-sod` from certifying a submission that `user-sod` authored; it does not exercise a second vendor author or a confirmation action. `CertificationGovernanceIT` approves a reopen and manually resolves its invalidation, but has no rejected-reopen assertion. `CertificationOperationsWorkerIT` retries an F05 job, but never invalidates its handoff before claim/publish. `CertificationReviewIT` records/reviews inbound/manual evidence without a resulting `business_confirmation_action` because no such path exists.
- **Impact:** The passing suite does not detect the local P1 paths in `F04-BE-001`, `F04-BE-002`, `F04-BE-003`, `F04-BE-008`, and `F04-BE-011`.
- **Required coverage:** Add direct-SQL and API scope rejection cases; vendor-affiliation SOD matrices for item and confirmation actions; rejected-reopen automatic resolution and exact-correction-only clearing; pre-claim and concurrent F05 invalidation races; and reviewed inbound/manual source-to-action/quorum/conflict cases. Use independently committed worker/reopen transactions for the race.
- **Disposition:** Open — P1 local release blocker.
