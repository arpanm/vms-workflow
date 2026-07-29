package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow",
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
class WorkforceAttendanceIT {
    private static final String ARROW_ORG = "00000000-0000-0000-0000-000000000101";
    private static final String EMPLOYEE = "00000000-0000-0000-0000-000000000801";
    private static final String ENGAGEMENT = "00000000-0000-0000-0000-000000000401";
    private static final String PROJECT = "00000000-0000-0000-0000-000000000501";
    private static final String SECOND_PROJECT = "00000000-0000-0000-0000-000000000502";
    private static final String LEAVE_TYPE = "00000000-0000-0000-0000-000000000921";
    private static final String JUNE_MONTH = "00000000-0000-0000-0000-000000000601";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void tWf001002_employeeLifecycleIsEffectiveDatedAndPreservesHistory() throws Exception {
        mvc.perform(get("/api/v1/workforce/employees")
                .queryParam("organizationId", ARROW_ORG)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].employeeNumber").value("AF-001"))
            .andExpect(jsonPath("$[0].employmentStatus").value("ACTIVE"));

        mvc.perform(patch("/api/v1/workforce/employees/{id}/lifecycle", EMPLOYEE)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "effectiveFrom": "2026-07-26",
                      "employmentStatus": "ACTIVE",
                      "activationStatus": "ENABLED",
                      "reason": "Effective-dated profile confirmation"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(2));

        assertEquals(2, jdbc.queryForObject(
            "SELECT COUNT(*) FROM employee_versions WHERE employee_id = ?",
            Integer.class, UUID.fromString(EMPLOYEE)));
    }

    @Test
    void tWf003004_allocationsRejectConcurrentTotalsAboveOneHundredPercent() throws Exception {
        mvc.perform(post("/api/v1/workforce/employees/{id}/allocations", EMPLOYEE)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "engagementId": "%s",
                      "projectId": "%s",
                      "validFrom": "2026-07-01",
                      "validTo": "2026-07-31",
                      "allocationPercent": 40,
                      "roleOnProject": "Contributor"
                    }
                    """.formatted(ENGAGEMENT, SECOND_PROJECT)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.allocationPercent").value(40));

        mvc.perform(post("/api/v1/workforce/employees/{id}/allocations", EMPLOYEE)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "engagementId": "%s",
                      "projectId": "%s",
                      "validFrom": "2026-07-10",
                      "validTo": "2026-07-20",
                      "allocationPercent": 20
                    }
                    """.formatted(ENGAGEMENT, PROJECT)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void tWf005006_calendarWeeklyOffHolidayAndDateOverrideResolveByDate() throws Exception {
        mvc.perform(get("/api/v1/attendance/days")
                .queryParam("employeeId", EMPLOYEE)
                .queryParam("from", "2026-07-04")
                .queryParam("to", "2026-07-06")
                .with(token("user-employee")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].expectedClassification").value("WORKING"))
            .andExpect(jsonPath("$[0].expectedMinutes").value(540))
            .andExpect(jsonPath("$[1].finalStatus").value("WEEKLY_OFF"))
            .andExpect(jsonPath("$[2].finalStatus").value("HOLIDAY"));
    }

    @Test
    void tWf007009_leaveLedgerIsIdempotentAndExcessBecomesExplicitLwp() throws Exception {
        String request = """
            {
              "leaveTypeId": "%s",
              "startDate": "2026-07-07",
              "endDate": "2026-07-07",
              "units": 1.0,
              "reason": "Partial paid leave test",
              "idempotencyKey": "leave-july-7"
            }
            """.formatted(LEAVE_TYPE);

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/workforce/employees/{id}/leave-requests", EMPLOYEE)
                    .with(token("user-employee"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paidUnits").value(0.5))
                .andExpect(jsonPath("$.lwpUnits").value(0.5));
        }

        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM leave_requests
            WHERE employee_id = ? AND idempotency_key = 'leave-july-7'
            """, Integer.class, UUID.fromString(EMPLOYEE)));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM leave_balance_ledger
            WHERE employee_id = ? AND idempotency_key = 'leave-consume:leave-july-7'
            """, Integer.class, UUID.fromString(EMPLOYEE)));

        jdbc.update("""
            INSERT INTO leave_balance_ledger
                (id, employee_id, leave_type_id, entry_type, quantity, effective_date,
                 idempotency_key, reason, recorded_by_subject)
            VALUES (?, ?, ?, 'MONTHLY_ACCRUAL', 1, DATE '2026-08-01',
                    'accrual:2026-08:v1', 'Monthly accrual', 'test-fixture')
            """, UUID.fromString("00000000-0000-0000-0000-000000000924"),
            UUID.fromString(EMPLOYEE), UUID.fromString(LEAVE_TYPE));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            INSERT INTO leave_balance_ledger
                (id, employee_id, leave_type_id, entry_type, quantity, effective_date,
                 idempotency_key, reason, recorded_by_subject)
            VALUES (?, ?, ?, 'MONTHLY_ACCRUAL', 1, DATE '2026-08-01',
                    'accrual:2026-08:v1', 'Duplicate accrual', 'test-fixture')
            """, UUID.fromString("00000000-0000-0000-0000-000000000925"),
            UUID.fromString(EMPLOYEE), UUID.fromString(LEAVE_TYPE)));
    }

    @Test
    void tAtt001003_checkInCheckoutAndRetryAreIdempotentImmutableEvents() throws Exception {
        String checkIn = """
            {
              "employeeId": "%s",
              "eventType": "CHECK_IN",
              "idempotencyKey": "punch-check-in"
            }
            """.formatted(EMPLOYEE);
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/attendance/punches")
                    .with(token("user-employee"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(checkIn))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value("CHECK_IN"));
        }

        mvc.perform(post("/api/v1/attendance/punches")
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "employeeId": "%s",
                      "eventType": "CHECK_OUT",
                      "idempotencyKey": "punch-check-out"
                    }
                    """.formatted(EMPLOYEE)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.sessionStatus").value("CLOSED"));

        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM attendance_events
            WHERE employee_id = ? AND idempotency_key IN ('punch-check-in', 'punch-check-out')
            """, Integer.class, UUID.fromString(EMPLOYEE)));
    }

    @Test
    void tAtt004006_partialLeaveClassifiesAndMissingCheckoutInventsNoPunchOrMinutes() throws Exception {
        mvc.perform(post("/api/v1/workforce/employees/{id}/leave-requests", EMPLOYEE)
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "leaveTypeId": "%s",
                      "startDate": "2026-07-07",
                      "endDate": "2026-07-07",
                      "units": 1.0,
                      "reason": "Partial paid leave test",
                      "idempotencyKey": "leave-july-7-classification"
                    }
                    """.formatted(LEAVE_TYPE)))
            .andExpect(status().isCreated());
        insertClosedSession(
            "00000000-0000-0000-0000-000000000941",
            "00000000-0000-0000-0000-000000000942",
            "00000000-0000-0000-0000-000000000943",
            "2026-07-07T03:30:00Z", "2026-07-07T08:00:00Z", "2026-07-07");
        insertOpenSession(
            "00000000-0000-0000-0000-000000000944",
            "00000000-0000-0000-0000-000000000945",
            "2026-07-08T03:30:00Z", "2026-07-08");

        mvc.perform(get("/api/v1/attendance/days")
                .queryParam("employeeId", EMPLOYEE)
                .queryParam("from", "2026-07-07")
                .queryParam("to", "2026-07-08")
                .with(token("user-employee")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].netMinutes").value(270))
            .andExpect(jsonPath("$[0].finalStatus").value("PRESENT_HALF_PLUS_PAID_LEAVE_HALF"))
            .andExpect(jsonPath("$[1].netMinutes").value(0))
            .andExpect(jsonPath("$[1].exceptionCode").value("MISSING_CHECKOUT"))
            .andExpect(jsonPath("$[1].finalStatus").value("MISSING_CHECKOUT_EXCEPTION"));

        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM attendance_events
            WHERE employee_id = ? AND work_date = DATE '2026-07-08'
            """, Integer.class, UUID.fromString(EMPLOYEE)));
        closeImportedOpenSession();
    }

    @Test
    void tAtt012013_snapshotIsImmutableAndReopenCreatesSupersedingLineage() throws Exception {
        insertClosedSession(
            "00000000-0000-0000-0000-000000000946",
            "00000000-0000-0000-0000-000000000947",
            "00000000-0000-0000-0000-000000000948",
            "2026-06-10T03:30:00Z", "2026-06-10T12:30:00Z", "2026-06-10");
        String response = mvc.perform(post("/api/v1/attendance/month-snapshots")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"engagementMonthId": "%s"}
                    """.formatted(JUNE_MONTH)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.dayCount").value(30))
            .andReturn().getResponse().getContentAsString();
        String snapshotId = response.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        String reopenResponse = mvc.perform(post("/api/v1/attendance/month-snapshots/{id}/reopen", snapshotId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason": "Approved correction cycle"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.status").value("REOPENED"))
            .andExpect(jsonPath("$.supersedesId").value(snapshotId))
            .andExpect(jsonPath("$.reopenedAt").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        String reopenedId = reopenResponse.replaceAll(
            ".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");

        mvc.perform(post("/api/v1/attendance/month-snapshots")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"engagementMonthId": "%s"}
                    """.formatted(JUNE_MONTH)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.status").value("CLOSED"))
            .andExpect(jsonPath("$.supersedesId").value(reopenedId))
            .andExpect(jsonPath("$.reopenedAt").doesNotExist())
            .andExpect(jsonPath("$.dayCount").value(30));

        assertEquals(3, jdbc.queryForObject("""
            SELECT COUNT(*) FROM attendance_snapshot_versions
            WHERE engagement_month_id = ?
            """, Integer.class, UUID.fromString(JUNE_MONTH)));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            UPDATE attendance_snapshot_versions SET checksum = repeat('0', 64) WHERE id = ?
            """, UUID.fromString(snapshotId)));
    }

    @Test
    void tWfTenantDenial_isNonDisclosingAcrossEmployeeAndAttendanceApis() throws Exception {
        mvc.perform(get("/api/v1/workforce/employees/{id}", EMPLOYEE)
                .with(token("user-reliance")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));

        mvc.perform(get("/api/v1/attendance/days")
                .queryParam("employeeId", EMPLOYEE)
                .queryParam("from", "2026-07-07")
                .queryParam("to", "2026-07-07")
                .with(token("user-reliance")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
    }

    @Test
    void tGhr001002_authoritativeModeFailsClosedWithoutCertifiedCapability() {
        UUID certificationId = UUID.fromString("00000000-0000-0000-0000-000000000971");
        jdbc.update("""
            INSERT INTO integration_capability_certifications
                (id, organization_id, provider, status, capability_manifest)
            VALUES (?, ?, 'GREYTHR', 'DRAFT', '{}'::jsonb)
            """, certificationId, UUID.fromString(ARROW_ORG));

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
            UPDATE attendance_source_mode_assignments
            SET mode = 'GREYTHR_AUTHORITATIVE',
                authoritative_source = 'GREYTHR',
                capability_certification_id = ?
            WHERE employee_id = ?
            """, certificationId, UUID.fromString(EMPLOYEE)));

    }

    @Test
    void attendanceGetCalculatesWithoutCreatingVersioningOrResolvingRows() throws Exception {
        insertOpenSession(
            "00000000-0000-0000-0000-000000000951",
            "00000000-0000-0000-0000-000000000952",
            "2026-07-09T03:30:00Z", "2026-07-09");
        int dayCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_days", Integer.class);
        int exceptionCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_exceptions", Integer.class);

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(get("/api/v1/attendance/days")
                    .queryParam("employeeId", EMPLOYEE)
                    .queryParam("from", "2026-07-09")
                    .queryParam("to", "2026-07-09")
                    .with(token("user-employee")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].exceptionCode").value("MISSING_CHECKOUT"));
        }

        assertEquals(dayCount, jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_days", Integer.class));
        assertEquals(exceptionCount, jdbc.queryForObject(
            "SELECT COUNT(*) FROM attendance_exceptions", Integer.class));
    }

    @Test
    void multiDayLeaveAllocatesAggregatePaidAndLwpUnitsExactlyOnce() throws Exception {
        mvc.perform(post("/api/v1/workforce/employees/{id}/leave-requests", EMPLOYEE)
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "leaveTypeId": "%s",
                      "startDate": "2026-07-14",
                      "endDate": "2026-07-15",
                      "units": 2.0,
                      "reason": "Two day split",
                      "idempotencyKey": "leave-two-day-exact"
                    }
                    """.formatted(LEAVE_TYPE)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.paidUnits").value(0.5))
            .andExpect(jsonPath("$.lwpUnits").value(1.5));

        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM leave_requests request
            WHERE request.idempotency_key = 'leave-two-day-exact'
              AND (
                request.paid_units <> (
                    SELECT COALESCE(SUM(day.paid_units), 0)
                    FROM leave_request_days day
                    WHERE day.leave_request_id = request.id
                )
                OR request.lwp_units <> (
                    SELECT COALESCE(SUM(day.lwp_units), 0)
                    FROM leave_request_days day
                    WHERE day.leave_request_id = request.id
                )
              )
            """, Integer.class));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM leave_request_days day
            JOIN leave_requests request ON request.id = day.leave_request_id
            WHERE request.idempotency_key = 'leave-two-day-exact'
            """, Integer.class));

        mvc.perform(get("/api/v1/attendance/days")
                .queryParam("employeeId", EMPLOYEE)
                .queryParam("from", "2026-07-14")
                .queryParam("to", "2026-07-15")
                .with(token("user-employee")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].leaveUnits").value(1.0))
            .andExpect(jsonPath("$[1].leaveUnits").value(1.0))
            .andExpect(jsonPath("$[1].finalStatus").value("LWP"));
    }

    @Test
    void leaveUnitsCannotExceedInclusiveDateSpan() throws Exception {
        mvc.perform(post("/api/v1/workforce/employees/{id}/leave-requests", EMPLOYEE)
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "leaveTypeId": "%s",
                      "startDate": "2026-07-16",
                      "endDate": "2026-07-16",
                      "units": 1.5,
                      "reason": "Invalid span",
                      "idempotencyKey": "leave-invalid-span"
                    }
                    """.formatted(LEAVE_TYPE)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void reviewerCanReadButCannotPunchOrSubmitEmployeeRegularization() throws Exception {
        mvc.perform(get("/api/v1/attendance/days")
                .queryParam("employeeId", EMPLOYEE)
                .queryParam("from", "2026-07-10")
                .queryParam("to", "2026-07-10")
                .with(token("user-arrow")))
            .andExpect(status().isOk());

        mvc.perform(post("/api/v1/attendance/punches")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "employeeId": "%s",
                      "eventType": "CHECK_IN",
                      "idempotencyKey": "reviewer-punch"
                    }
                    """.formatted(EMPLOYEE)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));

        mvc.perform(post("/api/v1/attendance/regularizations")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "employeeId": "%s",
                      "workDate": "2026-07-10",
                      "reasonCode": "MISSING_PUNCH",
                      "narrative": "Reviewer must not submit as employee",
                      "requestedOutcome": "ADD_CHECK_OUT",
                      "idempotencyKey": "reviewer-regularization"
                    }
                    """.formatted(EMPLOYEE)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));

        mvc.perform(post("/api/v1/workforce/employees/{id}/leave-requests", EMPLOYEE)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "leaveTypeId": "%s",
                      "startDate": "2026-07-10",
                      "endDate": "2026-07-10",
                      "units": 1.0,
                      "reason": "Reviewer must not submit as employee",
                      "idempotencyKey": "reviewer-leave"
                    }
                    """.formatted(LEAVE_TYPE)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
    }

    @Test
    void leaveAllocationSkipsWeekendAndHolidayWhenSandwichPolicyIsDisabled() throws Exception {
        mvc.perform(post("/api/v1/workforce/employees/{id}/leave-requests", EMPLOYEE)
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "leaveTypeId": "%s",
                      "startDate": "2026-07-05",
                      "endDate": "2026-07-07",
                      "units": 1.0,
                      "reason": "Holiday-spanning leave",
                      "idempotencyKey": "leave-skip-off-days"
                    }
                    """.formatted(LEAVE_TYPE)))
            .andExpect(status().isCreated());

        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM leave_request_days day
            JOIN leave_requests request ON request.id = day.leave_request_id
            WHERE request.idempotency_key = 'leave-skip-off-days'
              AND day.leave_date = DATE '2026-07-07'
            """, Integer.class));
        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM leave_request_days day
            JOIN leave_requests request ON request.id = day.leave_request_id
            WHERE request.idempotency_key = 'leave-skip-off-days'
              AND day.leave_date IN (DATE '2026-07-05', DATE '2026-07-06')
            """, Integer.class));

        mvc.perform(get("/api/v1/attendance/days")
                .queryParam("employeeId", EMPLOYEE)
                .queryParam("from", "2026-07-05")
                .queryParam("to", "2026-07-07")
                .with(token("user-employee")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].leaveUnits").value(0))
            .andExpect(jsonPath("$[1].leaveUnits").value(0))
            .andExpect(jsonPath("$[2].leaveUnits").value(1.0));
    }

    @Test
    void snapshotCloseMaterializesEveryAllocatedDayWithoutPriorReads() throws Exception {
        mvc.perform(post("/api/v1/attendance/month-snapshots")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"engagementMonthId": "%s"}
                    """.formatted(JUNE_MONTH)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.dayCount").value(30));

        assertEquals(30, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM attendance_snapshot_days day
            JOIN attendance_snapshot_versions snapshot ON snapshot.id = day.snapshot_id
            WHERE snapshot.engagement_month_id = ?
            """, Integer.class, UUID.fromString(JUNE_MONTH)));
        assertEquals(30, jdbc.queryForObject("""
            SELECT COUNT(DISTINCT work_date)
            FROM attendance_days
            WHERE employee_id = ?
              AND work_date >= DATE '2026-06-01'
              AND work_date < DATE '2026-07-01'
              AND is_current
            """, Integer.class, UUID.fromString(EMPLOYEE)));
    }

    @Test
    void inactiveAllocationDaysNeitherEnterNorBlockSnapshot() throws Exception {
        jdbc.update("""
            UPDATE employee_project_allocations
            SET status = 'TEMPORARILY_INACTIVE'
            WHERE id = '00000000-0000-0000-0000-000000000831'
            """);
        jdbc.update("""
            INSERT INTO attendance_days
                (id, employee_id, work_date, calculation_version,
                 expected_classification, expected_minutes, source_mode,
                 net_minutes, leave_units, final_status, exception_code)
            VALUES (
                '00000000-0000-0000-0000-000000000981',
                ?,
                DATE '2026-06-10',
                1,
                'WORKING',
                540,
                'INTERNAL_AUTHORITATIVE',
                0,
                0,
                'MISSING_CHECKOUT_EXCEPTION',
                'SOURCE_CONFLICT'
            )
            """, UUID.fromString(EMPLOYEE));
        jdbc.update("""
            INSERT INTO attendance_exceptions
                (id, employee_id, work_date, exception_code)
            VALUES (
                '00000000-0000-0000-0000-000000000982',
                ?,
                DATE '2026-06-10',
                'SOURCE_CONFLICT'
            )
            """, UUID.fromString(EMPLOYEE));

        mvc.perform(post("/api/v1/attendance/month-snapshots")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"engagementMonthId": "%s"}
                    """.formatted(JUNE_MONTH)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.dayCount").value(0));

        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM attendance_snapshot_days day
            JOIN attendance_snapshot_versions snapshot ON snapshot.id = day.snapshot_id
            WHERE snapshot.engagement_month_id = ?
            """, Integer.class, UUID.fromString(JUNE_MONTH)));
    }

    @Test
    void onlyClosedLeafSnapshotCanBeReopened() throws Exception {
        String closeResponse = mvc.perform(post("/api/v1/attendance/month-snapshots")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"engagementMonthId": "%s"}
                    """.formatted(JUNE_MONTH)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String closedId = extractId(closeResponse);
        String reopenResponse = mvc.perform(post(
                    "/api/v1/attendance/month-snapshots/{id}/reopen", closedId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason": "Correction"}
                    """))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String reopenedId = extractId(reopenResponse);

        mvc.perform(post("/api/v1/attendance/month-snapshots/{id}/reopen", closedId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason": "Invalid non-leaf reopen"}
                    """))
            .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/attendance/month-snapshots/{id}/reopen", reopenedId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason": "Invalid reopened-node reopen"}
                    """))
            .andExpect(status().isConflict());
    }

    @Test
    void selfEmployeeEndpointRequiresOneActiveAuthorizedLink() throws Exception {
        mvc.perform(get("/api/v1/workforce/employees/me")
                .with(token("user-employee")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(EMPLOYEE))
            .andExpect(jsonPath("$.employeeNumber").value("AF-001"));

        mvc.perform(get("/api/v1/workforce/employees/me")
                .with(token("user-reliance")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
        mvc.perform(get("/api/v1/workforce/employees/me")
                .with(token("user-disabled")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
    }

    @Test
    void missingEmployeesAreNonDisclosingOnSelfServiceCommands() throws Exception {
        String missingEmployee = "00000000-0000-0000-0000-000000009999";
        mvc.perform(get("/api/v1/workforce/employees/{id}", missingEmployee)
                .with(token("user-employee")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
        mvc.perform(post("/api/v1/attendance/punches")
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "employeeId": "%s",
                      "eventType": "CHECK_IN",
                      "idempotencyKey": "missing-employee-punch"
                    }
                    """.formatted(missingEmployee)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
    }

    @Test
    void revokedCapabilityFailsClosedWhenEffectiveSourceIsEvaluated() throws Exception {
        UUID certificationId = UUID.fromString("00000000-0000-0000-0000-000000000972");
        jdbc.update("""
            INSERT INTO integration_capability_certifications
                (id, organization_id, provider, status, certified_at, capability_manifest)
            VALUES (?, ?, 'GREYTHR', 'CERTIFIED', CURRENT_TIMESTAMP, '{}'::jsonb)
            """, certificationId, UUID.fromString(ARROW_ORG));
        jdbc.update("""
            UPDATE attendance_source_mode_assignments
            SET mode = 'GREYTHR_AUTHORITATIVE',
                authoritative_source = 'GREYTHR',
                capability_certification_id = ?
            WHERE employee_id = ?
            """, certificationId, UUID.fromString(EMPLOYEE));
        jdbc.update("""
            UPDATE integration_capability_certifications
            SET status = 'REVOKED'
            WHERE id = ?
            """, certificationId);

        mvc.perform(get("/api/v1/attendance/days")
                .queryParam("employeeId", EMPLOYEE)
                .queryParam("from", "2026-07-10")
                .queryParam("to", "2026-07-10")
                .with(token("user-employee")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail")
                .value("The effective attendance source capability is no longer certified."));
    }

    private static String extractId(String response) {
        return response.replaceAll(".*\\\"id\\\":\\\"([^\\\"]+)\\\".*", "$1");
    }

    private void insertClosedSession(String checkInId, String checkOutId, String sessionId,
                                     String checkIn, String checkOut, String workDate) {
        OffsetDateTime start = OffsetDateTime.parse(checkIn);
        OffsetDateTime end = OffsetDateTime.parse(checkOut);
        jdbc.update("""
            INSERT INTO attendance_events
                (id, employee_id, event_type, occurred_at, work_date, source,
                 idempotency_key, recorded_by_subject)
            VALUES (?, ?, 'IMPORTED_PUNCH', ?, ?::date, 'CSV_IMPORT', ?, 'test-fixture'),
                   (?, ?, 'IMPORTED_PUNCH', ?, ?::date, 'CSV_IMPORT', ?, 'test-fixture')
            """,
            UUID.fromString(checkInId), UUID.fromString(EMPLOYEE), start, workDate, checkInId,
            UUID.fromString(checkOutId), UUID.fromString(EMPLOYEE), end, workDate, checkOutId);
        jdbc.update("""
            INSERT INTO attendance_sessions
                (id, employee_id, work_date, check_in_event_id, check_out_event_id,
                 check_in_at, check_out_at, net_minutes, status)
            VALUES (?, ?, ?::date, ?, ?, ?, ?, ?, 'CLOSED')
            """, UUID.fromString(sessionId), UUID.fromString(EMPLOYEE), workDate,
            UUID.fromString(checkInId), UUID.fromString(checkOutId), start, end,
            Math.toIntExact(java.time.Duration.between(start, end).toMinutes()));
    }

    private void insertOpenSession(String checkInId, String sessionId, String checkIn,
                                   String workDate) {
        OffsetDateTime start = OffsetDateTime.parse(checkIn).withOffsetSameInstant(ZoneOffset.UTC);
        jdbc.update("""
            INSERT INTO attendance_events
                (id, employee_id, event_type, occurred_at, work_date, source,
                 idempotency_key, recorded_by_subject)
            VALUES (?, ?, 'IMPORTED_PUNCH', ?, ?::date, 'CSV_IMPORT', ?, 'test-fixture')
            """, UUID.fromString(checkInId), UUID.fromString(EMPLOYEE), start, workDate, checkInId);
        jdbc.update("""
            INSERT INTO attendance_sessions
                (id, employee_id, work_date, check_in_event_id, check_in_at, status)
            VALUES (?, ?, ?::date, ?, ?, 'OPEN')
            """, UUID.fromString(sessionId), UUID.fromString(EMPLOYEE), workDate,
            UUID.fromString(checkInId), start);
    }

    private void closeImportedOpenSession() {
        UUID checkOutId = UUID.fromString("00000000-0000-0000-0000-000000000949");
        OffsetDateTime checkOut = OffsetDateTime.parse("2026-07-08T12:30:00Z");
        jdbc.update("""
            INSERT INTO attendance_events
                (id, employee_id, event_type, occurred_at, work_date, source,
                 idempotency_key, recorded_by_subject)
            VALUES (?, ?, 'IMPORTED_PUNCH', ?, DATE '2026-07-08', 'CSV_IMPORT',
                    'resolve-test-open-session', 'test-fixture')
            """, checkOutId, UUID.fromString(EMPLOYEE), checkOut);
        jdbc.update("""
            UPDATE attendance_sessions
            SET check_out_event_id = ?, check_out_at = ?, net_minutes = 540, status = 'CLOSED'
            WHERE id = ?
            """, checkOutId, checkOut,
            UUID.fromString("00000000-0000-0000-0000-000000000945"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor token(String subject) {
        return jwt().jwt(value -> value.subject(subject).audience(List.of("vms-api")));
    }
}
