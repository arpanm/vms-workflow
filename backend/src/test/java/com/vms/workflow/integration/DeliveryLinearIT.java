package com.vms.workflow.integration;

import com.vms.workflow.application.DeliveryPlanningService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Savepoint;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    "vms.security.audience=vms-api",
    "vms.linear.webhook-secret-set={\"secret://local-fixture/linear/webhook\":"
        + "{\"current\":\"test-webhook-secret\",\"previous\":[\"old-test-webhook-secret\"]},"
        + "\"secret://local-fixture/linear/webhook-b\":"
        + "{\"current\":\"test-webhook-secret-b\"}}"
})
@AutoConfigureMockMvc
@Transactional
class DeliveryLinearIT {
    private static final String JULY_MONTH = "00000000-0000-0000-0000-000000000602";
    private static final String ENGAGEMENT = "00000000-0000-0000-0000-000000000401";
    private static final String PROJECT = "00000000-0000-0000-0000-000000000501";
    private static final String EMPLOYEE = "00000000-0000-0000-0000-000000000801";
    private static final String CONNECTION = "00000000-0000-0000-0000-000000001101";
    private static final String ISSUE = "00000000-0000-0000-0000-000000001201";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DeliveryPlanningService deliveryPlanningService;

    @Test
    void reconciliationCommandIsAuthorizedIdempotentAuditedAndTerminal()
        throws Exception {
        String unavailable = """
            {
              "outcome":"UNAVAILABLE",
              "errorCode":"PROVIDER_UNAVAILABLE",
              "reason":"Bounded provider reconciliation failed"
            }
            """;
        JsonNode first = json(mvc.perform(post(
                    "/api/v1/integrations/linear/connections/{id}/reconciliations",
                    CONNECTION)
                .header("Idempotency-Key", "linear-reconciliation-it")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(unavailable))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.jobStatus").value("FAILED"))
            .andExpect(jsonPath("$.connectionStatus").value("ACTION_REQUIRED"))
            .andExpect(jsonPath("$.replay").value(false))
            .andReturn().getResponse().getContentAsString());
        JsonNode replay = json(mvc.perform(post(
                    "/api/v1/integrations/linear/connections/{id}/reconciliations",
                    CONNECTION)
                .header("Idempotency-Key", "linear-reconciliation-it")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(unavailable))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.replay").value(true))
            .andReturn().getResponse().getContentAsString());
        assertEquals(first.path("jobId").asText(), replay.path("jobId").asText());
        assertEquals(
            first.path("commandChecksum").asText(),
            replay.path("commandChecksum").asText());
        assertEquals(
            first.path("correlationId").asText(),
            replay.path("correlationId").asText());
        assertEquals(
            first.path("causationId").asText(),
            replay.path("causationId").asText());
        mvc.perform(get(
                    "/api/v1/integrations/linear/health")
                .queryParam("engagementId", ENGAGEMENT)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTION_REQUIRED"))
            .andExpect(jsonPath("$.lastError").value("PROVIDER_UNAVAILABLE"))
            .andExpect(jsonPath("$.lastReconciledAt").isNotEmpty());

        mvc.perform(post(
                    "/api/v1/integrations/linear/connections/{id}/reconciliations",
                    CONNECTION)
                .header("Idempotency-Key", "linear-reconciliation-it")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "outcome":"UNAVAILABLE",
                      "errorCode":"PROVIDER_TIMEOUT",
                      "reason":"Changed command"
                    }
                    """))
            .andExpect(status().isConflict());
        mvc.perform(post(
                    "/api/v1/integrations/linear/connections/{id}/reconciliations",
                    CONNECTION)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(unavailable))
            .andExpect(status().isBadRequest());
        mvc.perform(post(
                    "/api/v1/integrations/linear/connections/{id}/reconciliations",
                    CONNECTION)
                .header("Idempotency-Key", "x".repeat(161))
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(unavailable))
            .andExpect(status().isBadRequest());
        mvc.perform(post(
                    "/api/v1/integrations/linear/connections/{id}/reconciliations",
                    CONNECTION)
                .header("Idempotency-Key", "linear-reconciliation-unauthorized")
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(unavailable))
            .andExpect(status().isNotFound());

        JsonNode recovery = json(mvc.perform(post(
                    "/api/v1/integrations/linear/connections/{id}/reconciliations",
                    CONNECTION)
                .header("Idempotency-Key", "linear-reconciliation-recovery-it")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "outcome":"AVAILABLE",
                      "errorCode":null,
                      "reason":"Provider reconciliation recovered"
                    }
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.jobStatus").value("SUCCEEDED"))
            .andExpect(jsonPath("$.connectionStatus").value("CONNECTED"))
            .andExpect(jsonPath("$.replay").value(false))
            .andReturn().getResponse().getContentAsString());
        JsonNode lateReplay = json(mvc.perform(post(
                    "/api/v1/integrations/linear/connections/{id}/reconciliations",
                    CONNECTION)
                .header("Idempotency-Key", "linear-reconciliation-it")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(unavailable))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.replay").value(true))
            .andReturn().getResponse().getContentAsString());
        for (String field : List.of(
            "jobId", "connectionId", "jobStatus", "connectionStatus",
            "staleIssueCount", "recordedAt", "errorCode", "commandChecksum",
            "correlationId", "causationId"
        )) {
            assertEquals(first.get(field), lateReplay.get(field), field);
        }
        assertFalse(recovery.path("jobId").asText().equals(
            lateReplay.path("jobId").asText()));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM linear_reconciliation_commands
            WHERE connection_id = ?::uuid
              AND actor_subject = 'user-arrow'
              AND command_checksum ~ '^[0-9a-f]{64}$'
              AND correlation_id IS NOT NULL
              AND causation_id IS NOT NULL
            """, Integer.class, CONNECTION));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM linear_sync_jobs
            WHERE connection_id = ?::uuid
              AND status IN ('FAILED', 'SUCCEEDED')
              AND completed_at IS NOT NULL
            """, Integer.class, CONNECTION));
        assertSqlRejected("""
            UPDATE linear_reconciliation_commands
            SET reason = 'tampered'
            WHERE connection_id = '%s'::uuid
            """.formatted(CONNECTION));
        assertSqlRejected("""
            UPDATE linear_sync_jobs
            SET status = 'RUNNING', completed_at = NULL
            WHERE id = '%s'::uuid
            """.formatted(first.path("jobId").asText()));
        String invalidJob = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO linear_sync_jobs
                (id, connection_id, job_type, status, attempt_count,
                 last_error_code, completed_at)
            VALUES (?::uuid, ?::uuid, 'NIGHTLY_RECONCILIATION',
                    'FAILED', 1, 'PROVIDER_UNAVAILABLE', CURRENT_TIMESTAMP)
            """, invalidJob, CONNECTION);
        assertSqlRejected("""
            INSERT INTO linear_reconciliation_commands
                (id, connection_id, sync_job_id, idempotency_key,
                 command_checksum, outcome, recorded_connection_status,
                 recorded_stale_issue_count, reason, actor_subject,
                 correlation_id, causation_id)
            VALUES (
                gen_random_uuid(), '%s'::uuid, '%s'::uuid,
                'invalid-terminal-command', repeat('a', 64), 'AVAILABLE',
                'CONNECTED', 0, 'Must fail terminal trigger', 'user-arrow',
                gen_random_uuid(), gen_random_uuid())
            """.formatted(CONNECTION, invalidJob));
        assertEquals(1, jdbc.update("""
            UPDATE linear_sync_jobs
            SET status = 'DEAD_LETTER', completed_at = CURRENT_TIMESTAMP
            WHERE id = ?::uuid
            """, invalidJob));
    }

    @Test
    void reconciliationOpenApiDeclaresBoundedHeaderAndFailureContracts()
        throws Exception {
        JsonNode operation = json(mvc.perform(get("/v3/api-docs")
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString())
            .path("paths")
            .path("/api/v1/integrations/linear/connections/{connectionId}/reconciliations")
            .path("post");
        JsonNode header = null;
        for (JsonNode parameter : operation.path("parameters")) {
            if ("Idempotency-Key".equals(parameter.path("name").asText())) {
                header = parameter;
                break;
            }
        }
        assertTrue(header != null, "Idempotency-Key must be documented");
        assertTrue(header.path("required").asBoolean());
        assertEquals(160, header.path("schema").path("maxLength").asInt());
        for (String response : List.of("201", "400", "404", "409")) {
            assertTrue(operation.path("responses").has(response), response);
        }
    }

    @Test
    void completePlanSubmitsFreezesQueuesCommitmentAndRevisesByClone() throws Exception {
        JsonNode created = createPlan("Approved local provider exception");
        String planId = created.path("id").asText();

        JsonNode submitted = json(mvc.perform(post(
                    "/api/v1/delivery/plans/{planId}/submit", planId)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("PENDING_APPROVAL"))
            .andExpect(jsonPath("$.checksum").isNotEmpty())
            .andReturn().getResponse().getContentAsString());
        String checksum = submitted.path("checksum").asText();
        String versionId = submitted.path("currentVersionId").asText();
        String deliverableVersionId = submitted.path("deliverables").get(0)
            .path("deliverableVersionId").asText();
        assertSqlRejected("""
            UPDATE delivery_plan_versions SET title = 'tampered'
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_deliverable_versions SET title = 'tampered'
            WHERE id = '%s'::uuid
            """.formatted(deliverableVersionId));
        assertSqlRejected("""
            UPDATE delivery_acceptance_criteria SET statement = 'tampered'
            WHERE deliverable_version_id = '%s'::uuid
            """.formatted(deliverableVersionId));
        assertSqlRejected("""
            UPDATE delivery_employee_assignments
            SET exception_reason = 'tampered'
            WHERE deliverable_version_id = '%s'::uuid
            """.formatted(deliverableVersionId));
        assertSqlRejected("""
            UPDATE delivery_recipient_snapshots
            SET procurement_cc = '[]'::jsonb
            WHERE plan_version_id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_plan_approvers SET authority_snapshot = '{}'::jsonb
            WHERE plan_version_id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            INSERT INTO delivery_dependencies
                (id, deliverable_version_id, dependency_type, description,
                 owner_subject, target_resolution_date, blocking)
            VALUES (gen_random_uuid(), '%s'::uuid, 'EXTERNAL', 'tampered',
                    'user-arrow', '2026-07-31', false)
            """.formatted(deliverableVersionId));
        assertSqlRejected("""
            INSERT INTO linear_issue_links
                (id, deliverable_version_id, connection_id, linear_issue_uuid,
                 identifier, issue_url, created_by_subject)
            VALUES (gen_random_uuid(), '%s'::uuid, '%s'::uuid, gen_random_uuid(),
                    'TEAM-999', 'https://linear.app/test/issue/TEAM-999', 'sql')
            """.formatted(deliverableVersionId, CONNECTION));
        assertTrue(jdbc.queryForObject("""
            SELECT authority_snapshot
                @> '{"eligible":true,"policyVersion":"F03-SOD-V1"}'::jsonb
            FROM delivery_plan_approvers
            WHERE plan_version_id = ?::uuid
              AND approver_subject = 'user-approver'
            """, Boolean.class, versionId));

        JsonNode frozen = json(mvc.perform(post(
                    "/api/v1/delivery/plans/{planId}/approvals", planId)
                .with(token("user-approver"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"decision":"APPROVE","comment":"Approved exact baseline"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("FROZEN"))
            .andExpect(jsonPath("$.baselineId").isNotEmpty())
            .andExpect(jsonPath("$.commitmentStatus").value("PENDING"))
            .andReturn().getResponse().getContentAsString());
        assertEquals(checksum, frozen.path("approvals").get(0)
            .path("signedChecksum").asText());
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

        mvc.perform(post("/api/v1/delivery/plans/{planId}/revisions", planId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Scope correction","impact":"No commercial impact"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.state").value("DRAFT"))
            .andExpect(jsonPath("$.priorVersionId")
                .value(frozen.path("currentVersionId").asText()))
            .andExpect(jsonPath("$.deliverables[0].deliverableCode").value("DLV-001"));
        assertEquals("FROZEN", jdbc.queryForObject("""
            SELECT state FROM delivery_plan_versions WHERE id = ?::uuid
            """, String.class, frozen.path("currentVersionId").asText()));

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            UPDATE delivery_plan_baselines SET checksum = repeat('0', 64)
            """));
    }

    @Test
    void incompletePlanReportsBlockersAndCannotSubmit() throws Exception {
        JsonNode created = createPlan(null);
        String planId = created.path("id").asText();
        assertTrue(created.path("completenessBlockers").toString()
            .contains("LINEAR_LINK_OR_EXCEPTION_REQUIRED"));

        mvc.perform(post("/api/v1/delivery/plans/{planId}/submit", planId)
                .with(token("user-arrow")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail")
                .value(org.hamcrest.Matchers.containsString(
                    "LINEAR_LINK_OR_EXCEPTION_REQUIRED")));
        assertEquals("DRAFT", jdbc.queryForObject("""
            SELECT version.state
            FROM delivery_plans plan
            JOIN delivery_plan_versions version ON version.id = plan.current_version_id
            WHERE plan.id = ?::uuid
            """, String.class, planId));
    }

    @Test
    void signedWebhookIsDurableDeduplicatedAndDoneOnlyUpdatesExecutionProjection()
        throws Exception {
        JsonNode created = createPlan(null);
        String planId = created.path("id").asText();
        String deliverableVersionId = created.path("deliverables").get(0)
            .path("deliverableVersionId").asText();
        JsonNode link = json(mvc.perform(post("/api/v1/integrations/linear/links")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "deliverableVersionId":"%s",
                      "connectionId":"%s",
                      "issueUuid":"%s"
                    }
                    """.formatted(deliverableVersionId, CONNECTION, ISSUE)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.currentNormalizedState").value("UNSTARTED"))
            .andReturn().getResponse().getContentAsString());
        String linkId = link.path("id").asText();

        mvc.perform(post("/api/v1/delivery/plans/{planId}/submit", planId)
                .with(token("user-arrow")))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/delivery/plans/{planId}/approvals", planId)
                .with(token("user-approver"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"decision":"APPROVE"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("FROZEN"));

        long timestamp = Instant.now().toEpochMilli();
        String deliveryId = UUID.randomUUID().toString();
        byte[] rawBody = ("""
            {"type":"Issue","action":"update","organizationId":"linear-test-organization",\
"connectionId":"%s","webhookTimestamp":%d,"data":{"id":"%s","identifier":"TEAM-123",\
"url":"https://linear.app/test/issue/TEAM-123","title":"Recorded issue",\
"updatedAt":"%s","state":{"id":"state-done","name":"Done","type":"completed"}}}
            """.formatted(CONNECTION, timestamp, ISSUE, Instant.now())).getBytes(
                StandardCharsets.UTF_8);
        String signature = signature(rawBody);

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/integrations/linear/webhook/{connectionId}", CONNECTION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Linear-Signature", signature)
                    .header("Linear-Timestamp", Long.toString(timestamp))
                    .header("Linear-Delivery", deliveryId)
                    .content(rawBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(attempt == 1));
        }
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM linear_webhook_deliveries
            """, Integer.class));
        assertEquals("QUEUED", jdbc.queryForObject("""
            SELECT status FROM linear_webhook_queue
            """, String.class));

        mvc.perform(post("/api/v1/integrations/linear/deliveries/{deliveryId}/process",
                    deliveryId)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PROCESSED"));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(
                        "/api/v1/integrations/linear/months/{monthId}/snapshots",
                        JULY_MONTH)
                    .with(token("user-arrow")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].snapshotType").value("MONTH_END"))
                .andExpect(jsonPath("$[0].status").value("CAPTURED"))
                .andExpect(jsonPath("$[0].normalizedState").value("COMPLETED"))
                .andExpect(jsonPath("$[0].confidence")
                    .value("CURRENT_STATE_ONLY"));
        }
        mvc.perform(get("/api/v1/integrations/linear/links/{linkId}/current", linkId)
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.normalizedState").value("COMPLETED"))
            .andExpect(jsonPath("$.executionProjection").value("COMPLETED"));
        mvc.perform(get("/api/v1/integrations/linear/links/{linkId}/snapshots", linkId)
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].snapshotType").value("PLAN_TIME"))
            .andExpect(jsonPath("$[0].normalizedState").value("UNSTARTED"));
        assertEquals("UNKNOWN", jdbc.queryForObject("""
            SELECT execution_projection
            FROM delivery_deliverable_versions
            WHERE id = ?::uuid
            """, String.class, deliverableVersionId));
        assertEquals("COMPLETED", jdbc.queryForObject("""
            SELECT execution_projection
            FROM delivery_execution_projections
            WHERE deliverable_version_id = ?::uuid
            """, String.class, deliverableVersionId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM linear_issue_snapshots
            WHERE issue_link_id = ?::uuid
              AND plan_version_id = ?::uuid
              AND snapshot_type = 'MONTH_END'
            """, Integer.class, linkId, created.path("currentVersionId").asText()));
        assertTrue(jdbc.queryForObject("""
            SELECT COUNT(*) >= 2
            FROM delivery_execution_projection_events
            WHERE deliverable_version_id = ?::uuid
            """, Boolean.class, deliverableVersionId));

        assertEquals("FROZEN", jdbc.queryForObject("""
            SELECT version.state
            FROM delivery_plans plan
            JOIN delivery_plan_versions version ON version.id = plan.current_version_id
            WHERE plan.id = ?::uuid
            """, String.class, planId));
        assertEquals("ACTIVE", jdbc.queryForObject("""
            SELECT state FROM engagement_months WHERE id = ?::uuid
            """, String.class, JULY_MONTH));
        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*) FROM delivery_audit_events
            WHERE facts @> '{"businessAcceptanceChanged":true}'::jsonb
            """, Integer.class));
    }

    @Test
    void invalidReplayOrSignatureMutatesNothing() throws Exception {
        long stale = Instant.now().minusSeconds(120).toEpochMilli();
        byte[] rawBody = ("""
            {"type":"Issue","action":"update","organizationId":"linear-test-organization",\
"connectionId":"%s","webhookTimestamp":%d,"data":{}}
            """.formatted(CONNECTION, stale)).getBytes(StandardCharsets.UTF_8);

        mvc.perform(post("/api/v1/integrations/linear/webhook/{connectionId}", CONNECTION)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Linear-Signature", signature(rawBody))
                .header("Linear-Timestamp", Long.toString(stale))
                .header("Linear-Delivery", UUID.randomUUID())
                .content(rawBody))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/integrations/linear/webhook/{connectionId}", CONNECTION)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Linear-Signature", "00".repeat(32))
                .header("Linear-Timestamp", Long.toString(Instant.now().toEpochMilli()))
                .header("Linear-Delivery", UUID.randomUUID())
                .content(rawBody))
            .andExpect(status().isBadRequest());
        byte[] malformed = "{not-json".getBytes(StandardCharsets.UTF_8);
        long now = Instant.now().toEpochMilli();
        mvc.perform(post("/api/v1/integrations/linear/webhook/{connectionId}", CONNECTION)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Linear-Signature", signature(malformed))
                .header("Linear-Timestamp", Long.toString(now))
                .header("Linear-Delivery", UUID.randomUUID())
                .content(malformed))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/integrations/linear/webhook/{connectionId}", CONNECTION)
                .contentType(MediaType.TEXT_PLAIN)
                .header("Linear-Signature", "00".repeat(32))
                .header("Linear-Timestamp", Long.toString(now))
                .header("Linear-Delivery", UUID.randomUUID())
                .content("{}"))
            .andExpect(status().isUnsupportedMediaType());
        byte[] oversized = new byte[262_145];
        mvc.perform(post("/api/v1/integrations/linear/webhook/{connectionId}", CONNECTION)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Linear-Signature", "00".repeat(32))
                .header("Linear-Timestamp", Long.toString(now))
                .header("Linear-Delivery", UUID.randomUUID())
                .content(oversized))
            .andExpect(status().isBadRequest());
        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*) FROM linear_webhook_deliveries
            """, Integer.class));
    }

    @Test
    void connectionSecretsAreIsolatedRotateAndDeliveryCollisionFailsClosed()
        throws Exception {
        long timestamp = Instant.now().toEpochMilli();
        byte[] bodyA = webhookBody(
            CONNECTION, "linear-test-organization", ISSUE, timestamp,
            Instant.now(), "completed");
        String deliveryA = UUID.randomUUID().toString();
        mvc.perform(webhookRequest(CONNECTION, deliveryA, timestamp, bodyA,
                "old-test-webhook-secret"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(false));
        assertArrayEquals(bodyA, jdbc.queryForObject("""
            SELECT raw_body FROM linear_webhook_deliveries
            WHERE delivery_id = ?::uuid
            """, byte[].class, deliveryA));

        String connectionB = "00000000-0000-0000-0000-000000001102";
        String issueB = "00000000-0000-0000-0000-000000001202";
        byte[] bodyB = webhookBody(
            connectionB, "linear-test-organization-b", issueB, timestamp,
            Instant.now(), "completed");
        mvc.perform(webhookRequest(connectionB, UUID.randomUUID().toString(),
                timestamp, bodyB, "test-webhook-secret"))
            .andExpect(status().isBadRequest());
        mvc.perform(webhookRequest(connectionB, UUID.randomUUID().toString(),
                timestamp, bodyB, "test-webhook-secret-b"))
            .andExpect(status().isOk());

        byte[] changed = webhookBody(
            CONNECTION, "linear-test-organization", ISSUE, timestamp,
            Instant.now().plusSeconds(1), "started");
        mvc.perform(webhookRequest(CONNECTION, deliveryA, timestamp, changed,
                "test-webhook-secret"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(
                "Webhook delivery identifier was reused with different content."));
    }

    @Test
    void scopedAuthorityRejectsOwnerConflictAndDatabaseRejectsUnsnapshottedVote()
        throws Exception {
        JsonNode created = createPlan("Approved local provider exception");
        String planId = created.path("id").asText();
        String versionId = created.path("currentVersionId").asText();
        jdbc.update("""
            UPDATE delivery_plan_approvers
            SET approver_subject = 'user-reliance'
            WHERE plan_version_id = ?::uuid
            """, versionId);
        mvc.perform(post("/api/v1/delivery/plans/{planId}/submit", planId)
                .with(token("user-arrow")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value(
                org.hamcrest.Matchers.containsString(
                    "APPROVER_SEPARATION_OF_DUTIES_CONFLICT")));
        assertSqlRejected("""
            INSERT INTO delivery_plan_approvals
                (id, plan_version_id, approver_subject, decision,
                 signed_checksum, authority_snapshot)
            VALUES (gen_random_uuid(), '%s'::uuid, 'user-reliance', 'APPROVE',
                    repeat('0', 64), '{"eligible":true}'::jsonb)
            """.formatted(versionId));
    }

    @Test
    void checksumCoversDependenciesApproversRecipientsLinksAndSnapshots()
        throws Exception {
        JsonNode created = createPlan("Approved local provider exception");
        UUID versionId = UUID.fromString(created.path("currentVersionId").asText());
        String deliverableVersionId = created.path("deliverables").get(0)
            .path("deliverableVersionId").asText();
        String initial = ReflectionTestUtils.invokeMethod(
            deliveryPlanningService, "checksum", versionId);
        jdbc.update("""
            INSERT INTO delivery_dependencies
                (id, deliverable_version_id, dependency_type, description,
                 owner_subject, target_resolution_date, blocking)
            VALUES (gen_random_uuid(), ?::uuid, 'EXTERNAL', 'Provider readiness',
                    'user-arrow', '2026-07-20', true)
            """, deliverableVersionId);
        String dependency = ReflectionTestUtils.invokeMethod(
            deliveryPlanningService, "checksum", versionId);
        assertFalse(initial.equals(dependency));
        jdbc.update("""
            INSERT INTO delivery_plan_approvers
                (plan_version_id, approver_subject, authority_snapshot)
            VALUES (?::uuid, 'user-approver-2', '{}')
            """, versionId);
        String approver = ReflectionTestUtils.invokeMethod(
            deliveryPlanningService, "checksum", versionId);
        assertFalse(dependency.equals(approver));
        jdbc.update("""
            UPDATE delivery_recipient_snapshots
            SET procurement_cc = procurement_cc || '["second@example.test"]'::jsonb
            WHERE plan_version_id = ?::uuid
            """, versionId);
        String recipient = ReflectionTestUtils.invokeMethod(
            deliveryPlanningService, "checksum", versionId);
        assertFalse(approver.equals(recipient));
        mvc.perform(post("/api/v1/integrations/linear/links")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"deliverableVersionId":"%s","connectionId":"%s","issueUuid":"%s"}
                    """.formatted(deliverableVersionId, CONNECTION, ISSUE)))
            .andExpect(status().isCreated());
        String link = ReflectionTestUtils.invokeMethod(
            deliveryPlanningService, "checksum", versionId);
        assertFalse(recipient.equals(link));
        ReflectionTestUtils.invokeMethod(
            deliveryPlanningService, "capturePlanSnapshots", versionId);
        String snapshot = ReflectionTestUtils.invokeMethod(
            deliveryPlanningService, "checksum", versionId);
        assertFalse(link.equals(snapshot));
    }

    @Test
    void linkUsesServerMetadataValidatesTeamAndRequiresMultiLinkRationale()
        throws Exception {
        JsonNode created = createPlan(null);
        String deliverableVersionId = created.path("deliverables").get(0)
            .path("deliverableVersionId").asText();
        mvc.perform(post("/api/v1/integrations/linear/links")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "deliverableVersionId":"%s","connectionId":"%s",
                      "issueUuid":"%s","identifier":"FORGED-999",
                      "url":"https://evil.example/forged","title":"Forged"
                    }
                    """.formatted(deliverableVersionId, CONNECTION, ISSUE)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.identifier").value("TEAM-123"))
            .andExpect(jsonPath("$.url").value(
                "https://linear.app/test/issue/TEAM-123"));
        String secondIssue = "00000000-0000-0000-0000-000000001298";
        insertRecordedIssue(secondIssue, "linear-team-a", "TEAM-124");
        mvc.perform(post("/api/v1/integrations/linear/links")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"deliverableVersionId":"%s","connectionId":"%s","issueUuid":"%s"}
                    """.formatted(deliverableVersionId, CONNECTION, secondIssue)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.detail").value("MULTI_LINK_RATIONALE_REQUIRED"));
        mvc.perform(post("/api/v1/integrations/linear/links")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"deliverableVersionId":"%s","connectionId":"%s",
                     "issueUuid":"%s","rationale":"Split implementation and rollout"}
                    """.formatted(deliverableVersionId, CONNECTION, secondIssue)))
            .andExpect(status().isCreated());

        String wrongTeamIssue = "00000000-0000-0000-0000-000000001299";
        insertRecordedIssue(wrongTeamIssue, "wrong-team", "TEAM-125");
        mvc.perform(post("/api/v1/integrations/linear/links")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"deliverableVersionId":"%s","connectionId":"%s",
                     "issueUuid":"%s","rationale":"Third link rationale"}
                    """.formatted(deliverableVersionId, CONNECTION, wrongTeamIssue)))
            .andExpect(status().isNotFound());
    }

    @Test
    void outOfOrderProviderEventIsRecordedAuditedAndDoesNotRegressProjection()
        throws Exception {
        JsonNode created = createPlan(null);
        String deliverableVersionId = created.path("deliverables").get(0)
            .path("deliverableVersionId").asText();
        JsonNode link = json(mvc.perform(post("/api/v1/integrations/linear/links")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"deliverableVersionId":"%s","connectionId":"%s","issueUuid":"%s"}
                    """.formatted(deliverableVersionId, CONNECTION, ISSUE)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        Instant newest = Instant.now();
        sendAndProcessWebhook(
            UUID.randomUUID().toString(), newest, "completed");
        sendAndProcessWebhook(
            UUID.randomUUID().toString(), newest.minusSeconds(300), "started");
        mvc.perform(get("/api/v1/integrations/linear/links/{linkId}/current",
                    link.path("id").asText())
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.normalizedState").value("COMPLETED"))
            .andExpect(jsonPath("$.executionProjection").value("COMPLETED"));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM linear_issue_events
            WHERE processing_disposition = 'STALE_IGNORED'
            """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM linear_webhook_audit_events
            WHERE event_type = 'OUT_OF_ORDER_EVENT_IGNORED'
            """, Integer.class));
    }

    @Test
    void stableLineageAndExplicitVersionTransitionsAreDatabaseEnforced()
        throws Exception {
        JsonNode created = createPlan("Approved local provider exception");
        String planId = created.path("id").asText();
        String versionId = created.path("currentVersionId").asText();
        String stableDeliverableId = created.path("deliverables").get(0)
            .path("id").asText();

        assertSqlRejected("""
            UPDATE delivery_deliverables SET deliverable_code = 'TAMPERED'
            WHERE id = '%s'::uuid
            """.formatted(stableDeliverableId));
        assertSqlRejected("""
            UPDATE delivery_deliverables SET plan_id = gen_random_uuid()
            WHERE id = '%s'::uuid
            """.formatted(stableDeliverableId));
        assertSqlRejected("""
            DELETE FROM delivery_deliverables WHERE id = '%s'::uuid
            """.formatted(stableDeliverableId));
        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET state = 'FROZEN', frozen_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_plan_versions SET state = 'SUPERSEDED'
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_plan_versions SET checksum = repeat('0', 64)
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_plan_versions SET version = 99
            WHERE id = '%s'::uuid
            """.formatted(versionId));

        mvc.perform(post("/api/v1/delivery/plans/{planId}/submit", planId)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("PENDING_APPROVAL"));

        for (String invalidState : List.of(
            "DRAFT", "APPROVED", "SUPERSEDED", "CANCELLED")) {
            assertSqlRejected("""
                UPDATE delivery_plan_versions SET state = '%s'
                WHERE id = '%s'::uuid
                """.formatted(invalidState, versionId));
        }
        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET state = 'FROZEN', title = 'tampered',
                frozen_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET state = 'FROZEN', plan_id = gen_random_uuid(),
                frozen_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET state = 'FROZEN', version = 99,
                frozen_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET state = 'FROZEN', frozen_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 2
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET state = 'FROZEN', frozen_at = submitted_at - INTERVAL '1 second',
                optimistic_version = optimistic_version + 1
            WHERE id = '%s'::uuid
            """.formatted(versionId));

        mvc.perform(post("/api/v1/delivery/plans/{planId}/approvals", planId)
                .with(token("user-approver"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("FROZEN"));

        for (String invalidState : List.of(
            "DRAFT", "REJECTED", "SUPERSEDED", "CANCELLED")) {
            assertSqlRejected("""
                UPDATE delivery_plan_versions SET state = '%s'
                WHERE id = '%s'::uuid
                """.formatted(invalidState, versionId));
        }
        assertSqlRejected("""
            UPDATE delivery_plan_versions SET title = 'tampered'
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_plan_versions SET plan_id = gen_random_uuid()
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_plan_versions SET version = 99
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET optimistic_version = optimistic_version + 1
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET frozen_at = frozen_at + INTERVAL '1 second'
            WHERE id = '%s'::uuid
            """.formatted(versionId));
        assertSqlRejected("""
            DELETE FROM delivery_deliverables WHERE id = '%s'::uuid
            """.formatted(stableDeliverableId));

        mvc.perform(post("/api/v1/delivery/plans/{planId}/revisions", planId)
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"Controlled revision","impact":"Lineage preserved"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("DRAFT"))
            .andExpect(jsonPath("$.priorVersionId").value(versionId));
        assertEquals("FROZEN", jdbc.queryForObject("""
            SELECT state FROM delivery_plan_versions WHERE id = ?::uuid
            """, String.class, versionId));
    }

    @Test
    void directFreezeRequiresQuorumBaselineOutboxAndAttributableAudit()
        throws Exception {
        JsonNode created = createPlan("Approved local provider exception");
        String planId = created.path("id").asText();
        String versionId = created.path("currentVersionId").asText();

        mvc.perform(post("/api/v1/delivery/plans/{planId}/submit", planId)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("PENDING_APPROVAL"));

        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET state = 'FROZEN', frozen_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = '%s'::uuid
            """.formatted(versionId));

        String checksum = jdbc.queryForObject("""
            SELECT checksum FROM delivery_plan_versions WHERE id = ?::uuid
            """, String.class, versionId);
        String authoritySnapshot = jdbc.queryForObject("""
            SELECT authority_snapshot::text
            FROM delivery_plan_approvers
            WHERE plan_version_id = ?::uuid AND approver_subject = 'user-approver'
            """, String.class, versionId);
        jdbc.update("""
            INSERT INTO delivery_plan_approvals
                (id, plan_version_id, approver_subject, decision, signed_checksum,
                 authority_snapshot)
            VALUES (?::uuid, ?::uuid, 'user-approver', 'APPROVE', ?, ?::jsonb)
            """, UUID.randomUUID().toString(), versionId, checksum, authoritySnapshot);

        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET state = 'FROZEN', frozen_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = '%s'::uuid
            """.formatted(versionId));

        String baselineId = UUID.randomUUID().toString();
        Integer deliverableCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM delivery_deliverable_versions
            WHERE plan_version_id = ?::uuid
            """, Integer.class, versionId);
        jdbc.update("""
            INSERT INTO delivery_plan_baselines
                (id, plan_version_id, checksum, deliverable_count)
            VALUES (?::uuid, ?::uuid, ?, ?)
            """, baselineId, versionId, checksum, deliverableCount);

        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET state = 'FROZEN', frozen_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = '%s'::uuid
            """.formatted(versionId));

        String outboxId = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO commitment_outbox
                (id, plan_version_id, baseline_id, message_type, idempotency_key,
                 recipient_snapshot, subject_text, plain_text, html_text,
                 archive_reference)
            VALUES (?::uuid, ?::uuid, ?::uuid, 'INITIAL', ?,
                    '{}'::jsonb, 'subject', 'plain', 'html', ?)
            """, outboxId, versionId, baselineId, "commitment:" + versionId,
            "db://commitment-outbox/" + outboxId);

        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET state = 'FROZEN', frozen_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = '%s'::uuid
            """.formatted(versionId));

        jdbc.update("""
            INSERT INTO delivery_audit_events
                (id, plan_id, plan_version_id, event_type, actor_subject, facts)
            VALUES (?::uuid, ?::uuid, ?::uuid, 'PLAN_FROZEN',
                    'user-approver', '{}'::jsonb)
            """, UUID.randomUUID().toString(), planId, versionId);
        assertEquals(1, jdbc.update("""
            UPDATE delivery_plan_versions
            SET state = 'FROZEN', frozen_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = ?::uuid
            """, versionId));
        assertEquals("FROZEN", jdbc.queryForObject("""
            SELECT state FROM delivery_plan_versions WHERE id = ?::uuid
            """, String.class, versionId));
    }

    @Test
    void rejectionRequiresSignedVoteAndMakesVersionOwnedEvidenceImmutable()
        throws Exception {
        JsonNode created = createPlan("Approved local provider exception");
        String planId = created.path("id").asText();
        String versionId = created.path("currentVersionId").asText();
        String deliverableVersionId = created.path("deliverables").get(0)
            .path("deliverableVersionId").asText();
        String criterionId = created.path("deliverables").get(0)
            .path("criteria").get(0).path("id").asText();

        mvc.perform(post("/api/v1/delivery/plans/{planId}/submit", planId)
                .with(token("user-arrow")))
            .andExpect(status().isOk());

        assertSqlRejected("""
            UPDATE delivery_plan_versions
            SET state = 'REJECTED', optimistic_version = optimistic_version + 1
            WHERE id = '%s'::uuid
            """.formatted(versionId));

        mvc.perform(post("/api/v1/delivery/plans/{planId}/approvals", planId)
                .with(token("user-approver"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"decision":"REJECT","comment":"Signed rejection evidence"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("REJECTED"));

        assertSqlRejected("""
            UPDATE delivery_deliverable_versions SET title = 'tampered'
            WHERE id = '%s'::uuid
            """.formatted(deliverableVersionId));
        assertSqlRejected("""
            DELETE FROM delivery_acceptance_criteria
            WHERE id = '%s'::uuid
            """.formatted(criterionId));
        assertSqlRejected("""
            INSERT INTO delivery_dependencies
                (id, deliverable_version_id, dependency_type, description,
                 owner_subject, target_resolution_date, blocking)
            VALUES (gen_random_uuid(), '%s'::uuid, 'EXTERNAL', 'tampered',
                    'user-arrow', '2026-07-31', false)
            """.formatted(deliverableVersionId));
    }

    @Test
    void deliverableVersionCannotCrossPlanOnInsertOrMove() throws Exception {
        JsonNode created = createPlan("Approved local provider exception");
        String sourceVersionId = created.path("currentVersionId").asText();
        String sourceStableId = created.path("deliverables").get(0).path("id").asText();
        String sourceDeliverableVersionId = created.path("deliverables").get(0)
            .path("deliverableVersionId").asText();

        String monthId = UUID.randomUUID().toString();
        String secondPlanId = UUID.randomUUID().toString();
        String secondVersionId = UUID.randomUUID().toString();
        String secondStableId = UUID.randomUUID().toString();
        String validCloneId = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO engagement_months
                (id, engagement_id, month_start_date, state, risk_status)
            VALUES (?::uuid, ?::uuid, '2035-01-01', 'ACTIVE', 'ON_TRACK')
            """, monthId, ENGAGEMENT);
        jdbc.update("""
            INSERT INTO delivery_plans
                (id, engagement_month_id, created_by_subject)
            VALUES (?::uuid, ?::uuid, 'user-arrow')
            """, secondPlanId, monthId);
        jdbc.update("""
            INSERT INTO delivery_plan_versions
                (id, plan_id, version, state, title, summary, business_outcomes,
                 coordinator_subject, baseline_type, quorum_mode, quorum_required,
                 created_by_subject)
            VALUES (?::uuid, ?::uuid, 1, 'DRAFT', 'Second plan', 'Summary',
                    'Outcome', 'user-arrow', 'ON_TIME', 'ANY_ONE', 1,
                    'user-arrow')
            """, secondVersionId, secondPlanId);
        jdbc.update("""
            UPDATE delivery_plans SET current_version_id = ?::uuid
            WHERE id = ?::uuid
            """, secondVersionId, secondPlanId);
        jdbc.update("""
            INSERT INTO delivery_deliverables
                (id, plan_id, deliverable_code)
            VALUES (?::uuid, ?::uuid, 'SECOND-001')
            """, secondStableId, secondPlanId);

        assertSqlRejected("""
            INSERT INTO delivery_deliverable_versions
                (id, deliverable_id, plan_version_id, project_id, title,
                 description, business_objective, product_owner_subject,
                 vendor_owner_subject, priority, target_completion_date,
                 evidence_expectations, dependency_none_declared,
                 risk_and_assumptions, delivery_category, link_exception_reason)
            SELECT gen_random_uuid(), '%s'::uuid, '%s'::uuid, project_id, title,
                   description, business_objective, product_owner_subject,
                   vendor_owner_subject, priority, target_completion_date,
                   evidence_expectations, dependency_none_declared,
                   risk_and_assumptions, delivery_category, link_exception_reason
            FROM delivery_deliverable_versions
            WHERE id = '%s'::uuid
            """.formatted(sourceStableId, secondVersionId, sourceDeliverableVersionId));

        jdbc.update("""
            INSERT INTO delivery_deliverable_versions
                (id, deliverable_id, plan_version_id, project_id, title,
                 description, business_objective, product_owner_subject,
                 vendor_owner_subject, priority, target_completion_date,
                 evidence_expectations, dependency_none_declared,
                 risk_and_assumptions, delivery_category, link_exception_reason)
            SELECT ?::uuid, ?::uuid, ?::uuid, project_id, title,
                   description, business_objective, product_owner_subject,
                   vendor_owner_subject, priority, target_completion_date,
                   evidence_expectations, dependency_none_declared,
                   risk_and_assumptions, delivery_category, link_exception_reason
            FROM delivery_deliverable_versions
            WHERE id = ?::uuid
            """, validCloneId, secondStableId, secondVersionId, sourceDeliverableVersionId);
        assertSqlRejected("""
            UPDATE delivery_deliverable_versions
            SET deliverable_id = '%s'::uuid
            WHERE id = '%s'::uuid
            """.formatted(sourceStableId, validCloneId));

        assertEquals(sourceVersionId, jdbc.queryForObject("""
            SELECT plan_version_id FROM delivery_deliverable_versions
            WHERE id = ?::uuid
            """, String.class, sourceDeliverableVersionId));
    }

    @Test
    void crossTenantPlansAreNonDisclosingAndOpenApiRedactsSecretReferences()
        throws Exception {
        JsonNode created = createPlan("Approved local provider exception");
        String planId = created.path("id").asText();

        mvc.perform(get("/api/v1/delivery/plans/{planId}", planId)
                .with(token("user-northstar")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
        mvc.perform(get("/api/v1/integrations/linear/health")
                .queryParam("engagementId", ENGAGEMENT)
                .with(token("user-northstar")))
            .andExpect(status().isNotFound());

        String openApi = mvc.perform(get("/v3/api-docs")
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertFalse(openApi.contains("credentialSecretRef"));
        assertFalse(openApi.contains("webhookSecretRef"));
        assertFalse(openApi.contains("test-webhook-secret"));
        assertTrue(openApi.contains("/api/v1/delivery/plans"));
        JsonNode linkRequest = objectMapper.readTree(openApi)
            .path("components").path("schemas").path("LinkIssueRequest")
            .path("properties");
        assertTrue(linkRequest.has("deliverableVersionId"));
        assertTrue(linkRequest.has("connectionId"));
        assertTrue(linkRequest.has("issueUuid"));
        assertTrue(linkRequest.has("rationale"));
        assertFalse(linkRequest.has("identifier"));
        assertFalse(linkRequest.has("url"));
        assertFalse(linkRequest.has("title"));
    }

    private JsonNode createPlan(String linkExceptionReason) throws Exception {
        String exceptionJson = linkExceptionReason == null
            ? "null"
            : objectMapper.writeValueAsString(linkExceptionReason);
        String response = mvc.perform(post("/api/v1/delivery/plans")
                .with(token("user-arrow"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "engagementMonthId":"%s",
                      "title":"July delivery plan",
                      "summary":"Committed July outcomes",
                      "businessOutcomes":"Reliable local evidence",
                      "coordinatorSubject":"user-arrow",
                      "baselineType":"ON_TIME",
                      "quorumMode":"ANY_ONE",
                      "quorumRequired":1,
                      "approverSubjects":["user-approver"],
                      "recipients":{
                        "arrowFoundry":["vendor@example.test"],
                        "relianceStakeholders":["owner@example.test"],
                        "procurementCc":["procurement@example.test"]
                      },
                      "deliverables":[{
                        "deliverableCode":"DLV-001",
                        "title":"Provider-neutral planning",
                        "description":"Implement a local durable vertical",
                        "businessObjective":"Retain verifiable commitments",
                        "projectId":"%s",
                        "productOwnerSubject":"user-reliance",
                        "vendorOwnerSubject":"user-arrow",
                        "priority":"P1",
                        "targetCompletionDate":"2026-07-31",
                        "evidenceExpectations":"Automated tests and immutable snapshot",
                        "dependencyNoneDeclared":true,
                        "riskAndAssumptions":"No live provider configuration",
                        "deliveryCategory":"INTEGRATION",
                        "linkExceptionReason":%s,
                        "criteria":[{
                          "statement":"Durable evidence is retained",
                          "validationMethod":"Integration test",
                          "expectedResult":"One immutable baseline",
                          "mandatory":true
                        }],
                        "dependencies":[],
                        "assignments":[{
                          "employeeId":"%s",
                          "effectiveFrom":"2026-07-01",
                          "effectiveTo":"2026-07-31"
                        }]
                      }]
                    }
                    """.formatted(JULY_MONTH, PROJECT, exceptionJson, EMPLOYEE)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("DRAFT"))
            .andReturn().getResponse().getContentAsString();
        return json(response);
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private byte[] webhookBody(
        String connectionId,
        String organizationId,
        String issueId,
        long webhookTimestamp,
        Instant providerUpdatedAt,
        String stateType
    ) {
        return ("""
            {"type":"Issue","action":"update","organizationId":"%s",\
"connectionId":"%s","webhookTimestamp":%d,"data":{"id":"%s","identifier":"TEAM-123",\
"url":"https://linear.app/test/issue/TEAM-123","title":"Recorded issue",\
"updatedAt":"%s","state":{"id":"state-%s","name":"State","type":"%s"}}}
            """.formatted(
                organizationId, connectionId, webhookTimestamp, issueId,
                providerUpdatedAt, stateType, stateType))
            .getBytes(StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
    webhookRequest(
        String connectionId,
        String deliveryId,
        long timestamp,
        byte[] body,
        String secret
    ) throws Exception {
        return post("/api/v1/integrations/linear/webhook/{connectionId}", connectionId)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Linear-Signature", signature(body, secret))
            .header("Linear-Timestamp", Long.toString(timestamp))
            .header("Linear-Delivery", deliveryId)
            .content(body);
    }

    private void insertRecordedIssue(String issueId, String teamId, String identifier) {
        jdbc.update("""
            INSERT INTO linear_recorded_issue_metadata
                (connection_id, linear_issue_uuid, provider_organization_id,
                 provider_team_id, identifier, issue_url, title,
                 provider_state_type, provider_updated_at, payload_hash)
            VALUES (?::uuid, ?::uuid, 'linear-test-organization', ?, ?,
                    'https://linear.app/test/issue/' || ?, 'Recorded issue',
                    'unstarted', CURRENT_TIMESTAMP, repeat('c', 64))
            """, CONNECTION, issueId, teamId, identifier, identifier);
    }

    private void sendAndProcessWebhook(
        String deliveryId,
        Instant providerUpdatedAt,
        String stateType
    ) throws Exception {
        long timestamp = Instant.now().toEpochMilli();
        byte[] body = webhookBody(
            CONNECTION, "linear-test-organization", ISSUE, timestamp,
            providerUpdatedAt, stateType);
        mvc.perform(webhookRequest(
                CONNECTION, deliveryId, timestamp, body, "test-webhook-secret"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/integrations/linear/deliveries/{deliveryId}/process",
                    deliveryId)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PROCESSED"));
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
        assertTrue(Boolean.TRUE.equals(rejected), "SQL mutation should be rejected: " + sql);
    }

    private String signature(byte[] rawBody) throws Exception {
        return signature(rawBody, "test-webhook-secret");
    }

    private String signature(byte[] rawBody, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
            secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(rawBody));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor token(
        String subject
    ) {
        return jwt().jwt(value -> value.subject(subject).audience(List.of("vms-api")));
    }
}
