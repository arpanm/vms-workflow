package com.vms.workflow.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public final class MigrationDtos {
    private MigrationDtos() {
    }

    public record UploadMetadata(
        @NotNull UUID engagementId,
        @NotNull UUID organizationId,
        UUID engagementMonthId,
        @NotBlank String templateCode,
        @Pattern(regexp = "1") String templateVersion,
        @Pattern(regexp = "DRY_RUN|COMMIT|REPROCESS_REJECTS|SUPERSEDE")
        String mode,
        boolean partialCommit,
        UUID parentJobId,
        UUID priorJobId,
        @NotBlank @Pattern(regexp =
            "GREYTHR_EXPORT|LINEAR_API|LINEAR_EXPORT|ORIGINAL_EMAIL|SIGNED_DOCUMENT|APPROVED_SPREADSHEET|MANUAL_RECONSTRUCTION|OTHER")
        String sourceType,
        @NotBlank @Pattern(regexp = "HIGH|MEDIUM|LOW|UNVERIFIED")
        String confidence,
        @NotBlank @Size(max = 300) String sourceDescription
    ) {
        public UploadMetadata {
            templateVersion = templateVersion == null ? "1" : templateVersion;
            mode = mode == null ? "DRY_RUN" : mode;
        }

        public UploadMetadata(
            UUID engagementId,
            UUID organizationId,
            UUID engagementMonthId,
            String templateCode,
            String templateVersion,
            String mode,
            boolean partialCommit,
            UUID parentJobId,
            UUID priorJobId
        ) {
            this(engagementId, organizationId, engagementMonthId, templateCode,
                templateVersion, mode, partialCommit, parentJobId, priorJobId,
                "OTHER", "UNVERIFIED", "Programmatic governed migration");
        }
    }

    public record VersionInput(long expectedVersion) {
    }

    public record CommitInput(
        long expectedVersion,
        @NotNull Boolean partialCommit
    ) {
    }

    public record ReasonInput(
        long expectedVersion,
        @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record ApprovalInput(
        long expectedVersion,
        String role,
        @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
        UUID reconciliationId,
        @Pattern(regexp = "[0-9a-f]{64}") String reconciliationHash,
        @Size(max = 1000) String reason
    ) {
    }

    public record ResolveConflictInput(
        long expectedVersion,
        @Pattern(regexp = "KEEP_EXISTING|REJECT|VERSIONED_SUPERSEDE")
        String decision,
        @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record RollbackInput(
        long expectedVersion,
        @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record SignOffInput(
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String reportHash,
        String role,
        @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
        @Size(max = 1000) String reason
    ) {
    }

    public record RetroRequestInput(
        @NotNull UUID engagementId,
        @NotNull UUID engagementMonthId,
        @Pattern(regexp = "COMMITMENT|CERTIFICATION|CONFIRMATION")
        String requestType,
        @NotNull LocalDate representedMonth,
        @NotBlank @Size(max = 1000) String reason,
        boolean originalActorUnavailable,
        @Size(max = 500) String delegationEvidenceReference
    ) {
    }

    public record RetroDecisionInput(
        long expectedVersion,
        @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
        @NotBlank @Size(max = 1000) String reason
    ) {
    }

    public record MonthTransitionInput(
        long expectedVersion,
        @NotBlank @Pattern(regexp =
            "HISTORICAL_PENDING_CERTIFICATION|HISTORICAL_PENDING_CONFIRMATION|CONFIRMED")
        String targetState,
        @NotBlank @Size(max = 1000) String reason
    ) {
    }
}
