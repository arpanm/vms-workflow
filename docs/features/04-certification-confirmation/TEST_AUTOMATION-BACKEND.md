# F04 Backend Test Automation

**Execution date:** 2026-07-26
**Scope:** backend-only JUnit 5, Spring Boot/MockMvc, Testcontainers PostgreSQL 18, Flyway V1–V11 plus deterministic test fixtures V1000–V1003.
**Disposition:** tests compile; the F01–F03 regression suites remain green. F04 is intentionally red where the implemented product violates the controlling test contract.

## Automation added

- `CertificationWorkflowIT` — frozen F03 source binding, vendor completeness/locking, stale version, idempotency, mandatory evidence, tenant/party/SOD, clarification lineage, item/criterion decisions, explicit deterministic summary, and readiness versioning.
- `BusinessConfirmationIT` — exact request scope/due/eligibility, in-app and secure-token actions, stale/replay behavior, current project authority, deadline enforcement, quorum notification timing, and proof that receipt/auto-reply/silence cannot approve.
- `CertificationPersistenceIT` — empty-schema migration, commercial-data exclusion, canonical hashing, submission child scope, summary/request cross-scope SQL, request scope immutability, append-only evidence/audit/outbox, outbox/provider dedupe, closure integrity, and invalidation resolution.
- `CertificationOpenApiIT` — authenticated documentation audience, F04 route publication, secure-token write-only/redaction, required version/idempotency headers, and correlation contract.
- `F04RegressionIT` — frozen F03 baseline/commitment immutability, closed F02 snapshot immutability, and absence of F05 invoice/package/procurement creation.
- `V1003__certification_confirmation_test_fixtures.sql` — deterministic governance, dual-party SOD, and wrong-project identities. It contains no salary, payroll, rate, markup, token, or provider secret data.

The provider-neutral production surface still has no outbox worker/retry/reminder/expiry API, inbound/manual-review API, close/reopen decision API, invalidation-resolution API, or durable F05 publication implementation. Those missing executable surfaces remain product blockers under F04-BE-007, F04-BE-008, and F04-BE-011; live-provider cases remain external acceptance and were not simulated as passed.

## Commands and exact results

1. `mvn -DskipTests test-compile`
   - **PASS** — 11 test source files compiled.
2. `mvn -Dit.test='CertificationWorkflowIT,BusinessConfirmationIT,CertificationPersistenceIT,CertificationOpenApiIT,F04RegressionIT' failsafe:integration-test failsafe:verify`
   - **FAIL (expected product failures)** — 30 run: **14 passed, 15 failed assertions, 1 production error, 0 skipped**.
3. `mvn verify`
   - **FAIL (expected product failures)** — 79 run: **63 passed, 15 failed assertions, 1 production error, 0 skipped**.
   - All **49 pre-F04 F01–F03/JWT tests passed**. The 30 F04 results are identical to the targeted run.

Logs were retained locally as `backend/target/f04-targeted.log` and `backend/target/f04-full.log`; normal Maven `target/` cleanup may remove them.

## Passing F04 cases (14)

- `BusinessConfirmationIT.staleRequestVersionAndNonConfirmationWithoutCommentAreAtomic`
- `BusinessConfirmationIT.deliveryReceiptAutoReplyAndSilenceNeverConfirm`
- `CertificationOpenApiIT.secureTokenIsWriteOnlyAndRestrictedPersistenceFieldsAreNotResponseSchema`
- `CertificationOpenApiIT.documentationAudienceIsAuthenticatedAndF04OperationsArePublished`
- `CertificationPersistenceIT.emptyDatabaseMigrationsExposeF04SchemaWithoutCommercialData`
- `CertificationPersistenceIT.appendOnlyEvidenceOutboxAuditAndProviderFingerprintRejectMutationOrDuplicate`
- `CertificationPersistenceIT.canonicalHashUsesStableKeysUtcAndDefinedListOrdering`
- `CertificationPersistenceIT.submissionOutcomeCriterionAndCertificationScopeAreDatabaseEnforced`
- `CertificationWorkflowIT.incompleteDraftStaysEditableAndStaleVersionDoesNotCreateAnotherDraft`
- `CertificationWorkflowIT.criterionAndDecisionValidationNeverTreatLinearOrPercentageAsAcceptance`
- `CertificationWorkflowIT.clarificationIsAdditiveAndTerminalSummaryChecksumIsDeterministic`
- `F04RegressionIT.f04DoesNotCreateF05InvoicePackageOrProcurementFacts`
- `F04RegressionIT.f04ReadinessReferencesButNeverMutatesClosedF02Snapshot`
- `F04RegressionIT.f04VerticalNeverMutatesFrozenF03PlanBaselineOrCommitment`

## Failing F04 cases and issue mapping (16)

| Automated case | Observed product behavior | Mapping |
| --- | --- | --- |
| `CertificationWorkflowIT.frozenBaselineSubmissionCertificationSummaryAndReadinessRemainExplicit` | Completed coherent vertical reaches readiness, but readiness returns generic `409` while persisting its five-pillar result. | F04-BE-010, F04-BE-012; T-READY-001 |
| `CertificationWorkflowIT.readinessIsIdempotentForSameManifestAndVersionsChangedInputs` | First readiness evaluation returns `409`, so idempotent/source-change evidence cannot be produced. | F04-BE-010; T-READY-002 |
| `CertificationWorkflowIT.identicalDraftRetryReturnsPriorResultWithoutDuplicateFacts` | Identical retry is version-checked before idempotency lookup and returns `409 MONTH_VERSION_CONFLICT`. | F04-BE-012; T-DEL-010 |
| `CertificationWorkflowIT.requiredFrozenEvidenceCannotBeOmitted` | Mandatory frozen evidence expectation submits with HTTP `200` and no evidence/authorized exception. | F04-BE-009; T-DEL-007 |
| `CertificationWorkflowIT.tenantPartyProjectAndSeparationOfDutiesAreServerEnforced` | Dual vendor/client author self-certifies successfully (`200` instead of safe denial). | F04-BE-003; T-CERT-001, T-F04-SEC-001 |
| `BusinessConfirmationIT.readinessCreatesExactScopeRequestWithCapturedDueEligibilityAndNoSecret` | Request creation rolls back with generic `409` because readiness cannot persist. | F04-BE-010; T-CONF-001/002 |
| `BusinessConfirmationIT.inAppConfirmationIsExplicitAuditedIdempotentAndTransportNeutral` | Explicit confirmation reaches terminal processing but rolls back with `409` during downstream readiness persistence. | F04-BE-010/F04-BE-011; T-CONF-009 |
| `BusinessConfirmationIT.actionRequiresCurrentPermissionOnTheCapturedEligibleProject` | Wrong-project actor passes authorization and reaches mutation; request then fails generically at the invalidation insert rather than returning safe `404`. | F04-BE-004 and F04-BE-002; T-CONF-006/008 |
| `BusinessConfirmationIT.pastDueRequestExpiresAndCannotAcceptInAppAction` | Past-due action is accepted past the deadline and reaches mutation; no typed `CONFIRMATION_EXPIRED` is returned. | F04-BE-005 and F04-BE-002; T-CONF-003/011 |
| `BusinessConfirmationIT.secureTokenIsSingleUseRequestBoundAndReplayOnlyReturnsOriginalOutcome` | A valid request-bound token reaches correction processing, but the transaction rolls back because the service omits required invalidation `status`. | F04-BE-002; T-CONF-004/005/007 |
| `BusinessConfirmationIT.multiPartyOutcomeNotificationOccursOnlyAfterQuorum` | First valid `ALL` contribution throws production NPE at `Set.of(...).contains(null)` before notification/quorum completion. | F04-BE-006; T-CONF-008, T-MSG-003 |
| `CertificationPersistenceIT.summaryAndConfirmationRejectCrossMonthAndCrossTenantSources` | PostgreSQL accepts a Northstar summary bound to Reliance submission/round/plan/baseline/policy facts. | F04-BE-001; T-F04-DB-001 |
| `CertificationPersistenceIT.issuedRequestScopeDueAndHashBoundFieldsAreImmutable` | PostgreSQL accepts an issued request transition that changes `due_at` while the canonical scope manifest/checksum remains unchanged. | F04-BE-001; T-CONF-002/003 |
| `CertificationPersistenceIT.closureAndReopenEvidenceCannotBeCreatedIncompleteOrMutated` | PostgreSQL accepts a current closure referencing an unconfirmed request. | F04-BE-002/F04-BE-008; T-CLOSE-001/003 |
| `CertificationPersistenceIT.invalidationResolutionIsAppendOnlyButCanBecomeEffectivelyCleared` | Every `ACTIVE → CLEARED` update is rejected and no effective resolution lineage exists. | F04-BE-002; T-READY-002, T-CLOSE-002 |
| `CertificationOpenApiIT.everyConsequentialF04OperationDocumentsEtagIdempotencyAndCorrelation` | Version/idempotency headers exist, but consequential operations omit the required `X-Correlation-Id` contract. | F04-BE-015; T-F04-API-001 |

## Test-defect disposition

No known test/fixture defect remains in the recorded run. During development, three harness mistakes were corrected before the final executions: the submission read model uses `UNDER_REVIEW`, the domain-event identifier is `subject_id`, and `certification_invalidations.status` must be explicit in direct fixtures. The remaining 15 failures and one NPE reproduce product behavior and are deliberately not weakened.

## Release disposition

- **F01–F03 regression:** pass (49/49).
- **F04 local G1–G3:** blocked by the failures above and by the unimplemented local worker/inbound/closure/F05 surfaces.
- **G4 provider acceptance:** not attempted; remains external and cannot be passed by fixture, receipt, timeout, or silence.
