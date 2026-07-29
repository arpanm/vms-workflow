package com.vms.workflow.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WorkforceAdministrationDtos {
    private WorkforceAdministrationDtos() {
    }

    public record EmployeeAliasInput(
        @NotBlank
        @Pattern(regexp = "HRIS_ID|EMAIL|BADGE|LEGACY_ID|OTHER")
        String aliasType,
        @NotBlank @Size(max = 320) String aliasValue,
        @NotNull LocalDate validFrom,
        LocalDate validTo
    ) {
    }

    public record EmployeeAliasView(
        UUID id,
        UUID employeeId,
        String aliasType,
        String aliasValue,
        LocalDate validFrom,
        LocalDate validTo,
        String status,
        OffsetDateTime createdAt
    ) {
    }

    public record DeliverableAllocationInput(
        @NotNull UUID projectAllocationId,
        @NotNull UUID deliverableId,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        @NotNull @DecimalMin("0.01") @DecimalMax("100.00")
        BigDecimal allocationPercent,
        @Size(max = 128) String roleOnDeliverable
    ) {
    }

    public record DeliverableAllocationView(
        UUID id,
        UUID employeeId,
        UUID projectAllocationId,
        UUID deliverableId,
        String deliverableCode,
        LocalDate validFrom,
        LocalDate validTo,
        BigDecimal allocationPercent,
        String roleOnDeliverable,
        String status
    ) {
    }

    public record CalendarWeekdayInput(
        @NotNull Integer isoWeekday,
        @NotBlank
        @Pattern(regexp = "WORKING|WEEKLY_OFF|HALF_DAY_EXPECTED")
        String classification,
        @NotNull Integer expectedMinutes
    ) {
    }

    public record CalendarHolidayInput(
        @NotNull LocalDate holidayDate,
        @NotBlank @Size(max = 128) String name,
        @NotBlank
        @Pattern(regexp = "HOLIDAY|HALF_DAY_EXPECTED")
        String classification,
        @NotNull Integer expectedMinutes
    ) {
    }

    public record PublishCalendarInput(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 64) String timezone,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        @NotNull Integer expectedFullMinutes,
        @NotNull Integer expectedHalfMinutes,
        @NotEmpty @Size(min = 7, max = 7)
        List<@Valid CalendarWeekdayInput> weekdays,
        List<@Valid CalendarHolidayInput> holidays
    ) {
    }

    public record CalendarVersionView(
        UUID id,
        UUID organizationId,
        String name,
        String timezone,
        int version,
        LocalDate validFrom,
        LocalDate validTo,
        int expectedFullMinutes,
        int expectedHalfMinutes,
        List<CalendarWeekdayInput> weekdays,
        List<CalendarHolidayInput> holidays
    ) {
    }

    public record PublishShiftPolicyInput(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Size(max = 64) String timezone,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        @NotNull LocalTime scheduledStartLocalTime,
        @NotNull LocalTime scheduledEndLocalTime,
        @NotNull LocalTime overnightCutoffLocalTime,
        @NotNull Integer expectedNetMinutes,
        @NotNull Integer maximumSessionMinutes,
        boolean allowSplitSessions,
        @NotNull Integer minimumBreakMinutes
    ) {
    }

    public record ShiftPolicyView(
        UUID id,
        UUID organizationId,
        String code,
        String name,
        String timezone,
        int version,
        LocalDate validFrom,
        LocalDate validTo,
        LocalTime scheduledStartLocalTime,
        LocalTime scheduledEndLocalTime,
        LocalTime overnightCutoffLocalTime,
        int expectedNetMinutes,
        int maximumSessionMinutes,
        boolean allowSplitSessions,
        int minimumBreakMinutes,
        String status,
        OffsetDateTime publishedAt
    ) {
    }

    public record AssignShiftInput(
        @NotNull UUID shiftPolicyVersionId,
        @NotNull LocalDate validFrom,
        LocalDate validTo
    ) {
    }

    public record ShiftAssignmentView(
        UUID id,
        UUID employeeId,
        UUID shiftPolicyVersionId,
        String shiftPolicyCode,
        String shiftPolicyName,
        int shiftPolicyVersion,
        String timezone,
        LocalDate validFrom,
        LocalDate validTo,
        OffsetDateTime createdAt
    ) {
    }

    public record RosterReadinessIssueView(
        String code,
        UUID employeeId,
        LocalDate workDate,
        String message
    ) {
    }

    public record RosterReadinessView(
        UUID engagementMonthId,
        LocalDate monthStartDate,
        int allocatedEmployeeCount,
        int allocatedEmployeeDayCount,
        int missingCalendarDayCount,
        int missingShiftDayCount,
        int missingEmployeeVersionDayCount,
        int missingSourceModeDayCount,
        boolean ready,
        List<RosterReadinessIssueView> issues
    ) {
    }

    public record FinalizeRosterInput(
        @NotBlank @Size(max = 4_000) String reason
    ) {
    }

    public record RosterSnapshotView(
        UUID id,
        UUID engagementMonthId,
        int version,
        UUID supersedesId,
        String status,
        String checksum,
        int employeeCount,
        int employeeDayCount,
        OffsetDateTime finalizedAt,
        String finalizedBySubject,
        String reason
    ) {
    }

    public record PublishLeavePolicyInput(
        @NotBlank @Size(max = 32) String leaveTypeCode,
        @NotBlank @Size(max = 128) String leaveTypeName,
        boolean paid,
        boolean balanceTracked,
        @NotNull @DecimalMin("0.25") @DecimalMax("1.00")
        BigDecimal minimumIncrement,
        @NotNull LocalDate validFrom,
        LocalDate validTo,
        boolean approvalRequired,
        @DecimalMin("0.25") BigDecimal maximumUnitsPerRequest,
        boolean excessToLwp,
        boolean cancellationAllowed,
        @NotNull Map<String, Object> rules
    ) {
    }

    public record LeavePolicyView(
        UUID id,
        UUID organizationId,
        UUID leaveTypeId,
        String leaveTypeCode,
        String leaveTypeName,
        int version,
        String status,
        LocalDate validFrom,
        LocalDate validTo,
        boolean approvalRequired,
        BigDecimal maximumUnitsPerRequest,
        boolean excessToLwp,
        boolean cancellationAllowed,
        Map<String, Object> rules,
        OffsetDateTime publishedAt
    ) {
    }

    public record LeaveBalanceCommandInput(
        @NotNull UUID leaveTypeId,
        @NotBlank
        @Pattern(regexp = "ACCRUAL|GRANT|ADJUSTMENT")
        String commandType,
        @NotNull BigDecimal quantity,
        @NotNull LocalDate effectiveDate,
        @NotBlank @Size(max = 160) String idempotencyKey,
        @NotBlank @Size(max = 4_000) String reason
    ) {
    }

    public record LeaveBalanceCommandView(
        UUID id,
        UUID employeeId,
        UUID leaveTypeId,
        String commandType,
        BigDecimal quantity,
        LocalDate effectiveDate,
        String idempotencyKey,
        String reason,
        OffsetDateTime createdAt
    ) {
    }

    public record LeaveDecisionInput(
        @NotBlank @Pattern(regexp = "APPROVE|REJECT|CANCEL") String decision,
        long expectedVersion,
        @NotBlank @Size(max = 160) String idempotencyKey,
        @NotBlank @Size(max = 4_000) String reason
    ) {
    }

    public record LeaveDecisionView(
        UUID id,
        UUID leaveRequestId,
        String decision,
        long expectedRequestVersion,
        String requestStatus,
        long requestVersion,
        BigDecimal paidUnits,
        BigDecimal lwpUnits,
        String reason,
        String decidedBySubject,
        OffsetDateTime decidedAt
    ) {
    }

    public record WorkforceCsvImportInput(
        @NotBlank
        @Pattern(regexp =
            "EMPLOYEE_ALIASES|DELIVERABLE_ALLOCATIONS|LEAVE_BALANCE_COMMANDS")
        String importType,
        @NotBlank @Size(max = 255) String fileName,
        @NotBlank @Size(max = 2_000_000) String csvContent,
        @NotBlank @Size(max = 160) String idempotencyKey,
        boolean apply
    ) {
    }

    public record WorkforceCsvErrorView(
        int rowNumber,
        String fieldName,
        String errorCode,
        String message
    ) {
    }

    public record WorkforceCsvImportView(
        UUID id,
        UUID organizationId,
        String importType,
        String fileName,
        String checksum,
        String status,
        int rowCount,
        int importedCount,
        List<WorkforceCsvErrorView> errors,
        boolean replay
    ) {
    }
}
