package com.vms.workflow.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

@Component
@ConditionalOnProperty(
    name = "vms.delivery.commitment.worker-enabled",
    havingValue = "true")
public class DeliveryCommitmentOperationsWorker {
    private static final int BATCH_SIZE = 25;
    private static final int MAX_ATTEMPTS = 5;
    private static final int LEASE_SECONDS = 120;

    private final JdbcTemplate jdbc;
    private final DeliveryCommitmentEmailAdapter adapter;
    private final DeliveryCommitmentConfiguration configuration;
    private final TransactionTemplate transactions;
    private final String workerId = "commitment-worker-" + UUID.randomUUID();

    public DeliveryCommitmentOperationsWorker(
        JdbcTemplate jdbc,
        DeliveryCommitmentEmailAdapter adapter,
        DeliveryCommitmentConfiguration configuration,
        TransactionTemplate transactions
    ) {
        this.jdbc = jdbc;
        this.adapter = adapter;
        this.configuration = configuration;
        this.transactions = transactions;
    }

    @Scheduled(
        fixedDelayString = "${vms.delivery.commitment.worker-delay:PT5S}",
        initialDelayString =
            "${vms.delivery.commitment.worker-initial-delay:PT5S}"
    )
    public void runOnce() {
        dispatchCommitments();
    }

    public int dispatchCommitments() {
        if (!"CONFIGURED".equals(adapter.configurationStatus())) {
            return 0;
        }
        int handled = 0;
        while (handled < BATCH_SIZE) {
            Claim claim = transactions.execute(ignored -> claim());
            if (claim == null) {
                break;
            }
            dispatch(claim);
            handled++;
        }
        return handled;
    }

    private Claim claim() {
        return jdbc.query("""
            WITH candidate AS (
                SELECT outbox.id
                FROM commitment_outbox outbox
                WHERE outbox.status IN ('PENDING', 'RETRY', 'SENDING')
                  AND COALESCE(outbox.next_attempt_at, outbox.created_at)
                      <= CURRENT_TIMESTAMP
                  AND (
                      outbox.status <> 'SENDING'
                      OR outbox.lease_expires_at < CURRENT_TIMESTAMP
                  )
                ORDER BY COALESCE(outbox.next_attempt_at, outbox.created_at),
                         outbox.created_at, outbox.id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE commitment_outbox outbox
            SET status = 'SENDING',
                lease_owner = ?,
                lease_expires_at =
                    CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
                attempt_count = outbox.attempt_count + 1
            FROM candidate
            WHERE outbox.id = candidate.id
            RETURNING outbox.id, outbox.plan_version_id, outbox.baseline_id,
                      outbox.idempotency_key,
                      outbox.recipient_snapshot::text,
                      outbox.subject_text, outbox.plain_text,
                      outbox.html_text, outbox.archive_reference,
                      outbox.attempt_count
            """, rs -> rs.next() ? new Claim(
                rs.getObject("id", UUID.class),
                rs.getObject("plan_version_id", UUID.class),
                rs.getObject("baseline_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("recipient_snapshot"),
                rs.getString("subject_text"),
                rs.getString("plain_text"),
                rs.getString("html_text"),
                rs.getString("archive_reference"),
                rs.getInt("attempt_count")) : null,
            workerId, LEASE_SECONDS);
    }

    private void dispatch(Claim claim) {
        DeliveryCommitmentEmailAdapter.SendResult result;
        try {
            result = adapter.send(
                new DeliveryCommitmentEmailAdapter.OutboundCommitment(
                    claim.id(), claim.planVersionId(), claim.baselineId(),
                    claim.idempotencyKey(), claim.recipientSnapshot(),
                    claim.subject(), claim.plainText(), claim.htmlText(),
                    claim.archiveReference()));
        } catch (RuntimeException exception) {
            result = new DeliveryCommitmentEmailAdapter.SendResult(
                "FAILED", null, null,
                "COMMITMENT_ADAPTER_FAILURE", true);
        }
        if (result == null) {
            result = new DeliveryCommitmentEmailAdapter.SendResult(
                "FAILED", null, null,
                "COMMITMENT_ADAPTER_EMPTY_RESULT", true);
        }
        DeliveryCommitmentEmailAdapter.SendResult finalResult = result;
        transactions.executeWithoutResult(ignored ->
            finalizeDispatch(claim, finalResult));
    }

    private void finalizeDispatch(
        Claim claim,
        DeliveryCommitmentEmailAdapter.SendResult result
    ) {
        boolean sent = "SENT".equals(result.status())
            || "DELIVERED".equals(result.status());
        boolean retry = !sent && result.retryable()
            && claim.attemptNumber() < MAX_ATTEMPTS;
        String errorCode = sent ? null : safeCode(result.errorCode());
        int updated;
        if (sent) {
            updated = jdbc.update("""
                UPDATE commitment_outbox
                SET status = 'SENT', sent_at = COALESCE(sent_at, CURRENT_TIMESTAMP),
                    provider_message_id = ?, provider_thread_id = ?,
                    next_attempt_at = NULL,
                    lease_owner = NULL, lease_expires_at = NULL,
                    last_error_code = NULL
                WHERE id = ? AND status = 'SENDING' AND lease_owner = ?
                """, result.providerMessageId(), result.providerThreadId(),
                claim.id(), workerId);
        } else if (retry) {
            updated = jdbc.update("""
                UPDATE commitment_outbox
                SET status = 'RETRY',
                    next_attempt_at = CURRENT_TIMESTAMP
                        + (? * INTERVAL '1 millisecond'),
                    lease_owner = NULL, lease_expires_at = NULL,
                    last_error_code = ?
                WHERE id = ? AND status = 'SENDING' AND lease_owner = ?
                """, retryMilliseconds(claim.attemptNumber()), errorCode,
                claim.id(), workerId);
        } else {
            updated = jdbc.update("""
                UPDATE commitment_outbox
                SET status = 'DEAD_LETTER', dead_lettered_at = CURRENT_TIMESTAMP,
                    next_attempt_at = NULL,
                    lease_owner = NULL, lease_expires_at = NULL,
                    last_error_code = ?
                WHERE id = ? AND status = 'SENDING' AND lease_owner = ?
                """, errorCode, claim.id(), workerId);
        }
        if (updated == 0) {
            return;
        }
        jdbc.update("""
            INSERT INTO commitment_outbox_attempts
                (id, outbox_id, attempt_number, status,
                 provider_message_reference, error_code)
            VALUES (?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), claim.id(), claim.attemptNumber(),
            sent ? "SENT" : retry ? "FAILED" : "DEAD_LETTER",
            sent ? result.providerMessageId() : null, errorCode);
    }

    private long retryMilliseconds(int attemptNumber) {
        long multiplier = 1L << Math.min(10, Math.max(0, attemptNumber - 1));
        Duration retry = configuration.retryDelay().multipliedBy(multiplier);
        return Math.min(retry.toMillis(), Duration.ofHours(1).toMillis());
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "COMMITMENT_DELIVERY_FAILED";
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9_]", "_");
        return normalized.substring(0, Math.min(64, normalized.length()));
    }

    private record Claim(
        UUID id,
        UUID planVersionId,
        UUID baselineId,
        String idempotencyKey,
        String recipientSnapshot,
        String subject,
        String plainText,
        String htmlText,
        String archiveReference,
        int attemptNumber
    ) {
    }
}
