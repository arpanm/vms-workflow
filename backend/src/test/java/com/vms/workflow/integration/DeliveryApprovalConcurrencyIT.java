package com.vms.workflow.integration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.linear.webhook-secret-set={}"
})
@AutoConfigureMockMvc
class DeliveryApprovalConcurrencyIT {
    private static final String AUGUST_MONTH =
        "00000000-0000-0000-0000-000000000604";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void concurrentAllQuorumFreezesExactlyOnce() throws Exception {
        jdbc.update("""
            INSERT INTO engagement_months
                (id, engagement_id, month_start_date, state, risk_status)
            VALUES (?::uuid, '00000000-0000-0000-0000-000000000401',
                    '2026-08-01', 'ACTIVE', 'ON_TRACK')
            ON CONFLICT DO NOTHING
            """, AUGUST_MONTH);
        JsonNode created = objectMapper.readTree(mvc.perform(post("/api/v1/delivery/plans")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "engagementMonthId":"%s",
                      "title":"August concurrent plan",
                      "summary":"Concurrency-safe approval evidence",
                      "businessOutcomes":"One baseline and outbox",
                      "coordinatorSubject":"user-arrow",
                      "baselineType":"ON_TIME",
                      "quorumMode":"ALL",
                      "quorumRequired":2,
                      "approverSubjects":["user-approver","user-approver-2"],
                      "recipients":{
                        "arrowFoundry":["vendor@example.test"],
                        "relianceStakeholders":["owner@example.test"],
                        "procurementCc":["procurement@example.test"]
                      },
                      "deliverables":[{
                        "deliverableCode":"AUG-001",
                        "title":"Concurrent approval",
                        "description":"Prove serialized quorum evaluation",
                        "businessObjective":"Freeze once",
                        "projectId":"00000000-0000-0000-0000-000000000501",
                        "productOwnerSubject":"user-reliance",
                        "vendorOwnerSubject":"user-arrow",
                        "priority":"P1",
                        "targetCompletionDate":"2026-08-31",
                        "evidenceExpectations":"Two concurrent votes",
                        "dependencyNoneDeclared":true,
                        "riskAndAssumptions":"Local PostgreSQL test",
                        "deliveryCategory":"QUALITY",
                        "linkExceptionReason":"Approved provider fixture exception",
                        "criteria":[{
                          "statement":"Exactly one baseline exists",
                          "validationMethod":"Concurrent integration test",
                          "expectedResult":"One baseline and one outbox",
                          "mandatory":true
                        }],
                        "dependencies":[],
                        "assignments":[{
                          "employeeId":"00000000-0000-0000-0000-000000000801",
                          "effectiveFrom":"2026-08-01",
                          "effectiveTo":"2026-08-31"
                        }]
                      }]
                    }
                    """.formatted(AUGUST_MONTH)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        String planId = created.path("id").asText();
        mvc.perform(post("/api/v1/delivery/plans/{planId}/submit", planId)
                .with(token("user-arrow")))
            .andExpect(status().isOk());

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> approve(planId, "user-approver"));
            var second = executor.submit(() -> approve(planId, "user-approver-2"));
            assertEquals(200, first.get());
            assertEquals(200, second.get());
        }
        mvc.perform(post("/api/v1/delivery/plans/{planId}/approvals", planId)
                .with(token("user-approver"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"))
            .andExpect(status().isConflict());

        assertEquals("FROZEN", jdbc.queryForObject("""
            SELECT version.state
            FROM delivery_plans plan
            JOIN delivery_plan_versions version ON version.id = plan.current_version_id
            WHERE plan.id = ?::uuid
            """, String.class, planId));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM delivery_plan_approvals approval
            JOIN delivery_plan_versions version ON version.id = approval.plan_version_id
            WHERE version.plan_id = ?::uuid
            """, Integer.class, planId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM delivery_plan_baselines baseline
            JOIN delivery_plan_versions version ON version.id = baseline.plan_version_id
            WHERE version.plan_id = ?::uuid
            """, Integer.class, planId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM commitment_outbox outbox
            JOIN delivery_plan_versions version ON version.id = outbox.plan_version_id
            WHERE version.plan_id = ?::uuid
            """, Integer.class, planId));
    }

    private int approve(String planId, String subject) throws Exception {
        return mvc.perform(post("/api/v1/delivery/plans/{planId}/approvals", planId)
                .with(token(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"))
            .andReturn().getResponse().getStatus();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor token(
        String subject
    ) {
        return jwt().jwt(value -> value.subject(subject).audience(List.of("vms-api")));
    }
}
