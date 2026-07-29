package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.security.FinanceAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Explicit authorization boundary for forensic artifact state. Immutable
 * content and represented metadata remain outside this service.
 */
@Service
public class FinanceArtifactGovernanceService {
    private final JdbcTemplate jdbc;
    private final FinanceAuthorizationService authorization;
    private final FinanceMutationJournal journal;
    private final FinanceCanonicalJson canonical;

    public FinanceArtifactGovernanceService(
        JdbcTemplate jdbc,
        FinanceAuthorizationService authorization,
        FinanceMutationJournal journal,
        FinanceCanonicalJson canonical
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.journal = journal;
        this.canonical = canonical;
    }

    @Transactional
    public Map<String, Object> changeLegalHold(
        String subject,
        UUID artifactId,
        boolean enabled,
        String reasonCode,
        String idempotencyKey
    ) {
        FinanceAuthorizationService.Scope scope =
            authorization.requireArtifact(
                subject, artifactId, "artifact.legal-hold.manage");
        String normalizedReason = normalizeReason(reasonCode);
        Map<String, Object> request = Map.of(
            "enabled", enabled,
            "reasonCode", normalizedReason);
        UUID replay = journal.replay(
            subject, "ARTIFACT_LEGAL_HOLD", artifactId,
            idempotencyKey, request);
        if (replay != null) {
            return view(subject, artifactId);
        }

        ArtifactHoldRow artifact = jdbc.query("""
            SELECT id, engagement_month_id, legal_hold
            FROM f05_private_artifacts
            WHERE id = ?
            FOR UPDATE
            """, rs -> rs.next() ? new ArtifactHoldRow(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getBoolean(3)) : null, artifactId);
        if (artifact == null) {
            throw new EntityNotFoundException("Finance resource not found.");
        }
        if (artifact.legalHold() == enabled) {
            throw new DomainConflictException(
                "LEGAL_HOLD_UNCHANGED",
                "The artifact already has the requested legal-hold state.");
        }
        if (!enabled && Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM f07_legal_holds hold
                WHERE hold.artifact_id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM f07_legal_hold_transitions transition
                      WHERE transition.hold_id = hold.id
                        AND transition.action = 'RELEASE_APPROVED'
                  )
            )
            """, Boolean.class, artifactId))) {
            throw new DomainConflictException(
                "F07_LEGAL_HOLD_RELEASE_WORKFLOW_REQUIRED",
                "This legal hold must be released through its governance workflow.");
        }

        UUID transitionId = UUID.randomUUID();
        Map<String, Object> authority = authority(scope);
        UUID correlationId = journal.correlationId();
        jdbc.update("""
            INSERT INTO f05_artifact_hold_transitions(
                id, artifact_id, prior_legal_hold, legal_hold,
                reason_code, authority_snapshot, actor_subject,
                correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """, transitionId, artifactId, artifact.legalHold(), enabled,
            normalizedReason, canonical.write(authority), subject,
            correlationId);
        int changed = jdbc.update("""
            UPDATE f05_private_artifacts
            SET legal_hold = ?
            WHERE id = ? AND legal_hold = ?
            """, enabled, artifactId, artifact.legalHold());
        if (changed != 1) {
            throw new DomainConflictException(
                "LEGAL_HOLD_CONCURRENT_CHANGE",
                "The artifact legal-hold state changed concurrently.");
        }

        journal.event(
            artifact.monthId(), "f05.artifact.legal-hold.changed.v1",
            "PRIVATE_ARTIFACT", artifactId, 1,
            Map.of(
                "transitionId", transitionId,
                "priorLegalHold", artifact.legalHold(),
                "legalHold", enabled,
                "reasonCode", normalizedReason),
            subject);
        journal.audit(
            artifact.monthId(), "ARTIFACT_LEGAL_HOLD_CHANGED",
            "PRIVATE_ARTIFACT", artifactId, null, "SUCCESS",
            normalizedReason, subject, authority,
            List.of(Map.of(
                "referenceType", "LEGAL_HOLD_TRANSITION",
                "referenceId", transitionId)));
        journal.remember(
            subject, "ARTIFACT_LEGAL_HOLD", artifactId,
            idempotencyKey, request, "LEGAL_HOLD_TRANSITION",
            transitionId);
        return view(subject, artifactId);
    }

    public Map<String, Object> view(String subject, UUID artifactId) {
        authorization.requireArtifact(
            subject, artifactId, "artifact.legal-hold.manage");
        Map<String, Object> result = jdbc.query("""
            SELECT artifact.id, artifact.engagement_month_id,
                   artifact.legal_hold, transition.reason_code,
                   transition.actor_subject, transition.recorded_at
            FROM f05_private_artifacts artifact
            LEFT JOIN LATERAL (
                SELECT value.reason_code, value.actor_subject,
                       value.recorded_at
                FROM f05_artifact_hold_transitions value
                WHERE value.artifact_id = artifact.id
                ORDER BY value.recorded_at DESC, value.id DESC
                LIMIT 1
            ) transition ON TRUE
            WHERE artifact.id = ?
            """, rs -> rs.next() ? FinanceArtifactGovernanceService.map(
                "artifactId", rs.getObject(1, UUID.class),
                "monthId", rs.getObject(2, UUID.class),
                "legalHold", rs.getBoolean(3),
                "reasonCode", rs.getString(4),
                "changedBy", rs.getString(5),
                "changedAt", rs.getTimestamp(6) == null ? null
                    : rs.getTimestamp(6).toInstant())
                : null, artifactId);
        if (result == null) {
            throw new EntityNotFoundException("Finance resource not found.");
        }
        return result;
    }

    private String normalizeReason(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "A legal-hold reason code is required.");
        }
        String normalized = value.strip().toUpperCase()
            .replaceAll("[^A-Z0-9_-]+", "_");
        if (normalized.length() > 100) {
            throw new IllegalArgumentException(
                "The legal-hold reason code must not exceed 100 characters.");
        }
        return normalized;
    }

    private Map<String, Object> authority(
        FinanceAuthorizationService.Scope scope
    ) {
        return map(
            "permission", "artifact.legal-hold.manage",
            "engagementId", scope.engagementId(),
            "monthId", scope.monthId(),
            "vendorOrganizationId", scope.vendorOrganizationId(),
            "clientOrganizationId", scope.clientOrganizationId(),
            "procurementOrganizationId", scope.procurementOrganizationId(),
            "financeOrganizationId", scope.financeOrganizationId());
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private record ArtifactHoldRow(
        UUID id,
        UUID monthId,
        boolean legalHold
    ) {
    }
}
