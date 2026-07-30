package com.vms.workflow.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_certification_operations_inbox_it",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.certification.email-provider-status=CONFIGURED",
    "vms.certification.token-handoff-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@AutoConfigureMockMvc
@Transactional
class CertificationOperationsInboxIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void inboxIsServerScopedAndOperationsExposeOnlySafeDurableWork()
        throws Exception {
        mvc.perform(get("/api/v1/certification/inbox")
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.monthId == '%s')]"
                .formatted(MONTH)).exists())
            .andExpect(jsonPath("$.items[0].actionPath").isString());

        mvc.perform(get("/api/v1/certification/inbox")
                .with(token("user-northstar")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[?(@.monthId == '%s')]"
                .formatted(MONTH)).doesNotExist());

        F04TestSupport.completedCertification(mvc, mapper, jdbc);
        jdbc.update("""
            UPDATE notification_outbox
            SET transport_status = 'FAILED',
                next_attempt_at = CURRENT_TIMESTAMP,
                last_error_code = 'RECORDED_PROVIDER_TIMEOUT'
            WHERE id = (
                SELECT id
                FROM notification_outbox
                WHERE engagement_month_id = ?::uuid
                  AND business_object_type <> 'confirmation_secure_token'
                ORDER BY created_at, id
                LIMIT 1
            )
            """, MONTH);

        mvc.perform(get("/api/v1/certification/operations")
                .with(token("user-engagement-admin")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providerConfiguration").value(
                "CONFIGURED"))
            .andExpect(jsonPath(
                "$.actionableItems[?(@.safeErrorCode == 'RECORDED_PROVIDER_TIMEOUT')]")
                .exists())
            .andExpect(jsonPath(
                "$.actionableItems[?(@.replayAllowed == true)]").exists())
            .andExpect(jsonPath("$.actionableItems[0].monthVersion").isNumber());

        mvc.perform(get("/api/v1/certification/operations")
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.actionableItems").isEmpty());
    }
}
