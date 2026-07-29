package com.vms.workflow.integration;

import com.vms.workflow.application.FinanceCanonicalJson;
import com.vms.workflow.application.FinanceOperationsWorker;
import com.vms.workflow.application.FinanceReportDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.vms.workflow.integration.F04TestSupport.ENGAGEMENT;
import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
@Transactional
class FinanceExportWorkerIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FinanceOperationsWorker worker;

    @Autowired
    private FinanceReportDataService reportData;

    @Autowired
    private FinanceCanonicalJson canonical;

    @Test
    void workerRendersAllFormatsPersistsHashesAndServesPrivateDownloads()
        throws Exception {
        Map<String, UUID> exports = new LinkedHashMap<>();
        for (String format : new String[]{"JSON", "CSV", "XLSX", "PDF"}) {
            String response = mvc.perform(post("/api/v1/finance/exports")
                    .with(token("user-procurement"))
                    .header("Idempotency-Key", "export-" + format)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "reportId":"INVOICE_READINESS",
                          "reportVersion":"v1",
                          "format":"%s",
                          "temporalMode":"SNAPSHOT",
                          "filters":{
                            "engagementId":"%s",
                            "monthId":"%s"
                          },
                          "reason":"Deterministic worker contract test"
                        }
                        """.formatted(format, ENGAGEMENT, MONTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andReturn().getResponse().getContentAsString();
            exports.put(format,
                UUID.fromString(mapper.readTree(response).path("exportId").asText()));
        }

        assertEquals(4, worker.processExports());

        for (Map.Entry<String, UUID> entry : exports.entrySet()) {
            UUID exportId = entry.getValue();
            mvc.perform(get("/api/v1/finance/exports/{id}", exportId)
                    .with(token("user-procurement")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.progressPercent").value(100))
                .andExpect(jsonPath("$.sha256").isNotEmpty());

            byte[] bytes = mvc.perform(post(
                        "/api/v1/finance/exports/{id}/download", exportId)
                    .with(token("user-procurement")))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn().getResponse().getContentAsByteArray();
            String persistedHash = jdbc.queryForObject("""
                SELECT result_hash FROM f05_report_exports WHERE id = ?
                """, String.class, exportId);
            byte[] persisted = jdbc.queryForObject("""
                SELECT blob.content
                FROM f05_report_exports export
                JOIN f05_private_artifact_blobs blob
                  ON blob.artifact_id = export.result_artifact_id
                WHERE export.id = ?
                """, byte[].class, exportId);
            assertArrayEquals(persisted, bytes);
            assertEquals(persistedHash, canonical.sha256Bytes(bytes));
            assertFormatSignature(entry.getKey(), bytes);
        }
    }

    @Test
    void workerUsesReportSpecificDataAndExcludesInternalPaymentComments()
        throws Exception {
        UUID invoiceId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO invoices(
                id, engagement_month_id, vendor_organization_id,
                invoice_type, invoice_number, normalized_invoice_number,
                invoice_date, billing_period_start, billing_period_end,
                currency, status, current_version, optimistic_version,
                created_by_subject, correlation_id
            ) VALUES (?, ?::uuid, ?::uuid, 'PRIMARY', ?, ?, ?, ?, ?,
                      'INR', 'PAYMENT_INITIATED', 1, 1, ?, ?)
            """, invoiceId, MONTH,
            "00000000-0000-0000-0000-000000000101",
            "REPORT-SPECIFIC-001", "REPORT-SPECIFIC-001",
            Date.valueOf(LocalDate.of(2026, 7, 31)),
            Date.valueOf(LocalDate.of(2026, 7, 1)),
            Date.valueOf(LocalDate.of(2026, 7, 31)),
            "user-arrow", UUID.randomUUID());
        jdbc.update("""
            INSERT INTO payment_status_history(
                id, invoice_id, sequence_number, status,
                sanitized_comment, internal_comment, external_reference,
                status_at, expected_payment_date, source,
                recorded_by_subject, correlation_id
            ) VALUES (?, ?, 1, 'PAYMENT_INITIATED', ?, ?, ?, ?, ?,
                      'MANUAL', 'user-finance-ap', ?)
            """, UUID.randomUUID(), invoiceId, "=SAFE-FOR-SPREADSHEET",
            "SECRET_INTERNAL_AP_COMMENT", "ERP-REPORT-001",
            Timestamp.from(Instant.parse("2026-07-20T10:00:00Z")),
            Date.valueOf(LocalDate.of(2026, 7, 31)), UUID.randomUUID());

        UUID readinessExport = requestJsonExport(
            "INVOICE_READINESS", "specific-readiness");
        UUID paymentExport = requestJsonExport(
            "PAYMENT_AGING", "specific-payment");

        assertEquals(2, worker.processExports());

        JsonNode readiness = storedJson(readinessExport);
        JsonNode payment = storedJson(paymentExport);
        assertEquals("INVOICE_READINESS",
            readiness.path("metadata").path("reportCode").asText());
        assertEquals("PAYMENT_AGING",
            payment.path("metadata").path("reportCode").asText());
        assertTrue(findRow(readiness, "invoiceId", invoiceId.toString())
            .has("invoiceStatus"));
        JsonNode paymentRow =
            findRow(payment, "invoiceId", invoiceId.toString());
        assertEquals("PAYMENT_INITIATED",
            paymentRow.path("paymentStatus").asText());
        assertEquals("=SAFE-FOR-SPREADSHEET",
            paymentRow.path("sanitizedComment").asText());
        assertFalse(paymentRow.has("internalComment"));
        assertFalse(payment.toString().contains("SECRET_INTERNAL_AP_COMMENT"));
        assertTrue(allRowsHaveMonth(readiness, MONTH));
        assertTrue(allRowsHaveMonth(payment, MONTH));
    }

    @Test
    void workerFailsClosedWhenStoredAuthoritySnapshotIsTampered()
        throws Exception {
        UUID exportId = requestJsonExport(
            "INVOICE_READINESS", "tampered-authority");
        jdbc.update("""
            UPDATE f05_report_exports
            SET authority_snapshot =
                jsonb_set(authority_snapshot, '{actorSubject}',
                          '"user-attacker"'::jsonb)
            WHERE id = ?
            """, exportId);

        assertEquals(1, worker.processExports());
        assertEquals("FAILED", jdbc.queryForObject("""
            SELECT status FROM f05_report_exports WHERE id = ?
            """, String.class, exportId));
        assertEquals(0, jdbc.queryForObject("""
            SELECT COUNT(*) FROM f05_report_exports
            WHERE id = ? AND result_artifact_id IS NOT NULL
            """, Integer.class, exportId));
    }

    @Test
    void everyPublishedReportDefinitionHasAnExecutableSpecificQuery() {
        Map<String, String> reports = Map.ofEntries(
            Map.entry("ATTENDANCE_COMPLIANCE", "finance.read"),
            Map.entry("PLAN_TIMELINESS", "finance.read"),
            Map.entry("DELIVERY_ACCEPTANCE", "finance.read"),
            Map.entry("CONFIRMATION_COMPLETION", "finance.read"),
            Map.entry("EVIDENCE_PACKAGE_VERSIONS", "finance.read"),
            Map.entry("INVOICE_READINESS", "finance.read"),
            Map.entry("PROCUREMENT_AGING", "procurement.review"),
            Map.entry("PAYMENT_AGING", "payment.update"),
            Map.entry("EXCEPTION_REOPEN", "procurement.exception"),
            Map.entry("COMMUNICATION_AUDIT", "finance.audit.read"));
        UUID engagementId = UUID.fromString(ENGAGEMENT);
        UUID monthId = UUID.fromString(MONTH);
        UUID procurementOrganization = UUID.fromString(
            "00000000-0000-0000-0000-000000000103");
        for (Map.Entry<String, String> report : reports.entrySet()) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("permission", report.getValue());
            snapshot.put("engagementId", engagementId);
            snapshot.put("vendorOrganizationId",
                "00000000-0000-0000-0000-000000000101");
            snapshot.put("clientOrganizationId",
                "00000000-0000-0000-0000-000000000102");
            snapshot.put("procurementOrganizationId",
                procurementOrganization);
            snapshot.put("financeOrganizationId",
                procurementOrganization);
            snapshot.put("actorSubject", "query-contract-test");
            snapshot.put("actorOrganizationId",
                procurementOrganization);
            snapshot.put("capturedAt", OffsetDateTime.now());

            assertNotNull(reportData.rows(
                report.getKey(), engagementId, monthId,
                procurementOrganization, "query-contract-test", snapshot),
                report.getKey());
        }
    }

    private UUID requestJsonExport(String reportId, String idempotencyKey)
        throws Exception {
        String response = mvc.perform(post("/api/v1/finance/exports")
                .with(token("user-finance-ap"))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reportId":"%s",
                      "reportVersion":"v1",
                      "format":"JSON",
                      "temporalMode":"SNAPSHOT",
                      "filters":{
                        "engagementId":"%s",
                        "monthId":"%s"
                      },
                      "reason":"Report-specific worker verification"
                    }
                    """.formatted(reportId, ENGAGEMENT, MONTH)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(
            mapper.readTree(response).path("exportId").asText());
    }

    private JsonNode storedJson(UUID exportId) throws Exception {
        byte[] content = jdbc.queryForObject("""
            SELECT blob.content
            FROM f05_report_exports export
            JOIN f05_private_artifact_blobs blob
              ON blob.artifact_id = export.result_artifact_id
            WHERE export.id = ?
            """, byte[].class, exportId);
        return mapper.readTree(content);
    }

    private JsonNode findRow(
        JsonNode report,
        String field,
        String expected
    ) {
        for (JsonNode row : report.path("rows")) {
            if (expected.equals(row.path(field).asText())) {
                return row;
            }
        }
        throw new AssertionError(
            "Expected report row with " + field + "=" + expected);
    }

    private boolean allRowsHaveMonth(JsonNode report, String monthId) {
        if (report.path("rows").isEmpty()) {
            return false;
        }
        for (JsonNode row : report.path("rows")) {
            if (!monthId.equals(row.path("monthId").asText())) {
                return false;
            }
        }
        return true;
    }

    private void assertFormatSignature(String format, byte[] bytes) {
        if ("XLSX".equals(format)) {
            assertArrayEquals(new byte[]{'P', 'K'},
                new byte[]{bytes[0], bytes[1]});
            return;
        }
        String text = new String(bytes, StandardCharsets.US_ASCII);
        if ("PDF".equals(format)) {
            assertTrue(text.startsWith("%PDF-"));
        } else if ("JSON".equals(format)) {
            assertTrue(text.startsWith("{"));
        } else {
            assertTrue(text.endsWith("\r\n"));
        }
    }
}
