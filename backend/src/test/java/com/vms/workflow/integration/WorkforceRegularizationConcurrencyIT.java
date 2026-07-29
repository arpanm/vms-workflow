package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_regularization_concurrency",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api"
})
@AutoConfigureMockMvc
class WorkforceRegularizationConcurrencyIT {
    private static final String EMPLOYEE =
        "00000000-0000-0000-0000-000000000801";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void concurrentCorrectionsSerializeIntoCompleteVersionedLineage()
        throws Exception {
        String firstId = create("regularization-concurrent-a");
        String secondId = create("regularization-concurrent-b");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() ->
                decide(firstId, 480, ready, start));
            Future<?> second = executor.submit(() ->
                decide(secondId, 510, ready, start));
            assertTrue(ready.await(
                Duration.ofSeconds(10).toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS));
            start.countDown();
            first.get(Duration.ofSeconds(30).toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
            second.get(Duration.ofSeconds(30).toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        assertEquals(List.of(1, 2), jdbc.queryForList("""
            SELECT adjustment_version
            FROM attendance_regularization_adjustments
            WHERE employee_id = ?::uuid AND work_date = DATE '2026-07-13'
            ORDER BY adjustment_version
            """, Integer.class, EMPLOYEE));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM attendance_regularization_adjustments current
            JOIN attendance_regularization_adjustments prior
              ON prior.id = current.supersedes_adjustment_id
            WHERE current.employee_id = ?::uuid
              AND current.work_date = DATE '2026-07-13'
              AND current.adjustment_version = 2
              AND prior.adjustment_version = 1
            """, Integer.class, EMPLOYEE));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM attendance_regularization_decisions decision
            JOIN attendance_regularizations request
              ON request.id = decision.regularization_id
            WHERE request.employee_id = ?::uuid
              AND request.work_date = DATE '2026-07-13'
            """, Integer.class, EMPLOYEE));
        assertEquals(2, jdbc.queryForObject("""
            SELECT COUNT(*) FROM attendance_regularization_adjustments
            WHERE employee_id = ?::uuid AND work_date = DATE '2026-07-13'
            """, Integer.class, EMPLOYEE));
        Integer latestMinutes = jdbc.queryForObject("""
            SELECT adjusted_net_minutes
            FROM attendance_regularization_adjustments
            WHERE employee_id = ?::uuid AND work_date = DATE '2026-07-13'
            ORDER BY adjustment_version DESC LIMIT 1
            """, Integer.class, EMPLOYEE);
        assertEquals(latestMinutes, jdbc.queryForObject("""
            SELECT net_minutes FROM attendance_days
            WHERE employee_id = ?::uuid AND work_date = DATE '2026-07-13'
              AND is_current
            """, Integer.class, EMPLOYEE));
    }

    private String create(String idempotencyKey) throws Exception {
        JsonNode response = mapper.readTree(mvc.perform(post(
                    "/api/v1/attendance/regularizations")
                .with(token("user-employee"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "employeeId":"%s",
                      "workDate":"2026-07-13",
                      "reasonCode":"CONCURRENT_CORRECTION",
                      "narrative":"Independent evidence review",
                      "requestedOutcome":"CORRECT_MINUTES",
                      "idempotencyKey":"%s"
                    }
                    """.formatted(EMPLOYEE, idempotencyKey)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        return response.path("id").asText();
    }

    private void decide(
        String regularizationId,
        int minutes,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        try {
            ready.countDown();
            assertTrue(start.await(
                Duration.ofSeconds(10).toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS));
            mvc.perform(post(
                        "/api/v1/attendance/regularizations/{id}/decisions",
                        regularizationId)
                    .with(token("user-arrow"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "decision":"APPROVE",
                          "adjustedNetMinutes":%d,
                          "reasoning":"Concurrent governed correction"
                        }
                        """.formatted(minutes)))
                .andExpect(status().isCreated());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor token(
        String subject
    ) {
        return jwt().jwt(value -> value
            .subject(subject)
            .audience(List.of("vms-api")));
    }
}
