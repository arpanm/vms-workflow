package com.vms.workflow.application;

import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.api.RetentionDtos.DryRunInput;
import com.vms.workflow.api.RetentionDtos.HoldInput;
import com.vms.workflow.api.RetentionDtos.ReleaseInput;
import com.vms.workflow.api.RetentionDtos.RecoveryInput;
import com.vms.workflow.api.RetentionDtos.ScheduleInput;
import com.vms.workflow.infrastructure.AuthorizationStore;
import com.vms.workflow.infrastructure.CorrelationIdFilter;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class RetentionPrivacyService {
    private static final Set<String> RECORD_CLASSES = Set.of(
        "TEMPORARY_EXPORT_CAPABILITY", "TEMPORARY_PACKAGE_SHARE");

    private final JdbcTemplate jdbc;
    private final AuthorizationStore authorization;
    private final FinanceMutationJournal journal;
    private final FinanceCanonicalJson canonical;
    private final Clock clock;
    private final boolean twoPersonRelease;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Duration executionLeaseDuration;
    private final TransactionTemplate candidateTransactions;

    public RetentionPrivacyService(
        JdbcTemplate jdbc,
        AuthorizationStore authorization,
        FinanceMutationJournal journal,
        FinanceCanonicalJson canonical,
        Clock clock,
        PlatformTransactionManager transactionManager,
        @Value("${vms.retention.two-person-release:true}")
        boolean twoPersonRelease,
        @Value("${vms.retention.max-attempts:3}") int maxAttempts,
        @Value("${vms.retention.retry-delay:PT5M}") Duration retryDelay,
        @Value("${vms.retention.execution-lease-duration:PT30M}")
        Duration executionLeaseDuration
    ) {
        if (maxAttempts < 1 || maxAttempts > 20
            || retryDelay.isNegative() || retryDelay.isZero()
            || retryDelay.compareTo(Duration.ofDays(1)) > 0
            || executionLeaseDuration.compareTo(retryDelay) <= 0
            || executionLeaseDuration.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException(
                "Retention retry configuration is outside the supported range.");
        }
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.journal = journal;
        this.canonical = canonical;
        this.clock = clock;
        this.twoPersonRelease = twoPersonRelease;
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
        this.executionLeaseDuration = executionLeaseDuration;
        this.candidateTransactions = new TransactionTemplate(transactionManager);
        this.candidateTransactions.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional
    public Map<String, Object> createSchedule(
        String subject,
        ScheduleInput input,
        String idempotencyKey
    ) {
        require(subject, input.organizationId(), "retention.schedule.manage");
        String recordClass = recordClass(input.recordClass());
        Map<String, Object> request = map(
            "recordClass", recordClass,
            "retentionDays", input.retentionDays(),
            "policyReference", input.policyReference(),
            "effectiveFrom", input.effectiveFrom());
        UUID replay = journal.replay(
            subject, "RETENTION_SCHEDULE", input.organizationId(),
            idempotencyKey, request);
        if (replay != null) {
            return schedule(replay);
        }
        jdbc.queryForObject("""
            SELECT pg_advisory_xact_lock(
                hashtextextended(? || ':' || ?, 0))
            """, Object.class, input.organizationId().toString(), recordClass);
        ScheduleVersion prior = jdbc.query("""
            SELECT id, version
            FROM f07_retention_schedules
            WHERE organization_id = ? AND record_class = ?
            ORDER BY version DESC
            LIMIT 1
            FOR UPDATE
            """, rs -> rs.next()
                ? new ScheduleVersion(rs.getObject(1, UUID.class), rs.getInt(2))
                : null, input.organizationId(), recordClass);
        UUID id = UUID.randomUUID();
        int version = prior == null ? 1 : prior.version() + 1;
        jdbc.update("""
            INSERT INTO f07_retention_schedules(
                id, organization_id, record_class, version, retention_days,
                policy_reference, effective_from, supersedes_id,
                created_by_subject, authority_snapshot, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """, id, input.organizationId(), recordClass, version,
            input.retentionDays(), safe(input.policyReference(), 200),
            Timestamp.from(input.effectiveFrom().toInstant()),
            prior == null ? null : prior.id(), subject,
            canonical.write(authority(input.organizationId(),
                "retention.schedule.manage")),
            CorrelationIdFilter.currentOrNew());
        journal.audit(null, "RETENTION_SCHEDULE_VERSIONED",
            "RETENTION_SCHEDULE", id, (long) version, "SUCCESS",
            "POLICY_CONFIGURED", subject,
            authority(input.organizationId(), "retention.schedule.manage"),
            List.of());
        journal.remember(
            subject, "RETENTION_SCHEDULE", input.organizationId(),
            idempotencyKey, request, "RETENTION_SCHEDULE", id);
        return schedule(id);
    }

    public List<Map<String, Object>> schedules(
        String subject,
        UUID organizationId
    ) {
        require(subject, organizationId, "retention.schedule.manage");
        return jdbc.query("""
            SELECT id FROM f07_retention_schedules
            WHERE organization_id = ?
            ORDER BY record_class, version DESC
            """, (rs, row) -> schedule(rs.getObject(1, UUID.class)),
            organizationId);
    }

    @Transactional
    public Map<String, Object> dryRun(
        String subject,
        DryRunInput input,
        String idempotencyKey
    ) {
        require(subject, input.organizationId(), "retention.execute");
        String recordClass = recordClass(input.recordClass());
        UUID replay = journal.replay(
            subject, "RETENTION_DRY_RUN", input.organizationId(),
            idempotencyKey, input);
        if (replay != null) {
            return run(subject, replay);
        }
        EffectiveSchedule schedule = effectiveSchedule(
            input.organizationId(), recordClass, input.asOf());
        UUID runId = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        jdbc.update("""
            INSERT INTO f07_retention_runs(
                id, schedule_id, organization_id, record_class, as_of,
                requested_by_subject, authority_snapshot, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """, runId, schedule.id(), input.organizationId(), recordClass,
            Timestamp.from(input.asOf().toInstant()), subject,
            canonical.write(authority(
                input.organizationId(), "retention.execute")),
            correlationId);
        if ("TEMPORARY_EXPORT_CAPABILITY".equals(recordClass)) {
            collectExports(runId, input, schedule.days());
        } else {
            collectShares(runId, input, schedule.days());
        }
        Counts counts = counts(runId);
        transition(runId, "DRY_RUN_COMPLETE", 0, counts.eligible(),
            counts.skipped(), 0, null, "DRY_RUN_RECORDED", subject);
        journal.audit(null, "RETENTION_DRY_RUN_COMPLETED",
            "RETENTION_RUN", runId, 1L, "SUCCESS", "DRY_RUN_RECORDED",
            subject, authority(input.organizationId(), "retention.execute"),
            List.of());
        journal.remember(
            subject, "RETENTION_DRY_RUN", input.organizationId(),
            idempotencyKey, input, "RETENTION_RUN", runId);
        return run(subject, runId);
    }

    /**
     * Deliberately non-transactional: each candidate result/proof is durable so
     * a later retry resumes rather than losing successful capability expiry.
     */
    public Map<String, Object> execute(
        String subject,
        UUID runId,
        String idempotencyKey
    ) {
        RunRow run = runRow(runId);
        require(subject, run.organizationId(), "retention.execute");
        UUID replay = journal.replay(
            subject, "RETENTION_EXECUTE", runId, idempotencyKey,
            Map.of("runId", runId));
        if (replay != null) {
            return run(subject, runId);
        }
        UUID ownerId = UUID.randomUUID();
        int attempt = beginExecution(runId, subject, ownerId);
        int expired = 0;
        int skipped = 0;
        int failed = 0;
        for (Candidate candidate : candidates(runId)) {
            heartbeat(runId, ownerId);
            try {
                String outcome = candidateTransactions.execute(status ->
                    applyCandidate(subject, run, candidate, attempt));
                if ("CAPABILITY_EXPIRED".equals(outcome)
                    || "ALREADY_APPLIED".equals(outcome)) {
                    expired++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException exception) {
                failed++;
                candidateTransactions.executeWithoutResult(status ->
                    result(runId, candidate.id(), attempt, "FAILED",
                        "CAPABILITY_EXPIRY_FAILED", subject));
            }
            heartbeat(runId, ownerId);
        }
        String status = failed == 0 ? "COMPLETED"
            : attemptsInCurrentCycle(runId) >= maxAttempts
                ? "DEAD_LETTER" : "RETRY_SCHEDULED";
        OffsetDateTime next = "RETRY_SCHEDULED".equals(status)
            ? OffsetDateTime.now(clock).plus(retryDelay) : null;
        int completedExpired = expired;
        int completedSkipped = skipped;
        int completedFailed = failed;
        candidateTransactions.executeWithoutResult(transaction ->
            completeExecution(
                run, subject, idempotencyKey, attempt, ownerId, status,
                completedExpired, completedSkipped, completedFailed, next));
        return run(subject, runId);
    }

    @Transactional
    public Map<String, Object> authorizeRecovery(
        String subject,
        UUID runId,
        RecoveryInput input,
        String idempotencyKey
    ) {
        RunRow run = runRow(runId);
        require(subject, run.organizationId(), "retention.execute");
        UUID replay = journal.replay(
            subject, "RETENTION_DEAD_LETTER_RECOVERY", runId,
            idempotencyKey, input);
        if (replay != null) {
            return run(subject, runId);
        }
        jdbc.queryForObject("""
            SELECT id FROM f07_retention_runs
            WHERE id = ?
            FOR UPDATE
            """, UUID.class, runId);
        ExecutionState latest = latestExecutionState(runId);
        if (!"DEAD_LETTER".equals(latest.status())) {
            throw new DomainConflictException(
                "RETENTION_DEAD_LETTER_REQUIRED",
                "Only a dead-lettered retention run can be recovered.");
        }
        String reasonCode = reason(input.reasonCode());
        transition(
            runId, "RECOVERY_AUTHORIZED", latest.attempt(),
            0, 0, 0, OffsetDateTime.now(clock), reasonCode, subject);
        journal.audit(null, "RETENTION_DEAD_LETTER_RECOVERY_AUTHORIZED",
            "RETENTION_RUN", runId, (long) latest.attempt(), "SUCCESS",
            reasonCode, subject,
            authority(run.organizationId(), "retention.execute"), List.of());
        journal.remember(
            subject, "RETENTION_DEAD_LETTER_RECOVERY", runId,
            idempotencyKey, input, "RETENTION_RUN", runId);
        return run(subject, runId);
    }

    private int beginExecution(UUID runId, String subject, UUID ownerId) {
        BeginResult result = candidateTransactions.execute(transaction -> {
            jdbc.queryForObject("""
                SELECT id FROM f07_retention_runs
                WHERE id = ?
                FOR UPDATE
                """, UUID.class, runId);
            ExecutionState latest = latestExecutionState(runId);
            OffsetDateTime now = OffsetDateTime.now(clock);
            LeaseRow lease = jdbc.query("""
                SELECT owner_id, attempt, lease_expires_at
                FROM f07_retention_execution_leases
                WHERE run_id = ?
                FOR UPDATE
                """, rs -> rs.next() ? new LeaseRow(
                    rs.getObject(1, UUID.class),
                    rs.getInt(2),
                    rs.getTimestamp(3).toInstant()
                        .atOffset(java.time.ZoneOffset.UTC)) : null, runId);
            if (lease != null && lease.leaseExpiresAt().isAfter(now)) {
                throw new DomainConflictException(
                    "RETENTION_EXECUTION_IN_PROGRESS",
                    "The retention run already has an active execution.");
            }
            if ("COMPLETED".equals(latest.status())) {
                throw new DomainConflictException(
                    "RETENTION_RUN_ALREADY_COMPLETED",
                    "The retention run is already complete.");
            }
            if ("DEAD_LETTER".equals(latest.status())) {
                throw new DomainConflictException(
                    "RETENTION_RUN_DEAD_LETTERED",
                    "The retention run requires authorized dead-letter recovery.");
            }
            if ("RETRY_SCHEDULED".equals(latest.status())
                && latest.nextAttemptAt() != null
                && latest.nextAttemptAt().isAfter(now)) {
                throw new DomainConflictException(
                    "RETENTION_RETRY_NOT_DUE",
                    "The retention retry window has not opened.");
            }
            if ("EXECUTION_STARTED".equals(latest.status())) {
                int priorAttempts = attemptsInCurrentCycle(runId);
                if (priorAttempts >= maxAttempts) {
                    jdbc.update("""
                        DELETE FROM f07_retention_execution_leases
                        WHERE run_id = ?
                        """, runId);
                    transition(
                        runId, "DEAD_LETTER", latest.attempt(),
                        0, 0, 1, null,
                        "EXECUTION_OWNER_LOST_MAX_ATTEMPTS", subject);
                    return new BeginResult(null, true);
                }
                transition(
                    runId, "RETRY_SCHEDULED", latest.attempt(),
                    0, 0, 1, now,
                    "EXECUTION_OWNER_LOST", subject);
            }
            int nextAttempt = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt), 0) + 1
                FROM f07_retention_run_transitions WHERE run_id = ?
                """, Integer.class, runId);
            if (attemptsInCurrentCycle(runId) >= maxAttempts) {
                throw new DomainConflictException(
                    "RETENTION_MAX_ATTEMPTS_REACHED",
                    "The retention run has exhausted its retry policy.");
            }
            transition(runId, "EXECUTION_STARTED", nextAttempt, 0, 0, 0,
                null, "EXECUTION_STARTED", subject);
            OffsetDateTime leaseExpiry = now.plus(executionLeaseDuration);
            jdbc.update("""
                INSERT INTO f07_retention_execution_leases(
                    run_id, owner_id, attempt, acquired_at,
                    heartbeat_at, lease_expires_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id) DO UPDATE
                SET owner_id = EXCLUDED.owner_id,
                    attempt = EXCLUDED.attempt,
                    acquired_at = EXCLUDED.acquired_at,
                    heartbeat_at = EXCLUDED.heartbeat_at,
                    lease_expires_at = EXCLUDED.lease_expires_at
                """, runId, ownerId, nextAttempt,
                Timestamp.from(now.toInstant()),
                Timestamp.from(now.toInstant()),
                Timestamp.from(leaseExpiry.toInstant()));
            return new BeginResult(nextAttempt, false);
        });
        if (result == null) {
            throw new IllegalStateException(
                "Retention execution could not acquire a lifecycle attempt.");
        }
        if (result.deadLettered()) {
            throw new DomainConflictException(
                "RETENTION_RUN_DEAD_LETTERED",
                "The abandoned run exhausted its retry policy.");
        }
        return Objects.requireNonNull(result.attempt());
    }

    private void heartbeat(UUID runId, UUID ownerId) {
        candidateTransactions.executeWithoutResult(transaction -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (jdbc.update("""
                UPDATE f07_retention_execution_leases
                SET heartbeat_at = ?, lease_expires_at = ?
                WHERE run_id = ? AND owner_id = ?
                """, Timestamp.from(now.toInstant()),
                Timestamp.from(now.plus(executionLeaseDuration).toInstant()),
                runId, ownerId) != 1) {
                throw new DomainConflictException(
                    "RETENTION_EXECUTION_SUPERSEDED",
                    "A newer retention execution owns the run lease.");
            }
        });
    }

    private int attemptsInCurrentCycle(UUID runId) {
        return jdbc.queryForObject("""
            SELECT count(*)
            FROM f07_retention_run_transitions transition
            WHERE transition.run_id = ?
              AND transition.status = 'EXECUTION_STARTED'
              AND transition.transition_sequence > COALESCE(
                  (
                      SELECT max(recovery.transition_sequence)
                      FROM f07_retention_run_transitions recovery
                      WHERE recovery.run_id = transition.run_id
                        AND recovery.status = 'RECOVERY_AUTHORIZED'
                  ),
                  0
              )
            """, Integer.class, runId);
    }

    private void completeExecution(
        RunRow run,
        String subject,
        String idempotencyKey,
        int attempt,
        UUID ownerId,
        String status,
        int expired,
        int skipped,
        int failed,
        OffsetDateTime next
    ) {
        jdbc.queryForObject("""
            SELECT id FROM f07_retention_runs
            WHERE id = ?
            FOR UPDATE
            """, UUID.class, run.id());
        ExecutionState latest = latestExecutionState(run.id());
        UUID leaseOwner = jdbc.query("""
            SELECT owner_id
            FROM f07_retention_execution_leases
            WHERE run_id = ?
            FOR UPDATE
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            run.id());
        if (!"EXECUTION_STARTED".equals(latest.status())
            || latest.attempt() != attempt
            || !ownerId.equals(leaseOwner)) {
            throw new DomainConflictException(
                "RETENTION_EXECUTION_SUPERSEDED",
                "A newer retention execution owns run completion.");
        }
        transition(run.id(), status, attempt, expired, skipped, failed,
            next, failed == 0 ? "CAPABILITIES_EXPIRED"
                : "DEAD_LETTER".equals(status)
                    ? "MAX_ATTEMPTS_EXHAUSTED"
                    : "RETRYABLE_FAILURE", subject);
        jdbc.update("""
            DELETE FROM f07_retention_execution_leases
            WHERE run_id = ? AND owner_id = ?
            """, run.id(), ownerId);
        journal.audit(null, "RETENTION_EXECUTION_" + status,
            "RETENTION_RUN", run.id(), (long) attempt, "SUCCESS", status,
            subject, authority(run.organizationId(), "retention.execute"),
            List.of());
        journal.remember(subject, "RETENTION_EXECUTE", run.id(),
            idempotencyKey, Map.of("runId", run.id()),
            "RETENTION_RUN", run.id());
    }

    private ExecutionState latestExecutionState(UUID runId) {
        ExecutionState state = jdbc.query("""
            SELECT status, attempt, next_attempt_at, recorded_at
            FROM f07_retention_run_transitions
            WHERE run_id = ?
            ORDER BY transition_sequence DESC
            LIMIT 1
            """, rs -> rs.next() ? new ExecutionState(
                rs.getString(1),
                rs.getInt(2),
                rs.getTimestamp(3) == null ? null
                    : rs.getTimestamp(3).toInstant()
                        .atOffset(java.time.ZoneOffset.UTC),
                rs.getTimestamp(4).toInstant()
                    .atOffset(java.time.ZoneOffset.UTC)) : null, runId);
        if (state == null) {
            throw new DomainConflictException(
                "RETENTION_DRY_RUN_REQUIRED",
                "The retention run has no dry-run evidence.");
        }
        return state;
    }

    public Map<String, Object> run(String subject, UUID runId) {
        RunRow row = runRow(runId);
        require(subject, row.organizationId(), "retention.execute");
        List<Map<String, Object>> candidateViews = jdbc.query("""
            SELECT id, target_type, target_id, artifact_id, deadline,
                   decision, reason_code, classification, source_hash,
                   evidence_preserved
            FROM f07_retention_candidates
            WHERE run_id = ?
            ORDER BY target_type, target_id
            """, (rs, index) -> map(
                "candidateId", rs.getObject(1, UUID.class),
                "targetType", rs.getString(2),
                "targetId", rs.getObject(3, UUID.class),
                "artifactId", rs.getObject(4, UUID.class),
                "deadline", rs.getTimestamp(5).toInstant(),
                "decision", rs.getString(6),
                "reasonCode", rs.getString(7),
                "classification", rs.getString(8),
                "sourceHash", rs.getString(9),
                "closedEvidencePreserved", rs.getBoolean(10)), runId);
        List<Map<String, Object>> transitions = jdbc.query("""
            SELECT status, attempt, eligible_count, skipped_count,
                   failure_count, next_attempt_at, reason_code, recorded_at
            FROM f07_retention_run_transitions
            WHERE run_id = ?
            ORDER BY transition_sequence
            """, (rs, index) -> map(
                "status", rs.getString(1), "attempt", rs.getInt(2),
                "eligibleCount", rs.getInt(3),
                "skippedCount", rs.getInt(4),
                "failureCount", rs.getInt(5),
                "nextAttemptAt", rs.getTimestamp(6) == null ? null
                    : rs.getTimestamp(6).toInstant(),
                "reasonCode", rs.getString(7),
                "recordedAt", rs.getTimestamp(8).toInstant()), runId);
        List<Map<String, Object>> results = jdbc.query("""
            SELECT candidate_id, attempt, outcome, reason_code, recorded_at
            FROM f07_retention_execution_results
            WHERE run_id = ?
            ORDER BY recorded_at, id
            """, (rs, index) -> map(
                "candidateId", rs.getObject(1, UUID.class),
                "attempt", rs.getInt(2),
                "outcome", rs.getString(3),
                "reasonCode", rs.getString(4),
                "recordedAt", rs.getTimestamp(5).toInstant()), runId);
        List<Map<String, Object>> proofs = jdbc.query("""
            SELECT candidate_id, proof_type, target_type, target_id,
                   source_hash, content_deleted, closed_evidence_preserved,
                   expired_at
            FROM f07_retention_proofs
            WHERE run_id = ?
            ORDER BY expired_at, id
            """, (rs, index) -> map(
                "candidateId", rs.getObject(1, UUID.class),
                "proofType", rs.getString(2),
                "targetType", rs.getString(3),
                "targetId", rs.getObject(4, UUID.class),
                "sourceHash", rs.getString(5),
                "contentDeleted", rs.getBoolean(6),
                "closedEvidencePreserved", rs.getBoolean(7),
                "expiredAt", rs.getTimestamp(8).toInstant()), runId);
        return map(
            "runId", row.id(), "organizationId", row.organizationId(),
            "recordClass", row.recordClass(), "asOf", row.asOf(),
            "candidates", candidateViews, "transitions", transitions,
            "results", results, "proofs", proofs);
    }

    @Transactional
    public Map<String, Object> placeHold(
        String subject,
        UUID artifactId,
        HoldInput input,
        String idempotencyKey
    ) {
        Artifact artifact = artifact(artifactId, true);
        require(subject, artifact.organizationId(), "legal-hold.manage");
        UUID replay = journal.replay(
            subject, "F07_LEGAL_HOLD_PLACE", artifactId,
            idempotencyKey, input);
        if (replay != null) {
            return hold(subject, replay);
        }
        if (artifact.legalHold()) {
            throw new DomainConflictException(
                "LEGAL_HOLD_ALREADY_ACTIVE", "The artifact is already held.");
        }
        String reason = reason(input.reasonCode());
        UUID holdId = UUID.randomUUID();
        Map<String, Object> authority =
            authority(artifact.organizationId(), "legal-hold.manage");
        UUID correlation = CorrelationIdFilter.currentOrNew();
        jdbc.update("""
            INSERT INTO f07_legal_holds(
                id, artifact_id, organization_id, reason_code,
                two_person_release, placed_by_subject, authority_snapshot,
                correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """, holdId, artifactId, artifact.organizationId(), reason,
            twoPersonRelease, subject, canonical.write(authority), correlation);
        holdTransition(holdId, "PLACED", false, true, reason,
            subject, authority, correlation);
        applyArtifactHold(
            artifact, true, reason, subject, authority, correlation);
        journal.audit(artifact.monthId(), "F07_LEGAL_HOLD_PLACED",
            "PRIVATE_ARTIFACT", artifactId, null, "SUCCESS", reason,
            subject, authority, List.of(Map.of("holdId", holdId)));
        journal.remember(subject, "F07_LEGAL_HOLD_PLACE", artifactId,
            idempotencyKey, input, "F07_LEGAL_HOLD", holdId);
        return hold(subject, holdId);
    }

    @Transactional
    public Map<String, Object> requestRelease(
        String subject,
        UUID artifactId,
        UUID holdId,
        ReleaseInput input,
        String idempotencyKey
    ) {
        return release(subject, artifactId, holdId, input, idempotencyKey, false);
    }

    @Transactional
    public Map<String, Object> approveRelease(
        String subject,
        UUID artifactId,
        UUID holdId,
        ReleaseInput input,
        String idempotencyKey
    ) {
        return release(subject, artifactId, holdId, input, idempotencyKey, true);
    }

    public Map<String, Object> hold(String subject, UUID holdId) {
        HoldRow hold = holdRow(holdId);
        require(subject, hold.organizationId(), "legal-hold.manage");
        List<Map<String, Object>> transitions = jdbc.query("""
            SELECT action, prior_hold, effective_hold, reason_code,
                   actor_subject, recorded_at
            FROM f07_legal_hold_transitions WHERE hold_id = ?
            ORDER BY recorded_at, id
            """, (rs, index) -> map(
                "action", rs.getString(1),
                "priorHold", rs.getBoolean(2),
                "effectiveHold", rs.getBoolean(3),
                "reasonCode", rs.getString(4),
                "actor", rs.getString(5),
                "recordedAt", rs.getTimestamp(6).toInstant()), holdId);
        return map(
            "holdId", hold.id(), "artifactId", hold.artifactId(),
            "organizationId", hold.organizationId(),
            "twoPersonRelease", hold.twoPersonRelease(),
            "transitions", transitions);
    }

    public List<Map<String, Object>> classification(
        String subject,
        UUID organizationId
    ) {
        require(subject, organizationId, "retention.schedule.manage");
        return jdbc.query("""
            SELECT asset_type, asset_name, classification, handling_rule,
                   retention_record_class, prohibited_commercial_data
            FROM f07_data_classification_inventory
            ORDER BY asset_type, asset_name
            """, (rs, index) -> map(
                "assetType", rs.getString(1),
                "assetName", rs.getString(2),
                "classification", rs.getString(3),
                "handlingRule", rs.getString(4),
                "retentionRecordClass", rs.getString(5),
                "prohibitedCommercialData", rs.getBoolean(6)));
    }

    private Map<String, Object> release(
        String subject, UUID artifactId, UUID holdId, ReleaseInput input,
        String idempotencyKey, boolean approval
    ) {
        Artifact artifact = artifact(artifactId, true);
        HoldRow hold = holdRow(holdId);
        if (!artifactId.equals(hold.artifactId())
            || !artifact.organizationId().equals(hold.organizationId())) {
            throw new EntityNotFoundException("Legal hold not found.");
        }
        require(subject, artifact.organizationId(), "legal-hold.manage");
        boolean immediateRelease = !hold.twoPersonRelease() && !approval;
        String operation = approval
            ? "F07_LEGAL_HOLD_RELEASE_APPROVE"
            : immediateRelease
                ? "F07_LEGAL_HOLD_RELEASE_EFFECTIVE"
                : "F07_LEGAL_HOLD_RELEASE_REQUEST";
        UUID replay = journal.replay(
            subject, operation, holdId, idempotencyKey, input);
        if (replay != null) {
            return hold(subject, holdId);
        }
        if (!artifact.legalHold()) {
            throw new DomainConflictException(
                "LEGAL_HOLD_NOT_ACTIVE", "The artifact is not held.");
        }
        String reason = reason(input.reasonCode());
        Map<String, Object> authority =
            authority(artifact.organizationId(), "legal-hold.manage");
        UUID correlation = CorrelationIdFilter.currentOrNew();
        if (hold.twoPersonRelease() && !approval) {
            holdTransition(holdId, "RELEASE_REQUESTED", true, true,
                reason, subject, authority, correlation);
        } else {
            if (hold.twoPersonRelease()) {
                String requester = jdbc.query("""
                    SELECT actor_subject
                    FROM f07_legal_hold_transitions
                    WHERE hold_id = ? AND action = 'RELEASE_REQUESTED'
                    """, rs -> rs.next() ? rs.getString(1) : null, holdId);
                if (requester == null) {
                    throw new DomainConflictException(
                        "LEGAL_HOLD_RELEASE_REQUEST_REQUIRED",
                        "A legal-hold release request must be recorded first.");
                }
                if (subject.equals(requester)) {
                    throw new AccessDeniedException(
                        "A different authorized actor must approve release.");
                }
            } else if (approval) {
                throw new DomainConflictException(
                    "SECOND_APPROVAL_NOT_REQUIRED",
                    "This hold does not require a second release approval.");
            }
            holdTransition(holdId, "RELEASE_APPROVED", true, false,
                reason, subject, authority, correlation);
            applyArtifactHold(
                artifact, false, reason, subject, authority, correlation);
        }
        String auditAction = approval
            ? "F07_LEGAL_HOLD_RELEASE_APPROVED"
            : immediateRelease
                ? "F07_LEGAL_HOLD_RELEASED"
                : "F07_LEGAL_HOLD_RELEASE_REQUESTED";
        journal.audit(artifact.monthId(), auditAction,
            "PRIVATE_ARTIFACT", artifactId, null, "SUCCESS", reason,
            subject, authority, List.of(Map.of("holdId", holdId)));
        journal.remember(subject, operation, holdId, idempotencyKey,
            input, "F07_LEGAL_HOLD", holdId);
        return hold(subject, holdId);
    }

    private void collectExports(
        UUID runId, DryRunInput input, int days
    ) {
        jdbc.query("""
            SELECT value.id, value.result_artifact_id,
                   LEAST(
                       COALESCE(value.expires_at,
                           value.requested_at + (? * INTERVAL '1 day')),
                       value.requested_at + (? * INTERVAL '1 day')),
                   COALESCE(artifact.legal_hold, FALSE),
                   COALESCE(artifact.classification, 'CONFIDENTIAL'),
                   value.result_hash
            FROM f05_report_exports value
            LEFT JOIN f05_private_artifacts artifact
              ON artifact.id = value.result_artifact_id
            WHERE value.organization_id = ? AND value.status = 'READY'
            ORDER BY value.requested_at, value.id
            """, rs -> {
                while (rs.next()) {
                    candidate(runId, "REPORT_EXPORT",
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getTimestamp(3).toInstant()
                            .atOffset(java.time.ZoneOffset.UTC),
                        rs.getBoolean(4), rs.getString(5), rs.getString(6),
                        input.asOf());
                }
                return null;
            }, days, days, input.organizationId());
    }

    private void collectShares(
        UUID runId, DryRunInput input, int days
    ) {
        jdbc.query("""
            SELECT share.id, package.invoice_document_artifact_id,
                   LEAST(share.expires_at,
                         share.created_at + (? * INTERVAL '1 day')),
                   artifact.legal_hold, artifact.classification,
                   package.canonical_input_hash
            FROM evidence_package_shares share
            JOIN evidence_package_versions package
              ON package.id = share.package_version_id
            JOIN engagement_months month
              ON month.id = package.engagement_month_id
            JOIN engagements engagement ON engagement.id = month.engagement_id
            JOIN f05_private_artifacts artifact
              ON artifact.id = package.invoice_document_artifact_id
            WHERE share.revoked_at IS NULL
              AND (
                  engagement.vendor_organization_id = ?
                  OR engagement.client_organization_id = ?
                  OR engagement.procurement_organization_id = ?
                  OR engagement.finance_organization_id = ?
              )
            ORDER BY share.created_at, share.id
            """, rs -> {
                while (rs.next()) {
                    candidate(runId, "PACKAGE_SHARE",
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getTimestamp(3).toInstant()
                            .atOffset(java.time.ZoneOffset.UTC),
                        rs.getBoolean(4), rs.getString(5), rs.getString(6),
                        input.asOf());
                }
                return null;
            }, days, input.organizationId(), input.organizationId(),
            input.organizationId(), input.organizationId());
    }

    private void candidate(
        UUID runId, String targetType, UUID targetId, UUID artifactId,
        OffsetDateTime deadline, boolean held, String classification,
        String sourceHash, OffsetDateTime asOf
    ) {
        String decision = held ? "HELD"
            : deadline.isAfter(asOf) ? "NOT_DUE" : "ELIGIBLE";
        String reason = held ? "LEGAL_HOLD_ACTIVE"
            : "NOT_DUE".equals(decision) ? "RETENTION_DEADLINE_NOT_REACHED"
            : "CAPABILITY_EXPIRY_DUE_EVIDENCE_PRESERVED";
        String proofHash = sourceHash == null
            ? canonical.sha256(map(
                "schemaVersion", 1,
                "targetType", targetType,
                "targetId", targetId,
                "artifactId", artifactId,
                "deadline", deadline,
                "classification", classification))
            : sourceHash;
        jdbc.update("""
            INSERT INTO f07_retention_candidates(
                id, run_id, target_type, target_id, artifact_id, deadline,
                decision, reason_code, classification, source_hash,
                evidence_preserved
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
            """, UUID.randomUUID(), runId, targetType, targetId, artifactId,
            Timestamp.from(deadline.toInstant()), decision, reason,
            classification, proofHash);
    }

    private String applyCandidate(
        String subject, RunRow run, Candidate candidate, int attempt
    ) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM f07_retention_proofs WHERE candidate_id = ?
            )
            """, Boolean.class, candidate.id()))) {
            result(run.id(), candidate.id(), attempt, "ALREADY_APPLIED",
                "PRIOR_PROOF_EXISTS", subject);
            return "ALREADY_APPLIED";
        }
        if (!"ELIGIBLE".equals(candidate.decision())) {
            String outcome = "HELD".equals(candidate.decision())
                ? "SKIPPED_HELD" : "SKIPPED_STATE_CHANGED";
            result(run.id(), candidate.id(), attempt, outcome,
                candidate.reasonCode(), subject);
            return outcome;
        }
        if (candidate.artifactId() != null && Boolean.TRUE.equals(
            jdbc.queryForObject("""
                SELECT legal_hold
                FROM f05_private_artifacts
                WHERE id = ?
                FOR UPDATE
                """, Boolean.class, candidate.artifactId()))) {
            result(run.id(), candidate.id(), attempt, "SKIPPED_HELD",
                "LEGAL_HOLD_ACTIVE_AT_EXECUTION", subject);
            return "SKIPPED_HELD";
        }
        int changed;
        if ("REPORT_EXPORT".equals(candidate.targetType())) {
            changed = jdbc.update("""
                UPDATE f05_report_exports
                SET status = 'EXPIRED'
                WHERE id = ? AND organization_id = ? AND status = 'READY'
                """, candidate.targetId(), run.organizationId());
        } else {
            changed = jdbc.update("""
                UPDATE evidence_package_shares
                SET revoked_at = CURRENT_TIMESTAMP,
                    revoked_by_subject = ?
                WHERE id = ? AND revoked_at IS NULL
                """, subject, candidate.targetId());
        }
        if (changed != 1) {
            result(run.id(), candidate.id(), attempt, "SKIPPED_STATE_CHANGED",
                "CAPABILITY_STATE_CHANGED", subject);
            return "SKIPPED_STATE_CHANGED";
        }
        jdbc.update("""
            INSERT INTO f07_retention_proofs(
                id, run_id, candidate_id, proof_type, target_type, target_id,
                source_hash, content_deleted, closed_evidence_preserved,
                expired_by_subject, correlation_id
            ) VALUES (?, ?, ?, 'CAPABILITY_EXPIRY', ?, ?, ?, FALSE, TRUE, ?, ?)
            """, UUID.randomUUID(), run.id(), candidate.id(),
            candidate.targetType(), candidate.targetId(),
            candidate.sourceHash(), subject, CorrelationIdFilter.currentOrNew());
        result(run.id(), candidate.id(), attempt, "CAPABILITY_EXPIRED",
            "CLOSED_EVIDENCE_PRESERVED", subject);
        return "CAPABILITY_EXPIRED";
    }

    private void result(
        UUID runId, UUID candidateId, int attempt, String outcome,
        String reason, String subject
    ) {
        jdbc.update("""
            INSERT INTO f07_retention_execution_results(
                id, run_id, candidate_id, attempt, outcome, reason_code,
                actor_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), runId, candidateId, attempt, outcome,
            reason, subject, CorrelationIdFilter.currentOrNew());
    }

    private void transition(
        UUID runId, String status, int attempt, int eligible, int skipped,
        int failed, OffsetDateTime next, String reason, String subject
    ) {
        jdbc.update("""
            INSERT INTO f07_retention_run_transitions(
                id, run_id, status, attempt, eligible_count, skipped_count,
                failure_count, next_attempt_at, reason_code, actor_subject,
                correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), runId, status, attempt, eligible, skipped,
            failed, next == null ? null : Timestamp.from(next.toInstant()),
            reason, subject, CorrelationIdFilter.currentOrNew());
    }

    private Counts counts(UUID runId) {
        return jdbc.query("""
            SELECT count(*) FILTER (WHERE decision = 'ELIGIBLE'),
                   count(*) FILTER (WHERE decision <> 'ELIGIBLE')
            FROM f07_retention_candidates WHERE run_id = ?
            """, rs -> {
                rs.next();
                return new Counts(rs.getInt(1), rs.getInt(2));
            }, runId);
    }

    private List<Candidate> candidates(UUID runId) {
        return jdbc.query("""
            SELECT id, target_type, target_id, artifact_id, decision,
                   reason_code, source_hash
            FROM f07_retention_candidates
            WHERE run_id = ?
            ORDER BY target_type, target_id
            """, (rs, index) -> new Candidate(
                rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getString(5), rs.getString(6), rs.getString(7)), runId);
    }

    private EffectiveSchedule effectiveSchedule(
        UUID organizationId, String recordClass, OffsetDateTime asOf
    ) {
        EffectiveSchedule result = jdbc.query("""
            SELECT id, retention_days
            FROM f07_retention_schedules
            WHERE organization_id = ? AND record_class = ?
              AND effective_from <= ?
            ORDER BY effective_from DESC, version DESC
            LIMIT 1
            """, rs -> rs.next()
                ? new EffectiveSchedule(
                    rs.getObject(1, UUID.class), rs.getInt(2))
                : null, organizationId, recordClass,
            Timestamp.from(asOf.toInstant()));
        if (result == null) {
            throw new DomainConflictException(
                "RETENTION_SCHEDULE_REQUIRED",
                "No effective retention schedule is configured.");
        }
        return result;
    }

    private Map<String, Object> schedule(UUID id) {
        Map<String, Object> result = jdbc.query("""
            SELECT id, organization_id, record_class, version,
                   retention_days, policy_reference, effective_from,
                   supersedes_id, created_at
            FROM f07_retention_schedules WHERE id = ?
            """, rs -> rs.next() ? map(
                "scheduleId", rs.getObject(1, UUID.class),
                "organizationId", rs.getObject(2, UUID.class),
                "recordClass", rs.getString(3),
                "version", rs.getInt(4),
                "retentionDays", rs.getInt(5),
                "policyReference", rs.getString(6),
                "effectiveFrom", rs.getTimestamp(7).toInstant(),
                "supersedesId", rs.getObject(8, UUID.class),
                "createdAt", rs.getTimestamp(9).toInstant()) : null, id);
        if (result == null) {
            throw new EntityNotFoundException("Retention schedule not found.");
        }
        return result;
    }

    private RunRow runRow(UUID id) {
        RunRow row = jdbc.query("""
            SELECT id, organization_id, record_class, as_of
            FROM f07_retention_runs WHERE id = ?
            """, rs -> rs.next() ? new RunRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getTimestamp(4).toInstant()
                    .atOffset(java.time.ZoneOffset.UTC)) : null, id);
        if (row == null) {
            throw new EntityNotFoundException("Retention run not found.");
        }
        return row;
    }

    private Artifact artifact(UUID id, boolean lock) {
        Artifact result = jdbc.query("""
            SELECT artifact.id, artifact.engagement_month_id,
                   artifact.owner_organization_id, artifact.legal_hold
            FROM f05_private_artifacts artifact
            WHERE artifact.id = ?
            """ + (lock ? " FOR UPDATE" : ""), rs -> rs.next()
                ? new Artifact(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class), rs.getBoolean(4))
                : null, id);
        if (result == null) {
            throw new EntityNotFoundException("Artifact not found.");
        }
        return result;
    }

    private HoldRow holdRow(UUID id) {
        HoldRow result = jdbc.query("""
            SELECT id, artifact_id, organization_id, two_person_release
            FROM f07_legal_holds WHERE id = ?
            """, rs -> rs.next() ? new HoldRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getBoolean(4)) : null, id);
        if (result == null) {
            throw new EntityNotFoundException("Legal hold not found.");
        }
        return result;
    }

    private void holdTransition(
        UUID holdId, String action, boolean prior, boolean effective,
        String reason, String subject, Map<String, Object> authority,
        UUID correlation
    ) {
        jdbc.update("""
            INSERT INTO f07_legal_hold_transitions(
                id, hold_id, action, prior_hold, effective_hold, reason_code,
                actor_subject, authority_snapshot, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            """, UUID.randomUUID(), holdId, action, prior, effective,
            reason, subject, canonical.write(authority), correlation);
    }

    private void applyArtifactHold(
        Artifact artifact, boolean enabled, String reason, String subject,
        Map<String, Object> authority, UUID correlation
    ) {
        jdbc.update("""
            INSERT INTO f05_artifact_hold_transitions(
                id, artifact_id, prior_legal_hold, legal_hold, reason_code,
                authority_snapshot, actor_subject, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """, UUID.randomUUID(), artifact.id(), artifact.legalHold(),
            enabled, reason, canonical.write(authority), subject, correlation);
        if (jdbc.update("""
            UPDATE f05_private_artifacts SET legal_hold = ?
            WHERE id = ? AND legal_hold = ?
            """, enabled, artifact.id(), artifact.legalHold()) != 1) {
            throw new DomainConflictException(
                "LEGAL_HOLD_CONCURRENT_CHANGE",
                "The artifact legal-hold state changed concurrently.");
        }
    }

    private void require(String subject, UUID organizationId, String permission) {
        LocalDate today = LocalDate.now(clock);
        if (subject == null
            || !authorization.hasActivePrincipal(subject, today)
            || !authorization.hasOrganizationPermission(
                subject, organizationId, permission, today)) {
            throw new AccessDeniedException(
                "The authenticated identity lacks organization governance authority.");
        }
    }

    private Map<String, Object> authority(UUID organizationId, String permission) {
        return Map.of(
            "organizationId", organizationId,
            "permission", permission,
            "authorityDerivedBy", "SERVER");
    }

    private String recordClass(String value) {
        String normalized = value == null ? ""
            : value.strip().toUpperCase(Locale.ROOT);
        if (!RECORD_CLASSES.contains(normalized)) {
            throw new IllegalArgumentException(
                "Unsupported retention record class.");
        }
        return normalized;
    }

    private String reason(String value) {
        String normalized = safe(value, 100).toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9_-]", "_");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("A reason code is required.");
        }
        return normalized;
    }

    private String safe(String value, int limit) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A required value is missing.");
        }
        String result = value.strip();
        return result.substring(0, Math.min(result.length(), limit));
    }

    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private record ScheduleVersion(UUID id, int version) {
    }

    private record EffectiveSchedule(UUID id, int days) {
    }

    private record RunRow(
        UUID id, UUID organizationId, String recordClass, OffsetDateTime asOf
    ) {
    }

    private record Counts(int eligible, int skipped) {
    }

    private record Candidate(
        UUID id, String targetType, UUID targetId, UUID artifactId,
        String decision, String reasonCode, String sourceHash
    ) {
    }

    private record ExecutionState(
        String status,
        int attempt,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime recordedAt
    ) {
    }

    private record BeginResult(Integer attempt, boolean deadLettered) {
    }

    private record LeaseRow(
        UUID ownerId,
        int attempt,
        OffsetDateTime leaseExpiresAt
    ) {
    }

    private record Artifact(
        UUID id, UUID monthId, UUID organizationId, boolean legalHold
    ) {
    }

    private record HoldRow(
        UUID id, UUID artifactId, UUID organizationId,
        boolean twoPersonRelease
    ) {
    }
}
