package com.vms.workflow.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Low-cardinality F06 telemetry. Labels are limited to controlled operation,
 * outcome and template enums; source names, actors, row keys and payload data
 * are deliberately excluded.
 */
@Component
public final class MigrationMetrics {
    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;

    public MigrationMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
        Gauge.builder(
                "vms.migration.jobs.oldest.active.age.seconds",
                this, MigrationMetrics::oldestActiveAgeSeconds)
            .description("Age of the oldest non-terminal migration job")
            .register(registry);
        Gauge.builder(
                "vms.migration.jobs.retry.count",
                this, MigrationMetrics::retryCount)
            .description("Accumulated retry count for migration jobs")
            .register(registry);
        Gauge.builder(
                "vms.migration.jobs.dead.letter.count",
                this, MigrationMetrics::deadLetterCount)
            .description("Migration jobs currently dead-lettered")
            .register(registry);
        Gauge.builder(
                "vms.migration.scan.pending.count",
                this, MigrationMetrics::pendingScanCount)
            .description("Migration sources awaiting a scanner verdict")
            .register(registry);
        Gauge.builder(
                "vms.migration.reconciliation.mismatch.count",
                this, MigrationMetrics::reconciliationMismatchCount)
            .description("Latest reconciliations with count or coverage mismatch")
            .register(registry);
    }

    public Timer.Sample start() {
        return Timer.start(registry);
    }

    public void recordHttp(
        Timer.Sample sample,
        String operation,
        String outcome,
        int status
    ) {
        Counter.builder("vms.migration.operation.outcomes")
            .tag("operation", operation)
            .tag("outcome", outcome)
            .tag("status", statusFamily(status))
            .register(registry)
            .increment();
        sample.stop(Timer.builder("vms.migration.operation.duration")
            .description("Migration HTTP operation duration")
            .tag("operation", operation)
            .tag("outcome", outcome)
            .publishPercentileHistogram()
            .minimumExpectedValue(Duration.ofMillis(1))
            .maximumExpectedValue(Duration.ofMinutes(10))
            .register(registry));
        if ("authorization_denied".equals(outcome)) {
            Counter.builder("vms.migration.authorization.denials")
                .tag("operation", operation)
                .register(registry)
                .increment();
        }
    }

    public void recordScan(String outcome) {
        Counter.builder("vms.migration.scan.outcomes")
            .tag("outcome", controlledOutcome(outcome))
            .register(registry)
            .increment();
    }

    public void recordRows(
        String operation,
        String template,
        String outcome,
        long rows
    ) {
        DistributionSummary.builder("vms.migration.rows.processed")
            .description("Rows processed by migration lifecycle operations")
            .baseUnit("rows")
            .tag("operation", operation)
            .tag("template", controlledTemplate(template))
            .tag("outcome", controlledOutcome(outcome))
            .register(registry)
            .record(Math.max(0, rows));
    }

    public void recordRetry(String outcome) {
        Counter.builder("vms.migration.retry.outcomes")
            .tag("outcome", controlledOutcome(outcome))
            .register(registry)
            .increment();
    }

    public void recordWorker(String outcome) {
        Counter.builder("vms.migration.recovery.worker.outcomes")
            .tag("outcome", controlledOutcome(outcome))
            .register(registry)
            .increment();
    }

    private double oldestActiveAgeSeconds() {
        return number("""
            SELECT COALESCE(EXTRACT(EPOCH FROM
                (CURRENT_TIMESTAMP - MIN(created_at))), 0)
            FROM migration_jobs
            WHERE state NOT IN (
              'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED',
              'CANCELLED', 'ROLLED_BACK')
            """);
    }

    private double retryCount() {
        return number("""
            SELECT COALESCE(SUM(retry_count), 0) FROM migration_jobs
            """);
    }

    private double deadLetterCount() {
        return number("""
            SELECT count(*) FROM migration_jobs
            WHERE dead_lettered_at IS NOT NULL
            """);
    }

    private double pendingScanCount() {
        return number("""
            SELECT count(*) FROM migration_source_files
            WHERE scan_status = 'PENDING'
            """);
    }

    private double reconciliationMismatchCount() {
        return number("""
            SELECT count(*)
            FROM migration_reconciliation_reports report
            WHERE COALESCE((report.counts->>'total')::bigint, 0)
                    <> COALESCE((report.counts->>'committed')::bigint, 0)
                     + COALESCE((report.counts->>'rejected')::bigint, 0)
               OR COALESCE(
                    (report.coverage->>'expected_employee_days')::bigint, 0)
                    <> COALESCE(
                    (report.coverage->>'imported_employee_days')::bigint, 0)
            """);
    }

    private double number(String sql) {
        try {
            Number value = jdbc.queryForObject(sql, Number.class);
            return value == null ? 0 : value.doubleValue();
        } catch (DataAccessException exception) {
            return 0;
        }
    }

    private String statusFamily(int status) {
        int family = Math.max(0, status) / 100;
        return family >= 1 && family <= 5 ? family + "xx" : "unknown";
    }

    private String controlledTemplate(String template) {
        if (template == null
            || !template.matches(
                "(0[1-9]|1[0-3])[a-z]?_[a-z0-9_]+")) {
            return "unknown";
        }
        return template;
    }

    private String controlledOutcome(String outcome) {
        if (outcome == null
            || !outcome.matches("[A-Za-z0-9_]{1,32}")) {
            return "unknown";
        }
        return outcome.toLowerCase();
    }
}
