package com.vms.workflow.infrastructure;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Aggregate-only operational gauges. Each statement has a one-second JDBC
 * timeout, returns one scalar and exposes no tenant, actor or resource labels.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class OperationalReadinessMetrics {
    private final JdbcTemplate jdbc;
    private final Counter queryErrors;

    public OperationalReadinessMetrics(
        JdbcTemplate jdbc,
        MeterRegistry registry
    ) {
        this.jdbc = jdbc;
        this.queryErrors = Counter.builder("vms.operational.scrape.errors")
            .description("Operational gauge database query failures")
            .register(registry);
        gauge(registry, "vms.operational.jobs.pending",
            "Pending or running durable jobs",
            OperationalReadinessMetrics::pendingJobs);
        gauge(registry, "vms.operational.outbox.pending",
            "Pending durable outbox records",
            OperationalReadinessMetrics::pendingOutbox);
        gauge(registry, "vms.operational.dead.letter.count",
            "Dead-lettered work across local workers",
            OperationalReadinessMetrics::deadLetters);
        gauge(registry, "vms.operational.queue.oldest.age.seconds",
            "Age of the oldest pending durable work item",
            OperationalReadinessMetrics::oldestQueueAge);
        gauge(registry, "vms.operational.provider.freshness.age.seconds",
            "Age since the least recent provider reconciliation or connection",
            OperationalReadinessMetrics::providerFreshnessAge);
        gauge(registry, "vms.operational.greythr.freshness.age.seconds",
            "Age since the least recent successful greytHR synchronization",
            OperationalReadinessMetrics::greytHrFreshnessAge);
        gauge(registry, "vms.operational.greythr.degraded.connections",
            "greytHR connections in an explicit degraded state",
            OperationalReadinessMetrics::greytHrDegradedConnections);
        gauge(registry, "vms.operational.retention.action.required",
            "Retention runs waiting for retry or dead-letter handling",
            OperationalReadinessMetrics::retentionActionRequired);
    }

    private void gauge(
        MeterRegistry registry,
        String name,
        String description,
        java.util.function.ToDoubleFunction<OperationalReadinessMetrics> value
    ) {
        Gauge.builder(name, this, value)
            .description(description)
            .register(registry);
    }

    double pendingJobs() {
        return number("""
            SELECT
              (SELECT count(*) FROM migration_jobs
               WHERE state NOT IN (
                 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED',
                 'CANCELLED', 'ROLLED_BACK'))
              + (SELECT count(*) FROM linear_sync_jobs
                 WHERE status IN ('QUEUED', 'RUNNING'))
              + (SELECT count(*) FROM f05_operation_jobs
                 WHERE status IN ('PENDING', 'CLAIMED', 'RETRY_SCHEDULED'))
              + (SELECT count(*) FROM f05_handoff_publish_jobs
                 WHERE status IN ('PENDING', 'CLAIMED'))
            """);
    }

    double pendingOutbox() {
        return number("""
            SELECT
              (SELECT count(*) FROM commitment_outbox
               WHERE status IN ('PENDING', 'RETRY'))
              + (SELECT count(*) FROM notification_outbox
                 WHERE transport_status IN (
                   'NOT_CONFIGURED', 'QUEUED', 'SENDING', 'FAILED'))
              + (SELECT count(*) FROM f05_outbox
                 WHERE status IN ('PENDING', 'CLAIMED'))
              + (SELECT count(*) FROM migration_outbox_events
                 WHERE published_at IS NULL)
            """);
    }

    double deadLetters() {
        return number("""
            SELECT
              (SELECT count(*) FROM migration_jobs
               WHERE dead_lettered_at IS NOT NULL)
              + (SELECT count(*) FROM linear_sync_jobs
                 WHERE status = 'DEAD_LETTER')
              + (SELECT count(*) FROM linear_webhook_queue
                 WHERE status = 'DEAD_LETTER')
              + (SELECT count(*) FROM commitment_outbox
                 WHERE status = 'DEAD_LETTER')
              + (SELECT count(*) FROM notification_outbox
                 WHERE transport_status = 'DEAD_LETTER')
              + (SELECT count(*) FROM f05_outbox
                 WHERE status = 'DEAD_LETTER')
              + (SELECT count(*) FROM f05_operation_jobs
                 WHERE status = 'DEAD_LETTER')
              + (SELECT count(*) FROM f05_handoff_publish_jobs
                 WHERE status = 'DEAD_LETTER')
            """);
    }

    double oldestQueueAge() {
        return number("""
            SELECT COALESCE(EXTRACT(EPOCH FROM (
                CURRENT_TIMESTAMP - MIN(created_at))), 0)
            FROM (
                SELECT created_at FROM linear_sync_jobs
                WHERE status IN ('QUEUED', 'RUNNING')
                UNION ALL
                SELECT created_at FROM f05_operation_jobs
                WHERE status IN ('PENDING', 'CLAIMED', 'RETRY_SCHEDULED')
                UNION ALL
                SELECT created_at FROM f05_outbox
                WHERE status IN ('PENDING', 'CLAIMED')
                UNION ALL
                SELECT created_at FROM migration_outbox_events
                WHERE published_at IS NULL
            ) pending
            """);
    }

    double providerFreshnessAge() {
        return number("""
            SELECT COALESCE(EXTRACT(EPOCH FROM (
                CURRENT_TIMESTAMP
                - MIN(COALESCE(last_reconciled_at, created_at)))), 0)
            FROM linear_connections
            WHERE status = 'CONNECTED'
            """);
    }

    double greytHrFreshnessAge() {
        return number("""
            SELECT COALESCE(MAX(EXTRACT(EPOCH FROM (
                CURRENT_TIMESTAMP
                - COALESCE(last_success_at, created_at)))), 0)
            FROM greythr_connections
            WHERE status IN ('ACTIVE', 'DEGRADED')
            """);
    }

    double greytHrDegradedConnections() {
        return number("""
            SELECT count(*)
            FROM greythr_connections
            WHERE status = 'DEGRADED'
            """);
    }

    double retentionActionRequired() {
        return number("""
            SELECT count(*)
            FROM (
                SELECT DISTINCT ON (run_id) run_id, status
                FROM f07_retention_run_transitions
                ORDER BY run_id, transition_sequence DESC
            ) latest
            WHERE latest.status IN ('RETRY_SCHEDULED', 'DEAD_LETTER')
            """);
    }

    private double number(String sql) {
        try {
            Number value = jdbc.query(sql, statement ->
                statement.setQueryTimeout(1), result -> {
                    if (!result.next()) {
                        return 0;
                    }
                    return result.getBigDecimal(1);
                });
            return value == null ? 0 : Math.max(0, value.doubleValue());
        } catch (org.springframework.dao.DataAccessException exception) {
            queryErrors.increment();
            return Double.NaN;
        }
    }
}
