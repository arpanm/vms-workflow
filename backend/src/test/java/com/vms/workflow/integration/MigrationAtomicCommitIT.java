package com.vms.workflow.integration;

import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.api.MigrationDtos;
import com.vms.workflow.application.MigrationService;
import com.vms.workflow.application.MigrationTemplateRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
    "spring.datasource.url="
        + "jdbc:tc:postgresql:18-alpine:///vms_workflow_atomic",
    "spring.datasource.driver-class-name="
        + "org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri="
        + "http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api",
    "vms.finance.cursor-signing-secret="
        + "migration-atomic-test-secret-with-at-least-32-bytes"
})
class MigrationAtomicCommitIT {
    private static final UUID ENGAGEMENT =
        UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID VENDOR =
        UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired
    private MigrationService migrations;

    @Autowired
    private MigrationTemplateRegistry templates;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void lateDuplicateRollsBackAnAllOrNothingBatch() {
        String batchCsv =
            String.join(",", templates.require("01_employees").headers())
                + "\r\n"
                + employeeRow("AF-MIG-ATOMIC-EARLIER",
                    "Synthetic", "Migration")
                + "\r\n"
                + employeeRow("AF-MIG-ATOMIC-RACE",
                    "Competing", "Batch")
                + "\r\n";
        Map<String, Object> batch = validate(upload(
            batchCsv, "employees-all-or-nothing-race.csv"));
        approve(batch, "user-arrow", "MIGRATION_LEAD",
            "atomic-batch-lead");
        approve(batch, "user-governance", "GOVERNANCE",
            "atomic-batch-governance");

        String competitorCsv =
            String.join(",", templates.require("01_employees").headers())
                + "\r\n"
                + employeeRow("AF-MIG-ATOMIC-RACE",
                    "Committed", "Competitor")
                + "\r\n";
        Map<String, Object> competitor = validate(upload(
            competitorCsv, "employees-atomic-competitor.csv"));
        approve(competitor, "user-arrow", "MIGRATION_LEAD",
            "atomic-competitor-lead");
        approve(competitor, "user-governance", "GOVERNANCE",
            "atomic-competitor-governance");
        competitor = migrations.commit(
            "user-arrow", id(competitor), version(competitor), false,
            "atomic-competitor-commit");
        assertEquals("COMPLETED", competitor.get("state"));

        UUID batchId = id(batch);
        DomainConflictException conflict = assertThrows(
            DomainConflictException.class,
            () -> migrations.commit(
                "user-arrow", batchId, version(batch), false,
                "atomic-batch-commit"));
        assertEquals("LATE_DUPLICATE_CONFLICT", conflict.getCode());

        assertEquals("READY_TO_COMMIT", jdbc.queryForObject("""
            SELECT state FROM migration_jobs WHERE id = ?
            """, String.class, batchId));
        assertEquals(0, count("""
            SELECT count(*) FROM employees
            WHERE employee_number = 'AF-MIG-ATOMIC-EARLIER'
            """));
        assertEquals(0, count("""
            SELECT count(*) FROM migration_canonical_facts
            WHERE id IN (
                SELECT fact_id FROM migration_provenance_links
                WHERE job_id = ?)
            """, batchId));
        assertEquals(0, count("""
            SELECT count(*) FROM migration_domain_provenance
            WHERE job_id = ?
            """, batchId));
        assertEquals(2, count("""
            SELECT count(*) FROM migration_rows
            WHERE job_id = ? AND state = 'VALID'
            """, batchId));
        assertEquals(0, count("""
            SELECT count(*) FROM migration_outbox_events
            WHERE event_key LIKE ?
            """, batchId + ":%"));
    }

    private Map<String, Object> upload(String csv, String filename) {
        MockMultipartFile file = new MockMultipartFile(
            "file", filename, "text/csv",
            csv.getBytes(StandardCharsets.UTF_8));
        return migrations.upload(
            "user-arrow",
            file,
            new MigrationDtos.UploadMetadata(
                ENGAGEMENT, VENDOR, null, "01_employees", "1",
                "DRY_RUN", false, null, null));
    }

    private Map<String, Object> validate(Map<String, Object> job) {
        return migrations.validate(
            "user-arrow", id(job), version(job),
            "validate-" + id(job));
    }

    private void approve(
        Map<String, Object> job,
        String subject,
        String role,
        String idempotencyKey
    ) {
        migrations.approve(
            subject,
            id(job),
            new MigrationDtos.ApprovalInput(
                version(job), role, "APPROVED", null, null,
                "Synthetic governed sign-off"),
            idempotencyKey);
    }

    private String employeeRow(
        String employeeNumber,
        String firstName,
        String lastName
    ) {
        return "1,ARROWFOUNDRY," + employeeNumber + ","
            + firstName + "," + lastName + ","
            + firstName + " " + lastName + ","
            + employeeNumber.toLowerCase() + "@example.test,"
            + "2026-01-01,,ACTIVE,Engineer,Platform,,Asia/Kolkata,"
            + "AF_STANDARD,AF_ATTENDANCE,AF_LEAVE,HISTORICAL_IMPORT,,"
            + "ENABLED,APPROVED_SPREADSHEET,synthetic-f06,";
    }

    private UUID id(Map<String, Object> job) {
        return UUID.fromString(String.valueOf(job.get("id")));
    }

    private long version(Map<String, Object> job) {
        return ((Number) job.get("version")).longValue();
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }
}
