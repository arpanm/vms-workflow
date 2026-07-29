package com.vms.workflow.application;

import com.vms.workflow.api.CertificationDtos.NotificationReplayInput;
import com.vms.workflow.api.CertificationDtos.NotificationReplayView;
import com.vms.workflow.api.CertificationDtos.CertificationInboxItemView;
import com.vms.workflow.api.CertificationDtos.CertificationInboxView;
import com.vms.workflow.api.CertificationDtos.CertificationOperationItemView;
import com.vms.workflow.api.CertificationDtos.CertificationOperationsView;
import com.vms.workflow.api.CertificationDtos.CertificationPermissions;
import com.vms.workflow.api.CertificationDtos.CertificationQueueSummaryView;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.infrastructure.CorrelationIdFilter;
import com.vms.workflow.security.CertificationAuthorizationService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CertificationOperationsService {
    private final JdbcTemplate jdbc;
    private final CertificationAuthorizationService authorization;
    private final CanonicalEvidenceHasher hasher;
    private final ConfirmationTokenHandoffVault tokenHandoffs;
    private final CertificationEmailAdapter emailAdapter;
    private final Clock clock;

    public CertificationOperationsService(
        JdbcTemplate jdbc,
        CertificationAuthorizationService authorization,
        CanonicalEvidenceHasher hasher,
        ConfirmationTokenHandoffVault tokenHandoffs,
        CertificationEmailAdapter emailAdapter,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.authorization = authorization;
        this.hasher = hasher;
        this.tokenHandoffs = tokenHandoffs;
        this.emailAdapter = emailAdapter;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CertificationInboxView inbox(String subject, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        List<MonthCandidate> candidates = jdbc.query("""
            SELECT month.id, month.engagement_id, engagement.engagement_code,
                   engagement.name, month.month_start_date, month.state,
                   month.certification_version
            FROM engagement_months month
            JOIN engagements engagement ON engagement.id = month.engagement_id
            ORDER BY month.month_start_date DESC, engagement.engagement_code,
                     month.id
            LIMIT 300
            """, (rs, rowNum) -> new MonthCandidate(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                rs.getString("engagement_code"),
                rs.getString("name"),
                rs.getObject("month_start_date", LocalDate.class),
                rs.getString("state"),
                rs.getLong("certification_version")));

        List<CertificationInboxItemView> items = new ArrayList<>();
        for (MonthCandidate candidate : candidates) {
            if (items.size() >= limit) {
                break;
            }
            try {
                authorization.requireMonthRead(subject, candidate.monthId());
            } catch (EntityNotFoundException denied) {
                continue;
            }
            items.add(inboxItem(subject, candidate));
        }
        items.sort(Comparator
            .comparing(CertificationInboxItemView::overdue).reversed()
            .thenComparing(item -> "NO_ACTION".equals(item.nextAction()))
            .thenComparing(CertificationInboxItemView::monthStartDate,
                Comparator.reverseOrder())
            .thenComparing(CertificationInboxItemView::engagementCode));
        int actionRequired = (int) items.stream()
            .filter(item -> !"NO_ACTION".equals(item.nextAction())).count();
        int overdue = (int) items.stream()
            .filter(CertificationInboxItemView::overdue).count();
        return new CertificationInboxView(
            OffsetDateTime.now(clock), items.size(), actionRequired, overdue,
            List.copyOf(items));
    }

    @Transactional(readOnly = true)
    public CertificationOperationsView operations(
        String subject,
        int requestedLimit
    ) {
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        List<UUID> monthIds = authorizedOperationsMonths(subject);
        if (monthIds.isEmpty()) {
            return new CertificationOperationsView(
                OffsetDateTime.now(clock), emailAdapter.configurationStatus(),
                emptyQueueSummaries(), List.of());
        }
        UUID[] scope = monthIds.toArray(UUID[]::new);
        List<CertificationOperationItemView> items = new ArrayList<>();
        items.addAll(notificationWork(scope));
        items.addAll(scheduleWork(scope));
        items.addAll(handoffWork(scope));
        items.sort(Comparator
            .comparing(CertificationOperationItemView::dueAt,
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CertificationOperationItemView::queue)
            .thenComparing(CertificationOperationItemView::id));
        List<CertificationQueueSummaryView> summaries =
            queueSummaries(scope);
        if (items.size() > limit) {
            items = new ArrayList<>(items.subList(0, limit));
        }
        return new CertificationOperationsView(
            OffsetDateTime.now(clock), emailAdapter.configurationStatus(),
            summaries, List.copyOf(items));
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

    private CertificationInboxItemView inboxItem(
        String subject,
        MonthCandidate candidate
    ) {
        CertificationPermissions permissions =
            authorization.permissions(subject, candidate.monthId());
        InboxFacts facts = jdbc.query("""
            SELECT
                (SELECT submission.status
                 FROM delivery_submissions submission
                 WHERE submission.engagement_month_id = month.id
                   AND submission.status <> 'SUPERSEDED'
                 ORDER BY submission.version DESC LIMIT 1) AS submission_status,
                (SELECT COUNT(*)
                 FROM delivery_deliverable_versions deliverable
                 JOIN delivery_plan_versions version
                   ON version.id = deliverable.plan_version_id
                  AND version.state = 'FROZEN'
                 JOIN delivery_plans plan ON plan.id = version.plan_id
                 WHERE plan.engagement_month_id = month.id)
                    AS deliverable_count,
                (SELECT COUNT(*)
                 FROM deliverable_certifications certification
                 JOIN delivery_submissions submission
                   ON submission.id = certification.submission_id
                 WHERE submission.engagement_month_id = month.id
                   AND certification.decision <> 'MORE_INFORMATION_REQUIRED')
                    AS terminal_count,
                (SELECT COUNT(*)
                 FROM delivery_submissions submission
                 JOIN delivery_deliverable_versions deliverable
                   ON deliverable.plan_version_id = submission.plan_version_id
                 LEFT JOIN deliverable_certifications certification
                   ON certification.submission_id = submission.id
                  AND certification.deliverable_version_id = deliverable.id
                 WHERE submission.engagement_month_id = month.id
                   AND submission.status IN ('SUBMITTED', 'UNDER_REVIEW')
                   AND deliverable.product_owner_subject = ?
                   AND (
                       certification.id IS NULL
                       OR certification.decision = 'MORE_INFORMATION_REQUIRED'
                   )) AS assigned_review_count,
                (SELECT COUNT(*)
                 FROM (
                     SELECT inbound.id
                     FROM inbound_confirmation_messages inbound
                     WHERE inbound.engagement_month_id = month.id
                       AND inbound.status IN (
                           'RECORDED', 'MANUAL_REVIEW_REQUIRED'
                       )
                     UNION ALL
                     SELECT manual.id
                     FROM manual_confirmation_evidence manual
                     WHERE manual.engagement_month_id = month.id
                       AND manual.verification_status =
                           'PENDING_SECOND_REVIEW'
                 ) pending_review) AS pending_inbound_count,
                (SELECT request.status
                 FROM business_confirmation_requests request
                 WHERE request.engagement_month_id = month.id
                 ORDER BY request.version DESC LIMIT 1) AS confirmation_state,
                (SELECT request.due_at
                 FROM business_confirmation_requests request
                 WHERE request.engagement_month_id = month.id
                 ORDER BY request.version DESC LIMIT 1) AS confirmation_due_at,
                (SELECT run.status
                 FROM certification_readiness_runs run
                 WHERE run.engagement_month_id = month.id
                 ORDER BY run.evaluated_at DESC, run.id DESC LIMIT 1)
                    AS readiness_status
            FROM engagement_months month
            WHERE month.id = ?
            """, rs -> rs.next() ? new InboxFacts(
                rs.getString("submission_status"),
                rs.getInt("deliverable_count"),
                rs.getInt("terminal_count"),
                rs.getInt("assigned_review_count"),
                rs.getInt("pending_inbound_count"),
                rs.getString("confirmation_state"),
                rs.getObject("confirmation_due_at", OffsetDateTime.class),
                rs.getString("readiness_status")) : null,
            subject, candidate.monthId());
        if (facts == null) {
            throw notFound();
        }
        boolean overdue = facts.confirmationDueAt() != null
            && facts.confirmationDueAt().isBefore(OffsetDateTime.now(clock))
            && !Set.of("CONFIRMED", "CHANGES_REQUESTED", "REJECTED",
                "EXPIRED", "CANCELLED", "SUPERSEDED")
                .contains(facts.confirmationState());
        NextAction next = nextAction(permissions, facts, candidate.monthId());
        return new CertificationInboxItemView(
            candidate.monthId(), candidate.engagementId(),
            candidate.engagementCode(), candidate.engagementName(),
            candidate.monthStartDate(), candidate.monthStartDate().toString(),
            candidate.lifecycleState(), candidate.monthVersion(),
            facts.submissionStatus(), facts.deliverableCount(),
            facts.terminalDecisionCount(), facts.assignedReviewCount(),
            permissions.canReviewInbound() ? facts.pendingInboundReviewCount() : 0,
            facts.confirmationState(), facts.confirmationDueAt(),
            facts.readinessStatus(), overdue, next.label(), next.path());
    }

    private NextAction nextAction(
        CertificationPermissions permissions,
        InboxFacts facts,
        UUID monthId
    ) {
        String monthPath = monthId.toString();
        if (permissions.canReviewInbound()
            && facts.pendingInboundReviewCount() > 0) {
            return new NextAction(
                "REVIEW_CONFIRMATION_EVIDENCE",
                "/confirmation/" + monthPath + "#inbound-review");
        }
        if (permissions.canCertify() && facts.assignedReviewCount() > 0) {
            return new NextAction(
                "CERTIFY_ASSIGNED_DELIVERABLES",
                "/certification/" + monthPath + "/review");
        }
        if (permissions.canEditSubmission()
            && facts.deliverableCount() > 0
            && (facts.submissionStatus() == null
                || "DRAFT".equals(facts.submissionStatus()))) {
            return new NextAction(
                facts.submissionStatus() == null
                    ? "START_DELIVERY_SUBMISSION"
                    : "COMPLETE_DELIVERY_SUBMISSION",
                "/certification/" + monthPath);
        }
        if (permissions.canRequestConfirmation()
            && "READY_FOR_REQUEST".equals(facts.readinessStatus())
            && facts.confirmationState() == null) {
            return new NextAction(
                "CREATE_CONFIRMATION_REQUEST",
                "/confirmation/" + monthPath);
        }
        if (permissions.canConfirm()
            && "AWAITING_RESPONSE".equals(facts.confirmationState())) {
            return new NextAction(
                "RESPOND_TO_CONFIRMATION",
                "/confirmation/" + monthPath);
        }
        if (permissions.canGenerateSummary()
            && facts.deliverableCount() > 0
            && facts.terminalDecisionCount() >= facts.deliverableCount()) {
            return new NextAction(
                "GENERATE_CERTIFICATION_SUMMARY",
                "/certification/" + monthPath + "/review");
        }
        return new NextAction("NO_ACTION", "/certification/" + monthPath);
    }

    private List<UUID> authorizedOperationsMonths(String subject) {
        List<UUID> candidates = jdbc.queryForList("""
            SELECT id
            FROM engagement_months
            ORDER BY month_start_date DESC, id
            LIMIT 300
            """, UUID.class);
        List<UUID> result = new ArrayList<>();
        for (UUID monthId : candidates) {
            try {
                authorization.requireClientOrProcurement(
                    subject, monthId,
                    CertificationAuthorizationService.OUTBOX_REPLAY);
                result.add(monthId);
            } catch (EntityNotFoundException denied) {
                // Scope filtering intentionally uses safe absence semantics.
            }
        }
        return result;
    }

    private List<CertificationOperationItemView> notificationWork(UUID[] scope) {
        return jdbc.query("""
            SELECT outbox.id, outbox.engagement_month_id,
                   month.certification_version,
                   month.month_start_date, engagement.engagement_code,
                   outbox.event_type, outbox.transport_status,
                   outbox.attempt_count,
                   COALESCE(outbox.next_attempt_at, outbox.created_at) AS due_at,
                   attempt.last_attempt_at, outbox.last_error_code,
                   outbox.correlation_id,
                   outbox.transport_status IN (
                       'FAILED', 'DEAD_LETTER', 'BOUNCED'
                   ) AND outbox.provider_status = 'CONFIGURED'
                       AS replay_allowed
            FROM notification_outbox outbox
            JOIN engagement_months month
              ON month.id = outbox.engagement_month_id
            JOIN engagements engagement ON engagement.id = month.engagement_id
            LEFT JOIN LATERAL (
                SELECT MAX(delivery.attempted_at) AS last_attempt_at
                FROM notification_delivery_attempts delivery
                WHERE delivery.outbox_id = outbox.id
            ) attempt ON TRUE
            WHERE outbox.engagement_month_id = ANY (?::uuid[])
              AND outbox.transport_status IN (
                  'QUEUED', 'SENDING', 'FAILED', 'BOUNCED', 'DEAD_LETTER'
              )
            """, (rs, rowNum) -> new CertificationOperationItemView(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getLong("certification_version"),
                rs.getObject("month_start_date", LocalDate.class).toString(),
                rs.getString("engagement_code"), "NOTIFICATION",
                rs.getString("event_type"), rs.getString("transport_status"),
                rs.getInt("attempt_count"),
                rs.getObject("due_at", OffsetDateTime.class),
                rs.getObject("last_attempt_at", OffsetDateTime.class),
                rs.getString("last_error_code"),
                rs.getObject("correlation_id", UUID.class),
                rs.getBoolean("replay_allowed")), (Object) scope);
    }

    private List<CertificationOperationItemView> scheduleWork(UUID[] scope) {
        return jdbc.query("""
            SELECT schedule.id, request.engagement_month_id,
                   month.certification_version,
                   month.month_start_date, engagement.engagement_code,
                   schedule.schedule_type, schedule.sequence_number,
                   schedule.status, schedule.attempt_count,
                   COALESCE(schedule.next_attempt_at, schedule.due_at) AS due_at,
                   schedule.completed_at, schedule.last_error_code,
                   request.id AS correlation_id
            FROM confirmation_request_schedules schedule
            JOIN business_confirmation_requests request
              ON request.id = schedule.request_id
            JOIN engagement_months month
              ON month.id = request.engagement_month_id
            JOIN engagements engagement ON engagement.id = month.engagement_id
            WHERE request.engagement_month_id = ANY (?::uuid[])
              AND schedule.status IN (
                  'PENDING', 'CLAIMED', 'DEAD_LETTER'
              )
            """, (rs, rowNum) -> new CertificationOperationItemView(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getLong("certification_version"),
                rs.getObject("month_start_date", LocalDate.class).toString(),
                rs.getString("engagement_code"), "SCHEDULE",
                rs.getString("schedule_type") + "_"
                    + rs.getInt("sequence_number"),
                rs.getString("status"), rs.getInt("attempt_count"),
                rs.getObject("due_at", OffsetDateTime.class),
                rs.getObject("completed_at", OffsetDateTime.class),
                rs.getString("last_error_code"),
                rs.getObject("correlation_id", UUID.class), false),
            (Object) scope);
    }

    private List<CertificationOperationItemView> handoffWork(UUID[] scope) {
        return jdbc.query("""
            SELECT job.id, handoff.engagement_month_id,
                   month.certification_version,
                   month.month_start_date, engagement.engagement_code,
                   job.status, job.attempt_count,
                   COALESCE(job.next_attempt_at, job.created_at) AS due_at,
                   attempt.last_attempt_at, job.last_error_code,
                   handoff.correlation_id
            FROM f05_handoff_publish_jobs job
            JOIN f05_certification_handoffs handoff
              ON handoff.id = job.handoff_id
            JOIN engagement_months month
              ON month.id = handoff.engagement_month_id
            JOIN engagements engagement ON engagement.id = month.engagement_id
            LEFT JOIN LATERAL (
                SELECT MAX(value.attempted_at) AS last_attempt_at
                FROM f05_handoff_publish_attempts value
                WHERE value.handoff_id = handoff.id
            ) attempt ON TRUE
            WHERE handoff.engagement_month_id = ANY (?::uuid[])
              AND job.status IN (
                  'PENDING', 'CLAIMED', 'DEAD_LETTER', 'CANCELLED'
              )
            """, (rs, rowNum) -> new CertificationOperationItemView(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getLong("certification_version"),
                rs.getObject("month_start_date", LocalDate.class).toString(),
                rs.getString("engagement_code"), "F05_HANDOFF",
                "CERTIFICATION_READINESS", rs.getString("status"),
                rs.getInt("attempt_count"),
                rs.getObject("due_at", OffsetDateTime.class),
                rs.getObject("last_attempt_at", OffsetDateTime.class),
                rs.getString("last_error_code"),
                rs.getObject("correlation_id", UUID.class), false),
            (Object) scope);
    }

    private List<CertificationQueueSummaryView> queueSummaries(UUID[] scope) {
        CertificationQueueSummaryView notifications = jdbc.query("""
            SELECT
                COUNT(*) FILTER (
                    WHERE transport_status = 'QUEUED') AS pending,
                COUNT(*) FILTER (
                    WHERE transport_status = 'SENDING') AS claimed,
                COUNT(*) FILTER (
                    WHERE transport_status IN ('FAILED', 'BOUNCED')) AS failed,
                COUNT(*) FILTER (
                    WHERE transport_status = 'DEAD_LETTER') AS dead_letter,
                COUNT(*) FILTER (
                    WHERE transport_status IN (
                        'SENT', 'DELIVERED')) AS completed,
                MIN(COALESCE(next_attempt_at, created_at)) FILTER (
                    WHERE transport_status IN (
                        'QUEUED', 'SENDING', 'FAILED',
                        'BOUNCED', 'DEAD_LETTER')) AS oldest_actionable
            FROM notification_outbox
            WHERE engagement_month_id = ANY (?::uuid[])
            """, rs -> rs.next() ? new CertificationQueueSummaryView(
                "NOTIFICATION", rs.getInt("pending"), rs.getInt("claimed"),
                rs.getInt("failed"), rs.getInt("dead_letter"),
                rs.getInt("completed"), 0,
                rs.getObject("oldest_actionable", OffsetDateTime.class)) : null,
            (Object) scope);
        CertificationQueueSummaryView schedules = jdbc.query("""
            SELECT
                COUNT(*) FILTER (WHERE schedule.status = 'PENDING') AS pending,
                COUNT(*) FILTER (WHERE schedule.status = 'CLAIMED') AS claimed,
                0 AS failed,
                COUNT(*) FILTER (
                    WHERE schedule.status = 'DEAD_LETTER') AS dead_letter,
                COUNT(*) FILTER (
                    WHERE schedule.status = 'COMPLETED') AS completed,
                COUNT(*) FILTER (
                    WHERE schedule.status = 'CANCELLED') AS cancelled,
                MIN(COALESCE(
                    schedule.next_attempt_at, schedule.due_at)) FILTER (
                    WHERE schedule.status IN (
                        'PENDING', 'CLAIMED', 'DEAD_LETTER'))
                    AS oldest_actionable
            FROM confirmation_request_schedules schedule
            JOIN business_confirmation_requests request
              ON request.id = schedule.request_id
            WHERE request.engagement_month_id = ANY (?::uuid[])
            """, rs -> rs.next() ? new CertificationQueueSummaryView(
                "SCHEDULE", rs.getInt("pending"), rs.getInt("claimed"),
                rs.getInt("failed"), rs.getInt("dead_letter"),
                rs.getInt("completed"), rs.getInt("cancelled"),
                rs.getObject("oldest_actionable", OffsetDateTime.class)) : null,
            (Object) scope);
        CertificationQueueSummaryView handoffs = jdbc.query("""
            SELECT
                COUNT(*) FILTER (WHERE job.status = 'PENDING') AS pending,
                COUNT(*) FILTER (WHERE job.status = 'CLAIMED') AS claimed,
                0 AS failed,
                COUNT(*) FILTER (
                    WHERE job.status = 'DEAD_LETTER') AS dead_letter,
                COUNT(*) FILTER (
                    WHERE job.status = 'COMPLETED') AS completed,
                COUNT(*) FILTER (
                    WHERE job.status = 'CANCELLED') AS cancelled,
                MIN(COALESCE(job.next_attempt_at, job.created_at)) FILTER (
                    WHERE job.status IN (
                        'PENDING', 'CLAIMED', 'DEAD_LETTER'))
                    AS oldest_actionable
            FROM f05_handoff_publish_jobs job
            JOIN f05_certification_handoffs handoff
              ON handoff.id = job.handoff_id
            WHERE handoff.engagement_month_id = ANY (?::uuid[])
            """, rs -> rs.next() ? new CertificationQueueSummaryView(
                "F05_HANDOFF", rs.getInt("pending"), rs.getInt("claimed"),
                rs.getInt("failed"), rs.getInt("dead_letter"),
                rs.getInt("completed"), rs.getInt("cancelled"),
                rs.getObject("oldest_actionable", OffsetDateTime.class)) : null,
            (Object) scope);
        return List.of(notifications, schedules, handoffs);
    }

    private List<CertificationQueueSummaryView> emptyQueueSummaries() {
        return List.of(
            new CertificationQueueSummaryView(
                "NOTIFICATION", 0, 0, 0, 0, 0, 0, null),
            new CertificationQueueSummaryView(
                "SCHEDULE", 0, 0, 0, 0, 0, 0, null),
            new CertificationQueueSummaryView(
                "F05_HANDOFF", 0, 0, 0, 0, 0, 0, null));
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

    private record MonthCandidate(
        UUID monthId,
        UUID engagementId,
        String engagementCode,
        String engagementName,
        LocalDate monthStartDate,
        String lifecycleState,
        long monthVersion
    ) {
    }

    private record InboxFacts(
        String submissionStatus,
        int deliverableCount,
        int terminalDecisionCount,
        int assignedReviewCount,
        int pendingInboundReviewCount,
        String confirmationState,
        OffsetDateTime confirmationDueAt,
        String readinessStatus
    ) {
    }

    private record NextAction(String label, String path) {
    }
}
