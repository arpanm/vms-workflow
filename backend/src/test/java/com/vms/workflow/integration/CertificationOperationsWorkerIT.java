package com.vms.workflow.integration;

import com.vms.workflow.application.CertificationEmailAdapter;
import com.vms.workflow.application.CertificationOperationsWorker;
import com.vms.workflow.application.ConfirmationTokenCodec;
import com.vms.workflow.application.F05CertificationReadinessPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.PROJECT_A;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.http.MediaType;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_certification_operations_worker_it",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.certification.email-provider-status=CONFIGURED",
    "vms.certification.worker-enabled=true",
    "vms.certification.token-handoff-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "vms.certification.f05-handoff-status=CONFIGURED",
    "vms.certification.worker-initial-delay=PT1H"
})
@Import(CertificationOperationsWorkerIT.AdapterConfiguration.class)
@AutoConfigureMockMvc
@Transactional
class CertificationOperationsWorkerIT {
    @Autowired
    private CertificationOperationsWorker worker;

    @Autowired
    private StubEmailAdapter email;

    @Autowired
    private StubF05Publisher f05;

    @Autowired
    private ConfirmationTokenCodec tokenCodec;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @BeforeEach
    void resetAdapter() {
        email.result.set(new CertificationEmailAdapter.SendResult(
            "SENT", "provider-message", "provider-thread", null, false));
        email.messages.clear();
        f05.result.set(new F05CertificationReadinessPublisher.PublishResult(
            "PUBLISHED", "certification.confirmation.readiness.v1",
            null, false));
        f05.facts.clear();
    }

    @Test
    void secureTokensAreEncryptedAtRestAndOnlyMaterializedForClaimedDispatch()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        worker.dispatchNotifications();
        email.messages.clear();

        var request = F04TestSupport.createConfirmationRequest(
            mvc, mapper, completed.monthVersion(),
            OffsetDateTime.now().plusDays(2), "secure-token-request");
        UUID requestId = UUID.fromString(request.path("id").asText());
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM confirmation_token_handoffs handoff
            JOIN confirmation_secure_tokens token ON token.id = handoff.token_id
            WHERE token.request_id = ? AND handoff.status = 'PENDING'
            """, Integer.class, requestId));

        assertTrue(worker.dispatchNotifications() >= 1);
        List<CertificationEmailAdapter.SecureActionLink> links =
            email.messages.stream()
                .flatMap(message -> message.secureActionLinks().stream())
                .toList();
        assertEquals(1, links.size());
        CertificationEmailAdapter.SecureActionLink link = links.getFirst();
        assertEquals(requestId, link.requestId());
        assertFalse(link.plaintextToken().isBlank());
        TokenMaterial material = jdbc.query("""
            SELECT token.token_hash, token.token_salt, token.work_factor,
                   encode(handoff.encrypted_token, 'base64') AS ciphertext,
                   handoff.status
            FROM confirmation_secure_tokens token
            JOIN confirmation_token_handoffs handoff
              ON handoff.token_id = token.id
            WHERE token.id = ?
            """, rs -> {
                rs.next();
                return new TokenMaterial(
                    rs.getString("token_hash"), rs.getString("token_salt"),
                    rs.getInt("work_factor"), rs.getString("ciphertext"),
                    rs.getString("status"));
            }, link.tokenId());
        assertTrue(tokenCodec.matches(
            link.plaintextToken(), material.hash(), material.salt(),
            material.workFactor()));
        assertEquals("DELIVERED", material.handoffStatus());
        assertFalse(material.ciphertext().contains(link.plaintextToken()));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT NOT EXISTS (
                SELECT 1 FROM notification_outbox
                WHERE plain_text LIKE '%' || ? || '%'
                   OR html_text LIKE '%' || ? || '%'
                   OR recipient_snapshot::text LIKE '%' || ? || '%'
            )
            """, Boolean.class, link.plaintextToken(),
            link.plaintextToken(), link.plaintextToken())));
    }

    @Test
    void authorizedReplayCreatesImmutableGenerationAndRedispatches()
        throws Exception {
        UUID outboxId = insertOutbox("worker-replay");
        email.result.set(new CertificationEmailAdapter.SendResult(
            "FAILED", null, null, "INVALID_RECIPIENT", false));
        assertEquals(1, worker.dispatchNotifications());
        assertEquals("DEAD_LETTER", outboxStatus(outboxId));
        long version = jdbc.queryForObject("""
            SELECT certification_version
            FROM engagement_months WHERE id = ?::uuid
            """, Long.class, MONTH);

        mvc.perform(post(
                    "/api/v1/certification/notifications/{id}/replays",
                    outboxId)
                .with(F04TestSupport.token("user-governance"))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key", "worker-replay-authorized")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedMonthVersion":%d,
                     "reason":"Provider recipient configuration was repaired."}
                    """.formatted(version)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.transportStatus").value("QUEUED"))
            .andExpect(jsonPath("$.replayNumber").value(1))
            .andExpect(jsonPath("$.totalAttemptCount").value(1));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM notification_outbox_replays
            WHERE outbox_id = ?
            """, Integer.class, outboxId));
        assertEquals(0, jdbc.queryForObject("""
            SELECT generation_attempt_count FROM notification_outbox
            WHERE id = ?
            """, Integer.class, outboxId));

        email.result.set(new CertificationEmailAdapter.SendResult(
            "SENT", "provider-replay", "provider-thread", null, false));
        assertEquals(1, worker.dispatchNotifications());
        assertEquals("SENT", outboxStatus(outboxId));
        assertEquals(2, attemptCount(outboxId));
    }

    @Test
    void confirmedRequestPublishesDurableF05FactWithRetryLineage()
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

        mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{id}/actions",
                    request.requestId())
                .with(F04TestSupport.token("user-reliance"))
                .header("If-Match", Integer.toString(request.version()))
                .header("Idempotency-Key", "f05-terminal-confirmation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":%d,"decision":"CONFIRM",
                     "projectId":"%s",
                     "comment":"Explicit terminal confirmation"}
                    """.formatted(request.version(), PROJECT_A)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CONFIRMED"));
        UUID jobId = jdbc.queryForObject("""
            SELECT job.id
            FROM f05_handoff_publish_jobs job
            JOIN f05_certification_handoffs handoff
              ON handoff.id = job.handoff_id
            WHERE handoff.confirmation_request_id = ?
            """, UUID.class, request.requestId());

        f05.result.set(new F05CertificationReadinessPublisher.PublishResult(
            "FAILED", "certification.confirmation.readiness.v1",
            "TEMPORARY_F05_FAILURE", true));
        assertEquals(1, worker.publishF05Handoffs());
        assertEquals("PENDING", f05JobStatus(jobId));
        jdbc.update("""
            UPDATE f05_handoff_publish_jobs
            SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
            WHERE id = ?
            """, jobId);
        f05.result.set(new F05CertificationReadinessPublisher.PublishResult(
            "PUBLISHED", "certification.confirmation.readiness.v1",
            null, false));
        assertEquals(1, worker.publishF05Handoffs());
        assertEquals("COMPLETED", f05JobStatus(jobId));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM f05_handoff_publish_attempts attempt
            JOIN f05_handoff_publish_jobs job
              ON job.handoff_id = attempt.handoff_id
            WHERE job.id = ?
            """, Integer.class, jobId));
        assertEquals("PUBLISHED", jdbc.queryForObject("""
            SELECT effective.effective_status
            FROM effective_f05_certification_handoffs effective
            JOIN f05_handoff_publish_jobs job
              ON job.handoff_id = effective.id
            WHERE job.id = ?
            """, String.class, jobId));
        assertEquals(2, f05.facts.size());
        assertEquals(request.requestId(),
            f05.facts.getFirst().confirmationRequestId());
        assertNotNull(f05.facts.getFirst().inputHash());
    }

    @Test
    void outboxRetriesThenSendsAndPermanentlyFailedDeliveryDeadLetters() {
        UUID retrying = insertOutbox("worker-retry");
        email.result.set(new CertificationEmailAdapter.SendResult(
            "FAILED", null, null, "PROVIDER_TIMEOUT", true));

        assertEquals(1, worker.dispatchNotifications());
        assertEquals("FAILED", outboxStatus(retrying));
        assertEquals(1, attemptCount(retrying));
        assertEquals("RETRYABLE_FAILURE", lastAttemptStatus(retrying));

        jdbc.update("""
            UPDATE notification_outbox
            SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
            WHERE id = ?
            """, retrying);
        email.result.set(new CertificationEmailAdapter.SendResult(
            "SENT", "provider-message", "provider-thread", null, false));
        assertEquals(1, worker.dispatchNotifications());
        assertEquals("SENT", outboxStatus(retrying));
        assertEquals(2, attemptCount(retrying));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM notification_delivery_attempts
            WHERE outbox_id = ?
            """, Integer.class, retrying));

        UUID permanent = insertOutbox("worker-permanent");
        email.result.set(new CertificationEmailAdapter.SendResult(
            "FAILED", null, null, "INVALID_RECIPIENT", false));
        assertEquals(1, worker.dispatchNotifications());
        assertEquals("DEAD_LETTER", outboxStatus(permanent));
        assertEquals("PERMANENT_FAILURE", lastAttemptStatus(permanent));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT dead_lettered_at IS NOT NULL
            FROM notification_outbox WHERE id = ?
            """, Boolean.class, permanent)));
    }

    @Test
    void dueReminderCompletesAndDueExpiryRevokesTokenWithServiceAudit()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        F04TestSupport.DirectConfirmation reminder =
            F04TestSupport.directConfirmation(
                jdbc, completed, "ANY_ONE", 1,
                OffsetDateTime.now().minusHours(1),
                OffsetDateTime.now().plusHours(2),
                List.of(new F04TestSupport.EligibleFixture(
                    "user-reliance", "ravi@reliance.example", PROJECT_A)));
        UUID reminderSchedule = insertSchedule(
            reminder.requestId(), "REMINDER");
        assertEquals(1, worker.processSchedules());
        assertEquals("COMPLETED", scheduleStatus(reminderSchedule));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM notification_outbox
            WHERE business_object_id = ?
              AND event_type = 'CONFIRMATION_REMINDER'
            """, Integer.class, reminder.requestId()));

        jdbc.update("""
            UPDATE business_confirmation_requests
            SET status = 'SUPERSEDED', optimistic_version = optimistic_version + 1
            WHERE id = ?
            """, reminder.requestId());
        F04TestSupport.DirectConfirmation expired =
            F04TestSupport.directConfirmation(
                jdbc, completed, "ANY_ONE", 1,
                OffsetDateTime.now().minusDays(2),
                OffsetDateTime.now().minusDays(1),
                List.of(new F04TestSupport.EligibleFixture(
                    "user-reliance", "ravi@reliance.example", PROJECT_A)));
        UUID tokenId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO confirmation_secure_tokens
                (id, request_id, request_version,
                 eligible_confirmer_subject, project_id,
                 token_hash, token_salt, hash_algorithm,
                 work_factor, expires_at, created_at)
            VALUES (?, ?, ?, 'user-reliance', ?::uuid,
                    'encoded-hash', 'encoded-salt',
                    'PBKDF2-HMAC-SHA256', 120000,
                    CURRENT_TIMESTAMP - INTERVAL '36 hours',
                    CURRENT_TIMESTAMP - INTERVAL '47 hours')
            """, tokenId, expired.requestId(), expired.version(), PROJECT_A);
        UUID expirySchedule = insertSchedule(expired.requestId(), "EXPIRY");

        assertEquals(1, worker.processSchedules());
        assertEquals("EXPIRED", requestStatus(expired.requestId()));
        assertEquals("COMPLETED", scheduleStatus(expirySchedule));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM confirmation_token_revocations
            WHERE token_id = ?
            """, Integer.class, tokenId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM certification_domain_events
            WHERE subject_id = ?
              AND event_type = 'confirmation.expired.v1'
              AND actor_type = 'SERVICE'
            """, Integer.class, expired.requestId()));
    }

    private UUID insertOutbox(String key) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO notification_outbox
                (id, engagement_month_id, event_type,
                 business_object_type, business_object_id,
                 business_object_version, idempotency_key,
                 correlation_id, template_key, template_version,
                 recipient_snapshot, subject_text, plain_text, html_text,
                 rendered_body_hash, archive_manifest_hash,
                 provider_status, transport_status, next_attempt_at)
            VALUES (?, ?::uuid, 'WORKER_TEST', 'worker_test',
                    gen_random_uuid(), 1, ?, gen_random_uuid(),
                    'worker-test-v1', 1,
                    '{"to":[],"cc":[]}'::jsonb,
                    'subject', 'plain', '<p>html</p>',
                    repeat('a',64), repeat('b',64),
                    'CONFIGURED', 'QUEUED',
                    CURRENT_TIMESTAMP - INTERVAL '1 second')
            """, id, MONTH, key);
        return id;
    }

    private UUID insertSchedule(UUID requestId, String type) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO confirmation_request_schedules
                (id, request_id, schedule_type, sequence_number,
                 due_at, status, next_attempt_at)
            VALUES (?, ?, ?, 99,
                    CURRENT_TIMESTAMP - INTERVAL '1 second',
                    'PENDING', CURRENT_TIMESTAMP - INTERVAL '1 second')
            """, id, requestId, type);
        return id;
    }

    private String outboxStatus(UUID id) {
        return jdbc.queryForObject("""
            SELECT transport_status FROM notification_outbox WHERE id = ?
            """, String.class, id);
    }

    private int attemptCount(UUID id) {
        return jdbc.queryForObject("""
            SELECT attempt_count FROM notification_outbox WHERE id = ?
            """, Integer.class, id);
    }

    private String requestStatus(UUID id) {
        return jdbc.queryForObject("""
            SELECT status FROM business_confirmation_requests WHERE id = ?
            """, String.class, id);
    }

    private String lastAttemptStatus(UUID id) {
        return jdbc.queryForObject("""
            SELECT status FROM notification_delivery_attempts
            WHERE outbox_id = ? ORDER BY attempt_number DESC LIMIT 1
            """, String.class, id);
    }

    private String scheduleStatus(UUID id) {
        return jdbc.queryForObject("""
            SELECT status FROM confirmation_request_schedules WHERE id = ?
            """, String.class, id);
    }

    private String f05JobStatus(UUID id) {
        return jdbc.queryForObject("""
            SELECT status FROM f05_handoff_publish_jobs WHERE id = ?
            """, String.class, id);
    }

    @TestConfiguration
    static class AdapterConfiguration {
        @Bean
        @Primary
        StubEmailAdapter stubEmailAdapter() {
            return new StubEmailAdapter();
        }

        @Bean
        @Primary
        StubF05Publisher stubF05Publisher() {
            return new StubF05Publisher();
        }
    }

    static final class StubEmailAdapter implements CertificationEmailAdapter {
        private final AtomicReference<SendResult> result =
            new AtomicReference<>();
        private final List<OutboundMessage> messages =
            new CopyOnWriteArrayList<>();

        @Override
        public String configurationStatus() {
            return "CONFIGURED";
        }

        @Override
        public SendResult send(OutboundMessage message) {
            messages.add(message);
            return result.get();
        }
    }

    static final class StubF05Publisher
        implements F05CertificationReadinessPublisher {
        private final AtomicReference<PublishResult> result =
            new AtomicReference<>();
        private final List<ReadinessFact> facts =
            new CopyOnWriteArrayList<>();

        @Override
        public String configurationStatus() {
            return "CONFIGURED";
        }

        @Override
        public PublishResult publish(ReadinessFact fact) {
            facts.add(fact);
            return result.get();
        }
    }

    private record TokenMaterial(
        String hash,
        String salt,
        int workFactor,
        String ciphertext,
        String handoffStatus
    ) {
    }
}
