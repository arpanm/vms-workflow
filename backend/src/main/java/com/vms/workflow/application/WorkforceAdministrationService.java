package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.api.WorkforceAdministrationDtos.CalendarHolidayInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.CalendarVersionView;
import com.vms.workflow.api.WorkforceAdministrationDtos.CalendarWeekdayInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.AssignShiftInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.DeliverableAllocationInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.DeliverableAllocationView;
import com.vms.workflow.api.WorkforceAdministrationDtos.EmployeeAliasInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.EmployeeAliasView;
import com.vms.workflow.api.WorkforceAdministrationDtos.LeaveBalanceCommandInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.LeaveBalanceCommandView;
import com.vms.workflow.api.WorkforceAdministrationDtos.LeaveDecisionInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.LeaveDecisionView;
import com.vms.workflow.api.WorkforceAdministrationDtos.LeavePolicyView;
import com.vms.workflow.api.WorkforceAdministrationDtos.FinalizeRosterInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.PublishCalendarInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.PublishLeavePolicyInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.PublishShiftPolicyInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.RosterReadinessIssueView;
import com.vms.workflow.api.WorkforceAdministrationDtos.RosterReadinessView;
import com.vms.workflow.api.WorkforceAdministrationDtos.RosterSnapshotView;
import com.vms.workflow.api.WorkforceAdministrationDtos.ShiftAssignmentView;
import com.vms.workflow.api.WorkforceAdministrationDtos.ShiftPolicyView;
import com.vms.workflow.api.WorkforceAdministrationDtos.WorkforceCsvErrorView;
import com.vms.workflow.api.WorkforceAdministrationDtos.WorkforceCsvImportInput;
import com.vms.workflow.api.WorkforceAdministrationDtos.WorkforceCsvImportView;
import com.vms.workflow.api.WorkforceDtos.LeaveRequestView;
import com.vms.workflow.security.WorkforceAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class WorkforceAdministrationService {
    private static final Set<String> ALIAS_TYPES =
        Set.of("HRIS_ID", "EMAIL", "BADGE", "LEGACY_ID", "OTHER");
    private static final TypeReference<Map<String, Object>> JSON_MAP =
        new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final WorkforceAuthorizationService authorization;
    private final ObjectMapper objectMapper;

    public WorkforceAdministrationService(
        JdbcTemplate jdbc,
        WorkforceAuthorizationService authorization,
        ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<EmployeeAliasView> aliases(String subject, UUID employeeId) {
        authorization.requireEmployeeRead(subject, employeeId);
        return aliasViews(employeeId);
    }

    @Transactional
    public EmployeeAliasView addAlias(
        String subject,
        UUID employeeId,
        EmployeeAliasInput input
    ) {
        authorization.requireEmployeeManage(subject, employeeId);
        validateDates(input.validFrom(), input.validTo(), "Alias");
        if (!ALIAS_TYPES.contains(input.aliasType())) {
            throw new IllegalArgumentException("Unsupported alias type.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO employee_aliases
                (id, employee_id, alias_type, alias_value, valid_from, valid_to,
                 created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, employeeId, input.aliasType(), input.aliasValue().trim(),
            input.validFrom(), input.validTo(), subject);
        audit("EMPLOYEE_ALIAS", id, employeeId, "ALIAS_ADDED", subject);
        return aliasViews(employeeId).stream()
            .filter(value -> value.id().equals(id))
            .findFirst().orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public List<DeliverableAllocationView> deliverableAllocations(
        String subject,
        UUID employeeId
    ) {
        authorization.requireEmployeeRead(subject, employeeId);
        return deliverableAllocationViews(employeeId);
    }

    @Transactional
    public DeliverableAllocationView addDeliverableAllocation(
        String subject,
        UUID employeeId,
        DeliverableAllocationInput input
    ) {
        authorization.requireEmployeeManage(subject, employeeId);
        validateDates(input.validFrom(), input.validTo(), "Allocation");
        lockEmployee(employeeId);
        Boolean withinBounds = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM employee_project_allocations project_allocation
                JOIN engagement_months month
                  ON month.engagement_id = project_allocation.engagement_id
                JOIN delivery_plans plan
                  ON plan.engagement_month_id = month.id
                JOIN delivery_deliverables deliverable
                  ON deliverable.plan_id = plan.id
                 AND deliverable.id = ?
                WHERE project_allocation.id = ?
                  AND project_allocation.employee_id = ?
                  AND ?::date >= project_allocation.valid_from
                  AND (
                    project_allocation.valid_to IS NULL
                    OR (
                      ?::date IS NOT NULL
                      AND ?::date <= project_allocation.valid_to
                    )
                  )
                  AND project_allocation.allocation_percent >=
                    ? + COALESCE((
                      SELECT SUM(existing.allocation_percent)
                      FROM employee_deliverable_allocations existing
                      WHERE existing.employee_id = ?
                        AND existing.project_allocation_id =
                          project_allocation.id
                        AND existing.status IN ('PLANNED', 'ACTIVE')
                        AND daterange(
                          existing.valid_from,
                          COALESCE(
                            existing.valid_to + 1,
                            'infinity'::date
                          ),
                          '[)'
                        ) && daterange(
                          ?::date,
                          COALESCE(?::date + 1, 'infinity'::date),
                          '[)'
                        )
                    ), 0)
            )
            """, Boolean.class, input.deliverableId(),
            input.projectAllocationId(), employeeId, input.validFrom(),
            input.validTo(), input.validTo(), input.allocationPercent(),
            employeeId, input.validFrom(), input.validTo());
        if (!Boolean.TRUE.equals(withinBounds)) {
            throw invalidDeliverableAllocation();
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO employee_deliverable_allocations
                    (id, employee_id, project_allocation_id, deliverable_id,
                     valid_from, valid_to, allocation_percent,
                     role_on_deliverable, created_by_subject)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, employeeId, input.projectAllocationId(),
                input.deliverableId(), input.validFrom(), input.validTo(),
                input.allocationPercent(), input.roleOnDeliverable(), subject);
        } catch (DataAccessException invalidAllocation) {
            throw invalidDeliverableAllocation();
        }
        audit("DELIVERABLE_ALLOCATION", id, employeeId,
            "DELIVERABLE_ALLOCATION_ADDED", subject);
        return deliverableAllocationViews(employeeId).stream()
            .filter(value -> value.id().equals(id))
            .findFirst().orElseThrow(this::notFound);
    }

    private DomainConflictException invalidDeliverableAllocation() {
        return new DomainConflictException(
            "Deliverable allocation exceeds its project allocation "
                + "or falls outside its employee, date, or engagement scope.");
    }

    @Transactional(readOnly = true)
    public List<CalendarVersionView> calendars(
        String subject,
        UUID organizationId
    ) {
        authorization.requireOrganizationRead(subject, organizationId);
        return calendarViews(organizationId);
    }

    @Transactional
    public CalendarVersionView publishCalendar(
        String subject,
        UUID organizationId,
        PublishCalendarInput input
    ) {
        authorization.requireOrganizationManage(subject, organizationId);
        validateDates(input.validFrom(), input.validTo(), "Calendar");
        try {
            ZoneId.of(input.timezone());
        } catch (RuntimeException invalidZone) {
            throw new IllegalArgumentException("Calendar timezone is invalid.");
        }
        if (input.expectedFullMinutes() <= 0
            || input.expectedHalfMinutes() <= 0
            || input.expectedHalfMinutes() > input.expectedFullMinutes()) {
            throw new IllegalArgumentException(
                "Calendar minute thresholds are invalid.");
        }
        Set<Integer> days = input.weekdays().stream()
            .map(CalendarWeekdayInput::isoWeekday)
            .collect(java.util.stream.Collectors.toSet());
        if (days.size() != 7
            || !days.equals(Set.of(1, 2, 3, 4, 5, 6, 7))) {
            throw new IllegalArgumentException(
                "Calendar must define each ISO weekday exactly once.");
        }
        int version = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM working_calendar_versions
            WHERE organization_id = ? AND name = ?
            """, Integer.class, organizationId, input.name());
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO working_calendar_versions
                (id, organization_id, name, timezone, version, valid_from,
                 valid_to, expected_full_minutes, expected_half_minutes)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, organizationId, input.name(), input.timezone(), version,
            input.validFrom(), input.validTo(), input.expectedFullMinutes(),
            input.expectedHalfMinutes());
        for (CalendarWeekdayInput weekday : input.weekdays()) {
            if (weekday.expectedMinutes() < 0) {
                throw new IllegalArgumentException(
                    "Weekday minutes cannot be negative.");
            }
            jdbc.update("""
                INSERT INTO working_calendar_weekdays
                    (calendar_version_id, iso_weekday, classification,
                     expected_minutes)
                VALUES (?, ?, ?, ?)
                """, id, weekday.isoWeekday(), weekday.classification(),
                weekday.expectedMinutes());
        }
        for (CalendarHolidayInput holiday :
                input.holidays() == null ? List.<CalendarHolidayInput>of()
                    : input.holidays()) {
            if (holiday.expectedMinutes() < 0) {
                throw new IllegalArgumentException(
                    "Holiday minutes cannot be negative.");
            }
            jdbc.update("""
                INSERT INTO calendar_holidays
                    (id, calendar_version_id, holiday_date, name,
                     classification, expected_minutes)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), id, holiday.holidayDate(),
                holiday.name(), holiday.classification(),
                holiday.expectedMinutes());
        }
        audit("WORKING_CALENDAR", id, null, "CALENDAR_PUBLISHED", subject);
        return calendarViews(organizationId).stream()
            .filter(value -> value.id().equals(id))
            .findFirst().orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public List<ShiftPolicyView> shiftPolicies(
        String subject,
        UUID organizationId
    ) {
        authorization.requireOrganizationRead(subject, organizationId);
        return shiftPolicyViews(organizationId);
    }

    @Transactional
    public ShiftPolicyView publishShiftPolicy(
        String subject,
        UUID organizationId,
        PublishShiftPolicyInput input
    ) {
        authorization.requireOrganizationManage(subject, organizationId);
        validateDates(input.validFrom(), input.validTo(), "Shift policy");
        try {
            ZoneId.of(input.timezone());
        } catch (RuntimeException invalidZone) {
            throw new IllegalArgumentException("Shift policy timezone is invalid.");
        }
        if (input.expectedNetMinutes() <= 0
            || input.maximumSessionMinutes() <= 0
            || input.maximumSessionMinutes() > 36 * 60
            || input.expectedNetMinutes() > input.maximumSessionMinutes()
            || input.minimumBreakMinutes() < 0
            || input.minimumBreakMinutes() >= input.maximumSessionMinutes()) {
            throw new IllegalArgumentException(
                "Shift duration and break thresholds are invalid.");
        }
        String code = input.code().trim().toUpperCase(java.util.Locale.ROOT);
        LocalDate latestEffectiveFrom = jdbc.query("""
            SELECT valid_from
            FROM workforce_shift_policy_versions
            WHERE organization_id = ? AND code = ?
            ORDER BY version DESC
            LIMIT 1
            """, result -> result.next()
                ? result.getObject(1, LocalDate.class) : null,
            organizationId, code);
        if (latestEffectiveFrom != null
            && !input.validFrom().isAfter(latestEffectiveFrom)) {
            throw new DomainConflictException(
                "A new shift policy version must become effective after the latest version.");
        }
        int version = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM workforce_shift_policy_versions
            WHERE organization_id = ? AND code = ?
            """, Integer.class, organizationId, code);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO workforce_shift_policy_versions
                (id, organization_id, code, name, timezone, version,
                 valid_from, valid_to, scheduled_start_local_time,
                 scheduled_end_local_time, overnight_cutoff_local_time,
                 expected_net_minutes, maximum_session_minutes,
                 allow_split_sessions, minimum_break_minutes,
                 created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, organizationId, code, input.name().trim(),
            input.timezone(), version, input.validFrom(), input.validTo(),
            input.scheduledStartLocalTime(), input.scheduledEndLocalTime(),
            input.overnightCutoffLocalTime(), input.expectedNetMinutes(),
            input.maximumSessionMinutes(), input.allowSplitSessions(),
            input.minimumBreakMinutes(), subject);
        audit("SHIFT_POLICY", id, null, "SHIFT_POLICY_PUBLISHED", subject);
        return shiftPolicyViews(organizationId).stream()
            .filter(value -> value.id().equals(id))
            .findFirst().orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public List<ShiftAssignmentView> shiftAssignments(
        String subject,
        UUID employeeId
    ) {
        authorization.requireEmployeeRead(subject, employeeId);
        return shiftAssignmentViews(employeeId);
    }

    @Transactional
    public ShiftAssignmentView assignShift(
        String subject,
        UUID employeeId,
        AssignShiftInput input
    ) {
        authorization.requireEmployeeManage(subject, employeeId);
        validateDates(input.validFrom(), input.validTo(), "Shift assignment");
        lockEmployee(employeeId);
        Boolean valid = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM employees employee
                JOIN workforce_shift_policy_versions policy
                  ON policy.organization_id = employee.organization_id
                 AND policy.id = ?
                 AND policy.status = 'PUBLISHED'
                WHERE employee.id = ?
                  AND ?::date >= policy.valid_from
                  AND (
                    policy.valid_to IS NULL
                    OR (
                      ?::date IS NOT NULL
                      AND ?::date <= policy.valid_to
                    )
                  )
            )
            """, Boolean.class, input.shiftPolicyVersionId(), employeeId,
            input.validFrom(), input.validTo(), input.validTo());
        if (!Boolean.TRUE.equals(valid)) {
            throw new DomainConflictException(
                "Shift assignment must use a same-organization published policy "
                    + "and remain inside its effective range.");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                INSERT INTO employee_shift_assignments
                    (id, employee_id, shift_policy_version_id,
                     valid_from, valid_to, created_by_subject)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, employeeId, input.shiftPolicyVersionId(),
                input.validFrom(), input.validTo(), subject);
        } catch (DataAccessException overlap) {
            throw new DomainConflictException(
                "Employee shift assignments cannot overlap.");
        }
        audit("SHIFT_ASSIGNMENT", id, employeeId, "SHIFT_ASSIGNED", subject);
        return shiftAssignmentViews(employeeId).stream()
            .filter(value -> value.id().equals(id))
            .findFirst().orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public RosterReadinessView rosterReadiness(
        String subject,
        UUID engagementMonthId
    ) {
        RosterMonth month = rosterMonth(engagementMonthId);
        authorization.requireEngagementClose(subject, month.engagementId());
        return evaluateRosterReadiness(engagementMonthId, month);
    }

    @Transactional(readOnly = true)
    public List<RosterSnapshotView> rosterSnapshots(
        String subject,
        UUID engagementMonthId
    ) {
        RosterMonth month = rosterMonth(engagementMonthId);
        authorization.requireEngagementClose(subject, month.engagementId());
        return rosterSnapshotViews(engagementMonthId);
    }

    @Transactional
    public RosterSnapshotView finalizeRoster(
        String subject,
        UUID engagementMonthId,
        FinalizeRosterInput input
    ) {
        RosterMonth month = rosterMonth(engagementMonthId);
        authorization.requireEngagementClose(subject, month.engagementId());
        lockEngagementMonth(engagementMonthId);
        RosterReadinessView readiness =
            evaluateRosterReadiness(engagementMonthId, month);
        if (!readiness.ready()) {
            throw new DomainConflictException(
                "Roster cannot be finalized: "
                    + readiness.missingCalendarDayCount()
                    + " calendar, " + readiness.missingShiftDayCount()
                    + " shift, " + readiness.missingEmployeeVersionDayCount()
                    + " employee-version, and "
                    + readiness.missingSourceModeDayCount()
                    + " source-mode employee-day gaps remain.");
        }
        List<RosterDay> days = rosterDays(month);
        String checksum = rosterChecksum(days);
        List<RosterSnapshotView> existing =
            rosterSnapshotViews(engagementMonthId);
        if (!existing.isEmpty()
            && existing.getLast().checksum().equals(checksum)) {
            return existing.getLast();
        }
        UUID snapshotId = UUID.randomUUID();
        int version = existing.isEmpty() ? 1 : existing.getLast().version() + 1;
        UUID supersedes = existing.isEmpty() ? null : existing.getLast().id();
        int employeeCount = (int) days.stream()
            .map(RosterDay::employeeId).distinct().count();
        int employeeDayCount = (int) days.stream()
            .map(day -> day.employeeId() + ":" + day.workDate())
            .distinct().count();
        jdbc.update("""
            INSERT INTO workforce_roster_snapshot_versions
                (id, engagement_month_id, version, supersedes_id,
                 checksum, employee_count, employee_day_count,
                 finalized_by_subject, reason)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, snapshotId, engagementMonthId, version, supersedes,
            checksum, employeeCount, employeeDayCount, subject,
            input.reason().trim());
        for (RosterDay day : days) {
            jdbc.update("""
                INSERT INTO workforce_roster_snapshot_days
                    (snapshot_id, employee_id, work_date,
                     project_allocation_id, project_id, allocation_percent,
                     shift_policy_version_id, shift_policy_code,
                     shift_policy_version, timezone,
                     expected_classification, expected_minutes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, snapshotId, day.employeeId(), day.workDate(),
                day.projectAllocationId(), day.projectId(),
                day.allocationPercent(), day.shiftPolicyVersionId(),
                day.shiftPolicyCode(), day.shiftPolicyVersion(),
                day.timezone(), day.expectedClassification(),
                day.expectedMinutes());
        }
        audit("ROSTER_SNAPSHOT", snapshotId, null,
            "ROSTER_FINALIZED", subject);
        return rosterSnapshotViews(engagementMonthId).stream()
            .filter(value -> value.id().equals(snapshotId))
            .findFirst().orElseThrow(this::notFound);
    }

    @Transactional(readOnly = true)
    public List<LeavePolicyView> leavePolicies(
        String subject,
        UUID organizationId
    ) {
        authorization.requireOrganizationRead(subject, organizationId);
        return leavePolicyViews(organizationId);
    }

    @Transactional
    public LeavePolicyView publishLeavePolicy(
        String subject,
        UUID organizationId,
        PublishLeavePolicyInput input
    ) {
        authorization.requireOrganizationManage(subject, organizationId);
        validateDates(input.validFrom(), input.validTo(), "Leave policy");
        if (input.maximumUnitsPerRequest() != null
            && input.maximumUnitsPerRequest()
                .remainder(input.minimumIncrement())
                .compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException(
                "Maximum request units must align to the minimum increment.");
        }
        UUID leaveTypeId = jdbc.query("""
            SELECT id FROM leave_types
            WHERE organization_id = ? AND code = ?
            """, result -> result.next()
                ? result.getObject("id", UUID.class) : null,
            organizationId, input.leaveTypeCode());
        if (leaveTypeId == null) {
            leaveTypeId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO leave_types
                    (id, organization_id, code, name, paid, balance_tracked,
                     minimum_increment, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, leaveTypeId, organizationId, input.leaveTypeCode(),
                input.leaveTypeName(), input.paid(), input.balanceTracked(),
                input.minimumIncrement());
        } else {
            Boolean compatible = jdbc.queryForObject("""
                SELECT name = ? AND paid = ? AND balance_tracked = ?
                       AND minimum_increment = ?
                FROM leave_types WHERE id = ?
                """, Boolean.class, input.leaveTypeName(), input.paid(),
                input.balanceTracked(), input.minimumIncrement(), leaveTypeId);
            if (!Boolean.TRUE.equals(compatible)) {
                throw new DomainConflictException(
                    "Existing leave type identity cannot be redefined.");
            }
        }
        int version = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM leave_policy_versions
            WHERE organization_id = ? AND leave_type_id = ?
            """, Integer.class, organizationId, leaveTypeId);
        LocalDate latestEffectiveFrom = jdbc.query("""
            SELECT valid_from
            FROM leave_policy_versions
            WHERE organization_id = ? AND leave_type_id = ?
            ORDER BY version DESC
            LIMIT 1
            """, result -> result.next()
                ? result.getObject(1, LocalDate.class) : null,
            organizationId, leaveTypeId);
        if (latestEffectiveFrom != null
            && !input.validFrom().isAfter(latestEffectiveFrom)) {
            throw new DomainConflictException(
                "A new leave policy must become effective after the latest version.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO leave_policy_versions
                (id, organization_id, leave_type_id, version, status,
                 valid_from, valid_to, approval_required,
                 maximum_units_per_request, excess_to_lwp,
                 cancellation_allowed, rules, published_at,
                 created_by_subject)
            VALUES (?, ?, ?, ?, 'PUBLISHED', ?, ?, ?, ?, ?, ?, ?::jsonb,
                    CURRENT_TIMESTAMP, ?)
            """, id, organizationId, leaveTypeId, version,
            input.validFrom(), input.validTo(), input.approvalRequired(),
            input.maximumUnitsPerRequest(), input.excessToLwp(),
            input.cancellationAllowed(), json(input.rules()), subject);
        audit("LEAVE_POLICY", id, null, "LEAVE_POLICY_PUBLISHED", subject);
        return leavePolicyViews(organizationId).stream()
            .filter(value -> value.id().equals(id))
            .findFirst().orElseThrow(this::notFound);
    }

    @Transactional
    public LeaveBalanceCommandView recordBalanceCommand(
        String subject,
        UUID employeeId,
        LeaveBalanceCommandInput input
    ) {
        authorization.requireEmployeeManage(subject, employeeId);
        lockEmployee(employeeId);
        List<LeaveBalanceCommandView> replay =
            balanceCommandViews(employeeId, input.idempotencyKey());
        if (!replay.isEmpty()) {
            LeaveBalanceCommandView prior = replay.getFirst();
            if (!sameBalanceCommand(prior, input)) {
                throw new DomainConflictException(
                    "Idempotency key was already used for another balance command.");
            }
            return prior;
        }
        if (input.quantity().signum() == 0
            || (!"ADJUSTMENT".equals(input.commandType())
                && input.quantity().signum() < 0)) {
            throw new IllegalArgumentException(
                "Accrual/grant quantities must be positive; adjustment must be non-zero.");
        }
        Boolean valid = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM employees employee
                JOIN leave_types type
                  ON type.organization_id = employee.organization_id
                WHERE employee.id = ? AND type.id = ?
                  AND type.status = 'ACTIVE'
            )
            """, Boolean.class, employeeId, input.leaveTypeId());
        if (!Boolean.TRUE.equals(valid)) {
            throw new IllegalArgumentException(
                "Leave type is not active in the employee organization.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO leave_balance_commands
                (id, employee_id, leave_type_id, command_type, quantity,
                 effective_date, idempotency_key, reason, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, employeeId, input.leaveTypeId(), input.commandType(),
            input.quantity(), input.effectiveDate(), input.idempotencyKey(),
            input.reason(), subject);
        String entryType = switch (input.commandType()) {
            case "ACCRUAL" -> "MONTHLY_ACCRUAL";
            case "GRANT" -> "ANNUAL_GRANT";
            case "ADJUSTMENT" -> input.quantity().signum() > 0
                ? "MANUAL_ADJUSTMENT_CREDIT" : "MANUAL_ADJUSTMENT_DEBIT";
            default -> throw new IllegalArgumentException("Unsupported command type.");
        };
        jdbc.update("""
            INSERT INTO leave_balance_ledger
                (id, employee_id, leave_type_id, entry_type, quantity,
                 effective_date, idempotency_key, reference_type, reference_id,
                 reason, recorded_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'BALANCE_COMMAND', ?, ?, ?)
            """, UUID.randomUUID(), employeeId, input.leaveTypeId(),
            entryType, input.quantity(), input.effectiveDate(),
            "balance-command:" + input.idempotencyKey(), id,
            input.reason(), subject);
        audit("LEAVE_BALANCE_COMMAND", id, employeeId,
            "LEAVE_BALANCE_" + input.commandType(), subject);
        return balanceCommandViews(employeeId, input.idempotencyKey()).getFirst();
    }

    @Transactional
    public LeaveDecisionView decideLeave(
        String subject,
        UUID leaveRequestId,
        LeaveDecisionInput input
    ) {
        LeaveTarget target = leaveTarget(leaveRequestId, true);
        authorization.requireEmployeeManage(subject, target.employeeId());
        lockEmployee(target.employeeId());
        List<LeaveDecisionView> replay =
            leaveDecisionViews(leaveRequestId, input.idempotencyKey());
        if (!replay.isEmpty()) {
            LeaveDecisionView prior = replay.getFirst();
            if (!prior.decision().equals(input.decision())
                || !prior.reason().equals(input.reason())
                || prior.expectedRequestVersion() != input.expectedVersion()) {
                throw new DomainConflictException(
                    "Idempotency key was already used for another leave decision.");
            }
            return prior;
        }
        target = leaveTarget(leaveRequestId, true);
        if (target.version() != input.expectedVersion()) {
            throw new DomainConflictException(
                "Leave request version changed; reload before deciding.");
        }
        String nextStatus;
        if ("CANCEL".equals(input.decision())) {
            if (!"APPROVED".equals(target.status())
                || !cancellationAllowed(target)) {
                throw new DomainConflictException(
                    "Only a cancellable approved request may be cancelled.");
            }
            nextStatus = "CANCELLED";
            if (target.paidUnits().signum() > 0 && target.balanceTracked()) {
                insertLeaveLedger(target, target.paidUnits(), "LEAVE_RELEASED",
                    "leave-cancel:" + input.idempotencyKey(), input.reason(),
                    subject);
            }
        } else {
            if (!"SUBMITTED".equals(target.status())) {
                throw new DomainConflictException(
                    "Only a submitted request may be approved or rejected.");
            }
            nextStatus = "APPROVE".equals(input.decision())
                ? "APPROVED" : "REJECTED";
            if ("APPROVED".equals(nextStatus)
                && target.paidUnits().signum() > 0
                && target.balanceTracked()) {
                insertLeaveLedger(target, target.paidUnits().negate(),
                    "LEAVE_CONSUMED",
                    "leave-approve:" + input.idempotencyKey(),
                    input.reason(), subject);
            }
        }
        UUID decisionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO leave_request_decisions
                (id, leave_request_id, decision, expected_request_status,
                 expected_request_version, resulting_request_status,
                 resulting_request_version, reason, paid_units, lwp_units,
                 idempotency_key, decided_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, decisionId, leaveRequestId, input.decision(), target.status(),
            input.expectedVersion(), nextStatus, input.expectedVersion() + 1,
            input.reason(), target.paidUnits(), target.lwpUnits(),
            input.idempotencyKey(), subject);
        int changed = jdbc.update("""
            UPDATE leave_requests
            SET status = ?, version = version + 1
            WHERE id = ? AND version = ?
            """, nextStatus, leaveRequestId, input.expectedVersion());
        if (changed != 1) {
            throw new DomainConflictException(
                "Leave request version changed; reload before deciding.");
        }
        audit("LEAVE_REQUEST", leaveRequestId, target.employeeId(),
            "LEAVE_" + nextStatus, subject);
        return leaveDecisionViews(leaveRequestId, input.idempotencyKey())
            .getFirst();
    }

    @Transactional(readOnly = true)
    public List<LeaveRequestView> leaveRequestInbox(
        String subject,
        UUID organizationId
    ) {
        authorization.requireOrganizationManage(subject, organizationId);
        return jdbc.query("""
            SELECT request.id, request.employee_id, request.leave_type_id,
                   request.start_date, request.end_date,
                   request.requested_units, request.paid_units,
                   request.lwp_units, request.reason, request.status,
                   request.idempotency_key, request.created_at, request.version
            FROM leave_requests request
            JOIN employees employee ON employee.id = request.employee_id
            WHERE employee.organization_id = ?
            ORDER BY request.created_at DESC
            """, (result, row) -> new LeaveRequestView(
                result.getObject("id", UUID.class),
                result.getObject("employee_id", UUID.class),
                result.getObject("leave_type_id", UUID.class),
                result.getObject("start_date", LocalDate.class),
                result.getObject("end_date", LocalDate.class),
                result.getBigDecimal("requested_units"),
                result.getBigDecimal("paid_units"),
                result.getBigDecimal("lwp_units"),
                result.getString("reason"), result.getString("status"),
                result.getString("idempotency_key"),
                result.getObject("created_at", OffsetDateTime.class),
                result.getLong("version")),
            organizationId);
    }

    @Transactional(readOnly = true)
    public List<com.vms.workflow.api.AttendanceDtos.RegularizationView>
            regularizationInbox(String subject, UUID organizationId) {
        authorization.requireOrganizationManage(subject, organizationId);
        return jdbc.query("""
            SELECT request.id, request.employee_id, request.work_date,
                   request.reason_code, request.narrative,
                   request.requested_outcome, request.idempotency_key,
                   request.status, request.created_at
            FROM attendance_regularizations request
            JOIN employees employee ON employee.id = request.employee_id
            WHERE employee.organization_id = ?
            ORDER BY request.created_at DESC
            """, (result, row) ->
                new com.vms.workflow.api.AttendanceDtos.RegularizationView(
                    result.getObject("id", UUID.class),
                    result.getObject("employee_id", UUID.class),
                    result.getObject("work_date", LocalDate.class),
                    result.getString("reason_code"),
                    result.getString("narrative"),
                    result.getString("requested_outcome"),
                    result.getString("idempotency_key"),
                    result.getString("status"),
                    result.getObject("created_at", OffsetDateTime.class)),
            organizationId);
    }

    @Transactional
    public WorkforceCsvImportView importCsv(
        String subject,
        UUID organizationId,
        WorkforceCsvImportInput input
    ) {
        authorization.requireOrganizationManage(subject, organizationId);
        String normalized = input.csvContent().replace("\r\n", "\n")
            .replace('\r', '\n');
        String checksum = sha256(normalized);
        WorkforceCsvImportView prior =
            importReplay(organizationId, input.idempotencyKey(), true);
        if (prior != null) {
            if (!prior.checksum().equals(checksum)
                || !prior.importType().equals(input.importType())) {
                throw new DomainConflictException(
                    "Import idempotency key was reused with different content.");
            }
            return prior;
        }
        ParsedCsv csv = parseCsv(normalized);
        List<WorkforceCsvErrorView> errors =
            validateCsv(organizationId, input.importType(), csv);
        UUID batchId = UUID.randomUUID();
        String status;
        int imported = 0;
        if (!errors.isEmpty()) {
            status = "FAILED";
        } else if (!input.apply()) {
            status = "VALIDATED";
        } else {
            applyCsv(subject, organizationId, input.importType(), csv);
            status = "IMPORTED";
            imported = csv.rows().size();
        }
        jdbc.update("""
            INSERT INTO workforce_import_batches
                (id, organization_id, import_type, original_file_name,
                 content_checksum, idempotency_key, status, row_count,
                 error_count, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, batchId, organizationId, input.importType(),
            input.fileName(), checksum, input.idempotencyKey(), status,
            csv.rows().size(), errors.size(), subject);
        for (WorkforceCsvErrorView error : errors) {
            jdbc.update("""
                INSERT INTO workforce_import_errors
                    (id, batch_id, row_number, field_name, error_code, message)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), batchId, error.rowNumber(),
                error.fieldName(), error.errorCode(), error.message());
        }
        audit("WORKFORCE_IMPORT", batchId, null,
            "WORKFORCE_IMPORT_" + status, subject);
        return new WorkforceCsvImportView(
            batchId, organizationId, input.importType(), input.fileName(),
            checksum, status, csv.rows().size(), imported, errors, false);
    }

    private List<ShiftPolicyView> shiftPolicyViews(UUID organizationId) {
        return jdbc.query("""
            SELECT id, organization_id, code, name, timezone, version,
                   valid_from, valid_to, scheduled_start_local_time,
                   scheduled_end_local_time, overnight_cutoff_local_time,
                   expected_net_minutes, maximum_session_minutes,
                   allow_split_sessions, minimum_break_minutes, status,
                   published_at
            FROM workforce_shift_policy_versions
            WHERE organization_id = ?
            ORDER BY code, version DESC
            """, (result, row) -> new ShiftPolicyView(
                result.getObject("id", UUID.class),
                result.getObject("organization_id", UUID.class),
                result.getString("code"), result.getString("name"),
                result.getString("timezone"), result.getInt("version"),
                result.getObject("valid_from", LocalDate.class),
                result.getObject("valid_to", LocalDate.class),
                result.getObject("scheduled_start_local_time", LocalTime.class),
                result.getObject("scheduled_end_local_time", LocalTime.class),
                result.getObject("overnight_cutoff_local_time", LocalTime.class),
                result.getInt("expected_net_minutes"),
                result.getInt("maximum_session_minutes"),
                result.getBoolean("allow_split_sessions"),
                result.getInt("minimum_break_minutes"),
                result.getString("status"),
                result.getObject("published_at", OffsetDateTime.class)),
            organizationId);
    }

    private List<ShiftAssignmentView> shiftAssignmentViews(UUID employeeId) {
        return jdbc.query("""
            SELECT assignment.id, assignment.employee_id,
                   assignment.shift_policy_version_id,
                   policy.code, policy.name, policy.version, policy.timezone,
                   assignment.valid_from, assignment.valid_to,
                   assignment.created_at
            FROM employee_shift_assignments assignment
            JOIN workforce_shift_policy_versions policy
              ON policy.id = assignment.shift_policy_version_id
            WHERE assignment.employee_id = ?
            ORDER BY assignment.valid_from DESC
            """, (result, row) -> new ShiftAssignmentView(
                result.getObject("id", UUID.class),
                result.getObject("employee_id", UUID.class),
                result.getObject("shift_policy_version_id", UUID.class),
                result.getString("code"), result.getString("name"),
                result.getInt("version"), result.getString("timezone"),
                result.getObject("valid_from", LocalDate.class),
                result.getObject("valid_to", LocalDate.class),
                result.getObject("created_at", OffsetDateTime.class)),
            employeeId);
    }

    private RosterMonth rosterMonth(UUID engagementMonthId) {
        RosterMonth month = jdbc.query("""
            SELECT engagement_id, month_start_date
            FROM engagement_months
            WHERE id = ?
            """, result -> result.next()
                ? new RosterMonth(
                    result.getObject("engagement_id", UUID.class),
                    result.getObject("month_start_date", LocalDate.class))
                : null, engagementMonthId);
        if (month == null) {
            throw notFound();
        }
        return month;
    }

    private RosterReadinessView evaluateRosterReadiness(
        UUID engagementMonthId,
        RosterMonth month
    ) {
        RosterReadinessCounts counts = jdbc.query("""
            WITH allocated AS (
                SELECT DISTINCT allocation.employee_id,
                       day.work_date::date AS work_date
                FROM employee_project_allocations allocation
                CROSS JOIN LATERAL generate_series(
                    GREATEST(allocation.valid_from, ?::date),
                    LEAST(
                        COALESCE(allocation.valid_to, (?::date - 1)),
                        (?::date - 1)
                    ),
                    INTERVAL '1 day'
                ) AS day(work_date)
                WHERE allocation.engagement_id = ?
                  AND allocation.status IN ('PLANNED', 'ACTIVE')
                  AND allocation.valid_from < ?
                  AND (
                    allocation.valid_to IS NULL
                    OR allocation.valid_to >= ?
                  )
            )
            SELECT COUNT(DISTINCT employee_id) AS employee_count,
                   COUNT(*) AS employee_day_count,
                   COUNT(*) FILTER (
                     WHERE NOT EXISTS (
                       SELECT 1 FROM employee_calendar_assignments calendar
                       WHERE calendar.employee_id = allocated.employee_id
                         AND calendar.valid_from <= allocated.work_date
                         AND (
                           calendar.valid_to IS NULL
                           OR calendar.valid_to >= allocated.work_date
                         )
                     )
                   ) AS missing_calendar,
                   COUNT(*) FILTER (
                     WHERE NOT EXISTS (
                       SELECT 1 FROM employee_shift_assignments shift
                       WHERE shift.employee_id = allocated.employee_id
                         AND shift.valid_from <= allocated.work_date
                         AND (
                           shift.valid_to IS NULL
                           OR shift.valid_to >= allocated.work_date
                         )
                     )
                   ) AS missing_shift,
                   COUNT(*) FILTER (
                     WHERE NOT EXISTS (
                       SELECT 1 FROM employee_versions employee_version
                       WHERE employee_version.employee_id =
                               allocated.employee_id
                         AND employee_version.valid_from <= allocated.work_date
                         AND (
                           employee_version.valid_to IS NULL
                           OR employee_version.valid_to >= allocated.work_date
                         )
                     )
                   ) AS missing_employee_version,
                   COUNT(*) FILTER (
                     WHERE NOT EXISTS (
                       SELECT 1 FROM attendance_source_mode_assignments source
                       WHERE source.employee_id = allocated.employee_id
                         AND source.valid_from <= allocated.work_date
                         AND (
                           source.valid_to IS NULL
                           OR source.valid_to >= allocated.work_date
                         )
                     )
                   ) AS missing_source_mode
            FROM allocated
            """, result -> {
                result.next();
                return new RosterReadinessCounts(
                    result.getInt("employee_count"),
                    result.getInt("employee_day_count"),
                    result.getInt("missing_calendar"),
                    result.getInt("missing_shift"),
                    result.getInt("missing_employee_version"),
                    result.getInt("missing_source_mode"));
            }, month.monthStart(), month.monthStart().plusMonths(1),
            month.monthStart().plusMonths(1), month.engagementId(),
            month.monthStart().plusMonths(1), month.monthStart());
        List<RosterReadinessIssueView> issues = jdbc.query("""
            WITH allocated AS (
                SELECT DISTINCT allocation.employee_id,
                       day.work_date::date AS work_date
                FROM employee_project_allocations allocation
                CROSS JOIN LATERAL generate_series(
                    GREATEST(allocation.valid_from, ?::date),
                    LEAST(
                        COALESCE(allocation.valid_to, (?::date - 1)),
                        (?::date - 1)
                    ),
                    INTERVAL '1 day'
                ) AS day(work_date)
                WHERE allocation.engagement_id = ?
                  AND allocation.status IN ('PLANNED', 'ACTIVE')
                  AND allocation.valid_from < ?
                  AND (
                    allocation.valid_to IS NULL
                    OR allocation.valid_to >= ?
                  )
            ),
            issues AS (
                SELECT employee_id, work_date, 'MISSING_CALENDAR' AS code,
                       'No effective working calendar assignment.' AS message
                FROM allocated
                WHERE NOT EXISTS (
                    SELECT 1 FROM employee_calendar_assignments assignment
                    WHERE assignment.employee_id = allocated.employee_id
                      AND assignment.valid_from <= allocated.work_date
                      AND (
                        assignment.valid_to IS NULL
                        OR assignment.valid_to >= allocated.work_date
                      )
                )
                UNION ALL
                SELECT employee_id, work_date, 'MISSING_SHIFT',
                       'No effective shift policy assignment.'
                FROM allocated
                WHERE NOT EXISTS (
                    SELECT 1 FROM employee_shift_assignments assignment
                    WHERE assignment.employee_id = allocated.employee_id
                      AND assignment.valid_from <= allocated.work_date
                      AND (
                        assignment.valid_to IS NULL
                        OR assignment.valid_to >= allocated.work_date
                      )
                )
                UNION ALL
                SELECT employee_id, work_date, 'MISSING_EMPLOYEE_VERSION',
                       'No effective employee version.'
                FROM allocated
                WHERE NOT EXISTS (
                    SELECT 1 FROM employee_versions version
                    WHERE version.employee_id = allocated.employee_id
                      AND version.valid_from <= allocated.work_date
                      AND (
                        version.valid_to IS NULL
                        OR version.valid_to >= allocated.work_date
                      )
                )
                UNION ALL
                SELECT employee_id, work_date, 'MISSING_SOURCE_MODE',
                       'No effective attendance source mode.'
                FROM allocated
                WHERE NOT EXISTS (
                    SELECT 1 FROM attendance_source_mode_assignments source
                    WHERE source.employee_id = allocated.employee_id
                      AND source.valid_from <= allocated.work_date
                      AND (
                        source.valid_to IS NULL
                        OR source.valid_to >= allocated.work_date
                      )
                )
                UNION ALL
                SELECT allocated.employee_id, allocated.work_date,
                       'MISSING_CALENDAR_WEEKDAY',
                       'The effective calendar has no rule for this weekday.'
                FROM allocated
                JOIN employee_calendar_assignments assignment
                  ON assignment.employee_id = allocated.employee_id
                 AND assignment.valid_from <= allocated.work_date
                 AND (
                   assignment.valid_to IS NULL
                   OR assignment.valid_to >= allocated.work_date
                 )
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM working_calendar_weekdays weekday
                    WHERE weekday.calendar_version_id =
                          assignment.calendar_version_id
                      AND weekday.iso_weekday =
                          EXTRACT(ISODOW FROM allocated.work_date)
                )
                UNION ALL
                SELECT allocated.employee_id, allocated.work_date,
                       'INELIGIBLE_EMPLOYEE_STATUS',
                       'Employee is not active and enabled for this roster day.'
                FROM allocated
                JOIN employees employee ON employee.id = allocated.employee_id
                JOIN employee_versions version
                  ON version.employee_id = allocated.employee_id
                 AND version.valid_from <= allocated.work_date
                 AND (
                   version.valid_to IS NULL
                   OR version.valid_to >= allocated.work_date
                 )
                WHERE employee.join_date > allocated.work_date
                   OR version.employment_status NOT IN ('ACTIVE', 'ON_LEAVE')
                   OR version.activation_status <> 'ENABLED'
                   OR (
                     version.exit_date IS NOT NULL
                     AND version.exit_date < allocated.work_date
                   )
                UNION ALL
                SELECT allocated.employee_id, allocated.work_date,
                       'INVALID_SHIFT_POLICY',
                       'Shift policy is not published or effective on this day.'
                FROM allocated
                JOIN employee_shift_assignments assignment
                  ON assignment.employee_id = allocated.employee_id
                 AND assignment.valid_from <= allocated.work_date
                 AND (
                   assignment.valid_to IS NULL
                   OR assignment.valid_to >= allocated.work_date
                 )
                JOIN workforce_shift_policy_versions policy
                  ON policy.id = assignment.shift_policy_version_id
                WHERE policy.status <> 'PUBLISHED'
                   OR policy.valid_from > allocated.work_date
                   OR (
                     policy.valid_to IS NOT NULL
                     AND policy.valid_to < allocated.work_date
                   )
                UNION ALL
                SELECT allocation.employee_id, day.work_date::date,
                       'ALLOCATION_EXCEEDS_100_PERCENT',
                       'Effective project allocations exceed 100 percent.'
                FROM employee_project_allocations allocation
                CROSS JOIN LATERAL generate_series(
                    GREATEST(allocation.valid_from, ?::date),
                    LEAST(
                        COALESCE(allocation.valid_to, (?::date - 1)),
                        (?::date - 1)
                    ),
                    INTERVAL '1 day'
                ) AS day(work_date)
                WHERE allocation.engagement_id = ?
                  AND allocation.status IN ('PLANNED', 'ACTIVE')
                  AND allocation.valid_from < ?
                  AND (
                    allocation.valid_to IS NULL
                    OR allocation.valid_to >= ?
                  )
                GROUP BY allocation.employee_id, day.work_date
                HAVING SUM(allocation.allocation_percent) > 100
            )
            SELECT employee_id, work_date, code, message
            FROM issues
            ORDER BY work_date, employee_id, code
            LIMIT 250
            """, (result, row) -> new RosterReadinessIssueView(
                result.getString("code"),
                result.getObject("employee_id", UUID.class),
                result.getObject("work_date", LocalDate.class),
                result.getString("message")),
            month.monthStart(), month.monthStart().plusMonths(1),
            month.monthStart().plusMonths(1), month.engagementId(),
            month.monthStart().plusMonths(1), month.monthStart(),
            month.monthStart(), month.monthStart().plusMonths(1),
            month.monthStart().plusMonths(1), month.engagementId(),
            month.monthStart().plusMonths(1), month.monthStart());
        boolean ready = counts.employeeDayCount() > 0
            && counts.missingCalendar() == 0
            && counts.missingShift() == 0
            && counts.missingEmployeeVersion() == 0
            && counts.missingSourceMode() == 0
            && issues.isEmpty();
        if (counts.employeeDayCount() == 0) {
            issues = new ArrayList<>(issues);
            issues.add(new RosterReadinessIssueView(
                "NO_ALLOCATED_EMPLOYEE_DAYS", null, null,
                "The engagement month has no active allocated employee-days."));
        }
        return new RosterReadinessView(
            engagementMonthId, month.monthStart(),
            counts.employeeCount(), counts.employeeDayCount(),
            counts.missingCalendar(), counts.missingShift(),
            counts.missingEmployeeVersion(), counts.missingSourceMode(),
            ready, issues);
    }

    private List<RosterDay> rosterDays(RosterMonth month) {
        return jdbc.query("""
            SELECT allocation.employee_id, day.work_date::date AS work_date,
                   allocation.id AS project_allocation_id,
                   allocation.project_id, allocation.allocation_percent,
                   policy.id AS shift_policy_version_id,
                   policy.code AS shift_policy_code,
                   policy.version AS shift_policy_version,
                   policy.timezone,
                   COALESCE(override.classification, holiday.classification,
                            weekday.classification) AS expected_classification,
                   CASE
                     WHEN override.id IS NOT NULL
                       THEN override.expected_minutes
                     WHEN holiday.id IS NOT NULL
                       THEN holiday.expected_minutes
                     WHEN weekday.classification = 'WORKING'
                       THEN policy.expected_net_minutes
                     ELSE weekday.expected_minutes
                   END AS expected_minutes
            FROM employee_project_allocations allocation
            CROSS JOIN LATERAL generate_series(
                GREATEST(allocation.valid_from, ?::date),
                LEAST(
                    COALESCE(allocation.valid_to, (?::date - 1)),
                    (?::date - 1)
                ),
                INTERVAL '1 day'
            ) AS day(work_date)
            JOIN employee_calendar_assignments calendar
              ON calendar.employee_id = allocation.employee_id
             AND calendar.valid_from <= day.work_date::date
             AND (
               calendar.valid_to IS NULL
               OR calendar.valid_to >= day.work_date::date
             )
            JOIN working_calendar_weekdays weekday
              ON weekday.calendar_version_id = calendar.calendar_version_id
             AND weekday.iso_weekday =
                 EXTRACT(ISODOW FROM day.work_date::date)
            LEFT JOIN calendar_holidays holiday
              ON holiday.calendar_version_id = calendar.calendar_version_id
             AND holiday.holiday_date = day.work_date::date
            LEFT JOIN employee_date_overrides override
              ON override.employee_id = allocation.employee_id
             AND override.override_date = day.work_date::date
            JOIN employee_shift_assignments shift
              ON shift.employee_id = allocation.employee_id
             AND shift.valid_from <= day.work_date::date
             AND (
               shift.valid_to IS NULL
               OR shift.valid_to >= day.work_date::date
             )
            JOIN workforce_shift_policy_versions policy
              ON policy.id = shift.shift_policy_version_id
            WHERE allocation.engagement_id = ?
              AND allocation.status IN ('PLANNED', 'ACTIVE')
              AND allocation.valid_from < ?
              AND (
                allocation.valid_to IS NULL
                OR allocation.valid_to >= ?
              )
            ORDER BY allocation.employee_id, day.work_date, allocation.id
            """, (result, row) -> new RosterDay(
                result.getObject("employee_id", UUID.class),
                result.getObject("work_date", LocalDate.class),
                result.getObject("project_allocation_id", UUID.class),
                result.getObject("project_id", UUID.class),
                result.getBigDecimal("allocation_percent"),
                result.getObject("shift_policy_version_id", UUID.class),
                result.getString("shift_policy_code"),
                result.getInt("shift_policy_version"),
                result.getString("timezone"),
                result.getString("expected_classification"),
                result.getInt("expected_minutes")),
            month.monthStart(), month.monthStart().plusMonths(1),
            month.monthStart().plusMonths(1), month.engagementId(),
            month.monthStart().plusMonths(1), month.monthStart());
    }

    private String rosterChecksum(List<RosterDay> days) {
        StringBuilder canonical = new StringBuilder();
        for (RosterDay day : days) {
            canonical.append(day.employeeId()).append('|')
                .append(day.workDate()).append('|')
                .append(day.projectAllocationId()).append('|')
                .append(day.projectId()).append('|')
                .append(day.allocationPercent().stripTrailingZeros()
                    .toPlainString()).append('|')
                .append(day.shiftPolicyVersionId()).append('|')
                .append(day.shiftPolicyCode()).append('|')
                .append(day.shiftPolicyVersion()).append('|')
                .append(day.timezone()).append('|')
                .append(day.expectedClassification()).append('|')
                .append(day.expectedMinutes()).append('\n');
        }
        return sha256(canonical.toString());
    }

    private List<RosterSnapshotView> rosterSnapshotViews(
        UUID engagementMonthId
    ) {
        return jdbc.query("""
            SELECT id, engagement_month_id, version, supersedes_id, status,
                   checksum, employee_count, employee_day_count,
                   finalized_at, finalized_by_subject, reason
            FROM workforce_roster_snapshot_versions
            WHERE engagement_month_id = ?
            ORDER BY version
            """, (result, row) -> new RosterSnapshotView(
                result.getObject("id", UUID.class),
                result.getObject("engagement_month_id", UUID.class),
                result.getInt("version"),
                result.getObject("supersedes_id", UUID.class),
                result.getString("status"), result.getString("checksum"),
                result.getInt("employee_count"),
                result.getInt("employee_day_count"),
                result.getObject("finalized_at", OffsetDateTime.class),
                result.getString("finalized_by_subject"),
                result.getString("reason")),
            engagementMonthId);
    }

    private void lockEngagementMonth(UUID engagementMonthId) {
        Integer locked = jdbc.query("""
            SELECT 1 FROM engagement_months WHERE id = ? FOR UPDATE
            """, result -> result.next() ? result.getInt(1) : null,
            engagementMonthId);
        if (locked == null) {
            throw notFound();
        }
    }

    private List<EmployeeAliasView> aliasViews(UUID employeeId) {
        return jdbc.query("""
            SELECT id, employee_id, alias_type, alias_value, valid_from,
                   valid_to, status, created_at
            FROM employee_aliases
            WHERE employee_id = ?
            ORDER BY valid_from DESC, alias_type, alias_value
            """, (result, row) -> new EmployeeAliasView(
                result.getObject("id", UUID.class),
                result.getObject("employee_id", UUID.class),
                result.getString("alias_type"),
                result.getString("alias_value"),
                result.getObject("valid_from", LocalDate.class),
                result.getObject("valid_to", LocalDate.class),
                result.getString("status"),
                result.getObject("created_at", OffsetDateTime.class)),
            employeeId);
    }

    private List<DeliverableAllocationView> deliverableAllocationViews(
        UUID employeeId
    ) {
        return jdbc.query("""
            SELECT allocation.id, allocation.employee_id,
                   allocation.project_allocation_id,
                   allocation.deliverable_id, deliverable.deliverable_code,
                   allocation.valid_from, allocation.valid_to,
                   allocation.allocation_percent,
                   allocation.role_on_deliverable, allocation.status
            FROM employee_deliverable_allocations allocation
            JOIN delivery_deliverables deliverable
              ON deliverable.id = allocation.deliverable_id
            WHERE allocation.employee_id = ?
            ORDER BY allocation.valid_from DESC, deliverable.deliverable_code
            """, (result, row) -> new DeliverableAllocationView(
                result.getObject("id", UUID.class),
                result.getObject("employee_id", UUID.class),
                result.getObject("project_allocation_id", UUID.class),
                result.getObject("deliverable_id", UUID.class),
                result.getString("deliverable_code"),
                result.getObject("valid_from", LocalDate.class),
                result.getObject("valid_to", LocalDate.class),
                result.getBigDecimal("allocation_percent"),
                result.getString("role_on_deliverable"),
                result.getString("status")),
            employeeId);
    }

    private List<CalendarVersionView> calendarViews(UUID organizationId) {
        return jdbc.query("""
            SELECT id, organization_id, name, timezone, version, valid_from,
                   valid_to, expected_full_minutes, expected_half_minutes
            FROM working_calendar_versions
            WHERE organization_id = ?
            ORDER BY name, version DESC
            """, (result, row) -> {
                UUID id = result.getObject("id", UUID.class);
                List<CalendarWeekdayInput> weekdays = jdbc.query("""
                    SELECT iso_weekday, classification, expected_minutes
                    FROM working_calendar_weekdays
                    WHERE calendar_version_id = ?
                    ORDER BY iso_weekday
                    """, (weekday, index) -> new CalendarWeekdayInput(
                        weekday.getInt("iso_weekday"),
                        weekday.getString("classification"),
                        weekday.getInt("expected_minutes")), id);
                List<CalendarHolidayInput> holidays = jdbc.query("""
                    SELECT holiday_date, name, classification, expected_minutes
                    FROM calendar_holidays
                    WHERE calendar_version_id = ?
                    ORDER BY holiday_date
                    """, (holiday, index) -> new CalendarHolidayInput(
                        holiday.getObject("holiday_date", LocalDate.class),
                        holiday.getString("name"),
                        holiday.getString("classification"),
                        holiday.getInt("expected_minutes")), id);
                return new CalendarVersionView(
                    id, result.getObject("organization_id", UUID.class),
                    result.getString("name"), result.getString("timezone"),
                    result.getInt("version"),
                    result.getObject("valid_from", LocalDate.class),
                    result.getObject("valid_to", LocalDate.class),
                    result.getInt("expected_full_minutes"),
                    result.getInt("expected_half_minutes"),
                    weekdays, holidays);
            }, organizationId);
    }

    private List<LeavePolicyView> leavePolicyViews(UUID organizationId) {
        return jdbc.query("""
            SELECT policy.id, policy.organization_id, policy.leave_type_id,
                   type.code, type.name, policy.version, policy.status,
                   policy.valid_from, policy.valid_to,
                   policy.approval_required,
                   policy.maximum_units_per_request,
                   policy.excess_to_lwp, policy.cancellation_allowed,
                   policy.rules::text, policy.published_at
            FROM leave_policy_versions policy
            JOIN leave_types type ON type.id = policy.leave_type_id
            WHERE policy.organization_id = ?
            ORDER BY type.code, policy.version DESC
            """, (result, row) -> new LeavePolicyView(
                result.getObject("id", UUID.class),
                result.getObject("organization_id", UUID.class),
                result.getObject("leave_type_id", UUID.class),
                result.getString("code"), result.getString("name"),
                result.getInt("version"), result.getString("status"),
                result.getObject("valid_from", LocalDate.class),
                result.getObject("valid_to", LocalDate.class),
                result.getBoolean("approval_required"),
                result.getBigDecimal("maximum_units_per_request"),
                result.getBoolean("excess_to_lwp"),
                result.getBoolean("cancellation_allowed"),
                jsonMap(result.getString("rules")),
                result.getObject("published_at", OffsetDateTime.class)),
            organizationId);
    }

    private List<LeaveBalanceCommandView> balanceCommandViews(
        UUID employeeId,
        String idempotencyKey
    ) {
        return jdbc.query("""
            SELECT id, employee_id, leave_type_id, command_type, quantity,
                   effective_date, idempotency_key, reason, created_at
            FROM leave_balance_commands
            WHERE employee_id = ? AND idempotency_key = ?
            """, (result, row) -> new LeaveBalanceCommandView(
                result.getObject("id", UUID.class),
                result.getObject("employee_id", UUID.class),
                result.getObject("leave_type_id", UUID.class),
                result.getString("command_type"),
                result.getBigDecimal("quantity"),
                result.getObject("effective_date", LocalDate.class),
                result.getString("idempotency_key"),
                result.getString("reason"),
                result.getObject("created_at", OffsetDateTime.class)),
            employeeId, idempotencyKey);
    }

    private boolean sameBalanceCommand(
        LeaveBalanceCommandView prior,
        LeaveBalanceCommandInput input
    ) {
        return prior.leaveTypeId().equals(input.leaveTypeId())
            && prior.commandType().equals(input.commandType())
            && prior.quantity().compareTo(input.quantity()) == 0
            && prior.effectiveDate().equals(input.effectiveDate())
            && prior.reason().equals(input.reason());
    }

    private LeaveTarget leaveTarget(UUID requestId, boolean lock) {
        String lockClause = lock ? " FOR UPDATE" : "";
        List<LeaveTarget> values = jdbc.query("""
            SELECT request.id, request.employee_id, request.leave_type_id,
                   request.status, request.version, request.paid_units,
                   request.lwp_units, request.start_date, type.balance_tracked,
                   employee.organization_id
            FROM leave_requests request
            JOIN employees employee ON employee.id = request.employee_id
            JOIN leave_types type ON type.id = request.leave_type_id
            WHERE request.id = ?
            """ + lockClause, (result, row) -> new LeaveTarget(
                result.getObject("id", UUID.class),
                result.getObject("employee_id", UUID.class),
                result.getObject("leave_type_id", UUID.class),
                result.getString("status"), result.getLong("version"),
                result.getBigDecimal("paid_units"),
                result.getBigDecimal("lwp_units"),
                result.getObject("start_date", LocalDate.class),
                result.getBoolean("balance_tracked"),
                result.getObject("organization_id", UUID.class)),
            requestId);
        if (values.isEmpty()) {
            throw notFound();
        }
        return values.getFirst();
    }

    private boolean cancellationAllowed(LeaveTarget target) {
        Boolean allowed = jdbc.query("""
            SELECT cancellation_allowed
            FROM leave_policy_versions
            WHERE organization_id = ? AND leave_type_id = ?
              AND status = 'PUBLISHED'
              AND valid_from <= ?
              AND (valid_to IS NULL OR valid_to >= ?)
            ORDER BY version DESC
            LIMIT 1
            """, result -> result.next() ? result.getBoolean(1) : null,
            target.organizationId(), target.leaveTypeId(),
            target.startDate(), target.startDate());
        return allowed == null || allowed;
    }

    private void insertLeaveLedger(
        LeaveTarget target,
        BigDecimal quantity,
        String entryType,
        String idempotencyKey,
        String reason,
        String subject
    ) {
        jdbc.update("""
            INSERT INTO leave_balance_ledger
                (id, employee_id, leave_type_id, entry_type, quantity,
                 effective_date, idempotency_key, reference_type, reference_id,
                 reason, recorded_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'LEAVE_REQUEST', ?, ?, ?)
            """, UUID.randomUUID(), target.employeeId(), target.leaveTypeId(),
            entryType, quantity, target.startDate(), idempotencyKey,
            target.id(), reason, subject);
    }

    private List<LeaveDecisionView> leaveDecisionViews(
        UUID leaveRequestId,
        String idempotencyKey
    ) {
        return jdbc.query("""
            SELECT decision.id, decision.leave_request_id, decision.decision,
                   decision.expected_request_version,
                   decision.resulting_request_status,
                   decision.resulting_request_version, decision.paid_units,
                   decision.lwp_units, decision.reason,
                   decision.decided_by_subject, decision.decided_at
            FROM leave_request_decisions decision
            WHERE decision.leave_request_id = ?
              AND decision.idempotency_key = ?
            """, (result, row) -> new LeaveDecisionView(
                result.getObject("id", UUID.class),
                result.getObject("leave_request_id", UUID.class),
                result.getString("decision"),
                result.getLong("expected_request_version"),
                result.getString("resulting_request_status"),
                result.getLong("resulting_request_version"),
                result.getBigDecimal("paid_units"),
                result.getBigDecimal("lwp_units"),
                result.getString("reason"),
                result.getString("decided_by_subject"),
                result.getObject("decided_at", OffsetDateTime.class)),
            leaveRequestId, idempotencyKey);
    }

    private WorkforceCsvImportView importReplay(
        UUID organizationId,
        String idempotencyKey,
        boolean replay
    ) {
        List<WorkforceCsvImportView> values = jdbc.query("""
            SELECT id, organization_id, import_type, original_file_name,
                   content_checksum, status, row_count, error_count
            FROM workforce_import_batches
            WHERE organization_id = ? AND idempotency_key = ?
            """, (result, row) -> {
                UUID id = result.getObject("id", UUID.class);
                List<WorkforceCsvErrorView> errors = jdbc.query("""
                    SELECT row_number, field_name, error_code, message
                    FROM workforce_import_errors
                    WHERE batch_id = ?
                    ORDER BY row_number, field_name
                    """, (error, index) -> new WorkforceCsvErrorView(
                        error.getInt("row_number"),
                        error.getString("field_name"),
                        error.getString("error_code"),
                        error.getString("message")), id);
                int count = result.getInt("row_count");
                String status = result.getString("status");
                return new WorkforceCsvImportView(
                    id, result.getObject("organization_id", UUID.class),
                    result.getString("import_type"),
                    result.getString("original_file_name"),
                    result.getString("content_checksum"), status, count,
                    "IMPORTED".equals(status) ? count : 0, errors, replay);
            }, organizationId, idempotencyKey);
        return values.isEmpty() ? null : values.getFirst();
    }

    private ParsedCsv parseCsv(String content) {
        List<List<String>> lines = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            if (!line.isBlank()) {
                lines.add(parseCsvLine(line));
            }
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("CSV file is empty.");
        }
        List<String> headers = lines.getFirst().stream()
            .map(String::trim).toList();
        if (headers.isEmpty() || headers.stream().anyMatch(String::isBlank)
            || headers.stream().distinct().count() != headers.size()) {
            throw new IllegalArgumentException(
                "CSV headers must be non-empty and unique.");
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            List<String> values = lines.get(index);
            Map<String, String> row = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                row.put(headers.get(column),
                    column < values.size() ? values.get(column).trim() : "");
            }
            rows.add(row);
        }
        return new ParsedCsv(headers, rows);
    }

    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length()
                    && line.charAt(index + 1) == '"') {
                    value.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                fields.add(value.toString());
                value.setLength(0);
            } else {
                value.append(current);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("CSV contains an unterminated quote.");
        }
        fields.add(value.toString());
        return fields;
    }

    private List<WorkforceCsvErrorView> validateCsv(
        UUID organizationId,
        String importType,
        ParsedCsv csv
    ) {
        List<String> required = switch (importType) {
            case "EMPLOYEE_ALIASES" -> List.of(
                "employeeNumber", "aliasType", "aliasValue", "validFrom");
            case "DELIVERABLE_ALLOCATIONS" -> List.of(
                "employeeNumber", "projectAllocationId", "deliverableId",
                "validFrom", "allocationPercent");
            case "LEAVE_BALANCE_COMMANDS" -> List.of(
                "employeeNumber", "leaveTypeCode", "commandType", "quantity",
                "effectiveDate", "reason", "rowKey");
            default -> throw new IllegalArgumentException("Unsupported import type.");
        };
        List<WorkforceCsvErrorView> errors = new ArrayList<>();
        for (String header : required) {
            if (!csv.headers().contains(header)) {
                errors.add(new WorkforceCsvErrorView(
                    2, header, "MISSING_HEADER",
                    "Required CSV header is missing."));
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        for (int index = 0; index < csv.rows().size(); index++) {
            Map<String, String> row = csv.rows().get(index);
            int rowNumber = index + 2;
            for (String field : required) {
                if (row.getOrDefault(field, "").isBlank()) {
                    errors.add(new WorkforceCsvErrorView(
                        rowNumber, field, "REQUIRED",
                        "A required value is missing."));
                }
            }
            UUID employeeId = employeeId(
                organizationId, row.get("employeeNumber"));
            if (employeeId == null) {
                errors.add(new WorkforceCsvErrorView(
                    rowNumber, "employeeNumber", "EMPLOYEE_NOT_FOUND",
                    "Employee number is unavailable in the authorized organization."));
            }
            try {
                switch (importType) {
                    case "EMPLOYEE_ALIASES" -> {
                        LocalDate.parse(row.get("validFrom"));
                        if (!ALIAS_TYPES.contains(row.get("aliasType"))) {
                            throw new IllegalArgumentException();
                        }
                    }
                    case "DELIVERABLE_ALLOCATIONS" -> {
                        UUID.fromString(row.get("projectAllocationId"));
                        UUID.fromString(row.get("deliverableId"));
                        LocalDate.parse(row.get("validFrom"));
                        new BigDecimal(row.get("allocationPercent"));
                    }
                    case "LEAVE_BALANCE_COMMANDS" -> {
                        LocalDate.parse(row.get("effectiveDate"));
                        new BigDecimal(row.get("quantity"));
                        if (!Set.of("ACCRUAL", "GRANT", "ADJUSTMENT")
                            .contains(row.get("commandType"))) {
                            throw new IllegalArgumentException();
                        }
                    }
                    default -> throw new IllegalArgumentException();
                }
            } catch (RuntimeException invalid) {
                errors.add(new WorkforceCsvErrorView(
                    rowNumber, "row", "INVALID_FORMAT",
                    "One or more typed values are invalid."));
            }
        }
        return errors;
    }

    private void applyCsv(
        String subject,
        UUID organizationId,
        String importType,
        ParsedCsv csv
    ) {
        for (int index = 0; index < csv.rows().size(); index++) {
            Map<String, String> row = csv.rows().get(index);
            UUID employeeId = employeeId(
                organizationId, row.get("employeeNumber"));
            String rowKey = row.getOrDefault(
                "rowKey", "row-" + (index + 2));
            switch (importType) {
                case "EMPLOYEE_ALIASES" -> addAlias(
                    subject, employeeId,
                    new EmployeeAliasInput(
                        row.get("aliasType"), row.get("aliasValue"),
                        LocalDate.parse(row.get("validFrom")),
                        optionalDate(row.get("validTo"))));
                case "DELIVERABLE_ALLOCATIONS" ->
                    addDeliverableAllocation(
                        subject, employeeId,
                        new DeliverableAllocationInput(
                            UUID.fromString(row.get("projectAllocationId")),
                            UUID.fromString(row.get("deliverableId")),
                            LocalDate.parse(row.get("validFrom")),
                            optionalDate(row.get("validTo")),
                            new BigDecimal(row.get("allocationPercent")),
                            emptyToNull(row.get("roleOnDeliverable"))));
                case "LEAVE_BALANCE_COMMANDS" -> {
                    UUID leaveTypeId = jdbc.query("""
                        SELECT id FROM leave_types
                        WHERE organization_id = ? AND code = ?
                        """, result -> result.next()
                            ? result.getObject(1, UUID.class) : null,
                        organizationId, row.get("leaveTypeCode"));
                    if (leaveTypeId == null) {
                        throw new IllegalArgumentException(
                            "CSV leave type is unavailable.");
                    }
                    recordBalanceCommand(
                        subject, employeeId,
                        new LeaveBalanceCommandInput(
                            leaveTypeId, row.get("commandType"),
                            new BigDecimal(row.get("quantity")),
                            LocalDate.parse(row.get("effectiveDate")),
                            "csv:" + rowKey, row.get("reason")));
                }
                default -> throw new IllegalArgumentException(
                    "Unsupported import type.");
            }
        }
    }

    private UUID employeeId(UUID organizationId, String employeeNumber) {
        if (employeeNumber == null || employeeNumber.isBlank()) {
            return null;
        }
        return jdbc.query("""
            SELECT id FROM employees
            WHERE organization_id = ? AND employee_number = ?
            """, result -> result.next()
                ? result.getObject(1, UUID.class) : null,
            organizationId, employeeNumber);
    }

    private LocalDate optionalDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private void validateDates(LocalDate from, LocalDate to, String label) {
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException(
                label + " end date cannot precede start date.");
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalArgumentException("Rules must be valid JSON.");
        }
    }

    private Map<String, Object> jsonMap(String value) {
        try {
            return objectMapper.readValue(value, JSON_MAP);
        } catch (JacksonException failure) {
            throw new IllegalStateException(
                "Stored leave policy rules are invalid.", failure);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private void lockEmployee(UUID employeeId) {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))",
            result -> null, employeeId.toString());
    }

    private void audit(
        String objectType,
        UUID objectId,
        UUID employeeId,
        String action,
        String subject
    ) {
        jdbc.update("""
            INSERT INTO workforce_audit_events
                (id, object_type, object_id, employee_id, action,
                 actor_subject, facts)
            VALUES (?, ?, ?, ?, ?, ?, '{}'::jsonb)
            """, UUID.randomUUID(), objectType, objectId, employeeId,
            action, subject);
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found.");
    }

    private record LeaveTarget(
        UUID id,
        UUID employeeId,
        UUID leaveTypeId,
        String status,
        long version,
        BigDecimal paidUnits,
        BigDecimal lwpUnits,
        LocalDate startDate,
        boolean balanceTracked,
        UUID organizationId
    ) {
    }

    private record RosterMonth(
        UUID engagementId,
        LocalDate monthStart
    ) {
    }

    private record RosterReadinessCounts(
        int employeeCount,
        int employeeDayCount,
        int missingCalendar,
        int missingShift,
        int missingEmployeeVersion,
        int missingSourceMode
    ) {
    }

    private record RosterDay(
        UUID employeeId,
        LocalDate workDate,
        UUID projectAllocationId,
        UUID projectId,
        BigDecimal allocationPercent,
        UUID shiftPolicyVersionId,
        String shiftPolicyCode,
        int shiftPolicyVersion,
        String timezone,
        String expectedClassification,
        int expectedMinutes
    ) {
    }

    private record ParsedCsv(
        List<String> headers,
        List<Map<String, String>> rows
    ) {
    }
}
