package com.vms.workflow.integration;

import com.vms.workflow.application.MigrationRecoveryWorker;
import com.vms.workflow.application.MigrationTemplateRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:postgresql:18-alpine:///vms_workflow",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.finance.cursor-signing-secret="
        + "migration-test-cursor-secret-with-at-least-32-bytes"
})
@AutoConfigureMockMvc
@Transactional
class MigrationWorkflowIT {
    private static final UUID ENGAGEMENT =
        UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID VENDOR =
        UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID JULY =
        UUID.fromString("00000000-0000-0000-0000-000000000602");
    private static final UUID JUNE =
        UUID.fromString("00000000-0000-0000-0000-000000000601");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrationTemplateRegistry templates;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void flywayV17AndGovernedLifecycleProduceOneProvenancedEffect()
        throws Exception {
        assertEquals(1, count("""
            SELECT count(*) FROM flyway_schema_history
            WHERE version = '17' AND success
            """));
        assertEquals(1, count("""
            SELECT count(*) FROM flyway_schema_history
            WHERE version = '20' AND success
            """));
        JsonNode uploaded = uploadEmployee("AF-MIG-101", null);
        UUID uploadedId = UUID.fromString(uploaded.path("id").asText());
        assertDatabaseRejected(() -> jdbc.update("""
            UPDATE migration_jobs
            SET partial_commit = TRUE, version = version + 1
            WHERE id = ?
            """, uploadedId));
        assertEquals(0, count("""
            SELECT count(*) FROM migration_jobs
            WHERE id = ? AND partial_commit
            """, uploadedId));
        JsonNode validated = validate(uploaded);
        assertEquals("READY_TO_COMMIT", validated.path("state").asText());
        assertEquals(1, validated.path("validCount").asInt());

        long approvalVersion = validated.path("version").asLong();
        mvc.perform(post("/api/v1/migrations/jobs/{jobId}/approval",
                validated.path("id").asText())
                .with(token("user-arrow"))
                .header("If-Match", Long.toString(approvalVersion))
                .header("Idempotency-Key", "missing-decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "role":"MIGRATION_LEAD",
                      "reason":"Decision is intentionally omitted"
                    }
                    """.formatted(approvalVersion)))
            .andExpect(status().isBadRequest());

        mvc.perform(post(
                "/api/v1/migrations/reconciliations/{reportId}/sign-offs",
                validated.path("reconciliation")
                    .path("reconciliationId").asText())
                .with(token("user-arrow"))
                .header("Idempotency-Key", "missing-signoff-decision")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "reportHash":"%s",
                      "role":"MIGRATION_LEAD",
                      "reason":"Sign-off decision is intentionally omitted"
                    }
                    """.formatted(validated.path("reconciliation")
                        .path("sha256").asText())))
            .andExpect(status().isBadRequest());

        approve(validated, "user-arrow", "MIGRATION_LEAD", "lead-101");
        approve(validated, "user-governance", "GOVERNANCE", "gov-101");
        mvc.perform(get("/api/v1/migrations/jobs/{jobId}",
                validated.path("id").asText())
                .with(token("user-arrow")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reconciliation.approvals.length()")
                .value(2))
            .andExpect(jsonPath(
                "$.reconciliation.approvals[0].approvalId").isNotEmpty())
            .andExpect(jsonPath(
                "$.reconciliation.approvals[1].reconciliationHash")
                .value(validated.path("reconciliation")
                    .path("sha256").asText()));
        JsonNode committed = commit(validated, "commit-101");

        assertEquals("COMPLETED", committed.path("state").asText());
        UUID jobId = UUID.fromString(committed.path("id").asText());
        assertEquals(1, count("""
            SELECT count(*)
            FROM migration_canonical_facts fact
            JOIN migration_provenance_links provenance
              ON provenance.fact_id = fact.id
            JOIN migration_source_files source
              ON source.id = provenance.source_file_id
            WHERE provenance.job_id = ?
              AND fact.active
              AND fact.represented_at IS NULL
              AND fact.recorded_at >= source.recorded_at
              AND provenance.source_sha256 = source.sha256
            """, jobId));
        assertEquals(1, count("""
            SELECT count(*) FROM migration_reconciliation_reports
            WHERE job_id = ?
            """, jobId));
        assertEquals(1, count("""
            SELECT count(*) FROM migration_outbox_events
            WHERE event_key LIKE ?
            """, jobId + ":%"));
    }

    @Test
    void identicalUploadIsIdempotentAndCrossScopeCannotDiscoverIt()
        throws Exception {
        JsonNode first = uploadEmployee("AF-MIG-102", null);
        JsonNode second = uploadEmployee("AF-MIG-102", null);

        assertEquals(first.path("id").asText(), second.path("id").asText());
        assertEquals(1, count("""
            SELECT count(*) FROM migration_source_files
            WHERE template_code = '01_employees'
              AND sha256 = ?
            """, first.path("sourceSha256").asText()));

        mvc.perform(get("/api/v1/migrations/jobs/{jobId}",
                first.path("id").asText())
                .with(token("user-northstar")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value(
                "Resource not found."));
        mvc.perform(get("/api/v1/migrations/jobs/{jobId}", UUID.randomUUID())
                .with(token("user-northstar")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));

        JsonNode validated = validate(first);
        String reportId = validated.path("reconciliation")
            .path("reconciliationId").asText();
        String reportHash = validated.path("reconciliation")
            .path("sha256").asText();
        String signOff = """
            {
              "reportHash":"%s",
              "role":"MIGRATION_LEAD",
              "decision":"APPROVED",
              "reason":"Non-enumerability proof"
            }
            """.formatted(reportHash);
        mvc.perform(post(
                "/api/v1/migrations/reconciliations/{reportId}/sign-offs",
                reportId)
                .with(token("user-northstar"))
                .header("Idempotency-Key", "masked-existing-report")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signOff))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
        mvc.perform(post(
                "/api/v1/migrations/reconciliations/{reportId}/sign-offs",
                UUID.randomUUID())
                .with(token("user-northstar"))
                .header("Idempotency-Key", "masked-absent-report")
                .contentType(MediaType.APPLICATION_JSON)
                .content(signOff))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.detail").value("Resource not found."));
    }

    @Test
    void prohibitedCommercialHeaderIsRejectedBeforeSourceRetention()
        throws Exception {
        String header = String.join(",",
            templates.require("01_employees").headers()) + ",salary";
        String row = employeeRow("AF-MIG-103") + ",999999";
        int before = count("SELECT count(*) FROM migration_source_files");

        mvc.perform(uploadRequest(
                "01_employees", header + "\r\n" + row + "\r\n", null))
            .andExpect(status().isBadRequest());

        assertEquals(before,
            count("SELECT count(*) FROM migration_source_files"));
        assertEquals(0, count("""
            SELECT count(*) FROM migration_rows
            WHERE normalized_payload::text ILIKE '%999999%'
            """));
    }

    @Test
    void rawAndDailyAttendanceCannotBothOwnOneEmployeeDay()
        throws Exception {
        seedAttendancePredecessors();
        String raw = String.join(",",
            templates.require("07a_attendance_punches").headers())
            + "\r\n"
            + "1,ARROWFOUNDRY,PUNCH-MIG-1,AF-001,IN,"
            + "2026-07-08T09:00:00+05:30,Asia/Kolkata,OTHER,"
            + "synthetic-raw,,,,,\r\n";
        JsonNode rawJob = upload(
            "07a_attendance_punches", raw, null, "raw-authority.csv");
        rawJob = validate(rawJob);
        approve(rawJob, "user-arrow", "MIGRATION_LEAD", "raw-lead");
        approve(rawJob, "user-governance", "GOVERNANCE", "raw-gov");
        rawJob = commit(rawJob, "raw-commit");
        assertEquals("COMPLETED", rawJob.path("state").asText());

        String daily = String.join(",",
            templates.require("07b_attendance_daily").headers())
            + "\r\n"
            + "1,ARROWFOUNDRY,AF-001,2026-07-08,Asia/Kolkata,"
            + "WORKING,540,2026-07-08T09:00:00+05:30,"
            + "2026-07-08T18:00:00+05:30,480,PRESENT,,,,NONE,,"
            + "2026-07-08T18:30:00+05:30,"
            + "2026-07-08T18:30:00+05:30,OTHER,synthetic-daily,,\r\n";
        JsonNode dailyJob = upload(
            "07b_attendance_daily", daily, null, "daily-authority.csv");
        dailyJob = validate(dailyJob);
        approve(dailyJob, "user-arrow", "MIGRATION_LEAD", "daily-lead");
        approve(dailyJob, "user-governance", "GOVERNANCE", "daily-gov");

        mvc.perform(post("/api/v1/migrations/jobs/{jobId}/commit",
                dailyJob.path("id").asText())
                .with(token("user-arrow"))
                .header("If-Match", dailyJob.path("version").asText())
                .header("Idempotency-Key", "daily-commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":%d,"partialCommit":false}
                    """.formatted(dailyJob.path("version").asLong())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("ATTENDANCE_SOURCE_CONFLICT"));
        assertEquals(1, count("""
            SELECT count(*) FROM migration_attendance_authorities
            WHERE engagement_id = ? AND attendance_date = '2026-07-08'
            """, ENGAGEMENT));
    }

    @Test
    void consumedBatchRequiresReopenAndRetroUsesCurrentRecordedTime()
        throws Exception {
        JsonNode job = validate(uploadEmployee("AF-MIG-104", JULY));
        approve(job, "user-arrow", "MIGRATION_LEAD", "rollback-lead");
        approve(job, "user-governance", "BUSINESS", "rollback-business");
        job = commit(job, "rollback-commit");
        jdbc.update("""
            INSERT INTO attendance_snapshot_versions
              (id, engagement_month_id, version, checksum, day_count,
               closed_by_subject)
            VALUES (?, ?, 99, ?, 0, 'test-migration-consumer')
            """, UUID.randomUUID(), JULY, "a".repeat(64));

        mvc.perform(post("/api/v1/migrations/jobs/{jobId}/rollback",
                job.path("id").asText())
                .with(token("user-arrow"))
                .header("If-Match", job.path("version").asText())
                .header("Idempotency-Key", "rollback-denied")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":%d,"reason":"Consumed snapshot exists"}
                    """.formatted(job.path("version").asLong())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ROLLBACK_REQUIRES_REOPEN"));
        assertEquals("COMPLETED", jdbc.queryForObject("""
            SELECT state FROM migration_jobs WHERE id = ?
            """, String.class, UUID.fromString(job.path("id").asText())));

        String retroBody = mvc.perform(post("/api/v1/migrations/retro-requests")
                .with(token("user-governance"))
                .header("Idempotency-Key", "retro-current-time")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "engagementId":"%s",
                      "engagementMonthId":"%s",
                      "requestType":"CONFIRMATION",
                      "representedMonth":"2026-06-01",
                      "reason":"Original approver unavailable",
                      "originalActorUnavailable":true,
                      "delegationEvidenceReference":"delegation-case-101"
                    }
                    """.formatted(ENGAGEMENT, JUNE)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("PENDING"))
            .andExpect(jsonPath("$.representedMonth").value("2026-06-01"))
            .andReturn().getResponse().getContentAsString();
        JsonNode retro = mapper.readTree(retroBody);
        assertNull(retro.path("decisionAt").isNull()
            ? null : retro.path("decisionAt").asText());
        OffsetDateTime createdAt =
            OffsetDateTime.parse(retro.path("createdAt").asText());
        assertTrue(createdAt.isAfter(
            OffsetDateTime.parse("2026-06-30T23:59:59Z")));
        assertNotEquals("2026-06-01",
            createdAt.toLocalDate().toString());
    }

    @Test
    void unconsumedRollbackRemovesEmployeeAndImmutableLedgerEffects()
        throws Exception {
        JsonNode employee = validate(
            uploadEmployee("AF-MIG-ROLLBACK", null));
        approve(employee, "user-arrow", "MIGRATION_LEAD",
            "comp-employee-lead");
        approve(employee, "user-governance", "GOVERNANCE",
            "comp-employee-gov");
        employee = commit(employee, "comp-employee-commit");
        UUID employeeJob = UUID.fromString(employee.path("id").asText());
        rollback(employee, "comp-employee-rollback");

        assertEquals(0, count("""
            SELECT count(*) FROM employees
            WHERE employee_number = 'AF-MIG-ROLLBACK'
            """));
        assertCompensated(employeeJob);

        seedCompletedPredecessor("01_employees");
        String balanceCsv =
            String.join(",", templates.require("05_leave_balances").headers())
                + "\r\n"
                + "1,ARROWFOUNDRY,AF-001,CL,MIGRATION_CORRECTION,1.5,"
                + "2026-07-01,REF-ROLLBACK,Historical correction,"
                + "governance@example.test,2026-07-01T10:00:00+05:30,"
                + "IDEM-ROLLBACK-LEDGER,OTHER,synthetic-ledger,\r\n";
        JsonNode balance = validate(upload(
            "05_leave_balances", balanceCsv, null,
            "leave-balance-rollback.csv"));
        approve(balance, "user-arrow", "MIGRATION_LEAD",
            "comp-ledger-lead");
        approve(balance, "user-governance", "GOVERNANCE",
            "comp-ledger-gov");
        balance = commit(balance, "comp-ledger-commit");
        UUID balanceJob = UUID.fromString(balance.path("id").asText());
        assertEquals(1, count("""
            SELECT count(*) FROM leave_balance_ledger
            WHERE idempotency_key = 'IDEM-ROLLBACK-LEDGER'
            """));
        UUID ledgerId = jdbc.queryForObject("""
            SELECT id FROM leave_balance_ledger
            WHERE idempotency_key = 'IDEM-ROLLBACK-LEDGER'
            """, UUID.class);
        assertDatabaseRejected(() -> {
            jdbc.queryForObject("""
                SELECT set_config(
                    'vms.migration_compensation', ?, TRUE)
                """, String.class, UUID.randomUUID().toString());
            jdbc.update(
                "DELETE FROM leave_balance_ledger WHERE id = ?",
                ledgerId);
        });
        assertEquals(1, count("""
            SELECT count(*) FROM leave_balance_ledger WHERE id = ?
            """, ledgerId));

        rollback(balance, "comp-ledger-rollback");

        assertEquals(0, count("""
            SELECT count(*) FROM leave_balance_ledger
            WHERE idempotency_key = 'IDEM-ROLLBACK-LEDGER'
            """));
        assertCompensated(balanceJob);
    }

    @Test
    void invoiceRollbackDeactivatesInvoiceAndRemovesImportedVersion()
        throws Exception {
        seedCompletedPredecessor("11_business_confirmations");
        String invoiceCsv =
            String.join(",", templates.require("12_invoices").headers())
                + "\r\n"
                + String.join(",", List.of(
                    "1", "ARROWFOUNDRY", "RELIANCE_INTELLIGENCE",
                    "RI-AF-2026", "2026-07-01", "INV-MIG-ROLLBACK",
                    "2026-07-31", "2026-07-01", "2026-07-31",
                    "PO-MIG", "WO-MIG", "INR", "100", "18", "118",
                    "invoice-migration.pdf", "a".repeat(64),
                    "2026-07-31T12:00:00+05:30", "", "", "", "",
                    "", "", "OTHER", "synthetic-invoice", ""))
                + "\r\n";
        JsonNode invoice = validate(upload(
            "12_invoices", invoiceCsv, JULY,
            "invoice-rollback.csv"));
        approve(invoice, "user-arrow", "MIGRATION_LEAD",
            "comp-invoice-lead");
        approve(invoice, "user-governance", "GOVERNANCE",
            "comp-invoice-gov");
        invoice = commit(invoice, "comp-invoice-commit");
        UUID invoiceJob = UUID.fromString(invoice.path("id").asText());
        assertEquals(1, count("""
            SELECT count(*) FROM invoice_versions version
            JOIN invoices invoice ON invoice.id = version.invoice_id
            WHERE invoice.invoice_number = 'INV-MIG-ROLLBACK'
              AND version.source = 'HISTORICAL_MIGRATION'
            """));

        rollback(invoice, "comp-invoice-rollback");

        assertEquals("CANCELLED", jdbc.queryForObject("""
            SELECT status FROM invoices
            WHERE invoice_number = 'INV-MIG-ROLLBACK'
            """, String.class));
        assertEquals(0, count("""
            SELECT count(*) FROM invoice_versions version
            JOIN invoices invoice ON invoice.id = version.invoice_id
            WHERE invoice.invoice_number = 'INV-MIG-ROLLBACK'
              AND version.source = 'HISTORICAL_MIGRATION'
            """));
        assertCompensated(invoiceJob);
    }

    @Test
    void deliverableRollbackRemovesOwnedAssignmentsAndDependencies()
        throws Exception {
        seedCompletedPredecessor("02_employee_allocations");
        String csv =
            String.join(",", templates.require("08_deliverables").headers())
                + "\r\n"
                + String.join(",", List.of(
                    "1", "ARROWFOUNDRY", "RI-AF-2026", "2026-06",
                    "PLAN-F06-ROLLBACK", "1", "HISTORICAL_RECONSTRUCTED",
                    "APPROVED", "2026-05-30T10:00:00Z",
                    "ravi@reliance.example", "F06-ROLLBACK-DELIVERY",
                    "AGENTIC_SHOPOS", "Rollback delivery child effects",
                    "Exercises delivery-owned compensation",
                    "Prove guarded child rollback", "ravi@reliance.example",
                    "alice@arrowfoundry.example", "P1", "2026-06-30",
                    "PLATFORM", "Assignment removed|Dependency removed",
                    "Database assertions", "External archive",
                    "Synthetic local prerequisites", "AF-001", "",
                    "OTHER", "f06-delivery-rollback", ""))
                + "\r\n";
        JsonNode job = validate(upload(
            "08_deliverables", csv, JUNE, "delivery-rollback.csv"));
        approve(job, "user-arrow", "MIGRATION_LEAD",
            "delivery-rollback-lead");
        approve(job, "user-governance", "GOVERNANCE",
            "delivery-rollback-governance");
        job = commit(job, "delivery-rollback-commit");
        UUID jobId = UUID.fromString(job.path("id").asText());
        UUID assignmentId = jdbc.queryForObject("""
            SELECT domain_record_id
            FROM migration_domain_provenance
            WHERE job_id = ?
              AND domain_table = 'delivery_employee_assignments'
              AND active
            """, UUID.class, jobId);
        UUID dependencyId = jdbc.queryForObject("""
            SELECT domain_record_id
            FROM migration_domain_provenance
            WHERE job_id = ?
              AND domain_table = 'delivery_dependencies'
              AND active
            """, UUID.class, jobId);

        rollback(job, "delivery-rollback-compensate");

        assertEquals(0, count("""
            SELECT count(*) FROM delivery_employee_assignments WHERE id = ?
            """, assignmentId));
        assertEquals(0, count("""
            SELECT count(*) FROM delivery_dependencies WHERE id = ?
            """, dependencyId));
        assertEquals(2, count("""
            SELECT count(*) FROM migration_domain_compensations
            WHERE provenance_id IN (
              SELECT id FROM migration_domain_provenance
              WHERE job_id = ? AND domain_table IN (
                'delivery_employee_assignments', 'delivery_dependencies'))
            """, jobId));
        assertCompensated(jobId);
    }

    @Test
    void rejectedRowReprocessStagesOnlyRejectedParentRows()
        throws Exception {
        String validRow = employeeRow("AF-MIG-PARTIAL");
        String invalidRow = employeeRow("AF-MIG-REJECT")
            .replace("af-mig-reject@example.test", "");
        String csv =
            String.join(",", templates.require("01_employees").headers())
                + "\r\n" + validRow + "\r\n" + invalidRow + "\r\n";
        JsonNode parent = mapper.readTree(mvc.perform(uploadRequest(
                "01_employees", csv, null, "partial-employees.csv", true))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        parent = validate(parent);
        assertEquals(2, parent.path("totalRows").asInt());
        assertEquals(1, parent.path("validRows").asInt());
        assertEquals(1, parent.path("invalidRows").asInt());
        mvc.perform(post("/api/v1/migrations/jobs/{jobId}/commit",
                parent.path("id").asText())
                .with(token("user-arrow"))
                .header("If-Match", parent.path("version").asText())
                .header("Idempotency-Key", "partial-policy-mismatch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":%d,"partialCommit":false}
                    """.formatted(parent.path("version").asLong())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("COMMIT_POLICY_MISMATCH"));
        approve(parent, "user-arrow", "MIGRATION_LEAD", "partial-lead");
        approve(parent, "user-governance", "GOVERNANCE", "partial-gov");
        parent = commit(parent, "partial-commit");
        assertEquals("COMPLETED_WITH_ERRORS", parent.path("state").asText());

        long version = parent.path("version").asLong();
        JsonNode child = mapper.readTree(mvc.perform(post(
                    "/api/v1/migrations/jobs/{jobId}/reprocess",
                    parent.path("id").asText())
                .with(token("user-arrow"))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key", "partial-reprocess")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":%d}
                    """.formatted(version)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
        child = validate(child);

        assertEquals(1, child.path("totalRows").asInt());
        assertEquals(0, child.path("validRows").asInt());
        assertEquals(1, child.path("invalidRows").asInt());
        assertEquals(1, count("""
            SELECT count(*) FROM migration_rows WHERE job_id = ?
            """, UUID.fromString(child.path("id").asText())));
        assertEquals(0, count("""
            SELECT count(*) FROM migration_rows
            WHERE job_id = ? AND normalized_payload->>'employee_number'
                = 'AF-MIG-PARTIAL'
            """, UUID.fromString(child.path("id").asText())));
        mvc.perform(get("/api/v1/migrations/jobs/{jobId}/rows",
                child.path("id").asText())
                .with(token("user-arrow"))
                .queryParam("limit", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void jobListUsesActorBoundOpaqueCursorAndExactTotalCount()
        throws Exception {
        uploadEmployee("AF-MIG-CURSOR-1", null);
        uploadEmployee("AF-MIG-CURSOR-2", null);
        uploadEmployee("AF-MIG-CURSOR-3", null);

        JsonNode first = mapper.readTree(mvc.perform(get(
                    "/api/v1/migrations/jobs")
                .with(token("user-arrow"))
                .queryParam("engagementId", ENGAGEMENT.toString())
                .queryParam("limit", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andExpect(jsonPath("$.totalCount").value(3))
            .andReturn().getResponse().getContentAsString());
        String cursor = first.path("nextCursor").asText();
        assertTrue(cursor.contains("."));

        mvc.perform(get("/api/v1/migrations/jobs")
                .with(token("user-arrow"))
                .queryParam("engagementId", ENGAGEMENT.toString())
                .queryParam("limit", "2")
                .queryParam("cursor", cursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.totalCount").value(3));

        mvc.perform(get("/api/v1/migrations/jobs")
                .with(token("user-arrow"))
                .queryParam("engagementId", ENGAGEMENT.toString())
                .queryParam("cursor", cursor + "tampered"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void signOffAuthorityRejectsForgeryAmbiguityExpiryAndSameOrganization()
        throws Exception {
        JsonNode job = validate(uploadEmployee("AF-MIG-SOD", null));
        long version = job.path("version").asLong();
        String reportId =
            job.path("reconciliation").path("reconciliationId").asText();
        String reportHash =
            job.path("reconciliation").path("sha256").asText();

        approvalRequest(job, "user-arrow", "GOVERNANCE_REVIEWER",
            reportId, reportHash, "sod-forged")
            .andExpect(status().isForbidden());
        approvalRequest(job, "user-arrow", "MIGRATION_LEAD",
            reportId, "0".repeat(64), "sod-stale-hash")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(
                "RECONCILIATION_HASH_STALE"));

        UUID ambiguousAssignment = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO role_assignments
              (id, user_profile_id, organization_id, role_id, scope_type,
               scope_id, status, valid_from)
            VALUES (?, '00000000-0000-0000-0000-000000000201',
                    '00000000-0000-0000-0000-000000000102',
                    '11000000-0000-0000-0000-000000000009',
                    'ENGAGEMENT', ?, 'ACTIVE', '2020-01-01')
            """, ambiguousAssignment, ENGAGEMENT);
        approvalRequest(job, "user-arrow", "MIGRATION_LEAD",
            reportId, reportHash, "sod-ambiguous")
            .andExpect(status().isForbidden());
        jdbc.update("DELETE FROM role_assignments WHERE id = ?",
            ambiguousAssignment);

        jdbc.update("""
            UPDATE role_assignments SET status = 'INACTIVE'
            WHERE id = '12000000-0000-0000-0000-000000000041'
            """);
        approvalRequest(job, "user-governance", "GOVERNANCE_REVIEWER",
            reportId, reportHash, "sod-disabled")
            .andExpect(status().isForbidden());
        jdbc.update("""
            UPDATE role_assignments
            SET status = 'ACTIVE', valid_to = '2025-12-31'
            WHERE id = '12000000-0000-0000-0000-000000000041'
            """);
        approvalRequest(job, "user-governance", "GOVERNANCE_REVIEWER",
            reportId, reportHash, "sod-expired")
            .andExpect(status().isForbidden());
        jdbc.update("""
            UPDATE role_assignments
            SET valid_to = NULL
            WHERE id = '12000000-0000-0000-0000-000000000041'
            """);
        UUID sameOrganizationReviewer = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO role_assignments
              (id, user_profile_id, organization_id, role_id, scope_type,
               scope_id, status, valid_from)
            VALUES (?, '00000000-0000-0000-0000-000000000226',
                    '00000000-0000-0000-0000-000000000101',
                    '11000000-0000-0000-0000-000000000009',
                    'ENGAGEMENT', ?, 'ACTIVE', '2020-01-01')
            """, sameOrganizationReviewer, ENGAGEMENT);

        approvalRequest(job, "user-arrow", "MIGRATION_LEAD",
            reportId, reportHash, "sod-lead")
            .andExpect(status().isOk());
        approvalRequest(job, "user-reviewer", "GOVERNANCE_REVIEWER",
            reportId, reportHash, "sod-same-org-reviewer")
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/migrations/jobs/{jobId}/commit",
                job.path("id").asText())
                .with(token("user-arrow"))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key", "sod-same-org-commit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":%d,"partialCommit":false}
                    """.formatted(version)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(
                "MIGRATION_DUAL_APPROVAL_REQUIRED"));
    }

    @Test
    void cancelledRetryCreatesOneAppendOnlyIdempotentReplay()
        throws Exception {
        JsonNode original = uploadEmployee("AF-MIG-RETRY-CANCELLED", null);
        UUID originalId = UUID.fromString(original.path("id").asText());
        long originalVersion = original.path("version").asLong();
        JsonNode cancelled = mapper.readTree(mvc.perform(post(
                    "/api/v1/migrations/jobs/{jobId}/cancel", originalId)
                .with(token("user-arrow"))
                .header("If-Match", Long.toString(originalVersion))
                .header("Idempotency-Key", "retry-cancel-original")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "reason":"Cancel before governed replay"
                    }
                    """.formatted(originalVersion)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CANCELLED"))
            .andReturn().getResponse().getContentAsString());
        long cancelledVersion = cancelled.path("version").asLong();

        JsonNode replay = mapper.readTree(mvc.perform(post(
                    "/api/v1/migrations/jobs/{jobId}/retry", originalId)
                .with(token("user-arrow"))
                .header("If-Match", Long.toString(cancelledVersion))
                .header("Idempotency-Key", "retry-cancelled-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "reason":"Replay immutable cancelled history"
                    }
                    """.formatted(cancelledVersion)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("UPLOADED"))
            .andReturn().getResponse().getContentAsString());
        UUID replayId = UUID.fromString(replay.path("id").asText());
        assertNotEquals(originalId, replayId);

        mvc.perform(post("/api/v1/migrations/jobs/{jobId}/retry", originalId)
                .with(token("user-arrow"))
                .header("If-Match", Long.toString(cancelledVersion))
                .header("Idempotency-Key", "retry-cancelled-replay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "reason":"Replay immutable cancelled history"
                    }
                    """.formatted(cancelledVersion)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(replayId.toString()))
            .andExpect(jsonPath("$.state").value("UPLOADED"));

        assertEquals("CANCELLED", jdbc.queryForObject("""
            SELECT state FROM migration_jobs WHERE id = ?
            """, String.class, originalId));
        assertEquals(1, count("""
            SELECT count(*) FROM migration_jobs
            WHERE parent_job_id = ? AND prior_job_id = ?
            """, originalId, originalId));
        assertEquals(1, count("""
            SELECT count(*) FROM migration_decisions
            WHERE job_id = ? AND decision = 'REPLAY'
              AND idempotency_key = 'retry-cancelled-replay'
            """, replayId));
    }

    @Test
    void retryRejectsImmutableStagingAndUnsupportedTerminalState()
        throws Exception {
        assertTrue(applicationContext
            .getBeansOfType(MigrationRecoveryWorker.class).isEmpty(),
            "Recovery worker must remain opt-in.");

        JsonNode staged = validate(
            uploadEmployee("AF-MIG-RETRY-STAGED", null));
        UUID stagedId = UUID.fromString(staged.path("id").asText());
        jdbc.update("""
            UPDATE migration_jobs
            SET state = 'FAILED', version = version + 1
            WHERE id = ?
            """, stagedId);
        long stagedVersion = jdbc.queryForObject("""
            SELECT version FROM migration_jobs WHERE id = ?
            """, Long.class, stagedId);
        mvc.perform(post("/api/v1/migrations/jobs/{jobId}/retry", stagedId)
                .with(token("user-arrow"))
                .header("If-Match", stagedVersion)
                .header("Idempotency-Key", "retry-staged-immutable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "reason":"Attempt to replace staged evidence"
                    }
                    """.formatted(stagedVersion)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code")
                .value("VALIDATION_ATTEMPT_IMMUTABLE"));

        JsonNode terminal = validate(
            uploadEmployee("AF-MIG-RETRY-TERMINAL", null));
        approve(terminal, "user-arrow", "MIGRATION_LEAD",
            "retry-terminal-lead");
        approve(terminal, "user-governance", "GOVERNANCE",
            "retry-terminal-governance");
        terminal = commit(terminal, "retry-terminal-commit");
        mvc.perform(post("/api/v1/migrations/jobs/{jobId}/retry",
                terminal.path("id").asText())
                .with(token("user-arrow"))
                .header("If-Match", terminal.path("version").asText())
                .header("Idempotency-Key", "retry-terminal-rejected")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "reason":"Completed history cannot be retried"
                    }
                    """.formatted(terminal.path("version").asLong())))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("JOB_RETRY_NOT_ALLOWED"));
    }

    private JsonNode uploadEmployee(String employeeNumber, UUID monthId)
        throws Exception {
        return upload("01_employees",
            String.join(",", templates.require("01_employees").headers())
                + "\r\n" + employeeRow(employeeNumber) + "\r\n",
            monthId, "employees-" + employeeNumber + ".csv");
    }

    private String employeeRow(String employeeNumber) {
        return "1,ARROWFOUNDRY," + employeeNumber
            + ",Synthetic,Migration,Synthetic Migration,"
            + employeeNumber.toLowerCase() + "@example.test,"
            + "2026-01-01,,ACTIVE,Engineer,Platform,,Asia/Kolkata,"
            + "AF_STANDARD,AF_ATTENDANCE,AF_LEAVE,HISTORICAL_IMPORT,,"
            + "ENABLED,APPROVED_SPREADSHEET,synthetic-f06,";
    }

    private JsonNode upload(
        String templateCode,
        String csv,
        UUID monthId,
        String filename
    ) throws Exception {
        return mapper.readTree(mvc.perform(
                uploadRequest(templateCode, csv, monthId, filename))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
    uploadRequest(String templateCode, String csv, UUID monthId) {
        return uploadRequest(templateCode, csv, monthId,
            "unsafe-commercial.csv");
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
    uploadRequest(
        String templateCode,
        String csv,
        UUID monthId,
        String filename
    ) {
        return uploadRequest(templateCode, csv, monthId, filename, false);
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
    uploadRequest(
        String templateCode,
        String csv,
        UUID monthId,
        String filename,
        boolean partialCommit
    ) {
        String month = monthId == null
            ? "" : ",\"engagementMonthId\":\"" + monthId + "\"";
        MockMultipartFile file = new MockMultipartFile(
            "file", filename, "text/csv",
            csv.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile metadata = new MockMultipartFile(
            "metadata", "", "application/json", """
            {
              "engagementId":"%s",
              "organizationId":"%s",
              "templateCode":"%s",
              "templateVersion":"1",
              "mode":"DRY_RUN",
              "partialCommit":%s
              %s
            }
            """.formatted(
                ENGAGEMENT, VENDOR, templateCode, partialCommit, month)
            .getBytes(StandardCharsets.UTF_8));
        return multipart("/api/v1/migrations/jobs")
            .file(file).file(metadata).with(token("user-arrow"));
    }

    private JsonNode validate(JsonNode job) throws Exception {
        long version = job.path("version").asLong();
        return mapper.readTree(mvc.perform(post(
                    "/api/v1/migrations/jobs/{jobId}/validate",
                    job.path("id").asText())
                .with(token("user-arrow"))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key",
                    "validate-" + job.path("id").asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":%d}
                    """.formatted(version)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private void approve(
        JsonNode job,
        String subject,
        String role,
        String key
    ) throws Exception {
        long version = job.path("version").asLong();
        mvc.perform(post("/api/v1/migrations/jobs/{jobId}/approval",
                job.path("id").asText())
                .with(token(subject))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "expectedVersion":%d,
                      "role":"%s",
                      "decision":"APPROVED",
                      "reason":"Synthetic governed sign-off"
                    }
                    """.formatted(version, role)))
            .andExpect(status().isOk());
    }

    private org.springframework.test.web.servlet.ResultActions approvalRequest(
        JsonNode job,
        String subject,
        String role,
        String reconciliationId,
        String reconciliationHash,
        String key
    ) throws Exception {
        long version = job.path("version").asLong();
        return mvc.perform(post(
                "/api/v1/migrations/jobs/{jobId}/approval",
                job.path("id").asText())
            .with(token(subject))
            .header("If-Match", Long.toString(version))
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "expectedVersion":%d,
                  "role":"%s",
                  "decision":"APPROVED",
                  "reconciliationId":"%s",
                  "reconciliationHash":"%s",
                  "reason":"Adversarial authority proof"
                }
                """.formatted(
                    version, role, reconciliationId, reconciliationHash)));
    }

    private JsonNode commit(JsonNode job, String key) throws Exception {
        long version = job.path("version").asLong();
        return mapper.readTree(mvc.perform(post(
                    "/api/v1/migrations/jobs/{jobId}/commit",
                    job.path("id").asText())
                .with(token("user-arrow"))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":%d,"partialCommit":%s}
                    """.formatted(
                        version, job.path("partialCommit").asBoolean(false))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
    }

    private void seedAttendancePredecessors() {
        for (String template : List.of(
            "02_employee_allocations", "03_holidays",
            "04_employee_date_overrides", "06_leave_requests")) {
            seedCompletedPredecessor(template);
        }
    }

    private void seedCompletedPredecessor(String template) {
            UUID sourceId = UUID.randomUUID();
            String hash = MigrationTemplateRegistry.sha256(
                "dependency:" + template);
            jdbc.update("""
                INSERT INTO migration_source_files
                  (id, engagement_id, organization_id, template_code,
                   template_version, safe_filename, media_type, byte_size,
                   sha256, scan_status, uploaded_by_subject, retention_until)
                VALUES (?, ?, ?, ?, '1', ?, 'text/csv', 1, ?, 'PASSED',
                        'test-predecessor', '2033-07-01')
                """, sourceId, ENGAGEMENT, VENDOR, template,
                template + ".csv", hash);
            jdbc.update("""
                INSERT INTO migration_jobs
                  (id, source_file_id, engagement_id, organization_id,
                   template_code, template_version, mode, state,
                   requested_by_subject)
                VALUES (?, ?, ?, ?, ?, '1', 'DRY_RUN', 'COMPLETED',
                        'test-predecessor')
                """, UUID.randomUUID(), sourceId, ENGAGEMENT, VENDOR,
                template);
    }

    private JsonNode rollback(JsonNode job, String key) throws Exception {
        long version = job.path("version").asLong();
        return mapper.readTree(mvc.perform(post(
                    "/api/v1/migrations/jobs/{jobId}/rollback",
                    job.path("id").asText())
                .with(token("user-arrow"))
                .header("If-Match", Long.toString(version))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":%d,"reason":"Integration compensation"}
                    """.formatted(version)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("ROLLED_BACK"))
            .andReturn().getResponse().getContentAsString());
    }

    private void assertCompensated(UUID jobId) {
        int provenanceCount = count("""
            SELECT count(*) FROM migration_domain_provenance
            WHERE job_id = ?
            """, jobId);
        assertTrue(provenanceCount > 0);
        assertEquals(provenanceCount, count("""
            SELECT count(*) FROM migration_domain_provenance
            WHERE job_id = ? AND NOT active
              AND compensation_record_id IS NOT NULL
              AND compensated_at IS NOT NULL
            """, jobId));
        assertEquals(provenanceCount, count("""
            SELECT count(*)
            FROM migration_domain_compensations compensation
            JOIN migration_domain_provenance provenance
              ON provenance.id = compensation.provenance_id
            WHERE provenance.job_id = ?
            """, jobId));
        assertEquals(1, count("""
            SELECT count(*)
            FROM migration_jobs job
            JOIN migration_source_files source
              ON source.id = job.source_file_id
            JOIN migration_source_blobs blob
              ON blob.source_file_id = source.id
            WHERE job.id = ?
            """, jobId));
        assertEquals(1, count("""
            SELECT count(*) FROM migration_audit_events
            WHERE job_id = ?
              AND event_type = 'MIGRATION_BATCH_COMPENSATED'
            """, jobId));
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private void assertDatabaseRejected(Runnable operation) {
        jdbc.execute("SAVEPOINT f06_expected_rejection");
        try {
            assertThrows(DataAccessException.class, operation::run);
        } finally {
            jdbc.execute("ROLLBACK TO SAVEPOINT f06_expected_rejection");
            jdbc.execute("RELEASE SAVEPOINT f06_expected_rejection");
        }
    }

    private static RequestPostProcessor token(String subject) {
        return jwt().jwt(value ->
            value.subject(subject).audience(List.of("vms-api")));
    }
}
