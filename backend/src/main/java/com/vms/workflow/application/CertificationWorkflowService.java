package com.vms.workflow.application;

import com.vms.workflow.api.CertificationDtos.BaselineView;
import com.vms.workflow.api.CertificationDtos.CertificationCriterionInput;
import com.vms.workflow.api.CertificationDtos.CertificationCriterionResult;
import com.vms.workflow.api.CertificationDtos.CertificationRequest;
import com.vms.workflow.api.CertificationDtos.CertificationSummaryView;
import com.vms.workflow.api.CertificationDtos.CertificationView;
import com.vms.workflow.api.CertificationDtos.ClarificationRequest;
import com.vms.workflow.api.CertificationDtos.ClarificationView;
import com.vms.workflow.api.CertificationDtos.CloseMonthInput;
import com.vms.workflow.api.CertificationDtos.ConfirmationHistoryItem;
import com.vms.workflow.api.CertificationDtos.ConfirmationPreviewView;
import com.vms.workflow.api.CertificationDtos.CriterionView;
import com.vms.workflow.api.CertificationDtos.DeliverableCertificationView;
import com.vms.workflow.api.CertificationDtos.LinearSnapshotView;
import com.vms.workflow.api.CertificationDtos.InvalidationResolutionInput;
import com.vms.workflow.api.CertificationDtos.InvalidationResolutionView;
import com.vms.workflow.api.CertificationDtos.MonthClosureView;
import com.vms.workflow.api.CertificationDtos.MonthCertificationView;
import com.vms.workflow.api.CertificationDtos.NotificationView;
import com.vms.workflow.api.CertificationDtos.ReopenRequestInput;
import com.vms.workflow.api.CertificationDtos.ReopenDecisionInput;
import com.vms.workflow.api.CertificationDtos.ReopenDecisionView;
import com.vms.workflow.api.CertificationDtos.RecipientDisplay;
import com.vms.workflow.api.CertificationDtos.SafeEvidenceReference;
import com.vms.workflow.api.CertificationDtos.SaveSubmissionRequest;
import com.vms.workflow.api.CertificationDtos.SubmissionCriterionInput;
import com.vms.workflow.api.CertificationDtos.SubmissionItemInput;
import com.vms.workflow.api.CertificationDtos.SubmissionItemView;
import com.vms.workflow.api.CertificationDtos.SubmissionView;
import com.vms.workflow.api.CertificationDtos.SummaryRequest;
import com.vms.workflow.api.CertificationDtos.TimelineEventView;
import com.vms.workflow.api.CertificationDtos.VendorCriterionResponse;
import com.vms.workflow.api.DomainConflictException;
import com.vms.workflow.application.CanonicalEvidenceHasher.HashResult;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class CertificationWorkflowService {
    private static final Set<String> TERMINAL_DECISIONS = Set.of(
        "ACCEPTED", "ACCEPTED_WITH_OBSERVATIONS", "PARTIALLY_ACCEPTED",
        "DEFERRED_CLIENT_DEPENDENCY", "DEFERRED_VENDOR_DEPENDENCY",
        "CLIENT_DEPENDENCY_DEFERRED", "VENDOR_DEPENDENCY_DEFERRED",
        "REJECTED", "CANCELLED_BY_APPROVED_CHANGE");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CertificationAuthorizationService authorization;
    private final CanonicalEvidenceHasher hasher;
    private final CertificationEmailAdapter emailAdapter;
    private final CertificationConfiguration configuration;
    private final CertificationReviewService reviews;
    private final CertificationHandoffService handoffs;
    private final Clock clock;

    public CertificationWorkflowService(
        JdbcTemplate jdbc,
        ObjectMapper objectMapper,
        CertificationAuthorizationService authorization,
        CanonicalEvidenceHasher hasher,
        CertificationEmailAdapter emailAdapter,
        CertificationConfiguration configuration,
        CertificationReviewService reviews,
        CertificationHandoffService handoffs,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.authorization = authorization;
        this.hasher = hasher;
        this.emailAdapter = emailAdapter;
        this.configuration = configuration;
        this.reviews = reviews;
        this.handoffs = handoffs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MonthCertificationView workspace(String subject, UUID monthId) {
        Scope scope = authorization.requireMonthRead(subject, monthId);
        return workspaceView(subject, monthId, scope);
    }

    @Transactional
    public MonthCertificationView saveSubmission(
        String subject,
        UUID monthId,
        SaveSubmissionRequest request,
        String ifMatch,
        String idempotencyKey
    ) {
        authorization.requireVendorSubmission(
            subject, monthId, CertificationAuthorizationService.SUBMISSION_MANAGE);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, request.expectedMonthVersion());
        String requestHash = requestHash(request);
        UUID prior = priorResult(
            subject, "SAVE_SUBMISSION", monthId, idempotencyKey, requestHash);
        if (prior != null) {
            return workspaceView(
                subject, monthId, authorization.requireMonthRead(subject, monthId));
        }
        MonthRow month = lockMonth(monthId);
        requireVersion(month.certificationVersion(), expected, "MONTH_VERSION_CONFLICT");
        requireFrozenBaseline(month);
        UUID policyId = ensurePolicy(month, subject);

        SubmissionRow current = currentSubmission(monthId);
        if (current != null && !"DRAFT".equals(current.status())) {
            throw conflict(
                "SUBMISSION_LOCKED",
                "Submitted vendor evidence is locked; use clarification or reopen lineage.",
                current.version());
        }
        int version = nextSubmissionVersion(monthId);
        UUID submissionId = UUID.randomUUID();
        if (current != null) {
            jdbc.update("""
                UPDATE delivery_submissions
                SET status = 'SUPERSEDED', optimistic_version = optimistic_version + 1
                WHERE id = ?
                """, current.id());
        }
        jdbc.update("""
            INSERT INTO delivery_submissions
                (id, engagement_month_id, plan_version_id, baseline_id,
                 policy_version_id, version, status, supersedes_id, summary,
                 vendor_declaration_accepted, declaration_text, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)
            """, submissionId, monthId, month.planVersionId(), month.baselineId(),
            policyId, version, current == null ? null : current.id(),
            request.summary(), request.declarationAccepted(),
            "Vendor declaration v1", subject);
        insertSubmissionItems(month, submissionId, request.items(), subject);
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        audit(monthId, "SUBMISSION_DRAFT_SAVED", subject, "delivery_submission",
            submissionId, version, "IN_APP", null, "SUCCESS", policyId, correlationId);
        event(monthId, "delivery.submission.draft-saved.v1", subject,
            "delivery_submission", submissionId, version, correlationId,
            Map.of("provider", "LOCAL", "baselineId", month.baselineId().toString()));
        recordIdempotency(subject, "SAVE_SUBMISSION", monthId, idempotencyKey,
            requestHash, "delivery_submission", submissionId);
        bumpMonth(monthId, null);
        return workspaceView(
            subject, monthId, authorization.requireMonthRead(subject, monthId));
    }

    @Transactional
    public MonthCertificationView submit(
        String subject,
        UUID submissionId,
        long bodyExpectedVersion,
        String ifMatch,
        String idempotencyKey
    ) {
        authorization.requireSubmission(
            subject, submissionId,
            CertificationAuthorizationService.SUBMISSION_SUBMIT, Party.VENDOR);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, bodyExpectedVersion);
        String requestHash = hasher.hash(Map.of(
            "submissionId", submissionId.toString(),
            "submissionVersion", bodyExpectedVersion)).checksum();
        UUID prior = priorResult(subject, "SUBMIT_DELIVERY", submissionId,
            idempotencyKey, requestHash);
        if (prior != null) {
            SubmissionRow replayed = submission(submissionId);
            return workspaceView(subject, replayed.monthId(),
                authorization.requireMonthRead(subject, replayed.monthId()));
        }
        SubmissionRow submission = lockSubmission(submissionId);
        if (expected != submission.version()) {
            throw conflict("SUBMISSION_VERSION_CONFLICT",
                "The submission version is stale.", submission.version());
        }
        MonthRow month = lockMonth(submission.monthId());
        if (!"DRAFT".equals(submission.status())) {
            throw conflict("SUBMISSION_LOCKED",
                "Only a draft submission can be submitted.", submission.version());
        }
        List<String> blockers = submissionBlockers(submissionId);
        if (!blockers.isEmpty()) {
            throw new DomainConflictException(
                "SUBMISSION_INCOMPLETE",
                "Submission completeness blockers: " + String.join("; ", blockers),
                (long) submission.version());
        }
        HashResult hash = submissionHash(submissionId);
        jdbc.update("""
            UPDATE delivery_submissions
            SET status = 'SUBMITTED', checksum = ?, submitted_at = CURRENT_TIMESTAMP,
                optimistic_version = optimistic_version + 1
            WHERE id = ?
            """, hash.checksum(), submissionId);
        UUID policyId = submission.policyVersionId();
        UUID roundId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO certification_rounds
                (id, engagement_month_id, submission_id, round_number,
                 status, policy_version_id)
            VALUES (?, ?, ?, 1, 'OPEN', ?)
            """, roundId, submission.monthId(), submissionId, policyId);
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        enqueueNotification(month, "DELIVERY_SUBMITTED", "delivery_submission",
            submissionId, submission.version(), "delivery-submitted-v1",
            "Delivery submission received", hash.checksum(), correlationId);
        audit(submission.monthId(), "DELIVERY_SUBMITTED", subject,
            "delivery_submission", submissionId, submission.version(), "IN_APP",
            null, "SUCCESS", policyId, correlationId);
        event(submission.monthId(), "delivery.submitted.v1", subject,
            "delivery_submission", submissionId, submission.version(), correlationId,
            Map.of("checksum", hash.checksum(), "hashSchemaVersion", hash.schemaVersion()));
        recordIdempotency(subject, "SUBMIT_DELIVERY", submissionId, idempotencyKey,
            requestHash, "delivery_submission", submissionId);
        bumpMonth(submission.monthId(), "DELIVERY_SUBMITTED");
        return workspaceView(subject, submission.monthId(),
            authorization.requireMonthRead(subject, submission.monthId()));
    }

    @Transactional
    public MonthCertificationView clarify(
        String subject,
        UUID submissionId,
        ClarificationRequest request,
        String ifMatch,
        String idempotencyKey
    ) {
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, request.expectedSubmissionVersion());
        boolean isQuestion = request.questions() != null && !request.questions().isEmpty();
        boolean isResponse = request.response() != null && !request.response().isBlank();
        if (isQuestion == isResponse) {
            throw new IllegalArgumentException(
                "Provide either clarification questions or one response.");
        }
        if (isQuestion) {
            authorization.requireItemDecision(
                subject, submissionId, request.deliverableId());
        } else {
            authorization.requireSubmission(
                subject, submissionId,
                CertificationAuthorizationService.SUBMISSION_MANAGE, Party.VENDOR);
        }
        String requestHash = requestHash(request);
        UUID prior = priorResult(subject, "CLARIFICATION", submissionId,
            idempotencyKey, requestHash);
        if (prior != null) {
            UUID replayMonthId = submissionMonthId(submissionId);
            return workspaceView(subject, replayMonthId,
                authorization.requireMonthRead(subject, replayMonthId));
        }
        SubmissionRow submission = lockSubmission(submissionId);
        if (expected != submission.version()) {
            throw conflict("SUBMISSION_VERSION_CONFLICT",
                "The submission version is stale.", submission.version());
        }
        CertificationRound round = lockCurrentRound(submissionId);
        requireOutcome(submissionId, request.deliverableId());
        UUID resultId;
        if (isQuestion) {
            int number = nextClarificationNumber(
                round.id(), request.deliverableId());
            resultId = null;
            for (String question : request.questions()) {
                UUID clarificationId = UUID.randomUUID();
                jdbc.update("""
                    INSERT INTO certification_clarifications
                        (id, round_id, submission_id, deliverable_version_id,
                         clarification_number, kind, message, sla_paused,
                         policy_snapshot, actor_subject)
                    VALUES (?, ?, ?, ?, ?, 'QUESTION', ?, TRUE, ?::jsonb, ?)
                    """, clarificationId, round.id(), submissionId,
                    request.deliverableId(), number++, question,
                    "{\"slaPause\":\"POLICY_CAPTURED\"}", subject);
                if (resultId == null) {
                    resultId = clarificationId;
                }
            }
            jdbc.update("""
                UPDATE certification_rounds
                SET status = 'AWAITING_CLARIFICATION'
                WHERE id = ? AND status = 'OPEN'
                """, round.id());
        } else {
            ClarificationParent parent = clarificationParent(
                round.id(), request.deliverableId(), request.clarificationId());
            resultId = UUID.randomUUID();
            int number = nextClarificationNumber(
                round.id(), request.deliverableId());
            jdbc.update("""
                INSERT INTO certification_clarifications
                    (id, round_id, submission_id, deliverable_version_id,
                     clarification_number, kind, parent_clarification_id,
                     message, sla_paused, policy_snapshot, actor_subject)
                VALUES (?, ?, ?, ?, ?, 'RESPONSE', ?, ?, FALSE, ?::jsonb, ?)
                """, resultId, round.id(), submissionId, request.deliverableId(),
                number, parent.id(), request.response(),
                "{\"slaResume\":\"POLICY_CAPTURED\"}", subject);
            int responseVersion = jdbc.queryForObject("""
                SELECT COALESCE(MAX(response_version), 0) + 1
                FROM delivery_submission_responses
                WHERE submission_id = ? AND deliverable_version_id = ?
                """, Integer.class, submissionId, request.deliverableId());
            jdbc.update("""
                INSERT INTO delivery_submission_responses
                    (id, submission_id, deliverable_version_id, response_version,
                     response_text, created_by_subject)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), submissionId, request.deliverableId(),
                responseVersion, request.response(), subject);
            jdbc.update("""
                UPDATE certification_rounds
                SET status = 'SUPERSEDED'
                WHERE id = ? AND status = 'AWAITING_CLARIFICATION'
                """, round.id());
            jdbc.update("""
                INSERT INTO certification_rounds
                    (id, engagement_month_id, submission_id, round_number,
                     status, supersedes_id, policy_version_id)
                VALUES (?, ?, ?, ?, 'OPEN', ?, ?)
                """, UUID.randomUUID(), submission.monthId(), submissionId,
                round.roundNumber() + 1, round.id(), submission.policyVersionId());
        }
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        MonthRow month = lockMonth(submission.monthId());
        enqueueNotification(month,
            isQuestion ? "CLARIFICATION_REQUESTED" : "CLARIFICATION_RESPONDED",
            "certification_clarification", resultId, round.roundNumber(),
            "certification-clarification-v1",
            isQuestion ? "Delivery clarification requested" : "Delivery clarification response",
            requestHash, correlationId);
        audit(submission.monthId(),
            isQuestion ? "CLARIFICATION_REQUESTED" : "CLARIFICATION_RESPONDED",
            subject, "certification_clarification", resultId, round.roundNumber(),
            "IN_APP", null, "SUCCESS", submission.policyVersionId(), correlationId);
        recordIdempotency(subject, "CLARIFICATION", submissionId, idempotencyKey,
            requestHash, "certification_clarification", resultId);
        bumpMonth(submission.monthId(), "DELIVERY_REVIEW");
        return workspaceView(subject, submission.monthId(),
            authorization.requireMonthRead(subject, submission.monthId()));
    }

    @Transactional
    public MonthCertificationView certify(
        String subject,
        UUID submissionId,
        CertificationRequest request,
        String ifMatch,
        String idempotencyKey
    ) {
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, request.expectedSubmissionVersion());
        authorization.requireItemDecision(
            subject, submissionId, request.deliverableId());
        String requestHash = requestHash(request);
        UUID prior = priorResult(subject, "CERTIFY_ITEM", submissionId,
            idempotencyKey, requestHash);
        if (prior != null) {
            UUID replayMonthId = submissionMonthId(submissionId);
            return workspaceView(subject, replayMonthId,
                authorization.requireMonthRead(subject, replayMonthId));
        }
        SubmissionRow submission = lockSubmission(submissionId);
        if (expected != submission.version()) {
            throw conflict("SUBMISSION_VERSION_CONFLICT",
                "The submission version is stale.", submission.version());
        }
        if (!Set.of("SUBMITTED", "UNDER_REVIEW").contains(submission.status())) {
            throw conflict("SUBMISSION_NOT_SUBMITTED",
                "Certification requires a locked submitted delivery.", submission.version());
        }
        CertificationRound round = lockCurrentRound(submissionId);
        if ("AWAITING_CLARIFICATION".equals(round.status())) {
            throw new DomainConflictException(
                "CLARIFICATION_RESPONSE_REQUIRED",
                "Certification is paused until the vendor provides an additive response.");
        }
        requireOutcome(submissionId, request.deliverableId());
        validateCertification(request);
        validateCriterionResults(request.deliverableId(), request.criterionResults());
        UUID certificationId = UUID.randomUUID();
        String storedDecision = storedDecision(request.decision());
        String authoritySnapshot = authoritySnapshot(
            subject, submission.monthId(), request.deliverableId(),
            CertificationAuthorizationService.ITEM_DECIDE);
        HashResult actionHash = hasher.hash(certificationActionManifest(
            submission, round, request, subject));
        jdbc.update("""
            INSERT INTO deliverable_certifications
                (id, round_id, submission_id, deliverable_version_id, decision,
                 comment, cause, next_action, observations, accepted_scope,
                 rejected_scope, aggregate_override_rationale,
                 baseline_checksum, submission_checksum, authority_snapshot,
                 source, decided_by_subject, action_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                    'IN_APP', ?, ?)
            """, certificationId, round.id(), submissionId, request.deliverableId(),
            storedDecision, request.comment(), request.cause(), request.nextAction(),
            request.observations(), request.acceptedScope(), request.rejectedScope(),
            request.overrideRationale(), submission.baselineChecksum(),
            submission.checksum(), authoritySnapshot, subject, actionHash.checksum());
        for (CertificationCriterionInput criterion : request.criterionResults()) {
            jdbc.update("""
                INSERT INTO certification_criterion_results
                    (id, certification_id, criterion_id, result, rationale,
                     evidence_viewed)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, UUID.randomUUID(), certificationId, criterion.criterionId(),
                storedCriterionDecision(criterion.decision()), criterion.rationale(),
                json(List.of(Map.of("viewed", criterion.evidenceViewed()))));
        }
        if ("PARTIALLY_ACCEPTED".equals(request.decision())) {
            createCarryForward(
                submission, request.deliverableId(), certificationId,
                request.carryForward(), request.cause(), subject);
        }
        if ("MORE_INFORMATION_REQUIRED".equals(request.decision())) {
            UUID clarificationId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO certification_clarifications
                    (id, round_id, submission_id, deliverable_version_id,
                     clarification_number, kind, message, requested_evidence,
                     sla_paused, policy_snapshot, actor_subject)
                VALUES (?, ?, ?, ?, ?, 'QUESTION', ?, '[]'::jsonb, TRUE,
                        ?::jsonb, ?)
                """, clarificationId, round.id(), submissionId,
                request.deliverableId(),
                nextClarificationNumber(round.id(), request.deliverableId()),
                request.comment(), "{\"slaPause\":\"POLICY_CAPTURED\"}", subject);
            jdbc.update("""
                UPDATE certification_rounds
                SET status = 'AWAITING_CLARIFICATION'
                WHERE id = ?
                """, round.id());
        } else if (allItemsTerminal(submissionId, round.id())) {
            jdbc.update("""
                UPDATE certification_rounds
                SET status = 'COMPLETED', completed_at = CURRENT_TIMESTAMP
                WHERE id = ? AND status = 'OPEN'
                """, round.id());
        }
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        audit(submission.monthId(), "DELIVERABLE_CERTIFIED", subject,
            "deliverable_certification", certificationId, round.roundNumber(),
            "IN_APP", request.comment(), "SUCCESS", submission.policyVersionId(),
            correlationId);
        event(submission.monthId(), "delivery.certified.v1", subject,
            "deliverable_certification", certificationId, round.roundNumber(),
            correlationId, Map.of(
                "decision", request.decision(),
                "linearStateCreatedDecision", false,
                "actionHash", actionHash.checksum()));
        if ("MORE_INFORMATION_REQUIRED".equals(request.decision())) {
            MonthRow month = lockMonth(submission.monthId());
            enqueueNotification(month, "CLARIFICATION_REQUESTED",
                "deliverable_certification", certificationId, round.roundNumber(),
                "certification-clarification-v1",
                "More delivery information required", actionHash.checksum(),
                correlationId);
        }
        recordIdempotency(subject, "CERTIFY_ITEM", submissionId, idempotencyKey,
            requestHash, "deliverable_certification", certificationId);
        bumpMonth(submission.monthId(), "DELIVERY_REVIEW");
        return workspaceView(subject, submission.monthId(),
            authorization.requireMonthRead(subject, submission.monthId()));
    }

    @Transactional
    public MonthCertificationView createSummary(
        String subject,
        UUID monthId,
        SummaryRequest request,
        String ifMatch,
        String idempotencyKey
    ) {
        authorization.requireMonthParty(subject, monthId,
            CertificationAuthorizationService.SUMMARY_CREATE, Party.CLIENT);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, request.expectedMonthVersion());
        String requestHash = requestHash(request);
        UUID prior = priorResult(subject, "CREATE_SUMMARY", monthId,
            idempotencyKey, requestHash);
        if (prior != null) {
            return workspaceView(subject, monthId,
                authorization.requireMonthRead(subject, monthId));
        }
        MonthRow month = lockMonth(monthId);
        requireVersion(month.certificationVersion(), expected, "MONTH_VERSION_CONFLICT");
        SubmissionRow submission = currentSubmission(monthId);
        if (submission == null || !"SUBMITTED".equals(submission.status())) {
            throw new DomainConflictException(
                "SUBMISSION_REQUIRED", "A submitted vendor delivery is required.");
        }
        CertificationRound round = latestRound(submission.id());
        if (round == null || !"COMPLETED".equals(round.status())
            || !allItemsTerminal(submission.id(), round.id())) {
            throw new DomainConflictException(
                "TERMINAL_CERTIFICATIONS_REQUIRED",
                "Every baseline deliverable requires an explicit terminal certification.");
        }
        SummaryManifest source = certificationSummaryManifest(
            month, submission, round, request);
        HashResult hash = hasher.hash(source.manifest());
        SummaryRow current = currentSummary(monthId);
        if (current != null && current.checksum().equals(hash.checksum())
            && current.decision().equals(request.decision())) {
            recordIdempotency(subject, "CREATE_SUMMARY", monthId, idempotencyKey,
                requestHash, "monthly_certification_summary", current.id());
            return workspaceView(subject, monthId,
                authorization.requireMonthRead(subject, monthId));
        }
        int version = current == null ? 1 : current.version() + 1;
        if (current != null) {
            jdbc.update("""
                UPDATE monthly_certification_summaries
                SET status = 'SUPERSEDED'
                WHERE id = ?
                """, current.id());
        }
        UUID summaryId = UUID.randomUUID();
        String authoritySnapshot = authoritySnapshot(
            subject, monthId, null, CertificationAuthorizationService.SUMMARY_CREATE);
        jdbc.update("""
            INSERT INTO monthly_certification_summaries
                (id, engagement_month_id, submission_id, round_id,
                 plan_version_id, baseline_id, policy_version_id, version,
                 status, supersedes_id, monthly_decision, observations, risks,
                 manifest, checksum, authority_snapshot, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CURRENT', ?, ?, ?, ?, ?::jsonb,
                    ?, ?::jsonb, ?)
            """, summaryId, monthId, submission.id(), round.id(),
            month.planVersionId(), month.baselineId(), submission.policyVersionId(),
            version, current == null ? null : current.id(), request.decision(),
            request.observations(), source.risks(), hash.canonicalJson(),
            hash.checksum(), authoritySnapshot, subject);
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        enqueueNotification(month, "CERTIFICATION_COMPLETED",
            "monthly_certification_summary", summaryId, version,
            "certification-summary-v1", "Monthly delivery certification completed",
            hash.checksum(), correlationId);
        audit(monthId, "CERTIFICATION_SUMMARY_CREATED", subject,
            "monthly_certification_summary", summaryId, version, "IN_APP",
            request.observations(), "SUCCESS", submission.policyVersionId(),
            correlationId);
        event(monthId, "certification.summary.created.v1", subject,
            "monthly_certification_summary", summaryId, version, correlationId,
            Map.of("checksum", hash.checksum(), "decision", request.decision()));
        recordIdempotency(subject, "CREATE_SUMMARY", monthId, idempotencyKey,
            requestHash, "monthly_certification_summary", summaryId);
        bumpMonth(monthId, null);
        return workspaceView(subject, monthId,
            authorization.requireMonthRead(subject, monthId));
    }

    @Transactional
    public MonthCertificationView requestReopen(
        String subject,
        UUID monthId,
        ReopenRequestInput request,
        String ifMatch,
        String idempotencyKey
    ) {
        authorization.requireMonthParty(subject, monthId,
            CertificationAuthorizationService.REOPEN_REQUEST, Party.CLIENT);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, request.expectedMonthVersion());
        String requestHash = requestHash(request);
        UUID prior = priorResult(subject, "REQUEST_REOPEN", monthId,
            idempotencyKey, requestHash);
        if (prior != null) {
            return workspaceView(subject, monthId,
                authorization.requireMonthRead(subject, monthId));
        }
        MonthRow month = lockMonth(monthId);
        requireVersion(month.certificationVersion(), expected, "MONTH_VERSION_CONFLICT");
        if (!Set.of("CONFIRMED", "CLOSED", "INVOICE_READY", "INVOICE_SUBMITTED")
            .contains(month.state())) {
            throw new DomainConflictException(
                "MONTH_NOT_REOPENABLE",
                "Only a confirmed or downstream month can enter reopen review.");
        }
        List<ReopenImpact> impacts = resolveReopenImpacts(
            monthId, request.impactedRecordIds());
        UUID closureId = jdbc.query("""
            SELECT id FROM month_closures
            WHERE engagement_month_id = ? AND status = 'CURRENT'
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, monthId);
        UUID reopenId = UUID.randomUUID();
        Map<String, Object> recipients = recipientSnapshot(month.planVersionId());
        jdbc.update("""
            INSERT INTO month_reopen_requests
                (id, engagement_month_id, closure_id, status, reason, category,
                 impacted_records, package_or_invoice_submitted,
                 recipient_snapshot, risk_statement, requested_by_subject,
                 idempotency_key)
            VALUES (?, ?, ?, 'REQUESTED', ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?)
            """, reopenId, monthId, closureId, request.reason(), request.category(),
            json(request.impactedRecordIds()), packageAffected(
                request.packageInvoiceImpact()), json(recipients),
            request.riskStatement(), subject, idempotencyKey);
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        for (ReopenImpact impacted : impacts) {
            jdbc.update("""
                INSERT INTO certification_invalidations
                    (id, engagement_month_id, reopen_request_id, object_type,
                     object_id, reason_code, status, correlation_id,
                     created_by_subject)
                VALUES (?, ?, ?, ?, ?, 'REOPEN_REQUESTED', 'ACTIVE', ?, ?)
                """, UUID.randomUUID(), monthId, reopenId,
                impacted.objectType(), impacted.objectId(),
                correlationId, subject);
        }
        enqueueNotification(month, "REOPEN_REQUESTED", "month_reopen_request",
            reopenId, 1, "month-reopen-v1", "Month reopen requested",
            requestHash, correlationId);
        audit(monthId, "MONTH_REOPEN_REQUESTED", subject, "month_reopen_request",
            reopenId, 1, "IN_APP", request.reason(), "REQUESTED",
            ensurePolicy(month, subject), correlationId);
        event(monthId, "month.reopen.requested.v1", subject,
            "month_reopen_request", reopenId, 1, correlationId,
            Map.of("category", request.category(), "f05ExecutionPerformed", false));
        recordIdempotency(subject, "REQUEST_REOPEN", monthId, idempotencyKey,
            requestHash, "month_reopen_request", reopenId);
        bumpMonth(monthId, "REOPEN_REQUESTED");
        return workspaceView(subject, monthId,
            authorization.requireMonthRead(subject, monthId));
    }

    @Transactional
    public MonthClosureView closeMonth(
        String subject,
        UUID monthId,
        CloseMonthInput request,
        String ifMatch,
        String idempotencyKey
    ) {
        authorization.requireMonthParty(
            subject, monthId, CertificationAuthorizationService.CLOSE,
            Party.CLIENT);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, request.expectedMonthVersion());
        String requestHash = requestHash(request);
        UUID prior = priorResult(
            subject, "CLOSE_MONTH", monthId, idempotencyKey, requestHash);
        if (prior != null) {
            return closureView(prior);
        }

        MonthRow month = lockMonth(monthId);
        requireVersion(
            month.certificationVersion(), expected, "MONTH_VERSION_CONFLICT");
        if (!"CONFIRMED".equals(month.state())) {
            throw new DomainConflictException(
                "MONTH_NOT_CONFIRMED",
                "Only a verified confirmed month can be closed.");
        }
        int activeInvalidations = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM effective_certification_invalidations
            WHERE engagement_month_id = ? AND effective_status = 'ACTIVE'
            """, Integer.class, monthId);
        if (activeInvalidations != 0) {
            throw new DomainConflictException(
                "ACTIVE_INVALIDATIONS",
                "Active invalidations must be resolved before month closure.");
        }
        ClosureSources sources = closureSources(monthId);
        if (sources == null || !sources.readyForF05()) {
            throw new DomainConflictException(
                "READY_FOR_F05_REQUIRED",
                "A current ready-for-F05 manifest is required before closure.");
        }
        ClosureLineage priorClosure = jdbc.query("""
            SELECT id, version, status
            FROM month_closures
            WHERE engagement_month_id = ?
            ORDER BY version DESC
            LIMIT 1
            """, rs -> rs.next()
                ? new ClosureLineage(
                    rs.getObject("id", UUID.class), rs.getInt("version"),
                    rs.getString("status"))
                : null, monthId);
        if (priorClosure != null && "CURRENT".equals(priorClosure.status())) {
            throw new DomainConflictException(
                "MONTH_ALREADY_CLOSED",
                "A current immutable closure already exists.");
        }

        int version = priorClosure == null ? 1 : priorClosure.version() + 1;
        Map<String, Object> manifest = closureManifest(
            month, sources, activeInvalidations);
        HashResult hash = hasher.hash(manifest);
        UUID closureId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO month_closures
                (id, engagement_month_id, version, confirmation_request_id,
                 manifest, manifest_hash, hash_schema_version, status,
                 supersedes_id, closed_by_subject)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, 'CURRENT', ?, ?)
            """, closureId, monthId, version, sources.requestId(),
            hash.canonicalJson(), hash.checksum(), hash.schemaVersion(),
            priorClosure == null ? null : priorClosure.id(), subject);
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        UUID auditReference = audit(
            monthId, "MONTH_CLOSED", subject, "month_closure", closureId,
            version, "IN_APP", "Verified F04 closure manifest", "CLOSED",
            sources.policyVersionId(), correlationId);
        event(
            monthId, "certification.month.closed.v1", subject,
            "month_closure", closureId, version, correlationId,
            Map.of(
                "manifestHash", hash.checksum(),
                "confirmationRequestId", sources.requestId(),
                "readinessRunId", sources.readinessRunId(),
                "auditReference", auditReference));
        recordIdempotency(
            subject, "CLOSE_MONTH", monthId, idempotencyKey, requestHash,
            "month_closure", closureId);
        bumpMonth(monthId, "CLOSED");
        return closureView(closureId);
    }

    @Transactional
    public ReopenDecisionView decideReopen(
        String subject,
        UUID reopenRequestId,
        ReopenDecisionInput request,
        String ifMatch,
        String idempotencyKey
    ) {
        ReopenSource initial = reopenSource(reopenRequestId, false);
        authorization.requireMonthParty(
            subject, initial.monthId(),
            CertificationAuthorizationService.REOPEN_APPROVE, Party.CLIENT);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, request.expectedMonthVersion());
        String requestHash = requestHash(request);
        UUID prior = priorResult(
            subject, "DECIDE_REOPEN", reopenRequestId,
            idempotencyKey, requestHash);
        if (prior != null) {
            return reopenDecisionView(prior);
        }

        MonthRow month = lockMonth(initial.monthId());
        requireVersion(
            month.certificationVersion(), expected, "MONTH_VERSION_CONFLICT");
        if (!"REOPEN_REQUESTED".equals(month.state())) {
            throw new DomainConflictException(
                "REOPEN_NOT_PENDING",
                "The month has no pending reopen decision.");
        }
        ReopenSource source = reopenSource(reopenRequestId, true);
        if (source.decisionId() != null) {
            throw new DomainConflictException(
                "REOPEN_ALREADY_DECIDED",
                "The reopen request already has an append-only decision.");
        }
        if (subject.equals(source.requestedBySubject())) {
            throw new DomainConflictException(
                "SECOND_APPROVER_REQUIRED",
                "A reopen request requires a distinct authorized approver.");
        }

        UUID decisionId = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        jdbc.update("""
            INSERT INTO month_reopen_decisions
                (id, reopen_request_id, decision, reasoning,
                 authority_snapshot, decided_by_subject, correlation_id)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
            """, decisionId, reopenRequestId, request.decision(),
            request.reasoning(), authoritySnapshot(
                subject, source.monthId(), null,
                CertificationAuthorizationService.REOPEN_APPROVE),
            subject, correlationId);
        String resultingState;
        if ("APPROVE".equals(request.decision())) {
            UUID reopenCorrelationId = correlationId;
            jdbc.update("""
                INSERT INTO f05_handoff_invalidations
                    (id, handoff_id, reopen_decision_id, reason_code,
                     invalidated_by_subject, correlation_id)
                SELECT gen_random_uuid(), handoff.id, ?,
                       'MONTH_REOPEN_APPROVED', ?, ?
                FROM f05_certification_handoffs handoff
                WHERE handoff.engagement_month_id = ?
                ON CONFLICT (handoff_id, reopen_decision_id) DO NOTHING
                """, decisionId, subject, reopenCorrelationId,
                source.monthId());
            List<F05InvalidationNotice> f05Invalidations = jdbc.query("""
                SELECT invalidation.id AS invalidation_id,
                       handoff.id AS handoff_id,
                       handoff.confirmation_request_id,
                       handoff.package_hash
                FROM f05_handoff_invalidations invalidation
                JOIN f05_certification_handoffs handoff
                  ON handoff.id = invalidation.handoff_id
                WHERE invalidation.reopen_decision_id = ?
                ORDER BY handoff.id
                """, (rs, rowNum) -> new F05InvalidationNotice(
                    rs.getObject("invalidation_id", UUID.class),
                    rs.getObject("handoff_id", UUID.class),
                    rs.getObject("confirmation_request_id", UUID.class),
                    rs.getString("package_hash")), decisionId);
            for (F05InvalidationNotice invalidation : f05Invalidations) {
                event(
                    source.monthId(),
                    "certification.f05-handoff.invalidated.v1", subject,
                    "f05_handoff_invalidation", invalidation.invalidationId(),
                    1, correlationId, Map.of(
                        "contractVersion",
                            "certification.confirmation.readiness-invalidation.v1",
                        "handoffId", invalidation.handoffId(),
                        "confirmationRequestId",
                            invalidation.confirmationRequestId(),
                        "packageHash", invalidation.packageHash(),
                        "requiredConsumerAction",
                            "REVOKE_OR_COMPENSATE_BEFORE_DOWNSTREAM_USE"));
            }
            jdbc.update("""
                UPDATE month_closures
                SET status = 'SUPERSEDED'
                WHERE id = ? AND status = 'CURRENT'
                """, source.closureId());
            jdbc.update("""
                UPDATE business_confirmation_requests
                SET status = 'SUPERSEDED',
                    optimistic_version = optimistic_version + 1
                WHERE engagement_month_id = ?
                  AND status = 'CONFIRMED'
                """, source.monthId());
            resultingState = "REOPENED";
        } else {
            jdbc.update("""
                INSERT INTO certification_invalidation_resolutions
                    (id, invalidation_id, resolution, reasoning,
                     evidence_manifest, resolved_by_subject, correlation_id)
                SELECT gen_random_uuid(), invalidation.id, 'SUPERSEDED',
                       'The reopen request was rejected: ' || ?,
                       jsonb_build_object(
                           'schema', 'f04-reopen-rejection-resolution-v1',
                           'reopenRequestId', ?::text,
                           'reopenDecisionId', ?::text,
                           'invalidatedObjectType', invalidation.object_type,
                           'invalidatedObjectId', invalidation.object_id::text
                       ),
                       ?, ?
                FROM certification_invalidations invalidation
                WHERE invalidation.reopen_request_id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM certification_invalidation_resolutions resolution
                      WHERE resolution.invalidation_id = invalidation.id
                  )
                """, request.reasoning(), reopenRequestId, decisionId,
                subject, correlationId, reopenRequestId);
            resultingState = source.closureId() == null
                ? "CONFIRMED" : "CLOSED";
        }
        UUID auditReference = audit(
            source.monthId(), "MONTH_REOPEN_DECIDED", subject,
            "month_reopen_decision", decisionId, 1, "IN_APP",
            request.reasoning(), request.decision(), source.policyVersionId(),
            correlationId);
        event(
            source.monthId(), "certification.month.reopen-decided.v1", subject,
            "month_reopen_decision", decisionId, 1, correlationId,
            Map.of(
                "decision", request.decision(),
                "reopenRequestId", reopenRequestId,
                "auditReference", auditReference));
        recordIdempotency(
            subject, "DECIDE_REOPEN", reopenRequestId, idempotencyKey,
            requestHash, "month_reopen_decision", decisionId);
        bumpMonth(source.monthId(), resultingState);
        return reopenDecisionView(decisionId);
    }

    @Transactional
    public InvalidationResolutionView resolveInvalidation(
        String subject,
        UUID invalidationId,
        InvalidationResolutionInput request,
        String ifMatch,
        String idempotencyKey
    ) {
        InvalidationSource initial = invalidationSource(invalidationId, false);
        authorization.requireMonthParty(
            subject, initial.monthId(),
            CertificationAuthorizationService.REOPEN_APPROVE, Party.CLIENT);
        requireIdempotencyKey(idempotencyKey);
        long expected = expectedVersion(ifMatch);
        requireMatchingVersion(expected, request.expectedMonthVersion());
        String requestHash = requestHash(request);
        UUID prior = priorResult(
            subject, "RESOLVE_INVALIDATION", invalidationId,
            idempotencyKey, requestHash);
        if (prior != null) {
            return invalidationResolutionView(prior);
        }

        MonthRow month = lockMonth(initial.monthId());
        requireVersion(
            month.certificationVersion(), expected, "MONTH_VERSION_CONFLICT");
        InvalidationSource source = invalidationSource(invalidationId, true);
        if (source.resolutionId() != null) {
            throw new DomainConflictException(
                "INVALIDATION_ALREADY_RESOLVED",
                "The invalidation already has an append-only resolution.");
        }
        if ("SUPERSEDED".equals(request.resolution())) {
            throw new DomainConflictException(
                "EXACT_CORRECTION_REQUIRED",
                "Only rejection of the originating reopen can supersede its "
                    + "invalidation; an approved correction must be cleared "
                    + "by its exact confirmed successor.");
        }
        CorrectionEvidence correction = correctionEvidence(source);
        if ("CLEARED".equals(request.resolution()) && correction == null) {
            throw new DomainConflictException(
                "RECERTIFICATION_REQUIRED",
                "A later verified confirmation is required to clear this invalidation.");
        }
        Map<String, Object> evidence = correction == null
            ? Map.of("resolutionBasis", "Explicit supersession")
            : Map.of(
                "schema", "f04-exact-correction-resolution-v1",
                "invalidatedObjectType", source.objectType(),
                "invalidatedObjectId", source.objectId(),
                "correctedObjectType", correction.objectType(),
                "correctedObjectId", correction.objectId(),
                "correctedObjectVersion", correction.objectVersion(),
                "confirmationRequestId", correction.requestId(),
                "confirmationScopeChecksum", correction.scopeChecksum(),
                "confirmedAt", correction.confirmedAt());
        UUID resolutionId = UUID.randomUUID();
        UUID correlationId = CorrelationIdFilter.currentOrNew();
        jdbc.update("""
            INSERT INTO certification_invalidation_resolutions
                (id, invalidation_id, resolution, reasoning,
                 evidence_manifest, corrected_object_type,
                 corrected_object_id, corrected_object_version,
                 resolved_by_subject, correlation_id)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            """, resolutionId, invalidationId, request.resolution(),
            request.reasoning(), json(evidence),
            correction == null ? null : correction.objectType(),
            correction == null ? null : correction.objectId(),
            correction == null ? null : correction.objectVersion(),
            subject, correlationId);
        UUID auditReference = audit(
            source.monthId(), "CERTIFICATION_INVALIDATION_RESOLVED", subject,
            "certification_invalidation_resolution", resolutionId, 1,
            "IN_APP", request.reasoning(), request.resolution(),
            source.policyVersionId(), correlationId);
        event(
            source.monthId(), "certification.invalidation.resolved.v1", subject,
            "certification_invalidation_resolution", resolutionId, 1,
            correlationId, Map.of(
                "invalidationId", invalidationId,
                "resolution", request.resolution(),
                "auditReference", auditReference));
        recordIdempotency(
            subject, "RESOLVE_INVALIDATION", invalidationId, idempotencyKey,
            requestHash, "certification_invalidation_resolution", resolutionId);
        bumpMonth(source.monthId(), null);
        handoffs.publishConfirmedIfReady(
            subject, source.monthId(), correlationId);
        return invalidationResolutionView(resolutionId);
    }

    private ClosureSources closureSources(UUID monthId) {
        return jdbc.query("""
            SELECT request.id AS request_id, request.version AS request_version,
                   request.scope_checksum, request.policy_version_id,
                   request.attendance_snapshot_id, request.plan_version_id,
                   request.baseline_id, request.certification_summary_id,
                   summary.version AS summary_version,
                   summary.checksum AS summary_checksum,
                   summary.submission_id, submission.checksum AS submission_checksum,
                   baseline.checksum AS baseline_checksum,
                   exception.id AS attendance_exception_id,
                   readiness.id AS readiness_run_id,
                   readiness.input_hash AS readiness_input_hash,
                   readiness.ready_for_f05_handoff,
                   handoff.id AS handoff_id, handoff.package_hash
            FROM business_confirmation_requests request
            JOIN monthly_certification_summaries summary
              ON summary.id = request.certification_summary_id
            JOIN delivery_submissions submission
              ON submission.id = summary.submission_id
            JOIN delivery_plan_baselines baseline
              ON baseline.id = request.baseline_id
            LEFT JOIN certification_attendance_exceptions exception
              ON exception.engagement_month_id = request.engagement_month_id
             AND exception.policy_version_id = request.policy_version_id
            JOIN LATERAL (
                SELECT run.id, run.input_hash, run.ready_for_f05_handoff
                FROM certification_readiness_runs run
                WHERE run.engagement_month_id = request.engagement_month_id
                  AND run.ready_for_f05_handoff
                ORDER BY run.evaluated_at DESC
                LIMIT 1
            ) readiness ON TRUE
            LEFT JOIN LATERAL (
                SELECT value.id, value.package_hash
                FROM f05_certification_handoffs value
                WHERE value.confirmation_request_id = request.id
                ORDER BY value.created_at DESC
                LIMIT 1
            ) handoff ON TRUE
            WHERE request.engagement_month_id = ?
              AND request.status = 'CONFIRMED'
            ORDER BY request.version DESC
            LIMIT 1
            """, rs -> rs.next() ? new ClosureSources(
                rs.getObject("request_id", UUID.class),
                rs.getInt("request_version"),
                rs.getString("scope_checksum"),
                rs.getObject("policy_version_id", UUID.class),
                rs.getObject("attendance_snapshot_id", UUID.class),
                rs.getObject("attendance_exception_id", UUID.class),
                rs.getObject("plan_version_id", UUID.class),
                rs.getObject("baseline_id", UUID.class),
                rs.getString("baseline_checksum"),
                rs.getObject("submission_id", UUID.class),
                rs.getString("submission_checksum"),
                rs.getObject("certification_summary_id", UUID.class),
                rs.getInt("summary_version"),
                rs.getString("summary_checksum"),
                rs.getObject("readiness_run_id", UUID.class),
                rs.getString("readiness_input_hash"),
                rs.getBoolean("ready_for_f05_handoff"),
                rs.getObject("handoff_id", UUID.class),
                rs.getString("package_hash"))
                : null, monthId);
    }

    private Map<String, Object> closureManifest(
        MonthRow month,
        ClosureSources source,
        int activeInvalidations
    ) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "f04-month-closure-v1");
        manifest.put("monthId", month.id().toString());
        manifest.put("monthVersion", month.certificationVersion());
        manifest.put("policyVersionId", source.policyVersionId().toString());
        manifest.put("attendanceSnapshotId", uuidString(source.attendanceSnapshotId()));
        manifest.put("attendanceExceptionId", uuidString(source.attendanceExceptionId()));
        manifest.put("planVersionId", source.planVersionId().toString());
        manifest.put("baselineId", source.baselineId().toString());
        manifest.put("baselineChecksum", source.baselineChecksum());
        manifest.put("submissionId", source.submissionId().toString());
        manifest.put("submissionChecksum", source.submissionChecksum());
        manifest.put("summaryId", source.summaryId().toString());
        manifest.put("summaryVersion", source.summaryVersion());
        manifest.put("summaryChecksum", source.summaryChecksum());
        manifest.put("confirmationRequestId", source.requestId().toString());
        manifest.put("confirmationVersion", source.requestVersion());
        manifest.put("confirmationScopeChecksum", source.scopeChecksum());
        manifest.put("readinessRunId", source.readinessRunId().toString());
        manifest.put("readinessInputHash", source.readinessInputHash());
        manifest.put("activeInvalidationCount", activeInvalidations);
        manifest.put("f05HandoffId", uuidString(source.handoffId()));
        manifest.put("f05PackageHash", source.f05PackageHash());
        return manifest;
    }

    private MonthClosureView closureView(UUID closureId) {
        MonthClosureView value = jdbc.query("""
            SELECT id, engagement_month_id, version, confirmation_request_id,
                   manifest_hash, status, closed_at, supersedes_id
            FROM month_closures
            WHERE id = ?
            """, rs -> rs.next() ? new MonthClosureView(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getInt("version"),
                rs.getObject("confirmation_request_id", UUID.class),
                rs.getString("manifest_hash"), rs.getString("status"),
                rs.getObject("closed_at", OffsetDateTime.class),
                rs.getObject("supersedes_id", UUID.class))
                : null, closureId);
        if (value == null) {
            throw notFound();
        }
        return value;
    }

    private ReopenSource reopenSource(UUID reopenRequestId, boolean lock) {
        ReopenSource value = jdbc.query("""
            SELECT request.id, request.engagement_month_id, request.closure_id,
                   request.requested_by_subject, decision.id AS decision_id,
                   (
                       SELECT confirmation.policy_version_id
                       FROM business_confirmation_requests confirmation
                       WHERE confirmation.engagement_month_id =
                           request.engagement_month_id
                       ORDER BY confirmation.version DESC
                       LIMIT 1
                   ) AS policy_version_id
            FROM month_reopen_requests request
            LEFT JOIN month_reopen_decisions decision
              ON decision.reopen_request_id = request.id
            WHERE request.id = ?
            """ + (lock ? " FOR UPDATE OF request" : ""),
            rs -> rs.next() ? new ReopenSource(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getObject("closure_id", UUID.class),
                rs.getString("requested_by_subject"),
                rs.getObject("decision_id", UUID.class),
                rs.getObject("policy_version_id", UUID.class))
                : null, reopenRequestId);
        if (value == null) {
            throw notFound();
        }
        return value;
    }

    private ReopenDecisionView reopenDecisionView(UUID decisionId) {
        ReopenDecisionView value = jdbc.query("""
            SELECT decision.id, decision.reopen_request_id,
                   request.engagement_month_id, decision.decision,
                   decision.reasoning, profile.display_name,
                   decision.decided_by_subject, decision.decided_at,
                   audit.id AS audit_reference
            FROM month_reopen_decisions decision
            JOIN month_reopen_requests request
              ON request.id = decision.reopen_request_id
            LEFT JOIN user_profiles profile
              ON profile.identity_subject = decision.decided_by_subject
            LEFT JOIN LATERAL (
                SELECT event.id
                FROM certification_audit_events event
                WHERE event.object_type = 'month_reopen_decision'
                  AND event.object_id = decision.id
                ORDER BY event.occurred_at DESC
                LIMIT 1
            ) audit ON TRUE
            WHERE decision.id = ?
            """, rs -> rs.next() ? new ReopenDecisionView(
                rs.getObject("id", UUID.class),
                rs.getObject("reopen_request_id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getString("decision"), rs.getString("reasoning"),
                rs.getString("display_name") == null
                    ? rs.getString("decided_by_subject")
                    : rs.getString("display_name"),
                rs.getObject("decided_at", OffsetDateTime.class),
                rs.getObject("audit_reference", UUID.class))
                : null, decisionId);
        if (value == null) {
            throw notFound();
        }
        return value;
    }

    private InvalidationSource invalidationSource(
        UUID invalidationId,
        boolean lock
    ) {
        InvalidationSource value = jdbc.query("""
            SELECT invalidation.id, invalidation.engagement_month_id,
                   invalidation.object_type, invalidation.object_id,
                   invalidation.created_at, resolution.id AS resolution_id,
                   (
                       SELECT confirmation.policy_version_id
                       FROM business_confirmation_requests confirmation
                       WHERE confirmation.engagement_month_id =
                           invalidation.engagement_month_id
                       ORDER BY confirmation.version DESC
                       LIMIT 1
                   ) AS policy_version_id
            FROM certification_invalidations invalidation
            LEFT JOIN certification_invalidation_resolutions resolution
              ON resolution.invalidation_id = invalidation.id
            WHERE invalidation.id = ?
            """ + (lock ? " FOR UPDATE OF invalidation" : ""),
            rs -> rs.next() ? new InvalidationSource(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getString("object_type"),
                rs.getObject("object_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("resolution_id", UUID.class),
                rs.getObject("policy_version_id", UUID.class))
                : null, invalidationId);
        if (value == null) {
            throw notFound();
        }
        return value;
    }

    private CorrectionEvidence correctionEvidence(InvalidationSource source) {
        String sql = switch (source.objectType()) {
            case "ATTENDANCE_SNAPSHOT_VERSION" -> """
                SELECT corrected.id AS object_id,
                       corrected.version AS object_version,
                       request.id AS request_id, request.scope_checksum,
                       request.completed_at
                FROM attendance_snapshot_versions corrected
                JOIN business_confirmation_requests request
                  ON request.attendance_snapshot_id = corrected.id
                WHERE corrected.supersedes_id = ?
                  AND corrected.engagement_month_id = ?
                  AND request.engagement_month_id = ?
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > ?
                ORDER BY request.version DESC
                LIMIT 1
                """;
            case "DELIVERY_PLAN_VERSION" -> """
                SELECT corrected.id AS object_id,
                       corrected.version AS object_version,
                       request.id AS request_id, request.scope_checksum,
                       request.completed_at
                FROM delivery_plan_versions corrected
                JOIN delivery_plans plan ON plan.id = corrected.plan_id
                JOIN business_confirmation_requests request
                  ON request.plan_version_id = corrected.id
                WHERE corrected.prior_version_id = ?
                  AND plan.engagement_month_id = ?
                  AND request.engagement_month_id = ?
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > ?
                ORDER BY request.version DESC
                LIMIT 1
                """;
            case "DELIVERY_PLAN_BASELINE" -> """
                SELECT corrected.id AS object_id,
                       version.version AS object_version,
                       request.id AS request_id, request.scope_checksum,
                       request.completed_at
                FROM delivery_plan_baselines corrected
                JOIN delivery_plan_versions version
                  ON version.id = corrected.plan_version_id
                JOIN delivery_plans plan ON plan.id = version.plan_id
                JOIN business_confirmation_requests request
                  ON request.baseline_id = corrected.id
                WHERE corrected.original_baseline_id = ?
                  AND plan.engagement_month_id = ?
                  AND request.engagement_month_id = ?
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > ?
                ORDER BY request.version DESC
                LIMIT 1
                """;
            case "DELIVERY_SUBMISSION" -> """
                SELECT corrected.id AS object_id,
                       corrected.version AS object_version,
                       request.id AS request_id, request.scope_checksum,
                       request.completed_at
                FROM delivery_submissions corrected
                JOIN monthly_certification_summaries summary
                  ON summary.submission_id = corrected.id
                JOIN business_confirmation_requests request
                  ON request.certification_summary_id = summary.id
                WHERE corrected.supersedes_id = ?
                  AND corrected.engagement_month_id = ?
                  AND request.engagement_month_id = ?
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > ?
                ORDER BY request.version DESC
                LIMIT 1
                """;
            case "CERTIFICATION_ROUND" -> """
                SELECT corrected.id AS object_id,
                       corrected.round_number AS object_version,
                       request.id AS request_id, request.scope_checksum,
                       request.completed_at
                FROM certification_rounds corrected
                JOIN monthly_certification_summaries summary
                  ON summary.round_id = corrected.id
                JOIN business_confirmation_requests request
                  ON request.certification_summary_id = summary.id
                WHERE corrected.supersedes_id = ?
                  AND corrected.engagement_month_id = ?
                  AND request.engagement_month_id = ?
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > ?
                ORDER BY request.version DESC
                LIMIT 1
                """;
            case "MONTHLY_CERTIFICATION_SUMMARY" -> """
                SELECT corrected.id AS object_id,
                       corrected.version AS object_version,
                       request.id AS request_id, request.scope_checksum,
                       request.completed_at
                FROM monthly_certification_summaries corrected
                JOIN business_confirmation_requests request
                  ON request.certification_summary_id = corrected.id
                WHERE corrected.supersedes_id = ?
                  AND corrected.engagement_month_id = ?
                  AND request.engagement_month_id = ?
                  AND request.status = 'CONFIRMED'
                  AND request.completed_at > ?
                ORDER BY request.version DESC
                LIMIT 1
                """;
            case "BUSINESS_CONFIRMATION_REQUEST" -> """
                SELECT corrected.id AS object_id,
                       corrected.version AS object_version,
                       corrected.id AS request_id, corrected.scope_checksum,
                       corrected.completed_at
                FROM business_confirmation_requests corrected
                WHERE corrected.supersedes_id = ?
                  AND corrected.engagement_month_id = ?
                  AND corrected.engagement_month_id = ?
                  AND corrected.status = 'CONFIRMED'
                  AND corrected.completed_at > ?
                ORDER BY corrected.version DESC
                LIMIT 1
                """;
            default -> null;
        };
        if (sql == null) {
            return null;
        }
        return jdbc.query(sql, rs -> rs.next() ? new CorrectionEvidence(
            source.objectType(),
            rs.getObject("object_id", UUID.class),
            rs.getInt("object_version"),
            rs.getObject("request_id", UUID.class),
            rs.getString("scope_checksum"),
            rs.getObject("completed_at", OffsetDateTime.class))
            : null, source.objectId(), source.monthId(), source.monthId(),
            source.createdAt());
    }

    private List<ReopenImpact> resolveReopenImpacts(
        UUID monthId,
        List<UUID> impactedRecordIds
    ) {
        if (new LinkedHashSet<>(impactedRecordIds).size()
            != impactedRecordIds.size()) {
            throw new IllegalArgumentException(
                "Reopen impactedRecordIds must be distinct.");
        }
        List<ReopenImpact> impacts = new ArrayList<>();
        for (UUID objectId : impactedRecordIds) {
            List<ReopenImpact> matches = jdbc.query("""
                SELECT fact.object_type, fact.object_id
                FROM (
                    SELECT 'ATTENDANCE_SNAPSHOT_VERSION' AS object_type,
                           snapshot.id AS object_id
                    FROM attendance_snapshot_versions snapshot
                    WHERE snapshot.id = ?
                      AND snapshot.engagement_month_id = ?
                    UNION ALL
                    SELECT 'DELIVERY_PLAN_VERSION', version.id
                    FROM delivery_plan_versions version
                    JOIN delivery_plans plan ON plan.id = version.plan_id
                    WHERE version.id = ?
                      AND plan.engagement_month_id = ?
                    UNION ALL
                    SELECT 'DELIVERY_PLAN_BASELINE', baseline.id
                    FROM delivery_plan_baselines baseline
                    JOIN delivery_plan_versions version
                      ON version.id = baseline.plan_version_id
                    JOIN delivery_plans plan ON plan.id = version.plan_id
                    WHERE baseline.id = ?
                      AND plan.engagement_month_id = ?
                    UNION ALL
                    SELECT 'DELIVERY_SUBMISSION', submission.id
                    FROM delivery_submissions submission
                    WHERE submission.id = ?
                      AND submission.engagement_month_id = ?
                    UNION ALL
                    SELECT 'CERTIFICATION_ROUND', round.id
                    FROM certification_rounds round
                    WHERE round.id = ?
                      AND round.engagement_month_id = ?
                    UNION ALL
                    SELECT 'MONTHLY_CERTIFICATION_SUMMARY', summary.id
                    FROM monthly_certification_summaries summary
                    WHERE summary.id = ?
                      AND summary.engagement_month_id = ?
                    UNION ALL
                    SELECT 'BUSINESS_CONFIRMATION_REQUEST', request.id
                    FROM business_confirmation_requests request
                    WHERE request.id = ?
                      AND request.engagement_month_id = ?
                ) fact
                """, (rs, rowNum) -> new ReopenImpact(
                    rs.getString("object_type"),
                    rs.getObject("object_id", UUID.class)),
                objectId, monthId, objectId, monthId, objectId, monthId,
                objectId, monthId, objectId, monthId, objectId, monthId,
                objectId, monthId);
            if (matches.size() != 1) {
                throw new DomainConflictException(
                    "REOPEN_IMPACT_OUT_OF_SCOPE",
                    "Every reopen impact must identify one supported fact "
                        + "from the same engagement month.");
            }
            impacts.add(matches.getFirst());
        }
        return List.copyOf(impacts);
    }

    private InvalidationResolutionView invalidationResolutionView(
        UUID resolutionId
    ) {
        InvalidationResolutionView value = jdbc.query("""
            SELECT resolution.id, resolution.invalidation_id,
                   invalidation.engagement_month_id, resolution.resolution,
                   resolution.reasoning, profile.display_name,
                   resolution.resolved_by_subject, resolution.resolved_at,
                   audit.id AS audit_reference
            FROM certification_invalidation_resolutions resolution
            JOIN certification_invalidations invalidation
              ON invalidation.id = resolution.invalidation_id
            LEFT JOIN user_profiles profile
              ON profile.identity_subject = resolution.resolved_by_subject
            LEFT JOIN LATERAL (
                SELECT event.id
                FROM certification_audit_events event
                WHERE event.object_type =
                    'certification_invalidation_resolution'
                  AND event.object_id = resolution.id
                ORDER BY event.occurred_at DESC
                LIMIT 1
            ) audit ON TRUE
            WHERE resolution.id = ?
            """, rs -> rs.next() ? new InvalidationResolutionView(
                rs.getObject("id", UUID.class),
                rs.getObject("invalidation_id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getString("resolution"), rs.getString("reasoning"),
                rs.getString("display_name") == null
                    ? rs.getString("resolved_by_subject")
                    : rs.getString("display_name"),
                rs.getObject("resolved_at", OffsetDateTime.class),
                rs.getObject("audit_reference", UUID.class))
                : null, resolutionId);
        if (value == null) {
            throw notFound();
        }
        return value;
    }

    private MonthCertificationView workspaceView(
        String subject,
        UUID monthId,
        Scope scope
    ) {
        MonthRow month = month(monthId);
        BaselineView baseline = new BaselineView(
            month.baselineId(), month.planVersionId(), month.baselineChecksum(),
            "FROZEN".equals(month.planState()));
        SubmissionRow submission = currentSubmission(monthId);
        List<DeliverableCertificationView> deliverables =
            deliverableViews(subject, submission, month, scope);
        SubmissionView submissionView = submission == null
            ? null : submissionView(submission, deliverables);
        Set<UUID> visibleDeliverables = deliverables.stream()
            .map(DeliverableCertificationView::id)
            .collect(java.util.stream.Collectors.toSet());
        OffsetDateTime evaluatedAt = jdbc.query("""
            SELECT MAX(evaluated_at)
            FROM certification_readiness_runs
            WHERE engagement_month_id = ?
            """, rs -> rs.next()
                ? rs.getObject(1, OffsetDateTime.class) : null, monthId);
        if (evaluatedAt == null) {
            evaluatedAt = OffsetDateTime.now(clock);
        }
        boolean fullMonth = scope.allProjects();
        var permissions = authorization.permissions(subject, monthId);
        return new MonthCertificationView(
            month.id(), month.engagementId(),
            month.monthStart().getMonth().getDisplayName(
                java.time.format.TextStyle.FULL, Locale.ENGLISH)
                + " " + month.monthStart().getYear(),
            month.state(), month.certificationVersion(), false,
            Set.of("CLOSED", "REOPEN_REQUESTED").contains(month.state()),
            evaluatedAt, baseline, permissions,
            fullMonth ? availableEvidenceChoices(month) : List.of(),
            submissionView == null || fullMonth ? submissionView
                : new SubmissionView(
                    submissionView.id(), submissionView.version(),
                    submissionView.status(), "Restricted project-scoped submission",
                    submissionView.declarationAccepted(),
                    submissionView.completenessBlockers(),
                    submissionView.autosavedAt(), submissionView.submittedAt(),
                    submissionView.locked(), submissionView.items()),
            deliverables,
            submission == null ? List.of() : clarificationViews(submission.id()).stream()
                .filter(value -> visibleDeliverables.contains(value.deliverableId()))
                .toList(),
            fullMonth ? summaryView(monthId) : null,
            fullMonth ? linearSnapshotViews(month.planVersionId()) : List.of(),
            fullMonth ? confirmationPreview(month, submission) : null,
            fullMonth ? confirmationHistory(monthId) : List.of(),
            fullMonth ? notificationViews(monthId, null) : List.of(),
            fullMonth ? timeline(monthId) : List.of(),
            fullMonth
                ? reviews.items(subject, monthId, permissions.canReviewInbound())
                : List.of());
    }

    private List<DeliverableCertificationView> deliverableViews(
        String subject,
        SubmissionRow submission,
        MonthRow month,
        Scope scope
    ) {
        Set<UUID> visibleProjects = new HashSet<>(scope.projectIds());
        List<DeliverableRow> rows = jdbc.query("""
            SELECT deliverable.id, stable.deliverable_code, deliverable.title,
                   project.name AS project_name, deliverable.project_id,
                   deliverable.product_owner_subject,
                   deliverable.description, deliverable.business_objective,
                   deliverable.evidence_expectations
            FROM delivery_deliverable_versions deliverable
            JOIN delivery_deliverables stable ON stable.id = deliverable.deliverable_id
            JOIN projects project ON project.id = deliverable.project_id
            WHERE deliverable.plan_version_id = ?
            ORDER BY stable.deliverable_code
            LIMIT 201
            """, (rs, rowNum) -> new DeliverableRow(
                rs.getObject("id", UUID.class),
                rs.getString("deliverable_code"),
                rs.getString("title"),
                rs.getString("project_name"),
                rs.getObject("project_id", UUID.class),
                rs.getString("product_owner_subject"),
                rs.getString("description"),
                rs.getString("business_objective"),
                rs.getString("evidence_expectations")
            ), month.planVersionId());
        if (rows.size() > 200) {
            throw new DomainConflictException(
                "WORKSPACE_SIZE_EXCEEDED",
                "The month exceeds the supported 200-deliverable workspace limit.");
        }
        WorkspaceHydration hydration = workspaceHydration(
            month.planVersionId(), submission);
        Set<UUID> decisionProjects = authorization.authorizedProjectIds(
            subject, month.engagementId(),
            CertificationAuthorizationService.ITEM_DECIDE, Party.CLIENT);
        int reviewSlaSeconds = reviewSlaSeconds(
            submission == null ? null : submission.policyVersionId());
        OffsetDateTime reviewStartedAt = submission == null
            ? null : submission.submittedAt();
        OffsetDateTime reviewDueAt = reviewStartedAt == null
            ? null : reviewStartedAt.plusSeconds(reviewSlaSeconds);
        long reviewAgeSeconds = reviewStartedAt == null ? 0
            : Math.max(0, java.time.Duration.between(
                reviewStartedAt.toInstant(), clock.instant()).toSeconds());
        return rows.stream()
            .filter(row -> scope.allProjects()
                || visibleProjects.contains(row.projectId()))
            .map(row -> {
                CertificationView certification =
                    hydration.certifications().get(row.id());
                boolean assigned = subject.equals(row.productOwnerSubject())
                    && decisionProjects.contains(row.projectId());
                String agingStatus;
                if (certification != null && certification.terminal()) {
                    agingStatus = "RESOLVED";
                } else if (reviewStartedAt == null) {
                    agingStatus = "NOT_STARTED";
                } else if (reviewAgeSeconds >= (long) reviewSlaSeconds * 2) {
                    agingStatus = "OVERDUE";
                } else if (reviewAgeSeconds >= reviewSlaSeconds) {
                    agingStatus = "AGING";
                } else {
                    agingStatus = "NEW";
                }
                return new DeliverableCertificationView(
                    row.id(), row.code(), row.title(), row.projectName(),
                    row.id(), row.description(), row.businessObjective(),
                    row.evidenceExpectation(), assigned,
                    assigned ? "Frozen product-owner assignment" : null,
                    reviewStartedAt, reviewDueAt, reviewAgeSeconds, agingStatus,
                    hydration.criteria().getOrDefault(row.id(), List.of()),
                    hydration.submissionItems().get(row.id()),
                    certification);
            })
            .toList();
    }

    private WorkspaceHydration workspaceHydration(
        UUID planVersionId,
        SubmissionRow submission
    ) {
        Map<UUID, List<CriterionView>> criteria = new LinkedHashMap<>();
        jdbc.query("""
            SELECT criterion.deliverable_version_id, criterion.id,
                   criterion.sequence, criterion.statement,
                   criterion.expected_result, criterion.mandatory
            FROM delivery_acceptance_criteria criterion
            JOIN delivery_deliverable_versions deliverable
              ON deliverable.id = criterion.deliverable_version_id
            WHERE deliverable.plan_version_id = ?
            ORDER BY criterion.deliverable_version_id, criterion.sequence
            """, (rs, rowNum) -> new CriterionHydration(
                rs.getObject("deliverable_version_id", UUID.class),
                new CriterionView(
                    rs.getObject("id", UUID.class), rs.getInt("sequence"),
                    rs.getString("statement"),
                    rs.getString("expected_result"),
                    rs.getBoolean("mandatory"))), planVersionId)
            .forEach(value -> criteria
                .computeIfAbsent(
                    value.deliverableVersionId(), ignored -> new ArrayList<>())
                .add(value.view()));
        if (submission == null) {
            return new WorkspaceHydration(criteria, Map.of(), Map.of());
        }

        Map<OutcomeEvidenceKey, List<SafeEvidenceReference>> evidence =
            new LinkedHashMap<>();
        jdbc.query("""
            SELECT item.outcome_id, item.criterion_id, artifact.id,
                   artifact.safe_name, artifact.classification,
                   artifact.scan_status, artifact.artifact_kind
            FROM delivery_evidence_items item
            JOIN deliverable_delivery_outcomes outcome
              ON outcome.id = item.outcome_id
             AND outcome.submission_id = ?
            JOIN evidence_artifacts artifact
              ON artifact.id = item.artifact_id
            ORDER BY item.outcome_id, item.criterion_id NULLS FIRST,
                     artifact.safe_name, artifact.id
            """, (rs, rowNum) -> {
                String scan = viewScanStatus(rs.getString("scan_status"));
                String kind = "URL_REFERENCE".equals(
                    rs.getString("artifact_kind"))
                    ? "ALLOWLISTED_URL" : "ARTIFACT";
                return new EvidenceHydration(
                    new OutcomeEvidenceKey(
                        rs.getObject("outcome_id", UUID.class),
                        rs.getObject("criterion_id", UUID.class)),
                    new SafeEvidenceReference(
                        rs.getObject("id", UUID.class),
                        rs.getString("safe_name"),
                        rs.getString("classification"), scan, kind, false));
            }, submission.id()).forEach(value -> evidence
                .computeIfAbsent(value.key(), ignored -> new ArrayList<>())
                .add(value.view()));

        Map<UUID, List<VendorCriterionResponse>> vendorResponses =
            new LinkedHashMap<>();
        jdbc.query("""
            SELECT response.outcome_id, response.criterion_id,
                   response.response_text
            FROM delivery_submission_criterion_responses response
            JOIN deliverable_delivery_outcomes outcome
              ON outcome.id = response.outcome_id
             AND outcome.submission_id = ?
            ORDER BY response.outcome_id, response.criterion_id
            """, (rs, rowNum) -> new VendorResponseHydration(
                rs.getObject("outcome_id", UUID.class),
                rs.getObject("criterion_id", UUID.class),
                rs.getString("response_text")), submission.id())
            .forEach(value -> vendorResponses
                .computeIfAbsent(value.outcomeId(), ignored -> new ArrayList<>())
                .add(new VendorCriterionResponse(
                    value.criterionId(), value.response(),
                    evidence.getOrDefault(
                        new OutcomeEvidenceKey(
                            value.outcomeId(), value.criterionId()),
                        List.of()))));

        Map<UUID, SubmissionItemView> submissionItems = new LinkedHashMap<>();
        jdbc.query("""
            SELECT id, deliverable_version_id, declared_outcome,
                   completion_percent, completion_date, delivery_summary,
                   cause_category, impact, next_action,
                   carry_forward_proposal
            FROM deliverable_delivery_outcomes
            WHERE submission_id = ?
            ORDER BY deliverable_version_id
            """, (rs, rowNum) -> new OutcomeHydration(
                rs.getObject("id", UUID.class),
                rs.getObject("deliverable_version_id", UUID.class),
                rs.getString("declared_outcome"),
                rs.getInt("completion_percent"),
                rs.getObject("completion_date", LocalDate.class),
                rs.getString("delivery_summary"),
                rs.getString("cause_category"),
                rs.getString("impact"), rs.getString("next_action"),
                rs.getString("carry_forward_proposal")), submission.id())
            .forEach(value -> submissionItems.put(
                value.deliverableVersionId(),
                new SubmissionItemView(
                    value.deliverableVersionId(),
                    viewOutcome(value.declaredOutcome()),
                    value.completionPercent(), value.completionDate(),
                    value.deliverySummary(), value.causeCategory(),
                    value.impact(), value.nextAction(),
                    value.carryForwardProposal(),
                    vendorResponses.getOrDefault(value.id(), List.of()),
                    evidence.getOrDefault(
                        new OutcomeEvidenceKey(value.id(), null), List.of()))));

        Map<UUID, List<CertificationCriterionResult>> certificationResults =
            new LinkedHashMap<>();
        jdbc.query("""
            SELECT result.certification_id, result.criterion_id,
                   result.result, result.rationale, result.evidence_viewed
            FROM certification_criterion_results result
            JOIN deliverable_certifications certification
              ON certification.id = result.certification_id
             AND certification.submission_id = ?
            ORDER BY result.certification_id, result.criterion_id
            """, (rs, rowNum) -> new CertificationResultHydration(
                rs.getObject("certification_id", UUID.class),
                new CertificationCriterionResult(
                    rs.getObject("criterion_id", UUID.class),
                    viewCriterionDecision(rs.getString("result")),
                    rs.getString("rationale"),
                    rs.getString("evidence_viewed").contains("true"))),
            submission.id()).forEach(value -> certificationResults
                .computeIfAbsent(
                    value.certificationId(), ignored -> new ArrayList<>())
                .add(value.view()));

        Map<UUID, CertificationView> certifications = new LinkedHashMap<>();
        jdbc.query("""
            SELECT DISTINCT ON (certification.deliverable_version_id)
                   certification.id, certification.deliverable_version_id,
                   round.round_number, certification.decision,
                   certification.comment, certification.observations,
                   certification.cause, certification.next_action,
                   certification.accepted_scope, certification.rejected_scope,
                   carry.next_action AS carry_forward,
                   certification.decided_by_subject, profile.display_name,
                   certification.decided_at
            FROM deliverable_certifications certification
            JOIN certification_rounds round
              ON round.id = certification.round_id
            LEFT JOIN user_profiles profile
              ON profile.identity_subject = certification.decided_by_subject
            LEFT JOIN carry_forward_links carry
              ON carry.certification_id = certification.id
            WHERE certification.submission_id = ?
            ORDER BY certification.deliverable_version_id,
                     round.round_number DESC,
                     certification.decided_at DESC,
                     certification.id
            """, (rs, rowNum) -> new CertificationHydration(
                rs.getObject("id", UUID.class),
                rs.getObject("deliverable_version_id", UUID.class),
                rs.getInt("round_number"), rs.getString("decision"),
                rs.getString("comment"), rs.getString("observations"),
                rs.getString("cause"), rs.getString("next_action"),
                rs.getString("accepted_scope"),
                rs.getString("rejected_scope"),
                rs.getString("carry_forward"),
                rs.getString("decided_by_subject"),
                rs.getString("display_name"),
                rs.getObject("decided_at", OffsetDateTime.class)),
            submission.id()).forEach(value -> {
                String decision = viewDecision(value.decision());
                certifications.put(
                    value.deliverableVersionId(),
                    new CertificationView(
                        value.id(), value.roundNumber(), decision,
                        value.comment(), value.observations(), value.cause(),
                        value.nextAction(), value.acceptedScope(),
                        value.rejectedScope(), value.carryForward(),
                        certificationResults.getOrDefault(
                            value.id(), List.of()),
                        value.displayName() == null
                            ? value.decidedBySubject() : value.displayName(),
                        value.decidedAt(),
                        TERMINAL_DECISIONS.contains(decision)));
            });
        return new WorkspaceHydration(
            criteria, submissionItems, certifications);
    }

    private int reviewSlaSeconds(UUID policyId) {
        if (policyId == null) {
            return 86_400;
        }
        Integer value = jdbc.query("""
            SELECT COALESCE(
                (reminder_policy ->> 'reviewSlaSeconds')::integer,
                86400)
            FROM certification_policy_versions
            WHERE id = ?
            """, rs -> rs.next() ? rs.getInt(1) : null, policyId);
        return value == null || value <= 0 ? 86_400 : value;
    }

    private SubmissionView submissionView(
        SubmissionRow submission,
        List<DeliverableCertificationView> deliverables
    ) {
        List<SubmissionItemView> items = deliverables.stream()
            .map(DeliverableCertificationView::vendorSubmission)
            .filter(java.util.Objects::nonNull)
            .toList();
        CertificationRound round = latestRound(submission.id());
        String status = submission.status();
        if (round != null && "AWAITING_CLARIFICATION".equals(round.status())) {
            status = "CLARIFICATION_REQUIRED";
        } else if (round != null && !"DRAFT".equals(status)) {
            status = "UNDER_REVIEW";
        }
        return new SubmissionView(
            submission.id(), submission.version(), status, submission.summary(),
            submission.declarationAccepted(), submissionBlockers(submission.id()),
            submission.createdAt(), submission.submittedAt(),
            !"DRAFT".equals(submission.status()), items);
    }

    private List<SafeEvidenceReference> availableEvidenceChoices(
        MonthRow month
    ) {
        return jdbc.query("""
            SELECT artifact.id, artifact.safe_name, artifact.classification,
                   artifact.scan_status, artifact.source
            FROM evidence_artifacts artifact
            WHERE artifact.engagement_id = ?
              AND (
                  artifact.engagement_month_id IS NULL
                  OR artifact.engagement_month_id = ?
              )
              AND artifact.retention_status IN ('ACTIVE', 'RETAINED')
              AND artifact.scan_status IN ('PASSED', 'NOT_REQUIRED')
            ORDER BY artifact.safe_name, artifact.id
            """, (rs, rowNum) -> {
                String scan = viewScanStatus(rs.getString("scan_status"));
                return new SafeEvidenceReference(
                    rs.getObject("id", UUID.class), rs.getString("safe_name"),
                    rs.getString("classification"), scan,
                    rs.getString("source"), false);
            }, month.engagementId(), month.id());
    }

    private List<ClarificationView> clarificationViews(UUID submissionId) {
        return jdbc.query("""
            SELECT question.id, round.round_number,
                   question.deliverable_version_id, question.message,
                   question.actor_subject, requester.display_name,
                   question.recorded_at,
                   response.message AS response_message,
                   response.recorded_at AS response_at
            FROM certification_clarifications question
            JOIN certification_rounds round ON round.id = question.round_id
            LEFT JOIN user_profiles requester
              ON requester.identity_subject = question.actor_subject
            LEFT JOIN certification_clarifications response
              ON response.parent_clarification_id = question.id
             AND response.kind = 'RESPONSE'
            WHERE question.submission_id = ? AND question.kind = 'QUESTION'
            ORDER BY round.round_number, question.clarification_number
            """, (rs, rowNum) -> new ClarificationView(
                rs.getObject("id", UUID.class), rs.getInt("round_number"),
                rs.getObject("deliverable_version_id", UUID.class),
                List.of(rs.getString("message")),
                rs.getString("display_name") == null
                    ? rs.getString("actor_subject") : rs.getString("display_name"),
                rs.getObject("recorded_at", OffsetDateTime.class),
                rs.getString("response_message"),
                rs.getObject("response_at", OffsetDateTime.class),
                rs.getString("response_message") == null ? "OPEN" : "RESPONDED"),
            submissionId);
    }

    private CertificationSummaryView summaryView(UUID monthId) {
        return jdbc.query("""
            SELECT summary.id, summary.version, summary.monthly_decision,
                   summary.checksum, summary.created_at, summary.observations,
                   (SELECT COUNT(*) FROM deliverable_certifications certification
                    WHERE certification.round_id = summary.round_id
                      AND certification.decision <> 'MORE_INFORMATION_REQUIRED')
                       AS terminal_count,
                   (SELECT COUNT(*) FROM deliverable_delivery_outcomes outcome
                    WHERE outcome.submission_id = summary.submission_id) AS total_count,
                   summary.status
            FROM monthly_certification_summaries summary
            WHERE summary.engagement_month_id = ? AND summary.status = 'CURRENT'
            """, rs -> rs.next()
                ? new CertificationSummaryView(
                    rs.getObject("id", UUID.class), rs.getInt("version"),
                    rs.getString("monthly_decision"), rs.getString("checksum"),
                    rs.getObject("created_at", OffsetDateTime.class),
                    rs.getString("observations"), rs.getInt("terminal_count"),
                    rs.getInt("total_count"),
                    "SUPERSEDED".equals(rs.getString("status")))
                : null, monthId);
    }

    private List<LinearSnapshotView> linearSnapshotViews(UUID planVersionId) {
        if (planVersionId == null) {
            return List.of();
        }
        List<LinearSnapshotView> values = jdbc.query("""
            SELECT snapshot.snapshot_type,
                   CASE WHEN BOOL_AND(snapshot.status = 'CAPTURED')
                        THEN 'CAPTURED'
                        WHEN BOOL_OR(snapshot.status = 'FETCH_FAILED')
                        THEN 'FETCH_FAILED' ELSE 'UNAVAILABLE' END AS status,
                   MAX(snapshot.created_at) AS captured_at
            FROM linear_issue_snapshots snapshot
            WHERE snapshot.plan_version_id = ?
            GROUP BY snapshot.snapshot_type
            ORDER BY snapshot.snapshot_type
            """, (rs, rowNum) -> new LinearSnapshotView(
                rs.getString("snapshot_type"), rs.getString("status"),
                "CAPTURED".equals(rs.getString("status")) ? "CURRENT" : "UNKNOWN",
                rs.getObject("captured_at", OffsetDateTime.class), planVersionId),
            planVersionId);
        return values;
    }

    private ConfirmationPreviewView confirmationPreview(
        MonthRow month,
        SubmissionRow submission
    ) {
        Map<String, Object> recipients = recipientSnapshot(month.planVersionId());
        List<PreviewConfirmer> candidates = month.planVersionId() == null
            ? List.of()
            : jdbc.query("""
                SELECT DISTINCT deliverable.product_owner_subject,
                       deliverable.project_id, profile.display_name,
                       project.name AS project_name
                FROM delivery_deliverable_versions deliverable
                JOIN projects project ON project.id = deliverable.project_id
                JOIN user_profiles profile
                  ON profile.identity_subject =
                     deliverable.product_owner_subject
                 AND profile.status = 'ACTIVE'
                 AND profile.principal_type = 'HUMAN'
                WHERE deliverable.plan_version_id = ?
                ORDER BY deliverable.product_owner_subject,
                         deliverable.project_id
                """, (rs, rowNum) -> new PreviewConfirmer(
                    rs.getString("product_owner_subject"),
                    rs.getObject("project_id", UUID.class),
                    rs.getString("display_name"),
                    rs.getString("project_name")),
                month.planVersionId());
        List<RecipientDisplay> eligible = candidates.stream()
            .filter(value -> authorization.hasProjectPartyPermission(
                value.subject(), month.engagementId(), value.projectId(),
                CertificationAuthorizationService.CONFIRMATION_ACT,
                Party.CLIENT))
            .map(value -> new RecipientDisplay(
                value.displayName() + " — " + value.projectName(),
                "ASSIGNED_PRODUCT_OWNER"))
            .toList();
        List<String> blockers = new ArrayList<>();
        if (!"FROZEN".equals(month.planState())
            || month.planVersionId() == null || month.baselineId() == null) {
            blockers.add("EFFECTIVE_FROZEN_PLAN_REQUIRED");
        }
        if (submission == null || submission.checksum() == null) {
            blockers.add("DELIVERY_SUBMISSION_REQUIRED");
        }
        UUID summaryId = jdbc.query("""
            SELECT id FROM monthly_certification_summaries
            WHERE engagement_month_id = ? AND status = 'CURRENT'
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            month.id());
        if (summaryId == null) {
            blockers.add("CERTIFICATION_SUMMARY_REQUIRED");
        }
        UUID attendanceId = jdbc.query("""
            SELECT id
            FROM attendance_snapshot_versions snapshot
            WHERE snapshot.engagement_month_id = ?
              AND snapshot.status = 'CLOSED'
              AND NOT EXISTS (
                  SELECT 1 FROM attendance_snapshot_versions newer
                  WHERE newer.supersedes_id = snapshot.id
              )
            ORDER BY version DESC LIMIT 1
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            month.id());
        boolean attendanceException = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM certification_attendance_exceptions
                WHERE engagement_month_id = ?
            )
            """, Boolean.class, month.id()));
        if (month.attendanceRequired()
            && attendanceId == null && !attendanceException) {
            blockers.add("CLOSED_ATTENDANCE_SNAPSHOT_REQUIRED");
        }
        if (eligible.isEmpty()) {
            blockers.add("ACTIVE_ELIGIBLE_CONFIRMER_REQUIRED");
        }
        try {
            validateRecipientCategories(recipients);
        } catch (DomainConflictException ignored) {
            blockers.add("RECIPIENT_CATEGORY_MISSING");
        }
        List<String> sourceIds = java.util.stream.Stream.of(
                attendanceId, month.planVersionId(), month.baselineId(), summaryId)
            .filter(java.util.Objects::nonNull)
            .map(UUID::toString)
            .toList();
        Long policyDue = jdbc.query("""
            SELECT confirmation_due_seconds
            FROM certification_policy_versions
            WHERE engagement_id = ? AND status = 'ACTIVE'
            """, rs -> rs.next() ? rs.getLong(1) : null,
            month.engagementId());
        long dueSeconds = policyDue == null
            ? configuration.defaultConfirmationDue().toSeconds()
            : policyDue;
        return new ConfirmationPreviewView(
            sourceIds,
            previewRecipients(recipients.get("to")),
            previewRecipients(recipients.get("cc")),
            eligible,
            "Captured policy quorum; exact eligible projects are snapshotted on request.",
            OffsetDateTime.now(clock).plusSeconds(dueSeconds),
            blockers.isEmpty(), List.copyOf(blockers));
    }

    private List<RecipientDisplay> previewRecipients(Object raw) {
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        List<RecipientDisplay> recipients = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> recipient) {
                recipients.add(new RecipientDisplay(
                    String.valueOf(recipient.get("display")),
                    String.valueOf(recipient.get("roleReason"))));
            }
        }
        return List.copyOf(recipients);
    }

    private List<ConfirmationHistoryItem> confirmationHistory(UUID monthId) {
        return jdbc.query("""
            SELECT id, version, status, due_at, requested_at, supersedes_id
            FROM business_confirmation_requests
            WHERE engagement_month_id = ?
            ORDER BY version DESC
            """, (rs, rowNum) -> new ConfirmationHistoryItem(
                rs.getObject("id", UUID.class), rs.getInt("version"),
                rs.getString("status"), rs.getObject("due_at", OffsetDateTime.class),
                rs.getObject("requested_at", OffsetDateTime.class),
                rs.getObject("supersedes_id", UUID.class)), monthId);
    }

    List<NotificationView> notificationViews(UUID monthId, UUID objectId) {
        String sql = """
            SELECT outbox.id, outbox.event_type, outbox.business_object_type,
                   outbox.transport_status, outbox.recipient_snapshot::text,
                   outbox.created_at,
                   (SELECT MAX(attempted_at)
                    FROM notification_delivery_attempts attempt
                    WHERE attempt.outbox_id = outbox.id) AS last_attempt_at,
                   (SELECT error_category
                    FROM notification_delivery_attempts attempt
                    WHERE attempt.outbox_id = outbox.id
                    ORDER BY attempt.attempt_number DESC LIMIT 1) AS error_category,
                   outbox.correlation_id
            FROM notification_outbox outbox
            WHERE outbox.engagement_month_id = ?
            """ + (objectId == null ? "" : " AND outbox.business_object_id = ?")
            + " ORDER BY outbox.created_at DESC";
        Object[] arguments = objectId == null
            ? new Object[]{monthId} : new Object[]{monthId, objectId};
        return jdbc.query(sql, (rs, rowNum) -> new NotificationView(
            rs.getObject("id", UUID.class), rs.getString("event_type"),
            rs.getString("business_object_type"), rs.getString("transport_status"),
            recipientSummary(rs.getString("recipient_snapshot")),
            rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("last_attempt_at", OffsetDateTime.class),
            rs.getString("error_category"),
            rs.getObject("correlation_id", UUID.class)), arguments);
    }

    private List<TimelineEventView> timeline(UUID monthId) {
        return jdbc.query("""
            SELECT event.id, event.event_type, event.subject_type,
                   event.actor_subject, profile.display_name, event.recorded_at,
                   event.occurred_at, event.correlation_id
            FROM certification_domain_events event
            LEFT JOIN user_profiles profile
              ON profile.identity_subject = event.actor_subject
            WHERE event.engagement_month_id = ?
            ORDER BY event.recorded_at DESC
            LIMIT 200
            """, (rs, rowNum) -> new TimelineEventView(
                rs.getObject("id", UUID.class), rs.getString("event_type"),
                rs.getString("subject_type"),
                rs.getString("display_name") == null
                    ? rs.getString("actor_subject") : rs.getString("display_name"),
                rs.getObject("recorded_at", OffsetDateTime.class),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("correlation_id", UUID.class)), monthId);
    }

    private void insertSubmissionItems(
        MonthRow month,
        UUID submissionId,
        List<SubmissionItemInput> items,
        String subject
    ) {
        Set<UUID> seen = new HashSet<>();
        for (SubmissionItemInput item : items) {
            if (!seen.add(item.deliverableId())) {
                throw new IllegalArgumentException(
                    "A deliverable appears more than once in the submission.");
            }
            requireBaselineDeliverable(month.planVersionId(), item.deliverableId());
            UUID outcomeId = UUID.randomUUID();
            String storedOutcome = storedOutcome(item);
            LocalDate proposedTarget = Set.of(
                    "PARTIALLY_COMPLETED", "NOT_COMPLETED",
                    "DEFERRED_BY_CLIENT", "DEFERRED_BY_VENDOR")
                .contains(storedOutcome)
                && item.carryForwardProposal() != null
                && !item.carryForwardProposal().isBlank()
                    ? month.monthStart().plusMonths(1) : null;
            jdbc.update("""
                INSERT INTO deliverable_delivery_outcomes
                    (id, submission_id, deliverable_version_id, declared_outcome,
                     completion_percent, completion_date, delivery_summary,
                     variance_description, cause_category, impact, next_action,
                     carry_forward_proposal, proposed_target_month,
                     linear_month_end_status, vendor_owner_declaration)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, outcomeId, submissionId, item.deliverableId(), storedOutcome,
                item.completionPercentage(), item.completionDate(), item.summary(),
                item.varianceImpact(), item.varianceCause(), item.varianceImpact(),
                item.nextAction(), item.carryForwardProposal(), proposedTarget,
                linearMonthEndStatus(month.planVersionId(), item.deliverableId()),
                "Declared by authenticated vendor authority " + subject);
            Set<UUID> criterionIds = new HashSet<>();
            for (SubmissionCriterionInput criterion : item.criterionResponses()) {
                if (!criterionIds.add(criterion.criterionId())) {
                    throw new IllegalArgumentException(
                        "A criterion appears more than once in a submission item.");
                }
                requireCriterion(item.deliverableId(), criterion.criterionId());
                jdbc.update("""
                    INSERT INTO delivery_submission_criterion_responses
                        (id, outcome_id, criterion_id, response_status, response_text)
                    VALUES (?, ?, ?, 'MET', ?)
                    """, UUID.randomUUID(), outcomeId, criterion.criterionId(),
                    criterion.response());
                insertEvidenceLinks(
                    month, outcomeId, criterion.criterionId(),
                    criterion.evidenceReferenceIds(), subject);
            }
            insertEvidenceLinks(
                month, outcomeId, null, item.evidenceReferenceIds(), subject);
        }
    }

    private void insertEvidenceLinks(
        MonthRow month,
        UUID outcomeId,
        UUID criterionId,
        List<UUID> artifactIds,
        String subject
    ) {
        for (UUID artifactId : new LinkedHashSet<>(artifactIds)) {
            Boolean valid = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM evidence_artifacts
                    WHERE id = ? AND engagement_id = ?
                      AND (engagement_month_id IS NULL OR engagement_month_id = ?)
                      AND scan_status IN ('PASSED', 'NOT_REQUIRED')
                )
                """, Boolean.class, artifactId, month.engagementId(), month.id());
            if (!Boolean.TRUE.equals(valid)) {
                throw new DomainConflictException(
                    "EVIDENCE_UNAVAILABLE",
                    "Evidence references must be scan-passed, allowlisted and in scope.");
            }
            jdbc.update("""
                INSERT INTO delivery_evidence_items
                    (id, outcome_id, criterion_id, artifact_id, evidence_type,
                     description, created_by_subject)
                VALUES (?, ?, ?, ?, 'OTHER', 'Submission evidence reference', ?)
                """, UUID.randomUUID(), outcomeId, criterionId, artifactId,
                subject);
        }
    }

    private List<String> submissionBlockers(UUID submissionId) {
        List<String> blockers = new ArrayList<>();
        SubmissionRow submission = submission(submissionId);
        if (!submission.declarationAccepted()) {
            blockers.add("VENDOR_DECLARATION_REQUIRED");
        }
        int baselineItems = jdbc.queryForObject("""
            SELECT COUNT(*) FROM delivery_deliverable_versions
            WHERE plan_version_id = ?
            """, Integer.class, submission.planVersionId());
        int submittedItems = jdbc.queryForObject("""
            SELECT COUNT(*) FROM deliverable_delivery_outcomes
            WHERE submission_id = ?
            """, Integer.class, submissionId);
        if (baselineItems != submittedItems) {
            blockers.add("EVERY_BASELINE_DELIVERABLE_REQUIRES_OUTCOME");
        }
        int incompleteCriteria = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM deliverable_delivery_outcomes outcome
            WHERE outcome.submission_id = ?
              AND (SELECT COUNT(*) FROM delivery_acceptance_criteria criterion
                   WHERE criterion.deliverable_version_id =
                         outcome.deliverable_version_id)
                  <>
                  (SELECT COUNT(*)
                   FROM delivery_submission_criterion_responses response
                   WHERE response.outcome_id = outcome.id)
            """, Integer.class, submissionId);
        if (incompleteCriteria > 0) {
            blockers.add("EVERY_ACCEPTANCE_CRITERION_REQUIRES_RESPONSE");
        }
        int missingEvidence = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM deliverable_delivery_outcomes outcome
            JOIN delivery_deliverable_versions deliverable
              ON deliverable.id = outcome.deliverable_version_id
            WHERE outcome.submission_id = ?
              AND NULLIF(BTRIM(deliverable.evidence_expectations), '') IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM delivery_evidence_items evidence
                  JOIN evidence_artifacts artifact
                    ON artifact.id = evidence.artifact_id
                  WHERE evidence.outcome_id = outcome.id
                    AND artifact.scan_status IN ('PASSED', 'NOT_REQUIRED')
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM certification_evidence_exceptions exception
                  WHERE exception.submission_id = outcome.submission_id
                    AND exception.deliverable_version_id =
                        outcome.deliverable_version_id
              )
            """, Integer.class, submissionId);
        if (missingEvidence > 0) {
            blockers.add(
                "REQUIRED_EVIDENCE_OR_AUTHORIZED_EXCEPTION_REQUIRED");
        }
        int missingCriterionEvidence = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM deliverable_delivery_outcomes outcome
            JOIN delivery_submissions submission
              ON submission.id = outcome.submission_id
            JOIN certification_policy_versions policy
              ON policy.id = submission.policy_version_id
            JOIN delivery_deliverable_versions deliverable
              ON deliverable.id = outcome.deliverable_version_id
            JOIN delivery_acceptance_criteria criterion
              ON criterion.deliverable_version_id = deliverable.id
             AND criterion.mandatory
            WHERE outcome.submission_id = ?
              AND COALESCE(
                  (policy.evidence_policy
                      ->> 'requireWhenFrozenExpectationPresent')::boolean,
                  TRUE)
              AND NULLIF(BTRIM(deliverable.evidence_expectations), '')
                  IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1
                  FROM delivery_evidence_items evidence
                  JOIN evidence_artifacts artifact
                    ON artifact.id = evidence.artifact_id
                  WHERE evidence.outcome_id = outcome.id
                    AND evidence.criterion_id = criterion.id
                    AND artifact.scan_status IN ('PASSED', 'NOT_REQUIRED')
              )
              AND NOT EXISTS (
                  SELECT 1
                  FROM certification_evidence_exceptions exception
                  WHERE exception.submission_id = submission.id
                    AND exception.deliverable_version_id = deliverable.id
                    AND exception.criterion_id = criterion.id
              )
            """, Integer.class, submissionId);
        if (missingCriterionEvidence > 0) {
            blockers.add(
                "MANDATORY_CRITERION_EVIDENCE_OR_EXCEPTION_REQUIRED");
        }
        int varianceIncomplete = jdbc.queryForObject("""
            SELECT COUNT(*) FROM deliverable_delivery_outcomes
            WHERE submission_id = ?
              AND declared_outcome <> 'COMPLETED'
              AND (cause_category IS NULL OR impact IS NULL OR next_action IS NULL)
            """, Integer.class, submissionId);
        if (varianceIncomplete > 0) {
            blockers.add("NON_SIMPLE_OUTCOME_REQUIRES_CAUSE_IMPACT_NEXT_ACTION");
        }
        int carryIncomplete = jdbc.queryForObject("""
            SELECT COUNT(*) FROM deliverable_delivery_outcomes
            WHERE submission_id = ?
              AND declared_outcome IN (
                  'PARTIALLY_COMPLETED', 'NOT_COMPLETED',
                  'DEFERRED_BY_CLIENT', 'DEFERRED_BY_VENDOR')
              AND (carry_forward_proposal IS NULL OR proposed_target_month IS NULL)
            """, Integer.class, submissionId);
        if (carryIncomplete > 0) {
            blockers.add("CARRY_FORWARD_PROPOSAL_REQUIRED");
        }
        Boolean pendingRevision = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1
                FROM delivery_plan_versions version
                WHERE version.plan_id = (
                    SELECT plan_id FROM delivery_plan_versions WHERE id = ?
                )
                  AND version.id <> ?
                  AND version.state IN (
                      'DRAFT', 'READY_FOR_REVIEW', 'PENDING_APPROVAL',
                      'CHANGES_REQUESTED')
            )
            """, Boolean.class, submission.planVersionId(), submission.planVersionId());
        if (Boolean.TRUE.equals(pendingRevision)) {
            blockers.add("PLAN_REVISION_PENDING");
        }
        return blockers;
    }

    private HashResult submissionHash(UUID submissionId) {
        SubmissionRow submission = submission(submissionId);
        List<Map<String, Object>> items = jdbc.query("""
            SELECT outcome.id, outcome.deliverable_version_id,
                   outcome.declared_outcome, outcome.completion_percent,
                   outcome.completion_date, outcome.delivery_summary,
                   outcome.limitations, outcome.variance_description,
                   outcome.cause_category, outcome.impact, outcome.next_action,
                   outcome.carry_forward_proposal, outcome.proposed_target_month,
                   outcome.linear_month_end_status
            FROM deliverable_delivery_outcomes outcome
            WHERE outcome.submission_id = ?
            ORDER BY outcome.deliverable_version_id
            """, (rs, rowNum) -> {
                UUID outcomeId = rs.getObject("id", UUID.class);
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("id", outcomeId.toString());
                value.put("deliverableVersionId",
                    rs.getObject("deliverable_version_id", UUID.class).toString());
                value.put("outcome", rs.getString("declared_outcome"));
                value.put("completionPercent", rs.getInt("completion_percent"));
                value.put("completionDate", String.valueOf(
                    rs.getObject("completion_date", LocalDate.class)));
                value.put("summary", rs.getString("delivery_summary"));
                value.put("limitations", rs.getString("limitations"));
                value.put("variance", rs.getString("variance_description"));
                value.put("cause", rs.getString("cause_category"));
                value.put("impact", rs.getString("impact"));
                value.put("nextAction", rs.getString("next_action"));
                value.put("carryForward", rs.getString("carry_forward_proposal"));
                value.put("targetMonth", String.valueOf(
                    rs.getObject("proposed_target_month", LocalDate.class)));
                value.put("linearMonthEndStatus",
                    rs.getString("linear_month_end_status"));
                value.put("criteria", jdbc.queryForList("""
                    SELECT criterion_id::text AS criterion_id,
                           response_status, response_text
                    FROM delivery_submission_criterion_responses
                    WHERE outcome_id = ?
                    ORDER BY criterion_id
                    """, outcomeId));
                value.put("evidence", jdbc.queryForList("""
                    SELECT artifact_id::text AS artifact_id
                    FROM delivery_evidence_items
                    WHERE outcome_id = ?
                    ORDER BY artifact_id
                    """, outcomeId));
                return value;
            }, submissionId);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "delivery-submission-v1");
        manifest.put("submissionId", submissionId.toString());
        manifest.put("submissionVersion", submission.version());
        manifest.put("planVersionId", submission.planVersionId().toString());
        manifest.put("baselineId", submission.baselineId().toString());
        manifest.put("baselineChecksum", submission.baselineChecksum());
        manifest.put("declarationAccepted", submission.declarationAccepted());
        manifest.put("summary", submission.summary());
        manifest.put("items", items);
        return hasher.hash(manifest);
    }

    private void validateCertification(CertificationRequest request) {
        boolean accepted = "ACCEPTED".equals(request.decision());
        if (!accepted && blank(request.comment())) {
            throw new IllegalArgumentException(
                "Non-accepted certification requires a comment.");
        }
        if (!accepted && (blank(request.cause()) || blank(request.nextAction()))) {
            throw new IllegalArgumentException(
                "Non-accepted certification requires cause and next action.");
        }
        if ("ACCEPTED_WITH_OBSERVATIONS".equals(request.decision())
            && blank(request.observations())) {
            throw new IllegalArgumentException(
                "Accepted with observations requires observations.");
        }
        if ("PARTIALLY_ACCEPTED".equals(request.decision())
            && (blank(request.acceptedScope()) || blank(request.rejectedScope())
                || blank(request.carryForward()))) {
            throw new IllegalArgumentException(
                "Partial acceptance requires accepted/rejected scope and carry-forward.");
        }
        boolean allMet = request.criterionResults().stream()
            .allMatch(result -> Set.of("MET", "NOT_APPLICABLE")
                .contains(result.decision()));
        boolean anyNotMet = request.criterionResults().stream()
            .anyMatch(result -> Set.of("NOT_MET", "PARTIALLY_MET")
                .contains(result.decision()));
        boolean inconsistent = ("ACCEPTED".equals(request.decision()) && anyNotMet)
            || ("REJECTED".equals(request.decision()) && allMet);
        if (inconsistent && blank(request.overrideRationale())) {
            throw new IllegalArgumentException(
                "Aggregate decision conflicts with criterion results; override rationale is required.");
        }
    }

    private void validateCriterionResults(
        UUID deliverableVersionId,
        List<CertificationCriterionInput> inputs
    ) {
        Set<UUID> actual = new HashSet<>();
        for (CertificationCriterionInput input : inputs) {
            if (!actual.add(input.criterionId())) {
                throw new IllegalArgumentException(
                    "A certification criterion appears more than once.");
            }
            requireCriterion(deliverableVersionId, input.criterionId());
        }
        Set<UUID> expected = new HashSet<>(jdbc.queryForList("""
            SELECT id FROM delivery_acceptance_criteria
            WHERE deliverable_version_id = ?
            """, UUID.class, deliverableVersionId));
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                "Criterion results must map exactly to the frozen criteria.");
        }
    }

    private Map<String, Object> certificationActionManifest(
        SubmissionRow submission,
        CertificationRound round,
        CertificationRequest request,
        String subject
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("schema", "deliverable-certification-action-v1");
        value.put("submissionId", submission.id().toString());
        value.put("submissionChecksum", submission.checksum());
        value.put("baselineChecksum", submission.baselineChecksum());
        value.put("round", round.roundNumber());
        value.put("deliverableVersionId", request.deliverableId().toString());
        value.put("decision", request.decision());
        value.put("comment", request.comment());
        value.put("cause", request.cause());
        value.put("nextAction", request.nextAction());
        value.put("observations", request.observations());
        value.put("acceptedScope", request.acceptedScope());
        value.put("rejectedScope", request.rejectedScope());
        value.put("overrideRationale", request.overrideRationale());
        value.put("criteria", request.criterionResults().stream()
            .sorted(Comparator.comparing(item -> item.criterionId().toString()))
            .toList());
        value.put("actorSubject", subject);
        return value;
    }

    private void createCarryForward(
        SubmissionRow submission,
        UUID deliverableVersionId,
        UUID certificationId,
        String nextAction,
        String cause,
        String subject
    ) {
        CarrySource source = jdbc.query("""
            SELECT deliverable.deliverable_id, month.engagement_id,
                   month.month_start_date
            FROM delivery_deliverable_versions deliverable
            JOIN delivery_submissions submission ON submission.id = ?
            JOIN engagement_months month ON month.id = submission.engagement_month_id
            WHERE deliverable.id = ?
              AND deliverable.plan_version_id = submission.plan_version_id
            """, rs -> rs.next()
                ? new CarrySource(
                    rs.getObject("deliverable_id", UUID.class),
                    rs.getObject("engagement_id", UUID.class),
                    rs.getObject("month_start_date", LocalDate.class))
                : null, submission.id(), deliverableVersionId);
        UUID targetMonth = jdbc.query("""
            SELECT id FROM engagement_months
            WHERE engagement_id = ? AND month_start_date = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            source.engagementId(), source.monthStart().plusMonths(1));
        if (targetMonth == null) {
            throw new DomainConflictException(
                "CARRY_FORWARD_MONTH_REQUIRED",
                "The next engagement month must exist before partial acceptance.");
        }
        jdbc.update("""
            INSERT INTO carry_forward_links
                (id, certification_id, origin_deliverable_id,
                 origin_deliverable_version_id, target_engagement_month_id,
                 cause_owner, next_action, created_by_subject)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), certificationId, source.deliverableId(),
            deliverableVersionId, targetMonth, causeOwner(cause), nextAction, subject);
    }

    private SummaryManifest certificationSummaryManifest(
        MonthRow month,
        SubmissionRow submission,
        CertificationRound round,
        SummaryRequest request
    ) {
        List<Map<String, Object>> items = jdbc.query("""
            SELECT outcome.deliverable_version_id, outcome.declared_outcome,
                   certification.id AS certification_id, certification.decision,
                   certification.decided_by_subject, certification.decided_at,
                   certification.observations, certification.action_hash
            FROM deliverable_delivery_outcomes outcome
            JOIN deliverable_certifications certification
              ON certification.submission_id = outcome.submission_id
             AND certification.deliverable_version_id =
                 outcome.deliverable_version_id
             AND certification.round_id = ?
            WHERE outcome.submission_id = ?
            ORDER BY outcome.deliverable_version_id
            """, (rs, rowNum) -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", rs.getObject(
                    "deliverable_version_id", UUID.class).toString());
                item.put("vendorOutcome", rs.getString("declared_outcome"));
                item.put("certificationId", rs.getObject(
                    "certification_id", UUID.class).toString());
                item.put("decision", rs.getString("decision"));
                item.put("decidedBy", rs.getString("decided_by_subject"));
                item.put("decidedAt", rs.getObject(
                    "decided_at", OffsetDateTime.class));
                item.put("observations", rs.getString("observations"));
                item.put("actionHash", rs.getString("action_hash"));
                return item;
            }, round.id(), submission.id());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "monthly-certification-summary-v1");
        manifest.put("engagementMonthId", month.id().toString());
        manifest.put("planVersionId", month.planVersionId().toString());
        manifest.put("baselineId", month.baselineId().toString());
        manifest.put("baselineChecksum", month.baselineChecksum());
        manifest.put("submissionId", submission.id().toString());
        manifest.put("submissionChecksum", submission.checksum());
        manifest.put("roundId", round.id().toString());
        manifest.put("monthlyDecision", request.decision());
        manifest.put("observations", request.observations());
        manifest.put("items", items);
        manifest.put("linearMonthEndStatuses", jdbc.queryForList("""
            SELECT deliverable_version_id::text AS deliverable_version_id,
                   linear_month_end_status
            FROM deliverable_delivery_outcomes
            WHERE submission_id = ?
            ORDER BY deliverable_version_id
            """, submission.id()));
        manifest.put("evidenceIndex", jdbc.queryForList("""
            SELECT artifact.id::text AS id, artifact.sha256,
                   artifact.classification, artifact.scan_status
            FROM delivery_evidence_items item
            JOIN evidence_artifacts artifact ON artifact.id = item.artifact_id
            JOIN deliverable_delivery_outcomes outcome ON outcome.id = item.outcome_id
            WHERE outcome.submission_id = ?
            ORDER BY artifact.id
            """, submission.id()));
        manifest.put("carryForward", jdbc.queryForList("""
            SELECT link.origin_deliverable_version_id::text AS origin_id,
                   link.target_engagement_month_id::text AS target_month_id,
                   link.cause_owner, link.next_action
            FROM carry_forward_links link
            JOIN deliverable_certifications certification
              ON certification.id = link.certification_id
            WHERE certification.submission_id = ?
            ORDER BY link.origin_deliverable_version_id
            """, submission.id()));
        String risks = items.stream()
            .map(item -> (String) item.get("observations"))
            .filter(value -> value != null && !value.isBlank())
            .reduce((left, right) -> left + "\n" + right)
            .orElse(null);
        return new SummaryManifest(manifest, risks);
    }

    private boolean allItemsTerminal(UUID submissionId, UUID roundId) {
        int outcomes = jdbc.queryForObject("""
            SELECT COUNT(*) FROM deliverable_delivery_outcomes
            WHERE submission_id = ?
            """, Integer.class, submissionId);
        int terminal = jdbc.queryForObject("""
            SELECT COUNT(*) FROM deliverable_certifications
            WHERE submission_id = ? AND round_id = ?
              AND decision <> 'MORE_INFORMATION_REQUIRED'
            """, Integer.class, submissionId, roundId);
        return outcomes > 0 && outcomes == terminal;
    }

    private void requireFrozenBaseline(MonthRow month) {
        if (month.planVersionId() == null || month.baselineId() == null
            || !"FROZEN".equals(month.planState())) {
            throw new DomainConflictException(
                "EFFECTIVE_FROZEN_BASELINE_REQUIRED",
                "The engagement month has no current frozen delivery baseline.");
        }
    }

    private UUID ensurePolicy(MonthRow month, String subject) {
        UUID current = jdbc.query("""
            SELECT id FROM certification_policy_versions
            WHERE engagement_id = ? AND status = 'ACTIVE'
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            month.engagementId());
        if (current != null) {
            return current;
        }
        String quorumMode = month.quorumMode() == null ? "ANY_ONE" : month.quorumMode();
        int quorumRequired = month.quorumRequired() == null
            ? 1 : month.quorumRequired();
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("schema", "certification-policy-v1");
        policy.put("engagementId", month.engagementId().toString());
        policy.put("attendanceRequired", month.attendanceRequired());
        policy.put("separationOfDutiesRequired", true);
        policy.put("monthlyDecisionRequired", true);
        policy.put("manualSecondReviewRequired", true);
        policy.put("deemedSubmissionApprovalEnabled", false);
        policy.put("deemedCertificationApprovalEnabled", false);
        policy.put("deemedConfirmationApprovalEnabled", false);
        policy.put("quorumMode", quorumMode);
        policy.put("quorumRequired", quorumRequired);
        policy.put("tokenTtlSeconds", configuration.tokenTtl().toSeconds());
        policy.put("confirmationDueSeconds",
            configuration.defaultConfirmationDue().toSeconds());
        policy.put("reviewSlaSeconds", 86_400);
        policy.put("evidencePolicy", Map.of(
            "requireWhenFrozenExpectationPresent", true,
            "allowedScanStatuses", List.of("PASSED", "NOT_REQUIRED"),
            "authorizedExceptionRequired", true));
        HashResult hash = hasher.hash(policy);
        UUID policyId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO certification_policy_versions
                (id, engagement_id, version, status, attendance_required,
                 separation_of_duties_required, monthly_decision_required,
                 manual_second_review_required,
                 deemed_submission_approval_enabled,
                 deemed_certification_approval_enabled,
                 deemed_confirmation_approval_enabled, quorum_mode,
                 quorum_required, token_ttl_seconds,
                 confirmation_due_seconds, reminder_policy, evidence_policy,
                 recipient_policy, retention_policy, policy_hash,
                 created_by_subject)
            VALUES (?, ?, 1, 'ACTIVE', ?, TRUE, TRUE, TRUE, FALSE, FALSE,
                    FALSE, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb,
                    ?::jsonb, ?, ?)
            """, policyId, month.engagementId(), month.attendanceRequired(),
            quorumMode, quorumRequired, configuration.tokenTtl().toSeconds(),
            configuration.defaultConfirmationDue().toSeconds(),
            "{\"stages\":[],\"reviewSlaSeconds\":86400,"
                + "\"configuration\":\"LOCAL_DEFAULT\"}",
            "{\"artifactProvider\":\"" + configuration.objectStorageProviderStatus()
                + "\",\"requireWhenFrozenExpectationPresent\":true,"
                + "\"allowedScanStatuses\":[\"PASSED\",\"NOT_REQUIRED\"],"
                + "\"authorizedExceptionRequired\":true}",
            "{\"source\":\"FROZEN_PLAN_RECIPIENT_SNAPSHOT\"}",
            "{\"status\":\"ACTION_REQUIRED\"}",
            hash.checksum(), subject);
        return policyId;
    }

    private MonthRow lockMonth(UUID monthId) {
        MonthRow month = queryMonth(monthId, true);
        if (month == null) {
            throw notFound();
        }
        return month;
    }

    MonthRow month(UUID monthId) {
        MonthRow month = queryMonth(monthId, false);
        if (month == null) {
            throw notFound();
        }
        return month;
    }

    private MonthRow queryMonth(UUID monthId, boolean lock) {
        return jdbc.query("""
            SELECT month.id, month.engagement_id, month.month_start_date,
                   month.state, month.certification_version,
                   engagement.attendance_required,
                   plan.current_version_id AS plan_version_id,
                   version.state AS plan_state, version.quorum_mode,
                   version.quorum_required,
                   baseline.id AS baseline_id, baseline.checksum AS baseline_checksum
            FROM engagement_months month
            JOIN engagements engagement ON engagement.id = month.engagement_id
            LEFT JOIN delivery_plans plan ON plan.engagement_month_id = month.id
            LEFT JOIN delivery_plan_versions version
              ON version.id = plan.current_version_id
            LEFT JOIN delivery_plan_baselines baseline
              ON baseline.plan_version_id = version.id
            WHERE month.id = ?
            """ + (lock ? " FOR UPDATE OF month" : ""),
            rs -> rs.next() ? new MonthRow(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_id", UUID.class),
                rs.getObject("month_start_date", LocalDate.class),
                rs.getString("state"), rs.getLong("certification_version"),
                rs.getBoolean("attendance_required"),
                rs.getObject("plan_version_id", UUID.class),
                rs.getString("plan_state"),
                rs.getString("quorum_mode"),
                (Integer) rs.getObject("quorum_required"),
                rs.getObject("baseline_id", UUID.class),
                rs.getString("baseline_checksum")) : null, monthId);
    }

    private SubmissionRow lockSubmission(UUID submissionId) {
        SubmissionRow value = querySubmission("""
            WHERE submission.id = ? FOR UPDATE OF submission
            """, submissionId);
        if (value == null) {
            throw notFound();
        }
        return value;
    }

    private SubmissionRow submission(UUID submissionId) {
        SubmissionRow value = querySubmission(
            "WHERE submission.id = ?", submissionId);
        if (value == null) {
            throw notFound();
        }
        return value;
    }

    private SubmissionRow currentSubmission(UUID monthId) {
        return querySubmission("""
            WHERE submission.engagement_month_id = ?
              AND submission.status IN ('DRAFT', 'SUBMITTED', 'UNDER_REVIEW')
            """, monthId);
    }

    private UUID submissionMonthId(UUID submissionId) {
        UUID monthId = jdbc.query("""
            SELECT engagement_month_id
            FROM delivery_submissions
            WHERE id = ?
            """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
            submissionId);
        if (monthId == null) {
            throw notFound();
        }
        return monthId;
    }

    private SubmissionRow querySubmission(String predicate, UUID value) {
        return jdbc.query("""
            SELECT submission.id, submission.engagement_month_id,
                   submission.plan_version_id, submission.baseline_id,
                   submission.policy_version_id, submission.version,
                   submission.status, submission.summary,
                   submission.vendor_declaration_accepted,
                   submission.checksum, submission.optimistic_version,
                   submission.created_at, submission.submitted_at,
                   baseline.checksum AS baseline_checksum
            FROM delivery_submissions submission
            JOIN delivery_plan_baselines baseline ON baseline.id = submission.baseline_id
            """ + predicate,
            rs -> rs.next() ? new SubmissionRow(
                rs.getObject("id", UUID.class),
                rs.getObject("engagement_month_id", UUID.class),
                rs.getObject("plan_version_id", UUID.class),
                rs.getObject("baseline_id", UUID.class),
                rs.getObject("policy_version_id", UUID.class),
                rs.getInt("version"), rs.getString("status"),
                rs.getString("summary"),
                rs.getBoolean("vendor_declaration_accepted"),
                rs.getString("checksum"), rs.getLong("optimistic_version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("submitted_at", OffsetDateTime.class),
                rs.getString("baseline_checksum")) : null, value);
    }

    private int nextSubmissionVersion(UUID monthId) {
        return jdbc.queryForObject("""
            SELECT COALESCE(MAX(version), 0) + 1
            FROM delivery_submissions WHERE engagement_month_id = ?
            """, Integer.class, monthId);
    }

    private CertificationRound lockCurrentRound(UUID submissionId) {
        CertificationRound round = jdbc.query("""
            SELECT id, round_number, status
            FROM certification_rounds
            WHERE submission_id = ?
              AND status IN ('OPEN', 'AWAITING_CLARIFICATION')
            FOR UPDATE
            """, rs -> rs.next() ? new CertificationRound(
                rs.getObject("id", UUID.class), rs.getInt("round_number"),
                rs.getString("status")) : null, submissionId);
        if (round == null) {
            throw new DomainConflictException(
                "CERTIFICATION_ROUND_NOT_OPEN",
                "The submission has no open certification round.");
        }
        return round;
    }

    private CertificationRound latestRound(UUID submissionId) {
        return jdbc.query("""
            SELECT id, round_number, status
            FROM certification_rounds
            WHERE submission_id = ?
            ORDER BY round_number DESC LIMIT 1
            """, rs -> rs.next() ? new CertificationRound(
                rs.getObject("id", UUID.class), rs.getInt("round_number"),
                rs.getString("status")) : null, submissionId);
    }

    private SummaryRow currentSummary(UUID monthId) {
        return jdbc.query("""
            SELECT id, version, monthly_decision, checksum
            FROM monthly_certification_summaries
            WHERE engagement_month_id = ? AND status = 'CURRENT'
            """, rs -> rs.next() ? new SummaryRow(
                rs.getObject("id", UUID.class), rs.getInt("version"),
                rs.getString("monthly_decision"), rs.getString("checksum"))
                : null, monthId);
    }

    private void requireBaselineDeliverable(UUID planVersionId, UUID deliverableId) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM delivery_deliverable_versions
                WHERE id = ? AND plan_version_id = ?
            )
            """, Boolean.class, deliverableId, planVersionId);
        if (!Boolean.TRUE.equals(exists)) {
            throw notFound();
        }
    }

    private void requireCriterion(UUID deliverableId, UUID criterionId) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM delivery_acceptance_criteria
                WHERE id = ? AND deliverable_version_id = ?
            )
            """, Boolean.class, criterionId, deliverableId);
        if (!Boolean.TRUE.equals(exists)) {
            throw notFound();
        }
    }

    private void requireOutcome(UUID submissionId, UUID deliverableId) {
        Boolean exists = jdbc.queryForObject("""
            SELECT EXISTS (
                SELECT 1 FROM deliverable_delivery_outcomes
                WHERE submission_id = ? AND deliverable_version_id = ?
            )
            """, Boolean.class, submissionId, deliverableId);
        if (!Boolean.TRUE.equals(exists)) {
            throw notFound();
        }
    }

    private ClarificationParent clarificationParent(
        UUID roundId,
        UUID deliverableId,
        UUID requestedId
    ) {
        return jdbc.query("""
            SELECT id FROM certification_clarifications
            WHERE round_id = ? AND deliverable_version_id = ?
              AND kind = 'QUESTION'
              AND (?::uuid IS NULL OR id = ?::uuid)
            ORDER BY clarification_number DESC LIMIT 1
            """, rs -> {
                if (!rs.next()) {
                    throw new DomainConflictException(
                        "CLARIFICATION_QUESTION_REQUIRED",
                        "No matching open clarification question exists.");
                }
                return new ClarificationParent(rs.getObject(1, UUID.class));
            }, roundId, deliverableId, requestedId, requestedId);
    }

    private int nextClarificationNumber(UUID roundId, UUID deliverableId) {
        return jdbc.queryForObject("""
            SELECT COALESCE(MAX(clarification_number), 0) + 1
            FROM certification_clarifications
            WHERE round_id = ? AND deliverable_version_id = ?
            """, Integer.class, roundId, deliverableId);
    }

    private String linearMonthEndStatus(
        UUID planVersionId,
        UUID deliverableVersionId
    ) {
        int links = jdbc.queryForObject("""
            SELECT COUNT(*) FROM linear_issue_links
            WHERE deliverable_version_id = ?
            """, Integer.class, deliverableVersionId);
        if (links == 0) {
            return "UNAVAILABLE";
        }
        int captured = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM linear_issue_snapshots snapshot
            JOIN linear_issue_links link ON link.id = snapshot.issue_link_id
            WHERE link.deliverable_version_id = ?
              AND snapshot.plan_version_id = ?
              AND snapshot.snapshot_type = 'MONTH_END'
              AND snapshot.status = 'CAPTURED'
            """, Integer.class, deliverableVersionId, planVersionId);
        return captured == links ? "CAPTURED" : "FETCH_FAILED";
    }

    UUID enqueueNotification(
        MonthRow month,
        String eventType,
        String objectType,
        UUID objectId,
        int objectVersion,
        String templateKey,
        String subject,
        String sourceHash,
        UUID correlationId
    ) {
        Map<String, Object> recipients = recipientSnapshot(month.planVersionId());
        return enqueueNotification(
            month, eventType, objectType, objectId, objectVersion,
            templateKey, subject, sourceHash, correlationId, recipients, true);
    }

    UUID enqueueSecureTokenNotification(
        MonthRow month,
        UUID tokenId,
        int requestVersion,
        String eligibleEmail,
        String sourceHash,
        UUID correlationId
    ) {
        Map<String, Object> recipients = Map.of(
            "to", List.of(Map.of(
                "display", eligibleEmail.toLowerCase(Locale.ROOT),
                "roleReason", "ELIGIBLE_CONFIRMER")),
            "cc", List.of());
        return enqueueNotification(
            month, "CONFIRMATION_SECURE_LINK_ISSUED",
            "confirmation_secure_token", tokenId, requestVersion,
            "confirmation-secure-link-v1",
            "Monthly business confirmation action required",
            sourceHash, correlationId, recipients, false);
    }

    private UUID enqueueNotification(
        MonthRow month,
        String eventType,
        String objectType,
        UUID objectId,
        int objectVersion,
        String templateKey,
        String subject,
        String sourceHash,
        UUID correlationId,
        Map<String, Object> recipients,
        boolean requireAllRecipientCategories
    ) {
        if (requireAllRecipientCategories) {
            validateRecipientCategories(recipients);
        }
        String plain = subject + "\nEngagement month: " + month.monthStart()
            + "\nSource checksum: " + sourceHash
            + "\nA request-bound action link may be added only by the secure dispatcher."
            + "\nTransport status is separate from business approval.";
        String html = "<article><h1>" + escapeHtml(subject)
            + "</h1><p>Engagement month: " + month.monthStart()
            + "</p><p>Source checksum: <code>" + escapeHtml(sourceHash)
            + "</code></p><p>A request-bound action link may be added only by "
            + "the secure dispatcher.</p><p>Transport status is separate from "
            + "business approval.</p></article>";
        String bodyHash = hasher.sha256(plain + "\n" + html);
        String archiveHash = hasher.hash(Map.of(
            "template", templateKey,
            "templateVersion", 1,
            "recipients", recipients,
            "bodyHash", bodyHash,
            "sourceHash", sourceHash)).checksum();
        UUID outboxId = UUID.randomUUID();
        String providerStatus = emailAdapter.configurationStatus();
        String transport = "CONFIGURED".equals(providerStatus)
            ? "QUEUED" : "NOT_CONFIGURED";
        jdbc.update("""
            INSERT INTO notification_outbox
                (id, engagement_month_id, event_type, business_object_type,
                 business_object_id, business_object_version, idempotency_key,
                 correlation_id, template_key, template_version,
                 recipient_snapshot, subject_text, plain_text, html_text,
                 rendered_body_hash, archive_manifest_hash, provider_status,
                 transport_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """, outboxId, month.id(), eventType, objectType, objectId,
            Math.max(1, objectVersion),
            eventType + ":" + objectId + ":" + objectVersion,
            correlationId, templateKey, json(recipients), subject, plain, html,
            bodyHash, archiveHash, providerStatus, transport);
        return outboxId;
    }

    Map<String, Object> recipientSnapshot(UUID planVersionId) {
        if (planVersionId == null) {
            return Map.of("to", List.of(), "cc", List.of());
        }
        return jdbc.query("""
            SELECT arrow_foundry::text, reliance_stakeholders::text,
                   procurement_cc::text
            FROM delivery_recipient_snapshots
            WHERE plan_version_id = ?
            """, rs -> {
                if (!rs.next()) {
                    return Map.of("to", List.of(), "cc", List.of());
                }
                List<Map<String, String>> to = new ArrayList<>();
                addRecipients(to, stringList(rs.getString(1)), "VENDOR_STAKEHOLDER");
                addRecipients(to, stringList(rs.getString(2)), "CLIENT_STAKEHOLDER");
                List<Map<String, String>> cc = new ArrayList<>();
                addRecipients(cc, stringList(rs.getString(3)), "CENTRAL_PROCUREMENT");
                return dedupeRecipientSnapshot(to, cc);
            }, planVersionId);
    }

    private Map<String, Object> dedupeRecipientSnapshot(
        List<Map<String, String>> to,
        List<Map<String, String>> cc
    ) {
        Set<String> toDisplays = to.stream()
            .map(value -> value.get("display"))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Map<String, String>> all = new ArrayList<>(to);
        all.addAll(cc);
        List<Map<String, String>> merged = dedupeRecipients(all);
        return Map.of(
            "to", merged.stream()
                .filter(value -> toDisplays.contains(value.get("display")))
                .toList(),
            "cc", merged.stream()
                .filter(value -> !toDisplays.contains(value.get("display")))
                .toList());
    }

    private void validateRecipientCategories(Map<String, Object> snapshot) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> to =
            (List<Map<String, String>>) snapshot.getOrDefault("to", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, String>> cc =
            (List<Map<String, String>>) snapshot.getOrDefault("cc", List.of());
        Set<String> reasons = new HashSet<>();
        java.util.stream.Stream.concat(to.stream(), cc.stream())
            .map(value -> value.get("roleReason"))
            .filter(java.util.Objects::nonNull)
            .flatMap(value -> java.util.Arrays.stream(value.split(",")))
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .forEach(reasons::add);
        if (!reasons.containsAll(Set.of(
            "VENDOR_STAKEHOLDER", "CLIENT_STAKEHOLDER", "CENTRAL_PROCUREMENT"))) {
            throw new DomainConflictException(
                "RECIPIENT_CATEGORY_MISSING",
                "Vendor, client and Central Procurement recipient categories are required.");
        }
    }

    private void addRecipients(
        List<Map<String, String>> target,
        List<String> addresses,
        String reason
    ) {
        addresses.forEach(address -> target.add(Map.of(
            "display", address.toLowerCase(Locale.ROOT),
            "roleReason", reason)));
    }

    private List<Map<String, String>> dedupeRecipients(
        List<Map<String, String>> values
    ) {
        Map<String, Set<String>> reasons = new LinkedHashMap<>();
        values.forEach(value -> reasons
            .computeIfAbsent(value.get("display"), ignored -> new LinkedHashSet<>())
            .add(value.get("roleReason")));
        return reasons.entrySet().stream()
            .map(entry -> Map.of(
                "display", entry.getKey(),
                "roleReason", String.join(",", entry.getValue())))
            .toList();
    }

    private UUID audit(
        UUID monthId,
        String eventType,
        String actor,
        String objectType,
        UUID objectId,
        Integer objectVersion,
        String source,
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
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
            """, auditId, monthId, eventType, actor,
            json(Map.of("resolvedServerSide", true)), objectType, objectId,
            objectVersion, source, reason, result, correlationId, policyId);
        return auditId;
    }

    private void event(
        UUID monthId,
        String eventType,
        String actor,
        String subjectType,
        UUID subjectId,
        Integer version,
        UUID correlationId,
        Map<String, ?> payload
    ) {
        jdbc.update("""
            INSERT INTO certification_domain_events
                (id, engagement_month_id, event_type, actor_type, actor_subject,
                 subject_type, subject_id, subject_version, correlation_id, payload)
            VALUES (?, ?, ?, 'USER', ?, ?, ?, ?, ?, ?::jsonb)
            """, UUID.randomUUID(), monthId, eventType, actor, subjectType,
            subjectId, version, correlationId, json(payload));
    }

    private String authoritySnapshot(
        String subject,
        UUID monthId,
        UUID projectId,
        String permission
    ) {
        return json(Map.of(
            "actorSubject", subject,
            "engagementMonthId", monthId.toString(),
            "projectId", projectId == null ? "" : projectId.toString(),
            "permission", permission,
            "resolvedServerSide", true,
            "capturedAt", OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                .toString()));
    }

    private UUID priorResult(
        String actor,
        String operation,
        UUID scopeId,
        String key,
        String requestHash
    ) {
        IdempotencyRow prior = jdbc.query("""
            SELECT request_hash, result_id
            FROM certification_idempotency_keys
            WHERE actor_subject = ? AND operation = ?
              AND scope_id = ? AND idempotency_key = ?
            """, rs -> rs.next()
                ? new IdempotencyRow(rs.getString(1), rs.getObject(2, UUID.class))
                : null, actor, operation, scopeId, key);
        if (prior == null) {
            return null;
        }
        if (!prior.requestHash().equals(requestHash)) {
            throw new DomainConflictException(
                "IDEMPOTENCY_KEY_REUSED",
                "The idempotency key was already used with different input.");
        }
        return prior.resultId();
    }

    private void recordIdempotency(
        String actor,
        String operation,
        UUID scopeId,
        String key,
        String requestHash,
        String resultType,
        UUID resultId
    ) {
        jdbc.update("""
            INSERT INTO certification_idempotency_keys
                (id, actor_subject, operation, scope_id, idempotency_key,
                 request_hash, result_type, result_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, UUID.randomUUID(), actor, operation, scopeId, key,
            requestHash, resultType, resultId);
    }

    private void bumpMonth(UUID monthId, String newState) {
        if (newState == null) {
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
                """, newState, monthId);
        }
    }

    private String requestHash(Object request) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schema", "f04-api-request-v1");
        manifest.put("request", request);
        return hasher.hash(manifest).checksum();
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

    private void requireVersion(long current, long expected, String code) {
        if (current != expected) {
            throw conflict(code, "The resource version is stale.", current);
        }
    }

    private void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 160) {
            throw new IllegalArgumentException(
                "Idempotency-Key is required and must not exceed 160 characters.");
        }
    }

    private String storedOutcome(SubmissionItemInput input) {
        return switch (input.outcome()) {
            case "COMPLETED" -> "COMPLETED";
            case "PARTIALLY_COMPLETED" -> "PARTIALLY_COMPLETED";
            case "DEFERRED" -> input.varianceCause() != null
                && input.varianceCause().toUpperCase(Locale.ROOT).contains("CLIENT")
                    ? "DEFERRED_BY_CLIENT" : "DEFERRED_BY_VENDOR";
            case "NOT_COMPLETED" -> "NOT_COMPLETED";
            case "CANCELLED_BY_APPROVED_CHANGE" -> "CANCELLED_BY_APPROVED_CHANGE";
            default -> throw new IllegalArgumentException("Unsupported delivery outcome.");
        };
    }

    private String viewOutcome(String stored) {
        return switch (stored) {
            case "DEFERRED_BY_CLIENT", "DEFERRED_BY_VENDOR" -> "DEFERRED";
            case "COMPLETED_WITH_VARIANCE" -> "COMPLETED";
            default -> stored;
        };
    }

    private String storedDecision(String view) {
        return switch (view) {
            case "CLIENT_DEPENDENCY_DEFERRED" -> "DEFERRED_CLIENT_DEPENDENCY";
            case "VENDOR_DEPENDENCY_DEFERRED" -> "DEFERRED_VENDOR_DEPENDENCY";
            default -> view;
        };
    }

    private String viewDecision(String stored) {
        return switch (stored) {
            case "DEFERRED_CLIENT_DEPENDENCY" -> "CLIENT_DEPENDENCY_DEFERRED";
            case "DEFERRED_VENDOR_DEPENDENCY" -> "VENDOR_DEPENDENCY_DEFERRED";
            default -> stored;
        };
    }

    private String storedCriterionDecision(String view) {
        return switch (view) {
            case "MET" -> "ACCEPTED";
            case "PARTIALLY_MET" -> "PARTIAL";
            case "NOT_MET" -> "REJECTED";
            case "NOT_APPLICABLE" -> "NOT_APPLICABLE";
            default -> throw new IllegalArgumentException(
                "Unsupported criterion decision.");
        };
    }

    private String viewCriterionDecision(String stored) {
        return switch (stored) {
            case "ACCEPTED" -> "MET";
            case "PARTIAL" -> "PARTIALLY_MET";
            case "REJECTED" -> "NOT_MET";
            default -> stored;
        };
    }

    private String viewScanStatus(String stored) {
        return switch (stored) {
            case "NOT_REQUIRED", "PASSED" -> "PASSED";
            case "FAILED" -> "QUARANTINED";
            default -> stored;
        };
    }

    private String causeOwner(String cause) {
        if (cause == null) {
            return "JOINT";
        }
        String normalized = cause.toUpperCase(Locale.ROOT);
        if (normalized.contains("CLIENT")) {
            return "CLIENT";
        }
        if (normalized.contains("VENDOR")) {
            return "VENDOR";
        }
        if (normalized.contains("EXTERNAL")) {
            return "EXTERNAL";
        }
        return "JOINT";
    }

    private boolean packageAffected(String value) {
        return value != null
            && !value.equalsIgnoreCase("NONE")
            && !value.equalsIgnoreCase("NOT_SUBMITTED");
    }

    private String recipientSummary(String storedJson) {
        try {
            Map<String, Object> value = objectMapper.readValue(
                storedJson, new TypeReference<>() {
                });
            int to = value.get("to") instanceof List<?> values ? values.size() : 0;
            int cc = value.get("cc") instanceof List<?> values ? values.size() : 0;
            return to + " To / " + cc + " CC";
        } catch (JacksonException exception) {
            return "Restricted recipient snapshot";
        }
    }

    private List<String> stringList(String storedJson) {
        try {
            return objectMapper.readValue(storedJson, new TypeReference<>() {
            });
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored recipient JSON is invalid.", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize F04 data.", exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String uuidString(UUID value) {
        return value == null ? null : value.toString();
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    private DomainConflictException conflict(
        String code,
        String message,
        long currentVersion
    ) {
        return new DomainConflictException(code, message, currentVersion);
    }

    private EntityNotFoundException notFound() {
        return new EntityNotFoundException("Resource not found.");
    }

    record MonthRow(
        UUID id,
        UUID engagementId,
        LocalDate monthStart,
        String state,
        long certificationVersion,
        boolean attendanceRequired,
        UUID planVersionId,
        String planState,
        String quorumMode,
        Integer quorumRequired,
        UUID baselineId,
        String baselineChecksum
    ) {
    }

    record SubmissionRow(
        UUID id,
        UUID monthId,
        UUID planVersionId,
        UUID baselineId,
        UUID policyVersionId,
        int version,
        String status,
        String summary,
        boolean declarationAccepted,
        String checksum,
        long optimisticVersion,
        OffsetDateTime createdAt,
        OffsetDateTime submittedAt,
        String baselineChecksum
    ) {
    }

    record CertificationRound(UUID id, int roundNumber, String status) {
    }

    record SummaryRow(UUID id, int version, String decision, String checksum) {
    }

    private record DeliverableRow(
        UUID id,
        String code,
        String title,
        String projectName,
        UUID projectId,
        String productOwnerSubject,
        String description,
        String businessObjective,
        String evidenceExpectation
    ) {
    }

    private record WorkspaceHydration(
        Map<UUID, List<CriterionView>> criteria,
        Map<UUID, SubmissionItemView> submissionItems,
        Map<UUID, CertificationView> certifications
    ) {
    }

    private record CriterionHydration(
        UUID deliverableVersionId,
        CriterionView view
    ) {
    }

    private record OutcomeEvidenceKey(UUID outcomeId, UUID criterionId) {
    }

    private record EvidenceHydration(
        OutcomeEvidenceKey key,
        SafeEvidenceReference view
    ) {
    }

    private record VendorResponseHydration(
        UUID outcomeId,
        UUID criterionId,
        String response
    ) {
    }

    private record OutcomeHydration(
        UUID id,
        UUID deliverableVersionId,
        String declaredOutcome,
        int completionPercent,
        LocalDate completionDate,
        String deliverySummary,
        String causeCategory,
        String impact,
        String nextAction,
        String carryForwardProposal
    ) {
    }

    private record CertificationResultHydration(
        UUID certificationId,
        CertificationCriterionResult view
    ) {
    }

    private record CertificationHydration(
        UUID id,
        UUID deliverableVersionId,
        int roundNumber,
        String decision,
        String comment,
        String observations,
        String cause,
        String nextAction,
        String acceptedScope,
        String rejectedScope,
        String carryForward,
        String decidedBySubject,
        String displayName,
        OffsetDateTime decidedAt
    ) {
    }

    private record ClarificationParent(UUID id) {
    }

    private record CarrySource(
        UUID deliverableId,
        UUID engagementId,
        LocalDate monthStart
    ) {
    }

    private record PreviewConfirmer(
        String subject,
        UUID projectId,
        String displayName,
        String projectName
    ) {
    }

    private record SummaryManifest(Map<String, Object> manifest, String risks) {
    }

    private record ClosureSources(
        UUID requestId,
        int requestVersion,
        String scopeChecksum,
        UUID policyVersionId,
        UUID attendanceSnapshotId,
        UUID attendanceExceptionId,
        UUID planVersionId,
        UUID baselineId,
        String baselineChecksum,
        UUID submissionId,
        String submissionChecksum,
        UUID summaryId,
        int summaryVersion,
        String summaryChecksum,
        UUID readinessRunId,
        String readinessInputHash,
        boolean readyForF05,
        UUID handoffId,
        String f05PackageHash
    ) {
    }

    private record ClosureLineage(UUID id, int version, String status) {
    }

    private record ReopenSource(
        UUID id,
        UUID monthId,
        UUID closureId,
        String requestedBySubject,
        UUID decisionId,
        UUID policyVersionId
    ) {
    }

    private record InvalidationSource(
        UUID id,
        UUID monthId,
        String objectType,
        UUID objectId,
        OffsetDateTime createdAt,
        UUID resolutionId,
        UUID policyVersionId
    ) {
    }

    private record CorrectionEvidence(
        String objectType,
        UUID objectId,
        int objectVersion,
        UUID requestId,
        String scopeChecksum,
        OffsetDateTime confirmedAt
    ) {
    }

    private record ReopenImpact(String objectType, UUID objectId) {
    }

    private record F05InvalidationNotice(
        UUID invalidationId,
        UUID handoffId,
        UUID confirmationRequestId,
        String packageHash
    ) {
    }

    private record IdempotencyRow(String requestHash, UUID resultId) {
    }
}
