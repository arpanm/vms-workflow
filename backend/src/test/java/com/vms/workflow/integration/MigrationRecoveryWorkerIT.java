package com.vms.workflow.integration;

import com.vms.workflow.api.MigrationDtos;
import com.vms.workflow.application.MigrationMalwareScanner;
import com.vms.workflow.application.MigrationRecoveryWorker;
import com.vms.workflow.application.MigrationService;
import com.vms.workflow.application.MigrationTemplateRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(properties = {
    "spring.datasource.url="
        + "jdbc:tc:vmspostgresql:18-alpine:///vms_workflow_migration_recovery",
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
        + "migration-recovery-test-secret-with-at-least-32-bytes",
    "vms.migration.worker-enabled=true",
    "vms.migration.worker-batch-size=1",
    "vms.migration.worker-lease-seconds=30",
    "vms.migration.worker-recovery-age-seconds=60",
    "vms.migration.worker-initial-delay=PT1H"
})
@Import(MigrationRecoveryWorkerIT.ScannerConfiguration.class)
class MigrationRecoveryWorkerIT {
    private static final UUID ENGAGEMENT =
        UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final UUID VENDOR =
        UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Autowired
    private MigrationRecoveryWorker worker;

    @Autowired
    private MigrationService migrations;

    @Autowired
    private MigrationTemplateRegistry templates;

    @Autowired
    private ControllableScanner scanner;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetScanner() {
        scanner.mode.set(ScannerMode.PENDING);
    }

    @Test
    void expiredLeaseRecoversOnceFromCheckpointWithoutDuplicateEffects() {
        scanner.mode.set(ScannerMode.PASSED);
        Map<String, Object> uploaded = upload("AF-MIG-WORKER-LEASE");
        UUID jobId = id(uploaded);

        jdbc.update("""
            UPDATE migration_jobs
            SET state = 'SCANNING',
                lease_owner = 'crashed-worker',
                lease_until = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
                version = version + 1
            WHERE id = ?
            """, jobId);
        age(jobId);

        worker.runOnce();
        assertEquals("SCANNING", text("""
            SELECT state FROM migration_jobs WHERE id = ?
            """, jobId));
        assertEquals(0, integer("""
            SELECT retry_count FROM migration_jobs WHERE id = ?
            """, jobId));

        jdbc.update("""
            UPDATE migration_jobs
            SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1 second',
                version = version + 1
            WHERE id = ?
            """, jobId);
        age(jobId);
        worker.runOnce();

        assertEquals("READY_TO_COMMIT", text("""
            SELECT state FROM migration_jobs WHERE id = ?
            """, jobId));
        assertEquals(1, integer("""
            SELECT retry_count FROM migration_jobs WHERE id = ?
            """, jobId));
        assertNull(jdbc.queryForObject("""
            SELECT lease_owner FROM migration_jobs WHERE id = ?
            """, String.class, jobId));
        assertEquals(1, integer("""
            SELECT count(*) FROM migration_validation_attempts
            WHERE job_id = ? AND state = 'COMPLETED'
            """, jobId));
        assertEquals(1, integer("""
            SELECT count(*) FROM migration_rows WHERE job_id = ?
            """, jobId));
        assertEquals(2, integer("""
            SELECT count(*) FROM migration_checkpoints
            WHERE job_id = ? AND phase IN ('SCANNING', 'VALIDATING')
            """, jobId));
        assertEquals(1, integer("""
            SELECT count(*) FROM migration_decisions
            WHERE job_id = ? AND actor_subject = 'SYSTEM:F06_RECOVERY'
              AND decision = 'REPLAY'
            """, jobId));

        worker.runOnce();
        assertEquals(1, integer("""
            SELECT count(*) FROM migration_rows WHERE job_id = ?
            """, jobId));
        assertEquals(1, integer("""
            SELECT count(*) FROM migration_validation_attempts
            WHERE job_id = ?
            """, jobId));
    }

    @Test
    void scannerTimeoutDeadLettersAtBoundAndAuthorizedReplayRecoversIdempotently() {
        Map<String, Object> uploaded = upload("AF-MIG-WORKER-DEAD-LETTER");
        UUID originalId = id(uploaded);
        scanner.mode.set(ScannerMode.TIMEOUT);

        for (int attempt = 1; attempt <= 10; attempt++) {
            age(originalId);
            worker.runOnce();
            assertEquals(attempt, integer("""
                SELECT retry_count FROM migration_jobs WHERE id = ?
                """, originalId));
            if (attempt == 1) {
                worker.runOnce();
                assertEquals(1, integer("""
                    SELECT retry_count FROM migration_jobs WHERE id = ?
                    """, originalId));
            }
        }

        OffsetDateTime deadLetteredAt = jdbc.queryForObject("""
            SELECT dead_lettered_at FROM migration_jobs WHERE id = ?
            """, OffsetDateTime.class, originalId);
        assertNotNull(deadLetteredAt);
        assertEquals("UPLOADED", text("""
            SELECT state FROM migration_jobs WHERE id = ?
            """, originalId));
        assertEquals(1, integer("""
            SELECT count(*) FROM migration_audit_events
            WHERE job_id = ? AND event_type = 'MIGRATION_RETRY_DEAD_LETTERED'
              AND actor_subject = 'SYSTEM:F06_RECOVERY'
            """, originalId));
        assertEquals(1, integer("""
            SELECT count(*) FROM migration_decisions
            WHERE job_id = ? AND actor_subject = 'SYSTEM:F06_RECOVERY'
            """, originalId));

        long deadLetterVersion = number("""
            SELECT version FROM migration_jobs WHERE id = ?
            """, originalId);
        Map<String, Object> firstReplay = migrations.retry(
            "user-arrow", originalId, deadLetterVersion,
            "Authorized recovery after scanner investigation.",
            "migration-dead-letter-authorized-replay");
        Map<String, Object> idempotentReplay = migrations.retry(
            "user-arrow", originalId, deadLetterVersion,
            "Authorized recovery after scanner investigation.",
            "migration-dead-letter-authorized-replay");
        UUID replayId = id(firstReplay);
        assertNotEquals(originalId, replayId);
        assertEquals(replayId, id(idempotentReplay));
        assertEquals("UPLOADED", firstReplay.get("state"));
        assertEquals(1, integer("""
            SELECT count(*) FROM migration_jobs
            WHERE id = ? AND parent_job_id = ? AND prior_job_id = ?
              AND retry_count = 0 AND dead_lettered_at IS NULL
            """, replayId, originalId, originalId));
        assertEquals(1, integer("""
            SELECT count(*) FROM migration_decisions
            WHERE job_id = ?
              AND actor_subject = 'user-arrow'
              AND idempotency_key =
                  'migration-dead-letter-authorized-replay'
            """, replayId));
        assertEquals(deadLetteredAt, jdbc.queryForObject("""
            SELECT dead_lettered_at FROM migration_jobs WHERE id = ?
            """, OffsetDateTime.class, originalId));

        scanner.mode.set(ScannerMode.PASSED);
        age(replayId);
        worker.runOnce();
        assertEquals("READY_TO_COMMIT", text("""
            SELECT state FROM migration_jobs WHERE id = ?
            """, replayId));
        assertEquals(1, integer("""
            SELECT count(*) FROM migration_rows WHERE job_id = ?
            """, replayId));
        assertEquals(0, integer("""
            SELECT count(*) FROM migration_rows WHERE job_id = ?
            """, originalId));
        assertEquals(1, integer("""
            SELECT count(*) FROM migration_scan_verdicts verdict
            JOIN migration_jobs job
              ON job.source_file_id = verdict.source_file_id
            WHERE job.id = ? AND verdict.verdict = 'PASSED'
            """, replayId));

        worker.runOnce();
        assertEquals(1, integer("""
            SELECT count(*) FROM migration_rows WHERE job_id = ?
            """, replayId));
    }

    private Map<String, Object> upload(String employeeNumber) {
        String csv = String.join(
            ",", templates.require("01_employees").headers())
            + "\r\n" + employeeRow(employeeNumber) + "\r\n";
        MockMultipartFile file = new MockMultipartFile(
            "file", employeeNumber.toLowerCase() + ".csv", "text/csv",
            csv.getBytes(StandardCharsets.UTF_8));
        return migrations.upload(
            "user-arrow", file,
            new MigrationDtos.UploadMetadata(
                ENGAGEMENT, VENDOR, null, "01_employees", "1",
                "DRY_RUN", false, null, null));
    }

    private String employeeRow(String employeeNumber) {
        return "1,ARROWFOUNDRY," + employeeNumber
            + ",Synthetic,Recovery,Synthetic Recovery,"
            + employeeNumber.toLowerCase() + "@example.test,"
            + "2026-01-01,,ACTIVE,Engineer,Platform,,Asia/Kolkata,"
            + "AF_STANDARD,AF_ATTENDANCE,AF_LEAVE,HISTORICAL_IMPORT,,"
            + "ENABLED,APPROVED_SPREADSHEET,synthetic-f07,";
    }

    private void age(UUID jobId) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (var settings = connection.createStatement();
                 var update = connection.prepareStatement("""
                     UPDATE migration_jobs
                     SET updated_at =
                         CURRENT_TIMESTAMP - INTERVAL '2 minutes'
                     WHERE id = ?
                     """)) {
                settings.execute(
                    "SET LOCAL session_replication_role = replica");
                update.setObject(1, jobId);
                if (update.executeUpdate() != 1) {
                    throw new SQLException("Migration job was not found.");
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            return null;
        });
    }

    private UUID id(Map<String, Object> job) {
        return UUID.fromString(String.valueOf(job.get("id")));
    }

    private int integer(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private long number(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Long.class, arguments);
    }

    private String text(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, String.class, arguments);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ScannerConfiguration {
        @Bean
        @Primary
        ControllableScanner controllableScanner() {
            return new ControllableScanner();
        }
    }

    enum ScannerMode {
        PENDING,
        TIMEOUT,
        PASSED
    }

    static final class ControllableScanner
        implements MigrationMalwareScanner {
        private final AtomicReference<ScannerMode> mode =
            new AtomicReference<>(ScannerMode.PENDING);

        @Override
        public Verdict inspect(byte[] content, String sha256) {
            return switch (mode.get()) {
                case PENDING -> new Verdict(
                    Verdict.Status.PENDING, "RECORDED_TEST_SCANNER",
                    "1", "test-v1", "SCAN_PENDING");
                case TIMEOUT -> throw new IllegalStateException(
                    "Synthetic scanner timeout");
                case PASSED -> new Verdict(
                    Verdict.Status.PASSED, "RECORDED_TEST_SCANNER",
                    "1", "test-v1", null);
            };
        }
    }
}
