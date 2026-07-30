package com.vms.workflow.integration;

import com.vms.workflow.application.FinanceOperationsWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.vms.workflow.integration.F04TestSupport.ENGAGEMENT;
import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
    "vms.finance.local-scanner-enabled=true",
    "vms.finance.worker-enabled=true",
    "vms.finance.worker-initial-delay=PT1H"
})
@AutoConfigureMockMvc
class FinanceCommittedConcurrencyIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FinanceOperationsWorker worker;

    @Test
    void concurrentWorkersCommitOneExportArtifactAndEvent()
        throws Exception {
        String idempotencyKey = "committed-export-race-" + UUID.randomUUID();
        String response = mvc.perform(post("/api/v1/finance/exports")
                .with(token("user-procurement"))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reportId":"INVOICE_READINESS",
                      "reportVersion":"v1",
                      "format":"JSON",
                      "temporalMode":"CURRENT",
                      "filters":{
                        "engagementId":"%s",
                        "monthId":"%s"
                      },
                      "reason":"Committed concurrent worker verification"
                    }
                    """.formatted(ENGAGEMENT, MONTH)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        UUID exportId = UUID.fromString(
            mapper.readTree(response).path("exportId").asText());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = executor.submit(
                () -> processAfterBarrier(ready, start));
            Future<Integer> second = executor.submit(
                () -> processAfterBarrier(ready, start));
            ready.await();
            start.countDown();
            assertEquals(1, first.get() + second.get());
        }

        assertEquals("READY", jdbc.queryForObject("""
            SELECT status FROM f05_report_exports WHERE id = ?
            """, String.class, exportId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM f05_report_exports export
            JOIN f05_private_artifacts artifact
              ON artifact.id = export.result_artifact_id
            JOIN f05_private_artifact_blobs blob
              ON blob.artifact_id = artifact.id
            WHERE export.id = ?
            """, Integer.class, exportId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM f05_domain_events
            WHERE aggregate_id = ?
              AND event_type = 'f05.export.ready.v1'
            """, Integer.class, exportId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM f05_outbox outbox
            JOIN f05_domain_events event ON event.id = outbox.event_id
            WHERE event.aggregate_id = ?
              AND event.event_type = 'f05.export.ready.v1'
            """, Integer.class, exportId));
    }

    private int processAfterBarrier(
        CountDownLatch ready,
        CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        return worker.processExports();
    }
}
