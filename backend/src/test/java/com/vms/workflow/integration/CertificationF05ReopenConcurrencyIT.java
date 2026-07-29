package com.vms.workflow.integration;

import com.vms.workflow.application.CertificationOperationsWorker;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.PROJECT_A;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_f04_f05_reopen",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.certification.email-provider-status=NOT_CONFIGURED",
    "vms.certification.worker-enabled=true",
    "vms.certification.f05-handoff-status=CONFIGURED",
    "vms.certification.worker-initial-delay=PT1H"
})
@Import(CertificationF05ReopenConcurrencyIT.AdapterConfiguration.class)
@AutoConfigureMockMvc
class CertificationF05ReopenConcurrencyIT {
    @Autowired
    private CertificationOperationsWorker worker;

    @Autowired
    private RecordingF05Publisher publisher;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetPublisher() {
        publisher.facts.clear();
    }

    @Test
    void approvedReopenSerializesAgainstExpiredClaimReclaimAndTombstonesJob()
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
                .with(token("user-reliance"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "race-confirmation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":1,"decision":"CONFIRM",
                     "projectId":"%s","comment":"Race-ready confirmation"}
                    """.formatted(PROJECT_A)))
            .andExpect(status().isOk());
        UUID jobId = jdbc.queryForObject("""
            SELECT job.id
            FROM f05_handoff_publish_jobs job
            JOIN f05_certification_handoffs handoff
              ON handoff.id = job.handoff_id
            WHERE handoff.confirmation_request_id = ?
            """, UUID.class, request.requestId());
        jdbc.update("""
            UPDATE f05_handoff_publish_jobs
            SET status = 'CLAIMED', lease_owner = 'expired-worker',
                lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
            WHERE id = ?
            """, jobId);

        long confirmedVersion = monthVersion();
        mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/reopen-requests",
                    MONTH)
                .with(token("user-reliance"))
                .header("If-Match", Long.toString(confirmedVersion))
                .header("Idempotency-Key", "race-reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "category":"CERTIFICATION_CORRECTION",
                      "reason":"The published package source requires correction.",
                      "impactedRecordIds":["%s"],
                      "packageInvoiceImpact":"NOT_SUBMITTED",
                      "riskStatement":"The stale handoff must be fenced."
                    }
                    """.formatted(confirmedVersion, completed.summaryId())))
            .andExpect(status().isCreated());
        UUID reopenId = jdbc.queryForObject("""
            SELECT id FROM month_reopen_requests
            WHERE engagement_month_id = ?::uuid
            """, UUID.class, MONTH);
        long pendingVersion = monthVersion();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var publishing = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return worker.publishF05Handoffs();
            });
            var reopening = executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return mvc.perform(post(
                            "/api/v1/certification/reopen-requests/{id}/decisions",
                            reopenId)
                        .with(token("user-governance"))
                        .header("If-Match", Long.toString(pendingVersion))
                        .header("Idempotency-Key", "race-reopen-approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {
                              "expectedMonthVersion":%d,
                              "decision":"APPROVE",
                              "reasoning":"Independent approval fences stale F05 work."
                            }
                            """.formatted(pendingVersion)))
                    .andReturn().getResponse().getStatus();
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(publishing.get(30, TimeUnit.SECONDS) <= 1);
            assertEquals(201, reopening.get(30, TimeUnit.SECONDS));
        }

        assertEquals("CANCELLED", jdbc.queryForObject("""
            SELECT status FROM f05_handoff_publish_jobs WHERE id = ?
            """, String.class, jobId));
        assertNotNull(jdbc.queryForObject("""
            SELECT cancellation_invalidation_id
            FROM f05_handoff_publish_jobs WHERE id = ?
            """, UUID.class, jobId));
        assertEquals("INVALIDATED", jdbc.queryForObject("""
            SELECT effective.effective_status
            FROM effective_f05_certification_handoffs effective
            JOIN f05_handoff_publish_jobs job
              ON job.handoff_id = effective.id
            WHERE job.id = ?
            """, String.class, jobId));
        int publishedBeforeFinalFence = publisher.facts.size();
        assertEquals(0, worker.publishF05Handoffs());
        assertEquals(publishedBeforeFinalFence, publisher.facts.size());
    }

    private long monthVersion() {
        return jdbc.queryForObject("""
            SELECT certification_version
            FROM engagement_months WHERE id = ?::uuid
            """, Long.class, MONTH);
    }

    @TestConfiguration
    static class AdapterConfiguration {
        @Bean
        @Primary
        RecordingF05Publisher recordingF05Publisher() {
            return new RecordingF05Publisher();
        }
    }

    static class RecordingF05Publisher
        implements F05CertificationReadinessPublisher {
        private final List<ReadinessFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public String configurationStatus() {
            return "CONFIGURED";
        }

        @Override
        public PublishResult publish(ReadinessFact fact) {
            facts.add(fact);
            return new PublishResult(
                "PUBLISHED",
                "certification.confirmation.readiness.v1",
                null,
                false);
        }
    }
}
