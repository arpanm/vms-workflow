package com.vms.workflow.integration;

import com.vms.workflow.application.ConfirmationTokenCodec;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.vms.workflow.integration.F04TestSupport.PROJECT_A;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_certification_clock_it",
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
@Import(CertificationClockIT.ClockTestConfiguration.class)
@Transactional
class CertificationClockIT {
    private static final OffsetDateTime BASE =
        OffsetDateTime.parse("2026-07-27T10:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConfirmationTokenCodec tokenCodec;

    @Autowired
    private MutableTestClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(BASE);
    }

    @Test
    void confirmationIsAcceptedOneNanosecondBeforeCapturedDueTime()
        throws Exception {
        F04TestSupport.DirectConfirmation request =
            requestDueAt(BASE.plusMinutes(1));
        clock.set(request.dueAt().minusNanos(1));

        performAction(request, "before-due", null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CONFIRMED"));
        assertEquals(1, actionCount(request));
    }

    @Test
    void confirmationExpiresExactlyAtCapturedDueTime() throws Exception {
        F04TestSupport.DirectConfirmation request =
            requestDueAt(BASE.plusMinutes(1));
        clock.set(request.dueAt());

        performAction(request, "at-due", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONFIRMATION_EXPIRED"));
        assertExpiredWithoutAction(request);
    }

    @Test
    void confirmationExpiresOneNanosecondAfterCapturedDueTime()
        throws Exception {
        F04TestSupport.DirectConfirmation request =
            requestDueAt(BASE.plusMinutes(1));
        clock.set(request.dueAt().plusNanos(1));

        performAction(request, "after-due", null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CONFIRMATION_EXPIRED"));
        assertExpiredWithoutAction(request);
    }

    @Test
    void secureTokenIsAcceptedOneNanosecondBeforeItsExpiry()
        throws Exception {
        F04TestSupport.DirectConfirmation request =
            requestDueAt(BASE.plusDays(2));
        OffsetDateTime tokenExpiry = BASE.plusMinutes(1);
        ConfirmationTokenCodec.IssuedToken issued =
            issueToken(request, tokenExpiry);
        clock.set(tokenExpiry.minusNanos(1));

        performAction(request, "before-token-expiry", issued.plaintext())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CONFIRMED"));
        assertEquals(1, actionCount(request));
    }

    @Test
    void secureTokenIsRejectedExactlyAtItsExpiryWithoutConsumption()
        throws Exception {
        F04TestSupport.DirectConfirmation request =
            requestDueAt(BASE.plusDays(2));
        OffsetDateTime tokenExpiry = BASE.plusMinutes(1);
        ConfirmationTokenCodec.IssuedToken issued =
            issueToken(request, tokenExpiry);
        clock.set(tokenExpiry);

        performAction(request, "at-token-expiry", issued.plaintext())
            .andExpect(status().isNotFound());
        assertEquals(0, actionCount(request));
        assertNull(jdbc.queryForObject("""
            SELECT consumed_at
            FROM confirmation_secure_tokens
            WHERE request_id = ?
            """, OffsetDateTime.class, request.requestId()));
    }

    private F04TestSupport.DirectConfirmation requestDueAt(
        OffsetDateTime dueAt
    ) throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        return F04TestSupport.directConfirmation(
            jdbc, completed, "ANY_ONE", 1, BASE, dueAt,
            List.of(new F04TestSupport.EligibleFixture(
                "user-reliance", "ravi@reliance.example", PROJECT_A)));
    }

    private ConfirmationTokenCodec.IssuedToken issueToken(
        F04TestSupport.DirectConfirmation request,
        OffsetDateTime expiresAt
    ) {
        ConfirmationTokenCodec.IssuedToken issued = tokenCodec.issue();
        jdbc.update("""
            INSERT INTO confirmation_secure_tokens
                (id, request_id, request_version,
                 eligible_confirmer_subject, token_hash, token_salt,
                 hash_algorithm, work_factor, expires_at, created_at)
            VALUES (?, ?, ?, 'user-reliance', ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), request.requestId(), request.version(),
            issued.encodedHash(), issued.encodedSalt(), issued.algorithm(),
            issued.workFactor(), expiresAt, BASE);
        return issued;
    }

    private org.springframework.test.web.servlet.ResultActions performAction(
        F04TestSupport.DirectConfirmation request,
        String key,
        String secureToken
    ) throws Exception {
        String tokenField = secureToken == null
            ? "" : ",\"secureToken\":\"" + secureToken + "\"";
        return mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{requestId}/actions",
                    request.requestId())
                .with(token("user-reliance"))
                .header("If-Match", Integer.toString(request.version()))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":1,"decision":"CONFIRM"%s}
                    """.formatted(tokenField)));
    }

    private void assertExpiredWithoutAction(
        F04TestSupport.DirectConfirmation request
    ) {
        assertEquals("EXPIRED", jdbc.queryForObject("""
            SELECT status
            FROM business_confirmation_requests
            WHERE id = ?
            """, String.class, request.requestId()));
        assertEquals(0, actionCount(request));
    }

    private int actionCount(F04TestSupport.DirectConfirmation request) {
        return jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM business_confirmation_actions
            WHERE request_id = ?
            """, Integer.class, request.requestId());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockTestConfiguration {
        @Bean
        @Primary
        MutableTestClock certificationMutableTestClock() {
            return new MutableTestClock(BASE.toInstant());
        }
    }

    static final class MutableTestClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private MutableTestClock(Instant initial) {
            this(new AtomicReference<>(initial), ZoneOffset.UTC);
        }

        private MutableTestClock(
            AtomicReference<Instant> current,
            ZoneId zone
        ) {
            this.current = current;
            this.zone = zone;
        }

        void set(OffsetDateTime value) {
            current.set(value.toInstant());
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return requestedZone.equals(zone)
                ? this : new MutableTestClock(current, requestedZone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
