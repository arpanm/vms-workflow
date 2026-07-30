package com.vms.workflow.integration;

import com.vms.workflow.application.FinanceOperationsWorker;
import com.vms.workflow.application.FinancePackageService;
import com.vms.workflow.api.DomainConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.vms.workflow.integration.F04TestSupport.ENGAGEMENT;
import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_finance_committed_concurrency_it",
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

    @Autowired
    private FinancePackageService packages;

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

    @Test
    void concurrentPackageGenerationCommitsOneCanonicalPackageAndEvent()
        throws Exception {
        UUID f04ReadinessId = confirmedF04Readiness();
        UUID invoiceId = uploadPrimaryInvoice();
        int expectedPackageVersion = jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM evidence_package_versions
            WHERE engagement_month_id = ?::uuid
            """, Integer.class, MONTH);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<UUID> first = executor.submit(() -> packageAfterBarrier(
                ready, start, f04ReadinessId, expectedPackageVersion,
                "package-race-a-" + UUID.randomUUID()));
            Future<UUID> second = executor.submit(() -> packageAfterBarrier(
                ready, start, f04ReadinessId, expectedPackageVersion,
                "package-race-b-" + UUID.randomUUID()));
            ready.await();
            start.countDown();
            assertEquals(1, completedPackageCount(first, second));
        }

        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM evidence_package_versions
            WHERE engagement_month_id = ?::uuid
              AND invoice_id = ?
              AND status = 'CURRENT'
            """, Integer.class, MONTH, invoiceId));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM f05_domain_events
            WHERE engagement_month_id = ?::uuid
              AND event_type = 'f05.package.generated.v1'
            """, Integer.class, MONTH));
        assertEquals(1, jdbc.queryForObject("""
            SELECT COUNT(*) FROM f05_outbox outbox
            JOIN f05_domain_events event ON event.id = outbox.event_id
            WHERE event.engagement_month_id = ?::uuid
              AND event.event_type = 'f05.package.generated.v1'
            """, Integer.class, MONTH));
    }

    private int completedPackageCount(Future<UUID> first, Future<UUID> second)
        throws Exception {
        int completed = 0;
        for (Future<UUID> result : java.util.List.of(first, second)) {
            try {
                result.get();
                completed++;
            } catch (ExecutionException failure) {
                assertInstanceOf(DomainConflictException.class,
                    failure.getCause());
            }
        }
        return completed;
    }

    private UUID confirmedF04Readiness() throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        JsonNode confirmation = F04TestSupport.createConfirmationRequest(
            mvc, mapper, completed.monthVersion(),
            OffsetDateTime.now().plusDays(3).withNano(0),
            "concurrent-package-confirmation");
        UUID confirmationId = UUID.fromString(confirmation.path("id").asText());
        mvc.perform(post("/api/v1/certification/confirmation-requests/{id}/actions",
                    confirmationId)
                .with(token("user-reliance"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "concurrent-package-confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":1,"decision":"CONFIRM",
                     "comment":"Verified source for committed package race"}
                    """))
            .andExpect(status().isOk());
        return jdbc.queryForObject("""
            SELECT readiness_run_id
            FROM effective_f05_certification_handoffs
            WHERE engagement_month_id = ?::uuid
              AND effective_status <> 'INVALIDATED'
            ORDER BY created_at DESC LIMIT 1
            """, UUID.class, MONTH);
    }

    private UUID uploadPrimaryInvoice() throws Exception {
        String number = "PACKAGE-RACE-" + UUID.randomUUID();
        String created = mvc.perform(post("/api/v1/finance/invoices")
                .with(token("user-arrow"))
                .header("Idempotency-Key", "package-race-invoice-" + number)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "monthId":"%s", "documentKind":"PRIMARY",
                      "representedMetadata":{"invoiceNumber":"%s",
                        "invoiceDate":"2026-07-31",
                        "billingPeriodStart":"2026-07-01",
                        "billingPeriodEnd":"2026-07-31", "currency":"INR",
                        "taxableValue":"100.00", "taxValue":"18.00",
                        "totalValue":"118.00", "purchaseOrderReference":"PO-RACE",
                        "workOrderReference":"WO-RACE"}
                    }
                    """.formatted(MONTH, number)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        UUID invoiceId = UUID.fromString(mapper.readTree(created)
            .path("invoiceId").asText());
        MockMultipartFile file = new MockMultipartFile("file", "race.pdf",
            "application/pdf", "%PDF-1.7 package race\n%%EOF"
                .getBytes(StandardCharsets.UTF_8));
        MockMultipartFile metadata = new MockMultipartFile("metadata", "",
            MediaType.APPLICATION_JSON_VALUE, """
                {"expectedVersion":1,"classification":"CONFIDENTIAL",
                 "retentionPolicy":"FINANCE_EVIDENCE","source":"VENDOR_UPLOAD",
                 "reason":"Committed package race fixture"}
                """.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/v1/finance/invoices/{id}/documents", invoiceId)
                .file(file).file(metadata).with(token("user-arrow"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "package-race-upload-" + invoiceId))
            .andExpect(status().isOk());
        return invoiceId;
    }

    private UUID packageAfterBarrier(
        CountDownLatch ready,
        CountDownLatch start,
        UUID readinessId,
        int expectedPackageVersion,
        String idempotencyKey
    ) throws Exception {
        ready.countDown();
        start.await();
        return UUID.fromString(packages.generate("user-arrow",
            UUID.fromString(MONTH), expectedPackageVersion, readinessId,
            "Committed package race verification", idempotencyKey)
            .get("packageId").toString());
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
