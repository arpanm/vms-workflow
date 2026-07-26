# F04 Backend Test Review

**Review date:** 2026-07-26
**Scope reviewed:** `TEST_CASES.md`, `CODE_ISSUES-BACKEND.md`, `TEST_AUTOMATION-BACKEND.md`, all five F04 integration tests, `F04TestSupport`, and V1003 fixtures. No product or test files were changed.

## Outcome

The suite is a useful first executable specification, but it is **not release-grade test automation**. The targeted F04 run reproduced **30 tests: 14 pass, 15 assertion failures, 1 error**. A fresh full `mvn verify` reproduced **79 tests: 63 pass, 15 assertion failures, 1 error**; all 49 pre-F04 F01--F03/JWT tests pass. It migrated a new empty Testcontainers database through V1--V11 and V1000--V1003.

Of the 16 red outcomes, **9 are trustworthy product findings as executed**, **5 are product findings but only partially demonstrated by the failing test**, **1 is a test defect that cannot be classified as a product failure**, and **1 is a valid product finding with an incomplete all-operations assertion**. In particular, a generic `DataIntegrityViolationException` masks several later assertions, so a failing vertical test must not be read as proof of every property in its name.

## Red-outcome classification

| # | Automated case | Classification | Review conclusion |
| --- | --- | --- | --- |
| 1 | `frozenBaselineSubmissionCertificationSummaryAndReadinessRemainExplicit` | Trustworthy product failure | Readiness should return a blocked view, not generic 409. The failure is an actual persistence failure; frozen-source assertions after the call are not reached. |
| 2 | `readinessIsIdempotentForSameManifestAndVersionsChangedInputs` | Partial | The first readiness evaluation genuinely fails with 409. It does **not** demonstrate idempotency or changed-input behavior because neither subsequent evaluation runs. |
| 3 | `identicalDraftRetryReturnsPriorResultWithoutDuplicateFacts` | Trustworthy product failure | The service checks version before idempotency (workflow service lines 104--115), producing `MONTH_VERSION_CONFLICT`. The post-retry count assertions are not reached. |
| 4 | `requiredFrozenEvidenceCannotBeOmitted` | Trustworthy product failure | A frozen deliverable declaring mandatory evidence submits with no evidence. The blocker implementation has no evidence-policy query (workflow service lines 1107--1175). |
| 5 | `tenantPartyProjectAndSeparationOfDutiesAreServerEnforced` | Trustworthy product failure | The dual vendor/client author receives 200 and a certification is created. The policy's SOD setting is not consulted by item authorization. |
| 6 | `readinessCreatesExactScopeRequestWithCapturedDueEligibilityAndNoSecret` | Trustworthy product failure | Request creation reaches readiness and gets a generic 409; none of the scope, eligibility, or token assertions execute. It proves the local creation vertical is broken, not every named property. |
| 7 | `inAppConfirmationIsExplicitAuditedIdempotentAndTransportNeutral` | Partial | Confirmation reaches downstream readiness and rolls back with 409. It does not prove audit, idempotency, or notification behavior; source inspection confirms the readiness dependency is real. |
| 8 | `actionRequiresCurrentPermissionOnTheCapturedEligibleProject` | Trustworthy product finding, diagnostic weak | The response is 409 rather than safe 404 because authorization admits the wrong project. Source inspection confirms it only requires *any* project permission (authorization service lines 140--164); the test should isolate that boundary. |
| 9 | `pastDueRequestExpiresAndCannotAcceptInAppAction` | Partial; harness defect also open | Product code has no due-date comparison before the action (confirmation service lines 257--267), so expiry is a real defect. The red response itself is an unrelated generic DB conflict and the test stops before checking state; it is not direct proof that a past-due action committed. |
| 10 | `secureTokenIsSingleUseRequestBoundAndReplayOnlyReturnsOriginalOutcome` | Not trustworthy for its named token claim | The first correction action fails at the missing invalidation `status`, before consumption/replay assertions. It proves that correction is broken, not single-use/request binding/replay. |
| 11 | `multiPartyOutcomeNotificationOccursOnlyAfterQuorum` | Trustworthy product error, partial coverage | A valid first `ALL` contribution throws an NPE at `BusinessConfirmationService.act` line 350. The no-early-notification/final-notification assertions do not execute. |
| 12 | `summaryAndConfirmationRejectCrossMonthAndCrossTenantSources` | Trustworthy for summary only; partial | PostgreSQL accepts the cross-tenant summary. The following confirmation SQL is not executed after the first assertion failure, although migration review independently confirms no corresponding confirmation scope trigger. |
| 13 | `issuedRequestScopeDueAndHashBoundFieldsAreImmutable` | Trustworthy product failure | PostgreSQL accepts a transition changing `due_at`; the request guard omits it (V11 lines 954--968). The delete check is not reached. |
| 14 | `closureAndReopenEvidenceCannotBeCreatedIncompleteOrMutated` | Trustworthy for incomplete closure only; partial | PostgreSQL accepts a current closure for an unconfirmed request. Later update/delete checks do not execute. |
| 15 | `invalidationResolutionIsAppendOnlyButCanBecomeEffectivelyCleared` | **Test defect, not a product failure as written** | The test demands `UPDATE ACTIVE -> CLEARED`, contradicting the append-only security requirement. The product still lacks an append-only resolution model, but rejecting the update is correct; see F04-TEST-001. |
| 16 | `everyConsequentialF04OperationDocumentsEtagIdempotencyAndCorrelation` | Trustworthy for first operation; partial | The first operation lacks correlation documentation. The loop aborts, so it does not establish the contract status of the remaining six operations. |

## Test design assessment

- **Assertions:** Strong where they pair HTTP status with persisted facts (SOD, direct SQL scope, immutable audit/outbox). Weak where an initial expected-success assertion prevents all intended checks. Split independent requirements into separately named tests or collect results with `assertAll`.
- **Security and scope:** F04 includes a meaningful disabled/cross-tenant/SOD/wrong-project start, but it does not exercise unauthenticated F04 routes, malicious actor/organization/role claims, service principals, revoked delegates, client-provided recipient authority, stolen-token identity, wrong-request token, token expiry, CSRF, rate-limit non-disclosure, raw evidence authorization, or direct database least privilege.
- **Transactions and direct SQL:** Savepoint rollback in `CertificationPersistenceIT.assertSqlRejected` is hygienic for an individual negative statement. However, most F04 classes are test-transactional; MockMvc service work shares the ambient transaction. The suite therefore does not prove commit/rollback isolation, outbox atomicity after commit, or concurrent lock behavior across independent connections.
- **Concurrency/idempotency realism:** All F04 retry/quorum paths are sequential. There is no barrier, second transaction/connection, concurrent scheduler callback, duplicate worker claim, or competing action test. Existing F03 concurrency coverage is not F04 coverage.
- **Fixtures/determinism:** V1003 has deterministic fixed principals/scopes and contains no commercial fields, plaintext confirmation tokens, or provider secrets. The workflow helpers introduce `UUID.randomUUID()` and `OffsetDateTime.now()` (for example `F04TestSupport.java` lines 229--230 and `BusinessConfirmationIT.java` lines 65, 170--171), while production services use their own system clock. Boundary/expiry and canonical-manifest tests are therefore not deterministic.
- **Regression inclusion:** The three F04 regression tests are included and pass, but they stop at certification summary. They do not execute confirmation, terminal action, readiness, or F05 publication, so they cannot substantiate the claimed F04/F05 non-creation regression.

## Missing critical automation

The following remain required before G1--G3 can rely on automation:

1. Real concurrent, committed transaction tests for same-month submission, same action/token, `ALL`/`N_OF_M` quorum, outbox/event dedupe, readiness run dedupe, and duplicate job/provider callback claims.
2. A fake/recorded adapter worker suite covering secure-link handoff without persistence, retry/backoff/dead-letter/replay, reminder/expiry idempotency, and no-silence/no-receipt approval.
3. Full token and authorization matrix: unauthenticated, disabled/revoked, wrong org/project, service identity, forwarded/stolen/wrong-version/expired token, client claim tampering, and protected raw evidence/inbound endpoints.
4. Artifact/evidence tests for required-policy enforcement, scanner states, cross-scope artifact IDs, unsafe MIME/filename/formula/XSS/URL data, signed URL expiry, and exception authority.
5. Inbound/manual-evidence, close/reopen/invalidation resolution, retention/redaction, F05 durable-contract, and restricted database role/grant tests. Missing APIs may make these intentionally red, but they must exist as explicit tests rather than be inferred from absent production classes.

See `TEST_ISSUES-BACKEND.md` for actionable test defects and coverage gaps.
