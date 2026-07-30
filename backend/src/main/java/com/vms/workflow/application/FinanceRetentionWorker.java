package com.vms.workflow.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies one explicitly approved F07 content-retention candidate.
 *
 * <p>Candidate discovery and approval are performed by the governed retention
 * dry-run API. This component only runs inside that execution transaction and
 * never discovers or deletes content on a timer.</p>
 */
@Component
public class FinanceRetentionWorker {
    private final JdbcTemplate jdbc;
    private final FinancePrivateStorageAdapter storage;
    private final FinanceMutationJournal journal;
    private final Clock clock;

    public FinanceRetentionWorker(
        JdbcTemplate jdbc,
        FinancePrivateStorageAdapter storage,
        FinanceMutationJournal journal,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.journal = journal;
        this.clock = clock;
    }

    public String applyApprovedCandidate(
        UUID runId,
        UUID candidateId,
        UUID scheduleId,
        UUID artifactId,
        UUID monthId,
        String actor
    ) {
        if (!storage.transactionalDeleteSupported()) {
            throw new IllegalStateException(
                "RETENTION_STORAGE_DELETE_NOT_TRANSACTIONAL");
        }
        ArtifactState state = jdbc.query("""
            SELECT artifact.retention_status, artifact.legal_hold,
                   artifact.owner_organization_id
            FROM f05_private_artifacts artifact
            JOIN f07_retention_candidates candidate
              ON candidate.id = ?
             AND candidate.run_id = ?
             AND candidate.artifact_id = artifact.id
             AND candidate.decision = 'ELIGIBLE'
            JOIN f07_retention_runs run
              ON run.id = candidate.run_id
             AND run.schedule_id = ?
             AND run.organization_id = artifact.owner_organization_id
             AND run.record_class IN (
                 'FINANCE_EXPORT_CONTENT', 'FINANCE_EVIDENCE_CONTENT'
             )
            WHERE artifact.id = ?
            FOR UPDATE OF artifact
            """, rs -> rs.next()
                ? new ArtifactState(
                    rs.getString(1), rs.getBoolean(2),
                    rs.getObject(3, UUID.class))
                : null, candidateId, runId, scheduleId, artifactId);
        if (state == null || !"ACTIVE".equals(state.status())) {
            return "SKIPPED_STATE_CHANGED";
        }
        if (state.legalHold()) {
            return "SKIPPED_HELD";
        }
        if (isRetainedReference(artifactId)) {
            return "SKIPPED_REFERENCED";
        }

        OffsetDateTime disposedAt = OffsetDateTime.now(clock);
        UUID correlationId = journal.correlationId();
        jdbc.update("""
            INSERT INTO f05_artifact_retention_transitions(
                id, run_id, candidate_id, artifact_id, schedule_id, action,
                prior_status, effective_status, reason_code, actor_subject,
                correlation_id, recorded_at
            ) VALUES (?, ?, ?, ?, ?, 'DISPOSED', 'ACTIVE', 'DISPOSED',
                      'RETENTION_PERIOD_ELAPSED', ?, ?, ?)
            """, UUID.randomUUID(), runId, candidateId, artifactId,
            scheduleId, actor, correlationId,
            Timestamp.from(disposedAt.toInstant()));
        storage.delete(artifactId);
        if (jdbc.update("""
            UPDATE f05_private_artifacts
            SET retention_status = 'DISPOSED', disposed_at = ?,
                disposed_by_subject = ?,
                disposal_reason_code = 'RETENTION_PERIOD_ELAPSED'
            WHERE id = ? AND retention_status = 'ACTIVE'
            """, Timestamp.from(disposedAt.toInstant()), actor,
            artifactId) != 1) {
            throw new IllegalStateException(
                "ARTIFACT_RETENTION_CONCURRENT_CHANGE");
        }
        journal.event(
            monthId, "f05.artifact.retention.disposed.v1",
            "PRIVATE_ARTIFACT", artifactId, 1,
            Map.of("retentionRunId", runId,
                "retentionScheduleId", scheduleId,
                "reasonCode", "RETENTION_PERIOD_ELAPSED"),
            actor);
        journal.audit(
            monthId, "ARTIFACT_RETENTION_DISPOSED",
            "PRIVATE_ARTIFACT", artifactId, null, "SUCCESS",
            "RETENTION_PERIOD_ELAPSED", actor,
            Map.of("organizationId", state.organizationId()),
            List.of(
                Map.of("referenceType", "RETENTION_RUN",
                    "referenceId", runId),
                Map.of("referenceType", "RETENTION_SCHEDULE",
                    "referenceId", scheduleId)));
        return "CONTENT_DISPOSED";
    }

    public boolean isRetainedReference(UUID artifactId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT
                EXISTS (
                    SELECT 1 FROM invoice_versions
                    WHERE document_artifact_id = ?
                )
                OR EXISTS (
                    SELECT 1 FROM evidence_package_items
                    WHERE artifact_id = ?
                )
                OR EXISTS (
                    SELECT 1 FROM evidence_package_outputs
                    WHERE artifact_id = ?
                )
                OR EXISTS (
                    SELECT 1 FROM procurement_query_responses
                    WHERE response_artifact_id = ?
                )
            """, Boolean.class, artifactId, artifactId, artifactId,
            artifactId));
    }

    private record ArtifactState(
        String status,
        boolean legalHold,
        UUID organizationId
    ) {
    }
}
