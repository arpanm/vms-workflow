package com.vms.workflow.integration;

import com.vms.workflow.application.CanonicalEvidenceHasher;
import com.vms.workflow.application.InboundMessageAuthenticator;
import com.vms.workflow.api.CertificationDtos.InboundAuthenticationInput;
import com.vms.workflow.api.CertificationDtos.InboundMessageRecordInput;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.PROJECT_A;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
    "vms.certification.inbound-signing-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "vms.certification.worker-initial-delay=PT1H"
})
@AutoConfigureMockMvc
@Transactional
class CertificationReviewIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CanonicalEvidenceHasher hasher;

    @Autowired
    private InboundMessageAuthenticator inboundAuthenticator;

    @Test
    void signedInboundIngestRequiresServiceIdentityAndNeverReturnsRestrictedData()
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
        long version = jdbc.queryForObject("""
            SELECT certification_version
            FROM engagement_months WHERE id = ?::uuid
            """, Long.class, MONTH);
        OffsetDateTime receivedAt =
            OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        InboundMessageRecordInput input = new InboundMessageRecordInput(
            version, request.requestId(),
            "provider-fingerprint-" + UUID.randomUUID(),
            "provider-message-restricted", "provider-thread-restricted",
            "ravi@reliance.example", "restricted/raw/inbound.eml",
            "a".repeat(64), "b".repeat(64), "c".repeat(64),
            new InboundAuthenticationInput("PASS", "PASS", "PASS"),
            "EXPLICIT_CONFIRM", receivedAt);
        long signatureTimestamp = Instant.now().getEpochSecond();
        String signature = inboundAuthenticator.sign(
            UUID.fromString(MONTH), signatureTimestamp, input);
        String body = mapper.writeValueAsString(input);

        mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/inbound-messages",
                    MONTH)
                .with(token("user-governance"))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key", "inbound-human-denied")
                .header("X-VMS-Inbound-Timestamp", signatureTimestamp)
                .header("X-VMS-Inbound-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());

        mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/inbound-messages",
                    MONTH)
                .with(token("service-inbound"))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key", "inbound-bad-signature")
                .header("X-VMS-Inbound-Timestamp", signatureTimestamp)
                .header("X-VMS-Inbound-Signature", "v1=" + "0".repeat(64))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isNotFound());

        String response = mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/inbound-messages",
                    MONTH)
                .with(token("service-inbound"))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key", "inbound-signed")
                .header("X-VMS-Inbound-Timestamp", signatureTimestamp)
                .header("X-VMS-Inbound-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.reviewKind").value("INBOUND_MESSAGE"))
            .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
            .andExpect(jsonPath("$.authenticationConfidence").value("VERIFIED"))
            .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("ravi@reliance.example"));
        assertFalse(response.contains("restricted/raw"));
        assertFalse(response.contains("provider-message-restricted"));
        UUID messageId = UUID.fromString(
            mapper.readTree(response).path("id").asText());
        assertEquals(
            hasher.sha256("ravi@reliance.example"),
            jdbc.queryForObject("""
                SELECT sender_address_hash
                FROM inbound_confirmation_messages WHERE id = ?
                """, String.class, messageId));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM certification_security_events
            WHERE event_type IN (
                'INBOUND_INGEST_DENIED', 'INBOUND_CALLBACK_REJECTED'
            )
            """, Integer.class));

        mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/inbound-messages",
                    MONTH)
                .with(token("service-inbound"))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key", "inbound-signed")
                .header("X-VMS-Inbound-Timestamp", signatureTimestamp)
                .header("X-VMS-Inbound-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(messageId.toString()));
    }

    @Test
    void manualEvidenceRecordIsWriteOnlyAndRequiresDistinctSecondReviewer()
        throws Exception {
        F04TestSupport.DirectConfirmation request = request();
        long version = jdbc.queryForObject("""
            SELECT certification_version
            FROM engagement_months WHERE id = ?::uuid
            """, Long.class, MONTH);
        String body = """
            {
              "expectedMonthVersion":%d,
              "requestId":"%s",
              "artifactId":"%s",
              "evidenceFormat":"PDF",
              "senderAddress":"ravi@reliance.example",
              "recipients":["restricted-recipient@example.test"],
              "subject":"Restricted commercial subject",
              "messageId":"manual-record-message",
              "sentOrReceivedAt":"%s",
              "representedDecision":"CONFIRM",
              "fileHash":"%s"
            }
            """.formatted(
                version, request.requestId(), F04TestSupport.EVIDENCE,
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(1),
                "f".repeat(64));
        String response = mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/manual-evidence",
                    MONTH)
                .with(token("user-governance"))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key", "manual-record")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.reviewStatus").value("PENDING"))
            .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("ravi@reliance.example"));
        assertFalse(response.contains("restricted-recipient"));
        assertFalse(response.contains("Restricted commercial subject"));
        UUID evidenceId = UUID.fromString(
            mapper.readTree(response).path("id").asText());

        String reviewBody = """
            {"expectedReviewVersion":0,"decision":"APPROVE",
             "reasoning":"A second authorized reviewer verified the artifact."}
            """;
        mvc.perform(post(
                    "/api/v1/certification/manual-evidence/{id}/reviews",
                    evidenceId)
                .with(token("user-governance"))
                .header("If-Match", "0")
                .header("Idempotency-Key", "manual-self-review")
                .contentType(MediaType.APPLICATION_JSON)
                .content(reviewBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(
                "SECOND_REVIEWER_REQUIRED"));

        mvc.perform(post(
                    "/api/v1/certification/manual-evidence/{id}/reviews",
                    evidenceId)
                .with(token("user-reviewer"))
                .header("If-Match", "0")
                .header("Idempotency-Key", "manual-distinct-review")
                .contentType(MediaType.APPLICATION_JSON)
                .content(reviewBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
            .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void inboundReviewIsSafeAppendOnlyIdempotentAndCorrelated()
        throws Exception {
        F04TestSupport.DirectConfirmation request = request();
        UUID messageId = UUID.randomUUID();
        String fingerprint = "provider-" + messageId;
        jdbc.update("""
            INSERT INTO inbound_confirmation_messages
                (id, engagement_month_id, request_id,
                 provider_message_fingerprint, provider_message_id,
                 sender_address_hash, raw_reference, raw_sha256,
                 authentication_evidence, classified_intent, status,
                 provider_received_at, correlation_id)
            VALUES (?, ?::uuid, ?, ?, 'provider-secret-id',
                    ?, 'restricted/raw/message.eml', repeat('e',64),
                    '{"spf":"pass","dkim":"pass","dmarc":"pass",
                      "verified":true}'::jsonb,
                    'EXPLICIT_CONFIRM', 'MANUAL_REVIEW_REQUIRED',
                    CURRENT_TIMESTAMP - INTERVAL '2 hours',
                    gen_random_uuid())
            """, messageId, MONTH, request.requestId(), fingerprint,
            hasher.sha256("ravi@reliance.example"));
        UUID correlationId = UUID.randomUUID();
        String body = """
            {
              "expectedReviewVersion":0,
              "decision":"ACCEPT_INTERPRETATION",
              "reasoning":"Authenticated metadata and captured eligibility match."
            }
            """;

        String response = mvc.perform(post(
                    "/api/v1/certification/inbound-messages/{id}/reviews",
                    messageId)
                .with(token("user-governance"))
                .header("If-Match", "0")
                .header("Idempotency-Key", "inbound-review")
                .header("X-Correlation-Id", correlationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(header().string(
                "X-Correlation-Id", correlationId.toString()))
            .andExpect(jsonPath("$.reviewKind").value("INBOUND_MESSAGE"))
            .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
            .andExpect(jsonPath("$.senderEligibility").value("ELIGIBLE"))
            .andExpect(jsonPath("$.authenticationConfidence").value("VERIFIED"))
            .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("provider-secret-id"));
        assertFalse(response.contains("restricted/raw"));
        assertFalse(response.contains("ravi@reliance.example"));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM business_confirmation_actions action
            JOIN reviewed_confirmation_action_promotions promotion
              ON promotion.action_id = action.id
            WHERE action.request_id = ?
              AND action.request_version = 1
              AND action.actor_subject = 'user-reliance'
              AND action.source = 'VERIFIED_EMAIL_REPLY'
              AND action.verification_status = 'VERIFIED'
              AND action.session_evidence_hash = repeat('e', 64)
              AND promotion.source_type = 'INBOUND_MESSAGE'
              AND promotion.source_id = ?
            """, Integer.class, request.requestId(), messageId));
        assertEquals("CONFIRMED", jdbc.queryForObject("""
            SELECT status FROM business_confirmation_requests WHERE id = ?
            """, String.class, request.requestId()));

        mvc.perform(post(
                    "/api/v1/certification/inbound-messages/{id}/reviews",
                    messageId)
                .with(token("user-governance"))
                .header("If-Match", "0")
                .header("Idempotency-Key", "inbound-review")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.version").value(1));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM business_confirmation_actions
            WHERE request_id = ?
            """, Integer.class, request.requestId()));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM certification_audit_events
                WHERE object_id = ? AND correlation_id = ?
            )
            """, Boolean.class, messageId, correlationId)));

        mvc.perform(post(
                    "/api/v1/certification/inbound-messages/{id}/reviews",
                    messageId)
                .with(token("user-arrow"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "unauthorized-review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedReviewVersion":1,
                     "decision":"QUARANTINE",
                     "reasoning":"Must not reveal existence."}
                    """))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
    }

    @Test
    void manualEvidenceRequiresDistinctAuthorizedSecondReviewerAndStaysSafe()
        throws Exception {
        F04TestSupport.DirectConfirmation request = request();
        UUID evidenceId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO manual_confirmation_evidence
                (id, engagement_month_id, request_id, artifact_id,
                 evidence_format, sender_address, recipients,
                 subject_text, message_id, sent_or_received_at,
                 represented_decision, file_hash, recorded_by_subject)
            VALUES (?, ?::uuid, ?, ?::uuid, 'PDF',
                    'ravi@reliance.example',
                    '["restricted-recipient@example.test"]'::jsonb,
                    'Restricted commercial subject', 'manual-message-id',
                    CURRENT_TIMESTAMP - INTERVAL '1 day',
                    'CONFIRM', repeat('f',64), 'user-arrow')
            """, evidenceId, MONTH, request.requestId(),
            "00000000-0000-0000-0000-000000000904");

        String response = mvc.perform(post(
                    "/api/v1/certification/manual-evidence/{id}/reviews",
                    evidenceId)
                .with(token("user-governance"))
                .header("If-Match", "0")
                .header("Idempotency-Key", "manual-second-review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedReviewVersion":0,"decision":"APPROVE",
                     "reasoning":"Artifact scan and represented facts are coherent."}
                    """))
            .andExpect(status().isCreated())
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.reviewKind").value("MANUAL_EVIDENCE"))
            .andExpect(jsonPath("$.reviewStatus").value("APPROVED"))
            .andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("Restricted commercial subject"));
        assertFalse(response.contains("restricted-recipient"));
        assertFalse(response.contains("ravi@reliance.example"));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM business_confirmation_actions action
            JOIN reviewed_confirmation_action_promotions promotion
              ON promotion.action_id = action.id
            WHERE action.request_id = ?
              AND action.request_version = 1
              AND action.actor_subject = 'user-reliance'
              AND action.source = 'MANUAL_EVIDENCE'
              AND action.verification_status = 'MANUAL_REVIEWED'
              AND action.session_evidence_hash = repeat('f', 64)
              AND promotion.source_type = 'MANUAL_EVIDENCE'
              AND promotion.source_id = ?
            """, Integer.class, request.requestId(), evidenceId));
        assertEquals("CONFIRMED", jdbc.queryForObject("""
            SELECT status FROM business_confirmation_requests WHERE id = ?
            """, String.class, request.requestId()));

        JsonNode workspace = mapper.readTree(mvc.perform(get(
                    "/api/v1/certification/months/{monthId}", MONTH)
                .with(token("user-governance")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertTrue(workspace.path("inboundReviews").toString()
            .contains(evidenceId.toString()));
        assertFalse(workspace.toString()
            .contains("Restricted commercial subject"));
    }

    private F04TestSupport.DirectConfirmation request() throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        return F04TestSupport.directConfirmation(
            jdbc, completed, "ANY_ONE", 1,
            OffsetDateTime.now().minusMinutes(1),
            OffsetDateTime.now().plusDays(2),
            List.of(new F04TestSupport.EligibleFixture(
                "user-reliance", "ravi@reliance.example", PROJECT_A)));
    }
}
