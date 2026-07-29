package com.vms.workflow.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Optional bounded recovery for abandoned F06 scan/validation jobs. It is
 * disabled unless explicitly configured and uses PostgreSQL leases with
 * SKIP LOCKED so multiple application instances cannot claim the same job.
 */
@Component
@ConditionalOnProperty(
    name = "vms.migration.worker-enabled",
    havingValue = "true")
public final class MigrationRecoveryWorker {
    private static final int MAX_RETRIES = 10;
    private static final Logger LOGGER =
        LoggerFactory.getLogger(MigrationRecoveryWorker.class);

    private final JdbcTemplate jdbc;
    private final MigrationService migrations;
    private final MigrationMetrics metrics;
    private final TransactionTemplate transactions;
    private final int batchSize;
    private final int leaseSeconds;
    private final int recoveryAgeSeconds;
    private final String workerId = "f06-worker-" + UUID.randomUUID();

    public MigrationRecoveryWorker(
        JdbcTemplate jdbc,
        MigrationService migrations,
        MigrationMetrics metrics,
        TransactionTemplate transactions,
        @Value("${vms.migration.worker-batch-size:5}") int batchSize,
        @Value("${vms.migration.worker-lease-seconds:120}")
        int leaseSeconds,
        @Value("${vms.migration.worker-recovery-age-seconds:300}")
        int recoveryAgeSeconds
    ) {
        this.jdbc = jdbc;
        this.migrations = migrations;
        this.metrics = metrics;
        this.transactions = transactions;
        this.batchSize = Math.max(1, Math.min(batchSize, 25));
        this.leaseSeconds = Math.max(30, Math.min(leaseSeconds, 900));
        this.recoveryAgeSeconds =
            Math.max(60, Math.min(recoveryAgeSeconds, 86_400));
    }

    @Scheduled(
        fixedDelayString = "${vms.migration.worker-delay:PT30S}",
        initialDelayString = "${vms.migration.worker-initial-delay:PT30S}")
    public void runOnce() {
        for (int handled = 0; handled < batchSize; handled++) {
            Claim claim = transactions.execute(ignored -> claim());
            if (claim == null) {
                return;
            }
            try {
                migrations.retryClaimed(
                    claim.id(), claim.version(), workerId);
                metrics.recordWorker("recovered");
            } catch (RuntimeException exception) {
                LOGGER.warn(
                    "Migration recovery failed for job {} at claimed version {}",
                    claim.id(), claim.version(), exception);
                transactions.executeWithoutResult(
                    ignored -> fail(claim));
                metrics.recordWorker("failed");
            }
        }
    }

    private Claim claim() {
        return jdbc.query("""
            WITH candidate AS (
              SELECT job.id
              FROM migration_jobs job
              JOIN migration_source_files source
                ON source.id = job.source_file_id
              WHERE job.dead_lettered_at IS NULL
                AND job.retry_count < ?
                AND (job.lease_until IS NULL
                     OR job.lease_until < CURRENT_TIMESTAMP)
                AND job.updated_at
                    < CURRENT_TIMESTAMP - make_interval(secs => ?)
                AND (
                  job.state IN ('SCANNING', 'PARSING', 'VALIDATING', 'FAILED')
                  OR (job.state = 'UPLOADED'
                      AND source.scan_status = 'PENDING')
                )
              ORDER BY job.updated_at, job.id
              FOR UPDATE OF job SKIP LOCKED
              LIMIT 1
            )
            UPDATE migration_jobs job
            SET state = CASE
                  WHEN job.state IN ('SCANNING', 'PARSING', 'VALIDATING')
                    THEN 'FAILED'
                  ELSE job.state
                END,
                lease_owner = ?,
                lease_until = CURRENT_TIMESTAMP
                    + make_interval(secs => ?),
                version = job.version + 1
            FROM candidate
            WHERE job.id = candidate.id
            RETURNING job.id, job.version
            """, rs -> rs.next()
                ? new Claim(
                    rs.getObject(1, UUID.class),
                    rs.getLong(2))
                : null,
            MAX_RETRIES, recoveryAgeSeconds, workerId, leaseSeconds);
    }

    private void fail(Claim claim) {
        int updated = jdbc.update("""
            UPDATE migration_jobs
            SET retry_count = LEAST(retry_count + 1, ?),
                dead_lettered_at = CASE
                  WHEN retry_count + 1 >= ? THEN CURRENT_TIMESTAMP
                  ELSE dead_lettered_at
                END,
                lease_owner = NULL,
                lease_until = NULL,
                version = version + 1
            WHERE id = ? AND lease_owner = ?
            """, MAX_RETRIES, MAX_RETRIES, claim.id(), workerId);
        if (updated != 1) {
            LOGGER.warn(
                "Migration recovery failure could not release job {} because "
                    + "its lease changed",
                claim.id());
            return;
        }
        Integer retryCount = jdbc.queryForObject("""
            SELECT retry_count FROM migration_jobs WHERE id = ?
            """, Integer.class, claim.id());
        if (retryCount == null || retryCount < MAX_RETRIES) {
            return;
        }
        int decisionInserted = jdbc.update("""
            INSERT INTO migration_decisions
              (id, job_id, decision, reason, actor_subject, job_version,
               idempotency_key)
            VALUES (?, ?, 'REPLAY',
                    'Automated recovery exhausted the bounded retry budget.',
                    'SYSTEM:F06_RECOVERY', ?, ?)
            ON CONFLICT (actor_subject, idempotency_key) DO NOTHING
            """, UUID.randomUUID(), claim.id(), claim.version(),
            "f06-worker-dead-letter:" + claim.id());
        if (decisionInserted == 1) {
            jdbc.update("""
                INSERT INTO migration_audit_events
                  (id, engagement_id, organization_id, job_id, event_type,
                   actor_subject, metadata, correlation_id)
                SELECT ?, engagement_id, organization_id, id,
                       'MIGRATION_RETRY_DEAD_LETTERED',
                       'SYSTEM:F06_RECOVERY',
                       jsonb_build_object('retryCount', retry_count), ?
                FROM migration_jobs WHERE id = ?
                """, UUID.randomUUID(), UUID.randomUUID(), claim.id());
        }
    }

    private record Claim(
        UUID id,
        long version
    ) {
    }
}
