package com.vms.workflow.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class DeliveryDtos {
    private DeliveryDtos() {
    }

    public record RecipientRequest(
        @NotNull @Size(max = 50)
        List<@NotBlank @Email @Size(max = 254) String> arrowFoundry,
        @NotNull @Size(max = 50)
        List<@NotBlank @Email @Size(max = 254) String> relianceStakeholders,
        @NotNull @Size(max = 50)
        List<@NotBlank @Email @Size(max = 254) String> procurementCc
    ) {
    }

    public record CriterionRequest(
        @NotBlank @Size(max = 2_000) String statement,
        @NotBlank @Size(max = 1_000) String validationMethod,
        @NotBlank @Size(max = 2_000) String expectedResult,
        boolean mandatory
    ) {
    }

    public record DependencyRequest(
        @NotBlank @Pattern(regexp = "INTERNAL|LINEAR|EXTERNAL") String type,
        UUID dependsOnDeliverableId,
        @NotBlank @Size(max = 2_000) String description,
        @NotBlank @Size(max = 255) String ownerSubject,
        @NotNull LocalDate targetResolutionDate,
        boolean blocking
    ) {
    }

    public record AssignmentRequest(
        @NotNull UUID employeeId,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @Size(max = 2_000) String exceptionReason
    ) {
    }

    public record DeliverableRequest(
        @NotBlank @Size(max = 64)
        @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String deliverableCode,
        @NotBlank @Size(max = 256) String title,
        @NotBlank @Size(max = 10_000) String description,
        @NotBlank @Size(max = 5_000) String businessObjective,
        @NotNull UUID projectId,
        @NotBlank @Size(max = 255) String productOwnerSubject,
        @NotBlank @Size(max = 255) String vendorOwnerSubject,
        @NotBlank @Pattern(regexp = "P0|P1|P2|P3") String priority,
        @NotNull LocalDate targetCompletionDate,
        @NotBlank @Size(max = 5_000) String evidenceExpectations,
        boolean dependencyNoneDeclared,
        @NotBlank @Size(max = 5_000) String riskAndAssumptions,
        @NotBlank @Pattern(regexp =
            "FEATURE|PLATFORM|INTEGRATION|QUALITY|OPERATIONS|RESEARCH_POC|SUPPORT|OTHER")
        String deliveryCategory,
        @Size(max = 2_000) String linkExceptionReason,
        @NotEmpty @Size(max = 100) List<@Valid CriterionRequest> criteria,
        @NotNull @Size(max = 100) List<@Valid DependencyRequest> dependencies,
        @NotEmpty @Size(max = 200) List<@Valid AssignmentRequest> assignments
    ) {
    }

    public record CreatePlanRequest(
        @NotNull UUID engagementMonthId,
        @NotBlank @Size(max = 256) String title,
        @NotBlank @Size(max = 10_000) String summary,
        @NotBlank @Size(max = 10_000) String businessOutcomes,
        @NotBlank @Size(max = 255) String coordinatorSubject,
        @NotBlank @Pattern(regexp =
            "ON_TIME|LATE_APPROVED|HISTORICAL_RECONSTRUCTED") String baselineType,
        @NotBlank @Pattern(regexp = "ANY_ONE|ALL|N_OF_M") String quorumMode,
        @Positive int quorumRequired,
        @NotEmpty @Size(max = 25)
        List<@NotBlank @Size(max = 255) String> approverSubjects,
        @NotNull @Valid RecipientRequest recipients,
        @NotEmpty @Size(max = 200) List<@Valid DeliverableRequest> deliverables
    ) {
    }

    public record ApprovalRequest(
        @NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
        @Size(max = 4_000) String comment
    ) {
    }

    public record RevisionRequest(
        @NotBlank @Size(max = 4_000) String reason,
        @NotBlank @Size(max = 4_000) String impact
    ) {
    }

    public record PlanSummaryView(
        UUID id,
        UUID engagementMonthId,
        UUID currentVersionId,
        int version,
        String state,
        String title,
        String baselineType,
        String checksum,
        int deliverableCount,
        int approvedCount,
        int requiredApprovals,
        OffsetDateTime createdAt,
        OffsetDateTime frozenAt
    ) {
    }

    public record RecipientView(
        List<String> arrowFoundry,
        List<String> relianceStakeholders,
        List<String> procurementCc
    ) {
    }

    public record CriterionView(
        UUID id,
        int sequence,
        String statement,
        String validationMethod,
        String expectedResult,
        boolean mandatory
    ) {
    }

    public record DependencyView(
        UUID id,
        String type,
        UUID dependsOnDeliverableId,
        String description,
        String ownerSubject,
        LocalDate targetResolutionDate,
        boolean blocking
    ) {
    }

    public record AssignmentView(
        UUID id,
        UUID employeeId,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String exceptionReason
    ) {
    }

    public record ApprovalView(
        UUID id,
        String approverSubject,
        String decision,
        String signedChecksum,
        String comment,
        OffsetDateTime decidedAt
    ) {
    }

    public record DeliverableView(
        UUID id,
        UUID deliverableVersionId,
        String deliverableCode,
        String title,
        String description,
        String businessObjective,
        UUID projectId,
        String productOwnerSubject,
        String vendorOwnerSubject,
        String priority,
        LocalDate targetCompletionDate,
        String evidenceExpectations,
        boolean dependencyNoneDeclared,
        String riskAndAssumptions,
        String deliveryCategory,
        String linkExceptionReason,
        String executionProjection,
        List<CriterionView> criteria,
        List<DependencyView> dependencies,
        List<AssignmentView> assignments,
        List<LinearDtos.IssueLinkView> linearLinks
    ) {
    }

    public record PlanView(
        UUID id,
        UUID engagementMonthId,
        UUID currentVersionId,
        int version,
        String state,
        String title,
        String summary,
        String businessOutcomes,
        String coordinatorSubject,
        String baselineType,
        String checksum,
        UUID priorVersionId,
        String revisionReason,
        String revisionImpact,
        String createdBySubject,
        OffsetDateTime createdAt,
        OffsetDateTime submittedAt,
        OffsetDateTime frozenAt,
        List<String> completenessBlockers,
        RecipientView recipients,
        List<DeliverableView> deliverables,
        List<ApprovalView> approvals,
        UUID baselineId,
        @Schema(nullable = true,
            allowableValues = {"PENDING", "SENT", "RETRY", "DEAD_LETTER"})
        String commitmentStatus
    ) {
    }
}
