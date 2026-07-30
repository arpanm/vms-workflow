package com.vms.workflow.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable F05 export and local outbox worker with leased claims and retries.
 */
@Component
@ConditionalOnProperty(
    name = "vms.finance.worker-enabled",
    havingValue = "true")
public class FinanceOperationsWorker {
    private static final int MAX_ATTEMPTS = 5;
    private static final int LEASE_SECONDS = 120;
    private static final int BATCH_SIZE = 10;

    private final JdbcTemplate jdbc;
    private final FinanceCanonicalJson canonical;
    private final FinanceReportDataService reportData;
    private final FinanceReportRenderer renderer;
    private final FinancePrivateStorageAdapter storage;
    private final FinanceMalwareScanner scanner;
    private final FinanceMutationJournal journal;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String workerId = "f05-worker-" + UUID.randomUUID();

    public FinanceOperationsWorker(
        JdbcTemplate jdbc,
        FinanceCanonicalJson canonical,
        FinanceReportDataService reportData,
        FinanceReportRenderer renderer,
        FinancePrivateStorageAdapter storage,
        FinanceMalwareScanner scanner,
        FinanceMutationJournal journal,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.canonical = canonical;
        this.reportData = reportData;
        this.renderer = renderer;
        this.storage = storage;
        this.scanner = scanner;
        this.journal = journal;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(
        fixedDelayString = "${vms.finance.worker-delay:PT5S}",
        initialDelayString = "${vms.finance.worker-initial-delay:PT5S}")
    public void runOnce() {
        processExports();
        publishLocalOutbox();
    }

    public int processExports() {
        int processed = 0;
        while (processed < BATCH_SIZE) {
            ExportClaim claim = transactions.execute(ignored -> claimExport());
            if (claim == null) {
                break;
            }
            try {
                Rendered value = render(claim);
                transactions.executeWithoutResult(
                    ignored -> completeExport(claim, value));
            } catch (RuntimeException exception) {
                transactions.executeWithoutResult(
                    ignored -> failExport(claim, exception));
            }
            processed++;
        }
        return processed;
    }

    public int publishLocalOutbox() {
        return jdbc.update("""
            WITH due AS (
                SELECT id
                FROM f05_outbox
                WHERE status IN ('PENDING', 'CLAIMED')
                  AND COALESCE(next_attempt_at, created_at)
                      <= CURRENT_TIMESTAMP
                  AND (lease_expires_at IS NULL
                       OR lease_expires_at < CURRENT_TIMESTAMP)
                ORDER BY COALESCE(next_attempt_at, created_at), id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE f05_outbox outbox
            SET status = 'PUBLISHED',
                attempt_count = attempt_count + 1,
                published_at = CURRENT_TIMESTAMP,
                next_attempt_at = NULL,
                lease_owner = NULL,
                lease_expires_at = NULL,
                last_error_code = NULL
            FROM due
            WHERE outbox.id = due.id
            """, BATCH_SIZE);
    }

    public void replayExport(UUID exportId) {
        int changed = jdbc.update("""
            UPDATE f05_report_exports
            SET status = 'PENDING', progress = 0,
                retry_cycle_attempt_count = 0,
                next_attempt_at = CURRENT_TIMESTAMP,
                lease_owner = NULL, lease_expires_at = NULL,
                last_error_code = NULL
            WHERE id = ? AND status IN ('FAILED', 'DEAD_LETTER')
            """, exportId);
        if (changed == 0) {
            throw new IllegalStateException(
                "Only failed or dead-letter exports can be replayed.");
        }
    }

    private ExportClaim claimExport() {
        return jdbc.query("""
            WITH candidate AS (
                SELECT id
                FROM f05_report_exports
                WHERE status IN ('PENDING', 'CLAIMED', 'FAILED')
                  AND next_attempt_at <= CURRENT_TIMESTAMP
                  AND (lease_expires_at IS NULL
                       OR lease_expires_at < CURRENT_TIMESTAMP)
                ORDER BY next_attempt_at, requested_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE f05_report_exports value
            SET status = 'CLAIMED', progress = 15,
                attempt_count = attempt_count + 1,
                retry_cycle_attempt_count =
                    retry_cycle_attempt_count + 1,
                lease_owner = ?,
                lease_expires_at =
                    CURRENT_TIMESTAMP + (? * INTERVAL '1 second')
            FROM candidate
            WHERE value.id = candidate.id
            RETURNING value.id, value.organization_id, value.engagement_id,
                      value.report_code, value.report_version, value.format,
                      value.filters::text, value.snapshot_label,
                      value.requested_by_subject,
                      value.retry_cycle_attempt_count,
                      value.correlation_id, value.authority_snapshot::text
            """, rs -> rs.next() ? new ExportClaim(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                rs.getString(6), canonical.readMap(rs.getString(7)),
                rs.getString(8), rs.getString(9), rs.getInt(10),
                rs.getObject(11, UUID.class),
                canonical.readMap(rs.getString(12))) : null,
            workerId, LEASE_SECONDS);
    }

    private Rendered render(ExportClaim claim) {
        UUID filterMonthId = resolveFilterMonth(claim);
        UUID artifactMonthId = filterMonthId == null
            ? latestMonth(claim.engagementId()) : filterMonthId;
        List<Map<String, Object>> rows = reportData.rows(
            claim.reportCode(), claim.engagementId(), filterMonthId,
            claim.organizationId(), claim.requestedBy(),
            claim.authoritySnapshot());
        Map<String, Object> metadata = Map.of(
            "reportCode", claim.reportCode(),
            "reportVersion", claim.reportVersion(),
            "generatedBy", claim.requestedBy(),
            "generatedAt", OffsetDateTime.now(clock),
            "timezone", "UTC",
            "filters", claim.filters(),
            "temporalMode", claim.temporalMode(),
            "sourceFreshness", "CURRENT",
            "rowCount", rows.size());
        FinanceReportRenderer.RenderedReport rendered = renderer.render(
            claim.reportCode(), claim.reportVersion(), claim.format(),
            metadata, rows);
        String hash = canonical.sha256Bytes(rendered.content());
        FinanceMalwareScanner.ScanResult scanResult = scanner.scan(
            rendered.content(), rendered.mediaType(), rendered.safeName());
        if (!"PASSED".equals(scanResult.status())) {
            throw new IllegalStateException(
                "EXPORT_SCAN_" + scanResult.status());
        }
        return new Rendered(
            artifactMonthId, rendered.content(), rendered.mediaType(),
            rendered.safeName(), hash, rows.size(), scanResult);
    }

    private void completeExport(ExportClaim claim, Rendered value) {
        UUID artifactId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO f05_private_artifacts(
                id, engagement_month_id, owner_organization_id, logical_type,
                safe_name, media_type, byte_size, content_hash, object_key,
                object_version, classification, retention_class,
                scan_status, scan_engine, scanned_at, provider_status,
                source, uploaded_by_subject, correlation_id
            ) VALUES (?, ?, ?, 'REPORT_EXPORT', ?, ?, ?, ?, ?, ?,
                      'CONFIDENTIAL', 'FINANCE_EXPORT',
                      'PASSED', ?, CURRENT_TIMESTAMP, 'CONFIGURED',
                      'SERVER_EXPORT', 'SYSTEM:F05_WORKER', ?)
            """, artifactId, value.monthId(), claim.organizationId(),
            value.safeName(), value.mediaType(), value.content().length,
            value.hash(), "exports/" + claim.organizationId() + "/"
                + claim.engagementId() + "/" + claim.id(),
            "postgres-" + UUID.randomUUID(), value.scan().engine(),
            claim.correlationId());
        storage.store(artifactId, value.content());
        int changed = jdbc.update("""
            UPDATE f05_report_exports
            SET status = 'READY', progress = 100, row_count = ?,
                result_artifact_id = ?, result_hash = ?,
                source_freshness_at = CURRENT_TIMESTAMP,
                completed_at = CURRENT_TIMESTAMP,
                expires_at = CURRENT_TIMESTAMP + INTERVAL '24 hours',
                lease_owner = NULL, lease_expires_at = NULL,
                next_attempt_at = CURRENT_TIMESTAMP,
                last_error_code = NULL
            WHERE id = ? AND status = 'CLAIMED' AND lease_owner = ?
              AND lease_expires_at > CURRENT_TIMESTAMP
            """, value.rowCount(), artifactId, value.hash(),
            claim.id(), workerId);
        if (changed != 1) {
            throw new IllegalStateException("EXPORT_LEASE_LOST");
        }
        journal.event(value.monthId(), "f05.export.ready.v1",
            "REPORT_EXPORT", claim.id(), 2,
            Map.of("artifactId", artifactId, "hash", value.hash(),
                "rowCount", value.rowCount()), "SYSTEM:F05_WORKER");
    }

    private void failExport(ExportClaim claim, RuntimeException exception) {
        boolean retry = claim.attempt() < MAX_ATTEMPTS;
        jdbc.update("""
            UPDATE f05_report_exports
            SET status = ?, progress = 0,
                next_attempt_at = CURRENT_TIMESTAMP
                    + (? * INTERVAL '1 second'),
                lease_owner = NULL, lease_expires_at = NULL,
                last_error_code = ?
            WHERE id = ? AND status = 'CLAIMED' AND lease_owner = ?
            """, retry ? "FAILED" : "DEAD_LETTER",
            retry ? retrySeconds(claim.attempt()) : 0,
            safeError(exception), claim.id(), workerId);
    }

    private UUID resolveFilterMonth(ExportClaim claim) {
        Object filterMonth = claim.filters().get("monthId");
        if (filterMonth != null && !String.valueOf(filterMonth).isBlank()) {
            UUID monthId = UUID.fromString(String.valueOf(filterMonth));
            Boolean inScope = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM engagement_months
                    WHERE id = ? AND engagement_id = ?
                )
                """, Boolean.class, monthId, claim.engagementId());
            if (!Boolean.TRUE.equals(inScope)) {
                throw new IllegalArgumentException("EXPORT_MONTH_OUT_OF_SCOPE");
            }
            return monthId;
        }
        return null;
    }

    private UUID latestMonth(UUID engagementId) {
        UUID value = jdbc.query("""
            SELECT id FROM engagement_months
            WHERE engagement_id = ?
            ORDER BY month_start_date DESC LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            engagementId);
        if (value == null) {
            throw new IllegalStateException("EXPORT_MONTH_REQUIRED");
        }
        return value;
    }

    private long retrySeconds(int attempt) {
        return Math.min(300, (long) Math.pow(2, Math.max(1, attempt)));
    }

    private String safeError(RuntimeException exception) {
        String name = exception.getClass().getSimpleName()
            .replaceAll("[^A-Za-z0-9_]", "_").toUpperCase();
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    private record ExportClaim(
        UUID id,
        UUID organizationId,
        UUID engagementId,
        String reportCode,
        String reportVersion,
        String format,
        Map<String, Object> filters,
        String temporalMode,
        String requestedBy,
        int attempt,
        UUID correlationId,
        Map<String, Object> authoritySnapshot
    ) {
    }

    private record Rendered(
        UUID monthId,
        byte[] content,
        String mediaType,
        String safeName,
        String hash,
        int rowCount,
        FinanceMalwareScanner.ScanResult scan
    ) {
    }
}
