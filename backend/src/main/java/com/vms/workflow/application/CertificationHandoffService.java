package com.vms.workflow.application;

import com.vms.workflow.api.CertificationDtos.ReadinessView;
import com.vms.workflow.application.CanonicalEvidenceHasher.HashResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class CertificationHandoffService {
    private final JdbcTemplate jdbc;
    private final CertificationReadinessService readiness;
    private final CanonicalEvidenceHasher hasher;

    public CertificationHandoffService(
        JdbcTemplate jdbc,
        CertificationReadinessService readiness,
        CanonicalEvidenceHasher hasher
    ) {
        this.jdbc = jdbc;
        this.readiness = readiness;
        this.hasher = hasher;
    }

    @Transactional
    public boolean publishConfirmedIfReady(
        String subject,
        UUID monthId,
        UUID correlationId
    ) {
        ReadinessView ready = readiness.evaluateAuthorized(subject, monthId);
        if (!"READY".equals(ready.status())) {
            return false;
        }
        ConfirmedRequest request = latestConfirmedRequest(monthId);
        UUID runId = readinessRunId(monthId, ready);
        if (request == null || runId == null) {
            return false;
        }

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "f04-f05-handoff-v1");
        manifest.put("engagementMonthId", monthId.toString());
        manifest.put("confirmationRequestId", request.id().toString());
        manifest.put("confirmationRequestVersion", request.version());
        manifest.put("confirmationScopeHash", request.scopeChecksum());
        manifest.put("readinessRunId", runId.toString());
        manifest.put("readinessInputVersion", ready.inputManifestVersion());
        HashResult packageHash = hasher.hash(manifest);
        jdbc.update("""
            INSERT INTO f05_certification_handoffs
                (id, engagement_month_id, confirmation_request_id,
                 readiness_run_id, package_manifest, package_hash,
                 status, created_by_subject, correlation_id)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, 'READY_LOCAL', ?, ?)
            ON CONFLICT (confirmation_request_id, package_hash) DO NOTHING
            """, UUID.randomUUID(), monthId, request.id(), runId,
            packageHash.canonicalJson(), packageHash.checksum(), subject,
            correlationId);
        jdbc.update("""
            INSERT INTO f05_handoff_publish_jobs
                (id, handoff_id, status, next_attempt_at)
            SELECT gen_random_uuid(), handoff.id, 'PENDING',
                   CURRENT_TIMESTAMP
            FROM f05_certification_handoffs handoff
            WHERE handoff.confirmation_request_id = ?
              AND handoff.package_hash = ?
            ON CONFLICT (handoff_id) DO NOTHING
            """, request.id(), packageHash.checksum());
        return true;
    }

    private ConfirmedRequest latestConfirmedRequest(UUID monthId) {
        return jdbc.query("""
            SELECT id, version, scope_checksum
            FROM business_confirmation_requests
            WHERE engagement_month_id = ?
              AND status = 'CONFIRMED'
            ORDER BY version DESC
            LIMIT 1
            """, rs -> rs.next() ? new ConfirmedRequest(
                rs.getObject("id", UUID.class),
                rs.getInt("version"),
                rs.getString("scope_checksum")) : null, monthId);
    }

    private UUID readinessRunId(UUID monthId, ReadinessView ready) {
        String version = ready.inputManifestVersion();
        String hash = version != null && version.startsWith("f04-readiness-v1:")
            ? version.substring("f04-readiness-v1:".length()) : null;
        return jdbc.query("""
            SELECT id
            FROM certification_readiness_runs
            WHERE engagement_month_id = ?
              AND input_hash = ?
            """, rs -> rs.next() ? rs.getObject("id", UUID.class) : null,
            monthId, hash);
    }

    private record ConfirmedRequest(
        UUID id,
        int version,
        String scopeChecksum
    ) {
    }
}
