package com.vms.workflow.integration;

import com.vms.workflow.api.RetentionDtos.DryRunInput;
import com.vms.workflow.api.RetentionDtos.ScheduleInput;
import com.vms.workflow.application.FinanceCanonicalJson;
import com.vms.workflow.application.PostgresFinancePrivateStorageAdapter;
import com.vms.workflow.application.RetentionPrivacyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.vms.workflow.integration.F04TestSupport.MONTH;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_finance_retention_worker_it",
    "spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
    "spring.datasource.username=test",
    "spring.datasource.password=test",
    "spring.flyway.locations=classpath:db/migration,classpath:db/testdata",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:9/test-jwks",
    "vms.security.issuer=https://issuer.example.test",
    "vms.security.audience=vms-api"
})
class FinanceRetentionWorkerIT {
    private static final UUID ORGANIZATION =
        UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final String ACTOR = "user-sod";

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PostgresFinancePrivateStorageAdapter storage;
    @Autowired
    private FinanceCanonicalJson canonical;
    @Autowired
    private RetentionPrivacyService retention;

    @BeforeEach
    void grantGovernedRetentionAuthority() {
        jdbc.update("""
            INSERT INTO role_assignments(
                id, user_profile_id, organization_id, role_id,
                scope_type, scope_id, status, valid_from
            ) VALUES ('45000000-0000-0000-0000-000000000101',
                      '00000000-0000-0000-0000-000000000223', ?,
                      '11000000-0000-0000-0000-000000000001',
                      'ORGANIZATION', ?, 'ACTIVE', DATE '2020-01-01')
            ON CONFLICT DO NOTHING
            """, ORGANIZATION, ORGANIZATION);
    }

    @Test
    void governedDryRunAndExecutionDisposeOnlyEligibleContent() {
        UUID disposable = insertDueArtifact(false);
        UUID held = insertDueArtifact(true);
        UUID referenced = insertDueArtifact(false);
        referenceFromInvoice(referenced);
        createExportContentSchedule();

        UUID runId = dryRun("governed-disposal");
        assertEquals("ELIGIBLE", decision(runId, disposable));
        assertEquals("HELD", decision(runId, held));
        assertEquals("REFERENCED", decision(runId, referenced));

        retention.execute(ACTOR, runId, "execute-governed-disposal");

        assertEquals("DISPOSED", retentionStatus(disposable));
        assertEquals(0, blobCount(disposable));
        assertEquals("ACTIVE", retentionStatus(held));
        assertEquals(1, blobCount(held));
        assertEquals("ACTIVE", retentionStatus(referenced));
        assertEquals(1, blobCount(referenced));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM f05_artifact_retention_transitions
            WHERE run_id = ? AND artifact_id = ? AND schedule_id = (
                SELECT schedule_id FROM f07_retention_runs WHERE id = ?
            )
            """, runId, disposable, runId));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM f07_retention_proofs
            WHERE run_id = ? AND target_id = ?
              AND proof_type = 'CONTENT_DISPOSAL'
              AND content_deleted
            """, runId, disposable));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM f05_domain_events
            WHERE aggregate_id = ?
              AND event_type = 'f05.artifact.retention.disposed.v1'
            """, disposable));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM f05_audit_events
            WHERE object_id = ?
              AND action = 'ARTIFACT_RETENTION_DISPOSED'
            """, disposable));
    }

    @Test
    void noScheduleAndDirectDisposalBypassRemainRejected() {
        UUID artifact = insertArtifact(false, false);

        assertThrows(RuntimeException.class, () ->
            retention.dryRun(
                ACTOR,
                new DryRunInput(
                    ORGANIZATION, "FINANCE_EVIDENCE_CONTENT",
                    OffsetDateTime.now(ZoneOffset.UTC)),
                "missing-evidence-schedule-" + artifact));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            DELETE FROM f05_private_artifact_blobs WHERE artifact_id = ?
            """, artifact));
        assertThrows(DataAccessException.class, () -> jdbc.update("""
            UPDATE f05_private_artifacts
            SET retention_status = 'DISPOSED',
                disposed_at = CURRENT_TIMESTAMP,
                disposed_by_subject = 'UNAUTHORIZED',
                disposal_reason_code = 'BYPASS'
            WHERE id = ?
            """, artifact));
    }

    @Test
    void independentlyApprovedConcurrentRunsDisposeExactlyOnce()
        throws Exception {
        UUID artifact = insertDueArtifact(false);
        createExportContentSchedule();
        UUID firstRun = dryRun("race-first");
        UUID secondRun = dryRun("race-second");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() ->
                executeAfterBarrier(firstRun, "race-execute-first", ready, start));
            Future<?> second = executor.submit(() ->
                executeAfterBarrier(secondRun, "race-execute-second", ready, start));
            ready.await();
            start.countDown();
            first.get();
            second.get();
        }

        assertEquals("DISPOSED", retentionStatus(artifact));
        assertEquals(0, blobCount(artifact));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM f05_artifact_retention_transitions
            WHERE artifact_id = ? AND action = 'DISPOSED'
            """, artifact));
        assertEquals(1, count("""
            SELECT COUNT(*) FROM f07_retention_proofs
            WHERE target_id = ? AND proof_type = 'CONTENT_DISPOSAL'
            """, artifact));
    }

    private void createExportContentSchedule() {
        retention.createSchedule(
            ACTOR,
            new ScheduleInput(
                ORGANIZATION, "FINANCE_EXPORT_CONTENT", 30,
                "synthetic-approved-finance-export-policy",
                OffsetDateTime.parse("2020-01-01T00:00:00Z")),
            "schedule-" + UUID.randomUUID());
    }

    private UUID dryRun(String key) {
        Map<String, Object> result = retention.dryRun(
            ACTOR,
            new DryRunInput(
                ORGANIZATION, "FINANCE_EXPORT_CONTENT",
                OffsetDateTime.now(ZoneOffset.UTC)),
            key + "-" + UUID.randomUUID());
        return (UUID) result.get("runId");
    }

    private void executeAfterBarrier(
        UUID runId,
        String key,
        CountDownLatch ready,
        CountDownLatch start
    ) {
        try {
            ready.countDown();
            start.await();
            retention.execute(ACTOR, runId, key);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private UUID insertDueArtifact(boolean legalHold) {
        return insertArtifact(legalHold, true);
    }

    private UUID insertArtifact(boolean legalHold, boolean due) {
        UUID id = UUID.randomUUID();
        byte[] bytes = ("retention-" + id).getBytes(StandardCharsets.UTF_8);
        jdbc.update("""
            INSERT INTO f05_private_artifacts(
                id, engagement_month_id, owner_organization_id, logical_type,
                safe_name, media_type, byte_size, content_hash, object_key,
                object_version, classification, retention_class, legal_hold,
                scan_status, scan_engine, scanned_at, provider_status, source,
                recorded_at, uploaded_by_subject, correlation_id
            ) VALUES (?, ?::uuid, ?, 'REPORT_EXPORT', ?,
                      'application/json', ?, ?, ?, ?, 'CONFIDENTIAL',
                      'FINANCE_EXPORT', ?, 'PASSED', 'TEST_SCANNER',
                      CURRENT_TIMESTAMP, 'CONFIGURED', 'TEST',
                      CURRENT_TIMESTAMP - (? * INTERVAL '1 day'),
                      'SYSTEM:TEST', ?)
            """, id, MONTH, ORGANIZATION, "retention-" + id + ".json",
            bytes.length, canonical.sha256Bytes(bytes),
            "exports/test/" + id, "fixture-" + id, legalHold,
            due ? 31 : 0, UUID.randomUUID());
        storage.store(id, bytes);
        return id;
    }

    private void referenceFromInvoice(UUID artifactId) {
        UUID invoiceId = UUID.randomUUID();
        String number = "RET-" + invoiceId;
        jdbc.update("""
            INSERT INTO invoices(
                id, engagement_month_id, vendor_organization_id,
                invoice_type, invoice_number, normalized_invoice_number,
                invoice_date, billing_period_start, billing_period_end,
                currency, status, current_version, optimistic_version,
                created_by_subject, correlation_id
            ) VALUES (?, ?::uuid, ?, 'PRIMARY', ?, ?, ?, ?, ?, 'INR',
                      'DRAFT', 1, 1, 'user-arrow', ?)
            """, invoiceId, MONTH, ORGANIZATION, number, number,
            Date.valueOf(LocalDate.of(2026, 7, 31)),
            Date.valueOf(LocalDate.of(2026, 7, 1)),
            Date.valueOf(LocalDate.of(2026, 7, 31)),
            UUID.randomUUID());
        jdbc.update("""
            INSERT INTO invoice_versions(
                id, invoice_id, version, document_artifact_id,
                metadata_manifest, metadata_hash, source, represented_at,
                created_by_subject, correlation_id
            ) VALUES (?, ?, 1, ?, '{}'::jsonb, repeat('a', 64), 'TEST',
                      CURRENT_TIMESTAMP, 'user-arrow', ?)
            """, UUID.randomUUID(), invoiceId, artifactId, UUID.randomUUID());
    }

    private String decision(UUID runId, UUID artifactId) {
        return jdbc.queryForObject("""
            SELECT decision FROM f07_retention_candidates
            WHERE run_id = ? AND artifact_id = ?
            """, String.class, runId, artifactId);
    }

    private String retentionStatus(UUID artifactId) {
        return jdbc.queryForObject("""
            SELECT retention_status FROM f05_private_artifacts WHERE id = ?
            """, String.class, artifactId);
    }

    private int blobCount(UUID artifactId) {
        return count("""
            SELECT COUNT(*) FROM f05_private_artifact_blobs
            WHERE artifact_id = ?
            """, artifactId);
    }

    private int count(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }
}
