package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.vms.workflow.integration.F04TestSupport.PROJECT_A;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_f04_concurrency",
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
class CertificationConcurrencyIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentCommittedQuorumCreatesExactlyOneTerminalOutcomeAndF05Handoff()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        OffsetDateTime now = OffsetDateTime.now();
        F04TestSupport.DirectConfirmation request =
            F04TestSupport.directConfirmation(
                jdbc, completed, "ALL", 2, now.minusMinutes(1),
                now.plusDays(2),
                List.of(
                    new F04TestSupport.EligibleFixture(
                        "user-reliance", "ravi@reliance.example", PROJECT_A),
                    new F04TestSupport.EligibleFixture(
                        "user-project-b",
                        "project-b-owner@reliance.example", PROJECT_A)));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> action(
                request, "user-reliance", "concurrent-quorum-first",
                ready, start));
            var second = executor.submit(() -> action(
                request, "user-project-b", "concurrent-quorum-second",
                ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(200, first.get(30, TimeUnit.SECONDS));
            assertEquals(200, second.get(30, TimeUnit.SECONDS));
        }

        assertEquals("CONFIRMED", jdbc.queryForObject("""
            SELECT status
            FROM business_confirmation_requests
            WHERE id = ?
            """, String.class, request.requestId()));
        assertEquals(2, count("""
            SELECT COUNT(*)
            FROM business_confirmation_actions
            WHERE request_id = ?
            """, request.requestId()));
        assertEquals(2, count("""
            SELECT COUNT(*)
            FROM certification_domain_events
            WHERE subject_type = 'business_confirmation_action'
              AND subject_id IN (
                  SELECT id
                  FROM business_confirmation_actions
                  WHERE request_id = ?
              )
              AND event_type = 'confirmation.action.recorded.v1'
            """, request.requestId()));
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM notification_outbox
            WHERE business_object_id = ?
              AND event_type = 'CONFIRMATION_OUTCOME_RECORDED'
            """, request.requestId()));
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM f05_certification_handoffs
            WHERE confirmation_request_id = ?
            """, request.requestId()));
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM f05_handoff_publish_jobs job
            JOIN f05_certification_handoffs handoff
              ON handoff.id = job.handoff_id
            WHERE handoff.confirmation_request_id = ?
            """, request.requestId()));

        mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{requestId}/actions",
                    request.requestId())
                .with(token("user-reliance"))
                .header("If-Match", Integer.toString(request.version()))
                .header("Idempotency-Key", "concurrent-quorum-first")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":1,"decision":"CONFIRM"}
                    """))
            .andExpect(status().isOk());
        assertEquals(2, count("""
            SELECT COUNT(*)
            FROM business_confirmation_actions
            WHERE request_id = ?
            """, request.requestId()));
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM notification_outbox
            WHERE business_object_id = ?
              AND event_type = 'CONFIRMATION_OUTCOME_RECORDED'
            """, request.requestId()));
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM f05_certification_handoffs
            WHERE confirmation_request_id = ?
            """, request.requestId()));
    }

    private int action(
        F04TestSupport.DirectConfirmation request,
        String subject,
        String idempotencyKey,
        CountDownLatch ready,
        CountDownLatch start
    ) throws Exception {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException(
                "Concurrent confirmation start barrier timed out.");
        }
        return mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{requestId}/actions",
                    request.requestId())
                .with(token(subject))
                .header("If-Match", Integer.toString(request.version()))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":1,"decision":"CONFIRM"}
                    """))
            .andReturn().getResponse().getStatus();
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
