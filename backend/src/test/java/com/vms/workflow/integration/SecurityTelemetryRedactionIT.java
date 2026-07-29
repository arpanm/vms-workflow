package com.vms.workflow.integration;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.application.CertificationSecurityEventService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api"
})
@AutoConfigureMockMvc
@Import(SecurityTelemetryRedactionIT.RedactionProbeController.class)
class SecurityTelemetryRedactionIT {
    private static final UUID OBJECT_ID = UUID.fromString(
        "9b6dd424-260d-4b57-a1b8-8ec220b4eb1a");
    private static final List<String> SECRETS = List.of(
        "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJwcml2YXRlIn0.c2lnbmF0dXJl",
        "session-cookie-secret",
        "provider-password",
        "webhook-provider-secret",
        "private.person@example.test",
        "raw/private-person/message.eml",
        "restricted/tenant/invoices/invoice.pdf",
        "123412341234"
    );

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void f07Sup003_successErrorAndRetryDiagnosticsRemainRedactedAndCorrelated()
        throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            var success = invoke("SUCCESS");
            var retry = invoke("RETRY");
            var error = invoke("ERROR");

            assertEquals(200, success.getStatus());
            assertEquals(409, retry.getStatus());
            assertEquals(500, error.getStatus());
            for (var response : List.of(success, retry, error)) {
                UUID.fromString(response.getHeader("X-Correlation-Id"));
                assertNoSecrets(response.getContentAsString());
            }

            String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
            assertNoSecrets(logs);

            List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT redacted_facts::text AS facts,
                       actor_subject_hash, correlation_id, outcome
                FROM certification_security_events
                WHERE object_id = ?
                ORDER BY occurred_at
                """, OBJECT_ID);
            assertEquals(3, events.size());
            assertEquals(
                List.of("SUCCESS", "RETRY", "ERROR"),
                events.stream().map(row -> row.get("outcome")).toList());
            for (Map<String, Object> event : events) {
                String rendered = event.toString();
                assertNoSecrets(rendered);
                assertTrue(String.valueOf(event.get("actor_subject_hash"))
                    .matches("[0-9a-f]{64}"));
                UUID.fromString(String.valueOf(event.get("correlation_id")));
            }
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
    }

    private MockHttpServletResponse invoke(String outcome)
        throws Exception {
        return mvc.perform(get("/api/v1/redaction-probe/{outcome}/{id}",
                outcome, OBJECT_ID)
                .with(token("user-arrow"))
                .header("X-Provider-Secret", "webhook-provider-secret")
                .header("Cookie", "SESSION=session-cookie-secret")
                .accept(MediaType.APPLICATION_JSON))
            .andReturn().getResponse();
    }

    private void assertNoSecrets(String value) {
        for (String secret : SECRETS) {
            assertFalse(value.contains(secret), secret);
        }
    }

    @RestController
    @RequestMapping("/api/v1/redaction-probe")
    static class RedactionProbeController {
        private final CertificationSecurityEventService securityEvents;

        RedactionProbeController(CertificationSecurityEventService securityEvents) {
            this.securityEvents = securityEvents;
        }

        @GetMapping("/{outcome}/{id}")
        Map<String, String> probe(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable String outcome,
            @PathVariable UUID id
        ) {
            String corpus = """
                Authorization: Bearer %s Cookie: SESSION=%s
                password=%s webhook_secret=%s
                sender=%s %s %s aadhaar=%s
                """.formatted(
                SECRETS.get(0), SECRETS.get(1), SECRETS.get(2),
                SECRETS.get(3), SECRETS.get(4), SECRETS.get(5),
                SECRETS.get(6), SECRETS.get(7));
            securityEvents.recordBestEffort(
                null, "F07_REDACTION_" + outcome, jwt.getSubject(),
                "F07_REDACTION_PROBE", id, outcome, "SYNTHETIC_" + outcome,
                Map.of(
                    "diagnostic", corpus,
                    "webhook_secret", SECRETS.get(3),
                    "retryAttempt", outcome.equals("RETRY") ? 2 : 1));
            if (outcome.equals("RETRY")) {
                throw new DomainConflictException(corpus);
            }
            if (outcome.equals("ERROR")) {
                throw new IllegalStateException(corpus);
            }
            return Map.of("result", "SUCCESS");
        }
    }
}
