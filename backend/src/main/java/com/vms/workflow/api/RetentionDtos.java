package com.vms.workflow.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class RetentionDtos {
    private RetentionDtos() {
    }

    public record ScheduleInput(
        @NotNull UUID organizationId,
        @NotBlank @Size(max = 80) String recordClass,
        @Min(1) @Max(36_500) int retentionDays,
        @NotBlank @Size(max = 200) String policyReference,
        @NotNull OffsetDateTime effectiveFrom
    ) {
    }

    public record DryRunInput(
        @NotNull UUID organizationId,
        @NotBlank @Size(max = 80) String recordClass,
        @NotNull OffsetDateTime asOf
    ) {
    }

    public record HoldInput(
        @NotBlank @Size(max = 100) String reasonCode
    ) {
    }

    public record ReleaseInput(
        @NotBlank @Size(max = 100) String reasonCode
    ) {
    }

    public record RecoveryInput(
        @NotBlank @Size(max = 100) String reasonCode
    ) {
    }
}
