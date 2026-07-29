package com.vms.workflow.application;

import com.vms.workflow.api.AttendanceDtos.AttendanceDayView;
import com.vms.workflow.api.AttendanceDtos.AttendanceSnapshotView;
import com.vms.workflow.api.AttendanceDtos.CloseSnapshotRequest;
import com.vms.workflow.api.AttendanceDtos.PunchRequest;
import com.vms.workflow.api.AttendanceDtos.PunchView;
import com.vms.workflow.api.AttendanceDtos.RegularizationRequest;
import com.vms.workflow.api.AttendanceDtos.RegularizationView;
import com.vms.workflow.api.AttendanceDtos.RegularizationDecisionRequest;
import com.vms.workflow.api.AttendanceDtos.RegularizationDecisionView;
import com.vms.workflow.api.AttendanceDtos.ReopenSnapshotRequest;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.security.WorkforceAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AttendanceService {
    private static final Set<String> PUNCH_TYPES = Set.of("CHECK_IN", "CHECK_OUT");

    private final JdbcTemplate jdbc;
    private final WorkforceAuthorizationService authorization;
    private final Clock clock;

    public AttendanceService(
        JdbcTemplate jdbc,
        WorkforceAuthorizationService authorization,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.clock = clock;
    }

    @Transactional
    public PunchView punch(String subject, PunchRequest request) {
        authorization.requireAttendanceSelf(subject, request.employeeId());
        if (!PUNCH_TYPES.contains(request.eventType())) {
            throw new IllegalArgumentException("eventType must be CHECK_IN or CHECK_OUT.");
        }
        lockEmployee(request.employeeId());
        List<PunchView> existing = findPunch(request.employeeId(), request.idempotencyKey());
        if (!existing.isEmpty()) {
            if (!existing.getFirst().eventType().equals(request.eventType())) {
                throw new DomainConflictException("Idempotency key was already used for another event type.");
            }
            return existing.getFirst();
        }
        EmployeeAttendanceState employee = employeeState(request.employeeId(), LocalDate.now(clock));
        validateEffectiveSourceCapability(employee);
        if (!"ACTIVE".equals(employee.employmentStatus())
            || !"ENABLED".equals(employee.activationStatus())) {
            throw new DomainConflictException("Employee is not enabled for attendance.");
        }
        if (!"INTERNAL_AUTHORITATIVE".equals(employee.sourceMode())) {
            throw new DomainConflictException(
                "Internal punches are unavailable for the effective attendance source mode.");
        }
        OffsetDateTime occurredAt = OffsetDateTime.now(clock);
        LocalDate workDate = occurredAt.atZoneSameInstant(ZoneId.of(employee.timezone())).toLocalDate();
        UUID eventId = UUID.randomUUID();
        if ("CHECK_IN".equals(request.eventType())) {
            Boolean open = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM attendance_sessions
                    WHERE employee_id = ? AND status = 'OPEN'
                )
                """, Boolean.class, request.employeeId());
            if (Boolean.TRUE.equals(open)) {
                throw new DomainConflictException("An open attendance session already exists.");
            }
            jdbc.update("""
                INSERT INTO attendance_events
                    (id, employee_id, event_type, occurred_at, work_date, source,
                     idempotency_key, recorded_by_subject)
                VALUES (?, ?, 'CHECK_IN', ?, ?, 'INTERNAL_WEB', ?, ?)
                """, eventId, request.employeeId(), occurredAt, workDate,
                request.idempotencyKey(), subject);
            jdbc.update("""
                INSERT INTO attendance_sessions
                    (id, employee_id, work_date, check_in_event_id, check_in_at, status)
                VALUES (?, ?, ?, ?, ?, 'OPEN')
                """, UUID.randomUUID(), request.employeeId(), workDate, eventId, occurredAt);
            materializeDay(request.employeeId(), workDate);
            audit("ATTENDANCE_EVENT", eventId, request.employeeId(),
                "CHECK_IN", subject);
        } else {
            OpenSession session = jdbc.query("""
                SELECT id, check_in_at, work_date
                FROM attendance_sessions
                WHERE employee_id = ? AND status = 'OPEN'
                """, rs -> rs.next()
                    ? new OpenSession(rs.getObject("id", UUID.class),
                        rs.getObject("check_in_at", OffsetDateTime.class),
                        rs.getObject("work_date", LocalDate.class))
                    : null, request.employeeId());
            if (session == null) {
                throw new DomainConflictException("No open attendance session exists.");
            }
            occurredAt = normalizeCheckoutInstant(
                session.checkInAt(), occurredAt);
            int netMinutes = Math.toIntExact(Duration.between(session.checkInAt(), occurredAt).toMinutes());
            workDate = session.workDate();
            jdbc.update("""
                INSERT INTO attendance_events
                    (id, employee_id, event_type, occurred_at, work_date, source,
                     idempotency_key, recorded_by_subject)
                VALUES (?, ?, 'CHECK_OUT', ?, ?, 'INTERNAL_WEB', ?, ?)
                """, eventId, request.employeeId(), occurredAt, workDate,
                request.idempotencyKey(), subject);
            jdbc.update("""
                UPDATE attendance_sessions
                SET check_out_event_id = ?, check_out_at = ?, net_minutes = ?, status = 'CLOSED'
                WHERE id = ?
                """, eventId, occurredAt, netMinutes, session.id());
            materializeDay(request.employeeId(), workDate);
            audit("ATTENDANCE_EVENT", eventId, request.employeeId(),
                "CHECK_OUT", subject);
        }
        return findPunch(request.employeeId(), request.idempotencyKey()).getFirst();
    }

    static OffsetDateTime normalizeCheckoutInstant(
        OffsetDateTime checkInAt,
        OffsetDateTime observedCheckoutAt
    ) {
        if (observedCheckoutAt.isBefore(checkInAt)) {
            throw new DomainConflictException(
                "Checkout cannot precede check-in.");
        }
        // PostgreSQL requires check_out_at > check_in_at. Equal instants can
        // legitimately occur with a fixed clock or inside one clock tick.
        // Keep calculated minutes at zero while persisting monotonic evidence.
        return observedCheckoutAt.equals(checkInAt)
            ? checkInAt.plusNanos(1_000_000)
            : observedCheckoutAt;
    }

    @Transactional(readOnly = true)
    public List<AttendanceDayView> days(String subject, UUID employeeId, LocalDate from, LocalDate to) {
        authorization.requireAttendanceAccess(subject, employeeId);
        if (to.isBefore(from) || from.plusDays(62).isBefore(to)) {
            throw new IllegalArgumentException("Attendance range must be ordered and at most 63 days.");
        }
        return from.datesUntil(to.plusDays(1))
            .map(date -> readDay(employeeId, date))
            .toList();
    }

    public List<RegularizationView> regularizations(String subject, UUID employeeId) {
        authorization.requireAttendanceAccess(subject, employeeId);
        return jdbc.query("""
            SELECT id, employee_id, work_date, reason_code, narrative, requested_outcome,
                   idempotency_key, status, created_at
            FROM attendance_regularizations
            WHERE employee_id = ?
            ORDER BY created_at DESC
            """, (rs, rowNum) -> regularizationView(rs), employeeId);
    }

    @Transactional
    public RegularizationView createRegularization(String subject, RegularizationRequest request) {
        authorization.requireAttendanceSelf(subject, request.employeeId());
        lockEmployee(request.employeeId());
        List<RegularizationView> existing = jdbc.query("""
            SELECT id, employee_id, work_date, reason_code, narrative, requested_outcome,
                   idempotency_key, status, created_at
            FROM attendance_regularizations
            WHERE employee_id = ? AND idempotency_key = ?
            """, (rs, rowNum) -> regularizationView(rs),
            request.employeeId(), request.idempotencyKey());
        if (!existing.isEmpty()) {
            if (!sameRegularization(existing.getFirst(), request)) {
                throw new DomainConflictException(
                    "Idempotency key was already used for another regularization.");
            }
            return existing.getFirst();
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO attendance_regularizations
                (id, employee_id, work_date, reason_code, narrative, requested_outcome,
                 idempotency_key, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, id, request.employeeId(), request.workDate(), request.reasonCode(),
            request.narrative(), request.requestedOutcome(), request.idempotencyKey(), subject);
        audit("ATTENDANCE_REGULARIZATION", id, request.employeeId(),
            "REGULARIZATION_SUBMITTED", subject);
        return regularizations(subject, request.employeeId()).stream()
            .filter(value -> value.id().equals(id))
            .findFirst()
            .orElseThrow(this::notFound);
    }

    @Transactional
    public RegularizationDecisionView decideRegularization(
        String subject,
        UUID regularizationId,
        RegularizationDecisionRequest request
    ) {
        RegularizationTarget target = regularizationTarget(
            regularizationId, false);
        if (target == null) {
            throw notFound();
        }
        authorization.requireEmployeeManage(subject, target.employeeId());
        lockEmployee(target.employeeId());
        RegularizationTarget locked = regularizationTarget(
            regularizationId, true);
        if (locked == null
            || !locked.employeeId().equals(target.employeeId())
            || !locked.workDate().equals(target.workDate())) {
            throw notFound();
        }
        target = locked;
        List<RegularizationDecisionView> existing =
            regularizationDecisions(regularizationId);
        if (!existing.isEmpty()) {
            RegularizationDecisionView prior = existing.getFirst();
            if (!prior.decision().equals(request.decision())
                || !java.util.Objects.equals(
                    prior.adjustedNetMinutes(), request.adjustedNetMinutes())
                || !prior.reasoning().equals(request.reasoning())) {
                throw new DomainConflictException(
                    "The regularization already has a different terminal decision.");
            }
            return prior;
        }
        if (!"SUBMITTED".equals(target.status())
            && !"UNDER_REVIEW".equals(target.status())) {
            throw new DomainConflictException(
                "Only a pending regularization can be decided.");
        }
        if ("APPROVE".equals(request.decision())
            && request.adjustedNetMinutes() == null) {
            throw new IllegalArgumentException(
                "An approved regularization requires adjustedNetMinutes.");
        }
        if ("REJECT".equals(request.decision())
            && request.adjustedNetMinutes() != null) {
            throw new IllegalArgumentException(
                "A rejected regularization cannot adjust attendance minutes.");
        }
        UUID decisionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO attendance_regularization_decisions
                (id, regularization_id, decision, adjusted_net_minutes,
                 reasoning, decided_by_subject)
            VALUES (?, ?, ?, ?, ?, ?)
            """, decisionId, regularizationId, request.decision(),
            request.adjustedNetMinutes(), request.reasoning(), subject);
        if ("APPROVE".equals(request.decision())) {
            PriorAdjustment prior = jdbc.query("""
                SELECT id, adjustment_version
                FROM attendance_regularization_adjustments
                WHERE employee_id = ? AND work_date = ?
                ORDER BY adjustment_version DESC
                LIMIT 1
                FOR UPDATE
                """, result -> result.next() ? new PriorAdjustment(
                    result.getObject("id", UUID.class),
                    result.getInt("adjustment_version")
                ) : null, target.employeeId(), target.workDate());
            int nextVersion = prior == null ? 1 : prior.version() + 1;
            jdbc.update("""
                INSERT INTO attendance_regularization_adjustments
                    (id, regularization_id, employee_id, work_date,
                     adjustment_version, supersedes_adjustment_id,
                     supersedes_adjustment_version,
                     adjusted_net_minutes, reason, recorded_by_subject)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), regularizationId, target.employeeId(),
                target.workDate(), nextVersion,
                prior == null ? null : prior.id(),
                prior == null ? null : prior.version(),
                request.adjustedNetMinutes(), request.reasoning(), subject);
        }
        jdbc.update("""
            UPDATE attendance_regularizations SET status = ?
            WHERE id = ?
            """, "APPROVE".equals(request.decision()) ? "APPROVED" : "REJECTED",
            regularizationId);
        materializeDay(target.employeeId(), target.workDate());
        audit("REGULARIZATION_DECISION", decisionId, target.employeeId(),
            "APPROVE".equals(request.decision())
                ? "REGULARIZATION_APPROVED" : "REGULARIZATION_REJECTED",
            subject);
        return regularizationDecisions(regularizationId).getFirst();
    }

    private RegularizationTarget regularizationTarget(
        UUID regularizationId,
        boolean lock
    ) {
        return jdbc.query(("""
            SELECT id, employee_id, work_date, status
            FROM attendance_regularizations
            WHERE id = ?
            """ + (lock ? " FOR UPDATE" : "")), result ->
            result.next() ? new RegularizationTarget(
                result.getObject("id", UUID.class),
                result.getObject("employee_id", UUID.class),
                result.getObject("work_date", LocalDate.class),
                result.getString("status")
            ) : null, regularizationId);
    }

    private List<RegularizationDecisionView> regularizationDecisions(UUID id) {
        return jdbc.query("""
            SELECT id, regularization_id, decision, adjusted_net_minutes,
                   reasoning, decided_by_subject, decided_at
            FROM attendance_regularization_decisions
            WHERE regularization_id = ?
            """, (result, row) -> new RegularizationDecisionView(
                result.getObject("id", UUID.class),
                result.getObject("regularization_id", UUID.class),
                result.getString("decision"),
                result.getObject("adjusted_net_minutes", Integer.class),
                result.getString("reasoning"),
                result.getString("decided_by_subject"),
                result.getObject("decided_at", OffsetDateTime.class)
            ), id);
    }

    public List<AttendanceSnapshotView> snapshots(String subject, UUID engagementMonthId) {
        MonthScope month = monthScope(engagementMonthId);
        authorization.requireEngagementClose(subject, month.engagementId());
        return jdbc.query("""
            SELECT id, engagement_month_id, version, status, supersedes_id, closed_at,
                   checksum, day_count
            FROM attendance_snapshot_versions
            WHERE engagement_month_id = ?
            ORDER BY version
            """, (rs, rowNum) -> snapshotView(rs), engagementMonthId);
    }

    @Transactional
    public AttendanceSnapshotView closeSnapshot(String subject, CloseSnapshotRequest request) {
        MonthScope month = monthScope(request.engagementMonthId());
        authorization.requireEngagementClose(subject, month.engagementId());
        lockEngagementMonth(request.engagementMonthId());
        List<AttendanceSnapshotView> existing = currentSnapshot(request.engagementMonthId());
        if (!existing.isEmpty() && "CLOSED".equals(existing.getFirst().status())) {
            return existing.getFirst();
        }
        materializeAllocatedDays(month);
        Boolean unresolved = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM attendance_exceptions ex
                JOIN employee_project_allocations a ON a.employee_id = ex.employee_id
                  AND a.engagement_id = ?
                  AND a.status IN ('PLANNED', 'ACTIVE')
                  AND a.valid_from <= ex.work_date
                  AND (a.valid_to IS NULL OR a.valid_to >= ex.work_date)
                WHERE ex.work_date >= ? AND ex.work_date < ?
                  AND ex.status = 'OPEN'
                  AND ex.exception_code IN ('MISSING_CHECKOUT', 'SOURCE_CONFLICT')
            )
            """, Boolean.class, month.engagementId(), month.monthStart(), month.monthStart().plusMonths(1));
        if (Boolean.TRUE.equals(unresolved)) {
            throw new DomainConflictException(
                "Attendance month has unresolved missing-checkout or source-conflict exceptions.");
        }
        List<SnapshotDay> days = snapshotDays(month);
        String checksum = checksum(days);
        UUID snapshotId = UUID.randomUUID();
        UUID supersedesId = existing.isEmpty() ? null : existing.getFirst().id();
        int version = existing.isEmpty() ? 1 : existing.getFirst().version() + 1;
        jdbc.update("""
            INSERT INTO attendance_snapshot_versions
                (id, engagement_month_id, version, supersedes_id, status,
                 checksum, day_count, closed_by_subject)
            VALUES (?, ?, ?, ?, 'CLOSED', ?, ?, ?)
            """, snapshotId, request.engagementMonthId(), version, supersedesId,
            checksum, days.size(), subject);
        insertSnapshotDays(snapshotId, days);
        audit("ATTENDANCE_SNAPSHOT", snapshotId, null,
            "SNAPSHOT_CLOSED", subject);
        return snapshot(snapshotId);
    }

    @Transactional
    public AttendanceSnapshotView reopenSnapshot(String subject, UUID snapshotId,
                                                 ReopenSnapshotRequest request) {
        SnapshotScope original = jdbc.query("""
            SELECT s.engagement_month_id, em.engagement_id, s.checksum, s.status
            FROM attendance_snapshot_versions s
            JOIN engagement_months em ON em.id = s.engagement_month_id
            WHERE s.id = ?
            """, rs -> rs.next()
                ? new SnapshotScope(
                    rs.getObject("engagement_month_id", UUID.class),
                    rs.getObject("engagement_id", UUID.class),
                    rs.getString("checksum"),
                    rs.getString("status"))
                : null, snapshotId);
        if (original == null) {
            throw notFound();
        }
        authorization.requireEngagementReopen(subject, original.engagementId());
        lockEngagementMonth(original.engagementMonthId());
        List<AttendanceSnapshotView> leaf = currentSnapshot(original.engagementMonthId());
        if (!"CLOSED".equals(original.status())
            || leaf.size() != 1
            || !leaf.getFirst().id().equals(snapshotId)) {
            throw new DomainConflictException(
                "Only the current closed attendance snapshot can be reopened.");
        }
        int version = leaf.getFirst().version() + 1;
        UUID replacementId = UUID.randomUUID();
        int copied = jdbc.queryForObject("""
            SELECT COUNT(*) FROM attendance_snapshot_days WHERE snapshot_id = ?
            """, Integer.class, snapshotId);
        jdbc.update("""
            INSERT INTO attendance_snapshot_versions
                (id, engagement_month_id, version, supersedes_id, status, checksum, day_count,
                 closed_by_subject, reopen_reason)
            VALUES (?, ?, ?, ?, 'REOPENED', ?, ?, ?, ?)
            """, replacementId, original.engagementMonthId(), version, snapshotId,
            original.checksum(), copied, subject, request.reason());
        jdbc.update("""
            INSERT INTO attendance_snapshot_days
                (snapshot_id, attendance_day_id, employee_id, work_date,
                 final_status, net_minutes, source_mode)
            SELECT ?, attendance_day_id, employee_id, work_date,
                   final_status, net_minutes, source_mode
            FROM attendance_snapshot_days
            WHERE snapshot_id = ?
            """, replacementId, snapshotId);
        return snapshot(replacementId);
    }

    private AttendanceDayView readDay(UUID employeeId, LocalDate workDate) {
        DayCalculation calculation = evaluateDay(employeeId, workDate);
        List<AttendanceDayView> current = currentDay(employeeId, workDate);
        if (!current.isEmpty() && sameCalculation(current.getFirst(), calculation)) {
            return current.getFirst();
        }
        int nextVersion = current.isEmpty() ? 1 : current.getFirst().calculationVersion() + 1;
        return new AttendanceDayView(
            null,
            employeeId,
            workDate,
            calculation.expectation().classification(),
            calculation.expectation().expectedMinutes(),
            calculation.state().sourceMode(),
            calculation.netMinutes(),
            calculation.leave().paidUnits().add(calculation.leave().lwpUnits()),
            calculation.leave().leaveCode(),
            calculation.finalStatus(),
            calculation.exceptionCode(),
            nextVersion,
            OffsetDateTime.now(clock)
        );
    }

    private AttendanceDayView materializeDay(UUID employeeId, LocalDate workDate) {
        lockEmployee(employeeId);
        DayCalculation calculation = evaluateDay(employeeId, workDate);
        syncMissingCheckoutException(employeeId, workDate, calculation.exceptionCode());
        List<AttendanceDayView> current = currentDay(employeeId, workDate);
        if (!current.isEmpty() && sameCalculation(current.getFirst(), calculation)) {
            return current.getFirst();
        }
        int version = current.isEmpty() ? 1 : current.getFirst().calculationVersion() + 1;
        jdbc.update("""
            UPDATE attendance_days SET is_current = FALSE
            WHERE employee_id = ? AND work_date = ? AND is_current
            """, employeeId, workDate);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO attendance_days
                (id, employee_id, work_date, calculation_version, expected_classification,
                 expected_minutes, source_mode, net_minutes, leave_units, leave_type_code,
                 final_status, exception_code)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, employeeId, workDate, version,
            calculation.expectation().classification(),
            calculation.expectation().expectedMinutes(),
            calculation.state().sourceMode(),
            calculation.netMinutes(),
            calculation.leave().paidUnits().add(calculation.leave().lwpUnits()),
            calculation.leave().leaveCode(),
            calculation.finalStatus(),
            calculation.exceptionCode());
        return jdbc.query("""
            SELECT id, employee_id, work_date, expected_classification, expected_minutes,
                   source_mode, net_minutes, leave_units, leave_type_code, final_status,
                   exception_code, calculation_version, computed_at
            FROM attendance_days WHERE id = ?
            """, rs -> {
                rs.next();
                return dayView(rs);
            }, id);
    }

    private DayCalculation evaluateDay(UUID employeeId, LocalDate workDate) {
        CalendarExpectation expectation = expectation(employeeId, workDate);
        EmployeeAttendanceState state = employeeState(employeeId, workDate);
        validateEffectiveSourceCapability(state);
        Integer netMinutes = jdbc.queryForObject("""
            SELECT COALESCE(SUM(net_minutes), 0)
            FROM attendance_sessions
            WHERE employee_id = ? AND work_date = ? AND status = 'CLOSED'
            """, Integer.class, employeeId, workDate);
        Integer adjustedMinutes = jdbc.query("""
            SELECT adjusted_net_minutes
            FROM attendance_regularization_adjustments
            WHERE employee_id = ? AND work_date = ?
            ORDER BY adjustment_version DESC
            LIMIT 1
            """, result -> result.next() ? result.getInt(1) : null,
            employeeId, workDate);
        if (adjustedMinutes != null) {
            netMinutes = adjustedMinutes;
        }
        Boolean open = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM attendance_sessions
                WHERE employee_id = ? AND work_date = ? AND status = 'OPEN'
            )
            """, Boolean.class, employeeId, workDate);
        LeaveForDay leave = jdbc.query("""
            SELECT COALESCE(SUM(day.paid_units), 0) AS paid_units,
                   COALESCE(SUM(day.lwp_units), 0) AS lwp_units,
                   MAX(lt.code) AS leave_code
            FROM leave_request_days day
            JOIN leave_requests lr ON lr.id = day.leave_request_id
            JOIN leave_types lt ON lt.id = lr.leave_type_id
            WHERE lr.employee_id = ? AND lr.status = 'APPROVED'
              AND day.leave_date = ?
            """, rs -> {
                rs.next();
                return new LeaveForDay(rs.getBigDecimal("paid_units"),
                    rs.getBigDecimal("lwp_units"), rs.getString("leave_code"));
            }, employeeId, workDate);
        var localNow = OffsetDateTime.now(clock)
            .atZoneSameInstant(ZoneId.of(state.timezone()));
        LocalDate localToday = localNow.toLocalDate();
        boolean cutoffPassed = workDate.isBefore(localToday)
            || (workDate.equals(localToday)
                && localNow.toLocalTime().isAfter(state.missingCheckoutCutoff()));
        String exception = Boolean.TRUE.equals(open)
            ? (cutoffPassed ? "MISSING_CHECKOUT" : "OPEN_SESSION")
            : null;
        String status = classify(expectation, netMinutes, leave, exception);
        return new DayCalculation(expectation, state, netMinutes, leave, status, exception);
    }

    private void syncMissingCheckoutException(UUID employeeId, LocalDate workDate,
                                              String exceptionCode) {
        if ("MISSING_CHECKOUT".equals(exceptionCode)) {
            jdbc.update("""
                INSERT INTO attendance_exceptions
                    (id, employee_id, work_date, exception_code)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (employee_id, work_date, exception_code) DO NOTHING
                """, UUID.randomUUID(), employeeId, workDate, exceptionCode);
        } else {
            jdbc.update("""
                UPDATE attendance_exceptions
                SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP
                WHERE employee_id = ? AND work_date = ?
                  AND exception_code = 'MISSING_CHECKOUT' AND status = 'OPEN'
                """, employeeId, workDate);
        }
    }

    private List<AttendanceDayView> currentDay(UUID employeeId, LocalDate workDate) {
        return jdbc.query("""
            SELECT id, employee_id, work_date, expected_classification, expected_minutes,
                   source_mode, net_minutes, leave_units, leave_type_code, final_status,
                   exception_code, calculation_version, computed_at
            FROM attendance_days
            WHERE employee_id = ? AND work_date = ? AND is_current
            """, (rs, rowNum) -> dayView(rs), employeeId, workDate);
    }

    private CalendarExpectation expectation(UUID employeeId, LocalDate workDate) {
        CalendarExpectation override = jdbc.query("""
            SELECT classification, expected_minutes
            FROM employee_date_overrides
            WHERE employee_id = ? AND override_date = ?
            """, rs -> rs.next()
                ? new CalendarExpectation(rs.getString("classification"), rs.getInt("expected_minutes"))
                : null, employeeId, workDate);
        if (override != null) {
            return override;
        }
        CalendarExpectation calendar = jdbc.query("""
            SELECT COALESCE(h.classification, w.classification) AS classification,
                   COALESCE(h.expected_minutes, w.expected_minutes) AS expected_minutes
            FROM employee_calendar_assignments a
            JOIN working_calendar_weekdays w
              ON w.calendar_version_id = a.calendar_version_id
             AND w.iso_weekday = EXTRACT(ISODOW FROM ?::date)
            LEFT JOIN calendar_holidays h
              ON h.calendar_version_id = a.calendar_version_id
             AND h.holiday_date = ?
            WHERE a.employee_id = ?
              AND a.valid_from <= ?
              AND (a.valid_to IS NULL OR a.valid_to >= ?)
            """, rs -> rs.next()
                ? new CalendarExpectation(rs.getString("classification"), rs.getInt("expected_minutes"))
                : null, workDate, workDate, employeeId, workDate, workDate);
        return calendar == null ? new CalendarExpectation("WORKING", 540) : calendar;
    }

    private EmployeeAttendanceState employeeState(UUID employeeId, LocalDate workDate) {
        EmployeeAttendanceState state = jdbc.query("""
            SELECT ev.employment_status, ev.activation_status, source.mode,
                   source.authoritative_source,
                   CASE
                     WHEN certification.id IS NOT NULL
                      AND certification.organization_id = emp.organization_id
                      AND certification.provider = 'GREYTHR'
                      AND certification.status = 'CERTIFIED'
                     THEN TRUE ELSE FALSE
                   END AS capability_certified,
                   COALESCE(calendar.timezone, 'Asia/Kolkata') AS timezone,
                   COALESCE(calendar.missing_checkout_cutoff_local_time, TIME '23:59')
                       AS missing_checkout_cutoff
            FROM employees emp
            JOIN employee_versions ev ON ev.employee_id = emp.id
              AND ev.valid_from <= ?
              AND (ev.valid_to IS NULL OR ev.valid_to >= ?)
            JOIN attendance_source_mode_assignments source ON source.employee_id = emp.id
              AND source.valid_from <= ?
              AND (source.valid_to IS NULL OR source.valid_to >= ?)
            LEFT JOIN integration_capability_certifications certification
              ON certification.id = source.capability_certification_id
            LEFT JOIN employee_calendar_assignments assignment ON assignment.employee_id = emp.id
              AND assignment.valid_from <= ?
              AND (assignment.valid_to IS NULL OR assignment.valid_to >= ?)
            LEFT JOIN working_calendar_versions calendar ON calendar.id = assignment.calendar_version_id
            WHERE emp.id = ?
            """, rs -> rs.next()
                ? new EmployeeAttendanceState(rs.getString("employment_status"),
                    rs.getString("activation_status"), rs.getString("mode"),
                    rs.getString("authoritative_source"),
                    rs.getBoolean("capability_certified"),
                    rs.getString("timezone"),
                    rs.getObject("missing_checkout_cutoff", LocalTime.class))
                : null, workDate, workDate, workDate, workDate, workDate, workDate, employeeId);
        if (state == null) {
            throw notFound();
        }
        return state;
    }

    private void validateEffectiveSourceCapability(EmployeeAttendanceState state) {
        if ("GREYTHR".equals(state.authoritativeSource()) && !state.capabilityCertified()) {
            throw new DomainConflictException(
                "The effective attendance source capability is no longer certified.");
        }
    }

    private String classify(CalendarExpectation expectation, int netMinutes,
                            LeaveForDay leave, String exception) {
        if ("MISSING_CHECKOUT".equals(exception)) {
            return "MISSING_CHECKOUT_EXCEPTION";
        }
        if ("OPEN_SESSION".equals(exception)) {
            return "OPEN_SESSION";
        }
        if (expectation.expectedMinutes() == 0) {
            if (netMinutes > 0) {
                return "WORKED_ON_OFF_DAY";
            }
            return "HOLIDAY".equals(expectation.classification()) ? "HOLIDAY" : "WEEKLY_OFF";
        }
        if (netMinutes >= expectation.expectedMinutes()) {
            return "PRESENT_FULL_DAY";
        }
        int half = Math.max(1, expectation.expectedMinutes() / 2);
        if (netMinutes >= half && leave.paidUnits().compareTo(new BigDecimal("0.5")) >= 0) {
            return "PRESENT_HALF_PLUS_PAID_LEAVE_HALF";
        }
        if (netMinutes >= half && leave.lwpUnits().compareTo(new BigDecimal("0.5")) >= 0) {
            return "PRESENT_HALF_PLUS_LWP_HALF";
        }
        if (netMinutes >= half) {
            return "SHORT_HOURS_HALF_DAY_EXCEPTION";
        }
        if (leave.paidUnits().compareTo(BigDecimal.ONE) >= 0) {
            return "PAID_LEAVE";
        }
        if (leave.lwpUnits().compareTo(BigDecimal.ONE) >= 0) {
            return "LWP";
        }
        return netMinutes > 0 ? "ABSENT_OR_FULL_DAY_EXCEPTION" : "ABSENT";
    }

    private boolean sameCalculation(AttendanceDayView value, DayCalculation calculation) {
        return value.expectedClassification().equals(
                calculation.expectation().classification())
            && value.expectedMinutes() == calculation.expectation().expectedMinutes()
            && value.sourceMode().equals(calculation.state().sourceMode())
            && value.netMinutes() == calculation.netMinutes()
            && value.leaveUnits().compareTo(
                calculation.leave().paidUnits().add(calculation.leave().lwpUnits())) == 0
            && java.util.Objects.equals(
                value.leaveTypeCode(), calculation.leave().leaveCode())
            && value.finalStatus().equals(calculation.finalStatus())
            && java.util.Objects.equals(
                value.exceptionCode(), calculation.exceptionCode());
    }

    private List<PunchView> findPunch(UUID employeeId, String idempotencyKey) {
        return jdbc.query("""
            SELECT event.id, event.employee_id, event.event_type, event.occurred_at,
                   event.work_date, event.source, event.idempotency_key,
                   session.id AS session_id, session.status AS session_status,
                   session.net_minutes
            FROM attendance_events event
            LEFT JOIN attendance_sessions session
              ON session.check_in_event_id = event.id OR session.check_out_event_id = event.id
            WHERE event.employee_id = ? AND event.idempotency_key = ?
            """, (rs, rowNum) -> new PunchView(
                rs.getObject("id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getString("event_type"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("work_date", LocalDate.class),
                rs.getString("source"),
                rs.getString("idempotency_key"),
                rs.getObject("session_id", UUID.class),
                rs.getString("session_status"),
                rs.getObject("net_minutes", Integer.class)
            ), employeeId, idempotencyKey);
    }

    private AttendanceDayView dayView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AttendanceDayView(
            rs.getObject("id", UUID.class),
            rs.getObject("employee_id", UUID.class),
            rs.getObject("work_date", LocalDate.class),
            rs.getString("expected_classification"),
            rs.getInt("expected_minutes"),
            rs.getString("source_mode"),
            rs.getInt("net_minutes"),
            rs.getBigDecimal("leave_units"),
            rs.getString("leave_type_code"),
            rs.getString("final_status"),
            rs.getString("exception_code"),
            rs.getInt("calculation_version"),
            rs.getObject("computed_at", OffsetDateTime.class)
        );
    }

    private RegularizationView regularizationView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RegularizationView(
            rs.getObject("id", UUID.class),
            rs.getObject("employee_id", UUID.class),
            rs.getObject("work_date", LocalDate.class),
            rs.getString("reason_code"),
            rs.getString("narrative"),
            rs.getString("requested_outcome"),
            rs.getString("idempotency_key"),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    private MonthScope monthScope(UUID engagementMonthId) {
        MonthScope result = jdbc.query("""
            SELECT engagement_id, month_start_date
            FROM engagement_months WHERE id = ?
            """, rs -> rs.next()
                ? new MonthScope(rs.getObject("engagement_id", UUID.class),
                    rs.getObject("month_start_date", LocalDate.class))
                : null, engagementMonthId);
        if (result == null) {
            throw notFound();
        }
        return result;
    }

    private void materializeAllocatedDays(MonthScope month) {
        List<EmployeeDay> allocatedDays = jdbc.query("""
            SELECT DISTINCT allocation.employee_id, day.work_date::date AS work_date
            FROM employee_project_allocations allocation
            CROSS JOIN LATERAL generate_series(
                GREATEST(allocation.valid_from, ?::date),
                LEAST(COALESCE(allocation.valid_to, (?::date - 1)), (?::date - 1)),
                INTERVAL '1 day'
            ) AS day(work_date)
            WHERE allocation.engagement_id = ?
              AND allocation.status IN ('PLANNED', 'ACTIVE')
              AND allocation.valid_from < ?
              AND (allocation.valid_to IS NULL OR allocation.valid_to >= ?)
            ORDER BY allocation.employee_id, work_date
            """, (rs, rowNum) -> new EmployeeDay(
                rs.getObject("employee_id", UUID.class),
                rs.getObject("work_date", LocalDate.class)
            ), month.monthStart(), month.monthStart().plusMonths(1),
            month.monthStart().plusMonths(1), month.engagementId(),
            month.monthStart().plusMonths(1), month.monthStart());
        allocatedDays.forEach(day -> materializeDay(day.employeeId(), day.workDate()));
    }

    private List<SnapshotDay> snapshotDays(MonthScope month) {
        return jdbc.query("""
            SELECT DISTINCT ON (d.employee_id, d.work_date)
                   d.id, d.employee_id, d.work_date, d.final_status, d.net_minutes, d.source_mode
            FROM attendance_days d
            JOIN employee_project_allocations a ON a.employee_id = d.employee_id
              AND a.engagement_id = ?
              AND a.status IN ('PLANNED', 'ACTIVE')
              AND a.valid_from <= d.work_date
              AND (a.valid_to IS NULL OR a.valid_to >= d.work_date)
            WHERE d.is_current
              AND d.work_date >= ? AND d.work_date < ?
            ORDER BY d.employee_id, d.work_date, d.id
            """, (rs, rowNum) -> new SnapshotDay(
                rs.getObject("id", UUID.class),
                rs.getObject("employee_id", UUID.class),
                rs.getObject("work_date", LocalDate.class),
                rs.getString("final_status"),
                rs.getInt("net_minutes"),
                rs.getString("source_mode")
            ), month.engagementId(), month.monthStart(), month.monthStart().plusMonths(1));
    }

    private void insertSnapshotDays(UUID snapshotId, List<SnapshotDay> days) {
        for (SnapshotDay day : days) {
            jdbc.update("""
                INSERT INTO attendance_snapshot_days
                    (snapshot_id, attendance_day_id, employee_id, work_date,
                     final_status, net_minutes, source_mode)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, snapshotId, day.attendanceDayId(), day.employeeId(), day.workDate(),
                day.finalStatus(), day.netMinutes(), day.sourceMode());
        }
    }

    private List<AttendanceSnapshotView> currentSnapshot(UUID engagementMonthId) {
        return jdbc.query("""
            SELECT s.id, s.engagement_month_id, s.version, s.status, s.supersedes_id,
                   s.closed_at, s.checksum, s.day_count
            FROM attendance_snapshot_versions s
            WHERE s.engagement_month_id = ?
              AND NOT EXISTS (
                  SELECT 1 FROM attendance_snapshot_versions newer
                  WHERE newer.supersedes_id = s.id
              )
            """, (rs, rowNum) -> snapshotView(rs), engagementMonthId);
    }

    private AttendanceSnapshotView snapshot(UUID snapshotId) {
        return jdbc.query("""
            SELECT id, engagement_month_id, version, status, supersedes_id, closed_at,
                   checksum, day_count
            FROM attendance_snapshot_versions WHERE id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw notFound();
                }
                return snapshotView(rs);
            }, snapshotId);
    }

    private AttendanceSnapshotView snapshotView(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID supersedes = rs.getObject("supersedes_id", UUID.class);
        OffsetDateTime recordedAt = rs.getObject("closed_at", OffsetDateTime.class);
        String status = rs.getString("status");
        return new AttendanceSnapshotView(
            rs.getObject("id", UUID.class),
            rs.getObject("engagement_month_id", UUID.class),
            rs.getInt("version"),
            status,
            supersedes,
            "CLOSED".equals(status) ? recordedAt : null,
            "REOPENED".equals(status) ? recordedAt : null,
            rs.getString("checksum"),
            rs.getInt("day_count")
        );
    }

    private boolean sameRegularization(RegularizationView existing,
                                       RegularizationRequest request) {
        return existing.employeeId().equals(request.employeeId())
            && existing.workDate().equals(request.workDate())
            && existing.reasonCode().equals(request.reasonCode())
            && existing.narrative().equals(request.narrative())
            && existing.requestedOutcome().equals(request.requestedOutcome());
    }

    private void lockEmployee(UUID employeeId) {
        jdbc.query(
            "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))",
            rs -> null, employeeId.toString());
    }

    private void lockEngagementMonth(UUID engagementMonthId) {
        Integer locked = jdbc.query("""
            SELECT 1 FROM engagement_months WHERE id = ? FOR UPDATE
            """, rs -> rs.next() ? rs.getInt(1) : null, engagementMonthId);
        if (locked == null) {
            throw notFound();
        }
    }

    private String checksum(List<SnapshotDay> days) {
        StringBuilder canonical = new StringBuilder();
        days.stream()
            .sorted(java.util.Comparator.comparing(SnapshotDay::employeeId)
                .thenComparing(SnapshotDay::workDate))
            .forEach(day -> canonical.append(day.employeeId()).append('|')
                .append(day.workDate()).append('|').append(day.finalStatus()).append('|')
                .append(day.netMinutes()).append('|').append(day.sourceMode()).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
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
            VALUES (?, ?, ?, ?, ?, ?, jsonb_strip_nulls(jsonb_build_object(
                'employeeId', ?::text)))
            """, UUID.randomUUID(), objectType, objectId, employeeId, action,
            subject, employeeId == null ? null : employeeId.toString());
    }

    private record EmployeeAttendanceState(
        String employmentStatus,
        String activationStatus,
        String sourceMode,
        String authoritativeSource,
        boolean capabilityCertified,
        String timezone,
        LocalTime missingCheckoutCutoff
    ) {
    }

    private record OpenSession(UUID id, OffsetDateTime checkInAt, LocalDate workDate) {
    }

    private record RegularizationTarget(
        UUID id,
        UUID employeeId,
        LocalDate workDate,
        String status
    ) {
    }

    private record PriorAdjustment(UUID id, int version) {
    }

    private record CalendarExpectation(String classification, int expectedMinutes) {
    }

    private record LeaveForDay(BigDecimal paidUnits, BigDecimal lwpUnits, String leaveCode) {
    }

    private record DayCalculation(
        CalendarExpectation expectation,
        EmployeeAttendanceState state,
        int netMinutes,
        LeaveForDay leave,
        String finalStatus,
        String exceptionCode
    ) {
    }

    private record MonthScope(UUID engagementId, LocalDate monthStart) {
    }

    private record SnapshotScope(
        UUID engagementMonthId,
        UUID engagementId,
        String checksum,
        String status
    ) {
    }

    private record EmployeeDay(UUID employeeId, LocalDate workDate) {
    }

    private record SnapshotDay(
        UUID attendanceDayId,
        UUID employeeId,
        LocalDate workDate,
        String finalStatus,
        int netMinutes,
        String sourceMode
    ) {
    }
}
