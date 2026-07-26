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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.PROJECT_A;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:18-alpine:///vms_workflow",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.certification.worker-initial-delay=PT1H"
})
@AutoConfigureMockMvc
@Transactional
class CertificationGovernanceIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void mixedActionsEnterConflictAndExactSeparatedGovernanceResolvesIt()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        F04TestSupport.DirectConfirmation request =
            F04TestSupport.directConfirmation(
                jdbc, completed, "ALL", 2,
                OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now().plusDays(2),
                List.of(
                    new F04TestSupport.EligibleFixture(
                        "user-reliance", "ravi@reliance.example", PROJECT_A),
                    new F04TestSupport.EligibleFixture(
                        "user-project-b", "project-b-owner@reliance.example",
                        PROJECT_A)));

        act(request, "user-reliance", "CONFIRM",
            "First confirmer explicitly accepted.", "conflict-action-one")
            .andExpect(jsonPath("$.state").value("AWAITING_RESPONSE"));
        act(request, "user-project-b", "REJECT",
            "Second confirmer found a material conflict.", "conflict-action-two")
            .andExpect(jsonPath("$.state").value("CONFLICT_REVIEW"));
        List<UUID> actionIds = jdbc.query("""
            SELECT id FROM business_confirmation_actions
            WHERE request_id = ? ORDER BY id DESC
            """, (rs, rowNum) -> rs.getObject(1, UUID.class),
            request.requestId());
        assertEquals(2, actionIds.size());
        assertEquals("CONFLICT_REVIEW", requestStatus(request.requestId()));
        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM business_confirmation_governance_decisions
            WHERE request_id = ?
            """, Integer.class, request.requestId()));

        String governanceBody = """
            {
              "expectedRequestVersion":%d,
              "decision":"CONFIRM",
              "reasoning":"Independent governance reviewed both immutable actions.",
              "actionIds":["%s","%s"]
            }
            """.formatted(
                request.version(), actionIds.get(0), actionIds.get(1));
        String response = mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{id}/governance-decisions",
                    request.requestId())
                .with(token("user-governance"))
                .header("If-Match", Integer.toString(request.version()))
                .header("Idempotency-Key", "conflict-governance")
                .contentType(MediaType.APPLICATION_JSON)
                .content(governanceBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("CONFIRM"))
            .andExpect(jsonPath("$.actionIds.length()").value(2))
            .andReturn().getResponse().getContentAsString();
        UUID decisionId = UUID.fromString(
            mapper.readTree(response).path("id").asText());
        assertEquals("CONFIRMED", requestStatus(request.requestId()));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM business_confirmation_governance_decisions
            WHERE id = ? AND request_id = ?
            """, Integer.class, decisionId, request.requestId()));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM f05_certification_handoffs
            WHERE confirmation_request_id = ?
            """, Integer.class, request.requestId()));

        mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{id}/governance-decisions",
                    request.requestId())
                .with(token("user-governance"))
                .header("If-Match", Integer.toString(request.version()))
                .header("Idempotency-Key", "conflict-governance")
                .contentType(MediaType.APPLICATION_JSON)
                .content(governanceBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(decisionId.toString()));
    }

    @Test
    void closeAndApprovedReopenKeepAppendOnlyInvalidationLineage()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        F04TestSupport.DirectConfirmation request =
            F04TestSupport.directConfirmation(
                jdbc, completed, "ANY_ONE", 1,
                OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now().plusDays(2),
                List.of(new F04TestSupport.EligibleFixture(
                    "user-reliance", "ravi@reliance.example", PROJECT_A)));
        act(request, "user-reliance", "CONFIRM",
            "Explicit close-ready confirmation.", "close-confirmation")
            .andExpect(jsonPath("$.state").value("CONFIRMED"));
        long confirmedVersion = monthVersion();

        String closureResponse = mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/closures", MONTH)
                .with(token("user-governance"))
                .header("If-Match", Long.toString(confirmedVersion))
                .header("Idempotency-Key", "month-close")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedMonthVersion":%d}
                    """.formatted(confirmedVersion)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("CURRENT"))
            .andExpect(jsonPath("$.confirmationRequestId")
                .value(request.requestId().toString()))
            .andReturn().getResponse().getContentAsString();
        UUID closureId = UUID.fromString(
            mapper.readTree(closureResponse).path("id").asText());
        assertFalse(
            mapper.readTree(closureResponse).path("manifestHash")
                .asText().isBlank());

        long closedVersion = monthVersion();
        mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/reopen-requests",
                    MONTH)
                .with(token("user-reliance"))
                .header("If-Match", Long.toString(closedVersion))
                .header("Idempotency-Key", "month-reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "category":"CERTIFICATION_CORRECTION",
                      "reason":"A represented certification fact requires correction.",
                      "impactedRecordIds":["%s"],
                      "packageInvoiceImpact":"NOT_SUBMITTED",
                      "riskStatement":"The closed fact must remain visible and be superseded."
                    }
                    """.formatted(closedVersion, completed.summaryId())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.lifecycleState")
                .value("REOPEN_REQUESTED"));
        UUID reopenId = jdbc.queryForObject("""
            SELECT id FROM month_reopen_requests
            WHERE engagement_month_id = ?::uuid AND status = 'REQUESTED'
            """, UUID.class, MONTH);
        UUID invalidationId = jdbc.queryForObject("""
            SELECT id FROM certification_invalidations
            WHERE reopen_request_id = ? AND object_id = ?
            """, UUID.class, reopenId, completed.summaryId());
        long reopenVersion = monthVersion();

        String decisionResponse = mvc.perform(post(
                    "/api/v1/certification/reopen-requests/{id}/decisions",
                    reopenId)
                .with(token("user-governance"))
                .header("If-Match", Long.toString(reopenVersion))
                .header("Idempotency-Key", "month-reopen-approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "decision":"APPROVE",
                      "reasoning":"Independent authority approved additive correction lineage."
                    }
                    """.formatted(reopenVersion)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("APPROVE"))
            .andReturn().getResponse().getContentAsString();
        assertNotNull(
            mapper.readTree(decisionResponse).path("auditReference").asText());
        assertEquals("SUPERSEDED", jdbc.queryForObject("""
            SELECT status FROM month_closures WHERE id = ?
            """, String.class, closureId));
        assertEquals("REOPENED", jdbc.queryForObject("""
            SELECT state FROM engagement_months WHERE id = ?::uuid
            """, String.class, MONTH));
        assertEquals("INVALIDATED", jdbc.queryForObject("""
            SELECT effective_status
            FROM effective_f05_certification_handoffs
            WHERE confirmation_request_id = ?
            """, String.class, request.requestId()));
        assertEquals("CANCELLED", jdbc.queryForObject("""
            SELECT job.status
            FROM f05_handoff_publish_jobs job
            JOIN f05_certification_handoffs handoff
              ON handoff.id = job.handoff_id
            WHERE handoff.confirmation_request_id = ?
            """, String.class, request.requestId()));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM certification_domain_events
            WHERE event_type = 'certification.f05-handoff.invalidated.v1'
              AND payload ->> 'confirmationRequestId' = ?
              AND payload ->> 'requiredConsumerAction' =
                  'REVOKE_OR_COMPENSATE_BEFORE_DOWNSTREAM_USE'
            """, Integer.class, request.requestId().toString()));

        long resolutionVersion = monthVersion();
        mvc.perform(post(
                    "/api/v1/certification/invalidations/{id}/resolutions",
                    invalidationId)
                .with(token("user-governance"))
                .header("If-Match", Long.toString(resolutionVersion))
                .header("Idempotency-Key", "invalidation-false-clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "resolution":"CLEARED",
                      "reasoning":"An unrelated later fact must not clear this."
                    }
                    """.formatted(resolutionVersion)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("RECERTIFICATION_REQUIRED"));

        jdbc.update("""
            UPDATE monthly_certification_summaries
            SET status = 'SUPERSEDED'
            WHERE id = ?
            """, completed.summaryId());
        UUID correctedSummaryId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO monthly_certification_summaries
                (id, engagement_month_id, submission_id, round_id,
                 plan_version_id, baseline_id, policy_version_id, version,
                 status, supersedes_id, monthly_decision, observations, risks,
                 manifest, checksum, authority_snapshot, represented_at,
                 created_by_subject)
            SELECT ?, engagement_month_id, submission_id, round_id,
                   plan_version_id, baseline_id, policy_version_id, 2,
                   'CURRENT', id, monthly_decision, observations, risks,
                   manifest, repeat('8', 64), authority_snapshot,
                   clock_timestamp(), 'user-reliance'
            FROM monthly_certification_summaries
            WHERE id = ?
            """, correctedSummaryId, completed.summaryId());
        UUID correctedRequestId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO business_confirmation_requests
                (id, engagement_month_id, attendance_snapshot_id,
                 plan_version_id, baseline_id, certification_summary_id,
                 policy_version_id, version, status, transport_status,
                 supersedes_id, quorum_mode, quorum_required,
                 recipient_snapshot, eligibility_snapshot, scope_manifest,
                 scope_checksum, requested_at, due_at, completed_at,
                 created_by_subject)
            SELECT ?, engagement_month_id, attendance_snapshot_id,
                   plan_version_id, baseline_id, ?, policy_version_id, 2,
                   'CONFIRMED', transport_status, id, quorum_mode,
                   quorum_required, recipient_snapshot, eligibility_snapshot,
                   scope_manifest, repeat('9', 64), clock_timestamp(),
                   clock_timestamp() + INTERVAL '1 day',
                   clock_timestamp() + INTERVAL '1 second',
                   'user-governance'
            FROM business_confirmation_requests
            WHERE id = ?
            """, correctedRequestId, correctedSummaryId, request.requestId());
        mvc.perform(post(
                    "/api/v1/certification/invalidations/{id}/resolutions",
                    invalidationId)
                .with(token("user-governance"))
                .header("If-Match", Long.toString(resolutionVersion))
                .header("Idempotency-Key", "invalidation-exact-clear")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "resolution":"CLEARED",
                      "reasoning":"The exact successor was recertified and confirmed."
                    }
                    """.formatted(resolutionVersion)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.resolution").value("CLEARED"));
        assertEquals("ACTIVE", jdbc.queryForObject("""
            SELECT status FROM certification_invalidations WHERE id = ?
            """, String.class, invalidationId));
        assertEquals("CLEARED", jdbc.queryForObject("""
            SELECT effective_status
            FROM effective_certification_invalidations WHERE id = ?
            """, String.class, invalidationId));
        assertEquals(correctedSummaryId, jdbc.queryForObject("""
            SELECT corrected_object_id
            FROM certification_invalidation_resolutions
            WHERE invalidation_id = ?
            """, UUID.class, invalidationId));
        assertEquals(2, jdbc.queryForObject("""
            SELECT corrected_object_version
            FROM certification_invalidation_resolutions
            WHERE invalidation_id = ?
            """, Integer.class, invalidationId));
    }

    @Test
    void reopenRejectsOutOfScopeImpactAndRejectionResolvesCreatedInvalidations()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        F04TestSupport.DirectConfirmation request =
            F04TestSupport.directConfirmation(
                jdbc, completed, "ANY_ONE", 1,
                OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now().plusDays(2),
                List.of(new F04TestSupport.EligibleFixture(
                    "user-reliance", "ravi@reliance.example", PROJECT_A)));
        act(request, "user-reliance", "CONFIRM",
            "Explicit confirmation before rejected reopen.",
            "rejected-reopen-confirmation")
            .andExpect(jsonPath("$.state").value("CONFIRMED"));

        long confirmedVersion = monthVersion();
        mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/reopen-requests",
                    MONTH)
                .with(token("user-reliance"))
                .header("If-Match", Long.toString(confirmedVersion))
                .header("Idempotency-Key", "out-of-scope-reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "category":"CERTIFICATION_CORRECTION",
                      "reason":"Attempt to target an unrelated fact.",
                      "impactedRecordIds":["%s"],
                      "packageInvoiceImpact":"NOT_SUBMITTED",
                      "riskStatement":"Must be rejected before persistence."
                    }
                    """.formatted(confirmedVersion, UUID.randomUUID())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("REOPEN_IMPACT_OUT_OF_SCOPE"));
        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*) FROM month_reopen_requests
            WHERE engagement_month_id = ?::uuid
            """, Integer.class, MONTH));

        mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/reopen-requests",
                    MONTH)
                .with(token("user-reliance"))
                .header("If-Match", Long.toString(confirmedVersion))
                .header("Idempotency-Key", "valid-rejected-reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "category":"CERTIFICATION_CORRECTION",
                      "reason":"Review found the original fact remains correct.",
                      "impactedRecordIds":["%s"],
                      "packageInvoiceImpact":"NOT_SUBMITTED",
                      "riskStatement":"Pending governance decision."
                    }
                    """.formatted(confirmedVersion, completed.summaryId())))
            .andExpect(status().isCreated());
        UUID reopenId = jdbc.queryForObject("""
            SELECT id FROM month_reopen_requests
            WHERE engagement_month_id = ?::uuid
            """, UUID.class, MONTH);

        long pendingVersion = monthVersion();
        mvc.perform(post(
                    "/api/v1/certification/reopen-requests/{id}/decisions",
                    reopenId)
                .with(token("user-governance"))
                .header("If-Match", Long.toString(pendingVersion))
                .header("Idempotency-Key", "reject-reopen-decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "decision":"REJECT",
                      "reasoning":"Independent review found no correction."
                    }
                    """.formatted(pendingVersion)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("REJECT"));

        assertEquals("CONFIRMED", jdbc.queryForObject("""
            SELECT state FROM engagement_months WHERE id = ?::uuid
            """, String.class, MONTH));
        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM effective_certification_invalidations
            WHERE reopen_request_id = ? AND effective_status = 'ACTIVE'
            """, Integer.class, reopenId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM certification_invalidation_resolutions resolution
            JOIN certification_invalidations invalidation
              ON invalidation.id = resolution.invalidation_id
            WHERE invalidation.reopen_request_id = ?
              AND resolution.resolution = 'SUPERSEDED'
            """, Integer.class, reopenId));
    }

    private org.springframework.test.web.servlet.ResultActions act(
        F04TestSupport.DirectConfirmation request,
        String subject,
        String decision,
        String comment,
        String key
    ) throws Exception {
        return mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{id}/actions",
                    request.requestId())
                .with(token(subject))
                .header("If-Match", Integer.toString(request.version()))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedRequestVersion":%d,
                      "decision":"%s",
                      "projectId":"%s",
                      "comment":"%s"
                    }
                    """.formatted(
                        request.version(), decision, PROJECT_A, comment)))
            .andExpect(status().isOk());
    }

    private long monthVersion() {
        return jdbc.queryForObject("""
            SELECT certification_version
            FROM engagement_months WHERE id = ?::uuid
            """, Long.class, MONTH);
    }

    private String requestStatus(UUID requestId) {
        return jdbc.queryForObject("""
            SELECT status FROM business_confirmation_requests WHERE id = ?
            """, String.class, requestId);
    }
}
