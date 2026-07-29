package com.vms.workflow.application;

import com.vms.workflow.api.DeliveryDtos.CommitmentDeadLetterView;
import com.vms.workflow.api.DeliveryDtos.CommitmentReplayRequest;
import com.vms.workflow.api.DeliveryDtos.CommitmentReplayView;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.security.DeliveryAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Bounded operator recovery for a provider-neutral commitment outbox.  The
 * original dead letter is never changed: a replay has its own immutable
 * command record and a new queued outbox row containing the frozen content.
 */
@Service
public class DeliveryCommitmentOperationsService {
    private static final int MAX_PAGE_SIZE = 100;

    private final JdbcTemplate jdbc;
    private final DeliveryAuthorizationService authorization;
    private final Clock clock;

    public DeliveryCommitmentOperationsService(
        JdbcTemplate jdbc,
        DeliveryAuthorizationService authorization,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.clock = clock;
    }

    public List<CommitmentDeadLetterView> deadLetters(
        String subject,
        UUID engagementId,
        int limit
    ) {
        authorization.requireEngagement(
            subject, engagementId, DeliveryAuthorizationService.COMMITMENT_REPLAY);
        int boundedLimit = Math.max(1, Math.min(MAX_PAGE_SIZE, limit));
        return jdbc.query("""
            SELECT outbox.id, plan.id, version.id, version.version,
                   outbox.message_type, outbox.attempt_count,
                   outbox.last_error_code, outbox.dead_lettered_at,
                   outbox.created_at,
                   (SELECT COUNT(*) FROM commitment_outbox_replays replay
                    WHERE replay.original_outbox_id = outbox.id)
            FROM commitment_outbox outbox
            JOIN delivery_plan_versions version ON version.id = outbox.plan_version_id
            JOIN delivery_plans plan ON plan.id = version.plan_id
            JOIN engagement_months month ON month.id = plan.engagement_month_id
            WHERE month.engagement_id = ?
              AND outbox.status = 'DEAD_LETTER'
            ORDER BY outbox.dead_lettered_at, outbox.id
            LIMIT ?
            """, (rs, rowNum) -> new CommitmentDeadLetterView(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getInt(4), rs.getString(5),
                rs.getInt(6), rs.getString(7),
                rs.getObject(8, OffsetDateTime.class),
                rs.getObject(9, OffsetDateTime.class), rs.getInt(10)),
            engagementId, boundedLimit);
    }

    @Transactional
    public CommitmentReplayView replay(
        String subject,
        UUID outboxId,
        String idempotencyKey,
        CommitmentReplayRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
            || idempotencyKey.length() > 160) {
            throw new IllegalArgumentException(
                "Idempotency-Key is required and must not exceed 160 characters.");
        }
        authorization.requireCommitmentOutbox(
            subject, outboxId, DeliveryAuthorizationService.COMMITMENT_REPLAY);
        String commandChecksum = checksum(request.reason());
        ExistingReplay existing = jdbc.query("""
            SELECT id, replay_outbox_id, command_checksum
            FROM commitment_outbox_replays
            WHERE original_outbox_id = ? AND idempotency_key = ?
            """, rs -> rs.next() ? new ExistingReplay(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3)) : null, outboxId, idempotencyKey);
        if (existing != null) {
            if (!existing.commandChecksum().equals(commandChecksum)) {
                throw new DomainConflictException(
                    "Idempotency-Key was already used for another commitment replay command.");
            }
            return replayView(existing.id(), outboxId, existing.replayOutboxId(), true);
        }
        OriginalOutbox original = jdbc.query("""
            SELECT outbox.plan_version_id, outbox.baseline_id, outbox.message_type,
                   outbox.recipient_snapshot::text, outbox.subject_text,
                   outbox.plain_text, outbox.html_text
            FROM commitment_outbox outbox
            WHERE outbox.id = ? AND outbox.status = 'DEAD_LETTER'
            FOR UPDATE
            """, rs -> rs.next() ? new OriginalOutbox(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7)) : null, outboxId);
        if (original == null) {
            throw new DomainConflictException(
                "Only a currently dead-lettered commitment can be replayed.");
        }
        UUID replayId = UUID.randomUUID();
        UUID replayOutboxId = UUID.randomUUID();
        OffsetDateTime recordedAt = OffsetDateTime.now(clock);
        jdbc.update("""
            INSERT INTO commitment_outbox
                (id, plan_version_id, baseline_id, message_type, idempotency_key,
                 recipient_snapshot, subject_text, plain_text, html_text,
                 archive_reference, status, next_attempt_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, 'PENDING', ?)
            """, replayOutboxId, original.planVersionId(), original.baselineId(),
            original.messageType(), "commitment-replay:" + replayId,
            original.recipientSnapshot(), original.subjectText(), original.plainText(),
            original.htmlText(), "db://commitment-outbox/" + replayOutboxId,
            recordedAt);
        jdbc.update("""
            INSERT INTO commitment_outbox_replays
                (id, original_outbox_id, replay_outbox_id, idempotency_key,
                 command_checksum, reason, actor_subject, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, replayId, outboxId, replayOutboxId, idempotencyKey,
            commandChecksum, request.reason(), subject, recordedAt);
        jdbc.update("""
            INSERT INTO delivery_audit_events
                (id, plan_id, plan_version_id, event_type, actor_subject, facts)
            SELECT ?, version.plan_id, version.id, 'COMMITMENT_REPLAY_QUEUED', ?,
                   jsonb_build_object(
                       'originalOutboxId', ?::text,
                       'replayOutboxId', ?::text,
                       'replayId', ?::text,
                       'commandChecksum', ?)
            FROM delivery_plan_versions version
            WHERE version.id = ?
            """, UUID.randomUUID(), subject, outboxId, replayOutboxId, replayId,
            commandChecksum, original.planVersionId());
        return replayView(replayId, outboxId, replayOutboxId, false);
    }

    private CommitmentReplayView replayView(
        UUID replayId,
        UUID originalOutboxId,
        UUID replayOutboxId,
        boolean replay
    ) {
        Integer replayNumber = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM commitment_outbox_replays
            WHERE original_outbox_id = ? AND created_at <= (
                SELECT created_at FROM commitment_outbox_replays WHERE id = ?
            )
            """, Integer.class, originalOutboxId, replayId);
        String status = jdbc.query("""
            SELECT status FROM commitment_outbox WHERE id = ?
            """, rs -> rs.next() ? rs.getString(1) : null, replayOutboxId);
        if (status == null || replayNumber == null) {
            throw new IllegalStateException("Commitment replay evidence is missing.");
        }
        return new CommitmentReplayView(
            replayId, originalOutboxId, replayOutboxId, status, replayNumber, replay);
    }

    private String checksum(String reason) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(
                    reason.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private record ExistingReplay(
        UUID id,
        UUID replayOutboxId,
        String commandChecksum
    ) {
    }

    private record OriginalOutbox(
        UUID planVersionId,
        UUID baselineId,
        String messageType,
        String recipientSnapshot,
        String subjectText,
        String plainText,
        String htmlText
    ) {
    }
}
