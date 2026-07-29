package com.vms.workflow.integration;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_f04_security",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.certification.rate-limit.actions-per-minute=1",
    "vms.certification.worker-initial-delay=PT1H"
})
@AutoConfigureMockMvc
class CertificationSecurityHardeningIT {
    private static final UUID UNKNOWN_REQUEST = UUID.fromString(
        "ffffffff-ffff-ffff-ffff-fffffffffff4");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void ambientCookieCannotAuthorizeBearerOnlyApiAndDenialIsAudited()
        throws Exception {
        UUID correlation = UUID.fromString(
            "c0400000-0000-0000-0000-000000000401");
        String response = mvc.perform(
                get("/api/v1/certification/months/{monthId}", MONTH)
                    .cookie(new Cookie("SESSION", "ambient-cookie-is-not-auth"))
                    .header("X-Correlation-Id", correlation))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(header().string(
                "X-Correlation-Id", correlation.toString()))
            .andExpect(jsonPath("$.correlationId")
                .value(correlation.toString()))
            .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains("ambient-cookie-is-not-auth"));
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM certification_security_events
            WHERE event_type = 'HTTP_AUTHENTICATION_DENIED'
              AND correlation_id = ?
              AND outcome = 'DENIED'
              AND redacted_facts ->> 'reasonCode' =
                  'AUTHENTICATION_REQUIRED'
            """, correlation));
    }

    @Test
    void confirmationActionRateLimitIsPerIdentityAndAuditedWithoutRawSubject()
        throws Exception {
        assertEquals(404, confirmationAction("rate-limited-subject"));
        String response = mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{requestId}/actions",
                    UNKNOWN_REQUEST)
                .with(token("rate-limited-subject"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "rate-second")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":1,"decision":"CONFIRM"}
                    """))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string("Retry-After", "60"))
            .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail")
                .value("The request rate limit was exceeded. Retry later."))
            .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains("rate-limited-subject"));
        String actorHash = jdbc.queryForObject("""
            SELECT actor_subject_hash
            FROM certification_security_events
            WHERE event_type = 'F04_RATE_LIMIT_EXCEEDED'
            ORDER BY occurred_at DESC
            LIMIT 1
            """, String.class);
        assertEquals(64, actorHash.length());
        assertNotEquals("rate-limited-subject", actorHash);
        assertEquals(1, count("""
            SELECT COUNT(*)
            FROM certification_security_events
            WHERE event_type = 'F04_RATE_LIMIT_EXCEEDED'
              AND outcome = 'DENIED'
              AND redacted_facts ->> 'operation' =
                  'CONFIRMATION_ACTION'
              AND redacted_facts ->> 'reasonCode' =
                  'RATE_LIMIT_EXCEEDED'
            """));
    }

    @Test
    void validationErrorNeverEchoesRestrictedRequestFields()
        throws Exception {
        String restrictedEmail = "restricted-person@example.test";
        String raw = mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/submissions", MONTH)
                .with(token("user-arrow"))
                .header("If-Match", "0")
                .header("Idempotency-Key", "redaction-validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":-1,
                      "summary":"restricted-person@example.test",
                      "declarationAccepted":true,
                      "items":[]
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value(
                "The request syntax, headers or validated fields are invalid."))
            .andReturn().getResponse().getContentAsString();

        JsonNode problem = mapper.readTree(raw);
        assertTrue(problem.hasNonNull("correlationId"));
        assertFalse(raw.contains(restrictedEmail));
    }

    private int confirmationAction(String subject) throws Exception {
        return mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{requestId}/actions",
                    UNKNOWN_REQUEST)
                .with(token(subject))
                .header("If-Match", "1")
                .header("Idempotency-Key", "rate-first")
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
