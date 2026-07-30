package com.vms.workflow.integration;

import com.vms.workflow.VmsWorkflowApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static com.vms.workflow.integration.F04TestSupport.token;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    classes = {
        VmsWorkflowApplication.class,
        FinanceWorkflowIT.ClockTestConfiguration.class
    },
    properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_finance_workflow_it",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.certification.email-provider-status=NOT_CONFIGURED",
    "vms.certification.f05-handoff-status=NOT_CONFIGURED",
    "vms.finance.worker-initial-delay=PT1H"
    })
@AutoConfigureMockMvc
@Transactional
class FinanceWorkflowIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdjustableClock adjustableClock;

    @BeforeEach
    void resetClock() {
        adjustableClock.reset();
    }

    @Test
    void verifiedHandoffDrivesDeterministicPackageAndInvoiceSubmission()
        throws Exception {
        SubmittedFixture fixture = submittedInvoice();

        assertEquals(1, count("""
            SELECT count(*) FROM invoices WHERE id = ?
            """, fixture.invoiceId()));
        assertTrue(count("""
            SELECT count(*) FROM f05_outbox outbox
            JOIN f05_domain_events event ON event.id = outbox.event_id
            WHERE event.aggregate_id = ?
            """, fixture.invoiceId()) >= 4);
        assertEquals(8, count("""
            SELECT count(*) FROM evidence_package_items
            WHERE package_version_id = ?
            """, fixture.packageId()));
        assertEquals(8, count("""
            SELECT count(DISTINCT item_type)
            FROM evidence_package_items
            WHERE package_version_id = ?
              AND item_type IN (
                  'ENGAGEMENT_CONTRACT', 'ROSTER_ALLOCATION', 'ATTENDANCE',
                  'APPROVED_PLAN', 'LINEAR_SNAPSHOT',
                  'DELIVERY_CERTIFICATION', 'VERIFIED_CONFIRMATION',
                  'INVOICE_DOCUMENT')
            """, fixture.packageId()));
        assertEquals(1, count("""
            SELECT count(*)
            FROM evidence_package_items item
            JOIN evidence_package_versions package
              ON package.id = item.package_version_id
            JOIN f05_certification_handoffs handoff
              ON handoff.id = package.handoff_id
            JOIN certification_readiness_results result
              ON result.run_id = handoff.readiness_run_id
             AND result.pillar = 'ATTENDANCE'
            WHERE item.package_version_id = ?
              AND item.item_type = 'ATTENDANCE'
              AND item.source_object_id = result.source_object_id
              AND item.source_object_id <> result.id
            """, fixture.packageId()));
        assertEquals(1, count("""
            SELECT count(*)
            FROM evidence_package_versions
            WHERE id = ? AND status = 'CURRENT'
              AND hash_schema_version = 2
              AND render_version = 'manifest-v2'
              AND canonical_manifest ->> 'schema' =
                  'f05-evidence-manifest-v2'
              AND jsonb_exists(canonical_manifest, 'invoiceLineage')
              AND jsonb_exists(
                  canonical_manifest, 'relatedInvoiceDisclosures')
              AND jsonb_typeof(canonical_manifest) = 'object'
            """, fixture.packageId()));

        assertThrows(DataAccessException.class, () -> jdbc.update("""
            UPDATE evidence_package_versions
            SET canonical_manifest = '{"tampered":true}'::jsonb
            WHERE id = ?
            """, fixture.packageId()));
    }

    @Test
    void packageKeepsPrimaryLineageAndDisclosesRelatedNoteWithoutSubstitution()
        throws Exception {
        SubmittedFixture primary = submittedInvoice();
        JsonNode note = json(mvc.perform(post("/api/v1/finance/invoices")
                .with(token("user-arrow"))
                .header("Idempotency-Key", "lineage-credit-create")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "monthId":"%s",
                      "documentKind":"CREDIT_NOTE",
                      "relatedInvoiceId":"%s",
                      "representedMetadata":{
                        "invoiceNumber":"SYNTH CREDIT LINEAGE 001",
                        "invoiceDate":"2026-07-31",
                        "billingPeriodStart":"2026-07-01",
                        "billingPeriodEnd":"2026-07-31",
                        "currency":"INR",
                        "taxableValue":"10.00",
                        "taxValue":"1.80",
                        "totalValue":"11.80",
                        "purchaseOrderReference":"PO-SYNTH-1",
                        "workOrderReference":"WO-SYNTH-1"
                      }
                    }
                    """.formatted(MONTH, primary.invoiceId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        UUID noteId = UUID.fromString(note.path("invoiceId").asText());
        MockMultipartFile noteFile = new MockMultipartFile(
            "file", "credit-note.pdf", "application/pdf",
            "%PDF-1.7\ncredit note disclosure\n%%EOF"
                .getBytes(StandardCharsets.UTF_8));
        MockMultipartFile noteMetadata = new MockMultipartFile(
            "metadata", "", MediaType.APPLICATION_JSON_VALUE, """
                {"expectedVersion":1,"classification":"CONFIDENTIAL",
                 "retentionPolicy":"FINANCE_EVIDENCE",
                 "source":"VENDOR_UPLOAD",
                 "reason":"Attach related credit note without substitution"}
                """.getBytes(StandardCharsets.UTF_8));
        JsonNode uploadedNote = json(mvc.perform(multipart(
                    "/api/v1/finance/invoices/{id}/documents", noteId)
                .file(noteFile).file(noteMetadata)
                .with(token("user-arrow"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "lineage-credit-upload"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());

        UUID f04ReadinessId = jdbc.queryForObject("""
            SELECT readiness_run_id
            FROM effective_f05_certification_handoffs
            WHERE engagement_month_id = ?::uuid
              AND effective_status <> 'INVALIDATED'
            ORDER BY created_at DESC
            LIMIT 1
            """, UUID.class, MONTH);
        JsonNode secondPackage = json(mvc.perform(post(
                    "/api/v1/finance/months/{monthId}/packages", MONTH)
                .with(token("user-arrow"))
                .header("If-Match", "2")
                .header("Idempotency-Key", "lineage-second-package")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":2,
                      "readinessRunId":"%s",
                      "reason":"Disclose related note while retaining primary"
                    }
                    """.formatted(f04ReadinessId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        UUID secondPackageId = UUID.fromString(
            secondPackage.path("packageId").asText());

        assertEquals(primary.invoiceId(), jdbc.queryForObject("""
            SELECT invoice_id FROM evidence_package_versions WHERE id = ?
            """, UUID.class, secondPackageId));
        assertEquals(1, count("""
            SELECT count(*)
            FROM evidence_package_versions package
            JOIN invoices invoice ON invoice.id = package.invoice_id
            WHERE package.id = ?
              AND package.invoice_version = invoice.current_version
              AND package.invoice_document_artifact_id IS NOT NULL
              AND length(package.invoice_document_hash) = 64
              AND package.canonical_manifest #>> '{invoiceLineage,invoiceId}' = ?
              AND package.canonical_manifest #>>
                  '{invoiceLineage,invoiceType}' = 'PRIMARY'
              AND package.canonical_manifest #>>
                  '{relatedInvoiceDisclosures,0,invoiceId}' = ?
              AND package.canonical_manifest #>>
                  '{relatedInvoiceDisclosures,0,substitutesPrimaryInvoice}' =
                  'false'
            """, secondPackageId, primary.invoiceId().toString(),
            noteId.toString()));
        assertEquals(1, count("""
            SELECT count(*)
            FROM evidence_package_items
            WHERE package_version_id = ?
              AND item_type = 'INVOICE_CREDIT_NOTE_DISCLOSURE'
              AND source_object_id = ?
              AND safe_name = 'credit-note.pdf'
              AND media_type = 'application/pdf'
              AND byte_size > 0
              AND object_version <> ''
              AND classification = 'CONFIDENTIAL'
              AND retention_class = 'FINANCE_EVIDENCE'
              AND artifact_availability = 'PRIVATE_SCAN_PASSED_BINARY'
              AND disclosure LIKE '%does not replace the PRIMARY invoice%'
            """, secondPackageId, noteId));
        assertEquals(9, count("""
            SELECT count(*) FROM evidence_package_items
            WHERE package_version_id = ?
              AND safe_name <> ''
              AND media_type <> ''
              AND byte_size >= 0
              AND object_version <> ''
              AND classification <> ''
              AND retention_class <> ''
              AND artifact_availability <> ''
            """, secondPackageId));

        mvc.perform(post(
                    "/api/v1/finance/procurement/invoices/{id}/reviews",
                    primary.invoiceId())
                .with(token("user-procurement"))
                .header("If-Match", String.valueOf(primary.invoiceVersion()))
                .header("Idempotency-Key", "stale-package-review-denied")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "decision":"APPROVED_FOR_PROCESSING",
                      "category":null,
                      "comment":"Must not approve stale package",
                      "packageId":"%s",
                      "packageVersion":1,
                      "readinessRunId":"%s"
                    }
                    """.formatted(primary.invoiceVersion(),
                        primary.packageId(), primary.readinessId())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("EXACT_READINESS_INPUT_REQUIRED"));

        long noteVersion = uploadedNote.path("version").asLong();
        JsonNode noteReadiness = json(mvc.perform(post(
                    "/api/v1/finance/invoices/{id}/readiness-runs", noteId)
                .with(token("user-arrow"))
                .header("If-Match", String.valueOf(noteVersion))
                .header("Idempotency-Key", "note-readiness-no-substitution")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":%d}
                    """.formatted(noteVersion)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.readiness.eligibleForSubmission")
                .value(false))
            .andReturn().getResponse().getContentAsString());
        mvc.perform(post("/api/v1/finance/invoices/{id}/submit", noteId)
                .with(token("user-arrow"))
                .header("If-Match", noteReadiness.path("version").asText())
                .header("Idempotency-Key", "note-submit-primary-package-denied")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "packageId":"%s",
                      "packageVersion":2,
                      "readinessRunId":"%s",
                      "acknowledgment":true,
                      "reason":"Must reject primary package substitution"
                    }
                    """.formatted(noteReadiness.path("version").asLong(),
                        secondPackageId,
                        noteReadiness.path("readiness").path("runId").asText())))
            .andExpect(status().isConflict());
    }

    @Test
    void packageSharesGrantThenRevokeAccessAndExpiredGrantsStayInactive()
        throws Exception {
        SubmittedFixture fixture = submittedInvoice();
        String expiry = OffsetDateTime.now().plusHours(1).withNano(0).toString();
        JsonNode share = json(mvc.perform(post(
                    "/api/v1/finance/packages/{id}/shares", fixture.packageId())
                .with(token("user-arrow"))
                .header("Idempotency-Key", "share-package")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "recipientSubject":"user-northstar",
                      "accessScope":"VIEW",
                      "expiresAt":"%s",
                      "reason":"Time-bound external evidence review"
                    }
                    """.formatted(expiry)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.revoked").value(false))
            .andReturn().getResponse().getContentAsString());
        UUID shareId = UUID.fromString(share.path("shareId").asText());

        mvc.perform(get("/api/v1/finance/packages/{id}", fixture.packageId())
                .with(token("user-northstar")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.packageId")
                .value(fixture.packageId().toString()));
        mvc.perform(get(
                    "/api/v1/finance/packages/{id}/shares", fixture.packageId())
                .with(token("user-governance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].shareId")
                .value(shareId.toString()));

        mvc.perform(post(
                    "/api/v1/finance/packages/{packageId}/shares/{shareId}/revoke",
                    fixture.packageId(), shareId)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "revoke-package-share")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"reason":"External review completed"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.revoked").value(true));
        mvc.perform(get("/api/v1/finance/packages/{id}", fixture.packageId())
                .with(token("user-northstar")))
            .andExpect(status().isForbidden());

        jdbc.update("""
            INSERT INTO evidence_package_shares(
                id, package_version_id, recipient_subject, access_scope,
                expires_at, revoked_at, revoked_by_subject,
                created_by_subject, created_at, correlation_id
            ) VALUES (?, ?, 'user-northstar', 'DOWNLOAD',
                      CURRENT_TIMESTAMP - INTERVAL '1 hour',
                      NULL, NULL, 'user-arrow',
                      CURRENT_TIMESTAMP - INTERVAL '2 hours', ?)
            """, UUID.randomUUID(), fixture.packageId(), UUID.randomUUID());
        mvc.perform(get("/api/v1/finance/packages/{id}", fixture.packageId())
                .with(token("user-northstar")))
            .andExpect(status().isForbidden());

        mvc.perform(post(
                    "/api/v1/finance/packages/{id}/shares", fixture.packageId())
                .with(token("user-arrow"))
                .header("Idempotency-Key", "share-after-expiry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "recipientSubject":"user-northstar",
                      "accessScope":"DOWNLOAD",
                      "expiresAt":"%s",
                      "reason":"New review window after prior grant expired"
                    }
                    """.formatted(expiry)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.revoked").value(false));
        assertEquals(2, count("""
            SELECT count(*) FROM f05_domain_events
            WHERE aggregate_type = 'PACKAGE_SHARE'
              AND event_type = 'f05.package.shared.v1'
            """));

        mvc.perform(post(
                    "/api/v1/finance/packages/{id}/shares", fixture.packageId())
                .with(token("user-northstar"))
                .header("Idempotency-Key", "cross-tenant-share")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "recipientSubject":"user-arrow",
                      "accessScope":"VIEW",
                      "expiresAt":"%s",
                      "reason":"Unauthorized cross-tenant attempt"
                    }
                    """.formatted(expiry)))
            .andExpect(status().isForbidden());
    }

    @Test
    void approvedExactLineageAllowsPaymentWhileDraftPaymentIsDenied()
        throws Exception {
        SubmittedFixture fixture = submittedInvoice();
        JsonNode approved = json(mvc.perform(post(
                    "/api/v1/finance/procurement/invoices/{id}/reviews",
                    fixture.invoiceId())
                .with(token("user-procurement"))
                .header("If-Match", String.valueOf(fixture.invoiceVersion()))
                .header("Idempotency-Key", "approve-invoice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "decision":"APPROVED_FOR_PROCESSING",
                      "category":null,
                      "comment":"Exact immutable inputs approved",
                      "packageId":"%s",
                      "packageVersion":1,
                      "readinessRunId":"%s"
                    }
                    """.formatted(fixture.invoiceVersion(),
                        fixture.packageId(), fixture.readinessId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("APPROVED_FOR_PROCESSING"))
            .andReturn().getResponse().getContentAsString());
        long approvedVersion = approved.path("version").asLong();

        mvc.perform(post("/api/v1/finance/invoices/{id}/payments",
                    fixture.invoiceId())
                .with(token("user-finance-ap"))
                .header("If-Match", String.valueOf(approvedVersion))
                .header("Idempotency-Key", "submit-to-ap")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "status":"SUBMITTED_TO_AP",
                      "statusAt":"%s",
                      "expectedPaymentDate":"2026-08-15",
                      "actualPaymentDate":null,
                      "externalReference":"AP-SYNTH-1",
                      "comment":"Approved invoice submitted to AP"
                    }
                    """.formatted(approvedVersion,
                        OffsetDateTime.now().minusMinutes(1).withNano(0))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state")
                .value("APPROVED_FOR_PROCESSING"));
        mvc.perform(get("/api/v1/finance/invoices/{id}/payments",
                    fixture.invoiceId())
                .with(token("user-finance-ap")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].status").value("SUBMITTED_TO_AP"))
            .andExpect(jsonPath("$[0].externalReference")
                .value("AP-SYNTH-1"));
    }

    @Test
    void assignedOwnerResponseAndProcurementClosureAreAppendOnly()
        throws Exception {
        SubmittedFixture fixture = submittedInvoice();
        JsonNode changed = json(mvc.perform(post(
                    "/api/v1/finance/procurement/invoices/{id}/queries",
                    fixture.invoiceId())
                .with(token("user-procurement"))
                .header("If-Match", String.valueOf(fixture.invoiceVersion()))
                .header("Idempotency-Key", "create-query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "category":"DOCUMENT_CLARIFICATION",
                      "summary":"Clarify the represented PO evidence.",
                      "ownerId":"user-arrow",
                      "dueAt":"%s",
                      "reason":"Procurement needs a durable response"
                    }
                    """.formatted(fixture.invoiceVersion(),
                        OffsetDateTime.now().plusDays(2).withNano(0))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CHANGES_REQUESTED"))
            .andReturn().getResponse().getContentAsString());
        UUID queryId = jdbc.queryForObject("""
            SELECT id FROM procurement_queries
            WHERE invoice_id = ?
            ORDER BY created_at DESC LIMIT 1
            """, UUID.class, fixture.invoiceId());

        mvc.perform(post(
                    "/api/v1/finance/procurement/queries/{id}/responses", queryId)
                .with(token("user-northstar"))
                .header("Idempotency-Key", "wrong-query-owner")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"response":"I should not be allowed to answer."}
                    """))
            .andExpect(status().isForbidden());
        mvc.perform(post(
                    "/api/v1/finance/procurement/queries/{id}/responses", queryId)
                .with(token("user-arrow"))
                .header("Idempotency-Key", "query-response")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"response":"The PO reference maps to the attached evidence."}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RESPONDED"))
            .andExpect(jsonPath("$.responseCount").value(1));
        mvc.perform(post(
                    "/api/v1/finance/procurement/queries/{id}/close", queryId)
                .with(token("user-procurement"))
                .header("Idempotency-Key", "close-query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"decision":"CLOSED","reason":"Clarification accepted"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CLOSED"))
            .andExpect(jsonPath("$.responseCount").value(1));

        mvc.perform(post(
                    "/api/v1/finance/procurement/invoices/{id}/reviews",
                    fixture.invoiceId())
                .with(token("user-procurement"))
                .header("If-Match", changed.path("version").asText())
                .header("Idempotency-Key", "approve-resolved-query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "decision":"APPROVED_FOR_PROCESSING",
                      "category":null,
                      "comment":"Resolved clarification preserves exact evidence",
                      "packageId":"%s",
                      "packageVersion":1,
                      "readinessRunId":"%s"
                    }
                    """.formatted(changed.path("version").asLong(),
                        fixture.packageId(), fixture.readinessId())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state")
                .value("APPROVED_FOR_PROCESSING"));

        assertEquals(1, count("""
            SELECT count(*) FROM procurement_query_responses
            WHERE query_id = ?
            """, queryId));
        assertEquals("CHANGES_REQUESTED", changed.path("state").asText());
    }

    @Test
    void quarantinePersistsExactBytesAndExceptionCreatesNewReadinessLineage()
        throws Exception {
        SubmittedFixture fixture = submittedInvoice();
        JsonNode quarantineInvoice = json(mvc.perform(post(
                    "/api/v1/finance/invoices")
                .with(token("user-arrow"))
                .header("Idempotency-Key", "quarantine-invoice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "monthId":"%s",
                      "documentKind":"CREDIT_NOTE",
                      "relatedInvoiceId":"%s",
                      "representedMetadata":{
                        "invoiceNumber":"SYNTH EICAR 001",
                        "invoiceDate":"2026-07-31",
                        "billingPeriodStart":"2026-07-01",
                        "billingPeriodEnd":"2026-07-31",
                        "currency":"INR",
                        "taxableValue":"1.00",
                        "taxValue":"0.18",
                        "totalValue":"1.18",
                        "purchaseOrderReference":"PO-EICAR-1",
                        "workOrderReference":"WO-EICAR-1"
                      }
                    }
                    """.formatted(MONTH, fixture.invoiceId())))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        UUID quarantineInvoiceId = UUID.fromString(
            quarantineInvoice.path("invoiceId").asText());
        byte[] eicar = (
            "%PDF-1.7\n"
                + "X5O!P%@AP[4\\PZX54(P^)7CC)7}$"
                + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!")
            .getBytes(StandardCharsets.US_ASCII);
        MockMultipartFile unsafeFile = new MockMultipartFile(
            "file", "unsafe.pdf", "application/pdf", eicar);
        MockMultipartFile unsafeMetadata = new MockMultipartFile(
            "metadata", "", MediaType.APPLICATION_JSON_VALUE, """
                {"expectedVersion":1,"classification":"CONFIDENTIAL",
                 "retentionPolicy":"FINANCE_EVIDENCE",
                 "source":"VENDOR_UPLOAD",
                 "reason":"Exercise quarantine without losing evidence"}
                """.getBytes(StandardCharsets.UTF_8));
        JsonNode quarantined = json(mvc.perform(multipart(
                    "/api/v1/finance/invoices/{id}/documents",
                    quarantineInvoiceId)
                .file(unsafeFile).file(unsafeMetadata)
                .with(token("user-arrow"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "quarantine-upload"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentDocument.scanStatus")
                .value("QUARANTINED"))
            .andReturn().getResponse().getContentAsString());
        UUID quarantinedArtifact = UUID.fromString(
            quarantined.path("currentDocument").path("documentId").asText());
        byte[] persisted = jdbc.queryForObject("""
            SELECT content FROM f05_private_artifact_blobs
            WHERE artifact_id = ?
            """, byte[].class, quarantinedArtifact);
        assertArrayEquals(eicar, persisted);
        assertEquals(64, jdbc.queryForObject("""
            SELECT length(content_hash) FROM f05_private_artifacts
            WHERE id = ?
            """, Integer.class, quarantinedArtifact));

        JsonNode naturalBlocked = json(mvc.perform(post(
                    "/api/v1/finance/invoices/{id}/readiness-runs",
                    quarantineInvoiceId)
                .with(token("user-arrow"))
                .header("If-Match", quarantined.path("version").asText())
                .header("Idempotency-Key", "quarantine-readiness")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":%d}
                    """.formatted(quarantined.path("version").asLong())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("EVIDENCE_PENDING"))
            .andExpect(jsonPath("$.readiness.eligibleForSubmission")
                .value(false))
            .andReturn().getResponse().getContentAsString());
        UUID naturalReadinessId = UUID.fromString(naturalBlocked
            .path("readiness").path("runId").asText());
        assertEquals(1, count("""
            SELECT count(*)
            FROM invoice_readiness_results result
            JOIN invoice_readiness_runs run ON run.id = result.readiness_run_id
            WHERE run.id = ?
              AND result.rule_code = 'INVOICE_DOCUMENT'
              AND result.result = 'BLOCKED_MISSING_EVIDENCE'
              AND result.source_object_type = 'INVOICE_ARTIFACT'
              AND result.source_object_id = ?
            """, naturalReadinessId, quarantinedArtifact));

        UUID blockedReadinessId = UUID.randomUUID();
        String lineageMarker = UUID.randomUUID().toString();
        String blockedInputManifest = jdbc.queryForObject("""
            SELECT (
                input_manifest
                || jsonb_build_object('integrationTestLineage', ?)
            )::text
            FROM invoice_readiness_runs
            WHERE id = ?
            """, String.class, lineageMarker, fixture.readinessId());
        String blockedInputHash = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(
                blockedInputManifest.getBytes(StandardCharsets.UTF_8)));
        jdbc.update("""
            UPDATE invoice_readiness_runs
            SET current_result = FALSE,
                eligible = FALSE,
                invalidated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND current_result
            """, fixture.readinessId());
        jdbc.update("""
            INSERT INTO invoice_readiness_runs (
                id, invoice_id, invoice_version, package_version_id, handoff_id,
                input_manifest, input_hash, policy_version, overall_status,
                eligible, current_result, evaluated_by_subject, evaluated_at,
                invalidated_at, correlation_id
            )
            SELECT ?, invoice_id, invoice_version, package_version_id, handoff_id,
                   CAST(? AS jsonb), ?, policy_version,
                   'BLOCKED_MISSING_EVIDENCE', FALSE, TRUE,
                   'integration-test-readiness-lineage', CURRENT_TIMESTAMP,
                   NULL, ?
            FROM invoice_readiness_runs
            WHERE id = ?
            """, blockedReadinessId, blockedInputManifest, blockedInputHash,
            UUID.randomUUID(), fixture.readinessId());
        jdbc.update("""
            INSERT INTO invoice_readiness_results (
                id, readiness_run_id, rule_code, result, severity, owner_label,
                source_object_type, source_object_id, source_version,
                source_hash, freshness_at, remediation_cta
            )
            SELECT gen_random_uuid(), ?, rule_code,
                   CASE
                       WHEN rule_code IN (
                           'INVOICE_DOCUMENT', 'VERIFIED_CONFIRMATION'
                       ) THEN 'BLOCKED_MISSING_EVIDENCE'
                       ELSE result
                   END,
                   CASE
                       WHEN rule_code IN (
                           'INVOICE_DOCUMENT', 'VERIFIED_CONFIRMATION'
                       ) THEN 'BLOCKING'
                       ELSE severity
                   END,
                   owner_label, source_object_type, source_object_id,
                   source_version, source_hash, freshness_at,
                   CASE
                       WHEN rule_code = 'VERIFIED_CONFIRMATION'
                       THEN 'Resolve or accept with dual Procurement authority'
                       ELSE remediation_cta
                   END
            FROM invoice_readiness_results
            WHERE readiness_run_id = ?
            """, blockedReadinessId, fixture.readinessId());
        UUID documentRuleId = jdbc.queryForObject("""
            SELECT id FROM invoice_readiness_results
            WHERE readiness_run_id = ? AND rule_code = 'INVOICE_DOCUMENT'
            """, UUID.class, blockedReadinessId);
        UUID blockedRuleId = jdbc.queryForObject("""
            SELECT id FROM invoice_readiness_results
            WHERE readiness_run_id = ?
              AND rule_code = 'VERIFIED_CONFIRMATION'
            """, UUID.class, blockedReadinessId);
        jdbc.update("""
            UPDATE invoices
            SET status = 'EVIDENCE_PENDING',
                current_readiness_run_id = ?,
                optimistic_version = optimistic_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, blockedReadinessId, fixture.invoiceId());
        long blockedInvoiceVersion = fixture.invoiceVersion() + 1;

        mvc.perform(post(
                    "/api/v1/finance/procurement/invoices/{id}/exceptions",
                    fixture.invoiceId())
                .with(token("user-procurement"))
                .header("If-Match", String.valueOf(blockedInvoiceVersion))
                .header("Idempotency-Key", "nonwaivable-document-exception")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "ruleId":"%s",
                      "readinessRunId":"%s",
                      "packageId":"%s",
                      "packageVersion":1,
                      "rationale":"Must not waive document integrity",
                      "validUntil":"%s"
                    }
                    """.formatted(
                        blockedInvoiceVersion, documentRuleId,
                        blockedReadinessId, fixture.packageId(),
                        OffsetDateTime.now().plusDays(3).withNano(0))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("READINESS_RULE_NOT_EXCEPTIONABLE"));

        // The governed exception lineage below uses a policy-declared business
        // readiness rule on the submitted primary invoice. Package generation
        // remains restricted to scan-passed artifacts, while document and
        // package-integrity rules are explicitly non-waivable.
        String exceptionBody = """
            {
              "expectedVersion":%d,
              "ruleId":"%s",
              "readinessRunId":"%s",
              "packageId":"%s",
              "packageVersion":1,
              "rationale":"Disclosed time-bound synthetic exception",
              "validUntil":"%s"
            }
            """;
        JsonNode pending = json(mvc.perform(post(
                    "/api/v1/finance/procurement/invoices/{id}/exceptions",
                    fixture.invoiceId())
                .with(token("user-procurement"))
                .header("If-Match", String.valueOf(blockedInvoiceVersion))
                .header("Idempotency-Key", "exception-request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(exceptionBody.formatted(
                    blockedInvoiceVersion, blockedRuleId,
                    blockedReadinessId, fixture.packageId(),
                    OffsetDateTime.now().plusDays(3).withNano(0))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exceptionStatus")
                .value("PENDING_SECOND_APPROVAL"))
            .andExpect(jsonPath("$.secondApproverSubject").doesNotExist())
            .andExpect(jsonPath("$.state").value("EVIDENCE_PENDING"))
            .andReturn().getResponse().getContentAsString());
        UUID exceptionId = UUID.fromString(
            pending.path("exceptionId").asText());
        UUID policyVersionId = UUID.fromString(
            pending.path("policyVersionId").asText());
        long pendingInvoiceVersion = pending.path("version").asLong();
        String approvalBody = """
            {
              "expectedVersion":%d,
              "invoiceId":"%s",
              "ruleId":"%s",
              "readinessRunId":"%s",
              "packageId":"%s",
              "packageVersion":1,
              "policyVersionId":"%s",
              "policyVersion":%d
            }
            """.formatted(
                pendingInvoiceVersion, fixture.invoiceId(), blockedRuleId,
                blockedReadinessId, fixture.packageId(), policyVersionId,
                pending.path("policyVersion").asInt());

        mvc.perform(post(
                    "/api/v1/finance/procurement/exceptions/{id}/second-approval",
                    exceptionId)
                .with(token("user-procurement"))
                .header("If-Match", String.valueOf(pendingInvoiceVersion))
                .header("Idempotency-Key", "self-second-approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvalBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("SEPARATION_OF_DUTIES_VIOLATION"));
        mvc.perform(post(
                    "/api/v1/finance/procurement/exceptions/{id}/second-approval",
                    exceptionId)
                .with(token("user-northstar"))
                .header("If-Match", String.valueOf(pendingInvoiceVersion))
                .header("Idempotency-Key", "cross-tenant-second-approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvalBody))
            .andExpect(status().isForbidden());
        mvc.perform(post(
                    "/api/v1/finance/procurement/exceptions/{id}/second-approval",
                    exceptionId)
                .with(token("user-procurement-second"))
                .header("If-Match", String.valueOf(pendingInvoiceVersion))
                .header("Idempotency-Key", "mismatched-second-approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvalBody.replace(
                    fixture.packageId().toString(), UUID.randomUUID().toString())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("EXCEPTION_APPROVAL_BINDING_MISMATCH"));
        jdbc.update("""
            UPDATE memberships
            SET status = 'REVOKED'
            WHERE user_profile_id = (
                SELECT id FROM user_profiles
                WHERE identity_subject = 'user-procurement-second')
            """);
        mvc.perform(post(
                    "/api/v1/finance/procurement/exceptions/{id}/second-approval",
                    exceptionId)
                .with(token("user-procurement-second"))
                .header("If-Match", String.valueOf(pendingInvoiceVersion))
                .header("Idempotency-Key", "revoked-second-approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvalBody))
            .andExpect(status().isForbidden());
        jdbc.update("""
            UPDATE memberships
            SET status = 'ACTIVE'
            WHERE user_profile_id = (
                SELECT id FROM user_profiles
                WHERE identity_subject = 'user-procurement-second')
            """);

        JsonNode accepted = json(mvc.perform(post(
                    "/api/v1/finance/procurement/exceptions/{id}/second-approval",
                    exceptionId)
                .with(token("user-procurement-second"))
                .header("If-Match", String.valueOf(pendingInvoiceVersion))
                .header("Idempotency-Key", "authenticated-second-approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvalBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("EXCEPTION_ACCEPTED"))
            .andExpect(jsonPath("$.exceptionStatus").value("ACCEPTED"))
            .andExpect(jsonPath("$.secondApproverSubject")
                .value("user-procurement-second"))
            .andReturn().getResponse().getContentAsString());
        long acceptedInvoiceVersion = accepted.path("version").asLong();
        mvc.perform(post(
                    "/api/v1/finance/procurement/exceptions/{id}/second-approval",
                    exceptionId)
                .with(token("user-procurement-second"))
                .header("If-Match", String.valueOf(pendingInvoiceVersion))
                .header("Idempotency-Key", "authenticated-second-approval")
                .contentType(MediaType.APPLICATION_JSON)
                .content(approvalBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.exceptionStatus").value("ACCEPTED"));
        UUID exceptionReadinessId = jdbc.queryForObject("""
            SELECT current_readiness_run_id FROM invoices WHERE id = ?
            """, UUID.class, fixture.invoiceId());
        assertNotEquals(blockedReadinessId, exceptionReadinessId);
        assertEquals(1, count("""
            SELECT count(*)
            FROM invoice_readiness_runs current_run
            WHERE current_run.id = ?
              AND current_run.invoice_id = ?
              AND current_run.current_result
              AND current_run.overall_status =
                  'EXCEPTION_ACCEPTED_BY_PROCUREMENT'
            """, exceptionReadinessId, fixture.invoiceId()));
        assertEquals(1, count("""
            SELECT count(*) FROM procurement_exceptions
            WHERE readiness_result_id = ?
              AND status = 'ACCEPTED'
              AND second_approver_subject = 'user-procurement-second'
            """, blockedRuleId));
        assertEquals(1, count("""
            SELECT count(*) FROM f05_audit_events
            WHERE object_id = ?
              AND action = 'PROCUREMENT_EXCEPTION_SECOND_APPROVED'
              AND actor_subject = 'user-procurement-second'
            """, fixture.invoiceId()));

        adjustableClock.advance(Duration.ofDays(4));
        mvc.perform(post(
                    "/api/v1/finance/procurement/invoices/{id}/reviews",
                    fixture.invoiceId())
                .with(token("user-procurement"))
                .header("If-Match", String.valueOf(acceptedInvoiceVersion))
                .header("Idempotency-Key", "expired-exception-review")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "decision":"APPROVED_FOR_PROCESSING",
                      "category":null,
                      "comment":"Must be blocked after exception expiry",
                      "packageId":"%s",
                      "packageVersion":1,
                      "readinessRunId":"%s"
                    }
                    """.formatted(acceptedInvoiceVersion,
                        fixture.packageId(), exceptionReadinessId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("VERSION_MISMATCH"));
        long reblockedVersion = jdbc.queryForObject("""
            SELECT optimistic_version FROM invoices WHERE id = ?
            """, Long.class, fixture.invoiceId());
        UUID expiredReadinessId = jdbc.queryForObject("""
            SELECT current_readiness_run_id FROM invoices WHERE id = ?
            """, UUID.class, fixture.invoiceId());
        assertEquals("EXPIRED", jdbc.queryForObject("""
            SELECT status FROM procurement_exceptions WHERE id = ?
            """, String.class, exceptionId));
        assertEquals("EVIDENCE_PENDING", jdbc.queryForObject("""
            SELECT status FROM invoices WHERE id = ?
            """, String.class, fixture.invoiceId()));
        assertNotEquals(exceptionReadinessId, expiredReadinessId);
        assertEquals(1, count("""
            SELECT count(*) FROM invoice_readiness_runs
            WHERE id = ? AND current_result AND NOT eligible
              AND overall_status = 'BLOCKED_MISSING_EVIDENCE'
            """, expiredReadinessId));
        assertEquals(1, count("""
            SELECT count(*) FROM f05_domain_events
            WHERE aggregate_id = ?
              AND event_type = 'f05.procurement.exception.expired.v1'
            """, fixture.invoiceId()));
        assertEquals(1, count("""
            SELECT count(*) FROM f05_outbox outbox
            JOIN f05_domain_events event ON event.id = outbox.event_id
            WHERE event.aggregate_id = ?
              AND event.event_type =
                  'f05.procurement.exception.expired.v1'
            """, fixture.invoiceId()));

        mvc.perform(post("/api/v1/finance/invoices/{id}/submit",
                    fixture.invoiceId())
                .with(token("user-arrow"))
                .header("If-Match", String.valueOf(reblockedVersion))
                .header("Idempotency-Key", "expired-exception-submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "packageId":"%s",
                      "packageVersion":1,
                      "readinessRunId":"%s",
                      "acknowledgment":true,
                      "reason":"Expired exception must not authorize submission"
                    }
                    """.formatted(reblockedVersion,
                        fixture.packageId(), expiredReadinessId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVOICE_NOT_READY"));
        mvc.perform(post("/api/v1/finance/invoices/{id}/payments",
                    fixture.invoiceId())
                .with(token("user-finance-ap"))
                .header("If-Match", String.valueOf(reblockedVersion))
                .header("Idempotency-Key", "expired-exception-payment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "status":"SUBMITTED_TO_AP",
                      "statusAt":"%s",
                      "expectedPaymentDate":"2026-08-15",
                      "actualPaymentDate":null,
                      "externalReference":"AP-EXPIRED-EXCEPTION",
                      "comment":"Expired exception must not authorize payment"
                    }
                    """.formatted(reblockedVersion,
                        OffsetDateTime.now(adjustableClock)
                            .minusMinutes(1).withNano(0))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("PROCUREMENT_APPROVAL_REQUIRED"));
        mvc.perform(get("/api/v1/finance/invoices/{id}", fixture.invoiceId())
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("EVIDENCE_PENDING"));
        assertEquals(1, count("""
            SELECT count(*) FROM f05_domain_events
            WHERE aggregate_id = ?
              AND event_type = 'f05.procurement.exception.expired.v1'
            """, fixture.invoiceId()));
    }

    @Test
    void packageListRoutesUseBoundedSignedSnapshotKeysets()
        throws Exception {
        SubmittedFixture fixture = submittedInvoice();
        seedPackagePages(fixture);

        JsonNode firstHistory = json(mvc.perform(get(
                    "/api/v1/finance/months/{monthId}/packages", MONTH)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertEquals(50, firstHistory.path("items").size());
        String historyCursor = firstHistory.path("nextCursor").asText();
        assertFalse(historyCursor.isBlank());
        UUID insertedHistory = insertPackagePageRow(
            fixture, 57, "CURRENT_TIMESTAMP + INTERVAL '1 hour'");
        JsonNode secondHistory = json(mvc.perform(get(
                    "/api/v1/finance/months/{monthId}/packages", MONTH)
                .queryParam("cursor", historyCursor)
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertStableContinuation(
            firstHistory, secondHistory, "packageId", insertedHistory);

        JsonNode firstAccess = json(mvc.perform(get(
                    "/api/v1/finance/packages/{id}/access-events",
                    fixture.packageId())
                .with(token("user-governance")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertEquals(50, firstAccess.path("items").size());
        String accessCursor = firstAccess.path("nextCursor").asText();
        UUID insertedAccess = insertAccessPageRow(
            fixture.packageId(), 99, true);
        JsonNode secondAccess = json(mvc.perform(get(
                    "/api/v1/finance/packages/{id}/access-events",
                    fixture.packageId())
                .queryParam("cursor", accessCursor)
                .with(token("user-governance")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertStableContinuation(
            firstAccess, secondAccess, "accessId", insertedAccess);

        JsonNode firstShares = json(mvc.perform(get(
                    "/api/v1/finance/packages/{id}/shares",
                    fixture.packageId())
                .with(token("user-governance")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertEquals(50, firstShares.path("items").size());
        String sharesCursor = firstShares.path("nextCursor").asText();
        UUID insertedShare = insertSharePageRow(
            fixture.packageId(), 99, true);
        JsonNode secondShares = json(mvc.perform(get(
                    "/api/v1/finance/packages/{id}/shares",
                    fixture.packageId())
                .queryParam("cursor", sharesCursor)
                .with(token("user-governance")))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertStableContinuation(
            firstShares, secondShares, "shareId", insertedShare);

        mvc.perform(get("/api/v1/finance/packages/{id}/shares",
                    fixture.packageId())
                .queryParam("cursor", accessCursor)
                .with(token("user-governance")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/finance/months/{id}/packages", MONTH)
                .queryParam("cursor", tamper(historyCursor))
                .with(token("user-arrow")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void incompatibleNewerF04HandoffIsDeniedBeforePackageGeneration()
        throws Exception {
        SubmittedFixture fixture = submittedInvoice();
        UUID incompatibleHandoffId = UUID.randomUUID();
        UUID f04ReadinessId = jdbc.queryForObject("""
            INSERT INTO f05_certification_handoffs(
                id, engagement_month_id, confirmation_request_id,
                closure_id, readiness_run_id, package_manifest, package_hash,
                status, created_by_subject, created_at, correlation_id
            )
            SELECT ?, engagement_month_id, confirmation_request_id,
                   closure_id, readiness_run_id,
                   '{"schema":"future-incompatible-handoff"}'::jsonb,
                   repeat('d', 64), 'READY_LOCAL', 'SYSTEM:TEST',
                   CURRENT_TIMESTAMP + INTERVAL '1 second', ?
            FROM effective_f05_certification_handoffs
            WHERE engagement_month_id = ?::uuid
              AND effective_status <> 'INVALIDATED'
            ORDER BY created_at DESC
            LIMIT 1
            RETURNING readiness_run_id
            """, UUID.class, incompatibleHandoffId, UUID.randomUUID(), MONTH);

        mvc.perform(post(
                    "/api/v1/finance/months/{monthId}/packages", MONTH)
                .with(token("user-arrow"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "incompatible-handoff-package")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":1,
                      "readinessRunId":"%s",
                      "reason":"Must reject incompatible F04 contract"
                    }
                    """.formatted(f04ReadinessId)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("F04_HANDOFF_INCOMPATIBLE"));
        assertEquals(1, count("""
            SELECT count(*) FROM evidence_package_versions
            WHERE id = ?
            """, fixture.packageId()));
    }

    @Test
    void approvedReopenInvalidatesPackageReadinessAndInvoiceWithOutboxFact()
        throws Exception {
        SubmittedFixture fixture = submittedInvoice();
        long confirmedVersion = jdbc.queryForObject("""
            SELECT certification_version
            FROM engagement_months WHERE id = ?::uuid
            """, Long.class, MONTH);
        mvc.perform(post(
                    "/api/v1/certification/months/{monthId}/reopen-requests",
                    MONTH)
                .with(token("user-reliance"))
                .header("If-Match", Long.toString(confirmedVersion))
                .header("Idempotency-Key", "f05-invalidation-reopen")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "category":"CERTIFICATION_CORRECTION",
                      "reason":"A represented source requires additive correction.",
                      "impactedRecordIds":["%s"],
                      "packageInvoiceImpact":"INVOICE_SUBMITTED",
                      "riskStatement":"Current downstream facts must become stale."
                    }
                    """.formatted(confirmedVersion, fixture.summaryId())))
            .andExpect(status().isCreated());
        UUID reopenId = jdbc.queryForObject("""
            SELECT id FROM month_reopen_requests
            WHERE engagement_month_id = ?::uuid AND status = 'REQUESTED'
            """, UUID.class, MONTH);
        long pendingVersion = jdbc.queryForObject("""
            SELECT certification_version
            FROM engagement_months WHERE id = ?::uuid
            """, Long.class, MONTH);
        mvc.perform(post(
                    "/api/v1/certification/reopen-requests/{id}/decisions",
                    reopenId)
                .with(token("user-governance"))
                .header("If-Match", Long.toString(pendingVersion))
                .header("Idempotency-Key", "f05-invalidation-approve")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedMonthVersion":%d,
                      "decision":"APPROVE",
                      "reasoning":"Independent authority approved correction."
                    }
                    """.formatted(pendingVersion)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.decision").value("APPROVE"));

        assertEquals("INVALIDATED", jdbc.queryForObject("""
            SELECT status FROM evidence_package_versions WHERE id = ?
            """, String.class, fixture.packageId()));
        assertEquals("EVIDENCE_PENDING", jdbc.queryForObject("""
            SELECT status FROM invoices WHERE id = ?
            """, String.class, fixture.invoiceId()));
        assertEquals(0, count("""
            SELECT count(*) FROM invoice_readiness_runs
            WHERE id = ? AND current_result
            """, fixture.readinessId()));
        UUID effectId = jdbc.queryForObject("""
            SELECT effect.id
            FROM f05_invalidation_effects effect
            JOIN f05_handoff_invalidations invalidation
              ON invalidation.id = effect.handoff_invalidation_id
            JOIN evidence_package_versions package
              ON package.handoff_id = invalidation.handoff_id
            WHERE package.id = ?
            """, UUID.class, fixture.packageId());
        assertEquals(1, count("""
            SELECT count(*)
            FROM f05_outbox outbox
            JOIN f05_domain_events event ON event.id = outbox.event_id
            WHERE event.event_type = 'f05.invalidated.v1'
              AND event.engagement_month_id = ?::uuid
              AND outbox.status = 'PENDING'
            """, MONTH));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            UPDATE f05_invalidation_effects
            SET reason_code = 'TAMPERED'
            WHERE id = ?
            """, effectId));
    }

    private SubmittedFixture submittedInvoice()
        throws Exception {
        F04TestSupport.CompletedCertification completed =
            F04TestSupport.completedCertification(mvc, mapper, jdbc);
        JsonNode confirmation = F04TestSupport.createConfirmationRequest(
            mvc, mapper, completed.monthVersion(),
            OffsetDateTime.now().plusDays(3).withNano(0),
            "f05-confirmation-request");
        UUID confirmationId = UUID.fromString(confirmation.path("id").asText());
        mvc.perform(post(
                    "/api/v1/certification/confirmation-requests/{id}/actions",
                    confirmationId)
                .with(token("user-reliance"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "f05-confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedRequestVersion":1,"decision":"CONFIRM",
                     "comment":"Verified exact scope for downstream evidence"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CONFIRMED"));

        UUID readinessId = jdbc.queryForObject("""
            SELECT readiness_run_id
            FROM effective_f05_certification_handoffs
            WHERE engagement_month_id = ?::uuid
              AND effective_status <> 'INVALIDATED'
            ORDER BY created_at DESC
            LIMIT 1
            """, UUID.class, MONTH);
        String createInvoice = """
            {
              "monthId":"%s",
              "documentKind":"PRIMARY",
              "relatedInvoiceId":null,
              "representedMetadata":{
                "invoiceNumber":" SYNTH F05 001 ",
                "invoiceDate":"2026-07-31",
                "billingPeriodStart":"2026-07-01",
                "billingPeriodEnd":"2026-07-31",
                "currency":"INR",
                "taxableValue":"100.00",
                "taxValue":"18.00",
                "totalValue":"118.00",
                "purchaseOrderReference":"PO-SYNTH-1",
                "workOrderReference":"WO-SYNTH-1"
              }
            }
            """.formatted(MONTH);
        JsonNode invoice = json(mvc.perform(post("/api/v1/finance/invoices")
                .with(token("user-arrow"))
                .header("Idempotency-Key", "invoice-create-once")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createInvoice))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        UUID invoiceId = UUID.fromString(invoice.path("invoiceId").asText());
        JsonNode replayedInvoice = json(mvc.perform(post("/api/v1/finance/invoices")
                .with(token("user-arrow"))
                .header("Idempotency-Key", "invoice-create-once")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createInvoice))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        assertEquals(invoiceId.toString(),
            replayedInvoice.path("invoiceId").asText());

        mvc.perform(post("/api/v1/finance/invoices/{id}/payments", invoiceId)
                .with(token("user-finance-ap"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "draft-payment-denied")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":1,
                      "status":"SUBMITTED_TO_AP",
                      "statusAt":"%s",
                      "expectedPaymentDate":"2026-08-15",
                      "actualPaymentDate":null,
                      "externalReference":"AP-PREMATURE",
                      "comment":"This draft must not enter AP"
                    }
                    """.formatted(
                        OffsetDateTime.now().minusMinutes(1).withNano(0))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("PROCUREMENT_APPROVAL_REQUIRED"));

        MockMultipartFile file = new MockMultipartFile(
            "file", "invoice.pdf", "application/pdf",
            "%PDF-1.7\nsynthetic invoice evidence\n%%EOF"
                .getBytes(StandardCharsets.UTF_8));
        MockMultipartFile metadata = new MockMultipartFile(
            "metadata", "", MediaType.APPLICATION_JSON_VALUE, """
                {"expectedVersion":1,"classification":"CONFIDENTIAL",
                 "retentionPolicy":"FINANCE_EVIDENCE",
                 "source":"VENDOR_UPLOAD",
                 "reason":"Attach immutable represented document"}
                """.getBytes(StandardCharsets.UTF_8));
        JsonNode uploaded = json(mvc.perform(multipart(
                    "/api/v1/finance/invoices/{id}/documents", invoiceId)
                .file(file).file(metadata)
                .with(token("user-arrow"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "invoice-upload-once"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertEquals("PASSED",
            uploaded.path("currentDocument").path("scanStatus").asText());
        long uploadedVersion = uploaded.path("version").asLong();

        String packageBody = """
            {"expectedMonthVersion":1,"readinessRunId":"%s",
             "reason":"Generate exact canonical package"}
            """.formatted(readinessId);
        JsonNode firstPackage = json(mvc.perform(post(
                    "/api/v1/finance/months/{monthId}/packages", MONTH)
                .with(token("user-arrow"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "package-once")
                .contentType(MediaType.APPLICATION_JSON)
                .content(packageBody))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        JsonNode replayedPackage = json(mvc.perform(post(
                    "/api/v1/finance/months/{monthId}/packages", MONTH)
                .with(token("user-arrow"))
                .header("If-Match", "1")
                .header("Idempotency-Key", "package-once")
                .contentType(MediaType.APPLICATION_JSON)
                .content(packageBody))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertEquals(firstPackage.path("packageId").asText(),
            replayedPackage.path("packageId").asText());
        assertEquals(1, count("""
            SELECT count(*) FROM evidence_package_versions
            WHERE engagement_month_id = ?::uuid
            """, MONTH));

        JsonNode ready = json(mvc.perform(post(
                    "/api/v1/finance/invoices/{id}/readiness-runs", invoiceId)
                .with(token("user-arrow"))
                .header("If-Match", String.valueOf(uploadedVersion))
                .header("Idempotency-Key", "readiness-once")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":%d}
                    """.formatted(uploadedVersion)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertTrue(ready.path("readiness")
            .path("eligibleForSubmission").asBoolean());
        assertEquals(9, ready.path("readiness").path("rules").size());
        String readinessRunId = ready.path("readiness").path("runId").asText();
        long readyVersion = ready.path("version").asLong();

        JsonNode submitted = json(mvc.perform(post(
                    "/api/v1/finance/invoices/{id}/submit", invoiceId)
                .with(token("user-arrow"))
                .header("If-Match", String.valueOf(readyVersion))
                .header("Idempotency-Key", "invoice-submit-once")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "packageId":"%s",
                      "packageVersion":1,
                      "readinessRunId":"%s",
                      "acknowledgment":true,
                      "reason":"Submit exact eligible versions"
                    }
                    """.formatted(readyVersion,
                        firstPackage.path("packageId").asText(),
                        readinessRunId)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        assertEquals("SUBMITTED_TO_PROCUREMENT",
            submitted.path("state").asText());
        assertFalse(submitted.toString().contains("object_key"));
        assertNotEquals("", submitted.path("etag").asText());
        return new SubmittedFixture(
            invoiceId,
            UUID.fromString(firstPackage.path("packageId").asText()),
            UUID.fromString(readinessRunId),
            submitted.path("version").asLong(),
            completed.summaryId());
    }

    private void seedPackagePages(SubmittedFixture fixture) {
        for (int index = 2; index <= 56; index++) {
            insertPackagePageRow(fixture, index,
                "'2020-01-01T00:00:00Z'::timestamptz"
                    + " + (" + index + " * INTERVAL '1 second')");
        }
        for (int index = 0; index < 55; index++) {
            insertAccessPageRow(fixture.packageId(), index, false);
            insertSharePageRow(fixture.packageId(), index, false);
        }
    }

    private UUID insertPackagePageRow(
        SubmittedFixture fixture,
        int version,
        String generatedAtExpression
    ) {
        UUID packageId = UUID.nameUUIDFromBytes(
            ("f05-history-page-" + version)
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update("""
            INSERT INTO evidence_package_versions(
                id, engagement_month_id, handoff_id, policy_version_id,
                invoice_id, invoice_version,
                invoice_document_artifact_id, invoice_document_hash,
                version, status, canonical_manifest,
                canonical_input_hash, hash_schema_version,
                render_version, invalidation_reason,
                generated_by_subject, generated_at, correlation_id
            )
            SELECT ?, engagement_month_id, handoff_id, policy_version_id,
                   invoice_id, invoice_version,
                   invoice_document_artifact_id, invoice_document_hash,
                   ?, 'SUPERSEDED', ?::jsonb, ?, hash_schema_version,
                   render_version, 'PAGINATION_TEST',
                   'system:pagination-test',
                   %s, ?
            FROM evidence_package_versions WHERE id = ?
            """.formatted(generatedAtExpression),
            packageId, version, "{\"seed\":" + version + "}",
            "%064x".formatted(version), UUID.randomUUID(),
            fixture.packageId());
        return packageId;
    }

    private UUID insertAccessPageRow(
        UUID packageId,
        int index,
        boolean afterSnapshot
    ) {
        UUID accessId = UUID.nameUUIDFromBytes(
            ("f05-access-page-" + index + "-" + afterSnapshot)
                .getBytes(StandardCharsets.UTF_8));
        String recordedAt = afterSnapshot
            ? "CURRENT_TIMESTAMP + INTERVAL '1 hour'"
            : "'2020-01-01T00:00:00Z'::timestamptz + ("
                + index + " * INTERVAL '1 second')";
        jdbc.update("""
            INSERT INTO f05_audit_events(
                id, engagement_month_id, action, object_type,
                object_id, object_version, result, reason_code,
                authority_snapshot, evidence_references,
                actor_subject, correlation_id, recorded_at
            ) VALUES (?, ?::uuid, 'PACKAGE_DOWNLOADED',
                      'EVIDENCE_PACKAGE', ?, 1, 'SUCCESS',
                      'PAGINATION_TEST',
                      '{"permission":"finance.audit.read"}'::jsonb,
                      '[]'::jsonb, 'system:pagination-test', ?,
                      %s)
            """.formatted(recordedAt),
            accessId, MONTH, packageId, UUID.randomUUID());
        return accessId;
    }

    private UUID insertSharePageRow(
        UUID packageId,
        int index,
        boolean afterSnapshot
    ) {
        UUID shareId = UUID.nameUUIDFromBytes(
            ("f05-share-page-" + index + "-" + afterSnapshot)
                .getBytes(StandardCharsets.UTF_8));
        String createdAt = afterSnapshot
            ? "CURRENT_TIMESTAMP + INTERVAL '1 hour'"
            : "'2020-01-01T00:00:00Z'::timestamptz + ("
                + index + " * INTERVAL '1 second')";
        String expiresAt = afterSnapshot
            ? "CURRENT_TIMESTAMP + INTERVAL '2 hours'"
            : "'2030-01-01T00:00:00Z'::timestamptz";
        jdbc.update("""
            INSERT INTO evidence_package_shares(
                id, package_version_id, recipient_subject,
                access_scope, expires_at, created_by_subject,
                created_at, correlation_id
            ) VALUES (?, ?, ?, 'VIEW', %s,
                      'system:pagination-test', %s, ?)
            """.formatted(expiresAt, createdAt),
            shareId, packageId,
            "pagination-recipient-" + index + "-" + afterSnapshot,
            UUID.randomUUID());
        return shareId;
    }

    private void assertStableContinuation(
        JsonNode first,
        JsonNode second,
        String idField,
        UUID insertedAfterSnapshot
    ) {
        Set<String> firstIds = new HashSet<>();
        first.path("items").forEach(
            item -> firstIds.add(item.path(idField).asText()));
        Set<String> secondIds = new HashSet<>();
        second.path("items").forEach(
            item -> secondIds.add(item.path(idField).asText()));
        assertTrue(firstIds.stream().noneMatch(secondIds::contains));
        assertFalse(firstIds.contains(insertedAfterSnapshot.toString()));
        assertFalse(secondIds.contains(insertedAfterSnapshot.toString()));
        assertEquals(first.path("totalCount").asLong(),
            second.path("totalCount").asLong());
    }

    private String tamper(String cursor) {
        char first = cursor.charAt(0);
        return (first == 'A' ? "B" : "A") + cursor.substring(1);
    }

    private JsonNode json(String body) throws Exception {
        return mapper.readTree(body);
    }

    private int count(String sql, Object... parameters) {
        Integer value = jdbc.queryForObject(sql, Integer.class, parameters);
        return value == null ? 0 : value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockTestConfiguration {
        @Bean
        @Primary
        AdjustableClock adjustableClock() {
            return new AdjustableClock();
        }
    }

    static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> instant =
            new AtomicReference<>(Instant.now());

        void reset() {
            instant.set(Instant.now());
        }

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }

    private record SubmittedFixture(
        UUID invoiceId,
        UUID packageId,
        UUID readinessId,
        long invoiceVersion,
        UUID summaryId
    ) {
    }
}
