package com.vms.workflow.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class WorkforceDtos {
    private WorkforceDtos() {
    }

    public record EmployeeView(
        UUID id,
        UUID organizationId,
        String employeeNumber,
        String firstName,
        String lastName,
        String displayName,
        String workEmail,
        String employmentStatus,
        LocalDate joinDate,
        LocalDate exitDate,
        String activationStatus,
        String attendanceSourceMode,
        LocalDate validFrom,
        LocalDate validTo,
        int version
    ) {
    }

    public record CreateEmployeeRequest(
        @NotNull UUID organizationId,
        @NotBlank String employeeNumber,
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank String displayName,
        @NotBlank @Email String workEmail,
        @NotNull LocalDate joinDate,
        String designation,
        @NotBlank String attendanceSourceMode,
        UUID userProfileId
    ) {
    }

    public record EmployeeLifecycleRequest(
        @NotNull LocalDate effectiveFrom,
        @NotBlank String employmentStatus,
        @NotBlank String activationStatus,
        LocalDate exitDate,
        @NotBlank String reason
    ) {
    }

    public record AllocationRequest(
        @NotNull UUID engagementId,
        @NotNull UUID projectId,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        @NotNull @DecimalMin("0.01") @DecimalMax("100.00") BigDecimal allocationPercent,
        String roleOnProject
    ) {
    }

    public record AllocationView(
        UUID id,
        UUID employeeId,
        UUID engagementId,
        UUID projectId,
        LocalDate validFrom,
        LocalDate validTo,
        BigDecimal allocationPercent,
        String roleOnProject,
        String status
    ) {
    }

    public record LeaveBalanceView(
        UUID leaveTypeId,
        String leaveTypeCode,
        String leaveTypeName,
        boolean paid,
        BigDecimal availableUnits
    ) {
    }

    public record LeaveRequest(
        @NotNull UUID leaveTypeId,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        @NotNull @DecimalMin("0.5") BigDecimal units,
        @NotBlank String reason,
        @NotBlank String idempotencyKey
    ) {
    }

    public record LeaveRequestView(
        UUID id,
        UUID employeeId,
        UUID leaveTypeId,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal units,
        BigDecimal paidUnits,
        BigDecimal lwpUnits,
        String reason,
        String status,
        String idempotencyKey,
        OffsetDateTime createdAt
    ) {
    }
}
