package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Applies validated migration proposals to their owning bounded-context tables.
 * The generic migration fact is an index/provenance aid only; this adapter's
 * authoritative domain effect is mandatory for every committed row.
 */
@Component
public final class MigrationDomainAdapter {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public MigrationDomainAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<DomainEffect> apply(
        String template,
        UUID engagementId,
        UUID organizationId,
        UUID monthId,
        String payloadJson,
        String actor,
        String idempotencyKey
    ) {
        Map<String, String> value = payload(payloadJson);
        return switch (template) {
            case "01_employees" ->
                employee(organizationId, value, actor);
            case "02_employee_allocations" ->
                List.of(allocation(engagementId, organizationId, value, actor));
            case "03_holidays" ->
                holiday(organizationId, value);
            case "04_employee_date_overrides" ->
                List.of(dateOverride(organizationId, value, actor));
            case "05_leave_balances" ->
                List.of(leaveBalance(organizationId, value, actor));
            case "06_leave_requests" ->
                List.of(leaveRequest(organizationId, value, actor));
            case "07a_attendance_punches" ->
                attendancePunch(organizationId, value, actor);
            case "07b_attendance_daily" ->
                attendanceDay(organizationId, value);
            case "08_deliverables" ->
                deliverable(
                    monthId, engagementId, organizationId, value, actor);
            case "09_deliverable_linear_links" ->
                linearLink(monthId, engagementId, value, actor);
            case "10_delivery_certifications" ->
                certification(monthId, engagementId, value, actor);
            case "11_business_confirmations" ->
                List.of(confirmation(
                    monthId, engagementId, value, actor, idempotencyKey));
            case "12_invoices" ->
                invoice(monthId, organizationId, value, actor);
            case "13_approval_history" ->
                List.of(approvalHistory(
                    monthId, engagementId, value, actor));
            default -> throw new IllegalArgumentException(
                "No migration domain adapter is registered.");
        };
    }

    /**
     * Reverses one provenanced effect. Callers must supply effects in reverse
     * sequence inside the same transaction that marks provenance inactive.
     */
    public void compensate(DomainEffect effect, UUID rollbackActionId) {
        jdbc.queryForObject("""
            SELECT set_config('vms.migration_compensation', ?, TRUE)
            """, String.class, rollbackActionId.toString());
        if (effect.kind() == EffectKind.INSERT) {
            if ("invoices".equals(effect.table())) {
                jdbc.update("""
                    UPDATE invoices
                    SET status = 'CANCELLED',
                        optimistic_version = optimistic_version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ? AND status = 'UPLOADED'
                    """, effect.recordId());
                return;
            }
            if ("delivery_employee_assignments".equals(effect.table())
                || "delivery_dependencies".equals(effect.table())) {
                deleteProvenancedDeliveryChild(effect, rollbackActionId);
                return;
            }
            String table = compensableTable(effect.table());
            jdbc.update("DELETE FROM " + table + " WHERE id = ?",
                effect.recordId());
            return;
        }
        Map<String, Object> before = effect.beforeState();
        switch (effect.table()) {
            case "employee_versions" -> jdbc.update("""
                UPDATE employee_versions SET valid_to = NULLIF(?, '')::date
                WHERE id = ?
                """, textValue(before, "validTo"), effect.recordId());
            case "attendance_days" -> jdbc.update("""
                UPDATE attendance_days SET is_current = ?
                WHERE id = ?
                """, booleanValue(before, "isCurrent"), effect.recordId());
            case "attendance_sessions" -> jdbc.update("""
                UPDATE attendance_sessions
                SET check_out_event_id = NULLIF(?, '')::uuid,
                    check_out_at = NULLIF(?, '')::timestamptz,
                    net_minutes = ?,
                    status = ?
                WHERE id = ?
                """, textValue(before, "checkOutEventId"),
                textValue(before, "checkOutAt"),
                before.get("netMinutes"), textValue(before, "status"),
                effect.recordId());
            case "delivery_plans" -> jdbc.update("""
                UPDATE delivery_plans
                SET current_version_id = NULLIF(?, '')::uuid
                WHERE id = ?
                """, textValue(before, "currentVersionId"),
                effect.recordId());
            case "invoices" -> jdbc.update("""
                UPDATE invoices
                SET current_version = ?, optimistic_version = ?,
                    updated_at = NULLIF(?, '')::timestamptz
                WHERE id = ?
                """, priorInt(before, "currentVersion"),
                priorLong(before, "optimisticVersion"),
                textValue(before, "updatedAt"), effect.recordId());
            default -> throw new IllegalArgumentException(
                "No migration compensation adapter is registered for "
                    + effect.table());
        }
    }

    private String compensableTable(String table) {
        return switch (table) {
            case "employees", "employee_versions",
                 "attendance_source_mode_assignments",
                 "employee_project_allocations",
                 "working_calendar_versions", "calendar_holidays",
                 "employee_date_overrides", "leave_balance_ledger",
                 "leave_requests", "attendance_events",
                 "attendance_sessions", "attendance_days",
                 "delivery_plans", "delivery_plan_versions",
                 "delivery_deliverables",
                 "delivery_deliverable_versions",
                 "delivery_acceptance_criteria",
                 "delivery_employee_assignments", "delivery_dependencies",
                 "linear_issue_links",
                 "linear_issue_snapshots",
                 "delivery_submission_responses",
                 "deliverable_certifications",
                 "business_confirmation_actions", "invoices",
                 "invoice_versions", "certification_audit_events" -> table;
            default -> throw new IllegalArgumentException(
                "Untrusted migration compensation table " + table);
        };
    }

    private void deleteProvenancedDeliveryChild(
        DomainEffect effect,
        UUID rollbackActionId
    ) {
        String table = compensableTable(effect.table());
        int deleted = jdbc.update("""
            DELETE FROM %s child
            WHERE child.id = ?
              AND EXISTS (
                SELECT 1
                FROM migration_domain_provenance provenance
                JOIN migration_rollback_actions action
                  ON action.id = ?
                 AND action.action = 'COMPENSATE'
                 AND action.job_id = provenance.job_id
                WHERE provenance.active
                  AND provenance.domain_table = ?
                  AND provenance.domain_record_id = child.id
              )
            """.formatted(table), effect.recordId(), rollbackActionId, table);
        if (deleted != 1) {
            throw new DomainConflictException(
                "ROLLBACK_DOMAIN_EFFECT_CHANGED",
                "The migration-owned delivery effect is absent or no longer compensable.");
        }
    }

    private List<DomainEffect> employee(
        UUID organizationId,
        Map<String, String> value,
        String actor
    ) {
        List<DomainEffect> effects = new ArrayList<>();
        String number = required(value, "employee_number");
        UUID employeeId = jdbc.query("""
            SELECT id FROM employees
            WHERE organization_id = ? AND employee_number = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            organizationId, number);
        LocalDate validFrom = LocalDate.parse(required(value, "join_date"));
        if (employeeId == null) {
            employeeId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO employees
                  (id, organization_id, employee_number, work_email,
                   join_date, created_by_subject)
                VALUES (?, ?, ?, ?, ?, ?)
                """, employeeId, organizationId, number,
                required(value, "work_email"), validFrom, actor);
            effects.add(inserted("employees", employeeId, 1, organizationId));
        }
        Integer version = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM employee_versions WHERE employee_id = ?
            """, Integer.class, employeeId);
        UUID priorId = jdbc.query("""
            SELECT id FROM employee_versions
            WHERE employee_id = ? AND valid_to IS NULL
            ORDER BY version DESC LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            employeeId);
        if (priorId != null) {
            LocalDate priorFrom = jdbc.queryForObject("""
                SELECT valid_from FROM employee_versions WHERE id = ?
                """, LocalDate.class, priorId);
            LocalDate priorTo = jdbc.queryForObject("""
                SELECT valid_to FROM employee_versions WHERE id = ?
                """, LocalDate.class, priorId);
            validFrom = validFrom.isAfter(priorFrom)
                ? validFrom : LocalDate.now();
            effects.add(updated(
                "employee_versions", priorId, version - 1, employeeId,
                Map.of("validTo", priorTo == null ? "" : priorTo.toString())));
            jdbc.update("""
                UPDATE employee_versions
                SET valid_to = ? WHERE id = ? AND valid_to IS NULL
                """, validFrom.minusDays(1), priorId);
        }
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO employee_versions
              (id, employee_id, version, valid_from, first_name, last_name,
               display_name, designation, employment_status,
               activation_status, exit_date, reason, recorded_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, NULLIF(?, ''),
                    ?, ?, NULLIF(?, '')::date,
                    'Governed historical migration', ?)
            """, versionId, employeeId, version, validFrom,
            required(value, "first_name"), required(value, "last_name"),
            required(value, "display_name"), value.get("designation"),
            required(value, "employment_status"),
            required(value, "activation_status"), value.get("exit_date"),
            actor);
        effects.add(inserted(
            "employee_versions", versionId, version, employeeId));
        if (!exists("""
            SELECT EXISTS (
              SELECT 1 FROM attendance_source_mode_assignments
              WHERE employee_id = ? AND valid_to IS NULL
            )
            """, employeeId)) {
            String attendanceMode =
                required(value, "attendance_source_mode");
            String authoritativeSource = switch (attendanceMode) {
                case "INTERNAL_AUTHORITATIVE" -> "INTERNAL";
                case "GREYTHR_AUTHORITATIVE" -> "GREYTHR";
                case "HISTORICAL_IMPORT", "HYBRID_TRANSITION" -> "IMPORT";
                default -> throw new DomainConflictException(
                    "MIGRATION_ATTENDANCE_MODE_INVALID",
                    "Attendance source mode is not supported.");
            };
            UUID capabilityId = null;
            if ("GREYTHR".equals(authoritativeSource)) {
                capabilityId = jdbc.query("""
                    SELECT id
                    FROM integration_capability_certifications
                    WHERE organization_id = ?
                      AND provider = 'GREYTHR'
                      AND status = 'CERTIFIED'
                    ORDER BY certified_at DESC, id
                    LIMIT 1
                    """, rs -> rs.next()
                        ? rs.getObject(1, UUID.class) : null,
                    organizationId);
                requireMapped(
                    capabilityId,
                    "Certified greytHR attendance capability");
            }
            UUID assignmentId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO attendance_source_mode_assignments
                  (id, employee_id, mode, authoritative_source,
                   capability_certification_id, valid_from,
                   created_by_subject)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, assignmentId, employeeId,
                attendanceMode, authoritativeSource, capabilityId,
                validFrom, actor);
            effects.add(inserted(
                "attendance_source_mode_assignments", assignmentId, 1,
                employeeId));
        }
        return List.copyOf(effects);
    }

    private DomainEffect allocation(
        UUID engagementId,
        UUID organizationId,
        Map<String, String> value,
        String actor
    ) {
        UUID employee = employeeId(
            organizationId, required(value, "employee_number"));
        UUID project = jdbc.query("""
            SELECT id FROM projects
            WHERE engagement_id = ? AND project_code = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            engagementId, required(value, "project_code"));
        requireMapped(project, "Project");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO employee_project_allocations
              (id, employee_id, engagement_id, project_id, valid_from,
               valid_to, allocation_percent, role_on_project, status,
               created_by_subject)
            VALUES (?, ?, ?, ?, ?, NULLIF(?, '')::date, ?, NULLIF(?, ''),
                    ?, ?)
            """, id, employee, engagementId, project,
            LocalDate.parse(required(value, "valid_from")),
            value.get("valid_to"),
            new BigDecimal(required(value, "allocation_percent")),
            value.get("role_on_project"),
            allocationStatus(value.get("deployment_status")), actor);
        return new DomainEffect(
            "employee_project_allocations", id, 1, employee);
    }

    private List<DomainEffect> holiday(
        UUID organizationId,
        Map<String, String> value
    ) {
        List<DomainEffect> effects = new ArrayList<>();
        String name = required(value, "holiday_calendar_code");
        int version = Integer.parseInt(required(value, "calendar_version"));
        UUID calendar = jdbc.query("""
            SELECT id FROM working_calendar_versions
            WHERE organization_id = ? AND name = ? AND version = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            organizationId, name, version);
        if (calendar == null) {
            calendar = UUID.randomUUID();
            String timezone = jdbc.queryForObject("""
                SELECT default_timezone FROM organizations WHERE id = ?
                """, String.class, organizationId);
            jdbc.update("""
                INSERT INTO working_calendar_versions
                  (id, organization_id, name, timezone, version, valid_from,
                   expected_full_minutes, expected_half_minutes)
                VALUES (?, ?, ?, ?, ?, ?, 540, 270)
                """, calendar, organizationId, name, timezone, version,
                LocalDate.parse(required(value, "holiday_date"))
                    .withDayOfYear(1));
            effects.add(inserted(
                "working_calendar_versions", calendar, version,
                organizationId));
        }
        UUID id = UUID.randomUUID();
        String fraction = value.getOrDefault("day_fraction", "FULL");
        jdbc.update("""
            INSERT INTO calendar_holidays
              (id, calendar_version_id, holiday_date, name, classification,
               expected_minutes)
            VALUES (?, ?, ?, ?, ?, ?)
            """, id, calendar,
            LocalDate.parse(required(value, "holiday_date")),
            required(value, "holiday_name"),
            fraction.toUpperCase(Locale.ROOT).contains("HALF")
                ? "HALF_DAY_EXPECTED" : "HOLIDAY",
            intValue(value, "expected_minutes", 0));
        effects.add(inserted(
            "calendar_holidays", id, version, calendar));
        return List.copyOf(effects);
    }

    private DomainEffect dateOverride(
        UUID organizationId,
        Map<String, String> value,
        String actor
    ) {
        UUID employee = employeeId(
            organizationId, required(value, "employee_number"));
        UUID id = UUID.randomUUID();
        String classification = required(
            value, "resulting_classification");
        if (classification.equals("WEEKLY_OFF")
            || classification.equals("HOLIDAY")) {
            classification = "NON_WORKING";
        }
        jdbc.update("""
            INSERT INTO employee_date_overrides
              (id, employee_id, override_date, classification,
               expected_minutes, reason, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, id, employee,
            LocalDate.parse(required(value, "override_date")),
            classification, intValue(value, "expected_minutes", 0),
            required(value, "reason"), actor);
        return new DomainEffect("employee_date_overrides", id, 1, employee);
    }

    private DomainEffect leaveBalance(
        UUID organizationId,
        Map<String, String> value,
        String actor
    ) {
        UUID employee = employeeId(
            organizationId, required(value, "employee_number"));
        UUID leaveType = leaveType(
            organizationId, required(value, "leave_type_code"));
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO leave_balance_ledger
              (id, employee_id, leave_type_id, entry_type, quantity,
               effective_date, idempotency_key, reason, recorded_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, employee, leaveType,
            required(value, "entry_type"),
            new BigDecimal(required(value, "quantity_days")),
            LocalDate.parse(required(value, "effective_date")),
            required(value, "idempotency_reference"),
            value.get("reason"), actor);
        return new DomainEffect("leave_balance_ledger", id, 1, employee);
    }

    private DomainEffect leaveRequest(
        UUID organizationId,
        Map<String, String> value,
        String actor
    ) {
        UUID employee = employeeId(
            organizationId, required(value, "employee_number"));
        UUID leaveType = leaveType(
            organizationId, required(value, "leave_type_code"));
        BigDecimal units =
            new BigDecimal(required(value, "quantity_days"));
        boolean lwp = value.getOrDefault(
            "paid_lwp_classification", "").contains("LWP");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO leave_requests
              (id, employee_id, leave_type_id, start_date, end_date,
               requested_units, paid_units, lwp_units, reason, status,
               idempotency_key, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, employee, leaveType,
            LocalDate.parse(required(value, "leave_date")),
            LocalDate.parse(required(value, "leave_date")), units,
            lwp ? BigDecimal.ZERO : units,
            lwp ? units : BigDecimal.ZERO,
            value.getOrDefault("reason", "Historical leave"),
            leaveStatus(value), required(value, "leave_request_external_id"),
            actor);
        return new DomainEffect("leave_requests", id, 1, employee);
    }

    private List<DomainEffect> attendancePunch(
        UUID organizationId,
        Map<String, String> value,
        String actor
    ) {
        UUID employee = employeeId(
            organizationId, required(value, "employee_number"));
        ZoneId zone = ZoneId.of(required(value, "timezone"));
        OffsetDateTime occurred = timestamp(
            required(value, "occurred_at"), zone);
        LocalDate workDate = occurred.atZoneSameInstant(zone).toLocalDate();
        String eventType = punchType(required(value, "event_type"));
        UUID eventId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO attendance_events
              (id, employee_id, event_type, occurred_at, work_date, source,
               idempotency_key, recorded_by_subject)
            VALUES (?, ?, ?, ?, ?, 'CSV_IMPORT', ?, ?)
            """, eventId, employee, eventType, occurred, workDate,
            required(value, "attendance_event_external_id"), actor);
        List<DomainEffect> effects = new ArrayList<>();
        effects.add(new DomainEffect(
            "attendance_events", eventId, 1, employee));
        if ("CHECK_IN".equals(eventType)) {
            UUID sessionId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO attendance_sessions
                  (id, employee_id, work_date, check_in_event_id,
                   check_in_at, status)
                VALUES (?, ?, ?, ?, ?, 'OPEN')
                """, sessionId, employee, workDate, eventId, occurred);
            effects.add(inserted(
                "attendance_sessions", sessionId, 1, employee));
        } else if ("CHECK_OUT".equals(eventType)) {
            UUID sessionId = jdbc.query("""
                SELECT id FROM attendance_sessions
                WHERE employee_id = ? AND status = 'OPEN'
                  AND check_in_at < ?
                ORDER BY check_in_at DESC LIMIT 1 FOR UPDATE
                """, rs -> rs.next()
                    ? rs.getObject(1, UUID.class) : null,
                employee, occurred);
            requireMapped(sessionId, "Open attendance session");
            OffsetDateTime checkIn = jdbc.queryForObject("""
                SELECT check_in_at FROM attendance_sessions WHERE id = ?
                """, OffsetDateTime.class, sessionId);
            long minutes = Duration.between(checkIn, occurred).toMinutes();
            Map<String, Object> before = jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'checkOutEventId',
                        COALESCE(check_out_event_id::text, ''),
                    'checkOutAt', COALESCE(check_out_at::text, ''),
                    'netMinutes', net_minutes,
                    'status', status)
                FROM attendance_sessions WHERE id = ?
                """, (rs, ignored) -> mapper.convertValue(
                    mapper.readTree(rs.getString(1)), Map.class), sessionId);
            jdbc.update("""
                UPDATE attendance_sessions
                SET check_out_event_id = ?, check_out_at = ?,
                    net_minutes = ?, status = 'CLOSED'
                WHERE id = ? AND status = 'OPEN'
                """, eventId, occurred, minutes, sessionId);
            effects.add(updated(
                "attendance_sessions", sessionId, 1, employee, before));
        }
        return List.copyOf(effects);
    }

    private List<DomainEffect> attendanceDay(
        UUID organizationId,
        Map<String, String> value
    ) {
        List<DomainEffect> effects = new ArrayList<>();
        UUID employee = employeeId(
            organizationId, required(value, "employee_number"));
        LocalDate date =
            LocalDate.parse(required(value, "attendance_date"));
        Integer version = jdbc.queryForObject("""
            SELECT COALESCE(MAX(calculation_version), 0) + 1
            FROM attendance_days
            WHERE employee_id = ? AND work_date = ?
            """, Integer.class, employee, date);
        List<UUID> priorCurrent = jdbc.query("""
            SELECT id FROM attendance_days
            WHERE employee_id = ? AND work_date = ? AND is_current
            FOR UPDATE
            """, (rs, ignored) -> rs.getObject(1, UUID.class),
            employee, date);
        for (UUID prior : priorCurrent) {
            effects.add(updated(
                "attendance_days", prior, version - 1, employee,
                Map.of("isCurrent", true)));
        }
        jdbc.update("""
            UPDATE attendance_days SET is_current = FALSE
            WHERE employee_id = ? AND work_date = ? AND is_current
            """, employee, date);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO attendance_days
              (id, employee_id, work_date, calculation_version, is_current,
               expected_classification, expected_minutes, source_mode,
               net_minutes, leave_units, leave_type_code, final_status,
               exception_code)
            VALUES (?, ?, ?, ?, TRUE, ?, ?, 'HISTORICAL_IMPORT', ?, ?, ?,
                    ?, NULLIF(?, ''))
            """, id, employee, date, version,
            required(value, "calendar_classification"),
            intValue(value, "expected_minutes", 0),
            intValue(value, "net_worked_minutes", 0),
            decimal(value, "paid_leave_days"),
            value.get("paid_leave_type_code"),
            required(value, "final_attendance_status"),
            value.get("exception_code"));
        effects.add(inserted("attendance_days", id, version, employee));
        return List.copyOf(effects);
    }

    private List<DomainEffect> deliverable(
        UUID monthId,
        UUID engagementId,
        UUID organizationId,
        Map<String, String> value,
        String actor
    ) {
        List<DomainEffect> effects = new ArrayList<>();
        requireMapped(monthId, "Engagement month");
        UUID plan = jdbc.query("""
            SELECT id FROM delivery_plans WHERE engagement_month_id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            monthId);
        if (plan == null) {
            plan = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO delivery_plans
                  (id, engagement_month_id, created_by_subject)
                VALUES (?, ?, ?)
                """, plan, monthId, actor);
            effects.add(inserted(
                "delivery_plans", plan, 1, monthId));
        }
        int version = Integer.parseInt(
            value.getOrDefault("plan_version", "1"));
        UUID planVersion = jdbc.query("""
            SELECT id FROM delivery_plan_versions
            WHERE plan_id = ? AND version = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            plan, version);
        if (planVersion == null) {
            planVersion = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO delivery_plan_versions
                  (id, plan_id, version, state, title, summary,
                   business_outcomes, coordinator_subject, baseline_type,
                   quorum_mode, quorum_required, created_by_subject)
                VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, 'ANY_ONE', 1, ?)
                """, planVersion, plan, version,
                "Historical plan " + required(value, "plan_external_id"),
                value.getOrDefault("description", "Historical migration"),
                value.getOrDefault("business_objective",
                    "Historical represented delivery"),
                actor, baselineType(value), actor);
            effects.add(inserted(
                "delivery_plan_versions", planVersion, version, plan));
            UUID priorCurrent = jdbc.queryForObject("""
                SELECT current_version_id FROM delivery_plans WHERE id = ?
                """, UUID.class, plan);
            effects.add(updated(
                "delivery_plans", plan, version, monthId,
                Map.of("currentVersionId",
                    priorCurrent == null ? "" : priorCurrent.toString())));
            jdbc.update("""
                UPDATE delivery_plans SET current_version_id = ? WHERE id = ?
                """, planVersion, plan);
        }
        UUID deliverable = jdbc.query("""
            SELECT id FROM delivery_deliverables
            WHERE plan_id = ? AND deliverable_code = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            plan, required(value, "deliverable_code"));
        if (deliverable == null) {
            deliverable = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO delivery_deliverables
                  (id, plan_id, deliverable_code)
                VALUES (?, ?, ?)
                """, deliverable, plan, required(value, "deliverable_code"));
            effects.add(inserted(
                "delivery_deliverables", deliverable, 1, plan));
        }
        UUID project = jdbc.query("""
            SELECT id FROM projects
            WHERE engagement_id = ? AND project_code = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            engagementId, required(value, "project_code"));
        requireMapped(project, "Project");
        UUID deliverableVersion = UUID.randomUUID();
        List<String> dependencies = collection(value.get("dependencies"));
        jdbc.update("""
            INSERT INTO delivery_deliverable_versions
              (id, deliverable_id, plan_version_id, project_id, title,
               description, business_objective, product_owner_subject,
               vendor_owner_subject, priority, target_completion_date,
               evidence_expectations, dependency_none_declared,
               risk_and_assumptions, delivery_category)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, deliverableVersion, deliverable, planVersion, project,
            required(value, "title"),
            value.getOrDefault("description", "Historical deliverable"),
            value.getOrDefault("business_objective",
                "Historical represented outcome"),
            subjectByEmail(required(value, "product_owner_email")),
            subjectByEmail(required(value, "vendor_owner_email")),
            required(value, "priority"),
            LocalDate.parse(required(value, "target_completion_date")),
            value.getOrDefault("evidence_expectations",
                "Historical evidence"),
            dependencies.isEmpty(),
            value.getOrDefault("risks_and_assumptions", "None disclosed"),
            deliveryCategory(value.get("delivery_category")));
        effects.add(inserted(
            "delivery_deliverable_versions",
            deliverableVersion, version, deliverable));

        List<String> criteria = collection(
            required(value, "acceptance_criteria"));
        for (int index = 0; index < criteria.size(); index++) {
            UUID criterion = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO delivery_acceptance_criteria
                  (id, deliverable_version_id, sequence, statement,
                   validation_method, expected_result, mandatory)
                VALUES (?, ?, ?, ?, 'Historical evidence review',
                        'Represented result is supported', TRUE)
                """, criterion, deliverableVersion, index + 1,
                criteria.get(index));
            effects.add(inserted(
                "delivery_acceptance_criteria",
                criterion, index + 1, deliverableVersion));
        }
        for (String number : collection(
            value.get("assigned_employee_numbers"))) {
            UUID assignment = UUID.randomUUID();
            UUID employee = employeeId(organizationId, number);
            LocalDate effectiveFrom = LocalDate.parse(
                required(value, "billing_month") + "-01");
            jdbc.update("""
                INSERT INTO delivery_employee_assignments
                  (id, deliverable_version_id, employee_id, effective_from,
                   effective_to, exception_reason)
                VALUES (?, ?, ?, ?, ?, ?)
                """, assignment, deliverableVersion, employee, effectiveFrom,
                LocalDate.parse(required(value, "target_completion_date")),
                "Governed historical migration");
            effects.add(inserted(
                "delivery_employee_assignments",
                assignment, 1, deliverableVersion));
        }
        for (String description : dependencies) {
            UUID dependency = UUID.randomUUID();
            UUID internal = jdbc.query("""
                SELECT candidate.id
                FROM delivery_deliverables candidate
                WHERE candidate.plan_id = ? AND candidate.deliverable_code = ?
                  AND candidate.id <> ?
                """, rs -> rs.next()
                    ? rs.getObject(1, UUID.class) : null,
                plan, description, deliverable);
            jdbc.update("""
                INSERT INTO delivery_dependencies
                  (id, deliverable_version_id, dependency_type,
                   depends_on_deliverable_id, description, owner_subject,
                   target_resolution_date, blocking)
                VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)
                """, dependency, deliverableVersion,
                internal == null ? "EXTERNAL" : "INTERNAL", internal,
                description,
                subjectByEmail(required(value, "product_owner_email")),
                LocalDate.parse(required(value, "target_completion_date")));
            effects.add(inserted(
                "delivery_dependencies",
                dependency, 1, deliverableVersion));
        }
        return List.copyOf(effects);
    }

    private List<DomainEffect> linearLink(
        UUID monthId,
        UUID engagementId,
        Map<String, String> value,
        String actor
    ) {
        UUID deliverable = deliverableVersion(
            monthId, required(value, "deliverable_code"));
        UUID connection = jdbc.query("""
            SELECT id FROM linear_connections
            WHERE engagement_id = ?
            ORDER BY created_at LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            engagementId);
        requireMapped(connection, "Linear connection");
        UUID issueUuid = value.getOrDefault(
            "linear_issue_uuid", "").isBlank()
            ? UUID.nameUUIDFromBytes(
                required(value, "linear_issue_identifier")
                    .getBytes(StandardCharsets.UTF_8))
            : UUID.fromString(value.get("linear_issue_uuid"));
        UUID link = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO linear_issue_links
              (id, deliverable_version_id, connection_id, linear_issue_uuid,
               identifier, issue_url, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, link, deliverable, connection, issueUuid,
            required(value, "linear_issue_identifier"),
            required(value, "linear_issue_url"), actor);
        UUID snapshot = UUID.randomUUID();
        boolean historical =
            !value.getOrDefault("historical_snapshot_at", "").isBlank()
                && !"LINEAR_API".equals(value.get("source_system"));
        jdbc.update("""
            INSERT INTO linear_issue_snapshots
              (id, issue_link_id, snapshot_type, status, provider_state,
               normalized_state, fetched_at, payload_hash, confidence)
            VALUES (?, ?, ?, 'CAPTURED', CAST(? AS JSONB), ?, ?, ?, ?)
            """, snapshot, link,
            historical ? "HISTORICAL_RETRIEVAL" : "PLAN_TIME",
            json(Map.of(
                "stateName", value.getOrDefault(
                    "historical_state_name", ""),
                "source", value.getOrDefault("source_system", "OTHER"))),
            normalizedLinearState(value.get("historical_state_type")),
            historical
                ? OffsetDateTime.parse(value.get("historical_snapshot_at"))
                : OffsetDateTime.now(),
            MigrationTemplateRegistry.sha256(json(value)),
            historical ? "SOURCE_EXPORT" : "CURRENT_STATE_ONLY");
        return List.of(
            new DomainEffect("linear_issue_links", link, 1, deliverable),
            new DomainEffect("linear_issue_snapshots",
                snapshot, 1, link));
    }

    private List<DomainEffect> certification(
        UUID monthId,
        UUID engagementId,
        Map<String, String> value,
        String actor
    ) {
        UUID deliverable = deliverableVersion(
            monthId, required(value, "deliverable_code"));
        UUID submission = jdbc.query("""
            SELECT id FROM delivery_submissions
            WHERE engagement_month_id = ?
            ORDER BY version DESC LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            monthId);
        requireMapped(submission, "Delivery submission");
        int responseVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(response_version), 0) + 1
            FROM delivery_submission_responses
            WHERE submission_id = ? AND deliverable_version_id = ?
            """, Integer.class, submission, deliverable);
        UUID vendorResponse = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO delivery_submission_responses
              (id, submission_id, deliverable_version_id, response_version,
               response_text, represented_at, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, vendorResponse, submission, deliverable, responseVersion,
            required(value, "vendor_summary"),
            optionalTimestamp(value.get("represented_certification_at")),
            actor);
        List<DomainEffect> effects = new ArrayList<>();
        effects.add(new DomainEffect(
            "delivery_submission_responses", vendorResponse,
            responseVersion, submission));
        UUID round = jdbc.query("""
            SELECT id FROM certification_rounds
            WHERE submission_id = ?
            ORDER BY round_number DESC LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            submission);
        if (round != null
            && !value.getOrDefault(
                "client_certification_decision", "").isBlank()) {
            OffsetDateTime represented = optionalTimestamp(
                value.get("represented_certification_at"));
            MappedAuthority authority = mappedAuthority(
                engagementId,
                required(value, "product_owner_email"),
                "CLIENT_PRODUCT_OWNER", null, represented);
            String baselineHash = jdbc.queryForObject("""
                SELECT baseline.checksum
                FROM delivery_submissions submission
                JOIN delivery_plan_baselines baseline
                  ON baseline.id = submission.baseline_id
                WHERE submission.id = ?
                """, String.class, submission);
            String submissionHash = jdbc.queryForObject("""
                SELECT checksum FROM delivery_submissions WHERE id = ?
                """, String.class, submission);
            UUID certification = UUID.randomUUID();
            String decision = certificationDecision(
                value.get("client_certification_decision"));
            String comment = required(
                value, "client_certification_comment");
            String cause = detailForDecision(
                decision, value.get("variance_cause"), comment);
            String nextAction = detailForDecision(
                decision, value.get("proposed_carry_forward_month"),
                value.getOrDefault("notes", comment));
            String observations = "ACCEPTED_WITH_OBSERVATIONS".equals(decision)
                ? comment : null;
            String acceptedScope = "PARTIALLY_ACCEPTED".equals(decision)
                ? required(value, "acceptance_criteria_result_summary") : null;
            String rejectedScope = "PARTIALLY_ACCEPTED".equals(decision)
                ? detailForDecision(decision, value.get("variance_cause"),
                    comment) : null;
            jdbc.update("""
                INSERT INTO deliverable_certifications
                  (id, round_id, submission_id, deliverable_version_id,
                   decision, comment, cause, next_action, observations,
                   accepted_scope, rejected_scope,
                   baseline_checksum, submission_checksum,
                   authority_snapshot, source, represented_at,
                   decided_by_subject, action_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        CAST(? AS JSONB), 'TRUSTED_MIGRATION', ?, ?, ?)
                """, certification, round, submission, deliverable,
                decision, comment, cause, nextAction, observations,
                acceptedScope, rejectedScope, baselineHash, submissionHash,
                json(Map.of("source", "TRUSTED_MIGRATION",
                    "importedBy", actor,
                    "historicalAuthority", authority.snapshot())),
                represented,
                authority.subject(),
                MigrationTemplateRegistry.sha256(json(value)));
            effects.add(new DomainEffect(
                "deliverable_certifications", certification, 1, round));
        }
        return List.copyOf(effects);
    }

    private String detailForDecision(
        String decision,
        String preferred,
        String fallback
    ) {
        if ("ACCEPTED".equals(decision)
            || "ACCEPTED_WITH_OBSERVATIONS".equals(decision)) {
            return null;
        }
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        if (fallback == null || fallback.isBlank()) {
            throw conflict("Certification decision detail is required.");
        }
        return fallback;
    }

    private DomainEffect confirmation(
        UUID monthId,
        UUID engagementId,
        Map<String, String> value,
        String actor,
        String idempotencyKey
    ) {
        requireMapped(monthId, "Engagement month");
        UUID request = jdbc.query("""
            SELECT id FROM business_confirmation_requests
            WHERE engagement_month_id = ?
            ORDER BY version DESC LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            monthId);
        requireMapped(request, "Business confirmation request");
        int requestVersion = jdbc.queryForObject("""
            SELECT version FROM business_confirmation_requests WHERE id = ?
            """, Integer.class, request);
        OffsetDateTime represented = optionalTimestamp(
            value.get("represented_response_at"));
        MappedAuthority authority = mappedAuthority(
            engagementId, required(value, "actor_email"),
            "CLIENT_PRODUCT_OWNER", null, represented);
        String evidenceHash = required(value, "evidence_sha256")
            .toLowerCase(Locale.ROOT);
        String evidenceFilename = value.getOrDefault(
            "evidence_filename", "");
        UUID evidenceArtifact = jdbc.query("""
            SELECT artifact.id
            FROM evidence_artifacts artifact
            WHERE artifact.engagement_id = ?
              AND artifact.engagement_month_id = ?
              AND artifact.sha256 = ?
              AND artifact.scan_status IN ('PASSED', 'NOT_REQUIRED')
              AND artifact.retention_status <> 'DISPOSED'
              AND (
                CAST(? AS VARCHAR) = ''
                OR lower(COALESCE(artifact.original_name, artifact.safe_name))
                    = lower(?)
                OR lower(artifact.safe_name) = lower(?)
              )
            ORDER BY artifact.recorded_at DESC, artifact.id
            LIMIT 2
            """, rs -> {
                UUID match = null;
                int count = 0;
                while (rs.next()) {
                    match = rs.getObject(1, UUID.class);
                    count++;
                }
                return count == 1 ? match : null;
            }, engagementId, monthId, evidenceHash, evidenceFilename,
            evidenceFilename, evidenceFilename);
        if (evidenceArtifact == null) {
            throw new DomainConflictException(
                "CONFIRMATION_EVIDENCE_NOT_VERIFIED",
                "Confirmation evidence must resolve uniquely by SHA-256 "
                    + "to a retained, scan-approved artifact in this month.");
        }
        List<UUID> eligibleProjects = jdbc.query("""
            SELECT project_id
            FROM confirmation_request_eligibility
            WHERE request_id = ? AND eligible_confirmer_subject = ?
            ORDER BY sequence_number
            """, (rs, ignored) -> rs.getObject(1, UUID.class),
            request, authority.subject());
        if (eligibleProjects.size() != 1) {
            throw conflict(
                "Historical confirmer must match exactly one captured "
                    + "request eligibility scope.");
        }
        UUID projectId = eligibleProjects.getFirst();
        Map<String, Object> authoritySnapshot = new LinkedHashMap<>();
        authoritySnapshot.put("source", "TRUSTED_MIGRATION");
        authoritySnapshot.put("reviewedBy", actor);
        authoritySnapshot.put("historicalAuthority", authority.snapshot());
        authoritySnapshot.put("evidenceArtifactId",
            evidenceArtifact.toString());
        authoritySnapshot.put("evidenceSha256", evidenceHash);
        authoritySnapshot.put(
            "projectScope",
            projectId == null ? "ENGAGEMENT_WIDE" : projectId.toString());
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO business_confirmation_actions
              (id, request_id, request_version, actor_subject,
               actor_authority_snapshot, project_id, action, comment, source,
               verification_status, session_evidence_hash, represented_at, action_hash,
               idempotency_key)
            VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?, ?, NULLIF(?, ''),
                    'TRUSTED_MIGRATION', 'MANUAL_REVIEWED', ?, ?, ?, ?)
            """, id, request, requestVersion, authority.subject(),
            json(authoritySnapshot), projectId,
            confirmationAction(value.get("decision")),
            value.get("review_comment"),
            evidenceHash, represented,
            MigrationTemplateRegistry.sha256(json(value)), idempotencyKey);
        return new DomainEffect(
            "business_confirmation_actions", id, requestVersion, request);
    }

    private List<DomainEffect> invoice(
        UUID monthId,
        UUID organizationId,
        Map<String, String> value,
        String actor
    ) {
        List<DomainEffect> effects = new ArrayList<>();
        requireMapped(monthId, "Engagement month");
        String number = required(value, "invoice_number");
        UUID invoice = jdbc.query("""
            SELECT id FROM invoices
            WHERE vendor_organization_id = ?
              AND normalized_invoice_number = lower(trim(?))
              AND status NOT IN ('SUPERSEDED', 'CANCELLED')
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            organizationId, number);
        int version;
        if (invoice == null) {
            invoice = UUID.randomUUID();
            version = 1;
            jdbc.update("""
                INSERT INTO invoices
                  (id, engagement_month_id, vendor_organization_id,
                   invoice_type, invoice_number, normalized_invoice_number,
                   invoice_date, billing_period_start, billing_period_end,
                   currency, taxable_value, tax_value, total_value,
                   po_reference, work_order_reference, status,
                   created_by_subject, correlation_id)
                VALUES (?, ?, ?, 'PRIMARY', ?, lower(trim(?)), ?, ?, ?, ?,
                        NULLIF(?, '')::numeric, NULLIF(?, '')::numeric,
                        NULLIF(?, '')::numeric, NULLIF(?, ''), NULLIF(?, ''),
                        'UPLOADED', ?, ?)
                """, invoice, monthId, organizationId, number, number,
                LocalDate.parse(required(value, "invoice_date")),
                LocalDate.parse(required(value, "billing_start_date")),
                LocalDate.parse(required(value, "billing_end_date")),
                required(value, "currency"),
                value.get("taxable_value"), value.get("tax_amount"),
                value.get("total_amount"), value.get("po_reference"),
                value.get("work_order_reference"), actor, UUID.randomUUID());
            effects.add(inserted("invoices", invoice, version, invoice));
        } else {
            Map<String, Object> before = jdbc.queryForObject("""
                SELECT jsonb_build_object(
                    'currentVersion', current_version,
                    'optimisticVersion', optimistic_version,
                    'updatedAt', updated_at::text)
                FROM invoices WHERE id = ? FOR UPDATE
                """, (rs, ignored) -> mapper.convertValue(
                    mapper.readTree(rs.getString(1)), Map.class), invoice);
            version = ((Number) before.get("currentVersion")).intValue() + 1;
            effects.add(updated(
                "invoices", invoice, version, invoice, before));
            jdbc.update("""
                UPDATE invoices
                SET current_version = ?, optimistic_version = optimistic_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, version, invoice);
        }
        String filename = required(value, "invoice_filename");
        String suppliedHash = value.getOrDefault("invoice_sha256", "");
        boolean verifiedDocumentHash = !suppliedHash.isBlank();
        UUID artifact = null;
        if (verifiedDocumentHash) {
            artifact = UUID.randomUUID();
            String contentHash = suppliedHash.toLowerCase(Locale.ROOT);
            String objectVersion = "migration-" + invoice + "-v" + version;
            jdbc.update("""
                INSERT INTO f05_private_artifacts
                  (id, engagement_month_id, owner_organization_id, logical_type,
                   safe_name, media_type, byte_size, content_hash, object_key,
                   object_version, classification, retention_class, scan_status,
                   provider_status, source, represented_at, uploaded_by_subject,
                   correlation_id)
                VALUES (?, ?, ?, 'INVOICE_DOCUMENT', ?,
                        'application/octet-stream', 0, ?, ?, ?,
                        'CONFIDENTIAL', 'FINANCE_SEVEN_YEARS', 'UNKNOWN',
                        'LOCAL_METADATA_ONLY', 'HISTORICAL_MIGRATION', ?, ?, ?)
                """, artifact, monthId, organizationId, filename, contentHash,
                "historical-migration/invoices/" + invoice + "/" + version
                    + "/" + filename,
                objectVersion,
                optionalTimestamp(value.get("represented_uploaded_at")),
                actor, UUID.randomUUID());
        }
        UUID invoiceVersion = UUID.randomUUID();
        Map<String, Object> metadataManifest = new LinkedHashMap<>(value);
        metadataManifest.put("documentHashStatus",
            verifiedDocumentHash ? "SUPPLIED_SHA256"
                : "UNVERIFIED_METADATA_ONLY");
        jdbc.update("""
            INSERT INTO invoice_versions
              (id, invoice_id, version, document_artifact_id,
               metadata_manifest, metadata_hash, source, represented_at,
               created_by_subject, correlation_id)
            VALUES (?, ?, ?, ?, CAST(? AS JSONB), ?,
                    'HISTORICAL_MIGRATION', ?, ?, ?)
            """, invoiceVersion, invoice, version, artifact,
            json(metadataManifest),
            MigrationTemplateRegistry.sha256(json(metadataManifest)),
            optionalTimestamp(value.get("represented_uploaded_at")),
            actor, UUID.randomUUID());
        effects.add(inserted(
            "invoice_versions", invoiceVersion, version, invoice));
        return List.copyOf(effects);
    }

    private DomainEffect approvalHistory(
        UUID monthId,
        UUID engagementId,
        Map<String, String> value,
        String actor
    ) {
        requireMapped(monthId, "Engagement month");
        UUID objectId = resolveApprovalObject(
            monthId, required(value, "object_type"),
            required(value, "object_external_id"));
        requireMapped(objectId, "Historical approval object");
        OffsetDateTime represented = optionalTimestamp(
            value.get("represented_at"));
        MappedAuthority authority = mappedAuthority(
            engagementId, required(value, "actor_email"),
            required(value, "actor_role"),
            value.get("actor_organization_code"), represented);
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO certification_audit_events
              (id, engagement_month_id, event_type, actor_subject,
               authority_snapshot, object_type, object_id, object_version,
               source, reason, result, evidence_references, correlation_id,
               occurred_at)
            VALUES (?, ?, 'HISTORICAL_APPROVAL_IMPORTED', ?,
                    CAST(? AS JSONB), ?, ?, NULLIF(?, '')::integer,
                    'TRUSTED_MIGRATION', NULLIF(?, ''), ?,
                    CAST(? AS JSONB), ?, ?)
            """, id, monthId, authority.subject(),
            json(Map.of(
                "historicalAuthority", authority.snapshot(),
                "verifiedBy", actor)),
            required(value, "object_type"), objectId,
            value.get("object_version"), value.get("comment"),
            required(value, "decision"),
            json(List.of(value.getOrDefault("evidence_reference", ""))),
            UUID.randomUUID(), optionalTimestamp(value.get("represented_at")));
        return new DomainEffect(
            "certification_audit_events", id,
            intValue(value, "object_version", 1), objectId);
    }

    private UUID resolveApprovalObject(
        UUID monthId,
        String type,
        String externalId
    ) {
        if (type.toUpperCase(Locale.ROOT).contains("PLAN")) {
            return jdbc.query("""
                SELECT version.id
                FROM delivery_plan_versions version
                JOIN delivery_plans plan ON plan.id = version.plan_id
                WHERE plan.engagement_month_id = ?
                ORDER BY version.version DESC LIMIT 1
                """, rs -> rs.next()
                    ? rs.getObject(1, UUID.class) : null, monthId);
        }
        if (type.toUpperCase(Locale.ROOT).contains("INVOICE")) {
            return jdbc.query("""
                SELECT id FROM invoices
                WHERE engagement_month_id = ? AND invoice_number = ?
                ORDER BY current_version DESC LIMIT 1
                """, rs -> rs.next()
                    ? rs.getObject(1, UUID.class) : null,
                monthId, externalId);
        }
        return jdbc.query("""
            SELECT summary.id
            FROM monthly_certification_summaries summary
            WHERE summary.engagement_month_id = ?
            ORDER BY version DESC LIMIT 1
            """, rs -> rs.next()
                ? rs.getObject(1, UUID.class) : null, monthId);
    }

    private UUID employeeId(UUID organizationId, String number) {
        UUID id = jdbc.query("""
            SELECT id FROM employees
            WHERE organization_id = ? AND employee_number = ?
            """, rs -> rs.next()
                ? rs.getObject(1, UUID.class) : null,
            organizationId, number);
        requireMapped(id, "Employee");
        return id;
    }

    private UUID leaveType(UUID organizationId, String code) {
        UUID id = jdbc.query("""
            SELECT id FROM leave_types
            WHERE organization_id = ? AND code = ?
            """, rs -> rs.next()
                ? rs.getObject(1, UUID.class) : null,
            organizationId, code);
        requireMapped(id, "Leave type");
        return id;
    }

    private UUID deliverableVersion(UUID monthId, String code) {
        requireMapped(monthId, "Engagement month");
        UUID id = jdbc.query("""
            SELECT version.id
            FROM delivery_deliverable_versions version
            JOIN delivery_deliverables deliverable
              ON deliverable.id = version.deliverable_id
            JOIN delivery_plans plan ON plan.id = deliverable.plan_id
            WHERE plan.engagement_month_id = ?
              AND deliverable.deliverable_code = ?
            ORDER BY version.plan_version_id DESC LIMIT 1
            """, rs -> rs.next()
                ? rs.getObject(1, UUID.class) : null, monthId, code);
        requireMapped(id, "Deliverable version");
        return id;
    }

    private String subjectByEmail(String email) {
        String subject = jdbc.query("""
            SELECT identity_subject FROM user_profiles
            WHERE lower(email) = lower(?) AND status = 'ACTIVE'
            """, rs -> rs.next() ? rs.getString(1) : null, email);
        if (subject == null) {
            throw conflict("Actor email is not mapped to an active principal.");
        }
        return subject;
    }

    private MappedAuthority mappedAuthority(
        UUID engagementId,
        String email,
        String roleCode,
        String organizationCode,
        OffsetDateTime representedAt
    ) {
        LocalDate authorityDate = representedAt == null
            ? LocalDate.now() : representedAt.toLocalDate();
        List<MappedAuthority> matches = jdbc.query("""
            SELECT profile.identity_subject, role.code, organization.code,
                   assignment.scope_type, assignment.scope_id
            FROM user_profiles profile
            JOIN role_assignments assignment
              ON assignment.user_profile_id = profile.id
            JOIN roles role ON role.id = assignment.role_id
            JOIN organizations organization
              ON organization.id = assignment.organization_id
            JOIN engagements engagement ON engagement.id = ?
            WHERE lower(profile.email) = lower(?)
              AND profile.status = 'ACTIVE'
              AND assignment.status = 'ACTIVE'
              AND role.status = 'ACTIVE'
              AND role.code = ?
              AND (CAST(? AS VARCHAR) IS NULL OR organization.code = ?)
              AND assignment.valid_from <= ?
              AND (assignment.valid_to IS NULL OR assignment.valid_to >= ?)
              AND (
                (assignment.scope_type = 'ENGAGEMENT'
                  AND assignment.scope_id = engagement.id)
                OR (assignment.scope_type = 'ORGANIZATION'
                  AND assignment.scope_id IN (
                    engagement.client_organization_id,
                    engagement.vendor_organization_id,
                    engagement.procurement_organization_id))
                OR (assignment.scope_type = 'PROJECT'
                  AND EXISTS (
                    SELECT 1 FROM projects project
                    WHERE project.id = assignment.scope_id
                      AND project.engagement_id = engagement.id))
              )
            ORDER BY CASE assignment.scope_type
                WHEN 'PROJECT' THEN 1
                WHEN 'ENGAGEMENT' THEN 2
                ELSE 3
              END, assignment.valid_from DESC, assignment.id
            """, (rs, ignored) -> {
                Map<String, Object> snapshot = Map.of(
                    "email", email,
                    "roleCode", rs.getString(2),
                    "organizationCode", rs.getString(3),
                    "scopeType", rs.getString(4),
                    "scopeId", rs.getObject(5, UUID.class).toString(),
                    "authorityDate", authorityDate.toString());
                return new MappedAuthority(rs.getString(1), snapshot);
            }, engagementId, email, roleCode, organizationCode,
            organizationCode, authorityDate, authorityDate);
        if (matches.isEmpty()) {
            throw conflict(
                "Historical actor does not have mapped authority in the "
                    + "governed engagement scope.");
        }
        if (matches.size() != 1) {
            throw conflict(
                "Historical actor authority is ambiguous; resolve one exact "
                    + "active governed assignment before import.");
        }
        return matches.getFirst();
    }

    private List<String> collection(String raw) {
        if (raw == null || raw.isBlank()
            || "NONE".equalsIgnoreCase(raw.trim())) {
            return List.of();
        }
        return List.of(raw.split("\\s*(?:\\||\\r?\\n|;)\\s*")).stream()
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> payload(String value) {
        return mapper.convertValue(mapper.readTree(value), Map.class);
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private String required(Map<String, String> value, String field) {
        String result = value.get(field);
        if (result == null || result.isBlank()) {
            throw conflict("Required domain field is missing: " + field);
        }
        return result;
    }

    private void requireMapped(Object value, String label) {
        if (value == null) {
            throw conflict(label + " is not mapped in the governed scope.");
        }
    }

    private DomainConflictException conflict(String detail) {
        return new DomainConflictException(
            "MIGRATION_DOMAIN_MAPPING_REQUIRED", detail);
    }

    private boolean exists(String sql, Object... arguments) {
        return Boolean.TRUE.equals(
            jdbc.queryForObject(sql, Boolean.class, arguments));
    }

    private int intValue(
        Map<String, String> value,
        String field,
        int fallback
    ) {
        String raw = value.get(field);
        return raw == null || raw.isBlank()
            ? fallback : Integer.parseInt(raw);
    }

    private BigDecimal decimal(Map<String, String> value, String field) {
        String raw = value.get(field);
        return raw == null || raw.isBlank()
            ? BigDecimal.ZERO : new BigDecimal(raw);
    }

    private OffsetDateTime optionalTimestamp(String raw) {
        return raw == null || raw.isBlank()
            ? null : OffsetDateTime.parse(raw);
    }

    private OffsetDateTime timestamp(String raw, ZoneId timezone) {
        try {
            return OffsetDateTime.parse(raw);
        } catch (RuntimeException exception) {
            return LocalDateTime.parse(raw)
                .atZone(timezone)
                .toOffsetDateTime();
        }
    }

    private String textValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        return raw == null ? "" : String.valueOf(raw);
    }

    private boolean booleanValue(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        return raw instanceof Boolean bool
            ? bool : Boolean.parseBoolean(String.valueOf(raw));
    }

    private int priorInt(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        return raw instanceof Number number
            ? number.intValue() : Integer.parseInt(String.valueOf(raw));
    }

    private long priorLong(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        return raw instanceof Number number
            ? number.longValue() : Long.parseLong(String.valueOf(raw));
    }

    private String allocationStatus(String value) {
        return switch (String.valueOf(value).toUpperCase(Locale.ROOT)) {
            case "PLANNED" -> "PLANNED";
            case "ENDED", "RELEASED" -> "ENDED";
            case "TEMPORARILY_INACTIVE", "INACTIVE" ->
                "TEMPORARILY_INACTIVE";
            default -> "ACTIVE";
        };
    }

    private String leaveStatus(Map<String, String> value) {
        String decision = value.getOrDefault(
            "decision_status", value.getOrDefault("request_status", ""));
        return switch (decision.toUpperCase(Locale.ROOT)) {
            case "APPROVED" -> "APPROVED";
            case "REJECTED", "DECLINED" -> "REJECTED";
            case "CANCELLED" -> "CANCELLED";
            default -> "SUBMITTED";
        };
    }

    private String punchType(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "IN", "CHECK_IN" -> "CHECK_IN";
            case "OUT", "CHECK_OUT" -> "CHECK_OUT";
            case "BREAK_START" -> "BREAK_START";
            case "BREAK_END" -> "BREAK_END";
            default -> "IMPORTED_PUNCH";
        };
    }

    private String baselineType(Map<String, String> value) {
        if (value.getOrDefault(
            "represented_plan_approved_at", "").isBlank()) {
            return "HISTORICAL_RECONSTRUCTED";
        }
        LocalDate approved = OffsetDateTime.parse(
            value.get("represented_plan_approved_at")).toLocalDate();
        LocalDate month = LocalDate.parse(
            required(value, "billing_month") + "-01");
        return approved.isAfter(month)
            ? "LATE_APPROVED" : "ON_TIME";
    }

    private String deliveryCategory(String value) {
        String category = String.valueOf(value).toUpperCase(Locale.ROOT);
        return List.of(
            "FEATURE", "PLATFORM", "INTEGRATION", "QUALITY", "OPERATIONS",
            "RESEARCH_POC", "SUPPORT", "OTHER").contains(category)
            ? category : "OTHER";
    }

    private String normalizedLinearState(String value) {
        String state = String.valueOf(value).toUpperCase(Locale.ROOT);
        return List.of(
            "BACKLOG", "UNSTARTED", "STARTED", "COMPLETED",
            "CANCELED", "UNKNOWN").contains(state) ? state : "UNKNOWN";
    }

    private String certificationDecision(String value) {
        return switch (String.valueOf(value).toUpperCase(Locale.ROOT)) {
            case "ACCEPTED", "CERTIFIED" -> "ACCEPTED";
            case "ACCEPTED_WITH_OBSERVATIONS",
                 "CERTIFIED_WITH_OBSERVATIONS" ->
                "ACCEPTED_WITH_OBSERVATIONS";
            case "PARTIALLY_ACCEPTED", "PARTIALLY_CERTIFIED" ->
                "PARTIALLY_ACCEPTED";
            case "REJECTED", "NOT_CERTIFIED" -> "REJECTED";
            default -> "MORE_INFORMATION_REQUIRED";
        };
    }

    private String confirmationAction(String value) {
        return switch (String.valueOf(value).toUpperCase(Locale.ROOT)) {
            case "CONFIRMED", "PROCUREMENT_EXCEPTION_ACCEPTED" -> "CONFIRM";
            case "CHANGES_REQUESTED" -> "REQUEST_CORRECTION";
            default -> "REJECT";
        };
    }

    private DomainEffect inserted(
        String table,
        UUID recordId,
        int version,
        UUID aggregateId
    ) {
        return new DomainEffect(
            table, recordId, version, aggregateId,
            EffectKind.INSERT, Map.of());
    }

    private DomainEffect updated(
        String table,
        UUID recordId,
        int version,
        UUID aggregateId,
        Map<String, Object> beforeState
    ) {
        return new DomainEffect(
            table, recordId, version, aggregateId,
            EffectKind.UPDATE, Map.copyOf(beforeState));
    }

    public enum EffectKind {
        INSERT,
        UPDATE
    }

    public record DomainEffect(
        String table,
        UUID recordId,
        int version,
        UUID aggregateId,
        EffectKind kind,
        Map<String, Object> beforeState
    ) {
        public DomainEffect(
            String table,
            UUID recordId,
            int version,
            UUID aggregateId
        ) {
            this(table, recordId, version, aggregateId,
                EffectKind.INSERT, Map.of());
        }
    }

    private record MappedAuthority(
        String subject,
        Map<String, Object> snapshot
    ) {
    }
}
