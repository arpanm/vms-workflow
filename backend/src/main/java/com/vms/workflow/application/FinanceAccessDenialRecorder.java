package com.vms.workflow.application;

import com.vms.workflow.infrastructure.CorrelationIdFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Persists security and audit evidence for denied F05 export access in an
 * independent transaction so the evidence survives the rejected request.
 */
@Service
public class FinanceAccessDenialRecorder {
    private final JdbcTemplate jdbc;
    private final FinanceCanonicalJson canonical;

    public FinanceAccessDenialRecorder(
        JdbcTemplate jdbc,
        FinanceCanonicalJson canonical
    ) {
        this.jdbc = jdbc;
        this.canonical = canonical;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordExportDenied(
        String subject,
        UUID exportId,
        String action,
        String reportCode,
        String routePermission,
        String reportPermission
    ) {
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        String safeSubject = subject == null ? "ANONYMOUS" : subject;
        jdbc.update("""
            INSERT INTO f05_security_events(
                id, event_type, result, reason_code,
                actor_subject_hash, correlation_id
            ) VALUES (?, 'REPORT_EXPORT_ACCESS_DENIED', 'DENIED',
                      'REPORT_PERMISSION_DENIED', ?, ?)
            """, UUID.randomUUID(),
            canonical.sha256Text(safeSubject), correlationId);
        jdbc.update("""
            INSERT INTO f05_audit_events(
                id, action, object_type, object_id, result, reason_code,
                authority_snapshot, evidence_references, actor_subject,
                correlation_id
            ) VALUES (?, ?, 'REPORT_EXPORT', ?, 'DENIED',
                      'REPORT_PERMISSION_DENIED', ?::jsonb, '[]'::jsonb,
                      ?, ?)
            """, UUID.randomUUID(), action, exportId,
            canonical.write(Map.of(
                "reportCode", reportCode,
                "routePermission", routePermission,
                "reportPermission", reportPermission)),
            safeSubject, correlationId);
    }
}
