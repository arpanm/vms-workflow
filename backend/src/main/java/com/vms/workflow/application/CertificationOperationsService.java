package com.vms.workflow.application;

import com.vms.workflow.api.CertificationDtos.NotificationReplayInput;
import com.vms.workflow.api.CertificationDtos.NotificationReplayView;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.infrastructure.CorrelationIdFilter;
import com.vms.workflow.security.CertificationAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CertificationOperationsService {
    private final JdbcTemplate jdbc;
    private final CertificationAuthorizationService authorization;
    private final CanonicalEvidenceHasher hasher;
    private final ConfirmationTokenHandoffVault tokenHandoffs;

    public CertificationOperationsService(
        JdbcTemplate jdbc,
        CertificationAuthorizationService authorization,
        CanonicalEvidenceHasher hasher,
        ConfirmationTokenHandoffVault tokenHandoffs
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.hasher = hasher;
        this.tokenHandoffs = tokenHandoffs;
    }

    @Transactional
    public NotificationReplayView replayNotification(
        String subject,
        UUID notificationId,
        NotificationReplayInput input,
        String ifMatch,
        String idempotencyKey
    ) {
        OutboxRow initial = outbox(notificationId, false);
        authorization.requireClientOrProcurement(
            subject, initial.monthId(),
            CertificationAuthorizationService.OUTBOX_REPLAY);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        if (expected != input.expectedMonthVersion()) {
            throw new IllegalArgumentException(
                "If-Match and expectedMonthVersion must match.");
        }
        String requestHash = hasher.hash(Map.of(
            "schema", "f04-notification-replay-v1",
            "reason", input.reason())).checksum();
        UUID prior = priorResult(
            subject, notificationId, idempotencyKey, requestHash);
        if (prior != null) {
            return replayView(prior);
        }

        Long monthVersion = jdbc.queryForObject("""
            SELECT certification_version
            FROM engagement_months
            WHERE id = ?
            FOR UPDATE
            """, Long.class, initial.monthId());
        if (monthVersion == null) {
            throw notFound();
        }
        if (monthVersion != expected) {
            throw new DomainConflictException(
                "MONTH_VERSION_CONFLICT",
                "The engagement month version is stale.", monthVersion);
        }
        OutboxRow outbox = outbox(notificationId, true);
        if (!Set.of("FAILED", "DEAD_LETTER", "BOUNCED")
            .contains(outbox.transportStatus())) {
            throw new DomainConflictException(
                "NOTIFICATION_NOT_REPLAYABLE",
                "Only failed, bounced or dead-letter notification work can be replayed.");
        }
        if (!"CONFIGURED".equals(outbox.providerStatus())) {
            throw new DomainConflictException(
                "EMAIL_PROVIDER_NOT_CONFIGURED",
                "Notification replay requires a configured provider adapter.");
        }
        if (outbox.secureHandoff()
            && !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM confirmation_token_handoffs handoff
                    JOIN confirmation_secure_tokens token
                      ON token.id = handoff.token_id
                    JOIN business_confirmation_requests request
                      ON request.id = handoff.request_id
                    WHERE handoff.outbox_id = ?
                      AND handoff.status = 'FAILED'
                      AND token.consumed_at IS NULL
                      AND token.expires_at > CURRENT_TIMESTAMP
                      AND request.status = 'AWAITING_RESPONSE'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM confirmation_token_revocations revocation
                          WHERE revocation.token_id = token.id
                      )
                )
                """, Boolean.class, notificationId))) {
            throw new DomainConflictException(
                "SECURE_TOKEN_NOT_REPLAYABLE",
                "The secure action token is no longer active.");
        }

        UUID replayId = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        int replayNumber = outbox.replayCount() + 1;
        jdbc.update("""
            INSERT INTO notification_outbox_replays
                (id, outbox_id, replay_number, reason,
                 replayed_by_subject, correlation_id)
            VALUES (?, ?, ?, ?, ?, ?)
            """, replayId, notificationId, replayNumber,
            input.reason(), subject, correlationId);
        jdbc.update("""
            UPDATE notification_outbox
            SET transport_status = 'QUEUED',
                next_attempt_at = CURRENT_TIMESTAMP,
                dead_lettered_at = NULL,
                last_error_code = NULL,
                lease_owner = NULL,
                lease_expires_at = NULL,
                replay_count = replay_count + 1,
                generation_attempt_count = 0
            WHERE id = ?
            """, notificationId);
        if (outbox.secureHandoff()) {
            tokenHandoffs.requeue(notificationId);
        }
        jdbc.update("""
            INSERT INTO certification_audit_events
                (id, engagement_month_id, event_type, actor_subject,
                 authority_snapshot, object_type, object_id, object_version,
                 source, reason, result, correlation_id)
            VALUES (?, ?, 'NOTIFICATION_REPLAY_QUEUED', ?,
                    ?::jsonb, 'notification_outbox', ?, ?,
                    'IN_APP', ?, 'QUEUED', ?)
            """, UUID.randomUUID(), outbox.monthId(), subject,
            "{\"permission\":\"certification.outbox.replay\","
                + "\"resolvedServerSide\":true}",
            notificationId, replayNumber, input.reason(), correlationId);
        jdbc.update("""
            INSERT INTO certification_domain_events
                (id, engagement_month_id, event_type, actor_type,
                 actor_subject, subject_type, subject_id, subject_version,
                 correlation_id, payload)
            VALUES (?, ?, 'notification.replay.queued.v1', 'USER', ?,
                    'notification_outbox', ?, ?, ?, ?::jsonb)
            """, UUID.randomUUID(), outbox.monthId(), subject,
            notificationId, replayNumber, correlationId,
            hasher.hash(Map.of(
                "replayNumber", replayNumber,
                "previousStatus", outbox.transportStatus()))
                .canonicalJson());
        jdbc.update("""
            INSERT INTO certification_idempotency_keys
                (id, actor_subject, operation, scope_id, idempotency_key,
                 request_hash, result_type, result_id)
            VALUES (?, ?, 'REPLAY_NOTIFICATION', ?, ?, ?,
                    'notification_outbox_replay', ?)
            """, UUID.randomUUID(), subject, notificationId,
            idempotencyKey, requestHash, replayId);
        jdbc.update("""
            UPDATE engagement_months
            SET certification_version = certification_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """, outbox.monthId());
        return replayView(replayId);
    }

    private OutboxRow outbox(UUID id, boolean lock) {
        OutboxRow row = jdbc.query("""
            SELECT id, engagement_month_id, provider_status,
                   transport_status, attempt_count, replay_count,
                   business_object_type = 'confirmation_secure_token'
                       AS secure_handoff
            FROM notification_outbox
            WHERE id = ?
            """ + (lock ? " FOR UPDATE" : ""),
            rs -> rs.next() ? new OutboxRow(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getString("provider_status"),
                rs.getString("transport_status"),
                rs.getInt("attempt_count"),
                rs.getInt("replay_count"),
                rs.getBoolean("secure_handoff")) : null,
            id);
        if (row == null) {
            throw notFound();
        }
        return row;
    }

    private NotificationReplayView replayView(UUID id) {
        NotificationReplayView view = jdbc.query("""
            SELECT replay.id, replay.outbox_id,
                   outbox.engagement_month_id,
                   outbox.transport_status, replay.replay_number,
                   outbox.attempt_count, replay.replayed_at,
                   replay.correlation_id
            FROM notification_outbox_replays replay
            JOIN notification_outbox outbox ON outbox.id = replay.outbox_id
            WHERE replay.id = ?
            """, rs -> rs.next() ? new NotificationReplayView(
                rs.getObject("id", UUID.class),
                rs.getObject("outbox_id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getString("transport_status"),
                rs.getInt("replay_number"),
                rs.getInt("attempt_count"),
                rs.getObject("replayed_at", OffsetDateTime.class),
                rs.getObject("correlation_id", UUID.class)) : null,
            id);
        if (view == null) {
            throw notFound();
        }
        return view;
    }

    private UUID priorResult(
        String actor,
        UUID scopeId,
        String key,
        String requestHash
    ) {
        return jdbc.query("""
            SELECT request_hash, result_id
            FROM certification_idempotency_keys
            WHERE actor_subject = ?
              AND operation = 'REPLAY_NOTIFICATION'
              AND scope_id = ? AND idempotency_key = ?
            """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                if (!requestHash.equals(rs.getString("request_hash"))) {
                    throw new DomainConflictException(
                        "IDEMPOTENCY_KEY_REUSED",
                        "The idempotency key was already used with different input.");
                }
                return rs.getObject("result_id", UUID.class);
            }, actor, scopeId, key);
    }

    private long expectedVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("If-Match is required.");
        }
        String normalized = ifMatch.strip();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2);
        }
        if (normalized.length() >= 2
            && normalized.startsWith("\"")
            && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "If-Match must contain a numeric version.", exception);
        }
    }

    private void requireIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() > 160) {
            throw new IllegalArgumentException(
                "A valid Idempotency-Key is required.");
        }
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found.");
    }

    private record OutboxRow(
        UUID id,
        UUID monthId,
        String providerStatus,
        String transportStatus,
        int attemptCount,
        int replayCount,
        boolean secureHandoff
    ) {
    }
}
