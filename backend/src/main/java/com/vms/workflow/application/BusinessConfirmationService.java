package com.vms.workflow.application;

import com.vms.workflow.api.CertificationDtos.CertificationPermissions;
import com.vms.workflow.api.CertificationDtos.ConfirmationActionRequest;
import com.vms.workflow.api.CertificationDtos.ConfirmationActionView;
import com.vms.workflow.api.CertificationDtos.ConfirmationHistoryItem;
import com.vms.workflow.api.CertificationDtos.ConfirmationProjectChoice;
import com.vms.workflow.api.CertificationDtos.ConfirmationRecipient;
import com.vms.workflow.api.CertificationDtos.ConfirmationRequestInput;
import com.vms.workflow.api.CertificationDtos.ConfirmationRequestView;
import com.vms.workflow.api.CertificationDtos.ConfirmationScopeSource;
import com.vms.workflow.api.CertificationDtos.GovernanceDecisionInput;
import com.vms.workflow.api.CertificationDtos.GovernanceDecisionView;
import com.vms.workflow.api.CertificationDtos.ReadinessView;
import com.vms.workflow.api.CertificationDtos.VersionDiffItem;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.application.CanonicalEvidenceHasher.HashResult;
import com.vms.workflow.application.ConfirmationTokenCodec.IssuedToken;
import com.vms.workflow.infrastructure.CorrelationIdFilter;
import com.vms.workflow.security.CertificationAuthorizationService;
import com.vms.workflow.security.CertificationAuthorizationService.Party;
import com.vms.workflow.security.CertificationAuthorizationService.Scope;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class BusinessConfirmationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CertificationAuthorizationService authorization;
    private final CertificationWorkflowService workflow;
    private final CertificationReadinessService readiness;
    private final CanonicalEvidenceHasher hasher;
    private final ConfirmationTokenCodec tokenCodec;
    private final ConfirmationTokenHandoffVault tokenHandoffs;
    private final CertificationSecurityEventService securityEvents;
    private final CertificationConfiguration configuration;
    private final CertificationEmailAdapter emailAdapter;
    private final F05CertificationReadinessPublisher f05Publisher;
    private final Clock clock;

    public BusinessConfirmationService(
        JdbcTemplate jdbc,
        ObjectMapper objectMapper,
        CertificationAuthorizationService authorization,
        CertificationWorkflowService workflow,
        CertificationReadinessService readiness,
        CanonicalEvidenceHasher hasher,
        ConfirmationTokenCodec tokenCodec,
        ConfirmationTokenHandoffVault tokenHandoffs,
        CertificationSecurityEventService securityEvents,
        CertificationConfiguration configuration,
        CertificationEmailAdapter emailAdapter,
        F05CertificationReadinessPublisher f05Publisher,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
        this.workflow = workflow;
        this.readiness = readiness;
        this.hasher = hasher;
        this.tokenCodec = tokenCodec;
        this.tokenHandoffs = tokenHandoffs;
        this.securityEvents = securityEvents;
        this.configuration = configuration;
        this.emailAdapter = emailAdapter;
        this.f05Publisher = f05Publisher;
        this.clock = clock;
    }

    @Transactional
    public ConfirmationRequestView createRequest(
        String subject,
        UUID monthId,
        ConfirmationRequestInput input,
        String ifMatch,
        String idempotencyKey
    ) {
        authorization.requireMonthParty(subject, monthId,
            CertificationAuthorizationService.CONFIRMATION_REQUEST, Party.CLIENT);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, input.expectedMonthVersion());
        String requestHash = requestHash(input);
        UUID prior = priorResult(subject, "CREATE_CONFIRMATION_REQUEST",
            monthId, idempotencyKey, requestHash);
        if (prior != null) {
            return requestView(subject, prior);
        }
        ConfirmationSources sources = lockSources(monthId);
        if (sources.monthVersion() != expected) {
            throw conflict("MONTH_VERSION_CONFLICT",
                "The engagement month version is stale.", sources.monthVersion());
        }
        ReadinessView readinessView = readiness.evaluateAuthorized(subject, monthId);
        if (!readiness.readyForConfirmationRequest(readinessView)) {
            throw new DomainConflictException(
                "CONFIRMATION_READINESS_BLOCKED",
                "Confirmation readiness blockers: "
                    + readinessView.blockers().stream()
                        .map(value -> value.code()).distinct().sorted().toList(),
                sources.monthVersion());
        }
        PolicyRow policy = policy(sources.policyVersionId());
        OffsetDateTime requestedAt = OffsetDateTime.now(clock);
        if (input.dueAt().isBefore(requestedAt.plusMinutes(5))) {
            throw new IllegalArgumentException(
                "Confirmation due time must allow at least five minutes.");
        }
        if (input.dueAt().isAfter(
            requestedAt.plusSeconds(policy.dueSeconds()).plusSeconds(1))) {
            throw new IllegalArgumentException(
                "Confirmation due time exceeds the captured policy window.");
        }
        ConfirmationRequestRow current = currentRequest(monthId, true);
        if (current != null && !Set.of(
                "CONFIRMED", "CHANGES_REQUESTED", "REJECTED")
            .contains(current.status())) {
            throw conflict("CONFIRMATION_REQUEST_ALREADY_ACTIVE",
                "The month already has an active confirmation request.",
                current.version());
        }
        List<EligibleConfirmer> eligible = resolveEligibleConfirmers(sources);
        if (eligible.isEmpty()) {
            throw new DomainConflictException(
                "ACTIVE_ELIGIBLE_CONFIRMER_REQUIRED",
                "No active scoped product owner can be snapshotted as confirmer.");
        }
        Map<String, Object> recipients =
            workflow.recipientSnapshot(sources.planVersionId());
        int quorumRequired = requiredQuorum(policy, eligible);
        int version = nextRequestVersion(monthId);
        if (current != null) {
            jdbc.update("""
                UPDATE business_confirmation_requests
                SET status = 'SUPERSEDED',
                    optimistic_version = optimistic_version + 1
                WHERE id = ?
                """, current.id());
        }
        Map<String, Object> eligibilityManifest =
            eligibilityManifest(eligible, policy, quorumRequired);
        Map<String, Object> scope = scopeManifest(
            sources, version, recipients, eligibilityManifest, input.dueAt());
        HashResult scopeHash = hasher.hash(scope);
        UUID requestId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO business_confirmation_requests
                (id, engagement_month_id, attendance_snapshot_id,
                 plan_version_id, baseline_id, certification_summary_id,
                 policy_version_id, version, status, transport_status,
                 supersedes_id, quorum_mode, quorum_required,
                 recipient_snapshot, eligibility_snapshot, scope_manifest,
                 scope_checksum, requested_at, due_at, due_offset_seconds,
                 created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?,
                    ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)
            """, requestId, monthId, sources.attendanceSnapshotId(),
            sources.planVersionId(), sources.baselineId(), sources.summaryId(),
            sources.policyVersionId(), version,
            providerTransportStatus(), current == null ? null : current.id(),
            policy.quorumMode(), quorumRequired, json(recipients),
            json(eligibilityManifest), scopeHash.canonicalJson(),
            scopeHash.checksum(), requestedAt, input.dueAt(),
            input.dueAt().getOffset().getTotalSeconds(), subject);
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        CertificationWorkflowService.MonthRow month = workflow.month(monthId);
        int sequence = 1;
        for (EligibleConfirmer confirmer : eligible) {
            UUID eligibilityId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO confirmation_eligibility_snapshots
                    (id, engagement_month_id, policy_version_id,
                     eligible_confirmer_subject, verified_email, project_id,
                     sequence_number, authority_snapshot)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, eligibilityId, monthId, sources.policyVersionId(),
                confirmer.subject(), confirmer.email(), confirmer.projectId(),
                sequence, json(confirmer.authority()));
            jdbc.update("""
                INSERT INTO confirmation_request_eligibility
                    (request_id, eligibility_id, eligible_confirmer_subject,
                     project_id, sequence_number)
                VALUES (?, ?, ?, ?, ?)
                """, requestId, eligibilityId, confirmer.subject(),
                confirmer.projectId(), sequence);
            IssuedToken token = tokenCodec.issue();
            UUID tokenId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO confirmation_secure_tokens
                    (id, request_id, request_version,
                     eligible_confirmer_subject, project_id, token_hash, token_salt,
                     hash_algorithm, work_factor, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, tokenId, requestId, version, confirmer.subject(),
                confirmer.projectId(),
                token.encodedHash(), token.encodedSalt(), token.algorithm(),
                token.workFactor(),
                earlier(input.dueAt(),
                    requestedAt.plusSeconds(policy.tokenTtlSeconds())));
            if ("CONFIGURED".equals(emailAdapter.configurationStatus())) {
                UUID secureOutboxId = workflow.enqueueSecureTokenNotification(
                    month, tokenId, version, confirmer.email(),
                    scopeHash.checksum(), correlationId);
                tokenHandoffs.store(
                    tokenId, requestId, secureOutboxId, token.plaintext());
            }
            sequence++;
        }
        scheduleRequest(requestId, requestedAt, input.dueAt());
        jdbc.update("""
            UPDATE business_confirmation_requests
            SET status = 'QUEUED', optimistic_version = optimistic_version + 1
            WHERE id = ?
            """, requestId);
        jdbc.update("""
            UPDATE business_confirmation_requests
            SET status = 'AWAITING_RESPONSE',
                optimistic_version = optimistic_version + 1
            WHERE id = ?
            """, requestId);
        workflow.enqueueNotification(month, "CONFIRMATION_REQUESTED",
            "business_confirmation_request", requestId, version,
            "confirmation-request-v1", "Monthly business confirmation requested",
            scopeHash.checksum(), correlationId);
        audit(monthId, "CONFIRMATION_REQUESTED", subject,
            "business_confirmation_request", requestId, version,
            null, "SUCCESS", sources.policyVersionId(), correlationId);
        event(monthId, "confirmation.requested.v1", subject,
            "business_confirmation_request", requestId, version, correlationId,
            Map.of("scopeChecksum", scopeHash.checksum(),
                "transportStatus", providerTransportStatus(),
                "secureTokenPlaintextPersisted", false,
                "secureTokenHandoffEncrypted",
                "CONFIGURED".equals(emailAdapter.configurationStatus())));
        recordIdempotency(subject, "CREATE_CONFIRMATION_REQUEST", monthId,
            idempotencyKey, requestHash, requestId);
        bumpMonth(monthId, "CONFIRMATION_PENDING");
        return requestView(subject, requestId);
    }

    @Transactional(readOnly = true)
    public ConfirmationRequestView request(String subject, UUID requestId) {
        authorization.requireRequestRead(subject, requestId);
        return requestView(subject, requestId);
    }

    @Transactional(noRollbackFor = ConfirmationExpiredException.class)
    public ConfirmationRequestView act(
        String subject,
        UUID requestId,
        ConfirmationActionRequest input,
        String ifMatch,
        String idempotencyKey
    ) {
        authorization.requireConfirmationAction(
            subject, requestId, input.projectId());
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, input.expectedRequestVersion());
        String requestHash = requestHash(input);
        UUID prior = priorResult(subject, "CONFIRMATION_ACTION", requestId,
            idempotencyKey, requestHash);
        if (prior != null) {
            return requestView(subject, requestId);
        }
        ConfirmationRequestRow request = lockRequest(requestId);
        if (request.version() != expected) {
            throw conflict("CONFIRMATION_VERSION_CONFLICT",
                "The confirmation request version is stale.", request.version());
        }
        if (!"AWAITING_RESPONSE".equals(request.status())) {
            throw conflict("CONFIRMATION_NOT_AWAITING_RESPONSE",
                "The confirmation request no longer accepts actions.",
                request.version());
        }
        OffsetDateTime evaluatedAt = OffsetDateTime.now(clock);
        if (!request.dueAt().isAfter(evaluatedAt)) {
            securityEvents.recordBestEffort(
                request.monthId(), "CONFIRMATION_ACTION_AFTER_DUE",
                subject, "BUSINESS_CONFIRMATION_REQUEST", requestId,
                "DENIED", "REQUEST_DUE_AT_ELAPSED",
                Map.of("requestVersion", request.version()));
            expireRequest(request, CorrelationIdFilter.currentOrNew());
            throw new ConfirmationExpiredException(request.version());
        }
        if (!"CONFIRM".equals(input.decision())
            && (input.comment() == null || input.comment().isBlank())) {
            throw new IllegalArgumentException(
                "Correction and rejection require a comment.");
        }
        EligibleAction eligibility = eligibility(
            requestId, subject, input.projectId());
        String source = input.secureToken() == null
            || input.secureToken().isBlank() ? "IN_APP" : "SECURE_EMAIL_LINK";
        TokenRow token = null;
        if ("SECURE_EMAIL_LINK".equals(source)) {
            token = tokenForUpdate(
                requestId, subject, eligibility.projectId());
            if (token == null || token.consumedAt() != null
                || !token.expiresAt().isAfter(evaluatedAt)
                || token.requestVersion() != request.version()
                || !tokenCodec.matches(input.secureToken(), token.hash(),
                    token.salt(), token.workFactor())) {
                securityEvents.recordBestEffort(
                    request.monthId(), "SECURE_CONFIRMATION_TOKEN_REJECTED",
                    subject, "BUSINESS_CONFIRMATION_REQUEST", requestId,
                    "DENIED", "TOKEN_VALIDATION_FAILED",
                    Map.of("requestVersion", request.version()));
                throw new EntityNotFoundException("Resource not found.");
            }
        }
        if ("ORDERED".equals(request.quorumMode())) {
            int priorConfirmed = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM business_confirmation_actions
                WHERE request_id = ? AND action = 'CONFIRM'
                """, Integer.class, requestId);
            if (eligibility.sequence() != priorConfirmed + 1) {
                throw new DomainConflictException(
                    "CONFIRMATION_OUT_OF_ORDER",
                    "The configured ordered confirmer sequence is not satisfied.");
            }
        }
        UUID actionId = UUID.randomUUID();
        Map<String, Object> actionManifest = new LinkedHashMap<>();
        actionManifest.put("schema", "confirmation-action-v1");
        actionManifest.put("requestId", requestId.toString());
        actionManifest.put("requestVersion", request.version());
        actionManifest.put("scopeChecksum", request.scopeChecksum());
        actionManifest.put("actorSubject", subject);
        actionManifest.put("decision", input.decision());
        actionManifest.put("comment", input.comment());
        actionManifest.put("projectId", string(eligibility.projectId()));
        actionManifest.put("source", source);
        HashResult actionHash = hasher.hash(actionManifest);
        String authoritySnapshot = json(Map.of(
            "eligibleSnapshotId", eligibility.eligibilityId().toString(),
            "roleReason", eligibility.roleReason(),
            "projectId", eligibility.projectId() == null
                ? "" : eligibility.projectId().toString(),
            "resolvedServerSide", true));
        jdbc.update("""
            INSERT INTO business_confirmation_actions
                (id, request_id, request_version, token_id, actor_subject,
                 actor_authority_snapshot, project_id, action, comment,
                 source, verification_status, action_hash, idempotency_key)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, 'VERIFIED', ?, ?)
            """, actionId, requestId, request.version(),
            token == null ? null : token.id(), subject, authoritySnapshot,
            eligibility.projectId(), input.decision(), input.comment(), source,
            actionHash.checksum(), idempotencyKey);
        if (token != null) {
            jdbc.update("""
                UPDATE confirmation_secure_tokens
                SET consumed_at = CURRENT_TIMESTAMP, consumed_by_subject = ?
                WHERE id = ? AND consumed_at IS NULL
                """, subject, token.id());
            tokenHandoffs.revokeToken(token.id(), "TOKEN_CONSUMED");
            securityEvents.recordBestEffort(
                request.monthId(), "SECURE_CONFIRMATION_TOKEN_CONSUMED",
                subject, "BUSINESS_CONFIRMATION_REQUEST", requestId,
                "SUCCESS", "TOKEN_SINGLE_USE_CONSUMED",
                Map.of("requestVersion", request.version()));
        }
        String resultingState = null;
        int distinctDecisions = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT action)
            FROM business_confirmation_actions
            WHERE request_id = ?
            """, Integer.class, requestId);
        if (distinctDecisions > 1) {
            resultingState = "CONFLICT_REVIEW";
        } else if ("REQUEST_CORRECTION".equals(input.decision())) {
            resultingState = "CHANGES_REQUESTED";
        } else if ("REJECT".equals(input.decision())) {
            resultingState = "REJECTED";
        } else if (quorumMet(request)) {
            resultingState = "CONFIRMED";
        }
        if (resultingState != null) {
            jdbc.update("""
                UPDATE business_confirmation_requests
                SET status = ?,
                    completed_at = CASE
                        WHEN ? = 'CONFLICT_REVIEW' THEN NULL
                        ELSE CURRENT_TIMESTAMP
                    END,
                    optimistic_version = optimistic_version + 1
                WHERE id = ?
                """, resultingState, resultingState, requestId);
            if (!"CONFLICT_REVIEW".equals(resultingState)) {
                revokeOutstandingTokens(
                    requestId, "REQUEST_TERMINAL", subject);
                completeSchedules(requestId);
            } else {
                revokeOutstandingTokens(
                    requestId, "CONFLICT_GOVERNANCE_REQUIRED", subject);
            }
        }
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        if (resultingState != null
            && Set.of("CHANGES_REQUESTED", "REJECTED")
                .contains(resultingState)) {
            jdbc.update("""
                INSERT INTO certification_invalidations
                    (id, engagement_month_id, object_type, object_id,
                     reason_code, status, correlation_id, created_by_subject)
                VALUES (?, ?, 'BUSINESS_CONFIRMATION_REQUEST', ?, ?, 'ACTIVE', ?, ?)
                """, UUID.randomUUID(), request.monthId(), requestId,
                resultingState, correlationId, subject);
        }
        CertificationWorkflowService.MonthRow month = workflow.month(request.monthId());
        if (resultingState != null) {
            workflow.enqueueNotification(month,
                "CONFLICT_REVIEW".equals(resultingState)
                    ? "CONFIRMATION_CONFLICT_REVIEW"
                    : "CONFIRMATION_OUTCOME_RECORDED",
                "business_confirmation_request", requestId, request.version(),
                "CONFLICT_REVIEW".equals(resultingState)
                    ? "confirmation-conflict-v1"
                    : "confirmation-outcome-v1",
                "CONFLICT_REVIEW".equals(resultingState)
                    ? "Monthly confirmation requires governance review"
                    : "Monthly confirmation outcome recorded",
                actionHash.checksum(), correlationId);
        }
        audit(request.monthId(), "CONFIRMATION_ACTION_RECORDED", subject,
            "business_confirmation_action", actionId, request.version(),
            input.comment(), "SUCCESS", request.policyVersionId(), correlationId);
        event(request.monthId(), "confirmation.action.recorded.v1", subject,
            "business_confirmation_action", actionId, request.version(),
            correlationId, Map.of(
                "decision", input.decision(),
                "source", source,
                "resultingState", resultingState == null
                    ? "AWAITING_QUORUM" : resultingState,
                "transportCreatedApproval", false));
        recordIdempotency(subject, "CONFIRMATION_ACTION", requestId,
            idempotencyKey, requestHash, actionId);
        bumpMonth(request.monthId(),
            "CONFIRMED".equals(resultingState) ? "CONFIRMED" : null);
        if ("CONFIRMED".equals(resultingState)) {
            ReadinessView ready = readiness.evaluateAuthorized(
                subject, request.monthId());
            UUID runId = readinessRunId(request.monthId());
            persistAndPublishF05Handoff(
                subject, request, ready, runId, correlationId);
        }
        return requestView(subject, requestId);
    }

    @Transactional
    public UUID promoteReviewedEvidence(
        String reviewerSubject,
        String sourceType,
        UUID sourceId,
        UUID reviewId
    ) {
        ReviewedEvidence evidence = reviewedEvidence(
            sourceType, sourceId, reviewId, reviewerSubject);
        EligibleReviewedAction represented = reviewedEligibility(
            evidence.requestId(), evidence.senderAddressHash());
        if (reviewerSubject.equals(represented.actorSubject())) {
            throw new DomainConflictException(
                "REVIEWER_CANNOT_REPRESENT_ACTION",
                "The restricted reviewer must be distinct from the represented confirmer.");
        }
        authorization.requireConfirmationAction(
            represented.actorSubject(), evidence.requestId(),
            represented.eligibility().projectId());

        ConfirmationRequestRow request = lockRequest(evidence.requestId());
        if (!"AWAITING_RESPONSE".equals(request.status())) {
            throw conflict(
                "CONFIRMATION_NOT_AWAITING_RESPONSE",
                "The confirmation request no longer accepts reviewed evidence.",
                request.version());
        }
        if (!request.dueAt().isAfter(OffsetDateTime.now(clock))) {
            throw new DomainConflictException(
                "REVIEWED_CONFIRMATION_EXPIRED",
                "Reviewed evidence cannot act after the captured request deadline.",
                (long) request.version());
        }
        if ("ORDERED".equals(request.quorumMode())) {
            int priorConfirmed = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM business_confirmation_actions
                WHERE request_id = ? AND action = 'CONFIRM'
                """, Integer.class, request.id());
            if (represented.eligibility().sequence() != priorConfirmed + 1) {
                throw new DomainConflictException(
                    "CONFIRMATION_OUT_OF_ORDER",
                    "The configured ordered confirmer sequence is not satisfied.");
            }
        }

        UUID actionId = UUID.randomUUID();
        String actionIdempotencyKey =
            "reviewed-" + sourceType.toLowerCase(Locale.ROOT) + "-" + sourceId;
        Map<String, Object> actionManifest = new LinkedHashMap<>();
        actionManifest.put("schema", "confirmation-reviewed-action-v1");
        actionManifest.put("requestId", request.id().toString());
        actionManifest.put("requestVersion", request.version());
        actionManifest.put("scopeChecksum", request.scopeChecksum());
        actionManifest.put("actorSubject", represented.actorSubject());
        actionManifest.put("decision", evidence.decision());
        actionManifest.put("projectId",
            string(represented.eligibility().projectId()));
        actionManifest.put("source", evidence.actionSource());
        actionManifest.put("sourceId", sourceId.toString());
        actionManifest.put("reviewId", reviewId.toString());
        actionManifest.put("representedAt", evidence.representedAt());
        actionManifest.put("evidenceHash", evidence.evidenceHash());
        HashResult actionHash = hasher.hash(actionManifest);
        String authoritySnapshot = json(Map.of(
            "eligibleSnapshotId",
                represented.eligibility().eligibilityId().toString(),
            "roleReason", represented.eligibility().roleReason(),
            "projectId", represented.eligibility().projectId() == null
                ? "" : represented.eligibility().projectId().toString(),
            "reviewId", reviewId.toString(),
            "reviewedBySubject", reviewerSubject,
            "sourceType", sourceType,
            "separationOfDutiesChecked", true,
            "resolvedServerSide", true));
        String safeComment = "CONFIRM".equals(evidence.decision())
            ? null
            : "A restricted reviewed " + (
                "INBOUND_MESSAGE".equals(sourceType)
                    ? "verified reply" : "manual evidence fact")
                + " represents this decision.";
        jdbc.update("""
            INSERT INTO business_confirmation_actions
                (id, request_id, request_version, actor_subject,
                 actor_authority_snapshot, project_id, action, comment,
                 source, verification_status, session_evidence_hash,
                 represented_at, action_hash, idempotency_key)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, actionId, request.id(), request.version(),
            represented.actorSubject(), authoritySnapshot,
            represented.eligibility().projectId(), evidence.decision(),
            safeComment, evidence.actionSource(),
            evidence.verificationStatus(), evidence.evidenceHash(),
            evidence.representedAt(), actionHash.checksum(),
            actionIdempotencyKey);

        UUID correlationId = CorrelationIdFilter.currentOrNew();
        jdbc.update("""
            INSERT INTO reviewed_confirmation_action_promotions
                (id, action_id, request_id, request_version, source_type,
                 source_id, review_id, represented_actor_subject,
                 represented_at, evidence_hash, promoted_by_subject,
                 correlation_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), actionId, request.id(), request.version(),
            sourceType, sourceId, reviewId, represented.actorSubject(),
            evidence.representedAt(), evidence.evidenceHash(),
            reviewerSubject, correlationId);

        String resultingState = null;
        int distinctDecisions = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT action)
            FROM business_confirmation_actions
            WHERE request_id = ?
            """, Integer.class, request.id());
        if (distinctDecisions > 1) {
            resultingState = "CONFLICT_REVIEW";
        } else if ("REQUEST_CORRECTION".equals(evidence.decision())) {
            resultingState = "CHANGES_REQUESTED";
        } else if ("REJECT".equals(evidence.decision())) {
            resultingState = "REJECTED";
        } else if (quorumMet(request)) {
            resultingState = "CONFIRMED";
        }
        if (resultingState != null) {
            jdbc.update("""
                UPDATE business_confirmation_requests
                SET status = ?,
                    completed_at = CASE
                        WHEN ? = 'CONFLICT_REVIEW' THEN NULL
                        ELSE CURRENT_TIMESTAMP
                    END,
                    optimistic_version = optimistic_version + 1
                WHERE id = ? AND status = 'AWAITING_RESPONSE'
                """, resultingState, resultingState, request.id());
            if (!"CONFLICT_REVIEW".equals(resultingState)) {
                revokeOutstandingTokens(
                    request.id(), "REQUEST_TERMINAL",
                    represented.actorSubject());
                completeSchedules(request.id());
            } else {
                revokeOutstandingTokens(
                    request.id(), "CONFLICT_GOVERNANCE_REQUIRED",
                    represented.actorSubject());
            }
        }
        if (resultingState != null
            && Set.of("CHANGES_REQUESTED", "REJECTED")
                .contains(resultingState)) {
            jdbc.update("""
                INSERT INTO certification_invalidations
                    (id, engagement_month_id, object_type, object_id,
                     reason_code, status, correlation_id, created_by_subject)
                VALUES (?, ?, 'BUSINESS_CONFIRMATION_REQUEST', ?, ?,
                        'ACTIVE', ?, ?)
                """, UUID.randomUUID(), request.monthId(), request.id(),
                resultingState, correlationId, represented.actorSubject());
        }
        CertificationWorkflowService.MonthRow month =
            workflow.month(request.monthId());
        if (resultingState != null) {
            workflow.enqueueNotification(
                month,
                "CONFLICT_REVIEW".equals(resultingState)
                    ? "CONFIRMATION_CONFLICT_REVIEW"
                    : "CONFIRMATION_OUTCOME_RECORDED",
                "business_confirmation_request", request.id(),
                request.version(),
                "CONFLICT_REVIEW".equals(resultingState)
                    ? "confirmation-conflict-v1"
                    : "confirmation-outcome-v1",
                "CONFLICT_REVIEW".equals(resultingState)
                    ? "Monthly confirmation requires governance review"
                    : "Monthly confirmation outcome recorded",
                actionHash.checksum(), correlationId);
        }
        audit(
            request.monthId(), "REVIEWED_CONFIRMATION_ACTION_RECORDED",
            represented.actorSubject(), "business_confirmation_action",
            actionId, request.version(), safeComment, "SUCCESS",
            request.policyVersionId(), correlationId);
        event(
            request.monthId(), "confirmation.reviewed-action.recorded.v1",
            represented.actorSubject(), "business_confirmation_action",
            actionId, request.version(), correlationId, Map.of(
                "decision", evidence.decision(),
                "source", evidence.actionSource(),
                "sourceId", sourceId,
                "reviewId", reviewId,
                "resultingState", resultingState == null
                    ? "AWAITING_QUORUM" : resultingState,
                "transportCreatedApproval", false));
        bumpMonth(
            request.monthId(),
            "CONFIRMED".equals(resultingState) ? "CONFIRMED" : null);
        if ("CONFIRMED".equals(resultingState)) {
            ReadinessView ready = readiness.evaluateAuthorized(
                represented.actorSubject(), request.monthId());
            UUID runId = readinessRunId(request.monthId());
            persistAndPublishF05Handoff(
                represented.actorSubject(), request, ready, runId,
                correlationId);
        }
        return actionId;
    }

    @Transactional
    public GovernanceDecisionView decideConflict(
        String subject,
        UUID requestId,
        GovernanceDecisionInput input,
        String ifMatch,
        String idempotencyKey
    ) {
        ConfirmationRequestRow initial = requestRow(requestId);
        authorization.requireMonthParty(
            subject, initial.monthId(),
            CertificationAuthorizationService.CLOSE, Party.CLIENT);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, input.expectedRequestVersion());
        String requestHash = requestHash(input);
        UUID prior = priorResult(
            subject, "GOVERN_CONFIRMATION_CONFLICT", requestId,
            idempotencyKey, requestHash);
        if (prior != null) {
            return governanceDecisionView(prior);
        }

        ConfirmationRequestRow request = lockRequest(requestId);
        if (request.version() != expected) {
            throw conflict(
                "CONFIRMATION_VERSION_CONFLICT",
                "The confirmation request version is stale.",
                request.version());
        }
        if (!"CONFLICT_REVIEW".equals(request.status())) {
            throw conflict(
                "CONFIRMATION_NOT_IN_CONFLICT_REVIEW",
                "Only a request in conflict review can receive governance.",
                request.version());
        }
        List<ActionFact> actions = jdbc.query("""
            SELECT id, actor_subject
            FROM business_confirmation_actions
            WHERE request_id = ?
            ORDER BY id
            """, (rs, rowNum) -> new ActionFact(
                rs.getObject("id", UUID.class),
                rs.getString("actor_subject")), requestId);
        List<UUID> storedActionIds = actions.stream()
            .map(ActionFact::id).toList();
        List<UUID> submittedActionIds = input.actionIds().stream()
            .distinct().sorted().toList();
        if (submittedActionIds.size() != input.actionIds().size()
            || !submittedActionIds.equals(storedActionIds.stream().sorted().toList())) {
            throw new DomainConflictException(
                "CONFLICT_ACTION_SET_MISMATCH",
                "Governance must cover the exact immutable conflict action set.");
        }
        if (actions.stream().anyMatch(value ->
                subject.equals(value.actorSubject()))) {
            throw new DomainConflictException(
                "GOVERNANCE_SEPARATION_OF_DUTIES_REQUIRED",
                "Conflict governance requires an authorized person who recorded no action.");
        }

        UUID decisionId = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        jdbc.update("""
            INSERT INTO business_confirmation_governance_decisions
                (id, request_id, request_version, decision, reasoning,
                 action_ids, authority_snapshot, decided_by_subject,
                 correlation_id)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
            """, decisionId, requestId, request.version(), input.decision(),
            input.reasoning(), json(submittedActionIds),
            json(Map.of(
                "permission", CertificationAuthorizationService.CLOSE,
                "resolvedServerSide", true,
                "separatedFromActionActors", true)),
            subject, correlationId);
        String resultingState = switch (input.decision()) {
            case "CONFIRM" -> "CONFIRMED";
            case "REQUEST_CORRECTION" -> "CHANGES_REQUESTED";
            case "REJECT" -> "REJECTED";
            default -> throw new IllegalArgumentException(
                "Unsupported governance decision.");
        };
        jdbc.update("""
            UPDATE business_confirmation_requests
            SET status = ?, completed_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = ? AND status = 'CONFLICT_REVIEW'
            """, resultingState, requestId);
        revokeOutstandingTokens(
            requestId, "GOVERNANCE_TERMINAL", subject);
        completeSchedules(requestId);
        if (!"CONFIRMED".equals(resultingState)) {
            jdbc.update("""
                INSERT INTO certification_invalidations
                    (id, engagement_month_id, object_type, object_id,
                     reason_code, status, correlation_id, created_by_subject)
                VALUES (?, ?, 'BUSINESS_CONFIRMATION_REQUEST', ?, ?,
                        'ACTIVE', ?, ?)
                """, UUID.randomUUID(), request.monthId(), requestId,
                "GOVERNANCE_" + resultingState, correlationId, subject);
        }
        CertificationWorkflowService.MonthRow month =
            workflow.month(request.monthId());
        workflow.enqueueNotification(
            month, "CONFIRMATION_GOVERNANCE_DECIDED",
            "business_confirmation_governance_decision", decisionId,
            request.version(), "confirmation-governance-v1",
            "Monthly confirmation conflict resolved",
            hasher.hash(Map.of(
                "decisionId", decisionId,
                "decision", input.decision(),
                "actionIds", submittedActionIds)).checksum(),
            correlationId);
        UUID auditReference = audit(
            request.monthId(), "CONFIRMATION_CONFLICT_GOVERNED", subject,
            "business_confirmation_governance_decision", decisionId,
            request.version(), input.reasoning(), resultingState,
            request.policyVersionId(), correlationId);
        event(
            request.monthId(), "confirmation.conflict.governed.v1", subject,
            "business_confirmation_governance_decision", decisionId,
            request.version(), correlationId,
            Map.of(
                "decision", input.decision(),
                "actionIds", submittedActionIds,
                "auditReference", auditReference));
        recordIdempotency(
            subject, "GOVERN_CONFIRMATION_CONFLICT", requestId,
            idempotencyKey, requestHash, decisionId);
        bumpMonth(
            request.monthId(),
            "CONFIRMED".equals(resultingState)
                ? "CONFIRMED" : "DELIVERY_REVIEW");
        if ("CONFIRMED".equals(resultingState)) {
            ReadinessView ready = readiness.evaluateAuthorized(
                subject, request.monthId());
            persistAndPublishF05Handoff(
                subject, request, ready, readinessRunId(request.monthId()),
                correlationId);
        }
        return governanceDecisionView(decisionId);
    }

    private ConfirmationRequestView requestView(String subject, UUID requestId) {
        RequestViewRow row = jdbc.query("""
            SELECT request.id, request.engagement_month_id, engagement.name,
                   month.month_start_date, request.version, request.status,
                   request.due_at, request.due_offset_seconds,
                   request.requested_at, request.scope_checksum,
                   request.scope_manifest::text, request.recipient_snapshot::text,
                   request.quorum_mode, request.quorum_required,
                   request.transport_status, request.supersedes_id,
                   request.policy_version_id
            FROM business_confirmation_requests request
            JOIN engagement_months month ON month.id = request.engagement_month_id
            JOIN engagements engagement ON engagement.id = month.engagement_id
            WHERE request.id = ?
            """, rs -> rs.next() ? new RequestViewRow(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getString("name"),
                rs.getObject("month_start_date", java.time.LocalDate.class),
                rs.getInt("version"), rs.getString("status"),
                rs.getObject("due_at", OffsetDateTime.class),
                rs.getInt("due_offset_seconds"),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getString("scope_checksum"), rs.getString("scope_manifest"),
                rs.getString("recipient_snapshot"), rs.getString("quorum_mode"),
                rs.getInt("quorum_required"), rs.getString("transport_status"),
                rs.getObject("supersedes_id", UUID.class),
                rs.getObject("policy_version_id", UUID.class)) : null, requestId);
        if (row == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        Scope readScope = authorization.requireMonthRead(subject, row.monthId());
        boolean eligible = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM confirmation_request_eligibility
                WHERE request_id = ? AND eligible_confirmer_subject = ?
            )
            """, Boolean.class, requestId, subject));
        List<ConfirmationProjectChoice> projectChoices =
            eligibleProjectChoices(requestId, subject);
        CertificationPermissions permissions =
            authorization.permissions(subject, row.monthId());
        boolean fullScope = readScope.allProjects();
        String visibleScopeChecksum = fullScope
            ? row.scopeChecksum()
            : hasher.sha256(
                "project-redacted:" + row.scopeChecksum() + ":" + subject);
        return new ConfirmationRequestView(
            row.id(), row.monthId(), row.engagementName(),
            row.monthStart().getMonth().getDisplayName(
                java.time.format.TextStyle.FULL, Locale.ENGLISH)
                + " " + row.monthStart().getYear(),
            row.version(), row.status(),
            storedOffset(row.dueAt(), row.dueOffsetSeconds()), row.createdAt(),
            !"AWAITING_RESPONSE".equals(row.status()), false, eligible,
            eligible ? "You are in the captured eligible-confirmer snapshot."
                : "You may view this request but are not an eligible confirmer.",
            projectChoices.size() > 1, projectChoices,
            visibleScopeChecksum,
            fullScope ? sourceVersionIds(row.scopeManifest()) : List.of(),
            fullScope ? scopeSources(row.scopeManifest()) : List.of(),
            fullScope
                ? confirmationRecipients(row.recipientSnapshot()) : List.of(),
            quorumDescription(row.quorumMode(), row.quorumRequired()),
            row.transportStatus(), emailAdapter.configurationStatus(),
            fullScope ? versionDiff(row) : List.of(),
            actionViews(requestId, readScope),
            fullScope
                ? workflow.notificationViews(row.monthId(), requestId)
                : List.of(),
            fullScope ? lineage(row.monthId()) : List.of(), permissions);
    }

    private GovernanceDecisionView governanceDecisionView(UUID decisionId) {
        GovernanceDecisionView value = jdbc.query("""
            SELECT decision.id, decision.request_id,
                   decision.request_version, decision.decision,
                   decision.reasoning, decision.action_ids::text,
                   decision.decided_by_subject, profile.display_name,
                   decision.decided_at,
                   (
                       SELECT audit.id
                       FROM certification_audit_events audit
                       WHERE audit.object_type =
                           'business_confirmation_governance_decision'
                         AND audit.object_id = decision.id
                       ORDER BY audit.occurred_at DESC
                       LIMIT 1
                   ) AS audit_reference
            FROM business_confirmation_governance_decisions decision
            LEFT JOIN user_profiles profile
              ON profile.identity_subject = decision.decided_by_subject
            WHERE decision.id = ?
            """, rs -> rs.next() ? new GovernanceDecisionView(
                rs.getObject("id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getInt("request_version"),
                rs.getString("decision"),
                rs.getString("reasoning"),
                uuidList(rs.getString("action_ids")),
                rs.getString("display_name") == null
                    ? rs.getString("decided_by_subject")
                    : rs.getString("display_name"),
                rs.getObject("decided_at", OffsetDateTime.class),
                rs.getObject("audit_reference", UUID.class))
                : null, decisionId);
        if (value == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return value;
    }

    private List<ConfirmationProjectChoice> eligibleProjectChoices(
        UUID requestId,
        String subject
    ) {
        return jdbc.query("""
            SELECT eligibility.project_id, project.name,
                   snapshot.authority_snapshot::text
            FROM confirmation_request_eligibility eligibility
            JOIN confirmation_eligibility_snapshots snapshot
              ON snapshot.id = eligibility.eligibility_id
            LEFT JOIN projects project ON project.id = eligibility.project_id
            WHERE eligibility.request_id = ?
              AND eligibility.eligible_confirmer_subject = ?
            ORDER BY eligibility.sequence_number
            """, (rs, rowNum) -> new ConfirmationProjectChoice(
                rs.getObject("project_id", UUID.class),
                rs.getString("name") == null
                    ? "Engagement-wide scope" : rs.getString("name"),
                roleReason(rs.getString("authority_snapshot"))),
            requestId, subject);
    }

    private List<ConfirmationActionView> actionViews(
        UUID requestId,
        Scope readScope
    ) {
        String projectFilter = readScope.allProjects()
            ? ""
            : " AND action.project_id = ANY (?::uuid[])";
        List<Object> arguments = new ArrayList<>();
        arguments.add(requestId);
        if (!readScope.allProjects()) {
            arguments.add(readScope.projectIds().toArray(UUID[]::new));
        }
        return jdbc.query("""
            SELECT action.id, action.action, action.actor_subject,
                   profile.display_name, action.actor_authority_snapshot::text,
                   action.source, action.comment, action.action_at,
                   action.represented_at,
                   (SELECT audit.id
                    FROM certification_audit_events audit
                    WHERE audit.object_id = action.id
                    ORDER BY audit.occurred_at DESC LIMIT 1) AS audit_id
            FROM business_confirmation_actions action
            LEFT JOIN user_profiles profile
              ON profile.identity_subject = action.actor_subject
            WHERE action.request_id = ?
            """ + projectFilter + """
            ORDER BY action.action_at
            """, (rs, rowNum) -> new ConfirmationActionView(
                rs.getObject("id", UUID.class), rs.getString("action"),
                rs.getString("display_name") == null
                    ? rs.getString("actor_subject") : rs.getString("display_name"),
                roleReason(rs.getString("actor_authority_snapshot")),
                viewSource(rs.getString("source")), rs.getString("comment"),
                rs.getObject("action_at", OffsetDateTime.class),
                rs.getObject("represented_at", OffsetDateTime.class),
                rs.getObject("audit_id", UUID.class)), arguments.toArray());
    }

    private List<ConfirmationHistoryItem> lineage(UUID monthId) {
        return jdbc.query("""
            SELECT id, version, status, due_at, due_offset_seconds,
                   requested_at, supersedes_id
            FROM business_confirmation_requests
            WHERE engagement_month_id = ?
            ORDER BY version DESC
            """, (rs, rowNum) -> new ConfirmationHistoryItem(
                rs.getObject("id", UUID.class), rs.getInt("version"),
                rs.getString("status"), storedOffset(
                    rs.getObject("due_at", OffsetDateTime.class),
                    rs.getInt("due_offset_seconds")),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getObject("supersedes_id", UUID.class)), monthId);
    }

    private List<ConfirmationRecipient> confirmationRecipients(String storedJson) {
        Map<String, Object> value = map(storedJson);
        List<ConfirmationRecipient> recipients = new ArrayList<>();
        appendRecipients(recipients, value.get("to"), "TO");
        appendRecipients(recipients, value.get("cc"), "CC");
        return recipients;
    }

    private void appendRecipients(
        List<ConfirmationRecipient> target,
        Object raw,
        String kind
    ) {
        if (!(raw instanceof List<?> values)) {
            return;
        }
        for (Object value : values) {
            if (value instanceof Map<?, ?> recipient) {
                target.add(new ConfirmationRecipient(
                    String.valueOf(recipient.get("display")),
                    String.valueOf(recipient.get("roleReason")), kind));
            }
        }
    }

    private List<String> sourceVersionIds(String scopeManifest) {
        Map<String, Object> value = map(scopeManifest);
        return java.util.stream.Stream.of(
                value.get("attendanceSnapshotId"),
                value.get("planVersionId"),
                value.get("baselineId"),
                value.get("certificationSummaryId"))
            .filter(java.util.Objects::nonNull)
            .map(String::valueOf)
            .toList();
    }

    private List<ConfirmationScopeSource> scopeSources(String scopeManifest) {
        Object raw = map(scopeManifest).get("sources");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<ConfirmationScopeSource> sources = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> source)
                || source.get("id") == null) {
                continue;
            }
            Integer version = source.get("version") instanceof Number number
                ? number.intValue() : null;
            sources.add(new ConfirmationScopeSource(
                String.valueOf(source.get("kind")),
                UUID.fromString(String.valueOf(source.get("id"))),
                version,
                nullableString(source.get("checksum")),
                nullableString(source.get("freshness")),
                nullableString(source.get("display"))));
        }
        return List.copyOf(sources);
    }

    private List<VersionDiffItem> versionDiff(RequestViewRow current) {
        if (current.supersedesId() == null) {
            return List.of(new VersionDiffItem(
                "Confirmation scope", null, current.scopeChecksum()));
        }
        String priorHash = jdbc.queryForObject("""
            SELECT scope_checksum FROM business_confirmation_requests WHERE id = ?
            """, String.class, current.supersedesId());
        return List.of(new VersionDiffItem(
            "Confirmation scope checksum", priorHash, current.scopeChecksum()));
    }

    private ConfirmationSources lockSources(UUID monthId) {
        return jdbc.query("""
            SELECT month.engagement_id, month.certification_version,
                   plan.current_version_id AS plan_version_id,
                   version.version AS plan_version_number,
                   version.checksum AS plan_checksum,
                   baseline.id AS baseline_id,
                   baseline.checksum AS baseline_checksum,
                   summary.id AS summary_id,
                   summary.version AS summary_version,
                   summary.checksum AS summary_checksum,
                   summary.policy_version_id,
                   (SELECT snapshot.id
                    FROM attendance_snapshot_versions snapshot
                    WHERE snapshot.engagement_month_id = month.id
                      AND snapshot.status = 'CLOSED'
                      AND NOT EXISTS (
                          SELECT 1 FROM attendance_snapshot_versions newer
                          WHERE newer.supersedes_id = snapshot.id)
                    ORDER BY snapshot.version DESC LIMIT 1)
                       AS attendance_snapshot_id
                   ,
                   (SELECT snapshot.version
                    FROM attendance_snapshot_versions snapshot
                    WHERE snapshot.engagement_month_id = month.id
                      AND snapshot.status = 'CLOSED'
                      AND NOT EXISTS (
                          SELECT 1 FROM attendance_snapshot_versions newer
                          WHERE newer.supersedes_id = snapshot.id)
                    ORDER BY snapshot.version DESC LIMIT 1)
                       AS attendance_snapshot_version,
                   (SELECT snapshot.checksum
                    FROM attendance_snapshot_versions snapshot
                    WHERE snapshot.engagement_month_id = month.id
                      AND snapshot.status = 'CLOSED'
                      AND NOT EXISTS (
                          SELECT 1 FROM attendance_snapshot_versions newer
                          WHERE newer.supersedes_id = snapshot.id)
                    ORDER BY snapshot.version DESC LIMIT 1)
                       AS attendance_snapshot_checksum
            FROM engagement_months month
            JOIN delivery_plans plan ON plan.engagement_month_id = month.id
            JOIN delivery_plan_versions version
              ON version.id = plan.current_version_id
            JOIN delivery_plan_baselines baseline
              ON baseline.plan_version_id = plan.current_version_id
            JOIN monthly_certification_summaries summary
              ON summary.engagement_month_id = month.id
             AND summary.status = 'CURRENT'
            WHERE month.id = ?
            FOR UPDATE OF month
            """, rs -> {
                if (!rs.next()) {
                    throw new DomainConflictException(
                        "CONFIRMATION_SOURCES_REQUIRED",
                        "Frozen plan, baseline and current certification summary are required.");
                }
                return new ConfirmationSources(
                    monthId, rs.getObject("engagement_id", UUID.class),
                    rs.getLong("certification_version"),
                    rs.getObject("plan_version_id", UUID.class),
                    rs.getInt("plan_version_number"),
                    rs.getString("plan_checksum"),
                    rs.getObject("baseline_id", UUID.class),
                    rs.getString("baseline_checksum"),
                    rs.getObject("summary_id", UUID.class),
                    rs.getInt("summary_version"),
                    rs.getString("summary_checksum"),
                    rs.getObject("policy_version_id", UUID.class),
                    rs.getObject("attendance_snapshot_id", UUID.class),
                    (Integer) rs.getObject("attendance_snapshot_version"),
                    rs.getString("attendance_snapshot_checksum"));
            }, monthId);
    }

    private List<EligibleConfirmer> resolveEligibleConfirmers(
        ConfirmationSources sources
    ) {
        return jdbc.query("""
            SELECT DISTINCT deliverable.product_owner_subject,
                   profile.email, deliverable.project_id
            FROM delivery_deliverable_versions deliverable
            JOIN user_profiles profile
              ON profile.identity_subject = deliverable.product_owner_subject
             AND profile.status = 'ACTIVE'
            WHERE deliverable.plan_version_id = ?
            ORDER BY deliverable.product_owner_subject, deliverable.project_id
            """, (rs, rowNum) -> new EligibleConfirmer(
                rs.getString("product_owner_subject"), rs.getString("email"),
                rs.getObject("project_id", UUID.class),
                Map.of(
                    "permission", CertificationAuthorizationService.CONFIRMATION_ACT,
                    "projectId", rs.getObject("project_id", UUID.class).toString(),
                    "roleReason", "ASSIGNED_PRODUCT_OWNER",
                    "resolvedServerSide", true)), sources.planVersionId()).stream()
            .filter(value -> authorization.hasProjectPartyPermission(
                value.subject(), sources.engagementId(), value.projectId(),
                CertificationAuthorizationService.CONFIRMATION_ACT,
                Party.CLIENT))
            .toList();
    }

    private PolicyRow policy(UUID policyId) {
        return jdbc.query("""
            SELECT quorum_mode, quorum_required, token_ttl_seconds,
                   confirmation_due_seconds
            FROM certification_policy_versions WHERE id = ?
            """, rs -> {
                if (!rs.next()) {
                    throw new DomainConflictException(
                        "CERTIFICATION_POLICY_REQUIRED",
                        "A captured certification policy is required.");
                }
                return new PolicyRow(
                    rs.getString("quorum_mode"), rs.getInt("quorum_required"),
                    rs.getInt("token_ttl_seconds"),
                    rs.getInt("confirmation_due_seconds"));
            }, policyId);
    }

    private int requiredQuorum(
        PolicyRow policy,
        List<EligibleConfirmer> eligible
    ) {
        int required = switch (policy.quorumMode()) {
            case "ANY_ONE" -> 1;
            case "ALL", "PROJECT_SPECIFIC" -> eligible.size();
            case "N_OF_M", "ORDERED" -> policy.quorumRequired();
            default -> throw new IllegalArgumentException(
                "Unsupported confirmation quorum.");
        };
        if (required < 1 || required > eligible.size()) {
            throw new DomainConflictException(
                "CONFIRMATION_QUORUM_UNSATISFIABLE",
                "The captured quorum cannot be satisfied by active eligible confirmers.");
        }
        return required;
    }

    private Map<String, Object> eligibilityManifest(
        List<EligibleConfirmer> eligible,
        PolicyRow policy,
        int quorumRequired
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("quorumMode", policy.quorumMode());
        value.put("quorumRequired", quorumRequired);
        value.put("eligible", eligible.stream()
            .sorted(Comparator.comparing(EligibleConfirmer::subject)
                .thenComparing(item -> item.projectId().toString()))
            .map(item -> Map.of(
                "subject", item.subject(),
                "emailHash", hasher.sha256(
                    item.email().toLowerCase(Locale.ROOT)),
                "projectId", item.projectId().toString(),
                "roleReason", "ASSIGNED_PRODUCT_OWNER"))
            .toList());
        return value;
    }

    private Map<String, Object> scopeManifest(
        ConfirmationSources sources,
        int requestVersion,
        Map<String, Object> recipients,
        Map<String, Object> eligible,
        OffsetDateTime dueAt
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", "business-confirmation-scope-v1");
        value.put("engagementMonthId", sources.monthId().toString());
        value.put("requestVersion", requestVersion);
        value.put("attendanceSnapshotId", string(sources.attendanceSnapshotId()));
        value.put("planVersionId", sources.planVersionId().toString());
        value.put("baselineId", sources.baselineId().toString());
        value.put("certificationSummaryId", sources.summaryId().toString());
        value.put("packageVersionReference", null);
        List<Map<String, Object>> sourceFacts = new ArrayList<>();
        if (sources.attendanceSnapshotId() != null) {
            sourceFacts.add(scopeSource(
                "ATTENDANCE_SNAPSHOT", sources.attendanceSnapshotId(),
                sources.attendanceSnapshotVersion(),
                sources.attendanceSnapshotChecksum(), "CURRENT",
                "Closed attendance snapshot"));
        }
        sourceFacts.add(scopeSource(
            "DELIVERY_PLAN_VERSION", sources.planVersionId(),
            sources.planVersion(), sources.planChecksum(), "FROZEN",
            "Frozen delivery plan"));
        sourceFacts.add(scopeSource(
            "DELIVERY_BASELINE", sources.baselineId(), null,
            sources.baselineChecksum(), "FROZEN",
            "Immutable delivery baseline"));
        sourceFacts.add(scopeSource(
            "CERTIFICATION_SUMMARY", sources.summaryId(),
            sources.summaryVersion(), sources.summaryChecksum(), "CURRENT",
            "Monthly certification summary"));
        value.put("sources", sourceFacts);
        value.put("recipientSnapshot", recipients);
        value.put("eligibilitySnapshot", eligible);
        value.put("dueAt", dueAt);
        value.put("f05ProviderStatus", f05Publisher.configurationStatus());
        return value;
    }

    private Map<String, Object> scopeSource(
        String kind,
        UUID id,
        Integer version,
        String checksum,
        String freshness,
        String display
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", kind);
        value.put("id", id.toString());
        value.put("version", version);
        value.put("checksum", checksum);
        value.put("freshness", freshness);
        value.put("display", display);
        return value;
    }

    private ConfirmationRequestRow currentRequest(UUID monthId, boolean lock) {
        return jdbc.query("""
            SELECT id, engagement_month_id, version, status, scope_checksum,
                   quorum_mode, quorum_required, policy_version_id, due_at
            FROM business_confirmation_requests
            WHERE engagement_month_id = ?
              AND status NOT IN ('CANCELLED', 'SUPERSEDED', 'EXPIRED')
            """ + (lock ? " FOR UPDATE" : ""),
            rs -> rs.next() ? new ConfirmationRequestRow(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getInt("version"), rs.getString("status"),
                rs.getString("scope_checksum"), rs.getString("quorum_mode"),
                rs.getInt("quorum_required"),
                rs.getObject("policy_version_id", UUID.class),
            rs.getObject("due_at", OffsetDateTime.class)) : null, monthId);
    }

    private ConfirmationRequestRow requestRow(UUID requestId) {
        ConfirmationRequestRow value = jdbc.query("""
            SELECT id, engagement_month_id, version, status, scope_checksum,
                   quorum_mode, quorum_required, policy_version_id, due_at
            FROM business_confirmation_requests
            WHERE id = ?
            """, rs -> rs.next() ? new ConfirmationRequestRow(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getInt("version"), rs.getString("status"),
                rs.getString("scope_checksum"), rs.getString("quorum_mode"),
                rs.getInt("quorum_required"),
                rs.getObject("policy_version_id", UUID.class),
                rs.getObject("due_at", OffsetDateTime.class)) : null,
            requestId);
        if (value == null) {
            throw new EntityNotFoundException("Resource not found.");
        }
        return value;
    }

    private ConfirmationRequestRow lockRequest(UUID requestId) {
        return jdbc.query("""
            SELECT id, engagement_month_id, version, status, scope_checksum,
                   quorum_mode, quorum_required, policy_version_id, due_at
            FROM business_confirmation_requests
            WHERE id = ?
            FOR UPDATE
            """, rs -> {
                if (!rs.next()) {
                    throw new EntityNotFoundException("Resource not found.");
                }
                return new ConfirmationRequestRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("engagement_month_id", UUID.class),
                    rs.getInt("version"), rs.getString("status"),
                    rs.getString("scope_checksum"), rs.getString("quorum_mode"),
                    rs.getInt("quorum_required"),
                    rs.getObject("policy_version_id", UUID.class),
                    rs.getObject("due_at", OffsetDateTime.class));
            }, requestId);
    }

    private EligibleAction eligibility(
        UUID requestId,
        String subject,
        UUID requestedProjectId
    ) {
        List<EligibleAction> matches = jdbc.query("""
            SELECT eligibility.eligibility_id, eligibility.project_id,
                   eligibility.sequence_number,
                   snapshot.authority_snapshot::text
            FROM confirmation_request_eligibility eligibility
            JOIN confirmation_eligibility_snapshots snapshot
              ON snapshot.id = eligibility.eligibility_id
            WHERE eligibility.request_id = ?
              AND eligibility.eligible_confirmer_subject = ?
              AND (?::uuid IS NULL OR eligibility.project_id = ?::uuid)
            ORDER BY eligibility.sequence_number
            """, (rs, rowNum) -> new EligibleAction(
                rs.getObject("eligibility_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getInt("sequence_number"),
                roleReason(rs.getString("authority_snapshot"))),
            requestId, subject, requestedProjectId, requestedProjectId);
        if (matches.isEmpty()) {
            throw new EntityNotFoundException("Resource not found.");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                "projectId is required when acting for multiple eligible projects.");
        }
        return matches.getFirst();
    }

    private ReviewedEvidence reviewedEvidence(
        String sourceType,
        UUID sourceId,
        UUID reviewId,
        String reviewerSubject
    ) {
        if ("INBOUND_MESSAGE".equals(sourceType)) {
            return jdbc.query("""
                SELECT message.request_id, message.sender_address_hash,
                       message.classified_intent,
                       message.provider_received_at,
                       message.provider_message_fingerprint,
                       message.raw_sha256
                FROM inbound_confirmation_messages message
                JOIN inbound_confirmation_reviews review
                  ON review.inbound_message_id = message.id
                 AND review.id = ?
                 AND review.reviewer_subject = ?
                 AND review.decision = 'ACCEPT_INTERPRETATION'
                WHERE message.id = ?
                  AND message.request_id IS NOT NULL
                  AND message.status = 'MANUAL_REVIEW_REQUIRED'
                  AND message.authentication_evidence
                      @> '{"verified":true}'::jsonb
                  AND message.classified_intent IN (
                      'EXPLICIT_CONFIRM', 'EXPLICIT_CORRECTION',
                      'EXPLICIT_REJECT'
                  )
                """, rs -> {
                    if (!rs.next()) {
                        throw new DomainConflictException(
                            "INBOUND_REPLY_NOT_PROMOTABLE",
                            "Only an accepted, authenticated explicit reply "
                                + "can become a confirmation action.");
                    }
                    String rawHash = rs.getString("raw_sha256");
                    return new ReviewedEvidence(
                        rs.getObject("request_id", UUID.class),
                        rs.getString("sender_address_hash"),
                        switch (rs.getString("classified_intent")) {
                            case "EXPLICIT_CONFIRM" -> "CONFIRM";
                            case "EXPLICIT_CORRECTION" ->
                                "REQUEST_CORRECTION";
                            case "EXPLICIT_REJECT" -> "REJECT";
                            default -> throw new IllegalStateException(
                                "Unsupported reviewed inbound intent.");
                        },
                        "VERIFIED_EMAIL_REPLY", "VERIFIED",
                        rawHash == null
                            ? hasher.sha256(rs.getString(
                                "provider_message_fingerprint"))
                            : rawHash,
                        rs.getObject(
                            "provider_received_at", OffsetDateTime.class));
                }, reviewId, reviewerSubject, sourceId);
        }
        if ("MANUAL_EVIDENCE".equals(sourceType)) {
            return jdbc.query("""
                SELECT evidence.request_id, evidence.sender_address,
                       evidence.represented_decision, evidence.file_hash,
                       evidence.sent_or_received_at
                FROM manual_confirmation_evidence evidence
                JOIN manual_confirmation_evidence_reviews review
                  ON review.manual_evidence_id = evidence.id
                 AND review.id = ?
                 AND review.reviewer_subject = ?
                 AND review.decision = 'APPROVE'
                WHERE evidence.id = ?
                  AND evidence.request_id IS NOT NULL
                  AND evidence.recorded_by_subject <> review.reviewer_subject
                """, rs -> {
                    if (!rs.next()) {
                        throw new DomainConflictException(
                            "MANUAL_EVIDENCE_NOT_PROMOTABLE",
                            "Only distinctly approved, request-bound manual "
                                + "evidence can become a confirmation action.");
                    }
                    return new ReviewedEvidence(
                        rs.getObject("request_id", UUID.class),
                        hasher.sha256(rs.getString("sender_address")
                            .strip().toLowerCase(Locale.ROOT)),
                        rs.getString("represented_decision"),
                        "MANUAL_EVIDENCE", "MANUAL_REVIEWED",
                        rs.getString("file_hash"),
                        rs.getObject(
                            "sent_or_received_at", OffsetDateTime.class));
                }, reviewId, reviewerSubject, sourceId);
        }
        throw new IllegalArgumentException(
            "Unsupported reviewed confirmation evidence type.");
    }

    private EligibleReviewedAction reviewedEligibility(
        UUID requestId,
        String senderAddressHash
    ) {
        List<EligibleReviewedAction> matches = jdbc.query("""
            SELECT eligibility.eligible_confirmer_subject,
                   eligibility.eligibility_id, eligibility.project_id,
                   eligibility.sequence_number,
                   snapshot.verified_email,
                   snapshot.authority_snapshot::text
            FROM confirmation_request_eligibility eligibility
            JOIN confirmation_eligibility_snapshots snapshot
              ON snapshot.id = eligibility.eligibility_id
            WHERE eligibility.request_id = ?
            ORDER BY eligibility.sequence_number
            """, (rs, rowNum) -> new EligibleReviewedAction(
                rs.getString("eligible_confirmer_subject"),
                rs.getString("verified_email"),
                new EligibleAction(
                    rs.getObject("eligibility_id", UUID.class),
                    rs.getObject("project_id", UUID.class),
                    rs.getInt("sequence_number"),
                    roleReason(rs.getString("authority_snapshot")))),
            requestId).stream().filter(candidate ->
                hasher.sha256(candidate.verifiedEmail().strip()
                    .toLowerCase(Locale.ROOT))
                    .equalsIgnoreCase(senderAddressHash))
            .toList();
        if (matches.size() != 1) {
            throw new DomainConflictException(
                "REVIEWED_EVIDENCE_ELIGIBILITY_AMBIGUOUS",
                "Reviewed evidence must resolve to exactly one captured "
                    + "eligible confirmer and project.");
        }
        return matches.getFirst();
    }

    private TokenRow tokenForUpdate(
        UUID requestId,
        String subject,
        UUID projectId
    ) {
        return jdbc.query("""
            SELECT id, request_version, token_hash, token_salt, work_factor,
                   expires_at, consumed_at
            FROM confirmation_secure_tokens
            WHERE request_id = ? AND eligible_confirmer_subject = ?
              AND project_id IS NOT DISTINCT FROM ?::uuid
              AND NOT EXISTS (
                  SELECT 1
                  FROM confirmation_token_revocations revocation
                  WHERE revocation.token_id = confirmation_secure_tokens.id
              )
            FOR UPDATE
            """, rs -> rs.next() ? new TokenRow(
                rs.getObject("id", UUID.class), rs.getInt("request_version"),
                rs.getString("token_hash"), rs.getString("token_salt"),
                rs.getInt("work_factor"),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("consumed_at", OffsetDateTime.class)) : null,
            requestId, subject, projectId);
    }

    private boolean quorumMet(ConfirmationRequestRow request) {
        int confirmed = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM business_confirmation_actions
            WHERE request_id = ? AND action = 'CONFIRM'
            """, Integer.class, request.id());
        return switch (request.quorumMode()) {
            case "ANY_ONE" -> confirmed >= 1;
            case "ALL", "N_OF_M", "ORDERED" ->
                confirmed >= request.quorumRequired();
            case "PROJECT_SPECIFIC" -> {
                int projects = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT project_id)
                    FROM confirmation_request_eligibility
                    WHERE request_id = ?
                    """, Integer.class, request.id());
                int confirmedProjects = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT project_id)
                    FROM business_confirmation_actions
                    WHERE request_id = ? AND action = 'CONFIRM'
                    """, Integer.class, request.id());
                yield projects > 0 && projects == confirmedProjects;
            }
            default -> false;
        };
    }

    private boolean expireRequest(
        ConfirmationRequestRow request,
        UUID correlationId
    ) {
        int updated = jdbc.update("""
            UPDATE business_confirmation_requests
            SET status = 'EXPIRED',
                optimistic_version = optimistic_version + 1
            WHERE id = ?
              AND status IN ('AWAITING_RESPONSE', 'CONFLICT_REVIEW')
            """, request.id());
        if (updated == 1) {
            revokeOutstandingTokens(
                request.id(), "REQUEST_EXPIRED", "SYSTEM:EXPIRY");
            jdbc.update("""
                UPDATE confirmation_request_schedules
                SET status = CASE
                        WHEN schedule_type = 'EXPIRY' THEN 'COMPLETED'
                        ELSE 'CANCELLED'
                    END,
                    completed_at = CURRENT_TIMESTAMP,
                    lease_owner = NULL,
                    lease_expires_at = NULL
                WHERE request_id = ?
                  AND status IN ('PENDING', 'CLAIMED')
                """, request.id());
            CertificationWorkflowService.MonthRow month =
                workflow.month(request.monthId());
            workflow.enqueueNotification(
                month, "CONFIRMATION_EXPIRED",
                "business_confirmation_request", request.id(),
                request.version(), "confirmation-expired-v1",
                "Monthly business confirmation expired",
                request.scopeChecksum(), correlationId);
            audit(
                request.monthId(), "CONFIRMATION_EXPIRED",
                "SYSTEM:CONFIRMATION_EXPIRY",
                "business_confirmation_request", request.id(),
                request.version(), "Captured due time elapsed", "EXPIRED",
                request.policyVersionId(), correlationId);
            event(
                request.monthId(), "confirmation.expired.v1",
                "SYSTEM:CONFIRMATION_EXPIRY",
                "business_confirmation_request", request.id(),
                request.version(), correlationId,
                Map.of(
                    "dueAt", request.dueAt(),
                    "tokenRevocationsRecorded", true));
            bumpMonth(request.monthId(), "DELIVERY_REVIEW");
        }
        return updated == 1;
    }

    @Transactional
    void processClaimedSchedule(UUID scheduleId, String leaseOwner) {
        ScheduleRow schedule = jdbc.query("""
            SELECT schedule.id, schedule.request_id, schedule.schedule_type,
                   schedule.due_at, schedule.status, schedule.lease_owner,
                   request.engagement_month_id, request.version,
                   request.status AS request_status, request.scope_checksum,
                   request.quorum_mode, request.quorum_required,
                   request.policy_version_id, request.due_at AS request_due_at
            FROM confirmation_request_schedules schedule
            JOIN business_confirmation_requests request
              ON request.id = schedule.request_id
            WHERE schedule.id = ?
            FOR UPDATE OF schedule, request
            """, rs -> rs.next() ? new ScheduleRow(
                rs.getObject("id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("schedule_type"),
                rs.getObject("due_at", OffsetDateTime.class),
                rs.getString("status"), rs.getString("lease_owner"),
                new ConfirmationRequestRow(
                    rs.getObject("request_id", UUID.class),
                    rs.getObject("engagement_month_id", UUID.class),
                    rs.getInt("version"),
                    rs.getString("request_status"),
                    rs.getString("scope_checksum"),
                    rs.getString("quorum_mode"),
                    rs.getInt("quorum_required"),
                    rs.getObject("policy_version_id", UUID.class),
                    rs.getObject("request_due_at", OffsetDateTime.class)))
                : null, scheduleId);
        if (schedule == null
            || !"CLAIMED".equals(schedule.status())
            || !leaseOwner.equals(schedule.leaseOwner())) {
            return;
        }
        if (!Set.of("AWAITING_RESPONSE", "CONFLICT_REVIEW")
            .contains(schedule.request().status())) {
            finishSchedule(schedule.id(), "CANCELLED");
            return;
        }
        if (schedule.dueAt().isAfter(OffsetDateTime.now(clock))) {
            jdbc.update("""
                UPDATE confirmation_request_schedules
                SET status = 'PENDING', lease_owner = NULL,
                    lease_expires_at = NULL, next_attempt_at = due_at
                WHERE id = ? AND lease_owner = ?
                """, schedule.id(), leaseOwner);
            return;
        }
        UUID correlationId = UUID.randomUUID();
        if ("EXPIRY".equals(schedule.type())) {
            if (!expireRequest(schedule.request(), correlationId)) {
                finishSchedule(schedule.id(), "CANCELLED");
            }
            return;
        }
        CertificationWorkflowService.MonthRow month =
            workflow.month(schedule.request().monthId());
        workflow.enqueueNotification(
            month, "CONFIRMATION_REMINDER",
            "business_confirmation_request", schedule.requestId(),
            schedule.request().version(), "confirmation-reminder-v1",
            "Monthly business confirmation reminder",
            schedule.request().scopeChecksum(), correlationId);
        audit(
            schedule.request().monthId(), "CONFIRMATION_REMINDER_QUEUED",
            "SYSTEM:CONFIRMATION_REMINDER",
            "confirmation_request_schedule", schedule.id(),
            schedule.request().version(), "Captured reminder became due",
            "QUEUED", schedule.request().policyVersionId(), correlationId);
        event(
            schedule.request().monthId(), "confirmation.reminder.queued.v1",
            "SYSTEM:CONFIRMATION_REMINDER",
            "confirmation_request_schedule", schedule.id(),
            schedule.request().version(), correlationId,
            Map.of("requestId", schedule.requestId()));
        finishSchedule(schedule.id(), "COMPLETED");
    }

    private void finishSchedule(UUID scheduleId, String status) {
        jdbc.update("""
            UPDATE confirmation_request_schedules
            SET status = ?, completed_at = CURRENT_TIMESTAMP,
                lease_owner = NULL, lease_expires_at = NULL,
                next_attempt_at = NULL
            WHERE id = ?
            """, status, scheduleId);
    }

    private void revokeOutstandingTokens(
        UUID requestId,
        String reason,
        String actor
    ) {
        jdbc.update("""
            INSERT INTO confirmation_token_revocations
                (id, token_id, reason_code, revoked_by_subject)
            SELECT gen_random_uuid(), token.id, ?, ?
            FROM confirmation_secure_tokens token
            WHERE token.request_id = ?
              AND token.consumed_at IS NULL
            ON CONFLICT (token_id) DO NOTHING
            """, reason, actor, requestId);
        tokenHandoffs.revokeRequest(requestId, reason);
    }

    private void scheduleRequest(
        UUID requestId,
        OffsetDateTime requestedAt,
        OffsetDateTime dueAt
    ) {
        long seconds = java.time.Duration.between(
            requestedAt, dueAt).toSeconds();
        if (seconds >= 600) {
            OffsetDateTime reminderAt = requestedAt.plusSeconds(seconds / 2);
            jdbc.update("""
                INSERT INTO confirmation_request_schedules
                    (id, request_id, schedule_type, sequence_number,
                     due_at, status, next_attempt_at)
                VALUES (?, ?, 'REMINDER', 1, ?, 'PENDING', ?)
                """, UUID.randomUUID(), requestId, reminderAt, reminderAt);
        }
        jdbc.update("""
            INSERT INTO confirmation_request_schedules
                (id, request_id, schedule_type, sequence_number,
                 due_at, status, next_attempt_at)
            VALUES (?, ?, 'EXPIRY', 1, ?, 'PENDING', ?)
            """, UUID.randomUUID(), requestId, dueAt, dueAt);
    }

    private void completeSchedules(UUID requestId) {
        jdbc.update("""
            UPDATE confirmation_request_schedules
            SET status = 'CANCELLED',
                completed_at = CURRENT_TIMESTAMP,
                lease_owner = NULL,
                lease_expires_at = NULL
            WHERE request_id = ?
              AND status IN ('PENDING', 'CLAIMED')
            """, requestId);
    }

    private void persistAndPublishF05Handoff(
        String subject,
        ConfirmationRequestRow request,
        ReadinessView ready,
        UUID runId,
        UUID correlationId
    ) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "f04-f05-handoff-v1");
        manifest.put("engagementMonthId", request.monthId().toString());
        manifest.put("confirmationRequestId", request.id().toString());
        manifest.put("confirmationRequestVersion", request.version());
        manifest.put("confirmationScopeHash", request.scopeChecksum());
        manifest.put("readinessRunId", runId.toString());
        manifest.put("readinessInputVersion", ready.inputManifestVersion());
        HashResult packageHash = hasher.hash(manifest);
        UUID handoffId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO f05_certification_handoffs
                (id, engagement_month_id, confirmation_request_id,
                 readiness_run_id, package_manifest, package_hash,
                 status, created_by_subject, correlation_id)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, 'READY_LOCAL', ?, ?)
            ON CONFLICT (confirmation_request_id, package_hash) DO NOTHING
            """, handoffId, request.monthId(), request.id(), runId,
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
    }

    private OffsetDateTime earlier(
        OffsetDateTime first,
        OffsetDateTime second
    ) {
        return first.isBefore(second) ? first : second;
    }

    private int nextRequestVersion(UUID monthId) {
        return jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM business_confirmation_requests
            WHERE engagement_month_id = ?
            """, Integer.class, monthId);
    }

    private String providerTransportStatus() {
        return "CONFIGURED".equals(emailAdapter.configurationStatus())
            ? "QUEUED" : "NOT_CONFIGURED";
    }

    private String quorumDescription(String mode, int required) {
        return switch (mode) {
            case "ANY_ONE" -> "Any one eligible confirmer";
            case "ALL" -> "All eligible confirmers";
            case "N_OF_M" -> required + " eligible confirmers";
            case "ORDERED" -> required + " confirmations in captured order";
            case "PROJECT_SPECIFIC" -> "At least one confirmation for every project";
            default -> mode;
        };
    }

    private String viewSource(String stored) {
        return switch (stored) {
            case "SECURE_EMAIL_LINK" -> "SECURE_LINK";
            case "VERIFIED_EMAIL_REPLY" -> "VERIFIED_REPLY";
            default -> stored;
        };
    }

    private String roleReason(String storedJson) {
        Object value = map(storedJson).get("roleReason");
        return value == null ? "CAPTURED_ELIGIBLE_AUTHORITY" : String.valueOf(value);
    }

    private UUID readinessRunId(UUID monthId) {
        return jdbc.query("""
            SELECT id FROM certification_readiness_runs
            WHERE engagement_month_id = ?
            ORDER BY evaluated_at DESC LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, monthId);
    }

    private UUID priorResult(
        String actor,
        String operation,
        UUID scopeId,
        String key,
        String requestHash
    ) {
        return jdbc.query("""
            SELECT request_hash, result_id
            FROM certification_idempotency_keys
            WHERE actor_subject = ? AND operation = ?
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
            }, actor, operation, scopeId, key);
    }

    private void recordIdempotency(
        String actor,
        String operation,
        UUID scopeId,
        String key,
        String requestHash,
        UUID resultId
    ) {
        jdbc.update("""
            INSERT INTO certification_idempotency_keys
                (id, actor_subject, operation, scope_id, idempotency_key,
                 request_hash, result_type, result_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), actor, operation, scopeId, key,
            requestHash, "business_confirmation", resultId);
    }

    private UUID audit(
        UUID monthId,
        String eventType,
        String actor,
        String objectType,
        UUID objectId,
        int version,
        String reason,
        String result,
        UUID policyId,
        UUID correlationId
    ) {
        UUID auditId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO certification_audit_events
                (id, engagement_month_id, event_type, actor_subject,
                 authority_snapshot, object_type, object_id, object_version,
                 source, reason, result, correlation_id, policy_version_id)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'IN_APP', ?, ?, ?, ?)
            """, auditId, monthId, eventType, actor,
            json(Map.of("resolvedServerSide", true)), objectType, objectId,
            version, reason, result, correlationId, policyId);
        return auditId;
    }

    private void event(
        UUID monthId,
        String eventType,
        String actor,
        String subjectType,
        UUID subjectId,
        int version,
        UUID correlationId,
        Map<String, ?> payload
    ) {
        String actorType = actor.startsWith("SYSTEM:")
            ? "SERVICE" : "USER";
        jdbc.update("""
            INSERT INTO certification_domain_events
                (id, engagement_month_id, event_type, actor_type, actor_subject,
                 subject_type, subject_id, subject_version, correlation_id, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """, UUID.randomUUID(), monthId, eventType, actorType, actor,
            subjectType, subjectId, version, correlationId, json(payload));
    }

    private void bumpMonth(UUID monthId, String state) {
        if (state == null) {
            jdbc.update("""
                UPDATE engagement_months
                SET certification_version = certification_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, monthId);
        } else {
            jdbc.update("""
                UPDATE engagement_months
                SET certification_version = certification_version + 1,
                    state = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, state, monthId);
        }
    }

    private String requestHash(Object input) {
        return hasher.hash(Map.of(
            "schema", "f04-confirmation-api-request-v1",
            "request", input)).checksum();
    }

    private long expectedVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new IllegalArgumentException("If-Match is required.");
        }
        String normalized = ifMatch.trim();
        if (normalized.startsWith("W/")) {
            normalized = normalized.substring(2);
        }
        if (normalized.length() >= 2
            && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        try {
            return Long.parseLong(normalized);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "If-Match must contain a numeric version.", exception);
        }
    }

    private void requireMatchingVersion(long header, long body) {
        if (header != body) {
            throw new IllegalArgumentException(
                "If-Match and request expected version must match.");
        }
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 160) {
            throw new IllegalArgumentException(
                "Idempotency-Key is required and must not exceed 160 characters.");
        }
    }

    private DomainConflictException conflict(
        String code,
        String message,
        long currentVersion
    ) {
        return new DomainConflictException(code, message, currentVersion);
    }

    private String string(UUID value) {
        return value == null ? null : value.toString();
    }

    private OffsetDateTime storedOffset(OffsetDateTime value, int offsetSeconds) {
        return value == null ? null : value.withOffsetSameInstant(
            java.time.ZoneOffset.ofTotalSeconds(offsetSeconds));
    }

    private String nullableString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                "Unable to serialize confirmation facts.", exception);
        }
    }

    private Map<String, Object> map(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JacksonException exception) {
            throw new IllegalStateException(
                "Stored confirmation JSON is invalid.", exception);
        }
    }

    private List<UUID> uuidList(String value) {
        try {
            List<String> stored = objectMapper.readValue(
                value, new TypeReference<>() {
                });
            return stored.stream().map(UUID::fromString).toList();
        } catch (JacksonException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Stored governance action IDs are invalid.", exception);
        }
    }

    private record ConfirmationSources(
        UUID monthId,
        UUID engagementId,
        long monthVersion,
        UUID planVersionId,
        int planVersion,
        String planChecksum,
        UUID baselineId,
        String baselineChecksum,
        UUID summaryId,
        int summaryVersion,
        String summaryChecksum,
        UUID policyVersionId,
        UUID attendanceSnapshotId,
        Integer attendanceSnapshotVersion,
        String attendanceSnapshotChecksum
    ) {
    }

    private record PolicyRow(
        String quorumMode,
        int quorumRequired,
        int tokenTtlSeconds,
        int dueSeconds
    ) {
    }

    private record EligibleConfirmer(
        String subject,
        String email,
        UUID projectId,
        Map<String, Object> authority
    ) {
    }

    private record ConfirmationRequestRow(
        UUID id,
        UUID monthId,
        int version,
        String status,
        String scopeChecksum,
        String quorumMode,
        int quorumRequired,
        UUID policyVersionId,
        OffsetDateTime dueAt
    ) {
    }

    private record RequestViewRow(
        UUID id,
        UUID monthId,
        String engagementName,
        java.time.LocalDate monthStart,
        int version,
        String status,
        OffsetDateTime dueAt,
        int dueOffsetSeconds,
        OffsetDateTime createdAt,
        String scopeChecksum,
        String scopeManifest,
        String recipientSnapshot,
        String quorumMode,
        int quorumRequired,
        String transportStatus,
        UUID supersedesId,
        UUID policyVersionId
    ) {
    }

    private record EligibleAction(
        UUID eligibilityId,
        UUID projectId,
        int sequence,
        String roleReason
    ) {
    }

    private record ReviewedEvidence(
        UUID requestId,
        String senderAddressHash,
        String decision,
        String actionSource,
        String verificationStatus,
        String evidenceHash,
        OffsetDateTime representedAt
    ) {
    }

    private record EligibleReviewedAction(
        String actorSubject,
        String verifiedEmail,
        EligibleAction eligibility
    ) {
    }

    private record ActionFact(UUID id, String actorSubject) {
    }

    private record ScheduleRow(
        UUID id,
        UUID requestId,
        String type,
        OffsetDateTime dueAt,
        String status,
        String leaseOwner,
        ConfirmationRequestRow request
    ) {
    }

    private record TokenRow(
        UUID id,
        int requestVersion,
        String hash,
        String salt,
        int workFactor,
        OffsetDateTime expiresAt,
        OffsetDateTime consumedAt
    ) {
    }
}
