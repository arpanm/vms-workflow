package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workforce_admin",
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
class WorkforceAdministrationIT {
    private static final String ORGANIZATION =
        "00000000-0000-0000-0000-000000000101";
    private static final String EMPLOYEE =
        "00000000-0000-0000-0000-000000000801";
    private static final String LEAVE_TYPE =
        "00000000-0000-0000-0000-000000000921";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void approvalRequiredLeaveConsumesOnlyAfterDecisionAndCancellationReleases()
        throws Exception {
        mvc.perform(post(
                    "/api/v1/workforce/organizations/{id}/leave-policies",
                    ORGANIZATION)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "leaveTypeCode":"CL",
                      "leaveTypeName":"Casual Leave",
                      "paid":true,
                      "balanceTracked":true,
                      "minimumIncrement":0.5,
                      "validFrom":"2026-08-01",
                      "approvalRequired":true,
                      "maximumUnitsPerRequest":2,
                      "excessToLwp":true,
                      "cancellationAllowed":true,
                      "rules":{"reviewerRequired":true}
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.approvalRequired").value(true));

        JsonNode request = mapper.readTree(mvc.perform(post(
                    "/api/v1/workforce/employees/{id}/leave-requests",
                    EMPLOYEE)
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "leaveTypeId":"%s",
                      "startDate":"2026-08-03",
                      "endDate":"2026-08-03",
                      "units":1,
                      "reason":"Governed approval path",
                      "idempotencyKey":"governed-leave-request"
                    }
                    """.formatted(LEAVE_TYPE)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("SUBMITTED"))
            .andExpect(jsonPath("$.version").value(0))
            .andReturn().getResponse().getContentAsString());
        String requestId = request.path("id").asText();
        mvc.perform(get("/api/v1/workforce/leave-request-inbox")
                .queryParam("organizationId", ORGANIZATION)
                .with(token("user-employee")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/workforce/regularization-inbox")
                .queryParam("organizationId", ORGANIZATION)
                .with(token("user-employee")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/workforce/leave-request-inbox")
                .queryParam("organizationId", ORGANIZATION)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(requestId))
            .andExpect(jsonPath("$[0].status").value("SUBMITTED"))
            .andExpect(jsonPath("$[0].version").value(0));
        assertEquals(0, count("""
            SELECT COUNT(*) FROM leave_balance_ledger
            WHERE reference_id = ?::uuid AND entry_type = 'LEAVE_CONSUMED'
            """, requestId));

        String approval = """
            {
              "decision":"APPROVE",
              "expectedVersion":0,
              "idempotencyKey":"governed-leave-approve",
              "reason":"Independent manager reviewed the request"
            }
            """;
        JsonNode first = mapper.readTree(mvc.perform(post(
                    "/api/v1/workforce/leave-requests/{id}/decisions",
                    requestId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(approval))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.requestStatus").value("APPROVED"))
            .andExpect(jsonPath("$.requestVersion").value(1))
            .andReturn().getResponse().getContentAsString());
        JsonNode replay = mapper.readTree(mvc.perform(post(
                    "/api/v1/workforce/leave-requests/{id}/decisions",
                    requestId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(approval))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        assertEquals(first.path("id").asText(), replay.path("id").asText());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM leave_balance_ledger
            WHERE reference_id = ?::uuid AND entry_type = 'LEAVE_CONSUMED'
            """, requestId));

        mvc.perform(post(
                    "/api/v1/workforce/leave-requests/{id}/decisions",
                    requestId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "decision":"CANCEL",
                      "expectedVersion":1,
                      "idempotencyKey":"governed-leave-cancel",
                      "reason":"Employee withdrew before the leave date"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.requestStatus").value("CANCELLED"))
            .andExpect(jsonPath("$.requestVersion").value(2));
        JsonNode replayAfterCancellation = mapper.readTree(mvc.perform(post(
                    "/api/v1/workforce/leave-requests/{id}/decisions",
                    requestId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(approval))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.requestStatus").value("APPROVED"))
            .andExpect(jsonPath("$.requestVersion").value(1))
            .andReturn().getResponse().getContentAsString());
        assertEquals(first, replayAfterCancellation);
        assertEquals(1, count("""
            SELECT COUNT(*) FROM leave_balance_ledger
            WHERE reference_id = ?::uuid AND entry_type = 'LEAVE_RELEASED'
            """, requestId));
    }

    @Test
    void aliasCsvValidationAndBreakPairsAreDurableAndReplaySafe()
        throws Exception {
        String csv = """
            employeeNumber,aliasType,aliasValue,validFrom
            AF-001,HRIS_ID,GHR-001,2026-08-01
            """;
        mvc.perform(post(
                    "/api/v1/workforce/organizations/{id}/imports",
                    ORGANIZATION)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    java.util.Map.of(
                        "importType", "EMPLOYEE_ALIASES",
                        "fileName", "employee-aliases.csv",
                        "csvContent", csv,
                        "idempotencyKey", "alias-validate",
                        "apply", false))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("VALIDATED"))
            .andExpect(jsonPath("$.importedCount").value(0));
        assertEquals(0, count("""
            SELECT COUNT(*) FROM employee_aliases
            WHERE employee_id = ?::uuid AND alias_value = 'GHR-001'
            """, EMPLOYEE));

        mvc.perform(post(
                    "/api/v1/workforce/organizations/{id}/imports",
                    ORGANIZATION)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                    java.util.Map.of(
                        "importType", "EMPLOYEE_ALIASES",
                        "fileName", "employee-aliases.csv",
                        "csvContent", csv,
                        "idempotencyKey", "alias-apply",
                        "apply", true))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("IMPORTED"))
            .andExpect(jsonPath("$.importedCount").value(1));

        for (String event : List.of(
            "CHECK_IN", "BREAK_START", "BREAK_END", "CHECK_OUT")) {
            mvc.perform(post("/api/v1/attendance/punches")
                    .with(token("user-employee"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "employeeId":"%s",
                          "eventType":"%s",
                          "idempotencyKey":"break-flow-%s"
                        }
                        """.formatted(EMPLOYEE, event, event.toLowerCase())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventType").value(event));
        }
        assertEquals(1, count("""
            SELECT COUNT(*) FROM attendance_breaks
            WHERE employee_id = ?::uuid AND status = 'CLOSED'
            """, EMPLOYEE));
        assertEquals(4, count("""
            SELECT COUNT(*) FROM attendance_events
            WHERE employee_id = ?::uuid
              AND idempotency_key LIKE 'break-flow-%%'
            """, EMPLOYEE));
    }

    @Test
    void calendarBalanceCommandsAndDeliverableBoundsAreGoverned()
        throws Exception {
        mvc.perform(post(
                    "/api/v1/workforce/organizations/{id}/calendars",
                    ORGANIZATION)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name":"F02 Managed Calendar",
                      "timezone":"Asia/Kolkata",
                      "validFrom":"2026-08-01",
                      "expectedFullMinutes":540,
                      "expectedHalfMinutes":270,
                      "weekdays":[
                        {"isoWeekday":1,"classification":"WORKING","expectedMinutes":540},
                        {"isoWeekday":2,"classification":"WORKING","expectedMinutes":540},
                        {"isoWeekday":3,"classification":"WORKING","expectedMinutes":540},
                        {"isoWeekday":4,"classification":"WORKING","expectedMinutes":540},
                        {"isoWeekday":5,"classification":"WORKING","expectedMinutes":540},
                        {"isoWeekday":6,"classification":"WEEKLY_OFF","expectedMinutes":0},
                        {"isoWeekday":7,"classification":"WEEKLY_OFF","expectedMinutes":0}
                      ],
                      "holidays":[
                        {
                          "holidayDate":"2026-08-15",
                          "name":"Independence Day",
                          "classification":"HOLIDAY",
                          "expectedMinutes":0
                        }
                      ]
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.holidays[0].name")
                .value("Independence Day"));

        String balance = """
            {
              "leaveTypeId":"%s",
              "commandType":"GRANT",
              "quantity":2,
              "effectiveDate":"2026-08-01",
              "idempotencyKey":"opening-grant",
              "reason":"Approved opening balance"
            }
            """.formatted(LEAVE_TYPE);
        JsonNode first = mapper.readTree(mvc.perform(post(
                    "/api/v1/workforce/employees/{id}/leave-balance-commands",
                    EMPLOYEE)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(balance))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        JsonNode replay = mapper.readTree(mvc.perform(post(
                    "/api/v1/workforce/employees/{id}/leave-balance-commands",
                    EMPLOYEE)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(balance))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        assertEquals(first, replay);
        mvc.perform(post(
                    "/api/v1/workforce/employees/{id}/leave-balance-commands",
                    EMPLOYEE)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(balance.replace("\"quantity\":2", "\"quantity\":3")))
            .andExpect(status().isConflict());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM leave_balance_commands
            WHERE employee_id = ?::uuid AND idempotency_key = 'opening-grant'
            """, EMPLOYEE));

        jdbc.update("""
            INSERT INTO delivery_plans
                (id, engagement_month_id, created_by_subject)
            VALUES (?::uuid, ?::uuid, 'f02-test')
            """, "70000000-0000-0000-0000-000000000001",
            "00000000-0000-0000-0000-000000000601");
        jdbc.update("""
            INSERT INTO delivery_deliverables
                (id, plan_id, deliverable_code)
            VALUES (?::uuid, ?::uuid, 'F02-DEL-1')
            """, "70000000-0000-0000-0000-000000000011",
            "70000000-0000-0000-0000-000000000001");
        mvc.perform(post(
                    "/api/v1/workforce/employees/{id}/deliverable-allocations",
                    EMPLOYEE)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(deliverableAllocation(
                    "70000000-0000-0000-0000-000000000011", 30)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.deliverableCode").value("F02-DEL-1"))
            .andExpect(jsonPath("$.allocationPercent").value(30));
        mvc.perform(post(
                    "/api/v1/workforce/employees/{id}/deliverable-allocations",
                    EMPLOYEE)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(deliverableAllocation(
                    "70000000-0000-0000-0000-000000000011", 25)))
            .andExpect(status().isConflict());

        jdbc.update("""
            INSERT INTO delivery_plans
                (id, engagement_month_id, created_by_subject)
            VALUES (?::uuid, ?::uuid, 'f02-test')
            """, "70000000-0000-0000-0000-000000000002",
            "00000000-0000-0000-0000-000000000603");
        jdbc.update("""
            INSERT INTO delivery_deliverables
                (id, plan_id, deliverable_code)
            VALUES (?::uuid, ?::uuid, 'F02-OTHER-SCOPE')
            """, "70000000-0000-0000-0000-000000000012",
            "70000000-0000-0000-0000-000000000002");
        mvc.perform(post(
                    "/api/v1/workforce/employees/{id}/deliverable-allocations",
                    EMPLOYEE)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(deliverableAllocation(
                    "70000000-0000-0000-0000-000000000012", 10)))
            .andExpect(status().isConflict());
    }

    @Test
    void shiftPolicyCompletesAndFreezesTheDayLevelRoster() throws Exception {
        mvc.perform(get(
                    "/api/v1/workforce/engagement-months/{id}/roster-readiness",
                    "00000000-0000-0000-0000-000000000601")
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ready").value(false))
            .andExpect(jsonPath("$.allocatedEmployeeCount").value(1))
            .andExpect(jsonPath("$.allocatedEmployeeDayCount").value(30))
            .andExpect(jsonPath("$.missingShiftDayCount").value(30));

        JsonNode policy = mapper.readTree(mvc.perform(post(
                    "/api/v1/workforce/organizations/{id}/shift-policies",
                    ORGANIZATION)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "code":"NIGHT",
                      "name":"Governed overnight shift",
                      "timezone":"Asia/Kolkata",
                      "validFrom":"2026-06-01",
                      "scheduledStartLocalTime":"21:00:00",
                      "scheduledEndLocalTime":"06:00:00",
                      "overnightCutoffLocalTime":"07:00:00",
                      "expectedNetMinutes":480,
                      "maximumSessionMinutes":720,
                      "allowSplitSessions":true,
                      "minimumBreakMinutes":30
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.expectedNetMinutes").value(480))
            .andReturn().getResponse().getContentAsString());

        mvc.perform(post(
                    "/api/v1/workforce/employees/{id}/shift-assignments",
                    EMPLOYEE)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "shiftPolicyVersionId":"%s",
                      "validFrom":"2026-06-01"
                    }
                    """.formatted(policy.path("id").asText())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.shiftPolicyCode").value("NIGHT"));

        mvc.perform(get(
                    "/api/v1/workforce/engagement-months/{id}/roster-readiness",
                    "00000000-0000-0000-0000-000000000601")
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ready").value(true))
            .andExpect(jsonPath("$.missingShiftDayCount").value(0));

        String finalizeBody = """
            {"reason":"Manager verified allocation, calendar, source and shift coverage."}
            """;
        JsonNode first = mapper.readTree(mvc.perform(post(
                    "/api/v1/workforce/engagement-months/{id}/roster-snapshots",
                    "00000000-0000-0000-0000-000000000601")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(finalizeBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("FINALIZED"))
            .andExpect(jsonPath("$.employeeCount").value(1))
            .andExpect(jsonPath("$.employeeDayCount").value(30))
            .andExpect(jsonPath("$.checksum").isNotEmpty())
            .andReturn().getResponse().getContentAsString());
        JsonNode replay = mapper.readTree(mvc.perform(post(
                    "/api/v1/workforce/engagement-months/{id}/roster-snapshots",
                    "00000000-0000-0000-0000-000000000601")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(finalizeBody))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        assertEquals(first.path("id").asText(), replay.path("id").asText());
        assertEquals(30, count("""
            SELECT COUNT(DISTINCT (employee_id, work_date))
            FROM workforce_roster_snapshot_days
            WHERE snapshot_id = ?::uuid
            """, first.path("id").asText()));
    }

    private static String deliverableAllocation(
        String deliverableId,
        int percentage
    ) {
        return """
            {
              "projectAllocationId":"00000000-0000-0000-0000-000000000831",
              "deliverableId":"%s",
              "validFrom":"2026-08-01",
              "allocationPercent":%d,
              "roleOnDeliverable":"Engineer"
            }
            """.formatted(deliverableId, percentage);
    }

    private int count(String sql, Object... values) {
        return jdbc.queryForObject(sql, Integer.class, values);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor
            token(String subject) {
        return jwt().jwt(value -> value
            .subject(subject)
            .audience(List.of("vms-api")));
    }
}
