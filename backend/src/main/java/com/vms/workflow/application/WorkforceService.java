package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.api.WorkforceDtos.AllocationRequest;
import com.vms.workflow.api.WorkforceDtos.AllocationView;
import com.vms.workflow.api.WorkforceDtos.CreateEmployeeRequest;
import com.vms.workflow.api.WorkforceDtos.EmployeeLifecycleRequest;
import com.vms.workflow.api.WorkforceDtos.EmployeeView;
import com.vms.workflow.api.WorkforceDtos.LeaveBalanceView;
import com.vms.workflow.api.WorkforceDtos.LeaveRequest;
import com.vms.workflow.api.WorkforceDtos.LeaveRequestView;
import com.vms.workflow.api.WorkforceDtos.PolicyAssignmentRequest;
import com.vms.workflow.api.WorkforceDtos.PolicyAssignmentView;
import com.vms.workflow.security.WorkforceAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkforceService {
    private static final Set<String> EMPLOYMENT_STATUSES = Set.of(
        "PREBOARDING", "ACTIVE", "ON_LEAVE", "SUSPENDED", "EXITED", "ARCHIVED");
    private static final Set<String> ACTIVATION_STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> SOURCE_MODES = Set.of(
        "INTERNAL_AUTHORITATIVE", "GREYTHR_AUTHORITATIVE", "HYBRID_TRANSITION", "HISTORICAL_IMPORT");

    private static final String EMPLOYEE_SELECT = """
        SELECT emp.id, emp.organization_id, emp.employee_number,
               ev.first_name, ev.last_name, ev.display_name, emp.work_email,
               ev.employment_status, emp.join_date, ev.exit_date, ev.activation_status,
               source.mode, ev.valid_from, ev.valid_to, ev.version
        FROM employees emp
        JOIN employee_versions ev ON ev.employee_id = emp.id
          AND ev.valid_from <= CURRENT_DATE
          AND (ev.valid_to IS NULL OR ev.valid_to >= CURRENT_DATE)
        JOIN attendance_source_mode_assignments source ON source.employee_id = emp.id
          AND source.valid_from <= CURRENT_DATE
          AND (source.valid_to IS NULL OR source.valid_to >= CURRENT_DATE)
        """;

    private final JdbcTemplate jdbc;
    private final WorkforceAuthorizationService authorization;

    public WorkforceService(JdbcTemplate jdbc, WorkforceAuthorizationService authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    public List<EmployeeView> employees(String subject, UUID organizationId) {
        authorization.requireOrganizationRead(subject, organizationId);
        return jdbc.query(EMPLOYEE_SELECT + """
            WHERE emp.organization_id = ?
            ORDER BY emp.employee_number
            """, (rs, rowNum) -> employeeView(rs), organizationId);
    }

    @Transactional(readOnly = true)
    public EmployeeView employee(String subject, UUID employeeId) {
        authorization.requireEmployeeRead(subject, employeeId);
        return findEmployee(employeeId);
    }

    public EmployeeView me(String subject) {
        return findEmployee(authorization.activeSelfEmployee(subject));
    }

    @Transactional
    public EmployeeView createEmployee(String subject, CreateEmployeeRequest request) {
        authorization.requireOrganizationManage(subject, request.organizationId());
        validateSourceMode(request.attendanceSourceMode());
        UUID employeeId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO employees
                (id, organization_id, employee_number, work_email, user_profile_id,
                 join_date, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, employeeId, request.organizationId(), request.employeeNumber(), request.workEmail(),
            request.userProfileId(), request.joinDate(), subject);
        jdbc.update("""
            INSERT INTO employee_versions
                (id, employee_id, version, valid_from, first_name, last_name, display_name,
                 designation, employment_status, activation_status, recorded_by_subject)
            VALUES (?, ?, 1, ?, ?, ?, ?, ?, 'ACTIVE', 'ENABLED', ?)
            """, versionId, employeeId, request.joinDate(), request.firstName(), request.lastName(),
            request.displayName(), request.designation(), subject);
        jdbc.update("""
            INSERT INTO attendance_source_mode_assignments
                (id, employee_id, mode, authoritative_source, valid_from, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), employeeId, request.attendanceSourceMode(),
            authoritativeSource(request.attendanceSourceMode()), request.joinDate(), subject);
        audit("EMPLOYEE", employeeId, employeeId, "EMPLOYEE_CREATED", subject);
        return findEmployee(employeeId);
    }

    @Transactional
    public EmployeeView changeLifecycle(String subject, UUID employeeId,
                                        EmployeeLifecycleRequest request) {
        authorization.requireEmployeeManage(subject, employeeId);
        validateLifecycle(request);
        var current = jdbc.query("""
            SELECT id, version, valid_from, first_name, last_name, display_name, designation
            FROM employee_versions
            WHERE employee_id = ? AND valid_to IS NULL
            """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new CurrentEmployeeVersion(
                    rs.getObject("id", UUID.class), rs.getInt("version"),
                    rs.getObject("valid_from", LocalDate.class),
                    rs.getString("first_name"), rs.getString("last_name"),
                    rs.getString("display_name"), rs.getString("designation"));
            }, employeeId);
        if (current == null) {
            throw notFound();
        }
        if (!request.effectiveFrom().isAfter(current.validFrom())) {
            throw new DomainConflictException("Lifecycle changes must start after the current version.");
        }
        LocalDate joinDate = jdbc.queryForObject(
            "SELECT join_date FROM employees WHERE id = ?", LocalDate.class, employeeId);
        if (request.exitDate() != null && request.exitDate().isBefore(joinDate)) {
            throw new IllegalArgumentException("Exit date cannot precede join date.");
        }
        jdbc.update("UPDATE employee_versions SET valid_to = ? WHERE id = ?",
            request.effectiveFrom().minusDays(1), current.id());
        jdbc.update("""
            INSERT INTO employee_versions
                (id, employee_id, version, valid_from, first_name, last_name, display_name,
                 designation, employment_status, activation_status, exit_date, reason,
                 recorded_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), employeeId, current.version() + 1, request.effectiveFrom(),
            current.firstName(), current.lastName(), current.displayName(), current.designation(),
            request.employmentStatus(), request.activationStatus(), request.exitDate(),
            request.reason(), subject);
        return findEmployee(employeeId);
    }

    public List<AllocationView> allocations(String subject, UUID employeeId) {
        authorization.requireEmployeeRead(subject, employeeId);
        return jdbc.query("""
            SELECT id, employee_id, engagement_id, project_id, valid_from, valid_to,
                   allocation_percent, role_on_project, status
            FROM employee_project_allocations
            WHERE employee_id = ?
            ORDER BY valid_from, project_id
            """, (rs, rowNum) -> new AllocationView(
                rs.getObject("id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("valid_from", LocalDate.class),
                rs.getObject("valid_to", LocalDate.class),
                rs.getBigDecimal("allocation_percent"),
                rs.getString("role_on_project"),
                rs.getString("status")
            ), employeeId);
    }

    @Transactional
    public AllocationView createAllocation(String subject, UUID employeeId, AllocationRequest request) {
        authorization.requireEmployeeManage(subject, employeeId);
        if (request.validTo() != null && request.validTo().isBefore(request.validFrom())) {
            throw new IllegalArgumentException("Allocation end date cannot precede start date.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO employee_project_allocations
                (id, employee_id, engagement_id, project_id, valid_from, valid_to,
                 allocation_percent, role_on_project, status, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
            """, id, employeeId, request.engagementId(), request.projectId(), request.validFrom(),
            request.validTo(), request.allocationPercent(), request.roleOnProject(), subject);
        return allocations(subject, employeeId).stream()
            .filter(value -> value.id().equals(id))
            .findFirst()
            .orElseThrow(this::notFound);
    }

    @Transactional
    public PolicyAssignmentView assignPolicy(
        String subject,
        UUID employeeId,
        PolicyAssignmentRequest request
    ) {
        authorization.requireEmployeeManage(subject, employeeId);
        lockEmployee(employeeId);
        List<PolicyAssignmentView> existing = policyAssignments(
            employeeId, request.idempotencyKey());
        if (!existing.isEmpty()) {
            PolicyAssignmentView prior = existing.getFirst();
            if (!prior.calendarVersionId().equals(request.calendarVersionId())
                || !prior.leaveTypeId().equals(request.leaveTypeId())
                || prior.openingUnits().compareTo(request.openingUnits()) != 0
                || !prior.effectiveFrom().equals(request.effectiveFrom())
                || !prior.reason().equals(request.reason())) {
                throw new DomainConflictException(
                    "Idempotency key was already used for another policy assignment.");
            }
            return prior;
        }
        Boolean validPolicy = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM employees employee
                JOIN working_calendar_versions calendar
                  ON calendar.organization_id = employee.organization_id
                JOIN leave_types leave_type
                  ON leave_type.organization_id = employee.organization_id
                WHERE employee.id = ?
                  AND calendar.id = ?
                  AND calendar.valid_from <= ?
                  AND (calendar.valid_to IS NULL OR calendar.valid_to >= ?)
                  AND leave_type.id = ?
                  AND leave_type.status = 'ACTIVE'
            )
            """, Boolean.class, employeeId, request.calendarVersionId(),
            request.effectiveFrom(), request.effectiveFrom(),
            request.leaveTypeId());
        if (!Boolean.TRUE.equals(validPolicy)) {
            throw new IllegalArgumentException(
                "Calendar and leave policy must be active in the employee organization.");
        }
        Boolean calendarOverlap = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM employee_calendar_assignments
                WHERE employee_id = ?
                  AND daterange(valid_from, COALESCE(valid_to + 1, 'infinity'::date), '[)')
                      && daterange(?, 'infinity'::date, '[)')
            )
            """, Boolean.class, employeeId, request.effectiveFrom());
        if (Boolean.TRUE.equals(calendarOverlap)) {
            throw new DomainConflictException(
                "The employee already has an overlapping calendar assignment.");
        }
        UUID commandId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO employee_policy_assignment_commands
                (id, employee_id, calendar_version_id, leave_type_id,
                 opening_units, effective_from, idempotency_key, reason,
                 created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, commandId, employeeId, request.calendarVersionId(),
            request.leaveTypeId(), request.openingUnits(), request.effectiveFrom(),
            request.idempotencyKey(), request.reason(), subject);
        jdbc.update("""
            INSERT INTO employee_calendar_assignments
                (id, employee_id, calendar_version_id, valid_from)
            VALUES (?, ?, ?, ?)
            """, UUID.randomUUID(), employeeId, request.calendarVersionId(),
            request.effectiveFrom());
        jdbc.update("""
            INSERT INTO leave_balance_ledger
                (id, employee_id, leave_type_id, entry_type, quantity,
                 effective_date, idempotency_key, reference_type, reference_id,
                 reason, recorded_by_subject)
            VALUES (?, ?, ?, 'OPENING_BALANCE', ?, ?, ?, 'POLICY_ASSIGNMENT',
                    ?, ?, ?)
            """, UUID.randomUUID(), employeeId, request.leaveTypeId(),
            request.openingUnits(), request.effectiveFrom(),
            "policy:" + commandId, commandId, request.reason(),
            subject);
        audit("POLICY_ASSIGNMENT", commandId, employeeId,
            "POLICY_ASSIGNED", subject);
        return policyAssignments(employeeId, request.idempotencyKey()).getFirst();
    }

    private List<PolicyAssignmentView> policyAssignments(
        UUID employeeId,
        String idempotencyKey
    ) {
        return jdbc.query("""
            SELECT id, employee_id, calendar_version_id, leave_type_id,
                   opening_units, effective_from, idempotency_key, reason,
                   created_at
            FROM employee_policy_assignment_commands
            WHERE employee_id = ? AND idempotency_key = ?
            """, (result, row) -> new PolicyAssignmentView(
                result.getObject("id", UUID.class),
                result.getObject("employee_id", UUID.class),
                result.getObject("calendar_version_id", UUID.class),
                result.getObject("leave_type_id", UUID.class),
                result.getBigDecimal("opening_units"),
                result.getObject("effective_from", LocalDate.class),
                result.getString("idempotency_key"),
                result.getString("reason"),
                result.getObject("created_at", OffsetDateTime.class)
            ), employeeId, idempotencyKey);
    }

    public List<LeaveBalanceView> leaveBalances(String subject, UUID employeeId) {
        authorization.requireEmployeeRead(subject, employeeId);
        return jdbc.query("""
            SELECT lt.id, lt.code, lt.name, lt.paid,
                   COALESCE(SUM(ledger.quantity), 0) AS available_units
            FROM employees emp
            JOIN leave_types lt ON lt.organization_id = emp.organization_id
            LEFT JOIN leave_balance_ledger ledger
              ON ledger.employee_id = emp.id AND ledger.leave_type_id = lt.id
            WHERE emp.id = ? AND lt.status = 'ACTIVE'
            GROUP BY lt.id, lt.code, lt.name, lt.paid
            ORDER BY lt.code
            """, (rs, rowNum) -> new LeaveBalanceView(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("paid"),
                rs.getBigDecimal("available_units")
            ), employeeId);
    }

    public List<LeaveRequestView> leaveRequests(String subject, UUID employeeId) {
        authorization.requireEmployeeRead(subject, employeeId);
        return jdbc.query("""
            SELECT id, employee_id, leave_type_id, start_date, end_date, requested_units,
                   paid_units, lwp_units, reason, status, idempotency_key, created_at
            FROM leave_requests
            WHERE employee_id = ?
            ORDER BY created_at DESC
            """, (rs, rowNum) -> leaveRequestView(rs), employeeId);
    }

    @Transactional
    public LeaveRequestView createLeaveRequest(String subject, UUID employeeId, LeaveRequest request) {
        authorization.requireAttendanceSelf(subject, employeeId);
        lockEmployee(employeeId);
        List<LeaveRequestView> existing = jdbc.query("""
            SELECT id, employee_id, leave_type_id, start_date, end_date, requested_units,
                   paid_units, lwp_units, reason, status, idempotency_key, created_at
            FROM leave_requests
            WHERE employee_id = ? AND idempotency_key = ?
            """, (rs, rowNum) -> leaveRequestView(rs), employeeId, request.idempotencyKey());
        if (!existing.isEmpty()) {
            if (!sameLeaveRequest(existing.getFirst(), request)) {
                throw new DomainConflictException(
                    "Idempotency key was already used for another leave request.");
            }
            return existing.getFirst();
        }
        if (request.endDate().isBefore(request.startDate())) {
            throw new IllegalArgumentException("Leave end date cannot precede start date.");
        }
        Boolean overlaps = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM leave_requests
                WHERE employee_id = ?
                  AND status IN ('SUBMITTED', 'APPROVED')
                  AND daterange(start_date, end_date + 1, '[)')
                      && daterange(?, ? + 1, '[)')
            )
            """, Boolean.class, employeeId, request.startDate(), request.endDate());
        if (Boolean.TRUE.equals(overlaps)) {
            throw new DomainConflictException("The leave request overlaps an existing request.");
        }
        var type = jdbc.query("""
            SELECT lt.id, lt.paid, lt.balance_tracked, lt.minimum_increment
            FROM leave_types lt
            JOIN employees emp ON emp.organization_id = lt.organization_id
            WHERE lt.id = ? AND emp.id = ? AND lt.status = 'ACTIVE'
            """, rs -> rs.next()
                ? new LeaveType(rs.getObject("id", UUID.class), rs.getBoolean("paid"),
                    rs.getBoolean("balance_tracked"), rs.getBigDecimal("minimum_increment"))
                : null, request.leaveTypeId(), employeeId);
        if (type == null) {
            throw new IllegalArgumentException("Leave type is not available for the employee.");
        }
        List<LocalDate> eligibleDates = eligibleLeaveDates(
            employeeId, request.startDate(), request.endDate());
        validateLeaveUnits(request, type.minimumIncrement(), eligibleDates.size());
        BigDecimal balance = jdbc.queryForObject("""
            SELECT COALESCE(SUM(quantity), 0)
            FROM leave_balance_ledger
            WHERE employee_id = ? AND leave_type_id = ?
            """, BigDecimal.class, employeeId, request.leaveTypeId());
        BigDecimal paidUnits;
        if (!type.paid()) {
            paidUnits = BigDecimal.ZERO;
        } else if (!type.balanceTracked()) {
            paidUnits = request.units();
        } else {
            paidUnits = request.units().min(balance.max(BigDecimal.ZERO));
        }
        BigDecimal lwpUnits = request.units().subtract(paidUnits);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO leave_requests
                (id, employee_id, leave_type_id, start_date, end_date, requested_units,
                 paid_units, lwp_units, reason, status, idempotency_key, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'APPROVED', ?, ?)
            """, id, employeeId, request.leaveTypeId(), request.startDate(), request.endDate(),
            request.units(), paidUnits, lwpUnits, request.reason(), request.idempotencyKey(), subject);
        insertLeaveRequestDays(id, request, paidUnits, eligibleDates);
        if (paidUnits.signum() > 0 && type.balanceTracked()) {
            jdbc.update("""
                INSERT INTO leave_balance_ledger
                    (id, employee_id, leave_type_id, entry_type, quantity, effective_date,
                     idempotency_key, reference_type, reference_id, reason, recorded_by_subject)
                VALUES (?, ?, ?, 'LEAVE_CONSUMED', ?, ?, ?, 'LEAVE_REQUEST', ?, ?, ?)
                """, UUID.randomUUID(), employeeId, request.leaveTypeId(), paidUnits.negate(),
                request.startDate(), "leave-consume:" + request.idempotencyKey(), id,
                request.reason(), subject);
        }
        return leaveRequests(subject, employeeId).stream()
            .filter(value -> value.id().equals(id))
            .findFirst()
            .orElseThrow(this::notFound);
    }

    private void validateLeaveUnits(LeaveRequest request, BigDecimal minimumIncrement,
                                    int eligibleDayCount) {
        long inclusiveDayCount =
            ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;
        if (inclusiveDayCount > 366) {
            throw new IllegalArgumentException("Leave request date span cannot exceed 366 days.");
        }
        if (request.units().compareTo(BigDecimal.valueOf(eligibleDayCount)) > 0) {
            throw new IllegalArgumentException(
                "Requested leave units cannot exceed eligible working dates in the request span.");
        }
        if (request.units().stripTrailingZeros().scale() > 2
            || request.units().remainder(minimumIncrement).compareTo(BigDecimal.ZERO) != 0) {
            throw new IllegalArgumentException(
                "Requested leave units must use the leave type minimum increment.");
        }
    }

    private List<LocalDate> eligibleLeaveDates(UUID employeeId, LocalDate startDate,
                                               LocalDate endDate) {
        if (ChronoUnit.DAYS.between(startDate, endDate) + 1 > 366) {
            throw new IllegalArgumentException("Leave request date span cannot exceed 366 days.");
        }
        return startDate.datesUntil(endDate.plusDays(1))
            .filter(date -> isEligibleLeaveDate(employeeId, date))
            .toList();
    }

    private boolean isEligibleLeaveDate(UUID employeeId, LocalDate leaveDate) {
        Integer overrideMinutes = jdbc.query("""
            SELECT expected_minutes
            FROM employee_date_overrides
            WHERE employee_id = ? AND override_date = ?
            """, rs -> rs.next() ? rs.getInt("expected_minutes") : null,
            employeeId, leaveDate);
        if (overrideMinutes != null) {
            return overrideMinutes > 0;
        }
        Integer calendarMinutes = jdbc.query("""
            SELECT COALESCE(holiday.expected_minutes, weekday.expected_minutes)
                AS expected_minutes
            FROM employee_calendar_assignments assignment
            JOIN working_calendar_weekdays weekday
              ON weekday.calendar_version_id = assignment.calendar_version_id
             AND weekday.iso_weekday = EXTRACT(ISODOW FROM ?::date)
            LEFT JOIN calendar_holidays holiday
              ON holiday.calendar_version_id = assignment.calendar_version_id
             AND holiday.holiday_date = ?
            WHERE assignment.employee_id = ?
              AND assignment.valid_from <= ?
              AND (assignment.valid_to IS NULL OR assignment.valid_to >= ?)
            """, rs -> rs.next() ? rs.getInt("expected_minutes") : null,
            leaveDate, leaveDate, employeeId, leaveDate, leaveDate);
        return calendarMinutes == null || calendarMinutes > 0;
    }

    private void insertLeaveRequestDays(UUID leaveRequestId, LeaveRequest request,
                                        BigDecimal paidUnits,
                                        List<LocalDate> eligibleDates) {
        BigDecimal totalRemaining = request.units();
        BigDecimal paidRemaining = paidUnits;
        for (LocalDate leaveDate : eligibleDates) {
            if (totalRemaining.signum() <= 0) {
                break;
            }
            BigDecimal dayUnits = totalRemaining.min(BigDecimal.ONE);
            BigDecimal dayPaid = dayUnits.min(paidRemaining.max(BigDecimal.ZERO));
            BigDecimal dayLwp = dayUnits.subtract(dayPaid);
            jdbc.update("""
                INSERT INTO leave_request_days
                    (leave_request_id, leave_date, paid_units, lwp_units)
                VALUES (?, ?, ?, ?)
                """, leaveRequestId, leaveDate, dayPaid, dayLwp);
            totalRemaining = totalRemaining.subtract(dayUnits);
            paidRemaining = paidRemaining.subtract(dayPaid);
        }
        if (totalRemaining.signum() > 0) {
            throw new IllegalArgumentException(
                "Requested leave units cannot exceed eligible working dates in the request span.");
        }
    }

    private boolean sameLeaveRequest(LeaveRequestView existing, LeaveRequest request) {
        return existing.leaveTypeId().equals(request.leaveTypeId())
            && existing.startDate().equals(request.startDate())
            && existing.endDate().equals(request.endDate())
            && existing.units().compareTo(request.units()) == 0
            && existing.reason().equals(request.reason());
    }

    private void lockEmployee(UUID employeeId) {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))",
            rs -> null, employeeId.toString());
    }

    private EmployeeView findEmployee(UUID employeeId) {
        List<EmployeeView> values = jdbc.query(EMPLOYEE_SELECT + " WHERE emp.id = ?",
            (rs, rowNum) -> employeeView(rs), employeeId);
        if (values.isEmpty()) {
            throw notFound();
        }
        return values.getFirst();
    }

    private EmployeeView employeeView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EmployeeView(
            rs.getObject("id", UUID.class),
            rs.getObject("organization_id", UUID.class),
            rs.getString("employee_number"),
            rs.getString("first_name"),
            rs.getString("last_name"),
            rs.getString("display_name"),
            rs.getString("work_email"),
            rs.getString("employment_status"),
            rs.getObject("join_date", LocalDate.class),
            rs.getObject("exit_date", LocalDate.class),
            rs.getString("activation_status"),
            rs.getString("mode"),
            rs.getObject("valid_from", LocalDate.class),
            rs.getObject("valid_to", LocalDate.class),
            rs.getInt("version")
        );
    }

    private LeaveRequestView leaveRequestView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LeaveRequestView(
            rs.getObject("id", UUID.class),
            rs.getObject("employee_id", UUID.class),
            rs.getObject("leave_type_id", UUID.class),
            rs.getObject("start_date", LocalDate.class),
            rs.getObject("end_date", LocalDate.class),
            rs.getBigDecimal("requested_units"),
            rs.getBigDecimal("paid_units"),
            rs.getBigDecimal("lwp_units"),
            rs.getString("reason"),
            rs.getString("status"),
            rs.getString("idempotency_key"),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private void validateSourceMode(String mode) {
        if (!SOURCE_MODES.contains(mode)) {
            throw new IllegalArgumentException("Unsupported attendance source mode.");
        }
        if ("GREYTHR_AUTHORITATIVE".equals(mode)) {
            throw new DomainConflictException(
                "GREYTHR_AUTHORITATIVE requires a published tenant capability certification.");
        }
    }

    private void validateLifecycle(EmployeeLifecycleRequest request) {
        if (!EMPLOYMENT_STATUSES.contains(request.employmentStatus())) {
            throw new IllegalArgumentException("Unsupported employment status.");
        }
        if (!ACTIVATION_STATUSES.contains(request.activationStatus())) {
            throw new IllegalArgumentException("Unsupported activation status.");
        }
        if ("EXITED".equals(request.employmentStatus()) && request.exitDate() == null) {
            throw new IllegalArgumentException("Exited employees require an exit date.");
        }
    }

    private String authoritativeSource(String mode) {
        return switch (mode) {
            case "GREYTHR_AUTHORITATIVE" -> "GREYTHR";
            case "HISTORICAL_IMPORT" -> "IMPORT";
            default -> "INTERNAL";
        };
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found.");
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
            VALUES (?, ?, ?, ?, ?, ?,
                    jsonb_build_object('employeeId', ?::text))
            """, UUID.randomUUID(), objectType, objectId, employeeId, action,
            subject, employeeId.toString());
    }

    private record CurrentEmployeeVersion(
        UUID id,
        int version,
        LocalDate validFrom,
        String firstName,
        String lastName,
        String displayName,
        String designation
    ) {
    }

    private record LeaveType(
        UUID id,
        boolean paid,
        boolean balanceTracked,
        BigDecimal minimumIncrement
    ) {
    }
}
