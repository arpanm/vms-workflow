package com.vms.workflow.integration;

import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_greythr",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.profiles.active=system-e2e",
    "vms.clock.fixed-instant=2026-07-29T09:30:00Z",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api"
})
@AutoConfigureMockMvc
@Transactional
class GreytHrIntegrationIT {
    private static final String CONNECTION =
        "71000000-0000-0000-0000-000000000001";
    private static final String ORGANIZATION =
        "00000000-0000-0000-0000-000000000101";
    private static final String EMPLOYEE =
        "00000000-0000-0000-0000-000000000801";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void fixture() {
        jdbc.update("""
            INSERT INTO greythr_connections
                (id, organization_id, display_name, status, adapter_mode,
                 credential_reference, created_by_subject)
            VALUES (?::uuid, ?::uuid, 'Recorded greytHR integration',
                    'DISCOVERED', 'RECORDED_FIXTURE',
                    'secret://local-test/greythr', 'test-fixture')
            """, CONNECTION, ORGANIZATION);
        jdbc.update("""
            INSERT INTO greythr_recorded_pages
                (connection_id, page_number, payload, source_updated_at)
            VALUES (?::uuid, 1, ?::jsonb, CURRENT_TIMESTAMP)
            """, CONNECTION, """
                {
                  "employees":[{
                    "providerRecordId":"employee-af-001-v1",
                    "providerEmployeeId":"GHR-AF-001",
                    "employeeNumber":"AF-001",
                    "workEmail":"employee@arrowfoundry.example"
                  }],
                  "attendance":[{
                    "providerRecordId":"attendance-af-001-2026-07-07",
                    "providerEmployeeId":"GHR-AF-001",
                    "workDate":"2026-07-07",
                    "checkInAt":"2026-07-07T03:30:00Z",
                    "checkOutAt":"2026-07-07T12:30:00Z"
                  }],
                  "leave":[{
                    "providerRecordId":"leave-af-001-2026-07-08",
                    "providerEmployeeId":"GHR-AF-001",
                    "workDate":"2026-07-08",
                    "leaveTypeCode":"CL",
                    "units":0.5
                  }]
                }
                """);
        jdbc.update("""
            INSERT INTO attendance_events
                (id, employee_id, event_type, occurred_at, work_date, source,
                 idempotency_key, recorded_by_subject)
            VALUES ('71000000-0000-0000-0000-000000000011', ?::uuid,
                    'CHECK_IN', '2026-07-07T03:45:00Z', '2026-07-07',
                    'INTERNAL_WEB', 'greythr-conflict-in', 'user-employee'),
                   ('71000000-0000-0000-0000-000000000012', ?::uuid,
                    'CHECK_OUT', '2026-07-07T12:15:00Z', '2026-07-07',
                    'INTERNAL_WEB', 'greythr-conflict-out', 'user-employee')
            """, EMPLOYEE, EMPLOYEE);
        jdbc.update("""
            INSERT INTO attendance_sessions
                (id, employee_id, work_date, check_in_event_id,
                 check_out_event_id, check_in_at, check_out_at,
                 net_minutes, status)
            VALUES ('71000000-0000-0000-0000-000000000013', ?::uuid,
                    '2026-07-07', '71000000-0000-0000-0000-000000000011',
                    '71000000-0000-0000-0000-000000000012',
                    '2026-07-07T03:45:00Z', '2026-07-07T12:15:00Z',
                    510, 'CLOSED')
            """, EMPLOYEE);
    }

    @Test
    void capabilitySyncReconcileCutoverReplayAndOutageAreEndToEnd() throws Exception {
        mvc.perform(get(
                    "/api/v1/integrations/greythr/connections/{id}/capabilities",
                    CONNECTION)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DISCOVERED"))
            .andExpect(jsonPath("$.discoveredCapabilities.length()").value(3))
            .andExpect(jsonPath("$.credentialReference").doesNotExist());

        mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/certifications",
                    CONNECTION)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "organizationId":"%s",
                      "capabilities":["EMPLOYEES","ATTENDANCE","LEAVE"]
                    }
                    """.formatted(ORGANIZATION)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.certificationId").isNotEmpty())
            .andExpect(jsonPath("$.probeEvidenceId").isNotEmpty())
            .andExpect(jsonPath("$.probeEvidenceHash")
                .value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
            .andExpect(jsonPath("$.adapterMode").value("RECORDED_FIXTURE"));
        assertEquals(1, count("""
            SELECT count(*)
            FROM greythr_capability_probe_evidence probe
            JOIN greythr_certification_evidence certification
              ON certification.provider_probe_evidence_id = probe.id
            WHERE probe.connection_id = '%s'::uuid
              AND probe.status = 'PASSED'
              AND probe.authority_classification =
                  'SIMULATED_NON_PRODUCTION'
            """.formatted(CONNECTION)));

        String syncBody = """
            {"dateFrom":"2026-07-01","dateTo":"2026-07-31"}
            """;
        JsonNode first = mapper.readTree(mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/sync-runs",
                    CONNECTION)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "greythr-july")
                .contentType(MediaType.APPLICATION_JSON)
                .content(syncBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.employeeCount").value(1))
            .andExpect(jsonPath("$.attendanceCount").value(1))
            .andExpect(jsonPath("$.leaveCount").value(1))
            .andExpect(jsonPath("$.conflictCount").value(1))
            .andExpect(jsonPath("$.stale").value(false))
            .andReturn().getResponse().getContentAsString());
        JsonNode replay = mapper.readTree(mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/sync-runs",
                    CONNECTION)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "greythr-july")
                .contentType(MediaType.APPLICATION_JSON)
                .content(syncBody))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        assertEquals(first.path("id").asText(), replay.path("id").asText());
        assertEquals(3, count("SELECT COUNT(*) FROM greythr_imported_facts"));
        assertEquals(1, count("SELECT COUNT(*) FROM greythr_sync_runs"));
        assertEquals(0, count("""
            SELECT COUNT(*) FROM greythr_fact_applications
            WHERE connection_id = '%s'::uuid
            """.formatted(CONNECTION)));
        assertEquals(0, count("""
            SELECT COUNT(*) FROM leave_balance_ledger
            WHERE employee_id = '%s'::uuid
              AND reference_type = 'GREYTHR_FACT'
            """.formatted(EMPLOYEE)));

        JsonNode reconciliation = mapper.readTree(mvc.perform(get(
                    "/api/v1/integrations/greythr/connections/{id}/reconciliations",
                    CONNECTION)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("PENDING"))
            .andReturn().getResponse().getContentAsString());
        String reconciliationId = reconciliation.get(0).path("id").asText();
        mvc.perform(post(
                    "/api/v1/integrations/greythr/reconciliations/{id}/decisions",
                    reconciliationId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "decision":"USE_GREYTHR",
                      "reason":"Provider is the certified authoritative source"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("USE_GREYTHR"));
        assertEquals(510, jdbc.queryForObject("""
            SELECT net_minutes FROM attendance_sessions
            WHERE employee_id = ?::uuid AND work_date = '2026-07-07'
              AND status = 'CLOSED'
            """, Integer.class, EMPLOYEE));

        mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/cutovers",
                    CONNECTION)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "employeeId":"%s",
                      "effectiveFrom":"2026-07-01",
                      "reason":"Reconciled parallel run approved for authority cutover"
                    }
                    """.formatted(EMPLOYEE)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.mode").value("GREYTHR_AUTHORITATIVE"));
        assertEquals(540, jdbc.queryForObject("""
            SELECT net_minutes FROM attendance_sessions
            WHERE employee_id = ?::uuid AND work_date = '2026-07-07'
              AND status = 'CLOSED'
            """, Integer.class, EMPLOYEE));
        assertEquals(-0.5, jdbc.queryForObject("""
            SELECT sum(quantity)::double precision
            FROM leave_balance_ledger
            WHERE employee_id = ?::uuid
              AND effective_date = '2026-07-08'
              AND reference_type = 'GREYTHR_FACT'
            """, Double.class, EMPLOYEE), 0.0001);
        assertEquals(2, count("""
            SELECT COUNT(*) FROM greythr_fact_applications
            WHERE connection_id = '%s'::uuid AND action = 'APPLY'
            """.formatted(CONNECTION)));

        mvc.perform(post("/api/v1/attendance/punches")
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "employeeId":"%s",
                      "eventType":"CHECK_IN",
                      "idempotencyKey":"internal-after-greythr-cutover"
                    }
                    """.formatted(EMPLOYEE)))
            .andExpect(status().isConflict());

        jdbc.update("""
            UPDATE greythr_recorded_pages
            SET response_mode = 'UNAVAILABLE'
            WHERE connection_id = ?::uuid
            """, CONNECTION);
        mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/sync-runs",
                    CONNECTION)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "greythr-outage")
                .contentType(MediaType.APPLICATION_JSON)
                .content(syncBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DEGRADED"))
            .andExpect(jsonPath("$.errorCode").value("PROVIDER_UNAVAILABLE"))
            .andExpect(jsonPath("$.lastSuccessfulSyncAt").isNotEmpty());
        assertEquals(3, count("SELECT COUNT(*) FROM greythr_imported_facts"));

        mvc.perform(get(
                    "/api/v1/integrations/greythr/connections/{id}/health",
                    CONNECTION)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DEGRADED"))
            .andExpect(jsonPath("$.lastErrorCode").value("PROVIDER_UNAVAILABLE"))
            .andExpect(jsonPath("$.lastSuccessAt").isNotEmpty());

        jdbc.update("""
            UPDATE greythr_recorded_pages
            SET response_mode = 'AVAILABLE'
            WHERE connection_id = ?::uuid
            """, CONNECTION);
        mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/sync-runs",
                    CONNECTION)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "greythr-recovery")
                .contentType(MediaType.APPLICATION_JSON)
                .content(syncBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertEquals(3, count("SELECT COUNT(*) FROM greythr_imported_facts"));
        assertEquals(2, count("""
            SELECT COUNT(*) FROM greythr_fact_applications
            WHERE connection_id = '%s'::uuid AND action = 'APPLY'
            """.formatted(CONNECTION)));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM attendance_sessions
            WHERE employee_id = '%s'::uuid AND work_date = '2026-07-07'
              AND status = 'CLOSED' AND net_minutes = 540
            """.formatted(EMPLOYEE)));

        String health = mvc.perform(get(
                    "/api/v1/integrations/greythr/connections/{id}/health",
                    CONNECTION)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.lastErrorCode").doesNotExist())
            .andReturn().getResponse().getContentAsString();
        assertFalse(health.contains("secret://"));
    }

    @Test
    void tenantAndIdempotencyBoundariesFailClosed() throws Exception {
        mvc.perform(get(
                    "/api/v1/integrations/greythr/connections/{id}/health",
                    CONNECTION)
                .with(token("user-northstar")))
            .andExpect(status().isForbidden());

        mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/certifications",
                    CONNECTION)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "organizationId":"%s",
                      "capabilities":["EMPLOYEES","ATTENDANCE","LEAVE"]
                    }
                    """.formatted(ORGANIZATION)))
            .andExpect(status().isCreated());
        mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/sync-runs",
                    CONNECTION)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "same-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"dateFrom":"2026-07-01","dateTo":"2026-07-31"}
                    """))
            .andExpect(status().isCreated());
        mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/sync-runs",
                    CONNECTION)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "same-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"dateFrom":"2026-06-01","dateTo":"2026-06-30"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        UUID nonConfigured = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO greythr_connections(
                id, organization_id, display_name, status, adapter_mode,
                created_by_subject
            ) VALUES (?, ?::uuid, ?, 'DISCOVERED', 'PROVIDER_NEUTRAL',
                      'test-fixture')
            """, nonConfigured, ORGANIZATION,
            "Nonconfigured " + nonConfigured);
        mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/certifications",
                    nonConfigured)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "organizationId":"%s",
                      "capabilities":["EMPLOYEES","ATTENDANCE","LEAVE"]
                    }
                    """.formatted(ORGANIZATION)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("GREYTHR_CAPABILITY_PROBE_FAILED"));
        assertEquals(0, count("""
            SELECT count(*) FROM greythr_capability_probe_evidence
            WHERE connection_id = '%s'::uuid
            """.formatted(nonConfigured)));
    }

    @Test
    void correctedFactsSupersedeAttendanceAndCompensateLeaveWithLineage()
        throws Exception {
        mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/certifications",
                    CONNECTION)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "organizationId":"%s",
                      "capabilities":["EMPLOYEES","ATTENDANCE","LEAVE"]
                    }
                    """.formatted(ORGANIZATION)))
            .andExpect(status().isCreated());
        mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/cutovers",
                    CONNECTION)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "employeeId":"%s",
                      "effectiveFrom":"2026-07-01",
                      "reason":"Enable attested authority before corrections"
                    }
                    """.formatted(EMPLOYEE)))
            .andExpect(status().isCreated());
        recordedCorrectionPayload(
            "2026-07-20T09:00:00Z", "2026-07-20T17:00:00Z", 0.5);
        runCorrectionSync("correction-v1");

        recordedCorrectionPayload(
            "2026-07-20T10:00:00Z", "2026-07-20T17:00:00Z", 0.75);
        jdbc.update("""
            UPDATE greythr_recorded_pages
            SET source_updated_at = source_updated_at + INTERVAL '1 minute'
            WHERE connection_id = ?::uuid
            """, CONNECTION);
        runCorrectionSync("correction-v2");

        assertEquals(5, count("""
            SELECT count(*) FROM greythr_imported_facts
            WHERE connection_id = '%s'::uuid
            """.formatted(CONNECTION)));
        assertEquals(2, count("""
            SELECT count(*) FROM greythr_imported_facts
            WHERE connection_id = '%s'::uuid
              AND supersedes_id IS NOT NULL
            """.formatted(CONNECTION)));
        assertEquals(1, count("""
            SELECT count(*) FROM greythr_employee_mappings
            WHERE connection_id = '%s'::uuid
            """.formatted(CONNECTION)));
        assertEquals(420, jdbc.queryForObject("""
            SELECT net_minutes FROM attendance_sessions
            WHERE employee_id = ?::uuid AND work_date = '2026-07-20'
              AND status = 'CLOSED'
            """, Integer.class, EMPLOYEE));
        assertEquals(1, count("""
            SELECT count(*) FROM attendance_sessions
            WHERE employee_id = '%s'::uuid AND work_date = '2026-07-20'
              AND status = 'SUPERSEDED'
            """.formatted(EMPLOYEE)));
        assertEquals(-0.75, jdbc.queryForObject("""
            SELECT sum(quantity)::double precision
            FROM leave_balance_ledger
            WHERE employee_id = ?::uuid
              AND effective_date = '2026-07-21'
              AND recorded_by_subject = 'service-greythr'
            """, Double.class, EMPLOYEE), 0.0001);
        assertEquals(6, count("""
            SELECT count(*) FROM greythr_fact_applications
            WHERE connection_id = '%s'::uuid
            """.formatted(CONNECTION)));
        assertEquals(1, count("""
            SELECT count(*) FROM greythr_fact_applications
            WHERE connection_id = '%s'::uuid AND action = 'SUPERSEDE'
            """.formatted(CONNECTION)));
        assertEquals(1, count("""
            SELECT count(*) FROM greythr_fact_applications
            WHERE connection_id = '%s'::uuid AND action = 'COMPENSATE'
            """.formatted(CONNECTION)));
    }

    private void runCorrectionSync(String idempotencyKey) throws Exception {
        mvc.perform(post(
                    "/api/v1/integrations/greythr/connections/{id}/sync-runs",
                    CONNECTION)
                .with(token("user-arrow"))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"dateFrom":"2026-07-01","dateTo":"2026-07-31"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.conflictCount").value(0));
    }

    private void recordedCorrectionPayload(
        String checkInAt,
        String checkOutAt,
        double leaveUnits
    ) {
        jdbc.update("""
            UPDATE greythr_recorded_pages
            SET payload = ?::jsonb
            WHERE connection_id = ?::uuid
            """, """
            {
              "employees":[{
                "providerRecordId":"employee-af-001-v1",
                "providerEmployeeId":"GHR-AF-001",
                "employeeNumber":"AF-001",
                "workEmail":"employee@arrowfoundry.example"
              }],
              "attendance":[{
                "providerRecordId":"attendance-af-001-corrected",
                "providerEmployeeId":"GHR-AF-001",
                "workDate":"2026-07-20",
                "checkInAt":"%s",
                "checkOutAt":"%s"
              }],
              "leave":[{
                "providerRecordId":"leave-af-001-corrected",
                "providerEmployeeId":"GHR-AF-001",
                "workDate":"2026-07-21",
                "leaveTypeCode":"CL",
                "units":%s
              }]
            }
            """.formatted(checkInAt, checkOutAt, leaveUnits), CONNECTION);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor token(
        String subject
    ) {
        return jwt().jwt(value -> value
            .subject(subject)
            .audience(List.of("vms-api")));
    }
}
