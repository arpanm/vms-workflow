package com.vms.workflow.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GreytHrDtos {
    private GreytHrDtos() {
    }

    public record CapabilityCertificationRequest(
        @NotNull UUID organizationId,
        @NotNull @Size(min = 1, max = 20) List<
            @Pattern(regexp = "EMPLOYEES|ATTENDANCE|LEAVE") String> capabilities
    ) {
    }

    public record CapabilityView(
        UUID connectionId,
        String provider,
        String status,
        List<String> discoveredCapabilities,
        UUID certificationId,
        OffsetDateTime certifiedAt,
        UUID probeEvidenceId,
        String probeEvidenceHash,
        OffsetDateTime probedAt,
        String adapterMode
    ) {
    }

    public record SyncRequest(
        @NotNull LocalDate dateFrom,
        @NotNull LocalDate dateTo
    ) {
    }

    public record SyncRunView(
        UUID id,
        UUID connectionId,
        String status,
        LocalDate dateFrom,
        LocalDate dateTo,
        int employeeCount,
        int attendanceCount,
        int leaveCount,
        int conflictCount,
        int pageCount,
        String errorCode,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime lastSuccessfulSyncAt,
        boolean stale
    ) {
    }

    public record ReconciliationDecisionRequest(
        @NotBlank @Pattern(regexp = "USE_GREYTHR|KEEP_INTERNAL") String decision,
        @NotBlank @Size(max = 4_000) String reason
    ) {
    }

    public record ReconciliationView(
        UUID id,
        UUID syncRunId,
        UUID employeeId,
        LocalDate workDate,
        String conflictType,
        String status,
        String decisionReason,
        OffsetDateTime decidedAt
    ) {
    }

    public record CutoverRequest(
        @NotNull UUID employeeId,
        @NotNull LocalDate effectiveFrom,
        @NotBlank @Size(max = 4_000) String reason
    ) {
    }

    public record CutoverView(
        UUID id,
        UUID employeeId,
        String mode,
        String authoritativeSource,
        LocalDate effectiveFrom,
        UUID certificationId
    ) {
    }

    public record HealthView(
        UUID connectionId,
        String status,
        OffsetDateTime lastAttemptAt,
        OffsetDateTime lastSuccessAt,
        boolean stale,
        String lastErrorCode,
        int pendingReconciliations
    ) {
    }

    public record RecordedPage(
        int pageNumber,
        String responseMode,
        OffsetDateTime sourceUpdatedAt,
        Map<String, Object> payload
    ) {
    }
}
