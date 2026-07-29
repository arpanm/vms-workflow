package com.vms.workflow.integration;

import com.vms.workflow.application.FinanceOperationsWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.ENGAGEMENT;
import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    "vms.finance.local-scanner-enabled=false",
    "vms.finance.worker-enabled=true",
    "vms.finance.worker-initial-delay=PT1H"
})
@AutoConfigureMockMvc
@Transactional
class FinanceExportRetryIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FinanceOperationsWorker worker;

    @Test
    void scanFailureRetriesToDeadLetterAndCanBeExplicitlyReplayed()
        throws Exception {
        String response = mvc.perform(post("/api/v1/finance/exports")
                .with(token("user-procurement"))
                .header("Idempotency-Key", "retry-export")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reportId":"INVOICE_READINESS",
                      "reportVersion":"v1",
                      "format":"CSV",
                      "temporalMode":"CURRENT",
                      "filters":{
                        "engagementId":"%s",
                        "monthId":"%s"
                      },
                      "reason":"Exercise bounded worker retry"
                    }
                    """.formatted(ENGAGEMENT, MONTH)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        UUID exportId = UUID.fromString(
            mapper.readTree(response).path("exportId").asText());

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(1, worker.processExports());
            String expected = attempt < 5 ? "FAILED" : "DEAD_LETTER";
            assertEquals(expected, jdbc.queryForObject("""
                SELECT status FROM f05_report_exports WHERE id = ?
                """, String.class, exportId));
            assertEquals(attempt, jdbc.queryForObject("""
                SELECT attempt_count FROM f05_report_exports WHERE id = ?
                """, Integer.class, exportId));
            assertNotNull(jdbc.queryForObject("""
                SELECT last_error_code FROM f05_report_exports WHERE id = ?
                """, String.class, exportId));
            if (attempt < 5) {
                jdbc.update("""
                    UPDATE f05_report_exports
                    SET next_attempt_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, exportId);
            }
        }

        worker.replayExport(exportId);
        assertEquals("PENDING", jdbc.queryForObject("""
            SELECT status FROM f05_report_exports WHERE id = ?
            """, String.class, exportId));
        assertEquals(5, jdbc.queryForObject("""
            SELECT attempt_count FROM f05_report_exports WHERE id = ?
            """, Integer.class, exportId));
        assertEquals(0, jdbc.queryForObject("""
            SELECT retry_cycle_attempt_count
            FROM f05_report_exports WHERE id = ?
            """, Integer.class, exportId));

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertEquals(1, worker.processExports());
            assertEquals(attempt < 5 ? "FAILED" : "DEAD_LETTER",
                jdbc.queryForObject("""
                    SELECT status FROM f05_report_exports WHERE id = ?
                    """, String.class, exportId));
            assertEquals(5 + attempt, jdbc.queryForObject("""
                SELECT attempt_count FROM f05_report_exports WHERE id = ?
                """, Integer.class, exportId));
            assertEquals(attempt, jdbc.queryForObject("""
                SELECT retry_cycle_attempt_count
                FROM f05_report_exports WHERE id = ?
                """, Integer.class, exportId));
            if (attempt < 5) {
                jdbc.update("""
                    UPDATE f05_report_exports
                    SET next_attempt_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, exportId);
            }
        }
    }
}
