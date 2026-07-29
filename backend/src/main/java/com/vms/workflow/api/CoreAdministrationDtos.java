package com.vms.workflow.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CoreAdministrationDtos {
    private CoreAdministrationDtos() {
    }

    public record EngagementAdministrationView(
        UUID id,
        String engagementCode,
        String name,
        String status,
        UUID defaultProjectId,
        UUID configurationVersionId,
        long version
    ) {
    }

    public record UpdateEngagementInput(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Pattern(regexp = "DRAFT|ACTIVE|SUSPENDED|COMPLETED|ARCHIVED")
        String status,
        UUID defaultProjectId,
        long expectedVersion
    ) {
    }

    public record ConfigurationView(
        UUID id,
        UUID engagementId,
        int version,
        String status,
        LocalDate validFrom,
        LocalDate validTo,
        String timezone,
        Integer planningDueDay,
        Integer certificationDueDay,
        Integer confirmationDueDay,
        Map<String, Object> reopenPolicy,
        Map<String, Object> notificationPolicy,
        OffsetDateTime publishedAt
    ) {
    }

    public record PublishConfigurationInput(
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        @NotBlank @Size(max = 64) String timezone,
        Integer planningDueDay,
        Integer certificationDueDay,
        Integer confirmationDueDay,
        @NotNull Map<String, Object> reopenPolicy,
        @NotNull Map<String, Object> notificationPolicy,
        long expectedEngagementVersion
    ) {
    }

    public record ContactMemberView(
        UUID id,
        UUID userProfileId,
        String email,
        String displayName,
        String roleAttribution,
        boolean verified,
        LocalDate validFrom,
        LocalDate validTo,
        String status
    ) {
    }

    public record ContactGroupView(
        UUID id,
        UUID engagementId,
        UUID projectId,
        String code,
        String name,
        String groupType,
        String status,
        long version,
        List<ContactMemberView> members
    ) {
    }

    public record CreateContactGroupInput(
        UUID projectId,
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Pattern(regexp =
            "CLIENT_PRODUCT_OWNERS|CLIENT_APPROVERS|VENDOR_DELIVERY|"
                + "VENDOR_HR|VENDOR_FINANCE|PROCUREMENT_CC|ESCALATION|"
                + "AUDIT_OBSERVERS|OTHER")
        String groupType
    ) {
    }

    public record AddContactMemberInput(
        UUID userProfileId,
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 255) String displayName,
        @NotBlank @Size(max = 80) String roleAttribution,
        boolean verified,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        long expectedGroupVersion
    ) {
    }

    public record EligibleUserView(
        UUID id,
        UUID organizationId,
        String displayName,
        String email,
        List<String> activeRoleCodes
    ) {
    }

    public record ApprovalStageInput(
        @NotBlank @Size(max = 160) String name,
        String roleCode,
        UUID contactGroupId,
        UUID explicitAssigneeId,
        String permissionCode,
        @NotBlank @Pattern(regexp = "ANY_ONE|ALL|N_OF_M") String quorumMode,
        int quorumRequired,
        boolean allowDelegation,
        Integer dueDurationHours
    ) {
    }

    public record CreateApprovalPolicyInput(
        UUID projectId,
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Pattern(regexp =
            "PLAN_APPROVAL|LEAVE_APPROVAL|REGULARIZATION|"
                + "ATTENDANCE_CORRECTION|DELIVERY_CERTIFICATION|"
                + "MONTH_CONFIRMATION|REOPEN|PROCUREMENT_EXCEPTION")
        String actionType,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        boolean prohibitSelfApproval,
        boolean evidenceRequired,
        @NotNull Map<String, Object> rules,
        @NotEmpty @Size(max = 12) List<@Valid ApprovalStageInput> stages
    ) {
    }

    public record ApprovalStageView(
        UUID id,
        int stageOrder,
        String name,
        String roleCode,
        UUID contactGroupId,
        UUID explicitAssigneeId,
        String permissionCode,
        String quorumMode,
        int quorumRequired,
        boolean allowDelegation,
        Integer dueDurationHours
    ) {
    }

    public record ApprovalPolicyView(
        UUID id,
        UUID engagementId,
        UUID projectId,
        String code,
        String name,
        String actionType,
        String status,
        long version,
        UUID policyVersionId,
        int policyVersion,
        String versionStatus,
        LocalDate validFrom,
        LocalDate validTo,
        boolean prohibitSelfApproval,
        boolean evidenceRequired,
        Map<String, Object> rules,
        List<ApprovalStageView> stages
    ) {
    }

    public record PublishApprovalPolicyInput(
        long expectedPolicyVersion
    ) {
    }

    public record CreateApprovalRequestInput(
        @NotNull UUID policyId,
        @NotNull UUID objectId,
        @NotBlank @Size(max = 160) String idempotencyKey
    ) {
    }

    public record ApprovalActionInput(
        @NotBlank
        @Pattern(regexp =
            "APPROVED|REJECTED|CHANGES_REQUESTED|CANCELLED")
        String decision,
        @Size(max = 1000) String reason,
        UUID delegationId,
        @NotBlank @Size(max = 160) String idempotencyKey,
        long expectedRequestVersion
    ) {
    }

    public record ReviseApprovalPolicyInput(
        @NotBlank @Size(max = 160) String name,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        boolean prohibitSelfApproval,
        boolean evidenceRequired,
        @NotNull Map<String, Object> rules,
        @NotEmpty @Size(max = 12) List<@Valid ApprovalStageInput> stages,
        long expectedPolicyVersion
    ) {
    }

    public record ApprovalActionView(
        UUID id,
        int stageOrder,
        String decision,
        UUID actorUserId,
        String actorSubject,
        Map<String, Object> authoritySnapshot,
        UUID delegatedFromUserId,
        UUID delegationId,
        String source,
        String reason,
        OffsetDateTime actedAt
    ) {
    }

    public record ApprovalRequestView(
        UUID id,
        UUID policyId,
        UUID policyVersionId,
        UUID engagementId,
        UUID projectId,
        String objectType,
        UUID objectId,
        long objectVersion,
        String objectHash,
        String requiredPermissionCode,
        int currentStageOrder,
        String status,
        long version,
        String requestedBySubject,
        OffsetDateTime requestedAt,
        boolean evidenceRequired,
        List<ApprovalStageView> stages,
        List<ApprovalActionView> actions
    ) {
    }

    public record CreateDelegationInput(
        @NotNull UUID organizationId,
        UUID projectId,
        @NotNull UUID delegatorUserId,
        @NotNull UUID delegateUserId,
        @NotEmpty List<@NotBlank @Size(max = 80) String> actionCodes,
        @NotNull OffsetDateTime validFrom,
        @NotNull @Future OffsetDateTime validTo,
        @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record DelegationView(
        UUID id,
        UUID organizationId,
        UUID engagementId,
        UUID projectId,
        UUID delegatorUserId,
        String delegatorName,
        UUID delegateUserId,
        String delegateName,
        List<String> actionCodes,
        OffsetDateTime validFrom,
        OffsetDateTime validTo,
        String status,
        String reason,
        long version
    ) {
    }

    public record RevokeDelegationInput(
        @NotBlank @Size(max = 500) String reason,
        long expectedVersion
    ) {
    }

    public record MonthTransitionInput(
        @NotBlank @Size(max = 40) String targetState,
        @NotBlank @Size(max = 1000) String reason,
        long expectedVersion
    ) {
    }

    public record MonthTransitionView(
        UUID id,
        UUID engagementMonthId,
        String fromState,
        String toState,
        long fromVersion,
        long toVersion,
        String actorSubject,
        String reason,
        UUID correlationId,
        OffsetDateTime transitionedAt
    ) {
    }
}
