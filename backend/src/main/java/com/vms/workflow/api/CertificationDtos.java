package com.vms.workflow.api;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class CertificationDtos {
    private CertificationDtos() {
    }

    public record SubmissionCriterionInput(
        @NotNull UUID criterionId,
        @NotBlank @Size(max = 10_000) String response,
        @NotNull @Size(max = 100) List<UUID> evidenceReferenceIds
    ) {
    }

    public record SubmissionItemInput(
        @NotNull UUID deliverableId,
        @NotBlank @Pattern(regexp =
            "COMPLETED|PARTIALLY_COMPLETED|DEFERRED|NOT_COMPLETED|CANCELLED_BY_APPROVED_CHANGE")
        String outcome,
        @Min(0) @Max(100) int completionPercentage,
        LocalDate completionDate,
        @NotBlank @Size(max = 20_000) String summary,
        @Size(max = 4_000) String varianceCause,
        @Size(max = 4_000) String varianceImpact,
        @Size(max = 4_000) String nextAction,
        @Size(max = 4_000) String carryForwardProposal,
        @NotEmpty @Size(max = 200)
        List<@Valid SubmissionCriterionInput> criterionResponses,
        @NotNull @Size(max = 200) List<UUID> evidenceReferenceIds
    ) {
    }

    public record SaveSubmissionRequest(
        @Min(0) long expectedMonthVersion,
        @NotBlank @Size(max = 20_000) String summary,
        boolean declarationAccepted,
        @NotEmpty @Size(max = 200) List<@Valid SubmissionItemInput> items
    ) {
    }

    public record SubmitSubmissionRequest(@Min(0) long expectedSubmissionVersion) {
    }

    public record ClarificationRequest(
        @Min(0) long expectedSubmissionVersion,
        @NotNull UUID deliverableId,
        @Size(min = 1, max = 20)
        List<@NotBlank @Size(max = 4_000) String> questions,
        UUID clarificationId,
        @Size(min = 1, max = 20_000) String response
    ) {
    }

    public record CertificationCriterionInput(
        @NotNull UUID criterionId,
        @NotBlank @Pattern(regexp = "MET|PARTIALLY_MET|NOT_MET|NOT_APPLICABLE")
        String decision,
        @NotNull @Size(max = 4_000) String rationale,
        boolean evidenceViewed
    ) {
    }

    public record CertificationRequest(
        @Min(0) long expectedSubmissionVersion,
        @NotNull UUID deliverableId,
        @NotBlank @Pattern(regexp =
            "ACCEPTED|ACCEPTED_WITH_OBSERVATIONS|PARTIALLY_ACCEPTED|"
                + "CLIENT_DEPENDENCY_DEFERRED|VENDOR_DEPENDENCY_DEFERRED|"
                + "REJECTED|CANCELLED_BY_APPROVED_CHANGE|MORE_INFORMATION_REQUIRED")
        String decision,
        @Size(max = 10_000) String comment,
        @Size(max = 10_000) String observations,
        @Size(max = 4_000) String cause,
        @Size(max = 4_000) String nextAction,
        @Size(max = 10_000) String acceptedScope,
        @Size(max = 10_000) String rejectedScope,
        @Size(max = 4_000) String carryForward,
        @Size(max = 4_000) String overrideRationale,
        @NotEmpty @Size(max = 200)
        List<@Valid CertificationCriterionInput> criterionResults
    ) {
    }

    public record SummaryRequest(
        @Min(0) long expectedMonthVersion,
        @NotBlank @Pattern(regexp =
            "CERTIFIED|CERTIFIED_WITH_OBSERVATIONS|PARTIALLY_CERTIFIED|NOT_CERTIFIED")
        String decision,
        @Size(max = 20_000) String observations
    ) {
    }

    public record ConfirmationRequestInput(
        @Min(0) long expectedMonthVersion,
        @NotNull @Future
        @JsonFormat(without = JsonFormat.Feature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
        OffsetDateTime dueAt
    ) {
    }

    public record ConfirmationActionRequest(
        @Min(0) long expectedRequestVersion,
        @NotBlank @Pattern(regexp = "CONFIRM|REQUEST_CORRECTION|REJECT")
        String decision,
        @Size(max = 20_000) String comment,
        @Schema(description =
            "Captured eligible project. Required when the actor is eligible for more than one project.")
        UUID projectId,
        @Schema(description = "Optional secure-link token. Omit for an authenticated in-app action.",
            accessMode = Schema.AccessMode.WRITE_ONLY)
        @Size(max = 1024) String secureToken
    ) {
    }

    public record GovernanceDecisionInput(
        @Min(0) int expectedRequestVersion,
        @NotBlank @Pattern(regexp = "CONFIRM|REQUEST_CORRECTION|REJECT")
        String decision,
        @NotBlank @Size(max = 20_000) String reasoning,
        @NotEmpty @Size(max = 200) List<@NotNull UUID> actionIds
    ) {
    }

    public record GovernanceDecisionView(
        UUID id,
        UUID requestId,
        int requestVersion,
        String decision,
        String reasoning,
        List<UUID> actionIds,
        String decidedByDisplay,
        OffsetDateTime decidedAt,
        UUID auditReference
    ) {
    }

    public record ReopenRequestInput(
        @Min(0) long expectedMonthVersion,
        @NotBlank @Pattern(regexp =
            "ATTENDANCE_CORRECTION|CERTIFICATION_CORRECTION|PLAN_CORRECTION|OTHER")
        String category,
        @NotBlank @Size(max = 10_000) String reason,
        @NotEmpty @Size(max = 200) List<UUID> impactedRecordIds,
        @NotBlank @Size(max = 4_000) String packageInvoiceImpact,
        @NotBlank @Size(max = 10_000) String riskStatement
    ) {
    }

    public record CloseMonthInput(@Min(0) long expectedMonthVersion) {
    }

    public record MonthClosureView(
        UUID id,
        UUID monthId,
        int version,
        UUID confirmationRequestId,
        String manifestHash,
        String status,
        OffsetDateTime closedAt,
        UUID supersedesId
    ) {
    }

    public record ReopenDecisionInput(
        @Min(0) long expectedMonthVersion,
        @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
        @NotBlank @Size(max = 10_000) String reasoning
    ) {
    }

    public record ReopenDecisionView(
        UUID id,
        UUID reopenRequestId,
        UUID monthId,
        String decision,
        String reasoning,
        String decidedByDisplay,
        OffsetDateTime decidedAt,
        UUID auditReference
    ) {
    }

    public record InvalidationResolutionInput(
        @Min(0) long expectedMonthVersion,
        @NotBlank @Pattern(regexp = "CLEARED|SUPERSEDED") String resolution,
        @NotBlank @Size(max = 10_000) String reasoning
    ) {
    }

    public record InvalidationResolutionView(
        UUID id,
        UUID invalidationId,
        UUID monthId,
        String resolution,
        String reasoning,
        String resolvedByDisplay,
        OffsetDateTime resolvedAt,
        UUID auditReference
    ) {
    }

    public record NotificationReplayInput(
        @Min(0) long expectedMonthVersion,
        @NotBlank @Size(max = 10_000) String reason
    ) {
    }

    public record NotificationReplayView(
        UUID id,
        UUID notificationId,
        UUID monthId,
        String transportStatus,
        int replayNumber,
        int totalAttemptCount,
        OffsetDateTime replayedAt,
        UUID correlationId
    ) {
    }

    public record CertificationInboxItemView(
        UUID monthId,
        UUID engagementId,
        String engagementCode,
        String engagementName,
        LocalDate monthStartDate,
        String monthLabel,
        String lifecycleState,
        long monthVersion,
        String submissionStatus,
        int deliverableCount,
        int terminalDecisionCount,
        int assignedReviewCount,
        int pendingInboundReviewCount,
        String confirmationState,
        OffsetDateTime confirmationDueAt,
        String readinessStatus,
        boolean overdue,
        String nextAction,
        String actionPath
    ) {
    }

    public record CertificationInboxView(
        OffsetDateTime generatedAt,
        int total,
        int actionRequired,
        int overdue,
        List<CertificationInboxItemView> items
    ) {
    }

    public record CertificationQueueSummaryView(
        String queue,
        int pending,
        int claimed,
        int failed,
        int deadLetter,
        int completed,
        int cancelled,
        OffsetDateTime oldestActionableAt
    ) {
    }

    public record CertificationOperationItemView(
        UUID id,
        UUID monthId,
        long monthVersion,
        String monthLabel,
        String engagementCode,
        String queue,
        String workType,
        String status,
        int attemptCount,
        OffsetDateTime dueAt,
        OffsetDateTime lastAttemptAt,
        String safeErrorCode,
        UUID correlationId,
        boolean replayAllowed
    ) {
    }

    public record CertificationOperationsView(
        OffsetDateTime generatedAt,
        String providerConfiguration,
        List<CertificationQueueSummaryView> queues,
        List<CertificationOperationItemView> actionableItems
    ) {
    }

    public record PolicyVersionInput(
        @Min(0) long expectedMonthVersion,
        boolean attendanceRequired,
        @AssertTrue boolean separationOfDutiesRequired,
        boolean monthlyDecisionRequired,
        @AssertTrue boolean manualSecondReviewRequired,
        @NotBlank @Pattern(regexp =
            "ANY_ONE|ALL|N_OF_M|ORDERED|PROJECT_SPECIFIC")
        String quorumMode,
        @Min(1) @Max(200) int quorumRequired,
        @Min(300) @Max(2_592_000) int tokenTtlSeconds,
        @Min(3_600) @Max(7_776_000) int confirmationDueSeconds,
        @NotNull @Size(max = 20)
        List<@Min(60) @Max(7_775_999) Integer> reminderOffsetsSeconds,
        @Min(300) @Max(2_592_000) int reviewSlaSeconds,
        boolean evidenceRequiredWhenFrozenExpectationPresent,
        @NotEmpty @Size(max = 2)
        List<@Pattern(regexp = "PASSED|NOT_REQUIRED") String> allowedScanStatuses,
        @NotBlank @Pattern(regexp = "FROZEN_PLAN_RECIPIENT_SNAPSHOT")
        String recipientSource,
        @Min(30) @Max(3_650) int retentionDays
    ) {
    }

    public record PolicyVersionView(
        UUID id,
        UUID engagementId,
        int version,
        String status,
        String quorumMode,
        int quorumRequired,
        int tokenTtlSeconds,
        int confirmationDueSeconds,
        List<Integer> reminderOffsetsSeconds,
        String policyHash,
        OffsetDateTime createdAt,
        String createdByDisplay
    ) {
    }

    public record EvidenceExceptionInput(
        @Min(0) long expectedMonthVersion,
        @NotNull UUID deliverableId,
        UUID criterionId,
        @NotBlank @Pattern(regexp =
            "PROVIDER_UNAVAILABLE|HISTORICAL_EVIDENCE|CLIENT_WAIVER|OTHER")
        String reasonCode,
        @NotBlank @Size(max = 10_000) String justification
    ) {
    }

    public record EvidenceExceptionView(
        UUID id,
        UUID monthId,
        UUID submissionId,
        UUID deliverableId,
        UUID criterionId,
        String reasonCode,
        String justification,
        String approvedByDisplay,
        OffsetDateTime approvedAt,
        UUID correlationId
    ) {
    }

    public record AttendanceExceptionInput(
        @Min(0) long expectedMonthVersion,
        @NotBlank @Pattern(regexp =
            "SOURCE_UNAVAILABLE|APPROVED_HISTORICAL_SCOPE|OTHER")
        String reasonCode,
        @NotBlank @Size(max = 10_000) String justification,
        @NotEmpty @Size(max = 50)
        List<@NotBlank @Size(max = 500) String> disclosures
    ) {
    }

    public record AttendanceExceptionView(
        UUID id,
        UUID monthId,
        UUID policyVersionId,
        String reasonCode,
        String justification,
        List<String> disclosures,
        String approvedByDisplay,
        OffsetDateTime approvedAt,
        UUID correlationId
    ) {
    }

    public record CertificationPermissions(
        boolean canEditSubmission,
        boolean canSubmit,
        boolean canRespondToClarification,
        boolean canCertify,
        boolean canRequestClarification,
        boolean canGenerateSummary,
        boolean canRequestConfirmation,
        boolean canConfirm,
        boolean canReviewInbound,
        boolean canReopen
    ) {
    }

    public record SafeEvidenceReference(
        UUID id,
        String displayName,
        String classification,
        String scanStatus,
        String source,
        boolean viewAllowed
    ) {
    }

    public record CriterionView(
        UUID id,
        int sequence,
        String statement,
        String expectedResult,
        boolean mandatory
    ) {
    }

    public record VendorCriterionResponse(
        UUID criterionId,
        String response,
        List<SafeEvidenceReference> evidenceReferences
    ) {
    }

    public record SubmissionItemView(
        UUID deliverableId,
        String outcome,
        int completionPercentage,
        LocalDate completionDate,
        String summary,
        String varianceCause,
        String varianceImpact,
        String nextAction,
        String carryForwardProposal,
        List<VendorCriterionResponse> criterionResponses,
        List<SafeEvidenceReference> evidenceReferences
    ) {
    }

    public record CertificationCriterionResult(
        UUID criterionId,
        String decision,
        String rationale,
        boolean evidenceViewed
    ) {
    }

    public record CertificationView(
        UUID id,
        int version,
        String decision,
        String comment,
        String observations,
        String cause,
        String nextAction,
        String acceptedScope,
        String rejectedScope,
        String carryForward,
        List<CertificationCriterionResult> criterionResults,
        String decidedByDisplay,
        OffsetDateTime decidedAt,
        boolean terminal
    ) {
    }

    public record DeliverableCertificationView(
        UUID id,
        String code,
        String title,
        String projectName,
        UUID baselineVersionId,
        String baselineDescription,
        String businessObjective,
        String evidenceExpectation,
        boolean assignedToCurrentActor,
        String assignmentReason,
        OffsetDateTime reviewStartedAt,
        OffsetDateTime reviewDueAt,
        long reviewAgeSeconds,
        @Schema(allowableValues = {
            "NOT_STARTED", "NEW", "AGING", "OVERDUE", "RESOLVED"
        })
        String reviewAgingStatus,
        List<CriterionView> criteria,
        SubmissionItemView vendorSubmission,
        CertificationView certification
    ) {
    }

    public record ClarificationView(
        UUID id,
        int round,
        UUID deliverableId,
        List<String> questions,
        String requestedByDisplay,
        OffsetDateTime requestedAt,
        String response,
        OffsetDateTime respondedAt,
        String status
    ) {
    }

    public record SubmissionView(
        UUID id,
        int version,
        String status,
        String summary,
        boolean declarationAccepted,
        List<String> completenessBlockers,
        OffsetDateTime autosavedAt,
        OffsetDateTime submittedAt,
        boolean locked,
        List<SubmissionItemView> items
    ) {
    }

    public record LinearSnapshotView(
        String label,
        String status,
        String freshness,
        OffsetDateTime capturedAt,
        UUID sourceVersionId
    ) {
    }

    public record CertificationSummaryView(
        UUID id,
        int version,
        String decision,
        String checksum,
        OffsetDateTime createdAt,
        String observations,
        int terminalItemCount,
        int totalItemCount,
        boolean superseded
    ) {
    }

    public record NotificationView(
        UUID id,
        String category,
        String businessState,
        String transportStatus,
        String recipientSummary,
        OffsetDateTime createdAt,
        OffsetDateTime lastAttemptAt,
        String errorCategory,
        UUID correlationId
    ) {
    }

    public record TimelineEventView(
        UUID id,
        String label,
        String state,
        String actorDisplay,
        OffsetDateTime recordedAt,
        OffsetDateTime representedAt,
        UUID correlationId
    ) {
    }

    public record ConfirmationHistoryItem(
        UUID id,
        int version,
        String state,
        OffsetDateTime dueAt,
        OffsetDateTime createdAt,
        UUID supersedesRequestId
    ) {
    }

    public record RecipientDisplay(String display, String roleReason) {
    }

    public record ConfirmationPreviewView(
        List<String> sourceVersionIds,
        List<RecipientDisplay> toRecipients,
        List<RecipientDisplay> ccRecipients,
        List<RecipientDisplay> eligibleConfirmers,
        String quorumDescription,
        OffsetDateTime defaultDueAt,
        boolean ready,
        List<String> blockers
    ) {
    }

    public record InboundReviewView(
        UUID id,
        @Schema(allowableValues = {"INBOUND_MESSAGE", "MANUAL_EVIDENCE"})
        String reviewKind,
        @Schema(allowableValues = {
            "VERIFIED_REPLY", "AMBIGUOUS_REPLY", "QUARANTINED", "MANUAL_EVIDENCE"
        })
        String source,
        @Schema(allowableValues = {"VERIFIED", "PARTIAL", "UNAVAILABLE", "FAILED"})
        String authenticationConfidence,
        @Schema(allowableValues = {"PENDING", "APPROVED", "REJECTED", "QUARANTINED"})
        String reviewStatus,
        @Schema(allowableValues = {"ELIGIBLE", "INELIGIBLE", "UNKNOWN"})
        String senderEligibility,
        int version,
        boolean assignedToCurrentActor,
        String assignmentReason,
        OffsetDateTime representedAt,
        OffsetDateTime recordedAt,
        long ageSeconds,
        @Schema(allowableValues = {"NEW", "AGING", "OVERDUE", "RESOLVED"})
        String agingStatus,
        String safeSummary,
        String reason,
        UUID auditReference
    ) {
    }

    public record InboundMessageReviewInput(
        @Min(0) int expectedReviewVersion,
        @NotBlank @Pattern(regexp =
            "ACCEPT_INTERPRETATION|REJECT_INTERPRETATION|QUARANTINE")
        String decision,
        @NotBlank @Size(max = 10_000) String reasoning
    ) {
    }

    public record InboundAuthenticationInput(
        @NotBlank @Pattern(regexp = "PASS|FAIL|UNAVAILABLE") String spf,
        @NotBlank @Pattern(regexp = "PASS|FAIL|UNAVAILABLE") String dkim,
        @NotBlank @Pattern(regexp = "PASS|FAIL|UNAVAILABLE") String dmarc
    ) {
    }

    public record InboundMessageRecordInput(
        @Min(0) long expectedMonthVersion,
        UUID requestId,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._:-]{16,128}")
        String providerMessageFingerprint,
        @Size(max = 255) String providerMessageId,
        @Size(max = 255) String providerThreadId,
        @NotBlank @Email @Size(max = 320)
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        String senderAddress,
        @Size(max = 768)
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        String rawReference,
        @Pattern(regexp = "[0-9a-f]{64}")
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        String rawSha256,
        @Pattern(regexp = "[0-9a-f]{64}") String inReplyToHash,
        @Pattern(regexp = "[0-9a-f]{64}") String referencesHash,
        @NotNull @Valid InboundAuthenticationInput authentication,
        @NotBlank @Pattern(regexp =
            "EXPLICIT_CONFIRM|EXPLICIT_CORRECTION|EXPLICIT_REJECT|AMBIGUOUS|"
                + "AUTO_REPLY|RECEIPT|FORWARDED|UNMATCHED|MALFORMED")
        String classifiedIntent,
        @NotNull @PastOrPresent OffsetDateTime providerReceivedAt
    ) {
    }

    public record ManualEvidenceRecordInput(
        @Min(0) long expectedMonthVersion,
        UUID requestId,
        @NotNull UUID artifactId,
        @NotBlank @Pattern(regexp =
            "EML|MSG|PDF|SCREENSHOT|PROVIDER_EXPORT")
        String evidenceFormat,
        @NotBlank @Email @Size(max = 320)
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        String senderAddress,
        @NotEmpty @Size(max = 200)
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        List<@Email @Size(max = 320) String> recipients,
        @NotBlank @Size(max = 2_000)
        @Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
        String subject,
        @NotBlank @Size(max = 255) String messageId,
        @NotNull @PastOrPresent OffsetDateTime sentOrReceivedAt,
        @NotBlank @Pattern(regexp =
            "CONFIRM|REQUEST_CORRECTION|REJECT")
        String representedDecision,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String fileHash
    ) {
    }

    public record ManualEvidenceReviewInput(
        @Min(0) int expectedReviewVersion,
        @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
        @NotBlank @Size(max = 10_000) String reasoning
    ) {
    }

    public record BaselineView(
        UUID id,
        UUID versionId,
        String checksum,
        boolean frozen
    ) {
    }

    public record MonthCertificationView(
        UUID monthId,
        UUID engagementId,
        String monthLabel,
        String lifecycleState,
        long version,
        boolean stale,
        boolean locked,
        OffsetDateTime lastEvaluatedAt,
        BaselineView baseline,
        CertificationPermissions permissions,
        List<SafeEvidenceReference> evidenceChoices,
        SubmissionView submission,
        List<DeliverableCertificationView> deliverables,
        List<ClarificationView> clarifications,
        CertificationSummaryView summary,
        List<LinearSnapshotView> linearSnapshots,
        ConfirmationPreviewView confirmationPreview,
        List<ConfirmationHistoryItem> confirmationHistory,
        List<NotificationView> notifications,
        List<TimelineEventView> timeline,
        List<InboundReviewView> inboundReviews
    ) {
    }

    public record ReadinessBlocker(
        String code,
        String message,
        String severity,
        String owner,
        String actionLabel,
        String actionPath
    ) {
    }

    public record ReadinessPillar(
        String key,
        String label,
        String status,
        String sourceVersionId,
        String freshness,
        OffsetDateTime checkedAt,
        List<ReadinessBlocker> blockers
    ) {
    }

    public record ReadinessView(
        UUID monthId,
        long version,
        String inputManifestVersion,
        String status,
        OffsetDateTime evaluatedAt,
        boolean stale,
        List<ReadinessPillar> pillars,
        List<ReadinessBlocker> blockers,
        String f05HandoffStatus
    ) {
    }

    public record ConfirmationActionView(
        UUID id,
        String decision,
        String actorDisplay,
        String actorRoleReason,
        String source,
        String comment,
        OffsetDateTime recordedAt,
        OffsetDateTime representedAt,
        UUID auditReference
    ) {
    }

    public record VersionDiffItem(
        String fieldLabel,
        String previousValue,
        String currentValue
    ) {
    }

    public record ConfirmationRecipient(
        String display,
        String roleReason,
        String kind
    ) {
    }

    public record ConfirmationProjectChoice(
        UUID id,
        String display,
        String roleReason
    ) {
    }

    public record ConfirmationScopeSource(
        String kind,
        UUID id,
        Integer version,
        String checksum,
        String freshness,
        String display
    ) {
    }

    public record ConfirmationRequestView(
        UUID id,
        UUID monthId,
        String engagementLabel,
        String monthLabel,
        int version,
        String state,
        OffsetDateTime dueAt,
        OffsetDateTime createdAt,
        boolean locked,
        boolean stale,
        boolean eligible,
        String eligibilityMessage,
        boolean projectIdRequired,
        List<ConfirmationProjectChoice> eligibleProjects,
        String scopeChecksum,
        List<String> sourceVersionIds,
        List<ConfirmationScopeSource> scopeSources,
        List<ConfirmationRecipient> recipients,
        String quorumDescription,
        String transportStatus,
        String providerConfiguration,
        List<VersionDiffItem> diff,
        List<ConfirmationActionView> actions,
        List<NotificationView> notifications,
        List<ConfirmationHistoryItem> lineage,
        CertificationPermissions permissions
    ) {
    }
}
