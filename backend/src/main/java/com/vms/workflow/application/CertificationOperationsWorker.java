package com.vms.workflow.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Component
public class CertificationOperationsWorker {
    private static final int BATCH_SIZE = 25;
    private static final int MAX_ATTEMPTS = 5;
    private static final int LEASE_SECONDS = 120;

    private final JdbcTemplate jdbc;
    private final CertificationEmailAdapter emailAdapter;
    private final ConfirmationTokenHandoffVault tokenHandoffs;
    private final F05CertificationReadinessPublisher f05Publisher;
    private final BusinessConfirmationService confirmations;
    private final CertificationSecurityEventService securityEvents;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String workerId = "f04-worker-" + UUID.randomUUID();

    public CertificationOperationsWorker(
        JdbcTemplate jdbc,
        CertificationEmailAdapter emailAdapter,
        ConfirmationTokenHandoffVault tokenHandoffs,
        F05CertificationReadinessPublisher f05Publisher,
        BusinessConfirmationService confirmations,
        CertificationSecurityEventService securityEvents,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.emailAdapter = emailAdapter;
        this.tokenHandoffs = tokenHandoffs;
        this.f05Publisher = f05Publisher;
        this.confirmations = confirmations;
        this.securityEvents = securityEvents;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Scheduled(
        fixedDelayString = "${vms.certification.worker-delay:PT5S}",
        initialDelayString = "${vms.certification.worker-initial-delay:PT5S}"
    )
    public void runOnce() {
        dispatchNotifications();
        publishF05Handoffs();
        processSchedules();
    }

    public int dispatchNotifications() {
        int handled = 0;
        while (handled < BATCH_SIZE) {
            OutboxClaim claim = claimOutbox();
            if (claim == null) {
                break;
            }
            dispatch(claim);
            handled++;
        }
        return handled;
    }

    public int processSchedules() {
        int handled = 0;
        while (handled < BATCH_SIZE) {
            ScheduleClaim claim = claimSchedule();
            if (claim == null) {
                break;
            }
            try {
                confirmations.processClaimedSchedule(claim.id(), workerId);
            } catch (RuntimeException exception) {
                failSchedule(claim);
            }
            handled++;
        }
        return handled;
    }

    public int publishF05Handoffs() {
        if (!"CONFIGURED".equals(f05Publisher.configurationStatus())) {
            return 0;
        }
        int handled = 0;
        while (handled < BATCH_SIZE) {
            F05Claim claim = claimF05();
            if (claim == null) {
                break;
            }
            publishF05(claim);
            handled++;
        }
        return handled;
    }

    private OutboxClaim claimOutbox() {
        return jdbc.query("""
            WITH candidate AS (
                SELECT outbox.id
                FROM notification_outbox outbox
                WHERE outbox.provider_status = 'CONFIGURED'
                  AND outbox.transport_status IN ('QUEUED', 'FAILED')
                  AND COALESCE(outbox.next_attempt_at, outbox.created_at)
                      <= CURRENT_TIMESTAMP
                  AND (
                      outbox.lease_expires_at IS NULL
                      OR outbox.lease_expires_at < CURRENT_TIMESTAMP
                  )
                ORDER BY COALESCE(outbox.next_attempt_at, outbox.created_at),
                         outbox.created_at, outbox.id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE notification_outbox outbox
            SET transport_status = 'SENDING',
                lease_owner = ?,
                lease_expires_at =
                    CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                attempt_count = outbox.attempt_count + 1,
                generation_attempt_count =
                    outbox.generation_attempt_count + 1
            FROM candidate
            WHERE outbox.id = candidate.id
            RETURNING outbox.id, outbox.idempotency_key,
                      outbox.subject_text, outbox.plain_text,
                      outbox.html_text, outbox.recipient_snapshot::text,
                      outbox.correlation_id, outbox.attempt_count,
                      outbox.generation_attempt_count,
                      outbox.engagement_month_id,
                      outbox.business_object_type,
                      outbox.business_object_id,
                      outbox.business_object_type =
                          'confirmation_secure_token' AS secure_handoff
            """, rs -> rs.next() ? new OutboxClaim(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("subject_text"),
                rs.getString("plain_text"),
                rs.getString("html_text"),
                rs.getString("recipient_snapshot"),
                rs.getObject("correlation_id", UUID.class),
                rs.getInt("attempt_count"),
                rs.getInt("generation_attempt_count"),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getString("business_object_type"),
                rs.getObject("business_object_id", UUID.class),
                rs.getBoolean("secure_handoff")) : null,
            workerId, LEASE_SECONDS);
    }

    private void dispatch(OutboxClaim claim) {
        CertificationEmailAdapter.SendResult result;
        try {
            List<CertificationEmailAdapter.SecureActionLink> secureLinks =
                tokenHandoffs.linksForOutbox(claim.id());
            if (claim.secureHandoff() && secureLinks.isEmpty()) {
                result = new CertificationEmailAdapter.SendResult(
                    "FAILED", null, null,
                    "SECURE_TOKEN_HANDOFF_UNAVAILABLE", false);
            } else {
            result = emailAdapter.send(
                new CertificationEmailAdapter.OutboundMessage(
                    claim.id(), claim.idempotencyKey(), claim.subject(),
                    claim.plainText(), claim.htmlText(),
                    claim.recipientSnapshot(), claim.correlationId(),
                    secureLinks));
            }
        } catch (RuntimeException exception) {
            result = new CertificationEmailAdapter.SendResult(
                "FAILED", null, null, "EMAIL_ADAPTER_FAILURE", true);
        }
        if (result == null) {
            result = new CertificationEmailAdapter.SendResult(
                "FAILED", null, null, "EMAIL_ADAPTER_EMPTY_RESULT", true);
        }
        CertificationEmailAdapter.SendResult finalResult = result;
        transactions.executeWithoutResult(ignored ->
            finalizeDispatch(claim, finalResult));
    }

    private void finalizeDispatch(
        OutboxClaim claim,
        CertificationEmailAdapter.SendResult result
    ) {
        boolean sent = "SENT".equals(result.status())
            || "DELIVERED".equals(result.status());
        String errorCode = sent ? null : safeCode(result.errorCategory());
        boolean retry = !sent && result.retryable()
            && claim.generationAttemptCount() < MAX_ATTEMPTS;
        int updated;
        if (sent) {
            updated = jdbc.update("""
                UPDATE notification_outbox
                SET transport_status = ?,
                    provider_message_id = ?,
                    provider_thread_id = ?,
                    sent_at = COALESCE(sent_at, CURRENT_TIMESTAMP),
                    delivered_at = CASE
                        WHEN ? = 'DELIVERED' THEN CURRENT_TIMESTAMP
                        ELSE delivered_at
                    END,
                    next_attempt_at = NULL,
                    lease_owner = NULL, lease_expires_at = NULL,
                    last_error_code = NULL
                WHERE id = ? AND lease_owner = ?
                  AND transport_status = 'SENDING'
                """, result.status(), result.providerMessageId(),
                result.providerThreadId(), result.status(),
                claim.id(), workerId);
        } else if (retry) {
            updated = jdbc.update("""
                UPDATE notification_outbox
                SET transport_status = 'FAILED',
                    next_attempt_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    lease_owner = NULL, lease_expires_at = NULL,
                    last_error_code = ?
                WHERE id = ? AND lease_owner = ?
                  AND transport_status = 'SENDING'
                """, retrySeconds(claim.generationAttemptCount()), errorCode,
                claim.id(), workerId);
        } else {
            updated = jdbc.update("""
                UPDATE notification_outbox
                SET transport_status = 'DEAD_LETTER',
                    dead_lettered_at = CURRENT_TIMESTAMP,
                    next_attempt_at = NULL,
                    lease_owner = NULL, lease_expires_at = NULL,
                    last_error_code = ?
                WHERE id = ? AND lease_owner = ?
                  AND transport_status = 'SENDING'
                """, errorCode, claim.id(), workerId);
        }
        if (updated == 0) {
            return;
        }
        if (sent) {
            tokenHandoffs.delivered(claim.id());
        } else if (!retry) {
            tokenHandoffs.failed(claim.id(), errorCode);
            securityEvents.recordBestEffort(
                claim.monthId(), "NOTIFICATION_DEAD_LETTERED",
                "SYSTEM:F04_NOTIFICATION_WORKER",
                claim.businessObjectType(), claim.businessObjectId(),
                "DEAD_LETTER", errorCode,
                java.util.Map.of(
                    "attemptCount", claim.attemptCount(),
                    "replayGenerationAttempt",
                    claim.generationAttemptCount()));
        }
        jdbc.update("""
            INSERT INTO notification_delivery_attempts
                (id, outbox_id, attempt_number, status,
                 error_category, sanitized_error_code,
                 provider_message_id, next_retry_at, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), claim.id(), claim.attemptCount(),
            sent ? "SENT" : retry
                ? "RETRYABLE_FAILURE" : "PERMANENT_FAILURE",
            errorCode, errorCode, result.providerMessageId(),
            retry
                ? OffsetDateTime.now(clock).plusSeconds(
                    retrySeconds(claim.generationAttemptCount()))
                : null,
            claim.correlationId());
    }

    private ScheduleClaim claimSchedule() {
        return jdbc.query("""
            WITH candidate AS (
                SELECT schedule.id
                FROM confirmation_request_schedules schedule
                WHERE schedule.status IN ('PENDING', 'CLAIMED')
                  AND COALESCE(schedule.next_attempt_at, schedule.due_at)
                      <= CURRENT_TIMESTAMP
                  AND (
                      schedule.status = 'PENDING'
                      OR schedule.lease_expires_at < CURRENT_TIMESTAMP
                  )
                ORDER BY COALESCE(
                    schedule.next_attempt_at, schedule.due_at),
                    schedule.due_at, schedule.id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE confirmation_request_schedules schedule
            SET status = 'CLAIMED',
                lease_owner = ?,
                lease_expires_at =
                    CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                attempt_count = schedule.attempt_count + 1
            FROM candidate
            WHERE schedule.id = candidate.id
            RETURNING schedule.id, schedule.request_id,
                      schedule.attempt_count
            """, rs -> rs.next() ? new ScheduleClaim(
                rs.getObject("id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getInt("attempt_count")) : null,
            workerId, LEASE_SECONDS);
    }

    private F05Claim claimF05() {
        return jdbc.query("""
            WITH candidate AS (
                SELECT job.id
                FROM f05_handoff_publish_jobs job
                JOIN f05_certification_handoffs handoff
                  ON handoff.id = job.handoff_id
                JOIN business_confirmation_requests request
                  ON request.id = handoff.confirmation_request_id
                WHERE job.status IN ('PENDING', 'CLAIMED')
                  AND request.status = 'CONFIRMED'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM f05_handoff_invalidations invalidation
                      WHERE invalidation.handoff_id = handoff.id
                  )
                  AND COALESCE(job.next_attempt_at, job.created_at)
                      <= CURRENT_TIMESTAMP
                  AND (
                      job.status = 'PENDING'
                      OR job.lease_expires_at < CURRENT_TIMESTAMP
                  )
                ORDER BY COALESCE(job.next_attempt_at, job.created_at),
                         job.created_at, job.id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            ),
            claimed AS (
                UPDATE f05_handoff_publish_jobs job
                SET status = 'CLAIMED',
                    lease_owner = ?,
                    lease_expires_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    attempt_count = job.attempt_count + 1
                FROM candidate
                WHERE job.id = candidate.id
                RETURNING job.*
            )
            SELECT claimed.id, claimed.handoff_id,
                   claimed.attempt_count,
                   handoff.engagement_month_id,
                   handoff.readiness_run_id,
                   readiness.input_hash,
                   handoff.confirmation_request_id,
                   request.scope_checksum,
                   handoff.correlation_id
            FROM claimed
            JOIN f05_certification_handoffs handoff
              ON handoff.id = claimed.handoff_id
            JOIN certification_readiness_runs readiness
              ON readiness.id = handoff.readiness_run_id
            JOIN business_confirmation_requests request
              ON request.id = handoff.confirmation_request_id
            """, rs -> rs.next() ? new F05Claim(
                rs.getObject("id", UUID.class),
                rs.getObject("handoff_id", UUID.class),
                rs.getInt("attempt_count"),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getObject("readiness_run_id", UUID.class),
                rs.getString("input_hash"),
                rs.getObject("confirmation_request_id", UUID.class),
                rs.getString("scope_checksum"),
                rs.getObject("correlation_id", UUID.class)) : null,
            workerId, LEASE_SECONDS);
    }

    private void publishF05(F05Claim claim) {
        transactions.executeWithoutResult(ignored -> {
            if (!lockAndValidateF05(claim)) {
                return;
            }
            F05CertificationReadinessPublisher.PublishResult result =
                invokeF05Publisher(claim);
            finalizeF05(claim, result);
        });
    }

    private boolean lockAndValidateF05(F05Claim claim) {
        return !jdbc.queryForList("""
            SELECT job.id
            FROM f05_handoff_publish_jobs job
            JOIN f05_certification_handoffs handoff
              ON handoff.id = job.handoff_id
            JOIN business_confirmation_requests request
              ON request.id = handoff.confirmation_request_id
            WHERE job.id = ?
              AND job.status = 'CLAIMED'
              AND job.lease_owner = ?
              AND request.status = 'CONFIRMED'
              AND NOT EXISTS (
                  SELECT 1
                  FROM f05_handoff_invalidations invalidation
                  WHERE invalidation.handoff_id = handoff.id
            )
            FOR UPDATE OF job
            """, UUID.class, claim.id(), workerId).isEmpty();
    }

    private F05CertificationReadinessPublisher.PublishResult invokeF05Publisher(
        F05Claim claim
    ) {
        F05CertificationReadinessPublisher.PublishResult result;
        try {
            result = f05Publisher.publish(
                new F05CertificationReadinessPublisher.ReadinessFact(
                    claim.monthId(), claim.readinessRunId(),
                    claim.readinessInputHash(), claim.requestId(),
                    claim.scopeChecksum(), claim.correlationId()));
        } catch (RuntimeException exception) {
            result = new F05CertificationReadinessPublisher.PublishResult(
                "FAILED",
                "certification.confirmation.readiness.v1",
                "F05_ADAPTER_FAILURE",
                true);
        }
        if (result == null) {
            result = new F05CertificationReadinessPublisher.PublishResult(
                "FAILED",
                "certification.confirmation.readiness.v1",
                "F05_ADAPTER_EMPTY_RESULT",
                true);
        }
        return result;
    }

    private void finalizeF05(
        F05Claim claim,
        F05CertificationReadinessPublisher.PublishResult result
    ) {
        boolean published = java.util.Set.of(
            "PUBLISHED", "ACCEPTED", "SENT").contains(result.status());
        boolean retry = !published && result.retryable()
            && claim.attemptCount() < MAX_ATTEMPTS;
        String failureCode = published
            ? null : safeCode(result.failureCode());
        int updated;
        if (published) {
            updated = jdbc.update("""
                UPDATE f05_handoff_publish_jobs
                SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP,
                    next_attempt_at = NULL, lease_owner = NULL,
                    lease_expires_at = NULL, last_error_code = NULL
                WHERE id = ? AND status = 'CLAIMED' AND lease_owner = ?
                  AND EXISTS (
                      SELECT 1
                      FROM f05_certification_handoffs handoff
                      JOIN business_confirmation_requests request
                        ON request.id = handoff.confirmation_request_id
                      WHERE handoff.id =
                          f05_handoff_publish_jobs.handoff_id
                        AND request.status = 'CONFIRMED'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM f05_handoff_invalidations invalidation
                            WHERE invalidation.handoff_id = handoff.id
                        )
                  )
                """, claim.id(), workerId);
        } else if (retry) {
            updated = jdbc.update("""
                UPDATE f05_handoff_publish_jobs
                SET status = 'PENDING',
                    next_attempt_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    lease_owner = NULL, lease_expires_at = NULL,
                    last_error_code = ?
                WHERE id = ? AND status = 'CLAIMED' AND lease_owner = ?
                """, retrySeconds(claim.attemptCount()), failureCode,
                claim.id(), workerId);
        } else {
            updated = jdbc.update("""
                UPDATE f05_handoff_publish_jobs
                SET status = 'DEAD_LETTER',
                    completed_at = CURRENT_TIMESTAMP,
                    next_attempt_at = NULL,
                    lease_owner = NULL, lease_expires_at = NULL,
                    last_error_code = ?
                WHERE id = ? AND status = 'CLAIMED' AND lease_owner = ?
                """, failureCode, claim.id(), workerId);
        }
        if (updated == 0) {
            return;
        }
        jdbc.update("""
            INSERT INTO f05_handoff_publish_attempts
                (id, handoff_id, status, contract_version,
                 sanitized_failure_code, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), claim.handoffId(),
            safeCode(result.status()), safeCode(result.contractVersion()),
            failureCode, claim.correlationId());
        if (!published && !retry) {
            securityEvents.recordBestEffort(
                claim.monthId(), "F05_HANDOFF_DEAD_LETTERED",
                "SYSTEM:F04_F05_WORKER",
                "F05_CERTIFICATION_HANDOFF", claim.handoffId(),
                "DEAD_LETTER", failureCode,
                java.util.Map.of("attemptCount", claim.attemptCount()));
        }
    }

    private void failSchedule(ScheduleClaim claim) {
        boolean deadLetter = claim.attemptCount() >= MAX_ATTEMPTS;
        if (deadLetter) {
            jdbc.update("""
                UPDATE confirmation_request_schedules
                SET status = 'DEAD_LETTER',
                    completed_at = CURRENT_TIMESTAMP,
                    next_attempt_at = NULL,
                    lease_owner = NULL, lease_expires_at = NULL,
                    last_error_code = 'SCHEDULE_PROCESSING_FAILURE'
                WHERE id = ? AND lease_owner = ?
                """, claim.id(), workerId);
            UUID monthId = jdbc.query("""
                SELECT engagement_month_id
                FROM business_confirmation_requests
                WHERE id = ?
                """, rs -> rs.next()
                    ? rs.getObject(1, UUID.class) : null,
                claim.requestId());
            securityEvents.recordBestEffort(
                monthId, "CONFIRMATION_SCHEDULE_DEAD_LETTERED",
                "SYSTEM:F04_SCHEDULE_WORKER",
                "BUSINESS_CONFIRMATION_REQUEST", claim.requestId(),
                "DEAD_LETTER", "SCHEDULE_PROCESSING_FAILURE",
                java.util.Map.of("attemptCount", claim.attemptCount()));
        } else {
            jdbc.update("""
                UPDATE confirmation_request_schedules
                SET status = 'PENDING',
                    next_attempt_at =
                        CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                    lease_owner = NULL, lease_expires_at = NULL,
                    last_error_code = 'SCHEDULE_PROCESSING_FAILURE'
                WHERE id = ? AND lease_owner = ?
                """, retrySeconds(claim.attemptCount()),
                claim.id(), workerId);
        }
    }

    private int retrySeconds(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 8);
        return Math.min(30 * (1 << exponent), 21_600);
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "EMAIL_DELIVERY_FAILURE";
        }
        String normalized = value.toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9_]", "_");
        if (normalized.isBlank()) {
            return "EMAIL_DELIVERY_FAILURE";
        }
        return normalized.substring(0, Math.min(normalized.length(), 64));
    }

    private record OutboxClaim(
        UUID id,
        String idempotencyKey,
        String subject,
        String plainText,
        String htmlText,
        String recipientSnapshot,
        UUID correlationId,
        int attemptCount,
        int generationAttemptCount,
        UUID monthId,
        String businessObjectType,
        UUID businessObjectId,
        boolean secureHandoff
    ) {
    }

    private record ScheduleClaim(
        UUID id,
        UUID requestId,
        int attemptCount
    ) {
    }

    private record F05Claim(
        UUID id,
        UUID handoffId,
        int attemptCount,
        UUID monthId,
        UUID readinessRunId,
        String readinessInputHash,
        UUID requestId,
        String scopeChecksum,
        UUID correlationId
    ) {
    }
}
