export const canonicalProvenanceInputs = [
  ".github/workflows/f07-release-evidence.yml",
  "backend/compose.yaml",
  "backend/pom.xml",
  "backend/src/main/resources/db/migration",
  "backend/target/workflow-backend-0.1.0-SNAPSHOT.jar",
  "docs/features/07-hardening-go-live",
  "dist",
  "package-lock.json",
  "package.json",
  "scripts/f07",
];
export const protectedMigrationBaseCommit =
  "cc5049e8e9534d7dc0f5a631a12f63d0bd6dbf64";

const supplyCommand =
  /^node scripts\/f07\/supply-chain\.mjs --run --artifact dist,backend\/target\/workflow-backend-0\.1\.0-SNAPSHOT\.jar --report-dir \.f07-evidence\/[0-9]+-[0-9]+\/supply-chain$/;
const liveMigrationCommand =
  /^node scripts\/f07\/migration-live-rehearsal\.mjs --execute --base-ref [0-9a-f]{40} --release-commit [0-9a-f]{40}$/;
const operationalArtifactCommand =
  /^node scripts\/f07\/operational-report\.mjs --kind (?:load|soak-24h|dr|rollout) --input \.f07-evidence\/inputs\/[A-Za-z0-9._-]+\.json$/;
const releaseArtifactCommand =
  /^node scripts\/f07\/release-artifact-manifest\.mjs --expected-commit [0-9a-f]{40} --supply-report-dir \.f07-evidence\/[A-Za-z0-9._/-]+\/supply-chain --output \.f07-evidence\/[A-Za-z0-9._/-]+\/release-artifact-manifest\.json$/;
const postDeployRegressionCommand =
  /^node scripts\/f07\/post-deploy-regression\.mjs --evidence-dir [A-Za-z0-9_./-]+$/;

const laneCommands = {
  "F07-CI-BUILD": { command: "npm run build", evidenceParser: "none" },
  "F07-CI-DB-BOOTSTRAP": {
    command: "mvn -B -f backend/pom.xml -Dit.test=F07MigrationBootstrapIT verify",
    evidenceParser: "junit",
  },
  "F07-CI-DB-BOOTSTRAP-SQL": {
    command: "node scripts/f07/bootstrap-rehearsal.mjs --execute",
    evidenceParser: "structured",
    structuredKind: "bootstrap-rehearsal-v1",
  },
  "F07-CI-DOCUMENTATION": {
    command: "node scripts/f07/ops-verify.mjs",
    evidenceParser: "structured",
    structuredKind: "documentation-verification-v1",
  },
  "F07-CI-DR-REHEARSAL": {
    actionRequiredWhenAbsent: true,
    commandPattern: operationalArtifactCommand,
    evidenceParser: "structured",
    structuredKind: "dr-rehearsal-v1",
  },
  "F07-CI-RELEASE-ARTIFACTS": {
    actionRequiredWhenAbsent: true,
    commandPattern: releaseArtifactCommand,
    evidenceParser: "structured",
    structuredKind: "release-artifact-manifest-v1",
  },
  "F07-CI-E2E-ACCESSIBILITY": {
    command:
      "npx playwright test e2e/f07-accessibility.spec.ts --reporter=json",
    evidenceParser: "playwright",
  },
  "F07-CI-E2E-CERTIFICATION": {
    command: "npx playwright test e2e/certification.spec.ts --reporter=json",
    evidenceParser: "playwright",
  },
  "F07-CI-E2E-DELIVERY": {
    command: "npx playwright test e2e/delivery.spec.ts --reporter=json",
    evidenceParser: "playwright",
  },
  "F07-CI-E2E-FINANCE": {
    command: "npx playwright test e2e/finance.spec.ts --reporter=json",
    evidenceParser: "playwright",
  },
  "F07-CI-E2E-MIGRATION": {
    command: "npx playwright test e2e/migration.spec.ts --reporter=json",
    evidenceParser: "playwright",
  },
  "F07-CI-E2E-WORKFORCE": {
    command: "npx playwright test e2e/workforce.spec.ts --reporter=json",
    evidenceParser: "playwright",
  },
  "F07-CI-FINANCE-SYSTEM": {
    command: "npm run --silent e2e:finance:system",
    evidenceParser: "playwright",
  },
  "F07-CI-F07-SYSTEM": {
    command: "npm run --silent e2e:f07:system",
    evidenceParser: "playwright",
  },
  "F07-CI-LINT": { command: "npm run lint", evidenceParser: "none" },
  "F07-CI-LOAD-SYSTEM": {
    actionRequiredWhenAbsent: true,
    commandPattern: operationalArtifactCommand,
    evidenceParser: "structured",
    structuredKind: "load-rehearsal-v1",
  },
  "F07-CI-MAVEN-VERIFY": {
    command: "mvn -B -f backend/pom.xml verify",
    evidenceParser: "junit",
  },
  "F07-CI-MIGRATION-LIVE": {
    commandPattern: liveMigrationCommand,
    evidenceParser: "structured",
    structuredKind: "migration-live-v1",
    structuredResult: "migration-live-v1",
  },
  "F07-CI-MIGRATION-SYSTEM": {
    command: "npm run --silent e2e:migration:system",
    evidenceParser: "playwright",
  },
  "F07-CI-OPS": {
    command: "node scripts/f07/release.mjs schema",
    evidenceParser: "structured",
    structuredKind: "f07-self-test-v1",
  },
  "F07-CI-PERFORMANCE": {
    command:
      "mvn -B -f backend/pom.xml -Dit.test=F07CapacityPerformanceIT verify",
    evidenceParser: "junit",
  },
  "F07-CI-POST-DEPLOY": {
    commandPattern: postDeployRegressionCommand,
    evidenceParser: "structured",
    structuredKind: "post-deploy-regression-v1",
  },
  "F07-CI-ROLLOUT": {
    actionRequiredWhenAbsent: true,
    commandPattern: operationalArtifactCommand,
    evidenceParser: "structured",
    structuredKind: "rollout-rehearsal-v1",
  },
  "F07-CI-SOAK-24H": {
    actionRequiredWhenAbsent: true,
    commandPattern: operationalArtifactCommand,
    evidenceParser: "structured",
    structuredKind: "soak-24h-v1",
  },
  "F07-CI-SUPPLY": {
    commandPattern: supplyCommand,
    evidenceParser: "structured",
    structuredKind: "supply-chain-v1",
  },
  "F07-CI-TYPECHECK": { command: "npm run typecheck", evidenceParser: "none" },
  "F07-CI-UNIT": {
    command: 'npx vitest run "--exclude=e2e/**" --reporter=json',
    evidenceParser: "vitest",
  },
};

const groups = {
  "F07-CI-OPS": [
    "F07-T001", "F07-T002", "F07-T003", "F07-T004", "F07-T006",
    "F07-T047",
    "F07-T081", "F07-T082", "F07-GO-001", "F07-OPS-002",
    "F07-OPS-006",
    "F07-REL-001", "F07-REL-004", "F07-REV-001",
  ],
  "F07-CI-MAVEN-VERIFY": [
    "F07-T005", "F07-T009", "F07-T010", "F07-T011", "F07-T012", "F07-T013", "F07-T014",
    "F07-T015", "F07-T016", "F07-T018", "F07-T019", "F07-T020", "F07-T021",
    "F07-T022", "F07-T023", "F07-T024", "F07-T025", "F07-T026", "F07-T030",
    "F07-T033", "F07-T034", "F07-T035", "F07-T036", "F07-T037",
    "F07-T040", "F07-T041", "F07-T042", "F07-T043", "F07-T044",
    "F07-T046", "F07-T048", "F07-T049", "F07-T050", "F07-T051",
    "F07-AUD-001", "F07-AUD-002",
    "F07-FILE-001", "F07-FILE-002", "F07-IAM-001", "F07-IAM-002", "F07-IAM-003",
    "F07-IAM-004", "F07-IAM-005", "F07-IAM-006", "F07-IAM-007", "F07-PRV-001",
    "F07-REL-002", "F07-RET-001", "F07-RET-002", "F07-SEC-001", "F07-SEC-002",
    "F07-SEC-003", "F07-SEC-004", "F07-SEC-005", "F07-SEC-006", "F07-SEC-007",
    "F07-SEC-008", "F07-SEC-009", "F07-SUP-003", "F07-T071", "F07-ROL-001",
    "F07-OPS-001", "F07-OPS-003", "F07-OPS-004", "F07-OPS-005",
  ],
  "F07-CI-SUPPLY": [
    "F07-T027", "F07-T028", "F07-T029", "F07-T031",
    "F07-SUP-001", "F07-SUP-002",
  ],
  "F07-CI-E2E-ACCESSIBILITY": [
    "F07-T059", "F07-T060", "F07-T061", "F07-T062",
    "F07-A11Y-001", "F07-A11Y-002", "F07-A11Y-003",
  ],
  "F07-CI-MIGRATION-LIVE": [
    "F07-REL-003",
  ],
  "F07-CI-E2E-WORKFORCE": [],
  "F07-CI-E2E-DELIVERY": [],
  "F07-CI-E2E-CERTIFICATION": [],
  "F07-CI-E2E-FINANCE": [],
  "F07-CI-E2E-MIGRATION": [],
  "F07-CI-FINANCE-SYSTEM": ["E2E-06", "E2E-09"],
  "F07-CI-F07-SYSTEM": [
    "E2E-01", "E2E-02", "E2E-03", "E2E-04", "E2E-05", "E2E-07",
    "E2E-10",
  ],
  "F07-CI-MIGRATION-SYSTEM": ["E2E-08"],
  "F07-CI-PERFORMANCE": [
    "F07-T053", "F07-T054", "F07-T055", "F07-T056",
    "F07-PERF-001", "F07-PERF-002",
  ],
  "F07-CI-LOAD-SYSTEM": [
    "F07-PERF-003", "F07-PERF-004", "F07-PERF-005",
  ],
  "F07-CI-SOAK-24H": ["F07-T057", "F07-PERF-006"],
  "F07-CI-DR-REHEARSAL": [
    "F07-T064", "F07-T065", "F07-T066", "F07-T067",
    "F07-DR-002", "F07-DR-003", "T-DR-001",
  ],
  "F07-CI-RELEASE-ARTIFACTS": [
    "F07-T070",
  ],
  "F07-CI-ROLLOUT": [
    "F07-T072", "F07-T073", "F07-T075",
    "F07-ROL-002",
  ],
  "F07-CI-POST-DEPLOY": ["F07-T074"],
  "F07-CI-DOCUMENTATION": [
    "F07-T078", "F07-T079", "F07-T080", "F07-DOC-001",
  ],
};

const explicitCaseRequirements = {
  "F07-T001": ["F07-SELF-RELEASE-GATE"],
  "F07-T002": ["F07-SELF-RELEASE-GATE"],
  "F07-T003": [
    "F07-SELF-RELEASE-GATE",
    "F07-SELF-CI-CONTRACT",
    "F07-SELF-TRACEABILITY",
  ],
  "F07-T004": ["F07-SELF-RELEASE-GATE"],
  "F07-T005": [
    "com.vms.workflow.infrastructure.DatabaseRoleGuardTest#failsClosedWhenEnabledWithoutExpectedRole",
    "com.vms.workflow.integration.WorkforceAttendanceIT#tGhr001002_authoritativeModeFailsClosedWithoutCertifiedCapability",
  ],
  "F07-T006": ["F07-SELF-MIGRATION-POLICY"],
  "F07-T009": [
    "com.vms.workflow.integration.ApiTenantSecurityIT#unknownDisabledAndInvalidScopeIdentitiesFailClosedAcrossEndpointFamilies",
    "com.vms.workflow.integration.FinanceSecurityIT#wrongRoleAndCrossTenantMonthAreDeniedWithoutObjectContent",
  ],
  "F07-T010": [
    "com.vms.workflow.integration.ApiTenantSecurityIT#memberCannotReadCrossOrganizationOrCrossEngagement",
    "com.vms.workflow.integration.ApiTenantSecurityIT#inaccessibleAndUnknownIdsHaveUniformNotFoundResponses",
  ],
  "F07-T011": [
    "com.vms.workflow.integration.F07DatabaseRoleIT#runtimeRoleCannotCreateObjectsOrReadAndMutateFlywayHistory",
    "com.vms.workflow.integration.F07DatabaseRoleIT#reportingIsReadOnlyAndRestrictedPayloadsAreNotGranted",
    "com.vms.workflow.integration.F07DatabaseRoleIT#workerIsQueueBoundAndCannotReadIdentityRbacSecretsOrBlobs",
  ],
  "F07-T012": [
    "com.vms.workflow.integration.F07DatabaseRoleIT#runtimeRoleCannotCreateObjectsOrReadAndMutateFlywayHistory",
    "com.vms.workflow.integration.F07DatabaseRoleIT#reportingIsReadOnlyAndRestrictedPayloadsAreNotGranted",
  ],
  "F07-T013": [
    "com.vms.workflow.integration.F07DatabaseRoleIT#publicHasNoBusinessPrivilegesAndFunctionsHaveFixedSearchPath",
    "com.vms.workflow.integration.F07DatabaseRoleIT#newestSchemaTriggerFunctionsAreFixedAndMinimallyExecutable",
    "com.vms.workflow.integration.F07DatabaseRoleIT#migrationProcessorCanOnlyReadSourceThroughItsLiveLease",
  ],
  "F07-T014": [
    "com.vms.workflow.integration.ApiTenantSecurityIT#unknownDisabledAndInvalidScopeIdentitiesFailClosedAcrossEndpointFamilies",
    "com.vms.workflow.integration.MigrationWorkflowIT#signOffAuthorityRejectsForgeryAmbiguityExpiryAndSameOrganization",
  ],
  "F07-T015": [
    "com.vms.workflow.integration.CertificationReviewIT#signedInboundIngestRequiresServiceIdentityAndNeverReturnsRestrictedData",
    "com.vms.workflow.integration.F07DatabaseRoleIT#workerIsQueueBoundAndCannotReadIdentityRbacSecretsOrBlobs",
  ],
  "F07-T016": [
    "com.vms.workflow.integration.F07HttpHardeningIT#livenessAndReadinessArePublicMinimalAndInfoIsUnavailable",
    "com.vms.workflow.integration.ApiTenantSecurityIT#openApiDeclaresJwtBearerSecurity",
  ],
  "F07-T018": [
    "com.vms.workflow.integration.F07HttpHardeningIT#headersApplyToSecureHealthAndAuthenticationFailures",
    "com.vms.workflow.infrastructure.SecurityHeadersFilterTest#writesHstsOnlyForSecureRequests",
    "com.vms.workflow.infrastructure.SecurityHeadersFilterTest#ignoresForwardedProtoFromUntrustedRemoteAddress",
    "com.vms.workflow.infrastructure.SecurityHeadersFilterTest#acceptsSingleHttpsProtoFromConfiguredTrustedProxy",
    "com.vms.workflow.infrastructure.SecurityHeadersFilterTest#rejectsAmbiguousForwardedProtoEvenFromTrustedProxy",
    "com.vms.workflow.infrastructure.SecurityHeadersFilterTest#productionDisablesContainerForwardHeaderReinterpretation",
  ],
  "F07-T019": [
    "com.vms.workflow.integration.F07HttpHardeningIT#corsAllowsOnlyConfiguredExactOrigin",
    "com.vms.workflow.security.SecurityConfigTest#wildcardLikeAndNullOriginsFailAtStartup",
  ],
  "F07-T020": [
    "com.vms.workflow.integration.CertificationSecurityHardeningIT#ambientCookieCannotAuthorizeBearerOnlyApiAndDenialIsAudited",
  ],
  "F07-T021": [
    "com.vms.workflow.application.FinanceLocalAdaptersTest#localRendererProducesAllFormatsAndEscapesFormulaCells",
  ],
  "F07-T022": [
    "com.vms.workflow.security.OutboundUriPolicyTest#rejectsSsrfAndCredentialForwardingShapes",
  ],
  "F07-T023": [
    "com.vms.workflow.security.CoreRateLimitFilterTest#incrementsOnceAndRejectsAtThresholdAcrossRandomWebhookIds",
    "com.vms.workflow.integration.FinanceRateLimitIT#mutationLimitIsScopedPersistedAuditedAndFailsClosed",
  ],
  "F07-T024": [
    "com.vms.workflow.integration.CertificationSecurityHardeningIT#validationErrorNeverEchoesRestrictedRequestFields",
    "com.vms.workflow.integration.FinanceSecurityIT#unauthenticatedFinanceRequestUsesSafeProblemDetails",
  ],
  "F07-T025": [
    "com.vms.workflow.integration.F07FeatureFlagObservabilityIT#mutationRetriesReplayExactlyAndRejectChangedPayloads",
    "com.vms.workflow.integration.DeliveryLinearIT#reconciliationCommandIsAuthorizedIdempotentAuditedAndTerminal",
    "com.vms.workflow.integration.DeliveryLinearIT#signedWebhookIsDurableDeduplicatedAndDoneOnlyUpdatesExecutionProjection",
    "com.vms.workflow.integration.WorkforceAttendanceIT#tAtt001003_checkInCheckoutAndRetryAreIdempotentImmutableEvents",
    "com.vms.workflow.integration.WorkforcePolicyRegularizationIT#securedPolicyCommandIsTenantValidatedAuditedAndReplaySafe",
    "com.vms.workflow.integration.WorkforceRegularizationConcurrencyIT#concurrentCorrectionsSerializeIntoCompleteVersionedLineage",
  ],
  "F07-T026": [
    "com.vms.workflow.integration.F07RetentionPrivacyIT#classificationAndDatabaseBoundaryRejectCommercialFields",
  ],
  "F07-T030": [
    "com.vms.workflow.integration.SecurityTelemetryRedactionIT#f07Sup003_successErrorAndRetryDiagnosticsRemainRedactedAndCorrelated",
  ],
  "F07-T033": [
    "com.vms.workflow.application.FinanceLocalAdaptersTest#localScannerQuarantinesEicarAndExecutableHeaders",
    "com.vms.workflow.integration.MigrationWorkflowIT#prohibitedCommercialHeaderIsRejectedBeforeSourceRetention",
  ],
  "F07-T034": [
    "com.vms.workflow.integration.FinanceWorkflowIT#quarantinePersistsExactBytesAndExceptionCreatesNewReadinessLineage",
    "com.vms.workflow.integration.FinanceExportAuthorizationIT#restrictedExportLifecycleRevalidatesCurrentReportPermission",
  ],
  "F07-T035": [
    "com.vms.workflow.application.FinanceLocalAdaptersTest#localScannerQuarantinesEicarAndExecutableHeaders",
    "com.vms.workflow.infrastructure.RequestSizeLimitFilterTest#rejectsOversizedChunkedBodyWithoutContentLength",
  ],
  "F07-T036": [
    "com.vms.workflow.integration.F07RetentionPrivacyIT#classificationAndDatabaseBoundaryRejectCommercialFields",
  ],
  "F07-T037": [
    "com.vms.workflow.integration.FinanceExportAuthorizationIT#restrictedExportLifecycleRevalidatesCurrentReportPermission",
    "com.vms.workflow.integration.FinanceWorkflowIT#packageSharesGrantThenRevokeAccessAndExpiredGrantsStayInactive",
  ],
  "F07-T040": [
    "com.vms.workflow.integration.F07FeatureFlagObservabilityIT#scopeDependencyWindowAndAuditAreServerAuthoritative",
    "com.vms.workflow.integration.FinanceArtifactGovernanceIT#scannerTransitionCreatesIndependentAuditRecord",
  ],
  "F07-T041": [
    "com.vms.workflow.infrastructure.ApiObservabilityFilterTest#recordsRouteTemplateAndDenialWithoutRawResourceIdentifier",
    "com.vms.workflow.integration.CertificationReviewIT#inboundReviewIsSafeAppendOnlyIdempotentAndCorrelated",
  ],
  "F07-T042": [
    "com.vms.workflow.integration.FinanceDatabaseControlsIT#privateArtifactMetadataIsImmutable",
    "com.vms.workflow.integration.FinanceDatabaseControlsIT#privateArtifactBlobIsImmutable",
    "com.vms.workflow.integration.WorkforcePolicyRegularizationIT#databaseRejectsCrossDateTargetAndPredecessorLineage",
    "com.vms.workflow.integration.DeliveryLinearIT#reconciliationCommandIsAuthorizedIdempotentAuditedAndTerminal",
  ],
  "F07-T043": [
    "com.vms.workflow.integration.F07RetentionPrivacyIT#scheduleAndDryRunAreOrganizationScopedAndVersioned",
    "com.vms.workflow.integration.F07RetentionPrivacyIT#executionIsSingleOwnerAndDeadLetterRecoveryStartsNewBoundedCycle",
  ],
  "F07-T044": [
    "com.vms.workflow.integration.F07RetentionPrivacyIT#legalHoldRequiresDifferentReleaseApproverAndIsAppendOnly",
    "com.vms.workflow.integration.F07RetentionPrivacyIT#committedHoldLinearizesBeforeRetentionCapabilityExpiry",
  ],
  "F07-T046": [
    "com.vms.workflow.integration.F07FeatureFlagObservabilityIT#openApiMetricsAndReadinessExposeBoundedSafeContracts",
    "com.vms.workflow.infrastructure.ApiObservabilityFilterTest#unmatchedPathsCollapseToOneControlledTag",
    "com.vms.workflow.infrastructure.OperationalReadinessMetricsIT#databaseRowsDriveFreshStaleAndDegradedGreytHrGauges",
  ],
  "F07-T047": ["F07-SELF-OPS-DOCS"],
  "F07-T048": [
    "com.vms.workflow.integration.F07HttpHardeningIT#livenessAndReadinessArePublicMinimalAndInfoIsUnavailable",
    "com.vms.workflow.infrastructure.OptionalProviderHealthIndicatorTest#reportsOptionalCapabilityDegradationWithoutNamesOrEndpoints",
  ],
  "F07-T049": [
    "com.vms.workflow.integration.CertificationOperationsWorkerIT#authorizedReplayCreatesImmutableGenerationAndRedispatches",
    "com.vms.workflow.integration.CertificationOperationsWorkerIT#confirmedRequestPublishesDurableF05FactWithRetryLineage",
    "com.vms.workflow.integration.CertificationOperationsWorkerIT#outboxRetriesThenSendsAndPermanentlyFailedDeliveryDeadLetters",
    "com.vms.workflow.integration.DeliveryCommitmentOperationsWorkerIT#retryRetainsOneOutboxAndOneSuccessfulProviderEffect",
    "com.vms.workflow.integration.FinanceExportRetryIT#scanFailureRetriesToDeadLetterAndCanBeExplicitlyReplayed",
    "com.vms.workflow.integration.GreytHrIntegrationIT#capabilitySyncReconcileCutoverReplayAndOutageAreEndToEnd",
    "com.vms.workflow.integration.MigrationRecoveryWorkerIT#expiredLeaseRecoversOnceFromCheckpointWithoutDuplicateEffects",
    "com.vms.workflow.integration.MigrationRecoveryWorkerIT#scannerTimeoutDeadLettersAtBoundAndAuthorizedReplayRecoversIdempotently",
    "com.vms.workflow.integration.DeliveryLinearIT#reconciliationCommandIsAuthorizedIdempotentAuditedAndTerminal",
  ],
  "F07-T050": [
    "com.vms.workflow.integration.CertificationOperationsWorkerIT#outboxRetriesThenSendsAndPermanentlyFailedDeliveryDeadLetters",
    "com.vms.workflow.integration.DeliveryCommitmentOperationsWorkerIT#retryRetainsOneOutboxAndOneSuccessfulProviderEffect",
    "com.vms.workflow.integration.FinanceExportRetryIT#scanFailureRetriesToDeadLetterAndCanBeExplicitlyReplayed",
    "com.vms.workflow.integration.GreytHrIntegrationIT#capabilitySyncReconcileCutoverReplayAndOutageAreEndToEnd",
    "com.vms.workflow.integration.MigrationRecoveryWorkerIT#scannerTimeoutDeadLettersAtBoundAndAuthorizedReplayRecoversIdempotently",
    "com.vms.workflow.integration.DeliveryLinearIT#reconciliationCommandIsAuthorizedIdempotentAuditedAndTerminal",
  ],
  "F07-T051": [
    "com.vms.workflow.infrastructure.OptionalProviderHealthIndicatorTest#reportsOptionalCapabilityDegradationWithoutNamesOrEndpoints",
    "com.vms.workflow.infrastructure.OperationalReadinessMetricsIT#databaseRowsDriveFreshStaleAndDegradedGreytHrGauges",
    "com.vms.workflow.integration.GreytHrIntegrationIT#capabilitySyncReconcileCutoverReplayAndOutageAreEndToEnd",
    "com.vms.workflow.integration.DeliveryLinearIT#reconciliationCommandIsAuthorizedIdempotentAuditedAndTerminal",
    "com.vms.workflow.integration.DeliveryLinearIT#outOfOrderProviderEventIsRecordedAuditedAndDoesNotRegressProjection",
  ],
  "F07-T059": [
    "F07-A11Y-001A@f07-accessibility-chromium",
    "F07-A11Y-001B@f07-accessibility-chromium",
    "F07-A11Y-001C@f07-accessibility-chromium",
    "F07-A11Y-001D@f07-accessibility-chromium",
    "F07-A11Y-001E@f07-accessibility-chromium",
  ],
  "F07-T060": [
    "F07-A11Y-002A@f07-accessibility-chromium",
    "F07-A11Y-002B@f07-accessibility-chromium",
    "F07-A11Y-002C@f07-accessibility-chromium",
    "F07-A11Y-002D@f07-accessibility-chromium",
    "F07-A11Y-002E@f07-accessibility-chromium",
    "F07-A11Y-002F@f07-accessibility-chromium",
  ],
  "F07-T061": [
    "F07-A11Y-001B@f07-compatibility-android",
    "F07-A11Y-003@f07-compatibility-ios",
  ],
  "F07-T062": [
    "F07-A11Y-003@f07-accessibility-chromium-utc",
    "F07-A11Y-003@f07-compatibility-firefox",
    "F07-A11Y-003@f07-compatibility-webkit",
    "F07-A11Y-003@f07-compatibility-android",
    "F07-A11Y-003@f07-compatibility-ios",
  ],
  "F07-T070": ["RELEASE-ARTIFACT-MANIFEST"],
  "F07-T071": [
    "com.vms.workflow.integration.F07FeatureFlagObservabilityIT#scopeDependencyWindowAndAuditAreServerAuthoritative",
    "com.vms.workflow.integration.F07FeatureFlagObservabilityIT#directClientInputCannotCreateAuthorityOrBypassRbac",
  ],
  "F07-T072": ["ROLLOUT-ROLLBACK"],
  "F07-T074": [
    "POST-DEPLOY-E2E-01",
    "POST-DEPLOY-E2E-02",
    "POST-DEPLOY-E2E-03",
    "POST-DEPLOY-E2E-04",
    "POST-DEPLOY-E2E-05",
    "POST-DEPLOY-E2E-06",
    "POST-DEPLOY-E2E-07",
    "POST-DEPLOY-E2E-08",
    "POST-DEPLOY-E2E-09",
    "POST-DEPLOY-E2E-10",
    "POST-DEPLOY-AUDIT-OUTBOX",
    "POST-ROLLBACK-INTEGRITY",
    "POST-DEPLOY-REGRESSION",
  ],
  "F07-T078": ["OPS-RUNBOOK-CATALOG"],
  "F07-T079": ["OPS-RUNBOOK-CATALOG"],
  "F07-T080": ["OPS-RUNBOOK-CATALOG"],
  "F07-T081": ["F07-SELF-CI-CONTRACT"],
  "F07-T082": ["F07-SELF-REVIEW-CONTROL"],
  "F07-A11Y-001": [
    "F07-A11Y-001A@f07-accessibility-chromium",
    "F07-A11Y-001B@f07-accessibility-chromium",
    "F07-A11Y-001C@f07-accessibility-chromium",
    "F07-A11Y-001D@f07-accessibility-chromium",
    "F07-A11Y-001E@f07-accessibility-chromium",
  ],
  "F07-A11Y-002": [
    "F07-A11Y-002A@f07-accessibility-chromium",
    "F07-A11Y-002B@f07-accessibility-chromium",
    "F07-A11Y-002C@f07-accessibility-chromium",
    "F07-A11Y-002D@f07-accessibility-chromium",
    "F07-A11Y-002E@f07-accessibility-chromium",
    "F07-A11Y-002F@f07-accessibility-chromium",
  ],
  "F07-A11Y-003": [
    "F07-A11Y-003@f07-accessibility-chromium",
    "F07-A11Y-003@f07-accessibility-chromium-utc",
    "F07-A11Y-003@f07-compatibility-firefox",
    "F07-A11Y-003@f07-compatibility-webkit",
    "F07-A11Y-003@f07-compatibility-android",
    "F07-A11Y-003@f07-compatibility-ios",
  ],
  "F07-AUD-001": [
    "com.vms.workflow.integration.CertificationReviewIT#inboundReviewIsSafeAppendOnlyIdempotentAndCorrelated",
    "com.vms.workflow.integration.FinanceArtifactGovernanceIT#scannerTransitionCreatesIndependentAuditRecord",
  ],
  "F07-AUD-002": [
    "com.vms.workflow.integration.FinanceDatabaseControlsIT#privateArtifactMetadataIsImmutable",
    "com.vms.workflow.integration.FinanceDatabaseControlsIT#directSqlCannotChangeLegalHoldWithoutAuthorizedTransitionLedger",
    "com.vms.workflow.integration.WorkforcePolicyRegularizationIT#databaseRejectsCrossDateTargetAndPredecessorLineage",
    "com.vms.workflow.integration.DeliveryLinearIT#reconciliationCommandIsAuthorizedIdempotentAuditedAndTerminal",
  ],
  "F07-DOC-001": ["OPS-RUNBOOK-CATALOG"],
  "F07-FILE-001": [
    "com.vms.workflow.application.FinanceLocalAdaptersTest#localScannerQuarantinesEicarAndExecutableHeaders",
    "com.vms.workflow.infrastructure.RequestSizeLimitFilterTest#rejectsDeclaredOversizedMutationBeforeReadingBody",
  ],
  "F07-FILE-002": [
    "com.vms.workflow.integration.FinanceExportAuthorizationIT#restrictedExportLifecycleRevalidatesCurrentReportPermission",
    "com.vms.workflow.integration.FinanceWorkflowIT#packageSharesGrantThenRevokeAccessAndExpiredGrantsStayInactive",
  ],
  "F07-GO-001": ["F07-SELF-RELEASE-GATE"],
  "F07-IAM-001": [
    "com.vms.workflow.integration.ApiTenantSecurityIT#memberCannotReadCrossOrganizationOrCrossEngagement",
    "com.vms.workflow.integration.FinanceSecurityIT#wrongRoleAndCrossTenantMonthAreDeniedWithoutObjectContent",
  ],
  "F07-IAM-002": [
    "com.vms.workflow.integration.ApiTenantSecurityIT#unknownDisabledAndInvalidScopeIdentitiesFailClosedAcrossEndpointFamilies",
  ],
  "F07-IAM-003": [
    "com.vms.workflow.integration.MigrationWorkflowIT#signOffAuthorityRejectsForgeryAmbiguityExpiryAndSameOrganization",
  ],
  "F07-IAM-004": [
    "com.vms.workflow.integration.F07DatabaseRoleIT#runtimeRoleCannotCreateObjectsOrReadAndMutateFlywayHistory",
    "com.vms.workflow.integration.F07DatabaseRoleIT#reportingIsReadOnlyAndRestrictedPayloadsAreNotGranted",
    "com.vms.workflow.integration.F07DatabaseRoleIT#workerIsQueueBoundAndCannotReadIdentityRbacSecretsOrBlobs",
  ],
  "F07-IAM-005": [
    "com.vms.workflow.integration.F07DatabaseRoleIT#publicHasNoBusinessPrivilegesAndFunctionsHaveFixedSearchPath",
    "com.vms.workflow.integration.F07DatabaseRoleIT#newestSchemaTriggerFunctionsAreFixedAndMinimallyExecutable",
    "com.vms.workflow.integration.F07DatabaseRoleIT#migrationProcessorCanOnlyReadSourceThroughItsLiveLease",
  ],
  "F07-IAM-006": [
    "com.vms.workflow.integration.CertificationReviewIT#signedInboundIngestRequiresServiceIdentityAndNeverReturnsRestrictedData",
  ],
  "F07-IAM-007": [
    "com.vms.workflow.integration.F07HttpHardeningIT#livenessAndReadinessArePublicMinimalAndInfoIsUnavailable",
  ],
  "F07-OPS-001": [
    "com.vms.workflow.integration.F07FeatureFlagObservabilityIT#openApiMetricsAndReadinessExposeBoundedSafeContracts",
    "com.vms.workflow.infrastructure.ApiObservabilityFilterTest#recordsRouteTemplateAndDenialWithoutRawResourceIdentifier",
  ],
  "F07-OPS-002": ["F07-SELF-OPS-DOCS"],
  "F07-OPS-003": [
    "com.vms.workflow.integration.F07HttpHardeningIT#livenessAndReadinessArePublicMinimalAndInfoIsUnavailable",
    "com.vms.workflow.infrastructure.OptionalProviderHealthIndicatorTest#reportsOptionalCapabilityDegradationWithoutNamesOrEndpoints",
  ],
  "F07-OPS-004": [
    "com.vms.workflow.integration.CertificationOperationsWorkerIT#authorizedReplayCreatesImmutableGenerationAndRedispatches",
    "com.vms.workflow.integration.CertificationOperationsWorkerIT#confirmedRequestPublishesDurableF05FactWithRetryLineage",
    "com.vms.workflow.integration.CertificationOperationsWorkerIT#outboxRetriesThenSendsAndPermanentlyFailedDeliveryDeadLetters",
    "com.vms.workflow.integration.DeliveryCommitmentOperationsWorkerIT#retryRetainsOneOutboxAndOneSuccessfulProviderEffect",
    "com.vms.workflow.integration.FinanceExportRetryIT#scanFailureRetriesToDeadLetterAndCanBeExplicitlyReplayed",
    "com.vms.workflow.integration.MigrationRecoveryWorkerIT#expiredLeaseRecoversOnceFromCheckpointWithoutDuplicateEffects",
    "com.vms.workflow.integration.MigrationRecoveryWorkerIT#scannerTimeoutDeadLettersAtBoundAndAuthorizedReplayRecoversIdempotently",
  ],
  "F07-OPS-005": [
    "com.vms.workflow.integration.CertificationOperationsWorkerIT#outboxRetriesThenSendsAndPermanentlyFailedDeliveryDeadLetters",
    "com.vms.workflow.integration.DeliveryCommitmentOperationsWorkerIT#retryRetainsOneOutboxAndOneSuccessfulProviderEffect",
    "com.vms.workflow.integration.FinanceExportRetryIT#scanFailureRetriesToDeadLetterAndCanBeExplicitlyReplayed",
    "com.vms.workflow.integration.GreytHrIntegrationIT#capabilitySyncReconcileCutoverReplayAndOutageAreEndToEnd",
    "com.vms.workflow.integration.MigrationRecoveryWorkerIT#scannerTimeoutDeadLettersAtBoundAndAuthorizedReplayRecoversIdempotently",
    "com.vms.workflow.integration.DeliveryLinearIT#reconciliationCommandIsAuthorizedIdempotentAuditedAndTerminal",
    "com.vms.workflow.infrastructure.OptionalProviderHealthIndicatorTest#reportsOptionalCapabilityDegradationWithoutNamesOrEndpoints",
  ],
  "F07-OPS-006": ["F07-SELF-OPS-DOCS"],
  "F07-PRV-001": [
    "com.vms.workflow.integration.F07RetentionPrivacyIT#classificationAndDatabaseBoundaryRejectCommercialFields",
  ],
  "F07-REL-001": ["F07-SELF-RELEASE-GATE"],
  "F07-REL-002": [
    "com.vms.workflow.infrastructure.DatabaseRoleGuardTest#failsClosedWhenEnabledWithoutExpectedRole",
    "com.vms.workflow.integration.WorkforceAttendanceIT#tGhr001002_authoritativeModeFailsClosedWithoutCertifiedCapability",
  ],
  "F07-REL-003": [
    "MIGRATION-LIVE-SOURCE-HISTORY",
    "MIGRATION-EMPTY-UPGRADE-CONVERGENCE",
    "MIGRATION-EVIDENCE-PRESERVATION",
    "MIGRATION-INCOMPATIBLE-BLOCKED",
  ],
  "F07-REL-004": [
    "F07-SELF-RELEASE-GATE",
    "F07-SELF-CI-CONTRACT",
    "F07-SELF-TRACEABILITY",
  ],
  "F07-RET-001": [
    "com.vms.workflow.integration.F07RetentionPrivacyIT#scheduleAndDryRunAreOrganizationScopedAndVersioned",
    "com.vms.workflow.integration.F07RetentionPrivacyIT#executionIsSingleOwnerAndDeadLetterRecoveryStartsNewBoundedCycle",
  ],
  "F07-RET-002": [
    "com.vms.workflow.integration.F07RetentionPrivacyIT#legalHoldRequiresDifferentReleaseApproverAndIsAppendOnly",
    "com.vms.workflow.integration.F07RetentionPrivacyIT#committedHoldLinearizesBeforeRetentionCapabilityExpiry",
  ],
  "F07-REV-001": ["F07-SELF-REVIEW-CONTROL"],
  "F07-ROL-001": [
    "com.vms.workflow.integration.F07FeatureFlagObservabilityIT#scopeDependencyWindowAndAuditAreServerAuthoritative",
    "com.vms.workflow.integration.F07FeatureFlagObservabilityIT#directClientInputCannotCreateAuthorityOrBypassRbac",
  ],
  "F07-SEC-001": [
    "com.vms.workflow.integration.F07HttpHardeningIT#headersApplyToSecureHealthAndAuthenticationFailures",
    "com.vms.workflow.infrastructure.SecurityHeadersFilterTest#ignoresForwardedProtoFromUntrustedRemoteAddress",
    "com.vms.workflow.infrastructure.SecurityHeadersFilterTest#acceptsSingleHttpsProtoFromConfiguredTrustedProxy",
    "com.vms.workflow.infrastructure.SecurityHeadersFilterTest#rejectsAmbiguousForwardedProtoEvenFromTrustedProxy",
    "com.vms.workflow.infrastructure.SecurityHeadersFilterTest#productionDisablesContainerForwardHeaderReinterpretation",
  ],
  "F07-SEC-002": [
    "com.vms.workflow.integration.F07HttpHardeningIT#corsAllowsOnlyConfiguredExactOrigin",
    "com.vms.workflow.security.SecurityConfigTest#wildcardLikeAndNullOriginsFailAtStartup",
  ],
  "F07-SEC-003": [
    "com.vms.workflow.integration.CertificationSecurityHardeningIT#ambientCookieCannotAuthorizeBearerOnlyApiAndDenialIsAudited",
  ],
  "F07-SEC-004": [
    "com.vms.workflow.application.FinanceLocalAdaptersTest#localRendererProducesAllFormatsAndEscapesFormulaCells",
  ],
  "F07-SEC-005": [
    "com.vms.workflow.security.OutboundUriPolicyTest#rejectsSsrfAndCredentialForwardingShapes",
  ],
  "F07-SEC-006": [
    "com.vms.workflow.security.CoreRateLimitFilterTest#incrementsOnceAndRejectsAtThresholdAcrossRandomWebhookIds",
    "com.vms.workflow.integration.FinanceRateLimitIT#mutationLimitIsScopedPersistedAuditedAndFailsClosed",
  ],
  "F07-SEC-007": [
    "com.vms.workflow.integration.CertificationSecurityHardeningIT#validationErrorNeverEchoesRestrictedRequestFields",
  ],
  "F07-SEC-008": [
    "com.vms.workflow.integration.F07FeatureFlagObservabilityIT#mutationRetriesReplayExactlyAndRejectChangedPayloads",
    "com.vms.workflow.integration.DeliveryLinearIT#reconciliationCommandIsAuthorizedIdempotentAuditedAndTerminal",
    "com.vms.workflow.integration.DeliveryLinearIT#signedWebhookIsDurableDeduplicatedAndDoneOnlyUpdatesExecutionProjection",
    "com.vms.workflow.integration.WorkforceAttendanceIT#tAtt001003_checkInCheckoutAndRetryAreIdempotentImmutableEvents",
    "com.vms.workflow.integration.WorkforcePolicyRegularizationIT#securedPolicyCommandIsTenantValidatedAuditedAndReplaySafe",
    "com.vms.workflow.integration.WorkforceRegularizationConcurrencyIT#concurrentCorrectionsSerializeIntoCompleteVersionedLineage",
  ],
  "F07-SEC-009": [
    "com.vms.workflow.integration.F07RetentionPrivacyIT#classificationAndDatabaseBoundaryRejectCommercialFields",
  ],
  "F07-SUP-003": [
    "com.vms.workflow.integration.SecurityTelemetryRedactionIT#f07Sup003_successErrorAndRetryDiagnosticsRemainRedactedAndCorrelated",
  ],
  "E2E-01": [
    "E2E-01@f07-workforce-system-chromium",
  ],
  "E2E-02": [
    "E2E-02@f07-greythr-system-chromium",
  ],
  "E2E-03": [
    "E2E-03@f07-delivery-confirmation-system-chromium",
  ],
  "E2E-04": [
    "E2E-04@f07-delivery-confirmation-system-chromium",
  ],
  "E2E-05": [
    "E2E-05@f07-delivery-confirmation-system-chromium",
  ],
  "E2E-06": [
    "E2E-F05-SYS-001@f05-finance-system-chromium",
    "E2E-F05-SYS-002@f05-finance-system-chromium",
    "E2E-F05-SYS-003@f05-finance-system-chromium",
  ],
  "E2E-07": [
    "E2E-07@f07-real-system-chromium",
  ],
  "E2E-08": [
    "E2E-F06-SYS-001@f06-migration-system-chromium",
    "E2E-F06-SYS-002@f06-migration-system-chromium",
    "E2E-F06-SYS-003@f06-migration-system-chromium",
    "E2E-F06-SYS-004@f06-migration-system-chromium",
    "E2E-F06-SYS-005@f06-migration-system-chromium",
    "E2E-F06-SYS-006@f06-migration-system-chromium",
    "E2E-F06-SYS-007@f06-migration-system-chromium",
  ],
  "E2E-09": [
    "E2E-09@f05-finance-system-chromium",
  ],
  "E2E-10": [
    "E2E-10@f07-real-system-chromium",
  ],
  "F07-PERF-001": [
    "com.vms.workflow.integration.F07CapacityPerformanceIT#f07Perf001_twentySixUsersCheckInWithBoundedConcurrencyAndIdempotentReplay",
  ],
  "F07-PERF-002": [
    "com.vms.workflow.integration.F07CapacityPerformanceIT#f07Perf002_scopedSearchAndReportingUseBoundedIndexedPlans",
  ],
  "F07-T053": [
    "com.vms.workflow.integration.F07CapacityPerformanceIT#f07Perf001_twentySixUsersCheckInWithBoundedConcurrencyAndIdempotentReplay",
    "com.vms.workflow.integration.F07CapacityPerformanceIT#f07Perf002_scopedSearchAndReportingUseBoundedIndexedPlans",
  ],
  "F07-T054": [
    "com.vms.workflow.integration.F07CapacityPerformanceIT#f07Perf001_twentySixUsersCheckInWithBoundedConcurrencyAndIdempotentReplay",
  ],
  "F07-T055": [
    "com.vms.workflow.integration.F07CapacityPerformanceIT#f07Perf001_twentySixUsersCheckInWithBoundedConcurrencyAndIdempotentReplay",
    "com.vms.workflow.integration.WorkforceRegularizationConcurrencyIT#concurrentCorrectionsSerializeIntoCompleteVersionedLineage",
  ],
  "F07-T056": [
    "com.vms.workflow.integration.F07CapacityPerformanceIT#f07Perf002_scopedSearchAndReportingUseBoundedIndexedPlans",
  ],
  "F07-SUP-001": ["SUPPLY-SCANNERS"],
  "F07-SUP-002": ["SUPPLY-SBOM-LICENSES"],
  "F07-T027": ["SUPPLY-SECRETS"],
  "F07-T028": [
    "SUPPLY-SCANNERS",
    "SUPPLY-SBOM-LICENSES",
    "SUPPLY-IMAGE-DIGESTS",
  ],
  "F07-T029": ["SUPPLY-ARTIFACTS"],
  "F07-T031": ["SUPPLY-SECRETS"],
  "F07-PERF-003": ["LOAD-WEBHOOK"],
  "F07-PERF-004": ["LOAD-MIGRATION-EXPORT"],
  "F07-PERF-005": ["LOAD-PACKAGE"],
  "F07-PERF-006": ["SOAK-24H"],
  "F07-T057": ["SOAK-24H"],
  "F07-T064": ["DR-BACKUP"],
  "F07-T065": ["DR-RESTORE", "DR-RECONCILE"],
  "F07-T066": ["DR-RECOVERY-BOUNDARY"],
  "F07-T067": ["DR-RESTORE", "DR-RECOVERY-BOUNDARY", "DR-RECONCILE"],
  "T-DR-001": ["DR-RESTORE", "DR-RECOVERY-BOUNDARY", "DR-RECONCILE"],
  "F07-DR-002": ["DR-CONFIDENTIALITY"],
  "F07-DR-003": ["DR-RECOVERY-BOUNDARY", "DR-RECONCILE"],
  "F07-T073": ["ROLLOUT-CANARY"],
  "F07-T075": ["ROLLOUT-ROLLBACK"],
  "F07-ROL-002": ["ROLLOUT-CANARY", "ROLLOUT-ROLLBACK"],
};

function buildRecordEvidencePolicy() {
  const entries = [];
  const assigned = new Map();
  for (const [laneId, recordIds] of Object.entries(groups)) {
    for (const recordId of recordIds) {
      if (assigned.has(recordId)) {
        throw new Error(
          `${recordId} is assigned to both ${assigned.get(recordId)} and ${laneId}`,
        );
      }
      assigned.set(recordId, laneId);
      entries.push([
        recordId,
        {
          laneId,
          resultId: recordId,
          requiredCases: explicitCaseRequirements[recordId] ?? [],
        },
      ]);
    }
  }
  return Object.freeze(Object.fromEntries(entries));
}

export const recordEvidencePolicy = buildRecordEvidencePolicy();

export const requiredCiLanes = Object.freeze(
  Object.fromEntries(
    Object.entries(laneCommands).map(([laneId, definition]) => [
      laneId,
      {
        ...definition,
        recordRequirements: Object.fromEntries(
          Object.entries(recordEvidencePolicy)
            .filter(([, policy]) => policy.laneId === laneId)
            .map(([recordId, policy]) => [recordId, policy.requiredCases]),
        ),
        suiteId: `${laneId}-SUITE`,
      },
    ]),
  ),
);

export const allowedIndependentApproverRoles = new Set([
  "data-platform-approver",
  "legal-procurement-approver",
  "operations-approver",
  "release-approver",
  "security-approver",
]);
