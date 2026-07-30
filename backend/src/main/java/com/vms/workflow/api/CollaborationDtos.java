package com.vms.workflow.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class CollaborationDtos {
    private CollaborationDtos() {
    }

    public record OnboardClientInput(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}") String clientCode,
        @NotBlank @Size(max = 255) String legalName,
        @NotBlank @Size(max = 255) String displayName,
        @Size(max = 255) String primaryDomain,
        @NotBlank @Size(max = 64) String timezone,
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String engagementCode,
        @NotBlank @Size(max = 255) String engagementName,
        @NotNull UUID vendorOrganizationId,
        UUID procurementOrganizationId,
        @NotBlank @Pattern(regexp =
            "DEDICATED_RESOURCE_MONTHLY|FIXED_COST_DELIVERY|STAFF_AUGMENTATION|HYBRID")
        String engagementModel,
        @NotNull LocalDate startDate,
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String projectCode,
        @NotBlank @Size(max = 255) String projectName
    ) {
    }

    public record ClientView(
        UUID organizationId,
        String clientCode,
        String legalName,
        String displayName,
        String status,
        UUID engagementId,
        String engagementCode,
        UUID projectId,
        String projectCode,
        int provisionedMonthCount
    ) {
    }

    public record ClientUserInput(
        @NotBlank @Size(max = 255) String identitySubject,
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 255) String displayName,
        @NotEmpty @Size(max = 12) List<
            @NotBlank @Pattern(regexp =
                "ORG_ADMIN|CLIENT_PRODUCT_OWNER|CLIENT_APPROVER|AUDITOR_READONLY")
                String> roleCodes,
        @NotNull LocalDate validFrom,
        LocalDate validTo
    ) {
    }

    public record RoleGrantInput(
        @NotBlank @Size(max = 64) String roleCode,
        @NotBlank @Pattern(regexp = "ORGANIZATION|ENGAGEMENT|PROJECT")
        String scopeType,
        @NotNull UUID scopeId,
        @NotNull LocalDate validFrom,
        LocalDate validTo
    ) {
    }

    public record ClientUserView(
        UUID userProfileId,
        UUID organizationId,
        String identitySubject,
        String email,
        String displayName,
        String status,
        List<String> roleCodes,
        List<String> permissions
    ) {
    }

    public record WorkItemLinkInput(
        @NotBlank @Pattern(regexp =
            "DOCUMENT|PRD|USER_STORY|FIGMA|PROTOTYPE|LINEAR|JIRA|"
                + "CODE_REVIEW|COMMIT|TEST_CASES|TEST_RUN|OTHER")
        String linkType,
        @NotBlank @Size(max = 160) String label,
        @NotBlank @Size(max = 2048) @Pattern(regexp = "https://.*") String url
    ) {
    }

    public record WorkItemAssignmentInput(
        @NotNull UUID userProfileId,
        @NotBlank @Pattern(regexp =
            "DEVELOPER|QA|PRODUCT_MANAGER|PROGRAM_MANAGER|UX_DESIGNER|"
                + "DEVOPS|DATA_ANALYST|OTHER")
        String discipline
    ) {
    }

    public record CreateWorkItemInput(
        @NotNull UUID engagementId,
        @NotNull UUID projectId,
        UUID engagementMonthId,
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_-]{1,63}") String workItemCode,
        @NotBlank @Size(max = 256) String title,
        @NotBlank @Size(max = 10_000) String description,
        @NotNull @Size(max = 10_000) String workflowDescription,
        @NotNull @Size(max = 10_000) String acceptanceCriteria,
        @NotBlank @Pattern(regexp = "P0|P1|P2|P3") String priority,
        @NotBlank @Pattern(regexp =
            "BACKLOG|PLANNED|APPROVED|IN_PROGRESS|BLOCKED|DELIVERED|"
                + "PARTIALLY_DELIVERED|NOT_DELIVERED|CANCELLED")
        String lifecycleStatus,
        boolean createdOnBehalfOfClient,
        @NotNull @Size(max = 50) List<@Valid WorkItemLinkInput> links,
        @NotNull @Size(max = 50) List<@Valid WorkItemAssignmentInput> assignments
    ) {
    }

    public record UpdateWorkItemInput(
        long expectedVersion,
        @NotBlank @Size(max = 256) String title,
        @NotBlank @Size(max = 10_000) String description,
        @NotNull @Size(max = 10_000) String workflowDescription,
        @NotNull @Size(max = 10_000) String acceptanceCriteria,
        @NotBlank @Pattern(regexp = "P0|P1|P2|P3") String priority,
        UUID engagementMonthId
    ) {
    }

    public record DeliveryStatusInput(
        long expectedVersion,
        @NotBlank @Pattern(regexp =
            "BACKLOG|PLANNED|APPROVED|IN_PROGRESS|BLOCKED|DELIVERED|"
                + "PARTIALLY_DELIVERED|NOT_DELIVERED|CANCELLED")
        String lifecycleStatus,
        @Size(max = 10_000) String deliverySummary
    ) {
    }

    public record CommentInput(
        @NotBlank @Size(max = 10_000) String body,
        @NotNull @Size(max = 100) List<@NotNull UUID> mentionedUserIds
    ) {
    }

    public record EstimateInput(
        @NotNull UUID userProfileId,
        @NotNull @DecimalMin("0.01") @DecimalMax("100000") BigDecimal hours,
        @Size(max = 2_000) String note
    ) {
    }

    public record EffortInput(
        @NotNull UUID userProfileId,
        @NotNull LocalDate workDate,
        @NotNull @DecimalMin("0.01") @DecimalMax("24") BigDecimal hours,
        @Size(max = 2_000) String note
    ) {
    }

    public record ApprovalInput(
        long expectedVersion,
        @NotBlank @Pattern(regexp = "PLAN_L1|DELIVERY_L1|DELIVERY_L2") String stage,
        @NotBlank @Pattern(regexp = "APPROVED|REJECTED|CHANGES_REQUESTED") String decision,
        Integer stackRank,
        @Size(max = 4_000) String comment
    ) {
    }

    public record AssignmentInput(
        @NotNull UUID userProfileId,
        @NotBlank @Pattern(regexp =
            "DEVELOPER|QA|PRODUCT_MANAGER|PROGRAM_MANAGER|UX_DESIGNER|"
                + "DEVOPS|DATA_ANALYST|OTHER")
        String discipline
    ) {
    }

    public record WorkItemLinkView(
        UUID id, String linkType, String label, String url,
        String createdBySubject, OffsetDateTime createdAt
    ) {
    }

    public record WorkItemAssignmentView(
        UUID id, UUID userProfileId, String displayName, String email,
        String discipline, String status, OffsetDateTime assignedAt
    ) {
    }

    public record CommentView(
        UUID id, String body, String authorSubject,
        List<UUID> mentionedUserIds, OffsetDateTime createdAt
    ) {
    }

    public record EstimateView(
        UUID id, UUID userProfileId, String displayName, BigDecimal hours,
        String note, boolean deleted, OffsetDateTime createdAt
    ) {
    }

    public record EffortView(
        UUID id, UUID userProfileId, String displayName, LocalDate workDate,
        BigDecimal hours, String note, OffsetDateTime createdAt
    ) {
    }

    public record ApprovalView(
        UUID id, String stage, String decision, Integer stackRank,
        String comment, String actorSubject, long workItemVersion,
        OffsetDateTime decidedAt
    ) {
    }

    public record WorkItemView(
        UUID id,
        UUID engagementId,
        UUID projectId,
        UUID engagementMonthId,
        LocalDate monthStartDate,
        String workItemCode,
        String title,
        String description,
        String workflowDescription,
        String acceptanceCriteria,
        String priority,
        Integer stackRank,
        String lifecycleStatus,
        String deliverySummary,
        boolean createdOnBehalfOfClient,
        long version,
        String createdBySubject,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        BigDecimal totalEstimateHours,
        BigDecimal totalEffortHours,
        List<WorkItemLinkView> links,
        List<WorkItemAssignmentView> assignments,
        List<CommentView> comments,
        List<EstimateView> estimates,
        List<EffortView> efforts,
        List<ApprovalView> approvals
    ) {
    }
}
