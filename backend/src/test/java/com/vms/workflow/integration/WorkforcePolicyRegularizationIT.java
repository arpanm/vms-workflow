package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Savepoint;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_policy_commands",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api"
})
@AutoConfigureMockMvc
@Transactional
class WorkforcePolicyRegularizationIT {
    private static final String ORGANIZATION =
        "00000000-0000-0000-0000-000000000101";
    private static final String EMPLOYEE =
        "00000000-0000-0000-0000-000000000801";
    private static final String CALENDAR =
        "00000000-0000-0000-0000-000000000901";
    private static final String LEAVE_TYPE =
        "00000000-0000-0000-0000-000000000921";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void securedPolicyCommandIsTenantValidatedAuditedAndReplaySafe()
        throws Exception {
        JsonNode employee = mapper.readTree(mvc.perform(post(
                    "/api/v1/workforce/employees")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "organizationId":"%s",
                      "employeeNumber":"AF-POLICY-IT",
                      "firstName":"Policy",
                      "lastName":"Tester",
                      "displayName":"Policy Tester",
                      "workEmail":"policy.tester@arrowfoundry.example",
                      "joinDate":"2026-07-01",
                      "designation":"Tester",
                      "attendanceSourceMode":"INTERNAL_AUTHORITATIVE"
                    }
                    """.formatted(ORGANIZATION)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        String employeeId = employee.path("id").asText();
        String maximumKey = "p".repeat(160);
        String body = """
            {
              "calendarVersionId":"%s",
              "leaveTypeId":"%s",
              "openingUnits":2,
              "effectiveFrom":"2026-07-01",
              "idempotencyKey":"%s",
              "reason":"Approved workforce policy"
            }
            """.formatted(CALENDAR, LEAVE_TYPE, maximumKey);
        JsonNode first = mapper.readTree(mvc.perform(post(
                    "/api/v1/workforce/employees/{id}/policy-assignments",
                    employeeId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.openingUnits").value(2))
            .andReturn().getResponse().getContentAsString());
        JsonNode replay = mapper.readTree(mvc.perform(post(
                    "/api/v1/workforce/employees/{id}/policy-assignments",
                    employeeId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        assertEquals(first.path("id").asText(), replay.path("id").asText());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM employee_policy_assignment_commands
            WHERE employee_id = ?::uuid
            """, employeeId));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM leave_balance_ledger
            WHERE employee_id = ?::uuid
              AND reference_type = 'POLICY_ASSIGNMENT'
            """, employeeId));
        mvc.perform(post(
                    "/api/v1/workforce/employees/{id}/policy-assignments",
                    employeeId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "calendarVersionId":"%s",
                      "leaveTypeId":"%s",
                      "openingUnits":1,
                      "effectiveFrom":"2026-07-01",
                      "idempotencyKey":"%s",
                      "reason":"Oversized key must be rejected before persistence"
                    }
                    """.formatted(CALENDAR, LEAVE_TYPE, "x".repeat(161))))
            .andExpect(status().isBadRequest());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM employee_policy_assignment_commands
            WHERE employee_id = ?::uuid
            """, employeeId));
        mvc.perform(post(
                    "/api/v1/workforce/employees/{id}/policy-assignments",
                    employeeId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "calendarVersionId":"%s",
                      "leaveTypeId":"%s",
                      "openingUnits":1,
                      "effectiveFrom":"2019-12-31",
                      "idempotencyKey":"policy-outside-calendar-window",
                      "reason":"Must be rejected outside calendar validity"
                    }
                    """.formatted(CALENDAR, LEAVE_TYPE)))
            .andExpect(status().isBadRequest());
        assertEquals(0, count("""
            SELECT COUNT(*) FROM employee_policy_assignment_commands
            WHERE employee_id = ?::uuid
              AND idempotency_key = 'policy-outside-calendar-window'
            """, employeeId));
    }

    @Test
    void authorizedDecisionAppendsEvidenceAndRecalculatesAttendance()
        throws Exception {
        JsonNode regularization = mapper.readTree(mvc.perform(post(
                    "/api/v1/attendance/regularizations")
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "employeeId":"%s",
                      "workDate":"2026-07-09",
                      "reasonCode":"APPROVED_CORRECTION",
                      "narrative":"Reviewed evidence supports a full day",
                      "requestedOutcome":"FULL_DAY_PRESENT",
                      "idempotencyKey":"regularization-decision-it"
                    }
                    """.formatted(EMPLOYEE)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUBMITTED"))
            .andReturn().getResponse().getContentAsString());
        String regularizationId = regularization.path("id").asText();
        String decision = """
            {
              "decision":"APPROVE",
              "adjustedNetMinutes":540,
              "reasoning":"Independent attendance review approved exact minutes"
            }
            """;
        JsonNode first = mapper.readTree(mvc.perform(post(
                    "/api/v1/attendance/regularizations/{id}/decisions",
                    regularizationId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(decision))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("APPROVE"))
            .andReturn().getResponse().getContentAsString());
        JsonNode replay = mapper.readTree(mvc.perform(post(
                    "/api/v1/attendance/regularizations/{id}/decisions",
                    regularizationId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(decision))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        assertEquals(first.path("id").asText(), replay.path("id").asText());
        mvc.perform(get("/api/v1/attendance/days")
                .queryParam("employeeId", EMPLOYEE)
                .queryParam("from", "2026-07-09")
                .queryParam("to", "2026-07-09")
                .with(token("user-employee")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].netMinutes").value(540))
            .andExpect(jsonPath("$[0].finalStatus").value("PRESENT_FULL_DAY"));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM attendance_regularization_decisions
            WHERE regularization_id = ?::uuid
            """, regularizationId));
        assertEquals("APPROVED", jdbc.queryForObject("""
            SELECT status FROM attendance_regularizations
            WHERE id = ?::uuid
            """, String.class, regularizationId));

        JsonNode correction = mapper.readTree(mvc.perform(post(
                    "/api/v1/attendance/regularizations")
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "employeeId":"%s",
                      "workDate":"2026-07-09",
                      "reasonCode":"SECOND_GOVERNED_CORRECTION",
                      "narrative":"Later evidence corrects the approved duration",
                      "requestedOutcome":"CORRECT_MINUTES",
                      "idempotencyKey":"regularization-decision-it-v2"
                    }
                    """.formatted(EMPLOYEE)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        String correctionId = correction.path("id").asText();
        mvc.perform(post(
                    "/api/v1/attendance/regularizations/{id}/decisions",
                    correctionId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "decision":"APPROVE",
                      "adjustedNetMinutes":480,
                      "reasoning":"A later independent review approved corrected minutes"
                    }
                    """))
            .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/attendance/days")
                .queryParam("employeeId", EMPLOYEE)
                .queryParam("from", "2026-07-09")
                .queryParam("to", "2026-07-09")
                .with(token("user-employee")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].netMinutes").value(480));
        assertEquals(2, count("""
            SELECT COUNT(*) FROM attendance_regularization_adjustments
            WHERE employee_id = ?::uuid AND work_date = DATE '2026-07-09'
            """, EMPLOYEE));
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM attendance_regularization_adjustments current
            JOIN attendance_regularization_adjustments prior
              ON prior.id = current.supersedes_adjustment_id
            WHERE current.regularization_id = ?::uuid
              AND current.adjustment_version = 2
              AND prior.regularization_id = ?::uuid
              AND prior.adjustment_version = 1
            """, correctionId, regularizationId));
    }

    @Test
    void unauthorizedDecisionIsNonDisclosingAndCreatesNoDecision()
        throws Exception {
        JsonNode regularization = mapper.readTree(mvc.perform(post(
                    "/api/v1/attendance/regularizations")
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "employeeId":"%s",
                      "workDate":"2026-07-10",
                      "reasonCode":"AUTHORIZATION_BOUNDARY",
                      "narrative":"Authorization must precede the row lock",
                      "requestedOutcome":"FULL_DAY_PRESENT",
                      "idempotencyKey":"regularization-authz-it"
                    }
                    """.formatted(EMPLOYEE)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        String regularizationId = regularization.path("id").asText();
        mvc.perform(post(
                    "/api/v1/attendance/regularizations/{id}/decisions",
                    regularizationId)
                .with(token("user-reliance"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "decision":"APPROVE",
                      "adjustedNetMinutes":540,
                      "reasoning":"Unauthorized cross-organization attempt"
                    }
                    """))
            .andExpect(status().isNotFound());
        assertEquals(0, count("""
            SELECT COUNT(*) FROM attendance_regularization_decisions
            WHERE regularization_id = ?::uuid
            """, regularizationId));
    }

    @Test
    void oversizedRegularizationKeyIsRejectedBeforePersistence()
        throws Exception {
        mvc.perform(post("/api/v1/attendance/regularizations")
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "employeeId":"%s",
                      "workDate":"2026-07-14",
                      "reasonCode":"OVERSIZED_KEY",
                      "narrative":"Validation must precede persistence",
                      "requestedOutcome":"CORRECT_MINUTES",
                      "idempotencyKey":"%s"
                    }
                    """.formatted(EMPLOYEE, "r".repeat(161))))
            .andExpect(status().isBadRequest());
        assertEquals(0, count("""
            SELECT COUNT(*) FROM attendance_regularizations
            WHERE employee_id = ?::uuid
              AND work_date = DATE '2026-07-14'
              AND reason_code = 'OVERSIZED_KEY'
            """, EMPLOYEE));
    }

    @Test
    void databaseRejectsCrossDateTargetAndPredecessorLineage() {
        UUID mismatchedRegularization = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO attendance_regularizations
                (id, employee_id, work_date, reason_code, narrative,
                 requested_outcome, idempotency_key, created_by_subject)
            VALUES (?, ?::uuid, DATE '2026-07-11', 'DB_SCOPE_TEST',
                    'Database scope test', 'CORRECT_MINUTES', ?,
                    'SYSTEM:LINEAGE_TEST')
            """, mismatchedRegularization, EMPLOYEE,
            "db-scope-" + mismatchedRegularization);
        assertSqlRejected("""
            INSERT INTO attendance_regularization_adjustments
                (id, regularization_id, employee_id, work_date,
                 adjustment_version, supersedes_adjustment_id,
                 supersedes_adjustment_version, adjusted_net_minutes,
                 reason, recorded_by_subject)
            VALUES (
                gen_random_uuid(), '%s'::uuid, '%s'::uuid, DATE '2026-07-12',
                1, NULL, NULL, 480, 'Cross-date target must fail',
                'SYSTEM:LINEAGE_TEST')
            """.formatted(mismatchedRegularization, EMPLOYEE));

        UUID dateTwelveRegularization = UUID.randomUUID();
        UUID dateThirteenRegularization = UUID.randomUUID();
        UUID nextDateTwelveRegularization = UUID.randomUUID();
        insertRegularization(dateTwelveRegularization, "2026-07-12");
        insertRegularization(dateThirteenRegularization, "2026-07-13");
        insertRegularization(nextDateTwelveRegularization, "2026-07-12");
        UUID dateTwelveAdjustment = UUID.randomUUID();
        UUID dateThirteenAdjustment = UUID.randomUUID();
        insertFirstAdjustment(
            dateTwelveAdjustment, dateTwelveRegularization, "2026-07-12");
        insertFirstAdjustment(
            dateThirteenAdjustment, dateThirteenRegularization, "2026-07-13");
        assertSqlRejected("""
            UPDATE attendance_regularizations
            SET work_date = DATE '2026-07-13'
            WHERE id = '%s'::uuid
            """.formatted(dateTwelveRegularization));
        assertSqlRejected("""
            DELETE FROM attendance_regularizations
            WHERE id = '%s'::uuid
            """.formatted(dateTwelveRegularization));

        assertSqlRejected("""
            INSERT INTO attendance_regularization_adjustments
                (id, regularization_id, employee_id, work_date,
                 adjustment_version, supersedes_adjustment_id,
                 supersedes_adjustment_version, adjusted_net_minutes,
                 reason, recorded_by_subject)
            VALUES (
                gen_random_uuid(), '%s'::uuid, '%s'::uuid, DATE '2026-07-12',
                2, '%s'::uuid, 1, 450, 'Cross-date predecessor must fail',
                'SYSTEM:LINEAGE_TEST')
            """.formatted(
                nextDateTwelveRegularization, EMPLOYEE, dateThirteenAdjustment));
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM attendance_regularization_adjustments
            WHERE id = ?::uuid
              AND employee_id = ?::uuid
              AND work_date = DATE '2026-07-12'
              AND adjustment_version = 1
            """, dateTwelveAdjustment, EMPLOYEE));
    }

    @Test
    void databaseRejectsCrossOrganizationPolicyCommandAndAcceptsValidLineage() {
        UUID foreignCalendar = UUID.randomUUID();
        UUID foreignLeaveType = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO working_calendar_versions
                (id, organization_id, name, timezone, version, valid_from,
                 expected_full_minutes, expected_half_minutes)
            VALUES (?, '00000000-0000-0000-0000-000000000102'::uuid,
                    'Foreign policy command calendar', 'UTC', 1,
                    DATE '2026-01-01', 540, 270)
            """, foreignCalendar);
        jdbc.update("""
            INSERT INTO leave_types
                (id, organization_id, code, name, paid, balance_tracked,
                 minimum_increment)
            VALUES (?, '00000000-0000-0000-0000-000000000102'::uuid,
                    'FOREIGN_POLICY', 'Foreign policy command leave',
                    TRUE, TRUE, 0.5)
            """, foreignLeaveType);

        assertSqlRejected("""
            INSERT INTO employee_policy_assignment_commands
                (id, employee_id, calendar_version_id, leave_type_id,
                 opening_units, effective_from, idempotency_key, reason,
                 created_by_subject)
            VALUES (
                gen_random_uuid(), '%s'::uuid, '%s'::uuid, '%s'::uuid,
                1, DATE '2026-07-01', 'db-cross-org-policy-command',
                'Cross-organization policy lineage must fail',
                'SYSTEM:POLICY_LINEAGE_TEST')
            """.formatted(EMPLOYEE, foreignCalendar, foreignLeaveType));

        UUID validCommand = UUID.randomUUID();
        assertEquals(1, jdbc.update("""
            INSERT INTO employee_policy_assignment_commands
                (id, employee_id, calendar_version_id, leave_type_id,
                 opening_units, effective_from, idempotency_key, reason,
                 created_by_subject)
            VALUES (?, ?::uuid, ?::uuid, ?::uuid, 1,
                    DATE '2026-07-01', 'db-valid-policy-command',
                    'Same-organization policy lineage is valid',
                    'SYSTEM:POLICY_LINEAGE_TEST')
            """, validCommand, EMPLOYEE, CALENDAR, LEAVE_TYPE));
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM employee_policy_assignment_commands command
            JOIN employees employee
              ON employee.id = command.employee_id
             AND employee.organization_id = command.organization_id
            JOIN working_calendar_versions calendar
              ON calendar.id = command.calendar_version_id
             AND calendar.organization_id = command.organization_id
            JOIN leave_types leave_type
              ON leave_type.id = command.leave_type_id
             AND leave_type.organization_id = command.organization_id
            WHERE command.id = ?
            """, validCommand));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT function.prosecdef
               AND function.proconfig
                   @> ARRAY['search_path=pg_catalog, public']::text[]
               AND owner.rolname = 'vms_migration_owner'
               AND has_function_privilege(
                   'vms_app_runtime',
                   'enforce_policy_command_organization()',
                   'EXECUTE')
               AND NOT has_function_privilege(
                   'vms_job_worker',
                   'enforce_policy_command_organization()',
                   'EXECUTE')
            FROM pg_proc function
            JOIN pg_roles owner ON owner.oid = function.proowner
            WHERE function.oid =
                'enforce_policy_command_organization()'::regprocedure
            """, Boolean.class)));
    }

    private void insertRegularization(UUID id, String workDate) {
        jdbc.update("""
            INSERT INTO attendance_regularizations
                (id, employee_id, work_date, reason_code, narrative,
                 requested_outcome, idempotency_key, created_by_subject)
            VALUES (?, ?::uuid, ?::date, 'DB_LINEAGE_TEST',
                    'Database lineage test', 'CORRECT_MINUTES', ?,
                    'SYSTEM:LINEAGE_TEST')
            """, id, EMPLOYEE, workDate, "db-lineage-" + id);
    }

    private void insertFirstAdjustment(
        UUID adjustmentId,
        UUID regularizationId,
        String workDate
    ) {
        jdbc.update("""
            INSERT INTO attendance_regularization_adjustments
                (id, regularization_id, employee_id, work_date,
                 adjustment_version, supersedes_adjustment_id,
                 supersedes_adjustment_version, adjusted_net_minutes,
                 reason, recorded_by_subject)
            VALUES (?, ?, ?::uuid, ?::date, 1, NULL, NULL, 480,
                    'First governed adjustment', 'SYSTEM:LINEAGE_TEST')
            """, adjustmentId, regularizationId, EMPLOYEE, workDate);
    }

    private void assertSqlRejected(String sql) {
        Boolean rejected = jdbc.execute(
            (org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
                Savepoint savepoint = connection.setSavepoint();
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate(sql);
                    connection.rollback(savepoint);
                    return false;
                } catch (java.sql.SQLException expected) {
                    connection.rollback(savepoint);
                    return true;
                } finally {
                    connection.releaseSavepoint(savepoint);
                }
            });
        assertTrue(
            Boolean.TRUE.equals(rejected),
            "SQL mutation should be rejected: " + sql);
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor token(
        String subject
    ) {
        return jwt().jwt(value -> value
            .subject(subject)
            .audience(List.of("vms-api")));
    }
}
