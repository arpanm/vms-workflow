package com.vms.workflow.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class AttendanceDtos {
    private AttendanceDtos() {
    }

    public record PunchRequest(
        @NotNull UUID employeeId,
        @NotBlank String eventType,
        @NotBlank String idempotencyKey
    ) {
    }

    public record PunchView(
        UUID id,
        UUID employeeId,
        String eventType,
        OffsetDateTime occurredAt,
        LocalDate workDate,
        String source,
        String idempotencyKey,
        UUID sessionId,
        String sessionStatus,
        Integer netMinutes
    ) {
    }

    public record AttendanceDayView(
        @Schema(
            nullable = true,
            description = "Persisted attendance-day identifier; null for a read-only transient calculation."
        )
        UUID id,
        UUID employeeId,
        LocalDate workDate,
        String expectedClassification,
        int expectedMinutes,
        String sourceMode,
        int netMinutes,
        BigDecimal leaveUnits,
        String leaveTypeCode,
        String finalStatus,
        String exceptionCode,
        int calculationVersion,
        OffsetDateTime computedAt
    ) {
    }

    public record RegularizationRequest(
        @NotNull UUID employeeId,
        @NotNull LocalDate workDate,
        @NotBlank String reasonCode,
        @NotBlank String narrative,
        @NotBlank String requestedOutcome,
        @NotBlank String idempotencyKey
    ) {
    }

    public record RegularizationView(
        UUID id,
        UUID employeeId,
        LocalDate workDate,
        String reasonCode,
        String narrative,
        String requestedOutcome,
        String idempotencyKey,
        String status,
        OffsetDateTime createdAt
    ) {
    }

    public record CloseSnapshotRequest(@NotNull UUID engagementMonthId) {
    }

    public record ReopenSnapshotRequest(@NotBlank String reason) {
    }

    public record AttendanceSnapshotView(
        UUID id,
        UUID engagementMonthId,
        int version,
        String status,
        UUID supersedesId,
        OffsetDateTime closedAt,
        OffsetDateTime reopenedAt,
        String checksum,
        int dayCount
    ) {
    }
}
