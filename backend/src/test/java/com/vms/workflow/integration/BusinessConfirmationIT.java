package com.vms.workflow.integration;

import com.vms.workflow.application.ConfirmationTokenCodec;
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
import static com.vms.workflow.integration.F04TestSupport.PROJECT_B;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    "vms.certification.email-provider-status=NOT_CONFIGURED",
    "vms.certification.f05-handoff-status=NOT_CONFIGURED"
})
@AutoConfigureMockMvc
@Transactional
class BusinessConfirmationIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfirmationTokenCodec tokenCodec;

    @Test
    void readinessCreatesExactScopeRequestWithCapturedDueEligibilityAndNoSecret()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        OffsetDateTime due = OffsetDateTime.now().plusDays(3).withNano(0);

        JsonNode request = F04TestSupport.createConfirmationRequest(
            mvc, mapper, completed.monthVersion(), due, "exact-request");
        UUID requestId = UUID.fromString(request.path("id").asText());
        assertEquals(due, OffsetDateTime.parse(request.path("dueAt").asText()));
        assertEquals("AWAITING_RESPONSE", request.path("state").asText());
        assertEquals("NOT_CONFIGURED", request.path("transportStatus").asText());
        assertTrue(request.path("sourceVersionIds").toString()
            .contains(completed.baseline().planVersionId().toString()));
        assertFalse(request.toString().contains("tokenHash"));
        assertFalse(request.toString().contains("secureToken"));
        assertEquals(completed.attendanceSnapshotId(), jdbc.queryForObject("""
            SELECT attendance_snapshot_id FROM business_confirmation_requests
            WHERE id = ?
            """, UUID.class, requestId));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM confirmation_request_eligibility
            WHERE request_id = ?
            """, requestId));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM confirmation_secure_tokens
            WHERE request_id = ?
            """, requestId));
    }

    @Test
    void correctedRequestRetainsLatestSupersededPredecessorAfterReopen()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        OffsetDateTime due = OffsetDateTime.now().plusDays(3).withNano(0);
        JsonNode original = F04TestSupport.createConfirmationRequest(
            mvc, mapper, completed.monthVersion(), due,
            "pre-reopen-confirmation");
        UUID originalId = UUID.fromString(original.path("id").asText());

        jdbc.update("""
            UPDATE business_confirmation_requests
            SET status = 'SUPERSEDED',
                optimistic_version = optimistic_version + 1
            WHERE id = ?
            """, originalId);
        long reopenedMonthVersion = jdbc.queryForObject("""
            SELECT certification_version
            FROM engagement_months
            WHERE id = ?::uuid
            """, Long.class, MONTH);

        JsonNode corrected = F04TestSupport.createConfirmationRequest(
            mvc, mapper, reopenedMonthVersion, due,
            "post-reopen-confirmation");
        UUID correctedId = UUID.fromString(corrected.path("id").asText());
        assertEquals(originalId, jdbc.queryForObject("""
            SELECT supersedes_id
            FROM business_confirmation_requests
            WHERE id = ?
            """, UUID.class, correctedId));
    }

    @Test
    void inAppConfirmationIsExplicitAuditedIdempotentAndTransportNeutral()
        throws Exception {
        F04TestSupport.DirectConfirmation fixture = directAnyOne(false);
        String body = """
            {"expectedRequestVersion":1,"decision":"CONFIRM",
             "comment":"Confirmed exact immutable scope"}
            """;
        JsonNode confirmed = action(
            fixture.requestId(), "user-reliance", "confirm-once", body, 200);
        assertEquals("CONFIRMED", confirmed.path("state").asText());
        assertEquals("IN_APP", confirmed.path("actions").get(0).path("source").asText());
        assertEquals("NOT_CONFIGURED", confirmed.path("transportStatus").asText());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM business_confirmation_actions
            WHERE request_id = ?
            """, fixture.requestId()));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM certification_audit_events audit
            JOIN business_confirmation_actions action ON action.id = audit.object_id
            WHERE action.request_id = ?
            """, fixture.requestId()));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM notification_outbox
            WHERE business_object_id = ?
            """, fixture.requestId()));

        JsonNode replay = action(
            fixture.requestId(), "user-reliance", "confirm-once", body, 200);
        assertEquals(confirmed.path("actions").get(0).path("id").asText(),
            replay.path("actions").get(0).path("id").asText());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM business_confirmation_actions
            WHERE request_id = ?
            """, fixture.requestId()));

        action(
            fixture.requestId(), "user-reliance", "confirm-terminal-replay",
            body, 409);
        assertEquals(1, count("""
            SELECT COUNT(*) FROM certification_security_events
            WHERE object_id = ?
              AND event_type = 'CONFIRMATION_ACTION_REJECTED'
              AND outcome = 'DENIED'
              AND redacted_facts
                  @> '{"reasonCode":"REQUEST_NOT_AWAITING_RESPONSE"}'::jsonb
            """, fixture.requestId()));
    }

    @Test
    void staleRequestVersionAndNonConfirmationWithoutCommentAreAtomic()
        throws Exception {
        F04TestSupport.DirectConfirmation fixture = directAnyOne(false);
        mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{requestId}/actions",
                    fixture.requestId())
                .with(token("user-reliance"))
                .header("If-Match", "0")
                .header("Idempotency-Key", "stale-action")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":0,"decision":"CONFIRM"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONFIRMATION_VERSION_CONFLICT"))
            .andExpect(jsonPath("$.currentVersion").value(1));
        mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{requestId}/actions",
                    fixture.requestId())
                .with(token("user-reliance"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "correction-no-comment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":1,"decision":"REQUEST_CORRECTION"}
                    """))
            .andExpect(status().isBadRequest());
        assertEquals(0, count("""
            SELECT COUNT(*) FROM business_confirmation_actions
            WHERE request_id = ?
            """, fixture.requestId()));
    }

    @Test
    void actionRequiresCurrentPermissionOnTheCapturedEligibleProject()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        F04TestSupport.DirectConfirmation fixture =
            F04TestSupport.directConfirmation(
                jdbc, completed, "ANY_ONE", 1,
                OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now().plusDays(2),
                List.of(new F04TestSupport.EligibleFixture(
                    "user-project-b", "project-b-owner@reliance.example",
                    PROJECT_B)));

        // user-project-b only has certification.confirmation.act on PROJECT_A.
        mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{requestId}/actions",
                    fixture.requestId())
                .with(token("user-project-b"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "wrong-project-action")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":1,
                     "decision":"REQUEST_CORRECTION",
                     "comment":"Wrong project must not authorize this action"}
                    """))
            .andExpect(status().isNotFound());
        assertEquals(0, count("""
            SELECT COUNT(*) FROM business_confirmation_actions
            WHERE request_id = ?
            """, fixture.requestId()));
    }

    @Test
    void pastDueRequestExpiresAndCannotAcceptInAppAction() throws Exception {
        F04TestSupport.DirectConfirmation fixture = directAnyOne(true);
        mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{requestId}/actions",
                    fixture.requestId())
                .with(token("user-reliance"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "past-due-action")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":1,
                     "decision":"REQUEST_CORRECTION",
                     "comment":"Past due requests accept no action"}
                    """))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONFIRMATION_EXPIRED"));
        assertEquals("EXPIRED", jdbc.queryForObject("""
            SELECT status FROM business_confirmation_requests WHERE id = ?
            """, String.class, fixture.requestId()));
        assertEquals(0, count("""
            SELECT COUNT(*) FROM business_confirmation_actions
            WHERE request_id = ?
            """, fixture.requestId()));
    }

    @Test
    void secureTokenIsSingleUseRequestBoundAndReplayOnlyReturnsOriginalOutcome()
        throws Exception {
        F04TestSupport.DirectConfirmation fixture = directAnyOne(false);
        ConfirmationTokenCodec.IssuedToken issued = tokenCodec.issue();
        UUID tokenId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO confirmation_secure_tokens
                (id, request_id, request_version, eligible_confirmer_subject,
                 token_hash, token_salt, hash_algorithm, work_factor, expires_at)
            VALUES (?, ?, 1, 'user-reliance', ?, ?, ?, ?,
                    CURRENT_TIMESTAMP + INTERVAL '1 day')
            """, tokenId, fixture.requestId(), issued.encodedHash(),
            issued.encodedSalt(), issued.algorithm(), issued.workFactor());
        String body = """
            {"expectedRequestVersion":1,"decision":"REQUEST_CORRECTION",
             "comment":"Request correction through the secure link",
             "secureToken":"%s"}
            """.formatted(issued.plaintext());
        JsonNode first = action(
            fixture.requestId(), "user-reliance", "secure-once", body, 200);
        assertEquals("SECURE_LINK",
            first.path("actions").get(0).path("source").asText());
        assertEquals("user-reliance", jdbc.queryForObject("""
            SELECT consumed_by_subject FROM confirmation_secure_tokens WHERE id = ?
            """, String.class, tokenId));

        JsonNode replay = action(
            fixture.requestId(), "user-reliance", "secure-once", body, 200);
        assertEquals(first.path("actions").get(0).path("id").asText(),
            replay.path("actions").get(0).path("id").asText());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM business_confirmation_actions
            WHERE request_id = ?
            """, fixture.requestId()));
    }

    @Test
    void directConfirmationFixturePreservesGovernedMonthTransitionLineage()
        throws Exception {
        F04TestSupport.DirectConfirmation fixture = directAnyOne(false);

        assertEquals("CONFIRMATION_PENDING", jdbc.queryForObject("""
            SELECT state FROM engagement_months WHERE id = ?::uuid
            """, String.class, MONTH));
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM engagement_month_transition_history history
            WHERE history.engagement_month_id = ?::uuid
              AND history.from_state = 'DELIVERY_REVIEW'
              AND history.to_state = 'CONFIRMATION_PENDING'
            """, MONTH));

        action(
            fixture.requestId(), "user-reliance",
            "governed-fixture-confirm", """
                {"expectedRequestVersion":1,"decision":"CONFIRM",
                 "comment":"Confirmed through governed pending state"}
                """, 200);

        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM engagement_month_transition_history history
            WHERE history.engagement_month_id = ?::uuid
              AND history.from_state = 'CONFIRMATION_PENDING'
              AND history.to_state = 'CONFIRMED'
            """, MONTH));
        assertThrows(
            RuntimeException.class,
            () -> jdbc.update("""
                UPDATE engagement_month_transition_history
                SET reason = 'rewritten'
                WHERE engagement_month_id = ?::uuid
                """, MONTH));
    }

    @Test
    void databaseRejectsDeliveryReviewToConfirmedShortcut()
        throws Exception {
        F04TestSupport.completedCertification(mvc, mapper, jdbc);
        assertEquals("DELIVERY_REVIEW", jdbc.queryForObject("""
            SELECT state FROM engagement_months WHERE id = ?::uuid
            """, String.class, MONTH));

        assertThrows(
            RuntimeException.class,
            () -> jdbc.update("""
                UPDATE engagement_months
                SET state = 'CONFIRMED'
                WHERE id = ?::uuid
                """, MONTH));
    }

    @Test
    void multiPartyOutcomeNotificationOccursOnlyAfterQuorum()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        F04TestSupport.DirectConfirmation fixture =
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
        JsonNode first = action(
            fixture.requestId(), "user-reliance", "quorum-first",
            """
                {"expectedRequestVersion":1,"decision":"CONFIRM"}
                """, 200);
        assertEquals("AWAITING_RESPONSE", first.path("state").asText());
        assertEquals(0, count("""
            SELECT COUNT(*) FROM notification_outbox
            WHERE business_object_id = ?
              AND template_key = 'confirmation-outcome-v1'
            """, fixture.requestId()));

        JsonNode terminal = action(
            fixture.requestId(), "user-project-b", "quorum-second",
            """
                {"expectedRequestVersion":1,"decision":"CONFIRM"}
                """, 200);
        assertEquals("CONFIRMED", terminal.path("state").asText());
        assertEquals(1, count("""
            SELECT COUNT(*) FROM notification_outbox
            WHERE business_object_id = ?
              AND template_key = 'confirmation-outcome-v1'
            """, fixture.requestId()));
    }

    @Test
    void projectScopedRequestReadReturnsOnlyVisibleContributionAndRedactedAggregate()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        F04TestSupport.DirectConfirmation fixture =
            F04TestSupport.directConfirmation(
                jdbc, completed, "ALL", 2,
                OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now().plusDays(2),
                List.of(
                    new F04TestSupport.EligibleFixture(
                        "user-reliance", "ravi@reliance.example", PROJECT_A),
                    new F04TestSupport.EligibleFixture(
                        "user-project-b", "project-b-owner@reliance.example",
                        PROJECT_B)));
        jdbc.update("""
            INSERT INTO business_confirmation_actions
                (id, request_id, request_version, actor_subject,
                 actor_authority_snapshot, project_id, action, comment,
                 source, verification_status, action_hash, idempotency_key)
            VALUES (gen_random_uuid(), ?, 1, 'user-reliance',
                    '{"roleReason":"ASSIGNED_PRODUCT_OWNER"}'::jsonb,
                    ?::uuid, 'CONFIRM', 'Visible project contribution',
                    'IN_APP', 'VERIFIED', repeat('a',64), 'visible-action'),
                   (gen_random_uuid(), ?, 1, 'user-project-b',
                    '{"roleReason":"ASSIGNED_PRODUCT_OWNER"}'::jsonb,
                    ?::uuid, 'CONFIRM', 'Hidden project contribution',
                    'IN_APP', 'VERIFIED', repeat('c',64), 'hidden-action')
            """, fixture.requestId(), PROJECT_A, fixture.requestId(), PROJECT_B);

        JsonNode scoped = mapper.readTree(mvc.perform(get(
                    "/api/v1/certification/confirmation-requests/{requestId}",
                    fixture.requestId())
                .with(token("user-project-reader")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recipients").isEmpty())
            .andExpect(jsonPath("$.sourceVersionIds").isEmpty())
            .andExpect(jsonPath("$.scopeSources").isEmpty())
            .andExpect(jsonPath("$.diff").isEmpty())
            .andExpect(jsonPath("$.notifications").isEmpty())
            .andExpect(jsonPath("$.lineage").isEmpty())
            .andExpect(jsonPath("$.actions.length()").value(1))
            .andExpect(jsonPath("$.actions[0].comment")
                .value("Visible project contribution"))
            .andReturn().getResponse().getContentAsString());
        assertFalse(scoped.toString().contains("Hidden project contribution"));
        assertFalse(scoped.path("scopeChecksum").asText()
            .equals("b".repeat(64)));

        mvc.perform(get(
                    "/api/v1/certification/confirmation-requests/{requestId}",
                    fixture.requestId())
                .with(token("user-governance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.recipients").isNotEmpty())
            .andExpect(jsonPath("$.sourceVersionIds").isNotEmpty())
            .andExpect(jsonPath("$.diff").isNotEmpty())
            .andExpect(jsonPath("$.lineage").isNotEmpty())
            .andExpect(jsonPath("$.actions.length()").value(2))
            .andExpect(jsonPath("$.scopeChecksum").value("b".repeat(64)));
    }

    @Test
    void quarantinedReceiptAndAutoReplyRowsNeverContributeConfirmationActions()
        throws Exception {
        F04TestSupport.DirectConfirmation fixture = directAnyOne(true);
        jdbc.update("""
            INSERT INTO inbound_confirmation_messages
                (id, engagement_month_id, request_id,
                 provider_message_fingerprint, sender_address_hash,
                 authentication_evidence, classified_intent, status,
                 provider_received_at, correlation_id)
            VALUES (gen_random_uuid(), ?::uuid, ?, 'receipt-fixture',
                    repeat('d', 64), '{"transport":"delivered"}'::jsonb,
                    'RECEIPT', 'QUARANTINED', CURRENT_TIMESTAMP, gen_random_uuid()),
                   (gen_random_uuid(), ?::uuid, ?, 'auto-reply-fixture',
                    repeat('e', 64), '{"autoSubmitted":true}'::jsonb,
                    'AUTO_REPLY', 'QUARANTINED', CURRENT_TIMESTAMP, gen_random_uuid())
            """, MONTH, fixture.requestId(), MONTH, fixture.requestId());
        assertEquals("AWAITING_RESPONSE", jdbc.queryForObject("""
            SELECT status FROM business_confirmation_requests WHERE id = ?
            """, String.class, fixture.requestId()));
        assertEquals(0, count("""
            SELECT COUNT(*) FROM business_confirmation_actions
            WHERE request_id = ?
            """, fixture.requestId()));
        mvc.perform(get(
                    "/api/v1/certification/confirmation-requests/{requestId}",
                    fixture.requestId())
                .with(token("user-reliance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("AWAITING_RESPONSE"))
            .andExpect(jsonPath("$.actions").isEmpty());
    }

    @Test
    void dualVendorClientCannotContributeConfirmationForAnotherVendorSubmission()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        F04TestSupport.DirectConfirmation request =
            F04TestSupport.directConfirmation(
                jdbc, completed, "ANY_ONE", 1,
                OffsetDateTime.now().minusMinutes(1),
                OffsetDateTime.now().plusDays(2),
                List.of(new F04TestSupport.EligibleFixture(
                    "user-sod", "dual-authority@example.test", PROJECT_A)));

        mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{requestId}/actions",
                    request.requestId())
                .with(token("user-sod"))
                .header("If-Match", Integer.toString(request.version()))
                .header("Idempotency-Key", "dual-party-confirmation-denied")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":1,"decision":"CONFIRM",
                     "projectId":"%s","comment":"Must be denied by SOD"}
                    """.formatted(PROJECT_A)))
            .andExpect(status().isNotFound());

        assertEquals(0, count("""
            SELECT COUNT(*) FROM business_confirmation_actions
            WHERE request_id = ?
            """, request.requestId()));
    }

    private F04TestSupport.DirectConfirmation directAnyOne(boolean expired)
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        OffsetDateTime now = OffsetDateTime.now();
        return F04TestSupport.directConfirmation(
            jdbc, completed, "ANY_ONE", 1,
            expired ? now.minusDays(3) : now.minusMinutes(1),
            expired ? now.minusDays(2) : now.plusDays(2),
            List.of(new F04TestSupport.EligibleFixture(
                "user-reliance", "ravi@reliance.example", PROJECT_A)));
    }

    private JsonNode action(
        UUID requestId,
        String subject,
        String key,
        String body,
        int expectedStatus
    ) throws Exception {
        return mapper.readTree(mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{requestId}/actions",
                    requestId)
                .with(token(subject))
                .header("If-Match", "1")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().is(expectedStatus))
            .andReturn().getResponse().getContentAsString());
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
