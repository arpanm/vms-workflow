package com.vms.workflow.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Applies time-based Procurement-exception expiry in the caller's transaction.
 * Consequential caller boundaries explicitly retain this transition when they
 * reject the stale submit, review, or payment request.
 */
@Service
public class FinanceExceptionValidityService {
    private static final Set<String> REBLOCKABLE_STATES = Set.of(
        "EVIDENCE_PENDING", "EXCEPTION_ACCEPTED",
        "SUBMITTED_TO_PROCUREMENT", "PROCUREMENT_REVIEW",
        "APPROVED_FOR_PROCESSING", "CHANGES_REQUESTED", "ON_HOLD",
        "REJECTED", "PAYMENT_INITIATED");

    private final JdbcTemplate jdbc;
    private final FinanceCanonicalJson canonical;
    private final FinanceMutationJournal journal;
    private final Clock clock;

    public FinanceExceptionValidityService(
        JdbcTemplate jdbc,
        FinanceCanonicalJson canonical,
        FinanceMutationJournal journal,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.canonical = canonical;
        this.journal = journal;
        this.clock = clock;
    }

    /**
     * Expires the single active exception for an invoice, if due. The status
     * predicate and row lock make repeated access idempotent.
     */
    @Transactional
    public boolean expireInvoice(UUID invoiceId, String triggerSubject) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        ExceptionExpiry row = jdbc.query("""
            SELECT exception.id, exception.invoice_id, exception.status,
                   exception.readiness_run_id,
                   invoice.engagement_month_id, invoice.current_version,
                   invoice.optimistic_version, invoice.status,
                   invoice.current_readiness_run_id
            FROM procurement_exceptions exception
            JOIN invoices invoice ON invoice.id = exception.invoice_id
            WHERE exception.invoice_id = ?
              AND exception.status IN ('PENDING_SECOND_APPROVAL', 'ACCEPTED')
              AND exception.valid_until <= ?
            FOR UPDATE OF exception, invoice
            """, rs -> rs.next() ? new ExceptionExpiry(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class), rs.getInt(6), rs.getLong(7),
                rs.getString(8), rs.getObject(9, UUID.class)) : null,
            invoiceId, Timestamp.from(now.toInstant()));
        if (row == null) {
            return false;
        }

        jdbc.update("""
            UPDATE procurement_exceptions
            SET status = 'EXPIRED', expired_at = ?
            WHERE id = ?
              AND status IN ('PENDING_SECOND_APPROVAL', 'ACCEPTED')
            """, Timestamp.from(now.toInstant()), row.exceptionId());

        UUID blockedRunId = null;
        boolean reblocked = REBLOCKABLE_STATES.contains(row.invoiceState());
        if (reblocked) {
            blockedRunId = createExpiryReadinessRun(row, now, triggerSubject);
            jdbc.update("""
                UPDATE invoices
                SET status = 'EVIDENCE_PENDING',
                    current_readiness_run_id = ?,
                    optimistic_version = optimistic_version + 1,
                    updated_at = ?
                WHERE id = ? AND optimistic_version = ?
                """, blockedRunId, Timestamp.from(now.toInstant()),
                invoiceId, row.optimisticVersion());
        }

        long eventVersion = reblocked
            ? row.optimisticVersion() + 1 : row.optimisticVersion();
        Map<String, Object> payload = map(
            "exceptionId", row.exceptionId(),
            "priorExceptionStatus", row.exceptionStatus(),
            "reblocked", reblocked,
            "blockedReadinessRunId", blockedRunId,
            "expiredAt", now);
        journal.event(row.monthId(), "f05.procurement.exception.expired.v1",
            "INVOICE", invoiceId, eventVersion, payload,
            "SYSTEM:EXCEPTION_VALIDITY");
        journal.audit(row.monthId(), "PROCUREMENT_EXCEPTION_EXPIRED",
            "INVOICE", invoiceId, eventVersion,
            reblocked ? "BLOCKED" : "SUCCESS",
            reblocked ? "EXCEPTION_VALIDITY_EXPIRED_REBLOCKED"
                : "EXCEPTION_VALIDITY_EXPIRED_TERMINAL",
            "SYSTEM:EXCEPTION_VALIDITY",
            map("source", "SERVER_CLOCK",
                "triggerSubject", triggerSubject == null
                    ? "SYSTEM" : triggerSubject),
            List.of(map("type", "PROCUREMENT_EXCEPTION",
                "id", row.exceptionId())));
        return true;
    }

    private UUID createExpiryReadinessRun(
        ExceptionExpiry row,
        OffsetDateTime now,
        String triggerSubject
    ) {
        Map<String, Object> prior = jdbc.query("""
            SELECT input_manifest::text, policy_version,
                   package_version_id, handoff_id
            FROM invoice_readiness_runs
            WHERE id = ? AND invoice_id = ?
            """, rs -> rs.next() ? map(
                "manifest", canonical.readMap(rs.getString(1)),
                "policyVersion", rs.getString(2),
                "packageId", rs.getObject(3, UUID.class),
                "handoffId", rs.getObject(4, UUID.class)) : null,
            row.originalReadinessRunId(), row.invoiceId());
        if (prior == null) {
            throw new IllegalStateException(
                "Stored exception readiness lineage is unavailable.");
        }
        if (row.currentReadinessRunId() != null) {
            jdbc.update("""
                UPDATE invoice_readiness_runs
                SET current_result = FALSE, eligible = FALSE,
                    invalidated_at = ?
                WHERE id = ? AND current_result
                """, Timestamp.from(now.toInstant()),
                row.currentReadinessRunId());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> manifest = new LinkedHashMap<>(
            (Map<String, Object>) prior.get("manifest"));
        manifest.remove("acceptedExceptionId");
        manifest.remove("acceptedReadinessResultId");
        manifest.put("expiredExceptionId", row.exceptionId());
        manifest.put("exceptionExpiredAt", now);
        UUID runId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO invoice_readiness_runs(
                id, invoice_id, invoice_version, package_version_id,
                handoff_id, input_manifest, input_hash, policy_version,
                overall_status, eligible, current_result,
                evaluated_by_subject, evaluated_at, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?,
                      'BLOCKED_MISSING_EVIDENCE', FALSE, TRUE,
                      'SYSTEM:EXCEPTION_VALIDITY', ?, ?)
            """, runId, row.invoiceId(), row.invoiceVersion(),
            prior.get("packageId"), prior.get("handoffId"),
            canonical.write(manifest), canonical.sha256(manifest),
            prior.get("policyVersion"), Timestamp.from(now.toInstant()),
            journal.correlationId());
        jdbc.update("""
            INSERT INTO invoice_readiness_results(
                id, readiness_run_id, rule_code, result, severity,
                owner_label, source_object_type, source_object_id,
                source_version, source_hash, freshness_at, remediation_cta)
            SELECT gen_random_uuid(), ?, rule_code, result, severity,
                   owner_label, source_object_type, source_object_id,
                   source_version, source_hash, freshness_at, remediation_cta
            FROM invoice_readiness_results
            WHERE readiness_run_id = ?
            ORDER BY rule_code
            """, runId, row.originalReadinessRunId());
        return runId;
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(String.valueOf(values[index]), values[index + 1]);
        }
        return result;
    }

    private record ExceptionExpiry(
        UUID exceptionId,
        UUID invoiceId,
        String exceptionStatus,
        UUID originalReadinessRunId,
        UUID monthId,
        int invoiceVersion,
        long optimisticVersion,
        String invoiceState,
        UUID currentReadinessRunId
    ) {
    }
}
